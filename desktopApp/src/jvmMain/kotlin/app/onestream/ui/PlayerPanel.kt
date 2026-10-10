package app.onestream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onestream.player.VideoPlayer
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Everything adjustable while watching, in one scrolling card: speed, quality, source, audio, subtitles, sound, skip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerPanel(
    player: VideoPlayer,
    qualities: List<String>, quality: String, onQuality: (String) -> Unit,
    sources: List<ExtractorLink>, currentSource: ExtractorLink?, onSource: (ExtractorLink) -> Unit,
    subs: List<SubtitleFile>,
    volume: Float, onVolume: (Float) -> Unit,
    skipSeconds: Int, onSkip: () -> Unit,
    onHelp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track lists are read once when the panel opens
    val audio = remember { runCatching { player.audioTracks() }.getOrDefault(emptyList()) }
    val embedded = remember { runCatching { player.subtitleTracks() }.getOrDefault(emptyList()) }
    var audioId by remember { mutableStateOf(runCatching { player.audioTrack() }.getOrDefault(-1)) }
    var subId by remember { mutableStateOf<Int?>(runCatching { player.subtitleTrack() }.getOrNull()) }
    var external by remember { mutableStateOf<String?>(null) }

    Column(
        modifier.width(360.dp).heightIn(max = 460.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xF2181818))
            .border(1.dp, Brand.Line, RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Playback settings", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("✕", Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClose).pointerHoverIcon(PointerIcon.Hand).padding(8.dp), color = Brand.Muted)
        }

        Section("Speed") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SPEEDS.forEach { r -> Chip(if (r == 1f) "Normal" else "${r.toString().removeSuffix(".0")}×", player.rate == r) { player.changeSpeed(r) } }
            }
        }

        Section("Quality") {
            if (qualities.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                qualities.forEach { q -> Chip(q, q == quality) { onQuality(q) } }
            } else Text("This stream has a single quality. Other sources may offer more.", color = Brand.Muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }

        if (sources.size > 1) Section("Source") {
            sources.forEach { l ->
                val q = if (l.quality > 0) "${l.quality}p" else ""
                Option(if (q.isEmpty() || l.name.contains(q)) l.name else "${l.name}  $q", l === currentSource) { onSource(l) }
            }
        }

        Section("Audio") {
            if (audio.isEmpty()) Text("Only one audio track", color = Brand.Muted)
            audio.forEach { t -> Option(t.name, t.id == audioId) { audioId = t.id; player.setAudioTrack(t.id) } }
        }

        Section("Subtitles") {
            Option("Off", subId == -1 && external == null) { subId = -1; external = null; player.setSubtitleTrack(-1) }
            embedded.filter { it.id != -1 }.forEach { t -> Option(t.name, subId == t.id && external == null) { subId = t.id; external = null; player.setSubtitleTrack(t.id) } }
            subs.forEach { f -> Option(f.lang, external == f.url) { external = f.url; player.addSubtitle(f.url) } }
            if (embedded.none { it.id != -1 } && subs.isEmpty()) Text("This title has no subtitles", color = Brand.Muted)
            DelayRow("Subtitle delay", player.subtitleDelayMs) { player.setSubtitleDelay(it) }
        }

        Section("Sound") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Volume ${volume.toInt()}%", Modifier.width(96.dp), color = Brand.Muted)
                Slider(
                    volume, onVolume, valueRange = 0f..200f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Brand.Red, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                )
            }
            Text("Above 100% boosts quiet audio and may distort.", color = Brand.Muted, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip(if (player.muted) "Muted" else "Mute", player.muted) { player.changeMute(!player.muted) } }
            DelayRow("Audio delay", player.audioDelayMs) { player.setAudioDelay(it) }
        }

        Section("Skip and keys") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientButton("Skip $skipSeconds seconds", onSkip)
                GhostButton("Keyboard shortcuts", onHelp)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        content()
    }
}

@Composable
private fun Option(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "✓" else "", Modifier.width(24.dp), color = Brand.Red, fontWeight = FontWeight.Bold)
        Text(text, color = if (selected) Color.White else Brand.Muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Steps of 50 ms; negative values play earlier. */
@Composable
private fun DelayRow(label: String, ms: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.weight(1f), color = Brand.Muted)
        GhostButton("−", { onChange(ms - 50) })
        Text("${if (ms > 0) "+" else ""}$ms ms", Modifier.width(76.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        GhostButton("+", { onChange(ms + 50) })
        if (ms != 0L) GhostButton("Reset", { onChange(0) })
    }
}
