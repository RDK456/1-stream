package app.onestream

import app.onestream.plugins.DEFAULT_REPOS
import app.onestream.plugins.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Set SCAN_ALL=1 to scan every default repository for real (downloads hundreds of plugin files; minutes). */
class ScanAllTest {
    @Test
    fun scanAllReadsRepositoriesAndJudgesPlugins() {
        if (System.getenv("SCAN_ALL") == null) return
        val home = Files.createTempDirectory("scan").toFile()
        val app = AppState(CoroutineScope(SupervisorJob() + Dispatchers.Default), Store(File(home, "data.json")), PluginManager(File(home, "plugins").also { it.mkdirs() }))
        app.store.seedDefaultRepos(DEFAULT_REPOS)
        app.scanAll()
        val end = System.currentTimeMillis() + 20 * 60_000
        var last = ""
        while ((app.scan != null || last.isEmpty()) && System.currentTimeMillis() < end) {
            Thread.sleep(2_000)
            val s = app.scan?.let { "${it.phase} ${it.done}/${it.total}" } ?: if (app.compat.isNotEmpty()) "done" else ""
            if (s != last && s.isNotEmpty()) { println(s); last = s }
        }
        val bad = app.compat.filterValues { it.isNotEmpty() }
        println("judged=${app.compat.size} cannotRun=${bad.size}")
        bad.values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8).forEach { println("  ${it.value}x ${it.key}") }
        assertTrue(app.compat.isNotEmpty())
        assertTrue(app.store.data.value.compat.isNotEmpty(), "verdicts are saved")
    }
}
