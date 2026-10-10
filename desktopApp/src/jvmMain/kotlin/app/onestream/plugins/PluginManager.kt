package app.onestream.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import android.content.StubContext
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.extractorApis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

/** Bump when the provider API in :library changes incompatibly. Plugins declare the one they were built for. */
const val PLUGIN_API_VERSION = 1

@Serializable
data class PluginManifest(
    val name: String,
    val pluginClassName: String,
    val version: Int = 1,
    val apiVersion: Int = PLUGIN_API_VERSION,
    /** Converted from an Android .cs3; only loaded if the user approved this exact file (see [PluginManager.approved]). */
    val android: Boolean = false,
)

data class InstalledPlugin(
    val file: File,
    val manifest: PluginManifest?,
    /** Null when loaded fine. */
    val error: String?,
    val providers: List<String>,
) {
    val displayName get() = manifest?.name ?: file.nameWithoutExtension
    val ok get() = error == null
}

/**
 * Loads provider plugins (plain JVM jars with a `manifest.json`) from a directory.
 * Each jar gets its own class loader; any failure is captured on the [InstalledPlugin] and never thrown.
 */
class PluginManager(val dir: File, private val approved: (File) -> Boolean = { true }) {
    private class Loaded(val loader: URLClassLoader, val plugin: BasePlugin)

    private val loaded = HashMap<String, Loaded>()
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun loadAll(): List<InstalledPlugin> =
        dir.listFiles { f -> f.extension == "jar" }.orEmpty().sortedBy { it.name }.map(::load)

    @Synchronized
    fun load(jar: File): InstalledPlugin {
        unload(jar)
        var manifest: PluginManifest? = null
        var loader: URLClassLoader? = null
        return try {
            manifest = readManifest(jar)
            require(manifest.apiVersion == PLUGIN_API_VERSION) {
                "built for plugin API ${manifest.apiVersion}, this app supports $PLUGIN_API_VERSION"
            }
            if (manifest.android && !approved(jar)) error("This Android plugin was not approved, or the file changed since you approved it. Reinstall it to approve it again.")
            loader = URLClassLoader(arrayOf(jar.toURI().toURL()), PluginManager::class.java.classLoader)
            val plugin = loader.loadClass(manifest.pluginClassName)
                .getDeclaredConstructor().newInstance() as BasePlugin
            plugin.filename = jar.absolutePath
            if (plugin is Plugin) plugin.load(StubContext()) else plugin.load()
            val mine = providersOf(jar.absolutePath)
            mine.forEach { runCatching { it.init() } }
            loaded[jar.absolutePath] = Loaded(loader, plugin)
            InstalledPlugin(jar, manifest, null, mine.map { it.name })
        } catch (t: Throwable) {
            // Roll back anything the half-loaded plugin registered
            removeRegistrations(jar.absolutePath)
            runCatching { loader?.close() }
            InstalledPlugin(jar, manifest, t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName, emptyList())
        }
    }

    @Synchronized
    fun unload(jar: File) {
        val l = loaded.remove(jar.absolutePath)
        if (l != null) runCatching { l.plugin.beforeUnload() }
        removeRegistrations(jar.absolutePath)
        // Windows keeps the jar locked until the loader is closed
        runCatching { l?.loader?.close() }
    }

    /** Unloads and deletes the jar. Returns false if the file could not be removed. */
    @Synchronized
    fun remove(jar: File): Boolean {
        unload(jar)
        return !jar.exists() || jar.delete()
    }

    private fun readManifest(jar: File): PluginManifest = JarFile(jar).use { jf ->
        val entry = jf.getJarEntry("manifest.json") ?: error("manifest.json missing")
        json.decodeFromString(jf.getInputStream(entry).readBytes().decodeToString())
    }

    private fun providersOf(path: String): List<MainAPI> =
        APIHolder.allProviders.withLock { APIHolder.allProviders.filter { it.sourcePlugin == path } }

    private fun removeRegistrations(path: String) {
        providersOf(path).forEach {
            APIHolder.allProviders.remove(it)
            APIHolder.removePluginMapping(it)
        }
        extractorApis.removeAll(extractorApis.withLock { extractorApis.filter { it.sourcePlugin == path } })
    }
}