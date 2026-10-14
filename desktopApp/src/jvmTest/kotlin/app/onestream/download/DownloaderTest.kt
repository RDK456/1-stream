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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
class DownloaderTest {
    private val tmp = Files.createTempDirectory("dl").toFile()
    private val routes = mutableMapOf<String, ByteArray>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = routes[ex.requestURI.path]
            if (body == null) ex.sendResponseHeaders(404, -1) else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
            ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val store = Store(File(tmp, "data.json"))
    private val dl = Downloader(CoroutineScope(SupervisorJob() + Dispatchers.IO), store, File(tmp, "out"))

    @AfterTest fun done() { server.stop(0); tmp.deleteRecursively() }

    private fun link(url: String, quality: Int, type: ExtractorLinkType) =
        ExtractorLink(source = "t", name = "t", url = url, referer = "", quality = quality, type = type)

    private fun finish(job: DownloadJob) {
        val end = System.currentTimeMillis() + 15_000
        while (job in dl.jobs && job.state == DlState.Running && System.currentTimeMillis() < end) Thread.sleep(50)
        assertEquals(DlState.Running, job.state, "download failed: ${job.error}")
        assertTrue(job !in dl.jobs, "download did not finish")
    }

    @Test fun directFileIsSavedAndRecorded() {
        val movie = ByteArray(300_000) { (it % 251).toByte() }
        routes["/movie.mp4"] = movie
        val job = dl.start("My: Movie?", link("$base/movie.mp4?token=1", 720, ExtractorLinkType.VIDEO))
        finish(job)
        assertEquals("My_ Movie_ [720p].mp4", job.file.name) // characters Windows forbids are replaced
        assertContentEquals(movie, job.file.readBytes())
        assertEquals(job.file.absolutePath, store.data.value.downloads.single().path)
    }

    @Test fun subtitlesAreSavedBesideTheVideo() {
        routes["/m.mp4"] = ByteArray(1000) { 1 }
        routes["/en.vtt"] = "WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n".toByteArray()
        routes["/fr.srt"] = "1\n00:00:00,000 --> 00:00:01,000\nBonjour\n".toByteArray()
        val job = dl.start("Film", link("$base/m.mp4", 720, ExtractorLinkType.VIDEO), listOf(app.onestream.DlSub("English", "$base/en.vtt"), app.onestream.DlSub("Français", "$base/fr.srt"), app.onestream.DlSub("Gone", "$base/missing.srt")))
        finish(job)
        val names = job.file.parentFile.list().orEmpty().toSet()
        assertTrue("Film [720p].English.vtt" in names && "Film [720p].Français.srt" in names, "subtitles saved with the video's name: $names")
        assertEquals(3, store.data.value.downloads.single().src?.subs?.size)
        assertEquals(3, names.size, "video + 2 subtitles, nothing for the missing one: $names")
    }

    @Test fun hlsPicksTheRequestedQualityAndDecrypts() {
        val key = ByteArray(16) { (it * 7).toByte() }
        val plain = List(3) { i -> ByteArray(5_000) { (i * 31 + it).toByte() } }
        fun enc(i: Int, b: ByteArray): ByteArray {
            val iv = ByteArray(16).also { it[15] = i.toByte() } // no IV in the playlist: the segment number is the IV
            return Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(b)
        }
        routes["/master.m3u8"] = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000,RESOLUTION=1920x1080
            sub/high.m3u8
        """.trimIndent().toByteArray()
        routes["/low.m3u8"] = "#EXTM3U\n#EXTINF:4,\nlow0.ts\n#EXT-X-ENDLIST\n".toByteArray()
        routes["/low0.ts"] = "WRONG QUALITY".toByteArray()
        routes["/sub/high.m3u8"] = ("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n" +
            (0 until 3).joinToString("") { "#EXTINF:4,\ns$it.ts\n" } + "#EXT-X-ENDLIST\n").toByteArray()
        routes["/sub/key.bin"] = key
        plain.forEachIndexed { i, b -> routes["/sub/s$i.ts"] = enc(i, b) }

        val job = dl.start("Show S1E1", link("$base/master.m3u8", 1080, ExtractorLinkType.M3U8))
        finish(job)
        assertEquals("Show S1E1 [1080p].ts", job.file.name)
        assertContentEquals(plain.reduce { a, b -> a + b }, job.file.readBytes())
    }
}
