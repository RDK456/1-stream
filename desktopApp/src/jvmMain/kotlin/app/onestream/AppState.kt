package app.onestream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.onestream.plugins.Cs3
import app.onestream.plugins.InstalledPlugin
import app.onestream.plugins.RepoPlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import app.onestream.plugins.PluginManager
import app.onestream.plugins.RepoIndex
import app.onestream.plugins.Repositories
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class Tab(val label: String, val glyph: String) {
    Search("Home", "⌂"), Library("Library", "★"), History("History", "◷"), Downloads("Downloads", "⤓"), Plugins("Plugins", "⧉"), About("Settings", "ⓘ")
}

class ScanState(val phase: String, val done: Int, val total: Int)

/** Bump when the Android shim improves so earlier "cannot run" verdicts are checked again. */
private const val COMPAT_REV = 1
fun compatKey(p: RepoPlugin) = "${p.internalName}@${p.version}@$COMPAT_REV"

sealed interface Screen {
    data class Details(val apiName: String, val url: String) : Screen
    /** Every title of one Home row, as a grid. */
    data class Shelf(val title: String, val items: List<com.lagradost.cloudstream3.SearchResponse>) : Screen
    data class Play(
        val apiName: String,
        val url: String,
        val title: String,
        val posterUrl: String?,
        val data: String,
        val episodeName: String?,
        val startMs: Long = 0,
    ) : Screen {
        /** The episode after this one, if any. Outside the constructor so equality stays cheap along a long chain. */
        var next: (() -> Play?)? = null
    }
}

class AppState(
    val scope: CoroutineScope,
    val store: Store = Store(AppPaths.data),
    val pluginManager: PluginManager = PluginManager(AppPaths.plugins) { store.isApproved(it) },
) {
    /** The player covers the whole monitor (see Main.kt and WinFullscreen). */
    var fullscreen by mutableStateOf(false)
    val repositories = Repositories()
    val downloader = app.onestream.download.Downloader(scope, store)
    val updates = app.onestream.update.UpdateController.create(scope, store, AppPaths.root)

    init {
        // Lets converted Android plugins (e.g. MegaProvider) read and add repositories
        com.lagradost.cloudstream3.plugins.CompatHost.repositories = { store.data.value.repos }
        com.lagradost.cloudstream3.plugins.CompatHost.addRepository = { raw ->
            val url = Repositories.normalize(raw)
            if (runCatching { Repositories.secure(url) }.isSuccess) store.addRepo(url)
        }
        com.lagradost.cloudstream3.plugins.CompatHost.fetchText = { runCatching { String(repositories.fetchRaw(it), Charsets.UTF_8) }.getOrNull() }
    }

    var tab by mutableStateOf(Tab.Search)
    var railExpanded by mutableStateOf(true)
    val stack = mutableStateListOf<Screen>()

    /** The window's close button was pressed: ask before quitting. */
    var confirmClose by mutableStateOf(false)

    /** Home search, kept here so it survives opening a title and coming back. */
    var query by mutableStateOf("")
    var searched by mutableStateOf(false)
    var searchResults by mutableStateOf<List<com.lagradost.cloudstream3.SearchResponse>>(emptyList())
    var searchFailures by mutableStateOf<List<String>>(emptyList())
    var searchProvider by mutableStateOf(0) // 0 = all
    /** Home rows already fetched, so returning to Home does not reload them. */
    val feed = app.onestream.ui.HomeFeed()
    val homeScroll = androidx.compose.foundation.lazy.LazyListState()

    var installed by mutableStateOf<List<InstalledPlugin>>(emptyList())
        private set
    var providers by mutableStateOf<List<MainAPI>>(emptyList())
        private set

    /** Repo URL -> fetched index or the failure. */
    val repoIndexes = mutableStateMapOf<String, Result<RepoIndex>>()

    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    fun reloadPlugins() {
        scope.launch(Dispatchers.IO) { applyInstalled(pluginManager.loadAll()) }
    }

    fun loadPluginsBlocking() = applyInstalled(pluginManager.loadAll())

    /** Loads (or reloads) one jar and refreshes the lists. */
    fun loadJar(jar: java.io.File) {
        val result = pluginManager.load(jar)
        applyInstalled((installed.filterNot { it.file == jar } + result).sortedBy { it.file.name })
    }

    fun removePlugin(p: InstalledPlugin): Boolean {
        val ok = pluginManager.remove(p.file)
        applyInstalled(installed.filterNot { it.file == p.file })
        return ok
    }

    /** Plugin scan verdicts by [compatKey]: empty = looks fine, otherwise why it cannot run here. */
    val compat = mutableStateMapOf<String, String>().also { it.putAll(store.data.value.compat) }
    var scan by mutableStateOf<ScanState?>(null)
        private set

    fun blockedReason(p: RepoPlugin): String? = compat[compatKey(p)]?.takeIf { it.isNotEmpty() }

    /**
     * Re-reads every repository, then downloads and statically checks each Android plugin not already judged
     * (nothing is run). Plugins that need classes only the Android app has are marked, and hidden from the install list.
     */
    fun scanAll() {
        if (scan != null) return
        scope.launch(Dispatchers.IO) {
            try {
                val repos = store.data.value.repos
                val read = AtomicInteger()
                scan = ScanState("Reading repositories", 0, repos.size)
                coroutineScope {
                    repos.map { u -> async { repoIndexes[u] = runCatching { repositories.fetchIndex(u) }; scan = ScanState("Reading repositories", read.incrementAndGet(), repos.size) } }.awaitAll()
                }
                val todo = repos.flatMap { u -> repoIndexes[u]?.getOrNull()?.plugins.orEmpty().map { u to it } }
                    .filter { it.second.android && compatKey(it.second) !in compat }.distinctBy { compatKey(it.second) }
                val done = AtomicInteger()
                val gate = Semaphore(3) // each check holds a converted plugin in memory
                val found = java.util.concurrent.ConcurrentHashMap<String, String>()
                scan = ScanState("Checking plugins", 0, todo.size)
                coroutineScope {
                    todo.map { (u, p) ->
                        async {
                            gate.withPermit {
                                // A failed download says nothing about the plugin, so it is left unjudged and retried next scan
                                val bytes = runCatching { repositories.fetchCs3(u, p) }.getOrNull()
                                if (bytes != null) {
                                    // Running out of memory says nothing about the plugin either, so that one is retried next scan too
                                    val verdict = try { Cs3.check(bytes) ?: "" } catch (e: OutOfMemoryError) { null } catch (e: Exception) { e.message ?: "Could not be checked" }
                                    if (verdict != null) { compat[compatKey(p)] = verdict; found[compatKey(p)] = verdict }
                                }
                                scan = ScanState("Checking plugins", done.incrementAndGet(), todo.size)
                            }
                        }
                    }.awaitAll()
                }
                store.mergeCompat(found)
            } finally { scan = null }
        }
    }

    /** Progress of the first-run install of default plugins, or null when idle. */
    var defaultInstall by mutableStateOf<ScanState?>(null)
        private set

    /**
     * Installs every plugin of [repos] that is not installed yet and can run on desktop. Each id is remembered once handled,
     * so a plugin the user removes stays removed; a plugin that failed on the network is tried again next time.
     */
    fun installDefaultPlugins(repos: List<String>) {
        scope.launch(Dispatchers.IO) { runDefaultInstall(repos) }
    }

    private fun runDefaultInstall(repos: List<String>) {
        if (defaultInstall != null) return
        try {
            for (repoUrl in repos) {
                defaultInstall = ScanState("Reading plugin list", 0, 0)
                val index = runCatching { repositories.fetchIndex(repoUrl) }.getOrNull() ?: continue
                repoIndexes[repoUrl] = Result.success(index)
                val handled = store.data.value.defaultInstalled.toSet()
                val todo = index.plugins.filter { "$repoUrl|${it.internalName}" !in handled }
                todo.forEachIndexed { i, p ->
                    defaultInstall = ScanState("Installing plugins", i, todo.size)
                    if (installOne(repoUrl, p)) store.markDefaultInstalled(listOf("$repoUrl|${p.internalName}"))
                }
            }
        } finally { defaultInstall = null }
    }

    /** Result of the last repository refresh and plugin update, e.g. "Checked 14:05: 2 updated". */
    var updateNote by mutableStateOf<String?>(null)
        private set
    var updating by mutableStateOf(false)
        private set
    private val updateBusy = java.util.concurrent.atomic.AtomicBoolean()
    private val maintaining = java.util.concurrent.atomic.AtomicBoolean()

    /** At start and every 6 hours: installs default plugins, refreshes repositories and (if switched on) updates installed plugins. */
    fun startMaintenance(defaults: List<String>) {
        if (!maintaining.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            while (true) {
                runDefaultInstall(defaults)
                if (store.data.value.autoUpdatePlugins) runAutoUpdate()
                kotlinx.coroutines.delay(6 * 3_600_000L)
            }
        }
    }

    fun updatePluginsNow() { scope.launch(Dispatchers.IO) { runAutoUpdate() } }

    /** Refreshes every repository, then updates each installed plugin whose repository now offers a newer version. */
    private suspend fun runAutoUpdate() {
        if (!updateBusy.compareAndSet(false, true)) return
        updating = true
        try {
            val sources = store.data.value.pluginSources
            val repos = (store.data.value.repos + sources.values.map { it.repo }).distinct()
            // A repository that is down keeps its last good index
            coroutineScope {
                repos.map { u -> async { runCatching { repositories.fetchIndex(u) }.onSuccess { repoIndexes[u] = Result.success(it) } } }.awaitAll()
            }
            var updated = 0
            var failed = 0
            for ((jarName, from) in sources) {
                val jar = java.io.File(pluginManager.dir, jarName)
                if (!jar.exists()) continue
                val p = repoIndexes[from.repo]?.getOrNull()?.plugins?.firstOrNull { it.internalName + ".jar" == jarName } ?: continue
                if (p.version <= from.version) continue
                if (updateOne(from.repo, p, jar)) updated++ else failed++
            }
            store.touchPluginUpdate()
            updateNote = "Checked ${java.time.LocalTime.now().withSecond(0).withNano(0)}: " +
                (if (updated == 0 && failed == 0) "everything is up to date" else "$updated updated" + if (failed > 0) ", $failed failed" else "")
        } finally { updating = false; updateBusy.set(false) }
    }

    /** Fetches and checks the new version first, so a bad download never touches the working plugin; puts the old jar back if the new one will not load. */
    private fun updateOne(repoUrl: String, p: RepoPlugin, jar: java.io.File): Boolean {
        val stage = java.nio.file.Files.createTempDirectory("onestream-upd").toFile()
        try {
            val fresh = if (p.android) {
                val bytes = repositories.fetchCs3(repoUrl, p)
                val why = Cs3.check(bytes)
                if (why != null) { compat[compatKey(p)] = why; store.mergeCompat(mapOf(compatKey(p) to why)); return false }
                repositories.installCs3(bytes, p, stage)
            } else repositories.download(repoUrl, p, stage)
            val old = jar.readBytes()
            val oldApproval = store.data.value.approvedPlugins[jar.name]
            pluginManager.unload(jar)
            try {
                fresh.copyTo(jar, overwrite = true)
                if (p.android) store.approvePlugin(jar.name, app.onestream.update.Updater.sha256(jar))
                loadJar(jar)
                if (installed.firstOrNull { it.file == jar }?.error != null) error("new version does not load")
            } catch (e: Exception) {
                pluginManager.unload(jar)
                jar.writeBytes(old)
                if (oldApproval != null) store.approvePlugin(jar.name, oldApproval)
                loadJar(jar)
                return false
            }
            store.recordPlugin(jar.name, repoUrl, p.version)
            return true
        } catch (e: Exception) {
            return false
        } catch (e: OutOfMemoryError) {
            return false
        } finally { stage.deleteRecursively() }
    }

    /** True when the plugin is settled (installed, already there, or cannot run here); false when a retry is worthwhile. */
    private fun installOne(repoUrl: String, p: RepoPlugin): Boolean {
        if (java.io.File(pluginManager.dir, p.internalName + ".jar").exists()) return true
        try {
            if (!p.android) {
                val jar = repositories.download(repoUrl, p, pluginManager.dir)
                store.recordPlugin(jar.name, repoUrl, p.version); loadJar(jar); return true
            }
            val bytes = repositories.fetchCs3(repoUrl, p)
            val why = try { Cs3.check(bytes) } catch (e: OutOfMemoryError) { return false } catch (e: Exception) { e.message ?: "Could not be checked" }
            if (why != null) { compat[compatKey(p)] = why; store.mergeCompat(mapOf(compatKey(p) to why)); return true }
            val jar = repositories.installCs3(bytes, p, pluginManager.dir)
            store.approvePlugin(jar.name, app.onestream.update.Updater.sha256(jar))
            store.recordPlugin(jar.name, repoUrl, p.version)
            loadJar(jar)
            return true
        } catch (e: app.onestream.plugins.RepoException) {
            return false // could not reach it or the hash did not match: try again next start
        } catch (e: Exception) {
            return true // converting or loading failed for this plugin itself; trying again would fail the same way
        }
    }

    fun refreshRepo(url: String) {
        scope.launch(Dispatchers.IO) {
            repoIndexes[url] = runCatching { repositories.fetchIndex(url) }
        }
    }

    fun api(name: String): MainAPI? = APIHolder.getApiFromNameNull(name)

    private fun applyInstalled(list: List<InstalledPlugin>) {
        installed = list
        providers = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }.sortedBy { it.name }
    }
}