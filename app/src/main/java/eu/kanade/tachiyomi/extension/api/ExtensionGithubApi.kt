package eu.kanade.tachiyomi.extension.api

import ani.dantotsu.asyncMap
import ani.dantotsu.parsers.novel.AvailableNovelSources
import ani.dantotsu.parsers.novel.NovelExtension
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AvailableAnimeSources
import eu.kanade.tachiyomi.extension.manga.model.AvailableMangaSources
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.core.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

internal class ExtensionGithubApi {
    private val networkService: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    private fun List<ExtensionSourceJsonObject>.toAnimeExtensionSources(): List<AvailableAnimeSources> {
        return this.map {
            AvailableAnimeSources(
                id = it.id,
                lang = it.lang,
                name = it.name,
                baseUrl = it.baseUrl,
            )
        }
    }

    private fun List<ExtensionJsonObject>.toAnimeExtensions(repository: String): List<AnimeExtension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.ANIME_LIB_VERSION_MIN && libVersion <= 1.5
            }
            .map {
                AnimeExtension.Available(
                    name = it.name.substringAfter("Aniyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    hasReadme = it.hasReadme == 1,
                    hasChangelog = it.hasChangelog == 1,
                    sources = it.sources?.toAnimeExtensionSources().orEmpty(),
                    apkName = it.apk,
                    repository = repository,
                    iconUrl = "${repository.removeSuffix("/index.min.json").removeSuffix("/index.pb")}/icon/${it.pkg}.png",
                )
            }
    }

    suspend fun findAnimeExtensions(): List<AnimeExtension.Available> {
        return withIOContext {
            val extensions: ArrayList<AnimeExtension.Available> = arrayListOf()
            val repos = PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).toMutableList()

            repos.asyncMap { repoUrl ->
                try {
                    val repoExtensions = fetchExtensionJsonObjects(repoUrl).toAnimeExtensions(repoUrl)
                    extensions.addAll(repoExtensions)
                } catch (e: Throwable) {
                    Logger.log("Failed to get anime extensions from GitHub")
                    Logger.log(e)
                }
            }

            extensions
        }
    }

    fun getAnimeApkUrl(extension: AnimeExtension.Available): String {
        val baseRepo = extension.repository.removeSuffix("index.min.json").removeSuffix("index.pb").removeSuffix("/")
        return "$baseRepo/apk/${extension.apkName}"
    }

    private fun List<ExtensionSourceJsonObject>.toMangaExtensionSources(): List<AvailableMangaSources> {
        return this.map {
            AvailableMangaSources(
                id = it.id,
                lang = it.lang,
                name = it.name,
                baseUrl = it.baseUrl,
            )
        }
    }

    private fun List<ExtensionJsonObject>.toMangaExtensions(repository: String): List<MangaExtension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.MANGA_LIB_VERSION_MIN && libVersion <= 1.5
            }
            .map {
                MangaExtension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    hasReadme = it.hasReadme == 1,
                    hasChangelog = it.hasChangelog == 1,
                    sources = it.sources?.toMangaExtensionSources().orEmpty(),
                    apkName = it.apk,
                    repository = repository,
                    iconUrl = "${repository.removeSuffix("/index.min.json").removeSuffix("/index.pb")}/icon/${it.pkg}.png",
                )
            }
    }

    suspend fun findMangaExtensions(): List<MangaExtension.Available> {
        return withIOContext {
            val extensions: ArrayList<MangaExtension.Available> = arrayListOf()
            val repos = PrefManager.getVal<Set<String>>(PrefName.MangaExtensionRepos).toMutableList()

            repos.asyncMap { repoUrl ->
                try {
                    val repoExtensions = fetchExtensionJsonObjects(repoUrl).toMangaExtensions(repoUrl)
                    extensions.addAll(repoExtensions)
                } catch (e: Throwable) {
                    Logger.log("Failed to get manga extensions from GitHub")
                    Logger.log(e)
                }
            }

            extensions
        }
    }

    fun getMangaApkUrl(extension: MangaExtension.Available): String {
        val baseRepo = extension.repository.removeSuffix("index.min.json").removeSuffix("index.pb").removeSuffix("/")
        return "$baseRepo/apk/${extension.apkName}"
    }

    suspend fun findNovelExtensions(): List<NovelExtension.Available> {
        return withIOContext {
            val extensions: ArrayList<NovelExtension.Available> = arrayListOf()
            val repos = PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos).toMutableList()

            repos.asyncMap { repoUrl ->
                try {
                    val repoExtensions = fetchExtensionJsonObjects(repoUrl).toNovelExtensions(repoUrl)
                    extensions.addAll(repoExtensions)
                } catch (e: Throwable) {
                    Logger.log("Failed to get novel extensions from GitHub")
                    Logger.log(e)
                }
            }

            extensions
        }
    }

    private fun List<ExtensionJsonObject>.toNovelExtensions(repository: String): List<NovelExtension.Available> {
        return mapNotNull { extension ->
            val sources = extension.sources?.map { source ->
                ExtensionSourceJsonObject(
                    source.id,
                    source.lang,
                    source.name,
                    source.baseUrl,
                )
            }
            val iconUrl = "${repository.removeSuffix("/index.min.json").removeSuffix("/index.pb")}/icon/${extension.pkg}.png"
            NovelExtension.Available(
                extension.name,
                extension.pkg,
                extension.apk,
                extension.code,
                repository,
                sources?.toNovelSources() ?: emptyList(),
                iconUrl,
            )
        }
    }

    private fun List<ExtensionSourceJsonObject>.toNovelSources(): List<AvailableNovelSources> {
        return map { source ->
            AvailableNovelSources(
                source.id,
                source.lang,
                source.name,
                source.baseUrl,
            )
        }
    }

    fun getNovelApkUrl(extension: NovelExtension.Available): String {
        val baseRepo = extension.repository.removeSuffix("index.min.json").removeSuffix("index.pb").removeSuffix("/")
        return "$baseRepo/apk/${extension.pkgName}.apk"
    }

    private suspend fun fetchExtensionJsonObjects(rawUrl: String): List<ExtensionJsonObject> {
        val baseUrl = rawUrl
            .removeSuffix("/")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.pb")

        val pbUrl = "$baseUrl/index.pb"
        val jsonUrl = "$baseUrl/index.min.json"

        // 1. Try fetching index.pb (Protobuf format)
        try {
            val response = try {
                networkService.client.newCall(GET(pbUrl)).awaitSuccess()
            } catch (e: Throwable) {
                fallbackRepoUrl(baseUrl)?.let {
                    networkService.client.newCall(GET("$it/index.pb")).awaitSuccess()
                } ?: throw e
            }

            val bytes = response.body.bytes()
            if (bytes.isNotEmpty() && bytes[0] != '<'.code.toByte()) {
                val store = ProtoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), bytes)
                val protoList = store.extensionList?.extensions.orEmpty()
                if (protoList.isNotEmpty()) {
                    return protoList.map { it.toExtensionJsonObject() }
                }
            }
        } catch (e: Throwable) {
            Logger.log("Failed to parse index.pb from $baseUrl, falling back to index.min.json")
        }

        // 2. Fallback to index.min.json (Legacy JSON format)
        return try {
            val response = try {
                networkService.client.newCall(GET(jsonUrl)).awaitSuccess()
            } catch (e: Throwable) {
                fallbackRepoUrl(baseUrl)?.let {
                    networkService.client.newCall(GET("$it/index.min.json")).awaitSuccess()
                } ?: throw e
            }

            with(json) {
                response.parseAs<List<ExtensionJsonObject>>()
            }
        } catch (e: Throwable) {
            Logger.log("Failed to parse index.min.json from $baseUrl")
            Logger.log(e)
            emptyList()
        }
    }

    private fun fallbackRepoUrl(repoUrl: String): String? {
        var fallbackRepoUrl = "https://gcore.jsdelivr.net/gh/"
        val strippedRepoUrl = repoUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.pb")
        val repoUrlParts = strippedRepoUrl.split("/")
        if (repoUrlParts.size < 3) {
            return null
        }
        val repoOwner = repoUrlParts[1]
        val repoName = repoUrlParts[2]
        fallbackRepoUrl += "$repoOwner/$repoName"
        val repoBranch = if (repoUrlParts.size > 3) {
            repoUrlParts[3]
        } else {
            "main"
        }
        fallbackRepoUrl += "@$repoBranch"
        return fallbackRepoUrl
    }
}

@Serializable
private data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
) {
    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<ProtoExtension> = emptyList(),
    )

    @Serializable
    data class ProtoExtension(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val packageName: String = "",
        @ProtoNumber(3) val resources: Resources? = null,
        @ProtoNumber(4) val extensionLib: String = "",
        @ProtoNumber(5) val versionCode: Long = 0,
        @ProtoNumber(6) val versionName: String = "",
        @ProtoNumber(7) val contentWarning: Int = 0,
        @ProtoNumber(8) val sources: List<ProtoSource> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String = "",
        @ProtoNumber(2) val iconUrl: String = "",
    )

    @Serializable
    data class ProtoSource(
        @ProtoNumber(1) val id: Long = 0,
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val language: String = "",
        @ProtoNumber(4) val homeUrl: String = "",
    )
}

private fun NetworkExtensionStore.ProtoExtension.toExtensionJsonObject(): ExtensionJsonObject {
    val langs = sources.map { it.language }.filter { it.isNotBlank() }.toSet()
    val langStr = when {
        langs.size == 1 -> langs.first()
        else -> "all"
    }
    val rawApk = resources?.apkUrl.orEmpty()
    val apkFileName = if (rawApk.contains('/')) rawApk.substringAfterLast('/') else rawApk

    return ExtensionJsonObject(
        name = name,
        pkg = packageName,
        apk = apkFileName,
        lang = langStr,
        code = versionCode,
        version = versionName,
        nsfw = if (contentWarning >= 2) 1 else 0,
        hasReadme = 0,
        hasChangelog = 0,
        sources = sources.map {
            ExtensionSourceJsonObject(
                id = it.id,
                lang = it.language,
                name = it.name,
                baseUrl = it.homeUrl,
            )
        },
    )
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val hasReadme: Int = 0,
    val hasChangelog: Int = 0,
    val sources: List<ExtensionSourceJsonObject>?,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private fun ExtensionJsonObject.extractLibVersion(): Double {
    return version.substringBeforeLast('.').toDoubleOrNull() ?: 0.0
}
