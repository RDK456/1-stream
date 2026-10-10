package app.onestream

import app.onestream.plugins.DEFAULT_PLUGIN_REPOS
import app.onestream.plugins.PluginManager
import app.onestream.plugins.Repositories
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultPluginsTest {
    private val jar = File(System.getProperty("blender.jar")).readBytes()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/repo.json") { ex ->
            val base = "http://127.0.0.1:${address.port}"
            val body = """{"name":"Test repo","plugins":[{"name":"Blender Open Movies","internalName":"blender-demo","version":1,"url":"$base/blender-demo.jar","sha256":"${Repositories.sha256(jar)}"}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        createContext("/blender-demo.jar") { ex -> ex.sendResponseHeaders(200, jar.size.toLong()); ex.responseBody.use { it.write(jar) } }
        start()
    }
    private val repo = "http://127.0.0.1:${server.address.port}/repo.json"

    private fun waitIdle(app: AppState) {
        val end = System.currentTimeMillis() + 60_000
        Thread.sleep(300)
        while (System.currentTimeMillis() < end && app.defaultInstall != null) Thread.sleep(100)
    }

    /** Network check against the real default repository; run with REAL_DEFAULTS=1. */
    @Test
    fun realDefaultRepositoryInstalls() {
        if (System.getenv("REAL_DEFAULTS") != "1") return
        val home = Files.createTempDirectory("defreal").toFile()
        val pm = PluginManager(File(home, "plugins").also { it.mkdirs() }) { true }
        val app = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), Store(File(home, "data.json")), pm)
        try {
            app.installDefaultPlugins(DEFAULT_PLUGIN_REPOS)
            Thread.sleep(500)
            val end = System.currentTimeMillis() + 600_000
            while (System.currentTimeMillis() < end && app.defaultInstall != null) Thread.sleep(500)
            println("DEFAULT PLUGINS installed=${app.installed.map { it.file.name to it.ok }} providers=${app.providers.map { it.name }} handled=${app.store.data.value.defaultInstalled.size}")
            assertTrue(app.installed.isNotEmpty(), "at least one default plugin should install")
        } finally { app.pluginManager.loadAll().forEach { app.pluginManager.unload(it.file) } }
    }

    @Test
    fun installsEveryPluginOnceAndLeavesRemovedOnesRemoved() {
        val home = Files.createTempDirectory("defplug").toFile()
        val pm = PluginManager(File(home, "plugins").also { it.mkdirs() })
        val app = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), Store(File(home, "data.json")), pm)
        try {
            app.installDefaultPlugins(listOf(repo)); waitIdle(app)
            assertTrue(File(pm.dir, "blender-demo.jar").exists(), "the repo's plugin should be installed")
            assertTrue(app.providers.any { it.name == "Blender Open Movies" }, "and loaded")
            assertEquals(listOf("$repo|blender-demo"), app.store.data.value.defaultInstalled)

            // The user removes it: starting again must not bring it back
            app.removePlugin(app.installed.first { it.file.name == "blender-demo.jar" })
            assertTrue(!File(pm.dir, "blender-demo.jar").exists())
            app.installDefaultPlugins(listOf(repo)); waitIdle(app)
            assertTrue(!File(pm.dir, "blender-demo.jar").exists(), "a removed default plugin stays removed")
        } finally {
            app.pluginManager.loadAll().forEach { app.pluginManager.unload(it.file) }
            server.stop(0)
        }
    }
}
