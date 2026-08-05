package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.NovelInterface
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.parsers.novel.NovelLoadResult
import ani.dantotsu.util.Logger
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AnimeLoadResult
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaLoadResult
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import eu.kanade.tachiyomi.util.system.getApplicationIcon
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.Locale

internal object ExtensionLoader {

    private val preferences: SourcePreferences by injectLazy()

    private val loadNsfwSource by lazy {
        preferences.showNsfwSource().get()
    }

    private const val ANIME_PACKAGE = "tachiyomi.animeextension"
    private const val MANGA_PACKAGE = "tachiyomi.extension"

    private const val XX_METADATA_SOURCE_CLASS = ".class"
    private const val XX_METADATA_SOURCE_FACTORY = ".factory"
    private const val XX_METADATA_NSFW = "n.nsfw"
    private const val XX_METADATA_HAS_README = ".hasReadme"
    private const val XX_METADATA_HAS_CHANGELOG = ".hasChangelog"

    const val ANIME_LIB_VERSION_MIN = 12
    const val ANIME_LIB_VERSION_MAX = 20

    const val MANGA_LIB_VERSION_MIN = 1.2
    const val MANGA_LIB_VERSION_MAX = 2.0

    val PACKAGE_FLAGS =
        PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES or
            (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    PackageManager.GET_SIGNING_CERTIFICATES
                else 0
            )

    private const val PRIVATE_EXTENSION_EXTENSION = "ext"

    private fun getPrivateExtensionDir(context: Context): File {
        return File(context.filesDir, "exts")
    }

    private fun File.copyAndSetReadOnlyTo(target: File): File {
        if (!exists()) {
            throw NoSuchFileException(this)
        }
        if (target.exists() && !target.delete()) {
            throw FileAlreadyExistsException(target)
        }
        target.parentFile?.mkdirs()
        inputStream().use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target.setReadOnly()
        return target
    }

    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo!!
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
            ?.map { Hash.sha256(it.toByteArray()) }
            ?.toList()
    }

    fun installPrivateExtensionFile(context: Context, file: File, type: MediaType): Boolean {
        val extension = context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            ?.takeIf { isPackageAnExtension(type, it) } ?: return false
        val currentExtension = getExtensionPackageInfoFromPkgName(context, extension.packageName, type)

        if (currentExtension != null) {
            if (PackageInfoCompat.getLongVersionCode(extension) <
                PackageInfoCompat.getLongVersionCode(currentExtension)
            ) {
                Logger.log("Installed extension version is higher. Downgrading is not allowed.")
                return false
            }

            val extensionSignatures = getSignatures(extension)
            if (extensionSignatures.isNullOrEmpty()) {
                Logger.log("Extension to be installed is not signed.")
                return false
            }

            if (!extensionSignatures.containsAll(getSignatures(currentExtension)!!)) {
                Logger.log("Installed extension signature is not matched.")
                return false
            }
        }

        val target = File(getPrivateExtensionDir(context), "${extension.packageName}.$PRIVATE_EXTENSION_EXTENSION")
        return try {
            target.delete()
            file.copyAndSetReadOnlyTo(target)
            true
        } catch (e: Exception) {
            Logger.log("Failed to install private extension: $e")
            false
        }
    }

    fun uninstallPrivateExtension(context: Context, pkgName: String) {
        val file = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        if (file.exists()) {
            file.delete()
        }
    }

    private fun selectExtensionPackage(shared: ExtensionInfo?, private: ExtensionInfo?): ExtensionInfo? {
        when {
            private == null && shared != null -> return shared
            shared == null && private != null -> return private
            shared == null && private == null -> return null
        }

        return if (PackageInfoCompat.getLongVersionCode(shared!!.packageInfo) >=
            PackageInfoCompat.getLongVersionCode(private!!.packageInfo)
        ) {
            shared
        } else {
            private
        }
    }

    private fun getExtensionInfoFromPkgName(context: Context, pkgName: String, type: MediaType): ExtensionInfo? {
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        val privatePkg = if (privateExtensionFile.isFile) {
            context.packageManager.getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
                ?.takeIf { isPackageAnExtension(type, it) }
                ?.let {
                    it.applicationInfo!!.fixBasePaths(privateExtensionFile.absolutePath)
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = false,
                    )
                }
        } else {
            null
        }

        val sharedPkg = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
                .takeIf { isPackageAnExtension(type, it) }
                ?.let {
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = true,
                    )
                }
        } catch (error: PackageManager.NameNotFoundException) {
            null
        }

        return selectExtensionPackage(sharedPkg, privatePkg)
    }

    fun getExtensionPackageInfoFromPkgName(context: Context, pkgName: String, type: MediaType): PackageInfo? {
        return getExtensionInfoFromPkgName(context, pkgName, type)?.packageInfo
    }

    private fun getInstalledExtensionPackages(context: Context, type: MediaType): List<ExtensionInfo> {
        val manager = context.packageManager

        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val sharedExtensions = installed.asSequence()
            .filter { isPackageAnExtension(type, it) }
            .map { ExtensionInfo(packageInfo = it, isShared = true) }

        val privateExtensions = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            ?.mapNotNull {
                val path = it.absolutePath
                manager.getPackageArchiveInfo(path, PACKAGE_FLAGS)?.apply {
                    applicationInfo?.fixBasePaths(path)
                }
            }
            ?.filter { isPackageAnExtension(type, it) }
            ?.map { ExtensionInfo(packageInfo = it, isShared = false) }
            ?: emptySequence()

        return (sharedExtensions + privateExtensions)
            .groupBy { it.packageInfo.packageName }
            .mapNotNull { (_, list) ->
                selectExtensionPackage(
                    list.firstOrNull { it.isShared },
                    list.firstOrNull { !it.isShared }
                )
            }
    }

    fun loadAnimeExtensions(context: Context): List<AnimeLoadResult> {
        val extPkgs = getInstalledExtensionPackages(context, MediaType.ANIME)
        if (extPkgs.isEmpty()) return emptyList()

        return runBlocking {
            val deferred = extPkgs.map {
                async { loadAnimeExtension(context, it) }
            }
            deferred.map { it.await() }
        }
    }

    fun loadMangaExtensions(context: Context): List<MangaLoadResult> {
        val extPkgs = getInstalledExtensionPackages(context, MediaType.MANGA)
        if (extPkgs.isEmpty()) return emptyList()

        return runBlocking {
            val deferred = extPkgs.map {
                async { loadMangaExtension(context, it) }
            }
            deferred.map { it.await() }
        }
    }

    fun loadNovelExtensions(context: Context): List<NovelLoadResult> {
        val extPkgs = getInstalledExtensionPackages(context, MediaType.NOVEL)
        if (extPkgs.isEmpty()) return emptyList()

        return runBlocking {
            val deferred = extPkgs.map {
                async { loadNovelExtension(context, it) }
            }
            deferred.map { it.await() }
        }
    }

    fun loadAnimeExtensionFromPkgName(context: Context, pkgName: String): AnimeLoadResult {
        val info = getExtensionInfoFromPkgName(context, pkgName, MediaType.ANIME)
            ?: return AnimeLoadResult.Error
        return loadAnimeExtension(context, info)
    }

    fun loadMangaExtensionFromPkgName(context: Context, pkgName: String): MangaLoadResult {
        val info = getExtensionInfoFromPkgName(context, pkgName, MediaType.MANGA)
            ?: return MangaLoadResult.Error
        return loadMangaExtension(context, info)
    }

    fun loadNovelExtensionFromPkgName(context: Context, pkgName: String): NovelLoadResult {
        val info = getExtensionInfoFromPkgName(context, pkgName, MediaType.NOVEL)
            ?: return NovelLoadResult.Error(Exception("Extension not found"))
        return loadNovelExtension(context, info)
    }

    private fun createClassLoader(sourceDir: String, context: Context): ClassLoader? {
        return try {
            ChildFirstPathClassLoader(sourceDir, null, context.classLoader)
        } catch (e: Throwable) {
            Logger.log("Extension classloader error: $e")
            Injekt.get<CrashlyticsInterface>().logException(e)
            null
        }
    }

    private fun loadAnimeExtension(context: Context, extensionInfo: ExtensionInfo): AnimeLoadResult {
        val pkgInfo = extensionInfo.packageInfo
        val pkgName = pkgInfo.packageName

        val appInfo = pkgInfo.applicationInfo ?: return AnimeLoadResult.Error

        if (!extensionInfo.isShared) {
            val privateFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
            appInfo.fixBasePaths(privateFile.absolutePath)
        }

        val extName = context.packageManager.getApplicationLabel(appInfo).toString().substringAfter("Aniyomi: ")
        val versionName = pkgInfo.versionName ?: return AnimeLoadResult.Error
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        val libVersion = versionName.substringBeforeLast(".").toDoubleOrNull()
        if (libVersion == null || libVersion < ANIME_LIB_VERSION_MIN || libVersion > ANIME_LIB_VERSION_MAX) {
            Logger.log("Unsupported anime lib version: $libVersion")
            return AnimeLoadResult.Error
        }

        val isNsfw = appInfo.metaData?.getInt("$ANIME_PACKAGE$XX_METADATA_NSFW") == 1
        if (!loadNsfwSource && isNsfw) {
            return AnimeLoadResult.Error
        }

        val loader = createClassLoader(appInfo.sourceDir, context) ?: return AnimeLoadResult.Error

        val classList = appInfo.metaData?.getString("$ANIME_PACKAGE$XX_METADATA_SOURCE_CLASS")?.split(";") ?: emptyList()

        val sources = classList.flatMap {
            try {
                val name = if (it.startsWith(".")) pkgName + it else it
                val obj = Class.forName(name, false, loader).getDeclaredConstructor().newInstance()
                when (obj) {
                    is AnimeSource -> listOf(obj)
                    is AnimeSourceFactory -> obj.createSources()
                    else -> emptyList()
                }
            } catch (e: Throwable) {
                Logger.log("Failed loading anime source: $e")
                emptyList()
            }
        }

        return AnimeLoadResult.Success(
            AnimeExtension.Installed(
                name = extName,
                pkgName = pkgName,
                versionName = versionName,
                versionCode = versionCode,
                libVersion = libVersion,
                lang = sources.filterIsInstance<AnimeCatalogueSource>().map { it.lang }.distinct().let {
                    when (it.size) {
                        0 -> ""
                        1 -> it.first()
                        else -> "all"
                    }
                },
                isNsfw = isNsfw,
                hasReadme = appInfo.metaData?.getInt("$ANIME_PACKAGE$XX_METADATA_HAS_README", 0) == 1,
                hasChangelog = appInfo.metaData?.getInt("$ANIME_PACKAGE$XX_METADATA_HAS_CHANGELOG", 0) == 1,
                sources = sources,
                pkgFactory = appInfo.metaData?.getString("$ANIME_PACKAGE$XX_METADATA_SOURCE_FACTORY"),
                isUnofficial = true,
                icon = context.getApplicationIcon(pkgName)
            )
        )
    }

    private fun loadMangaExtension(context: Context, extensionInfo: ExtensionInfo): MangaLoadResult {
        val pkgInfo = extensionInfo.packageInfo
        val pkgName = pkgInfo.packageName

        val appInfo = pkgInfo.applicationInfo ?: return MangaLoadResult.Error

        if (!extensionInfo.isShared) {
            val privateFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
            appInfo.fixBasePaths(privateFile.absolutePath)
        }

        val extName = context.packageManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
        val versionName = pkgInfo.versionName ?: return MangaLoadResult.Error
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        val libVersion = versionName.substringBeforeLast(".").toDoubleOrNull()
        if (libVersion == null || libVersion < MANGA_LIB_VERSION_MIN || libVersion > MANGA_LIB_VERSION_MAX) {
            Logger.log("Unsupported manga lib version: $libVersion")
            return MangaLoadResult.Error
        }

        val isNsfw = appInfo.metaData?.getInt("$MANGA_PACKAGE$XX_METADATA_NSFW") == 1
        if (!loadNsfwSource && isNsfw) {
            return MangaLoadResult.Error
        }

        val loader = createClassLoader(appInfo.sourceDir, context) ?: return MangaLoadResult.Error

        val classList = appInfo.metaData?.getString("$MANGA_PACKAGE$XX_METADATA_SOURCE_CLASS")?.split(";") ?: emptyList()

        val sources = classList.flatMap {
            try {
                val name = if (it.startsWith(".")) pkgName + it else it
                val obj = Class.forName(name, false, loader).getDeclaredConstructor().newInstance()
                when (obj) {
                    is MangaSource -> listOf(obj)
                    is SourceFactory -> obj.createSources()
                    else -> emptyList()
                }
            } catch (e: Throwable) {
                Logger.log("Failed loading manga source: $e")
                emptyList()
            }
        }

        return MangaLoadResult.Success(
            MangaExtension.Installed(
                name = extName,
                pkgName = pkgName,
                versionName = versionName,
                versionCode = versionCode,
                libVersion = libVersion,
                lang = sources.filterIsInstance<CatalogueSource>().map { it.lang }.distinct().let {
                    when (it.size) {
                        0 -> ""
                        1 -> it.first()
                        else -> "all"
                    }
                },
                isNsfw = isNsfw,
                hasReadme = appInfo.metaData?.getInt("$MANGA_PACKAGE$XX_METADATA_HAS_README", 0) == 1,
                hasChangelog = appInfo.metaData?.getInt("$MANGA_PACKAGE$XX_METADATA_HAS_CHANGELOG", 0) == 1,
                sources = sources,
                pkgFactory = appInfo.metaData?.getString("$MANGA_PACKAGE$XX_METADATA_SOURCE_FACTORY"),
                isUnofficial = true,
                icon = context.getApplicationIcon(pkgName)
            )
        )
    }

    private fun loadNovelExtension(context: Context, extensionInfo: ExtensionInfo): NovelLoadResult {
        val pkgInfo = extensionInfo.packageInfo
        val pkgName = pkgInfo.packageName

        val appInfo = pkgInfo.applicationInfo ?: return NovelLoadResult.Error(Exception("ApplicationInfo missing"))

        if (!extensionInfo.isShared) {
            val privateFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
            appInfo.fixBasePaths(privateFile.absolutePath)
        }

        val extName = context.packageManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
        val versionName = pkgInfo.versionName ?: return NovelLoadResult.Error(Exception("Missing version"))
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        val loader = createClassLoader(appInfo.sourceDir, context) ?: return NovelLoadResult.Error(Exception("ClassLoader failed"))

        val novelInterface = try {
            val className = appInfo.loadLabel(context.packageManager).toString()
            val extensionClass = "some.random.novelextensions.${className.lowercase(Locale.getDefault())}.$className"
            val clazz = loader.loadClass(extensionClass)
            clazz.getDeclaredConstructor().newInstance() as? NovelInterface
        } catch (e: Throwable) {
            Logger.log("Novel extension load failed: $e")
            null
        }

        return NovelLoadResult.Success(
            NovelExtension.Installed(
                name = extName,
                pkgName = pkgName,
                versionName = versionName,
                versionCode = versionCode,
                sources = listOfNotNull(novelInterface),
                isUnofficial = true,
                icon = context.getApplicationIcon(pkgName)
            )
        )
    }

    private fun isPackageAnExtension(type: MediaType, pkgInfo: PackageInfo): Boolean {
        return if (type == MediaType.NOVEL) {
            pkgInfo.packageName.startsWith("some.random")
        } else {
            pkgInfo.reqFeatures.orEmpty().any {
                it.name == when (type) {
                    MediaType.ANIME -> ANIME_PACKAGE
                    MediaType.MANGA -> MANGA_PACKAGE
                    else -> ""
                }
            }
        }
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean
    )
}
