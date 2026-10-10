package app.onestream.plugins

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoriesTest {
    private val jar = "pretend-jar-bytes".toByteArray()
    private val good = Repositories.sha256(jar)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/repo/index.json") { ex ->
            val body = """{"name":"Test repo","plugins":[
                {"name":"Good","internalName":"good","version":2,"url":"good.jar","sha256":"$good"},
                {"name":"Bad","internalName":"bad","version":1,"url":"good.jar","sha256":"deadbeef"},
                {"name":"Evil","internalName":"..\\evil","version":1,"url":"good.jar","sha256":"$good"}
            ]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        createContext("/repo/good.jar") { ex ->
            ex.sendResponseHeaders(200, jar.size.toLong()); ex.responseBody.use { it.write(jar) }
        }
        createContext("/repo/cs/repo.json") { ex ->
            val b = """{"name":"CS repo","manifestVersion":1,"pluginLists":["plugins.json"]}""".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        createContext("/repo/cs/plugins.json") { ex ->
            val b = """[{"name":"Up","internalName":"Up","version":3,"apiVersion":1,"url":"https://x/Up.cs3","status":1,"description":"d"},{"name":"Down","url":"https://x/Down.cs3","status":0}]""".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        createContext("/repo/notjson") { ex ->
            val b = "<html>".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}/repo"
    private val repos = Repositories()

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun fetchesIndexAndInstallsWithVerifiedHash() {
        val index = repos.fetchIndex("$base/index.json")
        assertEquals("Test repo", index.name)
        val dir = Files.createTempDirectory("plugins").toFile()
        val out = repos.download("$base/index.json", index.plugins[0], dir)
        assertEquals("good.jar", out.name)
        assertContentEquals(jar, out.readBytes())
        assertEquals(listOf("good.jar"), dir.list()!!.toList()) // no .part leftovers
    }

    @Test
    fun hashMismatchInstallsNothing() {
        val index = repos.fetchIndex("$base/index.json")
        val dir = Files.createTempDirectory("plugins").toFile()
        val e = assertFailsWith<RepoException> { repos.download("$base/index.json", index.plugins[1], dir) }
        assertTrue("Hash mismatch" in e.message!!)
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun pathTraversalIdRejected() {
        val index = repos.fetchIndex("$base/index.json")
        val dir = Files.createTempDirectory("plugins").toFile()
        assertFailsWith<RepoException> { repos.download("$base/index.json", index.plugins[2], dir) }
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun onlyHttpsOrLoopbackAllowed() {
        assertFailsWith<RepoException> { Repositories.secure("http://example.com/index.json") }
        assertFailsWith<RepoException> { Repositories.secure("ftp://example.com/x") }
        assertFailsWith<RepoException> { Repositories.secure("not a url") }
        Repositories.secure("https://example.com/index.json")
        Repositories.secure("http://localhost:8080/index.json")
        assertFalse(Repositories.secure("http://127.0.0.1/x").host.isNullOrEmpty())
    }

    @Test
    fun invalidIndexIsReportedNotThrownRaw() {
        assertFailsWith<RepoException> { repos.fetchIndex("$base/notjson") }
        assertFailsWith<RepoException> { repos.fetchIndex("$base/missing.json") }
    }

    @Test
    fun cloudStreamRepoIsListedButNeverInstalled() {
        val index = repos.fetchIndex("$base/cs/repo.json")
        assertEquals("CS repo", index.name)
        assertEquals(listOf("Up"), index.plugins.map { it.name }) // status 0 (down) is dropped
        assertTrue(index.plugins[0].android)
        val dir = Files.createTempDirectory("plugins").toFile()
        assertFailsWith<RepoException> { repos.download("$base/cs/repo.json", index.plugins[0], dir) }
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun defaultReposAreAllHttps() {
        assertEquals(31, DEFAULT_REPOS.size)
        DEFAULT_REPOS.forEach { Repositories.secure(it) }
    }

    @Test
    fun cloudstreamrepoLinksAreNormalizedToHttps() {
        assertEquals(
            "https://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json",
            Repositories.normalize("  cloudstreamrepo://raw.githubusercontent.com/self-similarity/MegaRepo/builds/repo.json "),
        )
        assertEquals("https://example.com/r.json", Repositories.normalize("https://cs.repo/?example.com/r.json"))
        assertEquals("https://example.com/r.json", Repositories.normalize("https://example.com/r.json"))
        Repositories.secure(Repositories.normalize("cloudstreamrepo://example.com/r.json"))
    }
}