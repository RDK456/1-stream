package app.onestream

import app.onestream.plugins.PluginManager
import app.onestream.player.Media
import app.onestream.player.VideoPlayer
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `1-stream --selftest <plugin.jar>`: runs the real pipeline without a window and exits 0 on success.
 * plugin load -> search -> load -> loadLinks -> libVLC decodes frames.
 */
object SelfTest {
    fun run(jarPath: String) {
        fun step(ok: Boolean, what: String) {
            println((if (ok) "PASS  " else "FAIL  ") + what)
            if (!ok) Runtime.getRuntime().halt(1)
        }
        val pm = PluginManager(AppPaths.plugins)
        val installed = pm.load(File(jarPath))
        step(installed.ok, "plugin loaded (${installed.error ?: installed.providers.joinToString()})")
        val api = APIHolder.allProviders.firstOrNull()
        step(api != null, "provider registered")
        api!!
        runBlocking {
            val results = api.search("sintel", 1)?.items.orEmpty()
            step(results.isNotEmpty(), "search returned ${results.size} result(s)")
            val load = api.load(results.first().url)
            step(load is MovieLoadResponse, "load returned ${load?.name}")
            val links = mutableListOf<ExtractorLink>()
            api.loadLinks((load as MovieLoadResponse).dataUrl, false, {}, { links.add(it) })
            step(links.isNotEmpty(), "loadLinks returned ${links.size} link(s): ${links.joinToString { it.name }}")
            val best = links.maxBy { it.quality }

            val player = VideoPlayer()
            player.play(Media(best.url, best.referer, null))
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline && !(player.frame != null && player.positionMs > 2_000)) {
                if (player.error != null) break
                Thread.sleep(250)
            }
            val f = player.frame
            println("      frame=${f?.width}x${f?.height} position=${player.positionMs}ms length=${player.lengthMs}ms playing=${player.playing}")
            step(player.error == null, "player reported no error")
            step(f != null && f.width > 0, "libVLC delivered video frames")
            step(player.positionMs > 2_000, "playback advanced past 2s")
            player.seekTo(player.lengthMs / 2)
            Thread.sleep(1500)
            step(player.positionMs > player.lengthMs / 2 - 1500, "seek works (now ${player.positionMs}ms)")
            player.release()
        }
        pm.unload(File(jarPath))
        println("SELFTEST OK"); System.out.flush()
        Runtime.getRuntime().halt(0)
    }
}