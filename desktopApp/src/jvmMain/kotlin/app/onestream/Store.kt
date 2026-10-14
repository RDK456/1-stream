package app.onestream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Bookmark(
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String? = null,
)

@Serializable
data class HistoryEntry(
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String? = null,
    /** Provider data string for the episode/movie that was playing. */
    val data: String,
    val episodeName: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
)

/** A finished download. In-progress ones live in the Downloader and are dropped if the app closes. */
@Serializable
data class DownloadRecord(
    val id: String,
    val title: String,
    /** Quality label such as `1080p`. */
    val label: String,
    val path: String,
    val bytes: Long,
    val at: Long,
    /** The stream it came from, so it can be downloaded again. Missing on downloads made before 1.0.11. */
    val src: DlSource? = null,
)

@Serializable
data class DlSource(val url: String, val referer: String = "", val quality: Int = 0, val m3u8: Boolean = false, val headers: Map<String, String> = emptyMap(), val subs: List<DlSub> = emptyList())

/** A subtitle file offered with a stream, saved next to the video. */
@Serializable
data class DlSub(val lang: String, val url: String, val headers: Map<String, String> = emptyMap())

/** Where an installed plugin jar came from, so it can be updated from the same repository. */
@Serializable
data class InstalledFrom(val repo: String, val version: Int)

@Serializable
data class StoreData(
    val downloads: List<DownloadRecord> = emptyList(),
    val repos: List<String> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val noticeAccepted: Boolean = false,
    val reduceMotion: Boolean = false,
    val autoUpdate: Boolean = true,
    /** Update feed (update.json) the user set in Settings; a build-time or environment feed wins over it. */
    val updateUrl: String = "",
    /** Plugin scan results: "id@version@rev" to why it cannot run here ("" = fine). */
    val compat: Map<String, String> = emptyMap(),
    val defaultReposSeeded: Boolean = false,
    /** Refresh repositories and update installed plugins at start and every few hours. */
    val autoUpdatePlugins: Boolean = true,
    /** Jar file name to the repository and version it was installed from. Plugins added by hand are not listed and never auto-update. */
    val pluginSources: Map<String, InstalledFrom> = emptyMap(),
    val lastPluginUpdate: Long = 0,
    /** `repoUrl|internalName` of default plugins already dealt with, so one the user removes is not installed again. */
    val defaultInstalled: List<String> = emptyList(),
    /** Converted Android plugin jar name to the SHA-256 of the jar the user approved. */
    val approvedPlugins: Map<String, String> = emptyMap(),
    /** Chosen language and type filters on Home and in Discover plugins ("homeLang", "homeType", "discLang", "discType"); a missing key means All. */
    val filters: Map<String, String> = emptyMap(),
)

/** One JSON file, rewritten on every change. Fine for the data volumes involved. */
class Store(private val file: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val _data = MutableStateFlow(read())
    val data: StateFlow<StoreData> = _data

    private fun read(): StoreData = runCatching { json.decodeFromString<StoreData>(file.readText()) }
        .getOrDefault(StoreData())

    @Synchronized
    fun update(block: (StoreData) -> StoreData) {
        _data.update(block)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(StoreData.serializer(), _data.value))
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }

    /** First run only: adds the default repositories once, so removing one sticks. */
    fun seedDefaultRepos(defaults: List<String>) = update { if (it.defaultReposSeeded) it else it.copy(repos = (it.repos + defaults).distinct(), defaultReposSeeded = true) }

    fun setAutoUpdatePlugins(on: Boolean) = update { it.copy(autoUpdatePlugins = on) }
    fun recordPlugin(jarName: String, repo: String, version: Int) = update { it.copy(pluginSources = it.pluginSources + (jarName to InstalledFrom(repo, version))) }
    fun touchPluginUpdate() = update { it.copy(lastPluginUpdate = System.currentTimeMillis()) }

    fun markDefaultInstalled(ids: List<String>) = update { it.copy(defaultInstalled = (it.defaultInstalled + ids).distinct()) }

    fun approvePlugin(jarName: String, sha256: String) = update { it.copy(approvedPlugins = it.approvedPlugins + (jarName to sha256)) }
    fun isApproved(jar: File) = _data.value.approvedPlugins[jar.name]?.equals(app.onestream.update.Updater.sha256(jar), ignoreCase = true) == true

    fun setFilter(key: String, value: String?) = update { it.copy(filters = if (value == null) it.filters - key else it.filters + (key to value)) }

    fun addRepo(url: String) = update { if (url in it.repos) it else it.copy(repos = it.repos + url) }
    fun removeRepo(url: String) = update { it.copy(repos = it.repos - url) }

    fun toggleBookmark(b: Bookmark) = update { d ->
        if (d.bookmarks.any { it.url == b.url && it.apiName == b.apiName })
            d.copy(bookmarks = d.bookmarks.filterNot { it.url == b.url && it.apiName == b.apiName })
        else d.copy(bookmarks = d.bookmarks + b)
    }

    /** Library entries are identified by `apiName|url`. */
    fun removeBookmarks(keys: Set<String>) = update { d -> d.copy(bookmarks = d.bookmarks.filterNot { "${it.apiName}|${it.url}" in keys }) }

    fun removeHistory(e: HistoryEntry) = update { d -> d.copy(history = d.history.filterNot { it.url == e.url && it.apiName == e.apiName }) }
    fun clearHistory() = update { it.copy(history = emptyList()) }

    fun addDownload(r: DownloadRecord) = update { it.copy(downloads = listOf(r) + it.downloads.filterNot { d -> d.id == r.id }) }
    fun removeDownload(id: String) = update { d -> d.copy(downloads = d.downloads.filterNot { it.id == id }) }

    fun isBookmarked(url: String, apiName: String) =
        _data.value.bookmarks.any { it.url == url && it.apiName == apiName }

    fun saveProgress(e: HistoryEntry) = update { d ->
        val rest = d.history.filterNot { it.url == e.url && it.apiName == e.apiName }
        d.copy(history = (listOf(e.copy(updatedAt = System.currentTimeMillis())) + rest).take(100))
    }

    fun acceptNotice() = update { it.copy(noticeAccepted = true) }
    fun setReduceMotion(on: Boolean) = update { it.copy(reduceMotion = on) }
    fun setAutoUpdate(on: Boolean) = update { it.copy(autoUpdate = on) }
    fun setUpdateUrl(url: String) = update { it.copy(updateUrl = url) }
    fun mergeCompat(results: Map<String, String>) = update { it.copy(compat = it.compat + results) }
}