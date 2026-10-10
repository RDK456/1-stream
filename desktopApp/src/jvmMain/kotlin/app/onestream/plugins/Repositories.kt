package app.onestream.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

@Serializable
data class RepoPlugin(
    val name: String,
    /** Stable id, used as the jar file name. */
    val internalName: String,
    val version: Int,
    val apiVersion: Int = PLUGIN_API_VERSION,
    /** Absolute, or relative to the index URL. */
    val url: String,
    val sha256: String,
    val description: String? = null,
    /** Listed from a CloudStream repo: an Android .cs3 that cannot run on desktop yet, so it is shown but never installed. */
    val android: Boolean = false,
    /** Language code from the repo list (`en`, `hi`, `multi`...), when it says. */
    val language: String? = null,
    /** Content types the plugin serves (`Movie`, `Anime`, `NSFW`...). */
    val types: List<String> = emptyList(),
)

@Serializable
data class RepoIndex(val name: String, val plugins: List<RepoPlugin>)

class RepoException(msg: String) : Exception(msg)

/** Fetches repository indexes and installs plugin jars, verifying SHA-256 before anything touches the plugin dir. */
class Repositories(private val http: HttpClient = defaultClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetchIndex(indexUrl: String): RepoIndex {
        val uri = secure(indexUrl)
        val text = String(get(uri), Charsets.UTF_8)
        return try {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: throw RepoException("not a JSON object")
            if ("pluginLists" in obj) fetchCloudStream(uri, obj) else json.decodeFromString<RepoIndex>(text)
        } catch (e: RepoException) {
            throw e
        } catch (e: Exception) {
            throw RepoException("Not a valid repository index: ${e.message}")
        }
    }

    /** CloudStream's repo.json points at plugin lists; the entries are Android .cs3 files with no hash. */
    private fun fetchCloudStream(indexUri: URI, obj: JsonObject): RepoIndex {
        var firstError: Exception? = null
        val plugins = (obj["pluginLists"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.flatMap { l ->
            try {
                val arr = json.parseToJsonElement(String(get(secure(indexUri.resolve(l).toString())), Charsets.UTF_8)) as JsonArray
                arr.mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                    if (o["status"]?.jsonPrimitive?.intOrNull == 0) return@mapNotNull null // marked down by the repo
                    val n = str("name") ?: return@mapNotNull null
                    RepoPlugin(
                        n, str("internalName") ?: n, o["version"]?.jsonPrimitive?.intOrNull ?: 0, o["apiVersion"]?.jsonPrimitive?.intOrNull ?: 0,
                        str("url") ?: return@mapNotNull null, "", str("description"), android = true,
                        language = str("language")?.lowercase()?.takeIf { it.isNotBlank() && it != "null" },
                        types = (o["tvTypes"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                    )
                }
            } catch (e: Exception) { if (firstError == null) firstError = e; emptyList() }
        }
        if (plugins.isEmpty() && firstError != null) throw RepoException("Could not read the plugin lists: ${firstError!!.message}")
        return RepoIndex(obj["name"]?.jsonPrimitive?.contentOrNull ?: indexUri.host, plugins)
    }

    /** Downloads [p], checks its hash, and writes `<internalName>.jar` into [targetDir]. */
    fun download(indexUrl: String, p: RepoPlugin, targetDir: File): File {
        if (p.android) throw RepoException("${p.name} is an Android plugin: use fetchCs3 and installCs3 after the user approves it")
        if (!p.internalName.matches(Regex("[A-Za-z0-9._-]+")) || p.internalName.contains("..")) {
            throw RepoException("Bad plugin id: ${p.internalName}")
        }
        val uri = secure(URI(indexUrl).resolve(p.url).toString())
        val bytes = get(uri)
        val actual = sha256(bytes)
        if (!actual.equals(p.sha256.trim(), ignoreCase = true)) {
            throw RepoException("Hash mismatch for ${p.name}: expected ${p.sha256}, got $actual")
        }
        targetDir.mkdirs()
        val out = File(targetDir, p.internalName + ".jar")
        val tmp = File(targetDir, p.internalName + ".jar.part")
        tmp.writeBytes(bytes)
        tmp.copyTo(out, overwrite = true)
        tmp.delete()
        return out
    }

    /** Downloads an Android plugin without running or converting it, so the user can review it first. */
    fun fetchCs3(indexUrl: String, p: RepoPlugin): ByteArray {
        val bytes = get(secure(URI(indexUrl).resolve(p.url).toString()))
        if (bytes.size > 50_000_000) throw RepoException("Plugin file is unreasonably large")
        return bytes
    }

    /** Converts approved .cs3 bytes into `<internalName>.jar` in [targetDir]. Does not load it. */
    fun installCs3(bytes: ByteArray, p: RepoPlugin, targetDir: File): File {
        if (!p.internalName.matches(Regex("[A-Za-z0-9._-]+")) || p.internalName.contains("..")) throw RepoException("Bad plugin id: ${p.internalName}")
        targetDir.mkdirs()
        val tmp = File(targetDir, p.internalName + ".jar.part")
        Cs3.convert(bytes, tmp)
        val out = File(targetDir, p.internalName + ".jar")
        tmp.copyTo(out, overwrite = true); tmp.delete()
        return out
    }

    fun fetchRaw(url: String): ByteArray = get(secure(normalize(url)))

    private fun get(uri: URI): ByteArray {
        val resp = try {
            http.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "1-stream").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        } catch (e: Exception) {
            throw RepoException("Could not reach $uri: ${e.message ?: e.javaClass.simpleName}")
        }
        if (resp.statusCode() !in 200..299) throw RepoException("HTTP ${resp.statusCode()} from $uri")
        return resp.body()
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build()

        /** Accepts what people paste: `cloudstreamrepo://host/path` and `https://cs.repo/?host/path` become https URLs. */
        fun normalize(input: String): String {
            val u = input.trim()
            return when {
                u.startsWith("cloudstreamrepo://", ignoreCase = true) -> "https://" + u.substring("cloudstreamrepo://".length)
                u.startsWith("https://cs.repo/?", ignoreCase = true) -> "https://" + u.substring("https://cs.repo/?".length).removePrefix("https://")
                else -> u
            }
        }

        /** HTTPS only; plain http is allowed for loopback so repositories can be developed locally. */
        fun secure(url: String): URI {
            val uri = try { URI(url.trim()) } catch (e: Exception) { throw RepoException("Invalid URL: $url") }
            val host = uri.host ?: throw RepoException("Invalid URL: $url")
            val loopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
            if (uri.scheme != "https" && !(uri.scheme == "http" && loopback)) {
                throw RepoException("Only https:// URLs are allowed: $url")
            }
            return uri
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Repositories whose plugins are all installed on first run (those that can run on desktop). The user can still remove any of them. */
val DEFAULT_PLUGIN_REPOS = listOf(
    "https://raw.githubusercontent.com/RVRBEAST76/allforu-repo/builds/repo.json",
)

/** Repositories added on first run (CloudStream community lists). They are removable; see Store.seedDefaultRepos. */
val DEFAULT_REPOS = listOf(
    "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json",
    "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json",
    "https://raw.githubusercontent.com/CranberrySoup/AniyomiCompatExtension/master/repo.json",
    "https://raw.githubusercontent.com/Gian-Fr/ItalianProvider/builds/repo.json",
    "https://raw.githubusercontent.com/CakesTwix/cloudstream-extensions-uk/master/repo.json",
    "https://raw.githubusercontent.com/techtanic/SkillShare-Repo/builds/repo.json",
    "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CS.json",
    "https://git.disroot.org/ayza/FStream/raw/branch/main/repo.json",
    "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json",
    "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json",
    "https://raw.githubusercontent.com/Luna712/Luna712-CloudStream-Extensions/master/repo.json",
    "https://raw.githubusercontent.com/redowan99/Redowan-CloudStream/master/repo.json",
    "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/main/repo",
    "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/builds/repo.json",
    "https://raw.githubusercontent.com/DieGon7771/ItaliaInStreaming/builds/repo.json",
    "https://gitlab.com/tearrs/cloudstream-vietnamese/-/raw/main/repo.json",
    "https://raw.githubusercontent.com/Bnyro/GermanProviders/refs/heads/master/repo.json",
    "https://raw.githubusercontent.com/TeKuma25/IndoStream/builds/repo.json",
    "https://raw.githubusercontent.com/saimuelbr/saimuelrepo/refs/heads/main/builds/repo.json",
    "https://raw.githubusercontent.com/Kraptor123/cs-Karma/refs/heads/master/repo.json",
    "https://raw.githubusercontent.com/redblacker8/storm-ext/refs/heads/builds/repo.json",
    "https://raw.githubusercontent.com/rockhero1234/cinephile/refs/heads/builds/repo.json",
    "https://raw.githubusercontent.com/med1245/cartoonyrepo/builds/repo.json",
    "https://raw.githubusercontent.com/Reflex755/ReflexRepo/refs/heads/builds/repo.json",
    "https://raw.githubusercontent.com/KSHITIJ8473/raghav/builds/repo.json",
    "https://raw.githubusercontent.com/mouradchaouche/cloudstream-frenchrepo/main/repo.json",
    "https://raw.githubusercontent.com/yorik100/Cloudstream/refs/heads/builds/repo.json",
    "https://raw.githubusercontent.com/RVRBEAST76/allforu-repo/builds/repo.json",
    "https://raw.githubusercontent.com/errorcode26/Ayu-CloudStream-Games/builds/repo.json",
    "https://raw.githubusercontent.com/FlummoxGamer/FLUMMOX-Repo/builds/repo.json",
    "https://raw.githubusercontent.com/Faisal0786/Desi/main/repo.json",
)