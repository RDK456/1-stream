package app.onestream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onestream.APP_NAME
import app.onestream.APP_VERSION
import app.onestream.AppPaths
import app.onestream.AppState
import app.onestream.update.Staged
import app.onestream.update.UpdateStatus
import androidx.compose.foundation.layout.fillMaxWidth
import kotlin.system.exitProcess

@Composable
fun AboutScreen(app: AppState) {
    val data by app.store.data.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LocalSide.current), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.enter(0), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BrandMark(64.dp)
            Column {
                Text(APP_NAME, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Version $APP_VERSION", color = Brand.Muted)
            }
        }
        Plate(Modifier.enter(1), title = "Settings") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reduce motion", fontWeight = FontWeight.Medium)
                    Text("Replaces slides and bounces with short fades.", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                }
                Switch(
                    data.reduceMotion, { app.store.setReduceMotion(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
                )
            }
        }
        Plate(Modifier.enter(2), title = "Updates") {
            val st = app.updates.status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (st) {
                            is UpdateStatus.Disabled -> "Updates unavailable"
                            UpdateStatus.Idle -> "Version $APP_VERSION"
                            UpdateStatus.Checking -> "Checking for updates…"
                            UpdateStatus.UpToDate -> "You are up to date ($APP_VERSION)"
                            is UpdateStatus.Available -> "Version ${st.feed.version} is available"
                            is UpdateStatus.Downloading -> "Downloading ${st.version}… ${(st.progress * 100).toInt()}%"
                            is UpdateStatus.Ready -> "Version ${st.staged.version} is ready (${if (st.staged is Staged.Delta) "delta" else "full"}, ${"%.1f".format(st.staged.bytes / 1_048_576.0)} MB)"
                            is UpdateStatus.Failed -> "Update failed"
                        },
                        fontWeight = FontWeight.Medium,
                    )
                    val sub = when (st) {
                        is UpdateStatus.Disabled -> st.reason
                        is UpdateStatus.Failed -> st.message
                        is UpdateStatus.Ready -> "Installs silently when you close the app, or restart now."
                        is UpdateStatus.Available -> st.feed.notes
                        else -> null
                    }
                    if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = if (st is UpdateStatus.Failed) MaterialTheme.colorScheme.error else Brand.Muted)
                }
                when (st) {
                    is UpdateStatus.Ready -> GradientButton("Restart to update", { if (app.updates.applyNow(relaunch = true)) exitProcess(0) })
                    is UpdateStatus.Available -> GradientButton("Download", { app.updates.downloadAvailable() })
                    is UpdateStatus.Disabled, is UpdateStatus.Checking, is UpdateStatus.Downloading -> {}
                    else -> GhostButton("Check now", { app.updates.check() })
                }
            }
            if (app.updates.feedEditable) {
                var feed by remember { mutableStateOf(app.updates.feedUrl) }
                var feedError by remember { mutableStateOf<String?>(null) }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { SearchField(feed, { feed = it; feedError = null }, "https://example.com/1-stream/update.json") }
                    GradientButton("Save and check", { runCatching { app.updates.setFeedUrl(feed) }.onFailure { feedError = it.message } })
                }
                feedError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            }
            if (st is UpdateStatus.Downloading) androidx.compose.material3.LinearProgressIndicator(
                progress = { st.progress }, color = Brand.Red, trackColor = Brand.Line, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Update automatically", fontWeight = FontWeight.Medium)
                    Text("Check on start, download in the background, install when you close the app.", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                }
                Switch(
                    data.autoUpdate, { app.store.setAutoUpdate(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Brand.Red, checkedThumbColor = Brand.Ink),
                )
            }
        }
        Plate(Modifier.enter(3), title = "About") {
            Text(
                "A Windows desktop media player that streams from plugins you install yourself. " +
                    "1-stream ships with no content sources; what you add and watch is your responsibility.",
            )
            Text("Your data folder: ${AppPaths.root}", Modifier.padding(top = 10.dp), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Plate(Modifier.enter(4), title = "Keys") {
            Text("Space pause · ← → seek 10s · ↑ ↓ volume · F fullscreen · Esc back", color = Brand.Muted)
        }
        Plate(Modifier.enter(5), title = "Licence") {
            Text(
                "Based on CloudStream (https://github.com/recloudstream/cloudstream), licensed under GPL-3.0. " +
                    "This program is free software; you can redistribute it and modify it under the terms of the GNU General Public License v3.0. " +
                    "It comes with ABSOLUTELY NO WARRANTY. Video playback by VLC (libVLC, LGPL-2.1+).",
                color = Brand.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}