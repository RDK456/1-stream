package app.onestream.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.onestream.DownloadRecord
import app.onestream.Store
import app.onestream.plugins.Repositories
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import app.onestream.DlSource
import app.onestream.DlSub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

enum class DlState { Running, Failed }

/** One download in progress (or failed). Finished downloads move to [Store] as a [DownloadRecord]. */
class DownloadJob(val id: String, val title: String, val label: String, file: File, internal val link: ExtractorLink, internal val subs: List<DlSub> = emptyList()) {
    var file by mutableStateOf(file)
    var done by mutableLongStateOf(0L)
    var total by mutableLongStateOf(0L)
    var state by mutableStateOf(DlState.Running)
    var error by mutableStateOf<String?>(null)
    /** HLS counts segments rather than bytes, so no size is known until it finishes. */
    var inBytes by mutableStateOf(true)
    internal var task: Job? = null
    val progress: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** Saves a chosen stream to disk: plain files directly, HLS by fetching and joining its segments (AES-128 supported). */
class Downloader(
    private val scope: CoroutineScope,
    private val store: Store,
    val dir: File = defaultDir(),
    private val http: HttpClient = Repositories.defaultClient(),
) {
    val jobs = mutableStateListOf<DownloadJob>()

    fun start(title: String, link: ExtractorLink, subs: List<DlSub> = emptyList()): DownloadJob {
        val hls = isHls(link)
        val ext = if (hls) "ts" else link.url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in VIDEO_EXT } ?: "mp4"
        val label = qualityLabel(link.quality)
        dir.mkdirs()
        val job = DownloadJob(UUID.randomUUID().toString(), title, label, uniqueFile(dir, "${safe(title)} [$label]", ext), link, subs)
        jobs += job
        run(job)
        return job
    }

    private fun isHls(link: ExtractorLink) = link.type == ExtractorLinkType.M3U8 || link.url.substringBefore('?').endsWith(".m3u8", true)

    private fun run(job: DownloadJob) {
        val link = job.link
        job.task = scope.launch(Dispatchers.IO) {
            try {
                if (isHls(link)) hls(link, job) else direct(link, job)
                saveSubs(job)
                val src = DlSource(link.url, link.referer, link.quality, isHls(link), link.headers, job.subs)
                store.addDownload(DownloadRecord(job.id, job.title, job.label, job.file.absolutePath, job.file.length(), System.currentTimeMillis(), src))
                jobs.remove(job)
            } catch (e: CancellationException) {
                part(job).delete(); throw e
            } catch (e: Throwable) {
                part(job).delete(); job.state = DlState.Failed; job.error = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Saves each offered subtitle beside the video as "<video name>.<language>.<ext>", which players such as VLC load by themselves. A subtitle that fails is skipped. */
    private fun saveSubs(job: DownloadJob) {
        val base = job.file.nameWithoutExtension
        job.subs.distinctBy { it.url }.take(12).forEach { s ->
            runCatching {
                val b = HttpRequest.newBuilder(URI(s.url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "Mozilla/5.0")
                if (job.link.referer.isNotBlank()) b.header("Referer", job.link.referer)
                s.headers.forEach { (k, v) -> runCatching { b.setHeader(k, v) } }
                val r = http.send(b.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                if (r.statusCode() !in 200..299 || r.body().isEmpty()) return@runCatching
                val ext = s.url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in setOf("srt", "vtt", "ass", "ssa") } ?: "srt"
                uniqueFile(job.file.parentFile, "$base.${safe(s.lang).replace(' ', '_').take(30)}", ext).writeBytes(r.body())
            }
        }
    }

    /** Runs a failed download again from the start. */
    fun retry(job: DownloadJob) {
        if (job.state != DlState.Failed) return
        job.state = DlState.Running; job.error = null; job.done = 0; job.total = 0
        run(job)
    }

    /** Downloads a saved file's stream again (e.g. to get the sound an older download lacked) as a new file next to the old one. */
    fun again(r: DownloadRecord) {
        val s = r.src ?: return
        scope.launch {
            val link = newExtractorLink("Saved", "Saved", s.url, if (s.m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                referer = s.referer; quality = s.quality; headers = s.headers
            }
            start(r.title, link, s.subs)
        }
    }

    fun cancel(job: DownloadJob) { job.task?.cancel(); jobs.remove(job) }

    private fun part(job: DownloadJob) = File(job.file.path + ".part")

    private fun request(link: ExtractorLink, url: String): HttpRequest {
        val b = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", "Mozilla/5.0")
        if (link.referer.isNotBlank()) b.header("Referer", link.referer)
        // Some headers (Host, Content-Length...) are restricted by the HTTP client and are skipped
        link.headers.forEach { (k, v) -> runCatching { b.setHeader(k, v) } }
        return b.GET().build()
    }

    private suspend fun bytes(link: ExtractorLink, url: String): ByteArray {
        var last: Exception? = null
        repeat(3) {
            currentCoroutineContext().ensureActive()
            try {
                val r = http.send(request(link, url), HttpResponse.BodyHandlers.ofByteArray())
                if (r.statusCode() in 200..299) return r.body()
                last = IOException("HTTP ${r.statusCode()} from $url")
            } catch (e: IOException) { last = e }
            delay(500)
        }
        throw last ?: IOException("Could not download $url")
    }

    private suspend fun text(link: ExtractorLink, url: String) = String(bytes(link, url), Charsets.UTF_8)

    private suspend fun direct(link: ExtractorLink, job: DownloadJob) {
        val r = http.send(request(link, link.url), HttpResponse.BodyHandlers.ofInputStream())
        if (r.statusCode() !in 200..299) throw IOException("HTTP ${r.statusCode()}")
        job.total = r.headers().firstValueAsLong("Content-Length").orElse(0)
        r.body().use { input ->
            part(job).outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); job.done += n
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        part(job).copyTo(job.file, overwrite = true); part(job).delete()
    }

    private class Variant(val url: String, val height: Int, val bandwidth: Long, val audio: String? = null)
    private class Seg(val url: String, val key: ByteArray?, val iv: ByteArray)

    private suspend fun hls(link: ExtractorLink, job: DownloadJob) {
        job.inBytes = false
        var url = link.url
        var text = text(link, url)
        var audioUrl: String? = null
        if ("#EXT-X-STREAM-INF" in text) {
            val master = text; val masterUrl = url
            val variants = variants(master, masterUrl)
            // Closest to the quality the user picked; otherwise the best one
            val pick = (if (link.quality > 0) variants.filter { it.height > 0 }.minByOrNull { abs(it.height - link.quality) } else null)
                ?: variants.maxByOrNull { it.bandwidth } ?: throw IOException("The playlist has no streams")
            url = pick.url; text = text(link, url)
            // Many streams keep the sound in its own playlist; without it the saved video is silent
            audioUrl = pick.audio?.let { audioRendition(master, masterUrl, it) }
        }
        val (init, segs) = parseMedia(link, url, text)
        val audio = audioUrl?.let { parseMedia(link, it, text(link, it)) }
        // A joined file is always mp4; one without separate sound keeps the wrapper the stream came in
        if (init != null || audio != null) job.file = File(job.file.parentFile, job.file.nameWithoutExtension + ".mp4")
        job.total = segs.size.toLong() + (audio?.second?.size ?: 0)
        val videoPart = part(job)
        fetchTo(link, videoPart, init, segs, job)
        if (audio == null) { videoPart.copyTo(job.file, overwrite = true); videoPart.delete(); return }
        val audioPart = File(job.file.path + ".audio.part")
        try {
            fetchTo(link, audioPart, audio.first, audio.second, job)
            if (!mux(videoPart, audioPart, job.file)) {
                // Never throw away a finished download: keep the picture and put the sound beside it, each in its own wrapper
                val dir = job.file.parentFile; val base = job.file.nameWithoutExtension
                job.file = File(dir, base + if (init != null) ".mp4" else ".ts")
                videoPart.copyTo(job.file, overwrite = true)
                audioPart.copyTo(File(dir, "$base.audio." + if (audio.first != null) "mp4" else "ts"), overwrite = true)
            }
        } finally { videoPart.delete(); audioPart.delete() }
    }

    /** The playlist address of the default (else first) audio rendition of [group], or null when it is muxed into the video. */
    private fun audioRendition(master: String, base: String, group: String): String? {
        val media = master.lines().map { it.trim() }.filter { it.startsWith("#EXT-X-MEDIA") }.map { attrs(it) }
            .filter { it["TYPE"] == "AUDIO" && it["GROUP-ID"] == group && it["URI"] != null }
        val m = media.firstOrNull { it["DEFAULT"] == "YES" } ?: media.firstOrNull() ?: return null
        return resolve(base, m.getValue("URI"))
    }

    private suspend fun parseMedia(link: ExtractorLink, url: String, text: String): Pair<String?, List<Seg>> {
        var init: String? = null
        val segs = ArrayList<Seg>()
        val keys = HashMap<String, ByteArray>()
        var seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.groupValues?.get(1)?.toLong() ?: 0L
        var keyUri: String? = null
        var ivAttr: String? = null
        for (line in text.lines().map { it.trim() }) when {
            line.startsWith("#EXT-X-KEY") -> {
                val a = attrs(line)
                when (a["METHOD"]) {
                    "NONE", null -> keyUri = null
                    "AES-128" -> { keyUri = resolve(url, a["URI"] ?: throw IOException("Key address missing")); ivAttr = a["IV"] }
                    else -> throw IOException("This stream uses ${a["METHOD"]} encryption, which can't be downloaded")
                }
            }
            line.startsWith("#EXT-X-MAP") -> init = resolve(url, attrs(line)["URI"] ?: throw IOException("Init segment missing"))
            line.isNotEmpty() && !line.startsWith("#") -> {
                val ku = keyUri
                val key = ku?.let { keys.getOrPut(it) { bytes(link, it) } }
                segs += Seg(resolve(url, line), key, ivAttr?.let(::hexIv) ?: ByteArray(16).also { b -> for (i in 0 until 8) b[15 - i] = (seq shr (8 * i)).toByte() })
                seq++
            }
        }
        if (segs.isEmpty()) throw IOException("The playlist has no segments")
        return init to segs
    }

    private suspend fun fetchTo(link: ExtractorLink, dest: File, init: String?, segs: List<Seg>, job: DownloadJob) {
        dest.outputStream().buffered().use { out ->
            init?.let { out.write(bytes(link, it)) }
            for (chunk in segs.chunked(4)) {
                val parts = coroutineScope { chunk.map { s -> async { decrypt(s, bytes(link, s.url)) } }.awaitAll() }
                parts.forEach(out::write)
                job.done += chunk.size
            }
        }
    }

    /**
     * Joins a video-only and an audio-only file into the mp4 [out] with the libVLC the player already uses (stream output, no re-encoding).
     * The sound is first rewrapped as m4a: libVLC takes it as a track from that, but not from an audio-only transport stream.
     */
    internal fun mux(video: File, audio: File, out: File): Boolean {
        val m4a = File(out.path + ".audio.m4a")
        val tmp = File(out.path + ".mux")
        try {
            if (!vlcCopy(audio, m4a, ":no-sout-video") || m4a.length() == 0L) return false
            if (!vlcCopy(video, tmp, ":sout-all", ":input-slave=file:///${m4a.absolutePath.replace(File.separatorChar, '/')}")) return false
            if (tmp.length() < video.length() / 2) return false
            tmp.copyTo(out, overwrite = true)
            return true
        } finally { m4a.delete(); tmp.delete() }
    }

    /** Plays [src] into an mp4 file at [dst] through libVLC's stream output, copying the streams as they are. */
    private fun vlcCopy(src: File, dst: File, vararg options: String): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = true
        val mp = try { app.onestream.player.Vlc.factory.mediaPlayers().newMediaPlayer() } catch (e: Throwable) { return false }
        try {
            mp.events().addMediaPlayerEventListener(object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
                override fun finished(p: uk.co.caprica.vlcj.player.base.MediaPlayer) { done.countDown() }
                override fun error(p: uk.co.caprica.vlcj.player.base.MediaPlayer) { ok = false; done.countDown() }
            })
            val started = mp.media().play(src.absolutePath, *options, ":sout=#std{access=file,mux=mp4,dst='${dst.absolutePath}'}")
            if (!started || !done.await(30, java.util.concurrent.TimeUnit.MINUTES)) ok = false
        } finally { runCatching { mp.controls().stop() }; mp.release() }
        return ok
    }

    private fun decrypt(s: Seg, data: ByteArray): ByteArray =
        if (s.key == null) data
        else Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(s.key, "AES"), IvParameterSpec(s.iv)) }.doFinal(data)

    private fun variants(text: String, base: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines().map { it.trim() }
        for ((i, line) in lines.withIndex()) if (line.startsWith("#EXT-X-STREAM-INF")) {
            val a = attrs(line)
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            out += Variant(resolve(base, uri), a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0, a["BANDWIDTH"]?.toLongOrNull() ?: 0, a["AUDIO"])
        }
        return out
    }

    companion object {
        private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v")

        fun defaultDir(): File = System.getenv("ONESTREAM_DOWNLOADS")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), "Videos/1-stream")

        fun qualityLabel(q: Int) = if (q > 0) "${q}p" else "Auto"

        /** Resolutions offered by an HLS master playlist as (height, absolute playlist address), tallest first, one per height. */
        fun hlsVariants(text: String, base: String): List<Pair<Int, String>> {
            val lines = text.lines().map { it.trim() }
            val out = ArrayList<Pair<Int, String>>()
            for ((i, line) in lines.withIndex()) if (line.startsWith("#EXT-X-STREAM-INF")) {
                val h = attrs(line)["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: continue
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                out += h to resolve(base, uri)
            }
            return out.distinctBy { it.first }.sortedByDescending { it.first }
        }

        /** Length in ms of an on-demand HLS playlist (master or media; [fetch] loads the first variant of a master), null for live or unreadable ones. */
        fun hlsDuration(text: String, base: String, fetch: (String) -> String): Long? {
            var t = text
            if ("#EXT-X-STREAM-INF" in t) {
                val lines = t.lines().map { it.trim() }
                val uri = lines.drop(lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") } + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
                t = fetch(resolve(base, uri))
            }
            if ("#EXT-X-ENDLIST" !in t) return null
            val secs = Regex("#EXTINF:([0-9.]+)").findAll(t).sumOf { it.groupValues[1].toDouble() }
            return (secs * 1000).toLong().takeIf { it > 0 }
        }

        /** Length in ms of a DASH manifest that states `mediaPresentationDuration`, else null. */
        fun dashDuration(text: String): Long? =
            Regex("mediaPresentationDuration=\"([^\"]+)\"").find(text)?.let { runCatching { java.time.Duration.parse(it.groupValues[1]).toMillis() }.getOrNull() }?.takeIf { it > 0 }

        private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(100).ifBlank { "video" }

        private fun uniqueFile(dir: File, base: String, ext: String): File {
            var f = File(dir, "$base.$ext")
            var n = 2
            while (f.exists()) f = File(dir, "$base ($n).$ext").also { n++ }
            return f
        }

        private fun resolve(base: String, ref: String) = URI(base).resolve(ref).toString()

        private fun attrs(line: String): Map<String, String> =
            Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

        private fun hexIv(s: String): ByteArray {
            val hex = s.removePrefix("0x").removePrefix("0X").padStart(32, '0')
            return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
