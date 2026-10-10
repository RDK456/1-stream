package app.onestream.plugins

import com.lagradost.cloudstream3.plugins.CompatHost
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs against a real .cs3 when CS3_SAMPLE points at one (MegaProvider); skipped otherwise. Needs network. */
class Cs3Test {
    private val sample = System.getenv("CS3_SAMPLE")?.let(::File)?.takeIf { it.isFile }

    @Test
    fun convertsRefusesUnapprovedAndRunsApproved() {
        val cs3 = sample ?: return
        val dir = Files.createTempDirectory("cs3test").toFile()
        val jar = File(dir, "MegaProvider.jar")
        Cs3.convert(cs3.readBytes(), jar)
        assertTrue(jar.length() > 1000)
        jar.copyTo(File("build/MegaProvider.converted.jar"), overwrite = true) // kept for inspection

        val denied = PluginManager(dir) { false }.load(jar)
        assertFalse(denied.ok, "unapproved Android plugin must not load")
        assertTrue("approved" in denied.error!!)

        val added = CopyOnWriteArrayList<String>()
        CompatHost.addRepository = { added.add(it) }
        val ok = PluginManager(dir) { true }.load(jar)
        assertTrue(ok.ok, "approved plugin should load: ${ok.error}")
        assertTrue(ok.manifest!!.name.isNotBlank())
        assertTrue(ok.manifest!!.android)

        if (!cs3.name.startsWith("Mega")) return // the rest is MegaProvider-specific
        // MegaProvider fetches the community repo list and adds every repository through RepositoryManager
        val end = System.currentTimeMillis() + 60_000
        while (added.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(500)
        assertNotNull(added.firstOrNull(), "MegaProvider should have added repositories")
        println("MegaProvider added ${added.size} repositories, e.g. ${added.first()}")
    }

    /** CS3_SAMPLE (MovieBox) must pass the scan; CS3_BROKEN (CineStream) must be flagged for needing CloudStreamApp. */
    @Test
    fun scanFlagsPluginsThatNeedTheAndroidApp() {
        sample?.let { assertEquals(null, Cs3.check(it.readBytes()), "a working plugin must not be flagged") }
        val broken = System.getenv("CS3_BROKEN")?.let(::File)?.takeIf { it.isFile } ?: return
        val why = Cs3.check(broken.readBytes())
        assertNotNull(why, "plugin needing the Android app must be flagged")
        assertTrue("CloudStreamApp" in why, why)
    }

    /** CS3_PROVIDERS=path1;path2 : each must convert, load, register a provider and answer a real search. Needs network. */
    @Test
    fun realProvidersLoadAndSearch() {
        val files = System.getenv("CS3_PROVIDERS")?.split(';')?.map(::File)?.filter { it.isFile } ?: return
        val dir = Files.createTempDirectory("cs3prov").toFile()
        for (cs3 in files) {
            val jar = File(dir, cs3.nameWithoutExtension + ".jar")
            Cs3.convert(cs3.readBytes(), jar)
            val r = PluginManager(dir) { true }.load(jar)
            assertTrue(r.ok, "${cs3.name} should load: ${r.error}")
            assertTrue(r.providers.isNotEmpty(), "${cs3.name} should register a provider")
            val api = com.lagradost.cloudstream3.APIHolder.getApiFromNameNull(r.providers.first())!!
            val hits = kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(60_000) { api.search("sintel", 1)?.items } }
            println("${cs3.name}: providers=${r.providers} search(sintel)=${hits?.size ?: "null"} first=${hits?.firstOrNull()?.name}")
            assertTrue(!hits.isNullOrEmpty(), "${cs3.name} search should return results")
        }
    }
}