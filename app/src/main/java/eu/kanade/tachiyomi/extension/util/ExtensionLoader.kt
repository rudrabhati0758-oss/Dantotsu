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
    const val ANIME_LIB_VERSION_MAX = 16

    const val MANGA_LIB_VERSION_MIN = 1.2
    const val MANGA_LIB_VERSION_MAX = 2.0

    val PACKAGE_FLAGS =
        PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES
            else 0)

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

    fun installPrivateExtensionFile(
        context: Context,
        file: File,
        type: MediaType
    ): Boolean {

        val extension =
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PACKAGE_FLAGS
            )?.takeIf {
                isPackageAnExtension(type, it)
            } ?: return false


        val currentExtension =
            getExtensionPackageInfoFromPkgName(
                context,
                extension.packageName,
                type
            )

        if (currentExtension != null) {

            if (
                PackageInfoCompat.getLongVersionCode(extension) <
                PackageInfoCompat.getLongVersionCode(currentExtension)
            ) {
                Logger.log(
                    "Installed extension version is higher. Downgrading is not allowed."
                )
                return false
            }

            val signatures = getSignatures(extension)

            if (signatures.isNullOrEmpty()) {
                Logger.log("Extension is not signed.")
                return false
            }

            if (!signatures.containsAll(getSignatures(currentExtension) ?: emptyList())) {
                Logger.log("Extension signature mismatch.")
                return false
            }
        }

        val target = File(
            getPrivateExtensionDir(context),
            "${extension.packageName}.$PRIVATE_EXTENSION_EXTENSION"
        )

        return try {

            target.delete()

            file.copyAndSetReadOnlyTo(target)

            if (currentExtension != null) {
                ExtensionInstallReceiver.notifyReplaced(
                    context,
                    extension.packageName
                )
            } else {
                ExtensionInstallReceiver.notifyAdded(
                    context,
                    extension.packageName
                )
            }

            true

        } catch (e: Exception) {

            Logger.log("Failed installing extension: $e")
            false
        }
    }


    fun uninstallPrivateExtension(
        context: Context,
        pkgName: String
    ) {

        val file = File(
            getPrivateExtensionDir(context),
            "$pkgName.$PRIVATE_EXTENSION_EXTENSION"
        )

        if (file.exists()) {
            file.delete()
        }
    }
        private fun selectExtensionPackage(
        shared: ExtensionInfo?,
        private: ExtensionInfo?
    ): ExtensionInfo? {

        if (shared == null) return private
        if (private == null) return shared

        return if (
            PackageInfoCompat.getLongVersionCode(shared.packageInfo) >=
            PackageInfoCompat.getLongVersionCode(private.packageInfo)
        ) {
            shared
        } else {
            private
        }
    }


    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

            val signingInfo = pkgInfo.signingInfo ?: return null

            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }

        } else {

            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
            ?.map {
                Hash.sha256(it.toByteArray())
            }
            ?.toList()
    }


    private fun ApplicationInfo.fixBasePaths(
        apkPath: String
    ) {

        sourceDir = apkPath
        publicSourceDir = apkPath
    }


    private fun getExtensionInfoFromPkgName(
        context: Context,
        pkgName: String,
        type: MediaType
    ): ExtensionInfo? {


        val privateFile = File(
            getPrivateExtensionDir(context),
            "$pkgName.$PRIVATE_EXTENSION_EXTENSION"
        )


        val privatePkg =
            if (privateFile.isFile) {

                context.packageManager
                    .getPackageArchiveInfo(
                        privateFile.absolutePath,
                        PACKAGE_FLAGS
                    )
                    ?.takeIf {
                        isPackageAnExtension(type, it)
                    }
                    ?.let {

                        it.applicationInfo?.fixBasePaths(
                            privateFile.absolutePath
                        )

                        ExtensionInfo(
                            packageInfo = it,
                            isShared = false
                        )
                    }

            } else null



        val sharedPkg =
            try {

                context.packageManager
                    .getPackageInfo(
                        pkgName,
                        PACKAGE_FLAGS
                    )
                    .takeIf {
                        isPackageAnExtension(type, it)
                    }
                    ?.let {

                        ExtensionInfo(
                            packageInfo = it,
                            isShared = true
                        )
                    }

            } catch (_: PackageManager.NameNotFoundException) {

                null
            }



        return selectExtensionPackage(
            sharedPkg,
            privatePkg
        )
    }


    fun getExtensionPackageInfoFromPkgName(
        context: Context,
        pkgName: String,
        type: MediaType
    ): PackageInfo? {

        return getExtensionInfoFromPkgName(
            context,
            pkgName,
            type
        )?.packageInfo
    }



    fun loadAnimeExtensions(
        context: Context
    ): List<AnimeLoadResult> {


        val packages =
            getInstalledExtensionPackages(
                context,
                MediaType.ANIME
            )


        if (packages.isEmpty()) {
            return emptyList()
        }


        return runBlocking {

            packages.map {

                async {
                    loadAnimeExtension(
                        context,
                        it
                    )
                }

            }.map {
                it.await()
            }
        }
    }



    fun loadMangaExtensions(
        context: Context
    ): List<MangaLoadResult> {


        val packages =
            getInstalledExtensionPackages(
                context,
                MediaType.MANGA
            )


        if (packages.isEmpty()) {
            return emptyList()
        }


        return runBlocking {

            packages.map {

                async {
                    loadMangaExtension(
                        context,
                        it
                    )
                }

            }.map {
                it.await()
            }
        }
    }



    fun loadNovelExtensions(
        context: Context
    ): List<NovelLoadResult> {


        val packages =
            getInstalledExtensionPackages(
                context,
                MediaType.NOVEL
            )


        if (packages.isEmpty()) {
            return emptyList()
        }


        return runBlocking {

            packages.map {

                async {
                    loadNovelExtension(
                        context,
                        it
                    )
                }

            }.map {
                it.await()
            }
        }
    }



    private fun getInstalledExtensionPackages(
        context: Context,
        type: MediaType
    ): List<ExtensionInfo> {


        val manager = context.packageManager


        val installed =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                manager.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(
                        PACKAGE_FLAGS.toLong()
                    )
                )

            } else {

                manager.getInstalledPackages(
                    PACKAGE_FLAGS
                )
            }



        val shared =
            installed
                .asSequence()
                .filter {
                    isPackageAnExtension(
                        type,
                        it
                    )
                }
                .map {
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = true
                    )
                }



        val privateExtensions =
            getPrivateExtensionDir(context)
                .listFiles()
                ?.asSequence()
                ?.filter {
                    it.extension == PRIVATE_EXTENSION_EXTENSION
                }
                ?.mapNotNull {

                    val path = it.absolutePath

                    manager.getPackageArchiveInfo(
                        path,
                        PACKAGE_FLAGS
                    )
                        ?.apply {
                            applicationInfo?.fixBasePaths(path)
                        }

                }
                ?.filter {
                    isPackageAnExtension(
                        type,
                        it
                    )
                }
                ?.map {

                    ExtensionInfo(
                        packageInfo = it,
                        isShared = false
                    )
                }
                ?: emptySequence()



        return (
            shared + privateExtensions
        )
            .groupBy {
                it.packageInfo.packageName
            }
            .mapNotNull { (_, values) ->

                selectExtensionPackage(
                    values.firstOrNull {
                        it.isShared
                    },
                    values.firstOrNull {
                        !it.isShared
                    }
                )
            }
    }
        private fun createClassLoader(
        sourceDir: String?,
        context: Context
    ): ClassLoader? {

        return try {

            ChildFirstPathClassLoader(
                sourceDir,
                null,
                context.classLoader
            )

        } catch (e: Throwable) {

            Logger.log(
                "Extension class loader failed: $e"
            )

            Injekt.get<CrashlyticsInterface>()
                .logException(e)

            null
        }
    }



    private fun loadAnimeExtension(
        context: Context,
        extensionInfo: ExtensionInfo
    ): AnimeLoadResult {


        val pkgInfo = extensionInfo.packageInfo
        val pkgName = pkgInfo.packageName

        val appInfo =
            pkgInfo.applicationInfo
                ?: return AnimeLoadResult.Error


        val pkgManager = context.packageManager


        val extName =
            pkgManager
                .getApplicationLabel(appInfo)
                .toString()
                .substringAfter("Aniyomi: ")


        val versionName =
            pkgInfo.versionName
                ?: return AnimeLoadResult.Error


        val versionCode =
            PackageInfoCompat.getLongVersionCode(pkgInfo)



        val libVersion =
            versionName
                .substringBeforeLast(".")
                .toDoubleOrNull()



        if (
            libVersion == null ||
            libVersion < ANIME_LIB_VERSION_MIN ||
            libVersion > ANIME_LIB_VERSION_MAX
        ) {

            Logger.log(
                "Unsupported anime extension version: $libVersion"
            )

            return AnimeLoadResult.Error
        }



        val isNsfw =
            appInfo.metaData
                ?.getInt(
                    "$ANIME_PACKAGE$XX_METADATA_NSFW"
                ) == 1


        if (!loadNsfwSource && isNsfw) {
            return AnimeLoadResult.Error
        }



        val classLoader =
            createClassLoader(
                appInfo.sourceDir,
                context
            )
                ?: return AnimeLoadResult.Error



        val classNames =
            appInfo.metaData
                ?.getString(
                    "$ANIME_PACKAGE$XX_METADATA_SOURCE_CLASS"
                )
                ?.split(";")
                ?: emptyList()



        val sources =
            classNames.flatMap { name ->

                try {

                    val className =
                        if (name.startsWith(".")) {
                            pkgName + name
                        } else {
                            name
                        }


                    when (
                        val obj =
                            Class.forName(
                                className,
                                false,
                                classLoader
                            )
                            .getDeclaredConstructor()
                            .newInstance()
                    ) {

                        is AnimeSource ->
                            listOf(obj)

                        is AnimeSourceFactory ->
                            obj.createSources()

                        else ->
                            emptyList()
                    }

                } catch (e: Throwable) {

                    Logger.log(
                        "Anime extension load failed: $e"
                    )

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
                lang = sources
                    .filterIsInstance<AnimeCatalogueSource>()
                    .map { it.lang }
                    .firstOrNull()
                    ?: "",
                isNsfw = isNsfw,
                hasReadme = false,
                hasChangelog = false,
                sources = sources,
                pkgFactory = appInfo.metaData
                    ?.getString(
                        "$ANIME_PACKAGE$XX_METADATA_SOURCE_FACTORY"
                    ),
                isUnofficial = true,
                icon = context.getApplicationIcon(pkgName)
            )
        )
    }
        private fun loadNovelExtension(
        context: Context,
        extensionInfo: ExtensionInfo
    ): NovelLoadResult {
        val pkgInfo = extensionInfo.packageInfo
        val pkgName = pkgInfo.packageName
        val pkgManager = context.packageManager

        val appInfo = pkgInfo.applicationInfo
            ?: return NovelLoadResult.Error(Exception("ApplicationInfo is null"))

        if (!extensionInfo.isShared) {
            val privateFile = File(
                getPrivateExtensionDir(context),
                "$pkgName.$PRIVATE_EXTENSION_EXTENSION"
            )
            appInfo.fixBasePaths(privateFile.absolutePath)
        }

        val extName =
            pkgManager.getApplicationLabel(appInfo)
                .toString()
                .substringAfter("Tachiyomi: ")

        val versionName = pkgInfo.versionName
            ?: return NovelLoadResult.Error(Exception("Missing versionName"))

        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        val classLoader = try {
            ChildFirstPathClassLoader(
                appInfo.sourceDir,
                null,
                context.classLoader
            )
        } catch (e: Throwable) {
            Logger.log("Extension load error: $extName")
            Injekt.get<CrashlyticsInterface>().logException(e)
            return NovelLoadResult.Error(e as Exception)
        }

        val novelInterfaceInstance = try {
            val className = appInfo.loadLabel(context.packageManager)
                .toString()

            val extensionClassName =
                "some.random.novelextensions.${className.lowercase(Locale.getDefault())}.$className"

            val loadedClass = classLoader.loadClass(extensionClassName)

            loadedClass
                .getDeclaredConstructor()
                .newInstance() as? NovelInterface

        } catch (e: Throwable) {
            Logger.log("Extension load error: $extName")
            return NovelLoadResult.Error(e as Exception)
        }

        return NovelLoadResult.Success(
            NovelExtension.Installed(
                name = extName,
                pkgName = pkgName,
                versionName = versionName,
                versionCode = versionCode,
                sources = listOfNotNull(novelInterfaceInstance),
                isUnofficial = true,
                icon = context.getApplicationIcon(pkgName),
            )
        )
    }


    private fun isPackageAnExtension(
        type: MediaType,
        pkgInfo: PackageInfo
    ): Boolean {

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
        val isShared: Boolean,
    )
}
