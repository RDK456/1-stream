package app.onestream

import app.onestream.plugins.PluginManager
import app.onestream.plugins.Repositories
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoUpdateTest {
    private val jar = File(System.getProperty("blender.jar")).readBytes()
    private val version = AtomicInteger(1)
    private val jarHits = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/repo.json") { ex ->
            val body = """{"name":"Test repo","plugins":[{"name":"Blender Open Movies","internalName":"blender-demo","version":${version.get()},"url":"blender-demo.jar","sha256":"${Repositories.sha256(jar)}"}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        createContext("/blender-demo.jar") { ex -> jarHits.incrementAndGet(); ex.sendResponseHeaders(200, jar.size.toLong()); ex.responseBody.use { it.write(jar) } }
        start()
    }
    private val repo = "http://127.0.0.1:${server.address.port}/repo.json"

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(100)
        assertTrue(cond(), "timed out waiting for $what")
    }

    @Test
    fun updatesInstalledPluginWhenRepositoryHasNewerVersion() {
        val home = Files.createTempDirectory("autoupd").toFile()
        val pm = PluginManager(File(home, "plugins").also { it.mkdirs() })
        val app = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), Store(File(home, "data.json")), pm)
        try {
            app.installDefaultPlugins(listOf(repo))
            waitUntil("first install") { app.store.data.value.pluginSources["blender-demo.jar"]?.version == 1 && app.defaultInstall == null }
            assertEquals(1, jarHits.get())

            app.updatePluginsNow()
            waitUntil("first check") { app.updateNote != null && !app.updating }
            assertEquals(1, jarHits.get(), "same version: nothing downloaded")

            version.set(2)
            app.updatePluginsNow()
            waitUntil("update") { app.store.data.value.pluginSources["blender-demo.jar"]?.version == 2 && !app.updating }
            assertEquals(2, jarHits.get())
            assertTrue(app.providers.any { it.name == "Blender Open Movies" }, "plugin is loaded again after the update")
            assertTrue(app.updateNote!!.contains("1 updated"), app.updateNote)
        } finally {
            app.pluginManager.loadAll().forEach { app.pluginManager.unload(it.file) }
            server.stop(0)
        }
    }
}
