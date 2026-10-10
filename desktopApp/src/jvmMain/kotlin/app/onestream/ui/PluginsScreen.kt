package app.onestream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onestream.AppState
import app.onestream.compatKey
import app.onestream.plugins.Cs3
import app.onestream.plugins.InstalledPlugin
import app.onestream.plugins.PLUGIN_API_VERSION
import app.onestream.plugins.RepoPlugin
import app.onestream.plugins.Repositories
import app.onestream.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.File

@Composable
fun PluginsScreen(app: AppState) {
    val data by app.store.data.collectAsState()
    var newRepo by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingCs3?>(null) }
    var query by remember { mutableStateOf("") }
    val langFilter = data.filters["discLang"]
    val typeFilter = data.filters["discType"]
    var hideInstalled by remember { mutableStateOf(false) }
    var showBlocked by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf(40) }

    LaunchedEffect(data.repos) { data.repos.filter { it !in app.repoIndexes }.forEach(app::refreshRepo) }

    fun install(repoUrl: String, p: RepoPlugin) {
        if (p.android) {
            app.scope.launch(Dispatchers.IO) {
                busy = p.internalName; message = null
                runCatching { app.repositories.fetchCs3(repoUrl, p) }
                    .onSuccess {
                        // Check before asking the user to trust it: a plugin that cannot run here is not worth the prompt
                        val why = runCatching { Cs3.check(it) }.getOrNull()
                        if (why != null) { app.compat[compatKey(p)] = why; message = "${p.name} can't run on desktop: $why" }
                        else pending = PendingCs3(repoUrl, p, it, Repositories.sha256(it))
                    }
                    .onFailure { message = "${p.name}: ${it.message}" }
                busy = null
            }
            return
        }
        app.scope.launch(Dispatchers.IO) {
            busy = p.internalName; message = null
            runCatching {
                // Windows keeps loaded jars locked, so release the old one first
                app.pluginManager.unload(File(app.pluginManager.dir, p.internalName + ".jar"))
                val jar = app.repositories.download(repoUrl, p, app.pluginManager.dir)
                app.store.recordPlugin(jar.name, repoUrl, p.version)
                app.loadJar(jar)
            }.onFailure { message = "${p.name}: ${it.message}" }
            busy = null
        }
    }

    fun addRepo() {
        val url = Repositories.normalize(newRepo)
        runCatching { Repositories.secure(url) }
            .onSuccess { app.store.addRepo(url); app.refreshRepo(url); newRepo = "" }
            .onFailure { addError = it.message }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(LocalSide.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Plate(
                Modifier.enter(0), title = "Installed plugins",
                action = {
                    GhostButton("Reload", { app.reloadPlugins() })
                    Box(Modifier.padding(start = 8.dp)) { GhostButton("Open folder", { runCatching { Desktop.getDesktop().open(app.pluginManager.dir) } }) }
                },
            ) {
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(if (data.autoUpdatePlugins) "Auto-update: on" else "Auto-update: off", data.autoUpdatePlugins) { app.store.setAutoUpdatePlugins(!data.autoUpdatePlugins) }
                    GhostButton(if (app.updating) "Checking…" else "Check now", { app.updatePluginsNow() }, enabled = !app.updating)
                    Text(app.updateNote ?: "Repositories and plugins refresh at start and every 6 hours.", color = Brand.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                if (app.installed.isEmpty()) Text("No plugins installed. Add a repository below, or drop a plugin .jar into the folder.", color = Brand.Muted)
                app.installed.forEachIndexed { i, p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusDot(p.ok)
                        Column(Modifier.weight(1f)) {
                            Text("${p.displayName}  v${p.manifest?.version ?: "?"}", fontWeight = FontWeight.Medium)
                            if (p.ok) Text("Providers: " + p.providers.joinToString().ifBlank { "none" }, color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                            else Text("Disabled: ${p.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (p.manifest?.android == true && p.error?.startsWith("This Android plugin was not approved") == true)
                            GhostButton("Approve", { app.store.approvePlugin(p.file.name, Updater.sha256(p.file)); app.reloadPlugins() })
                        GhostButton("Remove", { if (!app.removePlugin(p)) message = "Could not delete ${p.file.name}; close the app and delete it manually." })
                    }
                }
            }
        }
        item {
            Plate(Modifier.enter(1), title = "Add a repository") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Brand.Ink)
                            .border(1.dp, if (addError != null) MaterialTheme.colorScheme.error else Brand.Line, RoundedCornerShape(4.dp))
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    ) {
                        if (newRepo.isEmpty()) Text("https://example.com/repo/index.json", color = Brand.Muted)
                        BasicTextField(
                            newRepo, { newRepo = it; addError = null }, Modifier.fillMaxWidth(), singleLine = true,
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                            cursorBrush = SolidColor(Brand.Red),
                        )
                    }
                    GradientButton("Add", ::addRepo)
                }
                addError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                if (data.repos.isEmpty()) Text(
                    "A repository is an HTTPS URL that lists installable provider plugins. Every download is checked against its SHA-256.",
                    color = Brand.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        item {
            // Everything the added repositories offer, de-duplicated by plugin id (the id is also the jar name)
            val catalog = data.repos.flatMap { u -> app.repoIndexes[u]?.getOrNull()?.let { ix -> ix.plugins.map { Triple(u, ix.name, it) } }.orEmpty() }
                .distinctBy { it.third.internalName }
            val loaded = data.repos.count { app.repoIndexes[it] != null }
            val failed = data.repos.count { app.repoIndexes[it]?.isFailure == true }
            val q = query.trim()
            // Adult plugins stay out of the list unless that type is picked on purpose
            val blocked = catalog.count { app.blockedReason(it.third) != null }
            val matches = catalog.filter { (_, _, p) ->
                (showBlocked || app.blockedReason(p) == null) && (langFilter == null || (p.language ?: "?") == langFilter) && (typeFilter == null || typeFilter in p.types) &&
                    (typeFilter == "NSFW" || "NSFW" !in p.types) &&
                    (q.isEmpty() || p.name.contains(q, true) || p.description?.contains(q, true) == true) &&
                    !(hideInstalled && app.installed.any { it.file.name == p.internalName + ".jar" && it.ok })
            }
            val langs = catalog.groupingBy { it.third.language ?: "?" }.eachCount().entries.sortedByDescending { it.value }
                .map { FilterOption(it.key, (if (it.key == "?") "Unspecified" else langName(it.key)) + " ${it.value}") }
            val types = catalog.flatMap { it.third.types }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                .map { FilterOption(it.key, (if (it.key == "NSFW") "NSFW 18+" else it.key) + " ${it.value}") }
            Plate(
                Modifier.enter(2), title = "Discover plugins",
                action = {
                    GhostButton(if (app.scan == null) "Scan all repositories" else "Scanning…", { app.scanAll(); limit = 40 }, enabled = app.scan == null)
                },
            ) {
                if (data.repos.isEmpty()) Text("Add a repository above, then scan it to see what you can install.", color = Brand.Muted)
                else {
                    val sc = app.scan
                    Text(
                        (if (sc != null) "${sc.phase}… ${sc.done} of ${sc.total}" else if (loaded < data.repos.size) "Loading… $loaded of ${data.repos.size} repositories" else "${data.repos.size} repositories") +
                            " · ${catalog.size - blocked} installable" + (if (blocked > 0) " · $blocked can't run on desktop" else "") +
                            catalog.count { it.third.android && app.compat[compatKey(it.third)] == null }.let { if (it > 0 && sc == null) " · $it not checked yet" else "" } + (if (failed > 0) " · $failed unreachable" else ""),
                        color = Brand.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp),
                    )
                    if (sc != null) androidx.compose.material3.LinearProgressIndicator(
                        progress = { if (sc.total == 0) 0f else sc.done.toFloat() / sc.total }, color = Brand.Red, trackColor = Brand.Line,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    SearchField(query, { query = it; limit = 40 }, "Search plugins by name")
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterRow("Language", langs, langFilter, { app.store.setFilter("discLang", it); limit = 40 })
                        FilterRow("Type", types, typeFilter, { app.store.setFilter("discType", it); limit = 40 })
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Hide installed", hideInstalled) { hideInstalled = !hideInstalled; limit = 40 }
                            if (blocked > 0) Chip("Show $blocked that can't run", showBlocked) { showBlocked = !showBlocked; limit = 40 }
                            Text("${matches.size} match", color = Brand.Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    matches.take(limit).forEach { (u, repoName, p) ->
                        PluginRow(p, repoName, app.installed.firstOrNull { it.file.name == p.internalName + ".jar" }, busy, app.blockedReason(p), { install(u, p) })
                    }
                    if (matches.size > limit) GhostButton("Show ${minOf(40, matches.size - limit)} more", { limit += 40 }, Modifier.padding(top = 8.dp))
                }
            }
        }
        items(data.repos, key = { it }) { url ->
            val index = app.repoIndexes[url]
            var open by remember { mutableStateOf(false) }
            Plate(
                Modifier.enter(2), title = index?.getOrNull()?.name ?: url,
                action = {
                    index?.getOrNull()?.let { GhostButton(if (open) "Hide ${it.plugins.size}" else "Show ${it.plugins.size}", { open = !open }) }
                    Box(Modifier.padding(start = 8.dp)) { GhostButton("Refresh", { app.repoIndexes.remove(url); app.refreshRepo(url) }) }
                    Box(Modifier.padding(start = 8.dp)) { GhostButton("Remove", { app.store.removeRepo(url); app.repoIndexes.remove(url) }) }
                },
            ) {
                Text(url, style = MaterialTheme.typography.bodySmall, color = Brand.Muted, modifier = Modifier.padding(bottom = 6.dp))
                when {
                    index == null -> Text("Loading…", color = Brand.Muted)
                    index.isFailure -> Text(index.exceptionOrNull()?.message ?: "Failed", color = MaterialTheme.colorScheme.error)
                    !open -> Text("${index.getOrThrow().plugins.size} plugins", color = Brand.Muted)
                    else -> index.getOrThrow().plugins.take(300).forEach { p ->
                        PluginRow(p, null, app.installed.firstOrNull { it.file.name == p.internalName + ".jar" }, busy, app.blockedReason(p), { install(url, p) })
                    }
                }
            }
        }
    }

    pending?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = Brand.Panel,
            shape = RoundedCornerShape(8.dp),
            title = { Text("Install Android plugin?", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${c.plugin.name} v${c.plugin.version}", fontWeight = FontWeight.Medium)
                    Text("From: ${java.net.URI(c.repoUrl).resolve(c.plugin.url)}", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                    Text("SHA-256: ${c.sha256}", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                    Text(
                        "This plugin is code written by its author. Once installed it runs on your PC with the same access you have to your files and network. " +
                            "1-stream remembers this exact file and will refuse to run it if it changes. Only install plugins you trust.",
                        color = Brand.Amber, style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                GradientButton("Install and trust", {
                    pending = null
                    app.scope.launch(Dispatchers.IO) {
                        busy = c.plugin.internalName; message = null
                        runCatching {
                            app.pluginManager.unload(File(app.pluginManager.dir, c.plugin.internalName + ".jar"))
                            val jar = app.repositories.installCs3(c.bytes, c.plugin, app.pluginManager.dir)
                            app.store.approvePlugin(jar.name, Updater.sha256(jar))
                            app.store.recordPlugin(jar.name, c.repoUrl, c.plugin.version)
                            app.loadJar(jar)
                        }.onFailure { message = "${c.plugin.name}: ${it.message}" }
                        busy = null
                    }
                })
            },
            dismissButton = { GhostButton("Cancel", { pending = null }) },
        )
    }
}

private class PendingCs3(val repoUrl: String, val plugin: RepoPlugin, val bytes: ByteArray, val sha256: String)

/** One installable plugin; [repoName] is shown when the row is listed outside its own repository card. */
@Composable
private fun PluginRow(p: RepoPlugin, repoName: String?, have: InstalledPlugin?, busyId: String?, blockedReason: String?, onInstall: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("${p.name}  v${p.version}", fontWeight = FontWeight.Medium)
            val meta = listOfNotNull(p.language?.let(::langName), p.types.filter { it != "NSFW" }.take(3).joinToString(", ").ifBlank { null }, if ("NSFW" in p.types) "18+" else null, repoName)
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Brand.Red)
            p.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Muted, maxLines = 2) }
            if (blockedReason != null) Text("Can't run on desktop: $blockedReason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else if (p.android) Text("Android plugin, converted on install (best effort)", style = MaterialTheme.typography.bodySmall, color = Brand.Amber)
            if (!p.android && p.apiVersion != PLUGIN_API_VERSION) Text(
                "Needs plugin API ${p.apiVersion}; this app supports $PLUGIN_API_VERSION",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }
        val outdated = have != null && (have.manifest?.version ?: 0) < p.version
        val compatible = p.android || p.apiVersion == PLUGIN_API_VERSION
        when {
            blockedReason != null -> {}
            busyId == p.internalName -> Text("Installing…", color = Brand.Muted)
            have != null && have.ok && !outdated -> Text("Installed", color = Brand.Emerald, fontWeight = FontWeight.Medium)
            else -> GradientButton(if (outdated) "Update" else "Install", onInstall, enabled = compatible && busyId == null)
        }
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Brand.Ink).border(1.dp, Brand.Line, RoundedCornerShape(4.dp)).padding(horizontal = 14.dp, vertical = 11.dp)) {
        if (value.isEmpty()) Text(hint, color = Brand.Muted)
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp), cursorBrush = SolidColor(Brand.Red),
        )
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    Box(Modifier.padding(2.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (ok) Brand.Emerald else Brand.Rose).padding(5.dp))
}