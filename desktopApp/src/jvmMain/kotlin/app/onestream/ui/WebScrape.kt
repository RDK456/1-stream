package app.onestream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.onestream.AppState
import app.onestream.Screen
import app.onestream.Tab
import app.onestream.plugins.Repositories
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Pseudo provider name for streams pasted by hand; PlayerScreen plays [Screen.Play.data] directly for it. */
const val WEB_API = "web"

private val MEDIA = Regex("""https?:(?:\\?/){2}[^\s"'<>\\]+?\.(?:m3u8|mp4|webm|mkv|mov)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

private fun isMedia(url: String) = MEDIA.matches(url.trim())

/** Fetches [pageUrl] and lists the video addresses written in it (HTML tags, scripts, JSON). A direct media link is returned as is. */
fun scrapeMedia(pageUrl: String): List<String> {
    val uri = URI(pageUrl.trim())
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Paste a web address starting with http:// or https://" }
    if (isMedia(pageUrl)) return listOf(pageUrl.trim())
    val req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("User-Agent", "Mozilla/5.0").GET().build()
    val r = Repositories.defaultClient().send(req, HttpResponse.BodyHandlers.ofString())
    check(r.statusCode() in 200..299) { "The page answered HTTP ${r.statusCode()}" }
    return MEDIA.findAll(r.body()).map { it.value.replace("\\/", "/") }.map { runCatching { uri.resolve(it).toString() }.getOrDefault(it) }.distinct().toList()
}

/** Paste a page address, see the videos found on it, then play or save one. */
@Composable
fun ScrapeDialog(app: AppState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun go() {
        busy = true; error = null; found = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { scrapeMedia(url) } }
                .onSuccess { found = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }
    fun title(m: String) = runCatching { URI(m).path.substringAfterLast('/').substringBeforeLast('.') }.getOrNull().orEmpty().ifBlank { "Web video" }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Brand.Panel,
        shape = RoundedCornerShape(8.dp),
        title = { Text("Open from a web address", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { SearchField(url, { url = it }, "Paste a page or video link (https://…)") }
                    GradientButton(if (busy) "Looking…" else "Find videos", ::go, enabled = !busy && url.isNotBlank())
                }
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    found?.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Brand.PanelHi).border(1.dp, Brand.Line, RoundedCornerShape(6.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(m, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            GradientButton("Play", {
                                app.push(Screen.Play(WEB_API, url.trim(), title(m), null, m, null)); onClose()
                            })
                            GhostButton("Save", {
                                scope.launch {
                                    val l = newExtractorLink("Web", "Web", m, if (m.substringBefore('?').endsWith(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) { referer = url.trim() }
                                    app.downloader.start(title(m), l); onClose(); app.stack.clear(); app.tab = Tab.Downloads
                                }
                            })
                        }
                    }
                    if (found?.isEmpty() == true) Text("No video links found in that page. Sites that build the player with scripts after loading can't be read this way.", color = Brand.Muted)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { GhostButton("Close", onClose) },
    )
}
