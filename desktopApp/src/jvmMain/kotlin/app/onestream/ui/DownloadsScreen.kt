package app.onestream.ui

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.onestream.AppState
import app.onestream.DownloadRecord
import app.onestream.download.DlState
import app.onestream.download.DownloadJob
import java.awt.Desktop
import java.io.File

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

/** Downloads in progress at the top, then everything already saved. */
@Composable
fun DownloadsScreen(app: AppState) {
    val data by app.store.data.collectAsState()
    val jobs = app.downloader.jobs
    val dir = app.downloader.dir
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(LocalSide.current), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("Downloads", trailing = "${jobs.size + data.downloads.size}") }
                GhostButton("Open folder", { dir.mkdirs(); runCatching { Desktop.getDesktop().open(dir) } })
            }
            Text("Saved to $dir", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        }
        if (jobs.isEmpty() && data.downloads.isEmpty()) item {
            Plate(Modifier.enter(0)) { Text("Nothing downloaded yet. Open a title and press Download, then pick a quality.", color = Brand.Muted) }
        }
        items(jobs.toList(), key = { it.id }) { JobRow(app, it) }
        items(data.downloads, key = { it.id }) { RecordRow(app, it) }
    }
}

@Composable
private fun JobRow(app: AppState, j: DownloadJob) {
    Plate(Modifier.enter(0)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(j.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (j.state == DlState.Failed) Text("Failed: ${j.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else {
                    val detail = when {
                        j.inBytes && j.total > 0 -> "${mb(j.done)} of ${mb(j.total)}"
                        j.inBytes -> mb(j.done)
                        else -> "${j.done} of ${j.total} parts"
                    }
                    Text("${j.label} · $detail", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                    val bar = Modifier.fillMaxWidth().padding(top = 4.dp)
                    if (j.total > 0) LinearProgressIndicator(progress = { j.progress }, color = Brand.Red, trackColor = Brand.Line, modifier = bar)
                    else LinearProgressIndicator(color = Brand.Red, trackColor = Brand.Line, modifier = bar)
                }
            }
            GhostButton(if (j.state == DlState.Failed) "Dismiss" else "Cancel", { app.downloader.cancel(j) })
        }
    }
}

@Composable
private fun RecordRow(app: AppState, r: DownloadRecord) {
    val file = File(r.path)
    val exists = file.isFile
    Plate(Modifier.enter(0)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(r.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (exists) "${r.label} · ${mb(file.length())} · ${ago(r.at)}" else "The file is gone from ${file.parent}",
                    style = MaterialTheme.typography.bodySmall, color = if (exists) Brand.Muted else MaterialTheme.colorScheme.error,
                )
            }
            if (exists) {
                GradientButton("Play", { runCatching { Desktop.getDesktop().open(file) } })
                if (!LocalCompact.current) GhostButton("Show", { runCatching { ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start() } })
            }
            GhostButton(if (exists) "Delete" else "Remove", { file.delete(); app.store.removeDownload(r.id) })
        }
    }
}
