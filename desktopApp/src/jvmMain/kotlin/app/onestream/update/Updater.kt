package app.onestream.update

import app.onestream.plugins.RepoException
import app.onestream.plugins.Repositories
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

@Serializable
data class UpdateAsset(val url: String, val sha256: String, val size: Long = 0)

/** A zip holding only the files that changed between [from] and the feed's version. */
@Serializable
data class UpdatePatch(val from: String, val url: String, val sha256: String, val size: Long = 0, val removed: List<String> = emptyList())

/** `update.json`: what the newest release looks like. [files] is the full manifest (relative path to SHA-256). */
@Serializable
data class UpdateFeed(
    val version: String,
    val notes: String? = null,
    val msi: UpdateAsset? = null,
    val files: Map<String, String> = emptyMap(),
    val patches: List<UpdatePatch> = emptyList(),
)

sealed interface Staged {
    val version: String
    val bytes: Long
    /** Only the changed files, already hash-checked, sitting in [dir]. */
    data class Delta(override val version: String, override val bytes: Long, val dir: File, val removed: List<String>) : Staged
    /** A full MSI, installed silently with msiexec. */
    data class Full(override val version: String, override val bytes: Long, val msi: File) : Staged
}

class UpdateException(msg: String) : Exception(msg)

/**
 * Checks a feed and stages an update. Delta first: only changed files are downloaded and every file
 * is verified against the manifest. Anything unexpected (drifted install, missing patch) falls back to the full MSI.
 */
class Updater(
    private val feedUrl: String,
    private val installDir: File,
    private val stageDir: File,
    private val currentVersion: String,
    private val http: HttpClient = Repositories.defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun check(): UpdateFeed? {
        val feed = try {
            json.decodeFromString<UpdateFeed>(String(fetch(Repositories.secure(feedUrl)), Charsets.UTF_8))
        } catch (e: UpdateException) { throw e } catch (e: Exception) {
            throw UpdateException("Not a valid update feed: ${e.message}")
        }
        return feed.takeIf { compareVersions(it.version, currentVersion) > 0 }
    }

    fun stage(feed: UpdateFeed, progress: (Float) -> Unit = {}): Staged {
        stageDir.deleteRecursively(); stageDir.mkdirs()
        val patch = feed.patches.firstOrNull { it.from == currentVersion }
        if (patch != null && feed.files.isNotEmpty()) {
            runCatching { return stageDelta(feed, patch, progress) }
        }
        val msi = feed.msi ?: throw UpdateException("The release has no installer for this version.")
        val file = File(stageDir, "1-stream-${feed.version}.msi")
        download(resolve(msi.url), file, msi.sha256, progress)
        return Staged.Full(feed.version, file.length(), file)
    }

    private fun stageDelta(feed: UpdateFeed, patch: UpdatePatch, progress: (Float) -> Unit): Staged {
        val zip = File(stageDir, "patch.zip")
        download(resolve(patch.url), zip, patch.sha256, progress)
        val out = File(stageDir, "files").also { it.mkdirs() }
        val root = out.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            generateSequence { zin.nextEntry }.filter { !it.isDirectory }.forEach { e ->
                val rel = e.name.replace('\\', '/')
                val expected = feed.files[rel] ?: throw UpdateException("Patch contains an unknown file: $rel")
                val dest = File(out, rel).canonicalFile
                if (!dest.path.startsWith(root.path + File.separator)) throw UpdateException("Bad path in patch: $rel")
                dest.parentFile.mkdirs()
                dest.outputStream().use { zin.copyTo(it) }
                if (!sha256(dest).equals(expected, ignoreCase = true)) throw UpdateException("Patched file failed verification: $rel")
            }
        }
        // Everything the patch does not carry must already be on disk exactly as the manifest says
        val have = out.walkTopDown().filter { it.isFile }.map { it.relativeTo(out).invariantSeparatorsPath }.toSet()
        for ((rel, hash) in feed.files) {
            if (rel in have) continue
            val local = File(installDir, rel)
            if (!local.isFile || !sha256(local).equals(hash, ignoreCase = true)) throw UpdateException("Install differs from release at $rel")
        }
        for (rel in patch.removed) if (rel.contains("..") || rel.startsWith("/") || rel.contains(':')) throw UpdateException("Bad removal path: $rel")
        return Staged.Delta(feed.version, zip.length(), out, patch.removed)
    }

    private fun resolve(url: String) = Repositories.secure(URI(feedUrl).resolve(url).toString())

    private fun fetch(uri: URI): ByteArray = send(uri, HttpResponse.BodyHandlers.ofByteArray()).body()

    private fun <T> send(uri: URI, h: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val r = try {
            http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).header("User-Agent", "1-stream").GET().build(), h)
        } catch (e: Exception) { throw UpdateException("Could not reach $uri: ${e.message ?: e.javaClass.simpleName}") }
        if (r.statusCode() !in 200..299) throw UpdateException("HTTP ${r.statusCode()} from $uri")
        return r
    }

    private fun download(uri: URI, dest: File, sha: String, progress: (Float) -> Unit) {
        val r = send(uri, HttpResponse.BodyHandlers.ofInputStream())
        val total = r.headers().firstValueAsLong("content-length").orElse(-1)
        val md = MessageDigest.getInstance("SHA-256")
        r.body().use { input: InputStream ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(sha.trim(), ignoreCase = true)) { dest.delete(); throw RepoException("Hash mismatch for ${uri.path}: expected $sha, got $actual") }
    }

    companion object {
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
