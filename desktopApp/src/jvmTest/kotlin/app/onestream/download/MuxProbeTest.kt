package app.onestream.download

import app.onestream.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Joins a real video with a separate audio file through libVLC, the way HLS downloads with a separate sound track are finished. */
class MuxProbeTest {
    @Test fun joinsVideoAndAudio() {
        val video = File(System.getenv("PROBE_VIDEO") ?: return)
        val audio = File(System.getenv("PROBE_AUDIO") ?: return)
        val tmp = Files.createTempDirectory("mux").toFile()
        val dl = Downloader(CoroutineScope(SupervisorJob() + Dispatchers.IO), Store(File(tmp, "d.json")), File(tmp, "out"))
        val out = File(tmp, "joined.mp4")
        val silent = File(tmp, "silent.ts")
        val aac = File(tmp, "audio.ts")
        run {
            val latch = java.util.concurrent.CountDownLatch(1)
            val mp = app.onestream.player.Vlc.factory.mediaPlayers().newMediaPlayer()
            mp.events().addMediaPlayerEventListener(object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
                override fun finished(p: uk.co.caprica.vlcj.player.base.MediaPlayer) { latch.countDown() }
                override fun error(p: uk.co.caprica.vlcj.player.base.MediaPlayer) { latch.countDown() }
            })
            mp.media().play(video.absolutePath, ":no-sout-video", ":sout=#std{access=file,mux=ts,dst='${aac.absolutePath}'}")
            latch.await(5, java.util.concurrent.TimeUnit.MINUTES); mp.release()
        }
        run {
            val latch = java.util.concurrent.CountDownLatch(1)
            val mp = app.onestream.player.Vlc.factory.mediaPlayers().newMediaPlayer()
            mp.events().addMediaPlayerEventListener(object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
                override fun finished(p: uk.co.caprica.vlcj.player.base.MediaPlayer) { latch.countDown() }
                override fun error(p: uk.co.caprica.vlcj.player.base.MediaPlayer) { latch.countDown() }
            })
            mp.media().play(video.absolutePath, ":no-sout-audio", ":sout=#std{access=file,mux=ts,dst='${silent.absolutePath}'}")
            latch.await(5, java.util.concurrent.TimeUnit.MINUTES); mp.release()
        }
        val t = System.currentTimeMillis()
        val ok = dl.mux(silent, aac, out)
        println("MUX ok=$ok bytes=${out.length()} in ${System.currentTimeMillis() - t} ms")
        fun tracks(f: File): String {
            val m = app.onestream.player.Vlc.factory.media().newMedia(f.absolutePath)
            m.parsing().parse(); Thread.sleep(1500)
            return "audio=${m.info().audioTracks().size} video=${m.info().videoTracks().size}"
        }
        println("MUX silent ${tracks(silent)} aac ${tracks(aac)} | output ${tracks(out)}")
        assertTrue(ok && out.length() > 0)
    }
}
