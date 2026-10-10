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
class DownloadJob(val id: String, val title: String, val label: String, file: File) {
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

    fun start(title: String, link: ExtractorLink): DownloadJob {
        val hls = link.type == ExtractorLinkType.M3U8 || link.url.substringBefore('?').endsWith(".m3u8", true)
        val ext = if (hls) "ts" else link.url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in VIDEO_EXT } ?: "mp4"
        val label = qualityLabel(link.quality)
        dir.mkdirs()
        val job = DownloadJob(UUID.randomUUID().toString(), title, label, uniqueFile(dir, "${safe(title)} [$label]", ext))
        jobs += job
        job.task = scope.launch(Dispatchers.IO) {
            try {
                if (hls) hls(link, job) else direct(link, job)
                store.addDownload(DownloadRecord(job.id, title, label, job.file.absolutePath, job.file.length(), System.currentTimeMillis()))
                jobs.remove(job)
            } catch (e: CancellationException) {
                part(job).delete(); throw e
            } catch (e: Throwable) {
                part(job).delete(); job.state = DlState.Failed; job.error = e.message ?: e.javaClass.simpleName
            }
        }
        return job
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

    private class Variant(val url: String, val height: Int, val bandwidth: Long)
    private class Seg(val url: String, val key: ByteArray?, val iv: ByteArray)

    private suspend fun hls(link: ExtractorLink, job: DownloadJob) {
        job.inBytes = false
        var url = link.url
        var text = text(link, url)
        if ("#EXT-X-STREAM-INF" in text) {
            val variants = variants(text, url)
            // Closest to the quality the user picked; otherwise the best one
            val pick = (if (link.quality > 0) variants.filter { it.height > 0 }.minByOrNull { abs(it.height - link.quality) } else null)
                ?: variants.maxByOrNull { it.bandwidth } ?: throw IOException("The playlist has no streams")
            url = pick.url; text = text(link, url)
        }
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
        if (init != null) job.file = File(job.file.parentFile, job.file.nameWithoutExtension + ".mp4")
        job.total = segs.size.toLong()
        part(job).outputStream().buffered().use { out ->
            init?.let { out.write(bytes(link, it)) }
            for (chunk in segs.chunked(4)) {
                val parts = coroutineScope { chunk.map { s -> async { decrypt(s, bytes(link, s.url)) } }.awaitAll() }
                parts.forEach(out::write)
                job.done += chunk.size
            }
        }
        part(job).copyTo(job.file, overwrite = true); part(job).delete()
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
            out += Variant(resolve(base, uri), a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0, a["BANDWIDTH"]?.toLongOrNull() ?: 0)
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
