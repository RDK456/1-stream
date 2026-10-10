package app.onestream

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.onestream.plugins.DEFAULT_PLUGIN_REPOS
import app.onestream.plugins.DEFAULT_REPOS
import app.onestream.ui.App
import app.onestream.ui.LocalReduceMotionSnapshot
import app.onestream.ui.OneStreamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--selftest") {
        SelfTest.run(args.getOrNull(1) ?: error("usage: --selftest <plugin.jar>"))
        return
    }
    if (args.firstOrNull() == "--update-selftest") { updateSelfTest(); return }
    if (args.firstOrNull() == "--cs3-selftest") { cs3SelfTest(args.getOrNull(1) ?: error("usage: --cs3-selftest <plugin.cs3>")); return }
    runApp()
    // libVLC's shutdown hooks can deadlock against an in-flight player release; nothing is left to clean up.
    Runtime.getRuntime().halt(0)
}

private fun runApp() = application {
    val scope = rememberCoroutineScope()
    val app = remember { AppState(scope) }
    val windowState = rememberWindowState(size = DpSize(1280.dp, 800.dp))
    val icon = remember { runCatching { BitmapPainter(useResource("icon.png", ::loadImageBitmap)) }.getOrNull() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { app.loadPluginsBlocking() }
        app.store.seedDefaultRepos(DEFAULT_REPOS)
        app.updates.checkOnStart()
    }

    Window(onCloseRequest = { app.updates.applyOnExit(); exitApplication() }, title = APP_NAME, icon = icon, state = windowState) {
        val data by app.store.data.collectAsState()
        LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(420, 320) } // below this nothing fits
        // Plugins are only fetched once the user has read the notice about what plugins are
        LaunchedEffect(data.noticeAccepted) { if (data.noticeAccepted) app.installDefaultPlugins(DEFAULT_PLUGIN_REPOS) }
        LocalReduceMotionSnapshot.value = data.reduceMotion
        OneStreamTheme(data.reduceMotion) { App(app, windowState) }
    }
}
/** Headless: check the feed, stage the update, apply it silently (no relaunch) and exit. Used to verify installs. */
private fun updateSelfTest() {
    val c = app.onestream.update.UpdateController.create(kotlinx.coroutines.CoroutineScope(Dispatchers.IO), Store(AppPaths.data), AppPaths.root)
    println("version=$APP_VERSION status=${c.status}")
    c.check()
    val end = System.currentTimeMillis() + 120_000
    while (System.currentTimeMillis() < end && c.status.let { it is app.onestream.update.UpdateStatus.Idle || it is app.onestream.update.UpdateStatus.Checking || it is app.onestream.update.UpdateStatus.Downloading }) Thread.sleep(200)
    println("status=${c.status}")
    val applied = c.applyNow(relaunch = false)
    println("applied=$applied")
    Runtime.getRuntime().halt(if (applied) 0 else 2)
}
/** Headless: convert an Android .cs3, load it, and search with the first provider it registers. Used to verify packaged builds. */
private fun cs3SelfTest(path: String) {
    val dir = java.nio.file.Files.createTempDirectory("cs3st").toFile()
    val jar = java.io.File(dir, "plugin.jar")
    var code = 1
    try {
        app.onestream.plugins.Cs3.convert(java.io.File(path).readBytes(), jar)
        val r = app.onestream.plugins.PluginManager(dir) { true }.load(jar)
        println("loaded ok=${r.ok} error=${r.error} providers=${r.providers}")
        val api = r.providers.firstOrNull()?.let { com.lagradost.cloudstream3.APIHolder.getApiFromNameNull(it) }
        if (api != null) {
            val hits = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(60_000) { api.search("sintel", 1)?.items.orEmpty() } }
            println("search(sintel)=${hits.size} first=${hits.firstOrNull()?.name}")
            if (hits.isNotEmpty()) code = 0
        } else if (r.ok) code = 0
    } catch (e: Throwable) { println("FAIL ${e.javaClass.simpleName}: ${e.message}") }
    Runtime.getRuntime().halt(code)
}