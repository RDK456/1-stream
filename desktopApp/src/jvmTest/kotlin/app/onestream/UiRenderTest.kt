package app.onestream

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.WindowState
import app.onestream.plugins.PluginManager
import app.onestream.plugins.RepoIndex
import app.onestream.plugins.RepoPlugin
import app.onestream.ui.App
import app.onestream.ui.OneStreamTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the real UI offscreen with the real demo plugin loaded through the real loader.
 * Screenshots land in build/ui-shots for eyeballing; assertions only check each screen drew something.
 */
class UiRenderTest {
    private val shots = File(System.getProperty("shots.dir")).also { it.mkdirs() }

    private fun render(name: String, scene: ImageComposeScene, settleMs: Long = 1500): Int {
        var t = 0L
        val end = System.currentTimeMillis() + settleMs
        while (System.currentTimeMillis() < end) { scene.render(t * 1_000_000); t += 50; Thread.sleep(50) }
        val img = scene.render(t * 1_000_000)
        val bytes = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(shots, "$name.png").writeBytes(bytes)
        // A blank screen compresses to almost nothing
        return bytes.size
    }

    /** Phone-sized and very large windows: nothing may crash, and every screen must still draw. */
    @Test
    fun rendersAtAnySize() {
        val home = Files.createTempDirectory("ui").toFile()
        val pm = PluginManager(File(home, "plugins").also { it.mkdirs() })
        File(System.getProperty("blender.jar")).copyTo(File(pm.dir, "blender-demo.jar"))
        val app = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), Store(File(home, "data.json")), pm)
        app.loadPluginsBlocking()
        app.store.acceptNotice()
        for ((w, h) in listOf(420 to 640, 800 to 500, 2200 to 1200)) {
            val scene = ImageComposeScene(w, h, Density(1f)) { OneStreamTheme { App(app, WindowState()) } }
            try {
                for (t in Tab.entries) {
                    app.tab = t
                    assertTrue(render("size-${w}x$h-${t.name}", scene, if (t == Tab.Search) 3000 else 600) > 3_000, "$t at ${w}x$h drew nothing")
                }
                app.tab = Tab.Search
                app.push(Screen.Details("Blender Open Movies", "https://studio.blender.org/film/sintel-trailer"))
                assertTrue(render("size-${w}x$h-details", scene, 3000) > 3_000)
                app.pop()
            } finally { scene.close() }
        }
        app.pluginManager.loadAll().forEach { app.pluginManager.unload(it.file) }
    }

    @Test
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    fun rendersEveryScreen() {
        val home = Files.createTempDirectory("ui").toFile()
        val pm = PluginManager(File(home, "plugins").also { it.mkdirs() })
        File(System.getProperty("blender.jar")).copyTo(File(pm.dir, "blender-demo.jar"))
        val app = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), Store(File(home, "data.json")), pm)
        app.loadPluginsBlocking()
        assertTrue(app.providers.any { it.name == "Blender Open Movies" }, "demo plugin must load")

        val windowState = WindowState()
        val scene = ImageComposeScene(1280, 800, Density(1f)) { OneStreamTheme { App(app, windowState) } }
        try {
            assertTrue(render("1-first-run-notice", scene, 500) > 5_000)
            app.store.acceptNotice()
            assertTrue(render("2-browse", scene, 4000) > 5_000)
            app.push(Screen.Details("Blender Open Movies", "https://studio.blender.org/film/sintel-trailer"))
            assertTrue(render("3-details", scene, 4000) > 5_000)
            app.store.toggleBookmark(Bookmark("Sintel (Trailer)", "https://studio.blender.org/film/sintel-trailer", "Blender Open Movies", null))
            app.store.saveProgress(HistoryEntry("Sintel (Trailer)", "https://studio.blender.org/film/sintel-trailer", "Blender Open Movies", null, "sintel-trailer", null, 20_000, 52_000))
            app.pop(); app.tab = Tab.Library
            assertTrue(render("4-library", scene, 1500) > 5_000)
            app.tab = Tab.Plugins
            assertTrue(render("5-plugins", scene, 1500) > 5_000)
            // Discover panel with a populated catalog (no network: indexes are injected)
            fun p(n: String, lang: String, vararg t: String) = RepoPlugin(n, n.lowercase(), 1, 0, "$n.cs3", "", "Streams $n titles", android = true, language = lang, types = t.toList())
            app.store.addRepo("https://repo.test/a.json"); app.store.addRepo("https://repo.test/b.json")
            app.repoIndexes["https://repo.test/a.json"] = Result.success(RepoIndex("Alpha repo", listOf(
                p("MovieBox", "en", "Movie", "TvSeries"), p("HindiHub", "hi", "Movie"), p("AnimeKai", "ja", "Anime"), p("SpicyOne", "en", "NSFW"), p("Cartoons", "en", "Cartoon"),
            )))
            app.repoIndexes["https://repo.test/b.json"] = Result.failure(RuntimeException("HTTP 404"))
            assertTrue(render("5b-plugins-catalog", scene, 1500) > 5_000)
            app.tab = Tab.About
            assertTrue(render("6-about", scene, 800) > 5_000)

            // Real playback inside the composed UI: provider -> links -> libVLC -> Compose Image
            app.tab = Tab.Search
            app.push(Screen.Play("Blender Open Movies", "https://studio.blender.org/film/sintel-trailer", "Sintel (Trailer)", null, "sintel-trailer", null))
            render("7a-player-controls", scene, 3_000)
            // Real key presses: P opens playback settings, H the shortcut list, each closed by pressing it again
            fun press(key: androidx.compose.ui.input.key.Key) =
                scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(key, androidx.compose.ui.input.key.KeyEventType.KeyDown))
            press(androidx.compose.ui.input.key.Key.P)
            assertTrue(render("7b-player-settings", scene, 1_000) > 5_000)
            press(androidx.compose.ui.input.key.Key.P)
            press(androidx.compose.ui.input.key.Key.H)
            assertTrue(render("7c-player-shortcuts", scene, 1_000) > 5_000)
            press(androidx.compose.ui.input.key.Key.H)
            var size = render("7-player", scene, 20_000)
            // The trailer streams from the internet; on a slow start give it one more window before failing
            if (size <= 60_000) size = render("7-player", scene, 20_000)
            println("player screenshot bytes=$size")
            assertTrue(size > 60_000, "player screen should contain decoded video, got $size bytes")
            app.pop()
            render("8-after-player", scene, 500)
            val saved = app.store.data.value.history.firstOrNull { it.data == "sintel-trailer" }
            assertTrue(saved != null && saved.durationMs > 0, "watch progress should be saved when leaving the player")
        } finally {
            scene.close()
            app.pluginManager.loadAll().forEach { app.pluginManager.unload(it.file) }
        }
    }
}