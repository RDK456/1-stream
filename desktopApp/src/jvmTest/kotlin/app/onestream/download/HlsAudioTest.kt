package app.onestream.download

import app.onestream.Store
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A master playlist whose sound lives in its own audio playlist must come out as one file with both tracks.
 * Needs a real video: set PROBE_VIDEO to any mp4 with sound (skipped otherwise).
 */
@Suppress("DEPRECATION")
class HlsAudioTest {
    private fun strip(src: File, dst: File, vararg opt: String) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val mp = app.onestream.player.Vlc.factory.mediaPlayers().newMediaPlayer()
        mp.events().addMediaPlayerEventListener(object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
            override fun finished(p: uk.co.caprica.vlcj.player.base.MediaPlayer) { latch.countDown() }
            override fun error(p: uk.co.caprica.vlcj.player.base.MediaPlayer) { latch.countDown() }
        })
        mp.media().play(src.absolutePath, *opt, ":sout=#std{access=file,mux=ts,dst='${dst.absolutePath}'}")
        latch.await(5, java.util.concurrent.TimeUnit.MINUTES); mp.release()
    }

    private fun tracks(f: File): Pair<Int, Int> {
        val m = app.onestream.player.Vlc.factory.media().newMedia(f.absolutePath)
        m.parsing().parse(); Thread.sleep(2000)
        return m.info().audioTracks().size to m.info().videoTracks().size
    }

    @Test fun separateAudioPlaylistIsJoined() {
        val source = File(System.getenv("PROBE_VIDEO") ?: return)
        val tmp = Files.createTempDirectory("hlsaudio").toFile()
        val vts = File(tmp, "v.ts").also { strip(source, it, ":no-sout-audio") }
        val ats = File(tmp, "a.ts").also { strip(source, it, ":no-sout-video") }
        val routes = HashMap<String, ByteArray>()
        fun segments(name: String, f: File): String {
            val bytes = f.readBytes(); val step = (bytes.size / 3 / 188) * 188
            val parts = (0 until 3).map { i -> bytes.copyOfRange(i * step, if (i == 2) bytes.size else (i + 1) * step) }
            parts.forEachIndexed { i, p -> routes["/$name$i.ts"] = p }
            return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:0\n" + parts.indices.joinToString("") { "#EXTINF:10,\n$name$it.ts\n" } + "#EXT-X-ENDLIST\n"
        }
        routes["/video.m3u8"] = segments("v", vts).toByteArray()
        routes["/audio.m3u8"] = segments("a", ats).toByteArray()
        routes["/master.m3u8"] = ("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"en\",DEFAULT=YES,URI=\"audio.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO=\"aud\"\nvideo.m3u8\n").toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val b = routes[ex.requestURI.path]
                if (b == null) ex.sendResponseHeaders(404, -1) else { ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
                ex.close()
            }
            start()
        }
        try {
            val dl = Downloader(CoroutineScope(SupervisorJob() + Dispatchers.IO), Store(File(tmp, "d.json")), File(tmp, "out"))
            val link = ExtractorLink(source = "t", name = "t", url = "http://127.0.0.1:${server.address.port}/master.m3u8", referer = "", quality = 720, type = ExtractorLinkType.M3U8)
            val job = dl.start("Sample", link)
            val t = System.currentTimeMillis()
            while (dl.jobs.contains(job) && job.state == DlState.Running && System.currentTimeMillis() - t < 120_000) Thread.sleep(200)
            assertEquals(DlState.Running, job.state, "failed: ${job.error}")
            println("HLSAUDIO ${job.file.name} ${job.file.length()} ${tracks(job.file)} (audio, video)")
            assertTrue(job.file.length() > 0 && tracks(job.file).first >= 1 && tracks(job.file).second >= 1, "joined file has sound and picture")
        } finally { server.stop(0); tmp.deleteRecursively() }
    }
}
