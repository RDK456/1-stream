package app.onestream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.onestream.AppState
import app.onestream.DlSub
import app.onestream.Screen
import app.onestream.Tab
import app.onestream.download.Downloader
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Asks the provider for streams of [item], lists them by quality, and downloads the one that is clicked. */
@Composable
fun DownloadDialog(app: AppState, s: Screen.Details, item: PlayItem, title: String, onClose: () -> Unit) {
    val links = remember(item) { mutableStateListOf<ExtractorLink>() }
    val subs = remember(item) { mutableStateListOf<DlSub>() }
    var searching by remember(item) { mutableStateOf(true) }
    var failure by remember(item) { mutableStateOf<String?>(null) }
    LaunchedEffect(item) {
        val api = app.api(s.apiName)
        if (api == null) { failure = "Provider \"${s.apiName}\" is not installed."; searching = false; return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { withTimeout(90_000) { api.loadLinks(item.data, false, { sf -> synchronized(subs) { subs.add(DlSub(sf.lang, sf.url, sf.headers.orEmpty())) } }, { l -> synchronized(links) { links.add(l) } }) } } }
            .onFailure { failure = it.message ?: it.javaClass.simpleName }
        searching = false
    }
    // Torrents and DASH can't be saved; each address once, best quality first
    val options = links.filter { it.type == ExtractorLinkType.VIDEO || it.type == ExtractorLinkType.M3U8 }
        .distinctBy { it.url }.sortedByDescending { it.quality }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Brand.Panel,
        shape = RoundedCornerShape(8.dp),
        title = { Text("Download · $title", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Brand.PanelHi).border(1.dp, Brand.Line, RoundedCornerShape(6.dp))
                            .clickable {
                                app.downloader.start(title, l, subs.toList())
                                onClose()
                                app.stack.clear(); app.tab = Tab.Downloads
                            }
                            .pointerHoverIcon(PointerIcon.Hand).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(Downloader.qualityLabel(l.quality), color = Brand.Red, fontWeight = FontWeight.Bold)
                        Text(l.name.ifBlank { l.source }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (l.type == ExtractorLinkType.M3U8) Chip("HLS")
                    }
                }
                when {
                    searching -> Text(if (options.isEmpty()) "Looking for streams…" else "Still looking for more…", color = Brand.Muted)
                    options.isEmpty() -> Text(failure?.let { "No streams: $it" } ?: "This title has no downloadable streams.", color = Brand.Muted)
                }
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton("Close", onClose) },
    )
}
