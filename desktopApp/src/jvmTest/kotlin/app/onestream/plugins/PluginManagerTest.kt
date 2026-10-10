package app.onestream.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixtureApi : MainAPI() {
    override var name = "FixtureApi"
    override var mainUrl = "https://fixture.example"
}

class GoodPlugin : BasePlugin() {
    override fun load() = registerMainAPI(FixtureApi())
}

class HalfBrokenPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FixtureApi().apply { name = "HalfBroken" })
        error("boom after registering")
    }
}

class PluginManagerTest {
    private fun jar(dir: File, file: String, manifest: String?): File {
        val f = File(dir, file)
        JarOutputStream(f.outputStream()).use { out ->
            if (manifest != null) {
                out.putNextEntry(JarEntry("manifest.json")); out.write(manifest.toByteArray()); out.closeEntry()
            }
        }
        return f
    }

    private fun manifest(cls: String, api: Int = PLUGIN_API_VERSION) =
        """{"name":"T","pluginClassName":"${cls}","version":3,"apiVersion":$api}"""

    private fun providers() = APIHolder.allProviders.map { it.name }

    @Test
    fun loadsRegistersAndUnloads() {
        val dir = Files.createTempDirectory("pm").toFile()
        val pm = PluginManager(dir)
        val j = jar(dir, "good.jar", manifest(GoodPlugin::class.java.name))
        val r = pm.load(j)
        assertTrue(r.ok, r.error)
        assertEquals(listOf("FixtureApi"), r.providers)
        assertEquals(3, r.manifest?.version)
        assertTrue("FixtureApi" in providers())
        pm.unload(j)
        assertFalse("FixtureApi" in providers())
        assertTrue(pm.remove(j))
        assertFalse(j.exists())
    }

    @Test
    fun throwingPluginIsContainedAndRolledBack() {
        val dir = Files.createTempDirectory("pm").toFile()
        val pm = PluginManager(dir)
        val r = pm.load(jar(dir, "broken.jar", manifest(HalfBrokenPlugin::class.java.name)))
        assertFalse(r.ok)
        assertEquals("boom after registering", r.error)
        assertFalse("HalfBroken" in providers(), "providers from a failed load must be removed")
    }

    @Test
    fun badPluginsNeverThrow() {
        val dir = Files.createTempDirectory("pm").toFile()
        val pm = PluginManager(dir)
        val noManifest = pm.load(jar(dir, "a.jar", null))
        assertTrue("manifest.json" in noManifest.error!!)
        val wrongApi = pm.load(jar(dir, "b.jar", manifest(GoodPlugin::class.java.name, api = 99)))
        assertTrue("plugin API 99" in wrongApi.error!!)
        val missingClass = pm.load(jar(dir, "c.jar", manifest("does.not.Exist")))
        assertNotNull(missingClass.error)
        File(dir, "d.jar").writeText("not a zip")
        assertNotNull(pm.load(File(dir, "d.jar")).error)
        assertNull(providers().firstOrNull { it == "FixtureApi" })
    }

    @Test
    fun loadAllReportsEveryJarIndependently() {
        val dir = Files.createTempDirectory("pm").toFile()
        jar(dir, "1good.jar", manifest(GoodPlugin::class.java.name))
        jar(dir, "2bad.jar", manifest("nope.Nope"))
        val all = PluginManager(dir).loadAll()
        assertEquals(listOf(true, false), all.map { it.ok })
        PluginManager(dir).also { all.forEach { p -> it.unload(p.file) } }
    }
}