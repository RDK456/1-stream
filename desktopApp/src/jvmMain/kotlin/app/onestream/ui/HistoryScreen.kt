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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.onestream.AppState
import app.onestream.HistoryEntry
import app.onestream.Screen
import coil3.compose.AsyncImage

/** Everything you started watching, newest first, with a way back in. */
@Composable
fun HistoryScreen(app: AppState) {
    val data by app.store.data.collectAsState()
    val items = data.history
    var confirmClear by remember { mutableStateOf(false) }
    if (items.isEmpty()) {
        Message("Nothing watched yet", "Titles you start watching show up here so you can pick up where you left off.")
        return
    }
    val hours = items.sumOf { it.positionMs } / 3_600_000f
    val finished = items.count { it.durationMs > 0 && it.positionMs >= it.durationMs * 0.9 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(LocalSide.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("History", trailing = "${items.size} titles") }
                GhostButton("Clear all", { confirmClear = true })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                StatTile("Watch time", hours, "h", decimals = 1, hint = "across ${items.size} titles", modifier = Modifier.weight(1f).enter(0))
                StatTile("Finished", finished.toFloat(), hint = "90% or more watched", modifier = Modifier.weight(1f).enter(1))
            }
        }
        itemsIndexed(items, key = { _, h -> "${h.apiName}|${h.url}" }) { i, h -> HistoryRow(app, h, i) }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = Brand.Panel,
        shape = RoundedCornerShape(8.dp),
        title = { Text("Clear history?", fontWeight = FontWeight.SemiBold) },
        text = { Text("This removes ${items.size} titles from History and forgets where you stopped. Your library is not touched.", color = Brand.Muted) },
        confirmButton = { GradientButton("Clear", { app.store.clearHistory(); confirmClear = false }) },
        dismissButton = { GhostButton("Cancel", { confirmClear = false }) },
    )
}

@Composable
private fun HistoryRow(app: AppState, h: HistoryEntry, index: Int) {
    val done = h.durationMs > 0 && h.positionMs >= h.durationMs * 0.9
    val progress = if (h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxWidth().enter(index).clip(RoundedCornerShape(6.dp)).background(Brand.Panel).border(1.dp, Brand.Line, RoundedCornerShape(6.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(width = 56.dp, height = 84.dp).clip(RoundedCornerShape(4.dp)).background(Brand.PanelHi), contentAlignment = Alignment.Center) {
            if (h.posterUrl != null) AsyncImage(h.posterUrl, h.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(h.name.take(1).uppercase(), color = Brand.Red, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(h.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(h.episodeName, h.apiName).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Brand.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Brand.Line)) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).background(Brand.GradientH))
            }
            Text(
                (if (done) "Finished" else "${formatTime(h.positionMs)} of ${formatTime(h.durationMs)}") + " · " + ago(h.updatedAt),
                style = MaterialTheme.typography.labelSmall, color = Brand.Muted,
            )
        }
        GradientButton(if (done) "Watch again" else "Resume", { app.push(Screen.Play(h.apiName, h.url, h.name, h.posterUrl, h.data, h.episodeName, if (done) 0 else h.positionMs)) })
        if (!LocalCompact.current) GhostButton("Details", { app.push(Screen.Details(h.apiName, h.url)) })
        GhostButton("", { app.store.removeHistory(h) }, icon = IconKind.Close)
    }
}

/** "5 min ago", "3 h ago", "2 days ago"; a zero timestamp (older saves) says "earlier". */
internal fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return "earlier"
    val m = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} days ago"
    }
}
