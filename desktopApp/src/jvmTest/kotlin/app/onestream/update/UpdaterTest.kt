package app.onestream.update

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdaterTest {
    private val tmp = Files.createTempDirectory("upd").toFile()
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

    @AfterTest fun done() { server.stop(0); tmp.deleteRecursively() }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun zip(vararg e: Pair<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z -> for ((n, b) in e) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return o.toByteArray()
    }

    /** Installed 1.0.0: app/a.jar (unchanged), app/b.jar (changes), app/old.jar (removed). Release 1.0.1 adds app/c.jar. */
    private fun scenario(tamperA: Boolean = false, patchEntries: List<Pair<String, ByteArray>>? = null): Triple<File, Updater, UpdateFeed> {
        val install = File(tmp, "install").also { File(it, "app").mkdirs() }
        val a = "A".toByteArray(); val bOld = "b-old".toByteArray(); val bNew = "b-new".toByteArray(); val c = "c-new".toByteArray()
        File(install, "app/a.jar").writeBytes(if (tamperA) "tampered".toByteArray() else a)
        File(install, "app/b.jar").writeBytes(bOld); File(install, "app/old.jar").writeBytes("x".toByteArray())
        File(install, "1-stream.exe").writeBytes("exe".toByteArray())
        val patch = zip(*(patchEntries ?: listOf("app/b.jar" to bNew, "app/c.jar" to c)).toTypedArray())
        val msi = "fake-msi".toByteArray()
        routes["/patch.zip"] = patch; routes["/full.msi"] = msi
        val feed = UpdateFeed(
            "1.0.1", "notes", UpdateAsset("full.msi", sha(msi), msi.size.toLong()),
            mapOf("app/a.jar" to sha(a), "app/b.jar" to sha(bNew), "app/c.jar" to sha(c)),
            listOf(UpdatePatch("1.0.0", "patch.zip", sha(patch), patch.size.toLong(), removed = listOf("app/old.jar"))),
        )
        routes["/update.json"] = Json.encodeToString(feed).toByteArray()
        return Triple(install, Updater("$base/update.json", install, File(tmp, "stage"), "1.0.0"), feed)
    }

    @Test fun versionCompare() {
        assertTrue(Updater.compareVersions("1.0.10", "1.0.9") > 0)
        assertEquals(0, Updater.compareVersions("1.0", "1.0.0"))
        assertTrue(Updater.compareVersions("1.0.0", "1.1") < 0)
    }

    @Test fun checkOnlyReportsNewer() {
        val (install, _, _) = scenario()
        assertNull(Updater("$base/update.json", install, File(tmp, "s"), "1.0.1").check())
        assertEquals("1.0.1", Updater("$base/update.json", install, File(tmp, "s"), "1.0.0").check()?.version)
    }

    @Test fun deltaDownloadsOnlyChangedFilesAndAppliesSilently() {
        val (install, updater, feed) = scenario()
        val staged = updater.stage(feed)
        assertIs<Staged.Delta>(staged)
        assertTrue(File(staged.dir, "app/c.jar").isFile && !File(staged.dir, "app/a.jar").exists(), "only changed files are staged")
        // run the real apply script (no relaunch) and wait for it
        val script = File(tmp, "apply.cmd").apply { writeText(Applier.script(staged, install, "1-stream.exe", relaunch = false)) }
        assertEquals(0, ProcessBuilder(System.getenv("SystemRoot") + "\\System32\\cmd.exe", "/c", script.absolutePath).start().waitFor())
        assertEquals("b-new", File(install, "app/b.jar").readText())
        assertEquals("c-new", File(install, "app/c.jar").readText())
        assertTrue(!File(install, "app/old.jar").exists(), "removed file is deleted")
        assertEquals("A", File(install, "app/a.jar").readText())
    }

    @Test fun driftedInstallFallsBackToFullMsi() {
        val (_, updater, feed) = scenario(tamperA = true)
        assertIs<Staged.Full>(updater.stage(feed))
    }

    @Test fun zipSlipFallsBackToFullMsi() {
        val (_, updater, feed) = scenario(patchEntries = listOf("../evil.txt" to "x".toByteArray()))
        assertIs<Staged.Full>(updater.stage(feed))
        assertTrue(!File(tmp, "evil.txt").exists())
    }

    @Test fun badMsiHashIsRejected() {
        val (_, updater, feed) = scenario()
        assertFailsWith<Exception> { updater.stage(feed.copy(msi = feed.msi!!.copy(sha256 = "0".repeat(64)), patches = emptyList())) }
    }

    @Test fun feedSetInTheAppEnablesUpdates() {
        val (install, _, feed) = scenario()
        routes["/update.json"] = Json.encodeToString(feed.copy(version = "999.0.0")).toByteArray() // newer than whatever version the build under test has
        val c = UpdateController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), app.onestream.Store(File(tmp, "d.json")), install, File(tmp, "stage2"), null)
        assertIs<UpdateStatus.Disabled>(c.status)
        assertFailsWith<Exception> { c.setFeedUrl("http://example.com/update.json") } // plain http is refused
        c.setFeedUrl("$base/update.json")
        val end = System.currentTimeMillis() + 15_000
        while (c.status !is UpdateStatus.Ready && c.status !is UpdateStatus.Failed && System.currentTimeMillis() < end) Thread.sleep(100)
        assertIs<UpdateStatus.Ready>(c.status)
        assertEquals("$base/update.json", c.feedUrl)
    }

    @Test fun fullScriptRunsMsiexecSilently() {
        val s = Applier.script(Staged.Full("1.0.1", 1, File("C:/x/1-stream-1.0.1.msi")), File("C:/Users/u/AppData/Local/1-stream"), "1-stream.exe", relaunch = true)
        assertTrue("msiexec.exe /i" in s && "/qn /norestart" in s && "1-stream.exe\"" in s && s.trimEnd().endsWith("exit"))
    }
}
