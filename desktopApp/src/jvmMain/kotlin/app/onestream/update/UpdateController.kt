package app.onestream.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.onestream.APP_VERSION
import app.onestream.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateStatus {
    data class Disabled(val reason: String) : UpdateStatus
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    /** Found, but auto-update is off: waiting for the user. */
    data class Available(val feed: UpdateFeed) : UpdateStatus
    data class Downloading(val version: String, val progress: Float) : UpdateStatus
    data class Ready(val staged: Staged) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/**
 * UI-facing update state. Silent by default: checks on start, downloads in the background, and applies
 * the update when the app is closed (or immediately on "Restart to update").
 */
class UpdateController(
    private val scope: CoroutineScope,
    private val store: Store,
    private val installDir: File?,
    private val stageDir: File?,
    /** Feed baked into the build or set by environment; when present the user cannot change it. */
    private val fixedFeedUrl: String?,
) {
    private var updater: Updater? = null
    /** Set by [updateNow]: restart into the update as soon as it is staged. */
    private var restartWhenReady = false

    var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        private set

    val feedEditable get() = fixedFeedUrl == null
    val feedUrl get() = fixedFeedUrl ?: store.data.value.updateUrl

    init { configure() }

    private fun configure() {
        val url = feedUrl
        updater = if (url.isNotBlank() && installDir != null && stageDir != null) Updater(url, installDir, stageDir, APP_VERSION) else null
        status = when {
            installDir == null -> UpdateStatus.Disabled("Not an installed build (running from source).")
            url.isBlank() -> UpdateStatus.Disabled("No update feed yet. Paste the address of an update.json below.")
            else -> UpdateStatus.Idle
        }
    }

    /** Saves the feed the user typed (blank clears it) and checks it straight away. Throws if the address is not allowed. */
    fun setFeedUrl(url: String) {
        if (!feedEditable) return
        val u = url.trim()
        if (u.isNotEmpty()) app.onestream.plugins.Repositories.secure(u)
        store.setUpdateUrl(u)
        configure()
        check()
    }

    fun checkOnStart() { if (store.data.value.autoUpdate) check() }

    fun check() {
        val u = updater ?: return
        if (status is UpdateStatus.Checking || status is UpdateStatus.Downloading) return
        status = UpdateStatus.Checking
        scope.launch(Dispatchers.IO) {
            status = try {
                val feed = u.check()
                when {
                    feed == null -> UpdateStatus.UpToDate
                    store.data.value.autoUpdate -> { download(feed); status }
                    else -> UpdateStatus.Available(feed)
                }
            } catch (e: Exception) { UpdateStatus.Failed(e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun download(feed: UpdateFeed) {
        val u = updater ?: return
        status = UpdateStatus.Downloading(feed.version, 0f)
        status = try {
            UpdateStatus.Ready(u.stage(feed) { status = UpdateStatus.Downloading(feed.version, it) })
        } catch (e: Exception) { UpdateStatus.Failed(e.message ?: e.javaClass.simpleName) }
        if (restartWhenReady && status is UpdateStatus.Ready) restartInto()
        restartWhenReady = false
    }

    /** One click: fetches the update (a small patch when one exists for this version) and restarts into it. */
    fun updateNow() {
        when (status) {
            is UpdateStatus.Ready -> restartInto()
            is UpdateStatus.Available -> { restartWhenReady = true; downloadAvailable() }
            else -> {}
        }
    }

    private fun restartInto() { if (applyNow(relaunch = true)) kotlin.system.exitProcess(0) }

    fun downloadAvailable() {
        val a = status as? UpdateStatus.Available ?: return
        scope.launch(Dispatchers.IO) { download(a.feed) }
    }

    /** Starts the swap script. The caller must exit the app straight after. Returns false if nothing is staged. */
    fun applyNow(relaunch: Boolean): Boolean {
        val ready = status as? UpdateStatus.Ready ?: return false
        val dir = installDir ?: return false
        Applier.launch(ready.staged, dir, EXE_NAME, relaunch, stageDir ?: return false)
        status = UpdateStatus.Idle
        return true
    }

    fun applyOnExit() { applyNow(relaunch = false) }

    companion object {
        const val EXE_NAME = "1-stream.exe"

        fun create(scope: CoroutineScope, store: Store, stageRoot: File): UpdateController {
            val url = System.getenv("ONESTREAM_UPDATE_URL")?.takeIf { it.isNotBlank() }
                ?: System.getProperty("onestream.update.url")?.takeIf { it.isNotBlank() }
            val exe = System.getProperty("onestream.install.dir")?.let { File(it) }
                ?: ProcessHandle.current().info().command().map(::File).filter { it.name.equals(EXE_NAME, true) }.map { it.parentFile }.orElse(null)
            return UpdateController(scope, store, exe, exe?.let { File(stageRoot, "update") }, url)
        }
    }
}
