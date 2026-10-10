package app.onestream.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import app.onestream.AppState
import app.onestream.HistoryEntry
import app.onestream.Screen
import app.onestream.download.Downloader
import app.onestream.player.Media
import app.onestream.player.Track
import app.onestream.player.VideoPlayer
import app.onestream.player.VlcUnavailable
import app.onestream.plugins.Repositories
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.roundToInt

private fun ExtractorLink.userAgent(): String? =
    headers.entries.firstOrNull { it.key.equals("user-agent", ignoreCase = true) }?.value

/** Live and broken streams report 0 or a placeholder such as 99999 s; a real title is under a day long. */
private fun knownLength(ms: Long) = ms in 1 until 86_400_000L

/** The opening-song skip anime viewers expect. */
private const val SKIP_SECONDS = 85
private val ANIME_TYPES = setOf("Anime", "AnimeMovie", "OVA")
private val DIGITS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val SPEED_STEPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
fun PlayerScreen(app: AppState, s: Screen.Play, windowState: WindowState) {
    val created = remember { runCatching { VideoPlayer() } }
    val player = created.getOrNull()
    if (player == null) {
        Column(Modifier.fillMaxSize()) {
            GhostButton("←  Back", app::pop, Modifier.padding(12.dp))
            val e = created.exceptionOrNull()
            Message(
                "The video engine is unavailable",
                if (e is VlcUnavailable) e.message else "${e?.javaClass?.simpleName}: ${e?.message}",
                isError = true,
            )
        }
        return
    }
    Playback(app, s, windowState, player)
}

@Composable
private fun Playback(app: AppState, s: Screen.Play, windowState: WindowState, player: VideoPlayer) {
    val links = remember(s) { mutableStateListOf<ExtractorLink>() }
    val subs = remember(s) { mutableStateListOf<SubtitleFile>() }
    var current by remember(s) { mutableStateOf<ExtractorLink?>(null) }
    var searching by remember(s) { mutableStateOf(true) }
    var failure by remember(s) { mutableStateOf<String?>(null) }
    var controlsUntil by remember { mutableStateOf(System.currentTimeMillis() + 3500) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var volume by remember { mutableStateOf(100f) }
    var panelOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastUntil by remember { mutableStateOf(0L) }
    var quality by remember(s) { mutableStateOf("Auto") }
    var variants by remember(s) { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val side = LocalSide.current
    val compact = LocalCompact.current
    val anime = remember(s) { app.api(s.apiName)?.supportedTypes?.any { it.name in ANIME_TYPES } == true }

    fun save() {
        val len = player.lengthMs
        if (!knownLength(len)) return
        val pos = player.positionMs
        // Finished watching: store 0 so the next open starts from the beginning
        val stored = if (pos > len * 0.95) 0 else pos
        app.store.saveProgress(
            HistoryEntry(s.title, s.url, s.apiName, s.posterUrl, s.data, s.episodeName, stored, len)
        )
    }

    fun say(text: String) { toast = text; toastUntil = System.currentTimeMillis() + 1400 }

    fun start(link: ExtractorLink, from: Long) {
        current = link; quality = "Auto"
        player.play(Media(link.url, link.referer, link.userAgent()), from)
    }

    fun pickQuality(q: String) {
        val l = current ?: return
        val url = if (q == "Auto") l.url else variants.firstOrNull { "${it.first}p" == q }?.second ?: return
        quality = q
        player.play(Media(url, l.referer, l.userAgent()), player.positionMs)
        say("Quality: $q")
    }

    fun seekBy(ms: Long) {
        var to = (player.positionMs + ms).coerceAtLeast(0)
        if (knownLength(player.lengthMs)) to = to.coerceAtMost((player.lengthMs - 1000).coerceAtLeast(0))
        player.seekTo(to)
        say((if (ms > 0) "+" else "−") + "${kotlin.math.abs(ms) / 1000}s")
    }

    fun setVol(v: Float) { volume = v.coerceIn(0f, 200f); say("Volume ${volume.toInt()}%") }

    fun stepSpeed(dir: Int) {
        val i = SPEED_STEPS.indexOfFirst { it >= player.rate - 0.001f }.let { if (it < 0) SPEED_STEPS.lastIndex else it }
        val next = SPEED_STEPS[(i + dir).coerceIn(0, SPEED_STEPS.lastIndex)]
        player.changeSpeed(next); say("Speed ${if (next == 1f) "normal" else next.toString().removeSuffix(".0") + "×"}")
    }

    fun cycle(tracks: List<Track>, currentId: Int, label: String, set: (Int) -> Unit) {
        if (tracks.isEmpty()) { say("No $label tracks"); return }
        val i = tracks.indexOfFirst { it.id == currentId }
        val next = tracks[(i + 1) % tracks.size]
        set(next.id); say("$label: ${next.name}")
    }

    DisposableEffect(s) {
        onDispose { save(); player.release() }
    }

    LaunchedEffect(s) {
        val api = app.api(s.apiName)
        if (api == null) { failure = "Provider \"${s.apiName}\" is not installed."; searching = false; return@LaunchedEffect }
        val finder = launch(Dispatchers.IO) {
            runCatching {
                withTimeout(90_000) { api.loadLinks(s.data, false, { subs.add(it) }, { links.add(it) }) }
            }.onFailure { failure = it.message ?: it.javaClass.simpleName }
            searching = false
        }
        // Give slower sources a moment to report in, then take the best quality
        snapshotFlow { links.size }.first { it > 0 }
        delay(1500)
        if (current == null) start(links.maxByOrNull { it.quality } ?: links.first(), s.startMs)
        finder.join()
    }

    // The resolutions an HLS stream offers, for the Quality choice
    LaunchedEffect(current) {
        variants = emptyList()
        val l = current ?: return@LaunchedEffect
        if (l.type != ExtractorLinkType.M3U8 && !l.url.substringBefore('?').endsWith(".m3u8", true)) return@LaunchedEffect
        variants = withContext(Dispatchers.IO) {
            runCatching {
                val b = HttpRequest.newBuilder(URI(l.url)).timeout(Duration.ofSeconds(20)).header("User-Agent", l.userAgent() ?: "Mozilla/5.0")
                if (l.referer.isNotBlank()) b.header("Referer", l.referer)
                val text = Repositories.defaultClient().send(b.GET().build(), HttpResponse.BodyHandlers.ofString()).body()
                Downloader.hlsVariants(text, l.url)
            }.getOrDefault(emptyList())
        }
    }

    // Save progress periodically
    LaunchedEffect(s) {
        while (true) { delay(5_000); if (player.playing) save() }
    }
    // Drives control auto-hide and the on-screen messages
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    LaunchedEffect(volume) { player.setVolume(volume.toInt()) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val controlsVisible = now < controlsUntil || !player.playing || panelOpen || helpOpen
    fun poke() { controlsUntil = System.currentTimeMillis() + 3500 }
    fun fullscreen() {
        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }
    fun back() {
        if (windowState.placement == WindowPlacement.Fullscreen) windowState.placement = WindowPlacement.Floating
        app.pop()
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
            .pointerInput(Unit) {
                // Any pointer movement or press shows the controls
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        poke()
                    }
                }
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                poke()
                val big = SKIP_SECONDS * 1000L
                when (e.key) {
                    Key.Spacebar, Key.K -> { player.togglePause(); say(if (player.playing) "Pause" else "Play"); true }
                    Key.F, Key.F11 -> { fullscreen(); true }
                    Key.Escape -> { if (helpOpen) helpOpen = false else if (panelOpen) panelOpen = false else back(); true }
                    Key.DirectionRight, Key.L -> { seekBy(if (e.isShiftPressed) big else 10_000); true }
                    Key.DirectionLeft, Key.J -> { seekBy(if (e.isShiftPressed) -big else -10_000); true }
                    Key.DirectionUp -> { setVol(volume + 5); true }
                    Key.DirectionDown -> { setVol(volume - 5); true }
                    Key.M -> { player.changeMute(!player.muted); say(if (player.muted) "Muted" else "Sound on"); true }
                    Key.S -> { seekBy(big); true }
                    Key.Period -> { stepSpeed(+1); true }
                    Key.Comma -> { stepSpeed(-1); true }
                    Key.C -> { cycle(player.subtitleTracks(), player.subtitleTrack(), "Subtitles") { player.setSubtitleTrack(it) }; true }
                    Key.A -> { cycle(player.audioTracks(), player.audioTrack(), "Audio") { player.setAudioTrack(it) }; true }
                    Key.MoveHome -> { player.seekTo(0); say("Start"); true }
                    Key.P -> { panelOpen = !panelOpen; true }
                    Key.H, Key.Slash, Key.F1 -> { helpOpen = !helpOpen; true }
                    else -> {
                        val d = DIGITS.indexOf(e.key)
                        if (d >= 0 && knownLength(player.lengthMs)) { player.seekTo(player.lengthMs * d / 10); say("${d * 10}%"); true } else false
                    }
                }
            },
    ) {
        player.frame?.let {
            Image(
                it, null,
                Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { if (panelOpen) panelOpen = false else player.togglePause(); poke() },
                contentScale = ContentScale.Fit,
            )
        }
        val err = player.error ?: failure.takeIf { links.isEmpty() && !searching }
        when {
            err != null -> Message(err, "Try another source from the settings panel, or go back.", isError = true)
            links.isEmpty() && searching -> Loading(label = "Finding sources…")
            links.isEmpty() -> Message("No playable sources found", "The provider returned no links for this title.", isError = true)
            player.frame == null || player.buffering -> Loading(label = if (player.frame == null) "Starting…" else "Buffering…")
        }

        // Short confirmation of the last keyboard or button action
        if (toast != null && now < toastUntil) Text(
            toast!!, Modifier.align(Alignment.TopCenter).padding(top = 72.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
        )

        androidx.compose.animation.AnimatedVisibility(
            controlsVisible, Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(Motion.fade(Motion.Ms.fast)), exit = androidx.compose.animation.fadeOut(Motion.fade(Motion.Ms.normal)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
                        .padding(horizontal = (side - 8.dp).coerceAtLeast(6.dp), vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Netflix keeps only a back arrow up here
                    IconBtn(::back, 44.dp) { Glyph(IconKind.Back, Color.White, 30.dp) }
                }
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                        .padding(horizontal = side, vertical = 14.dp),
                ) {
                    val len = player.lengthMs.coerceAtLeast(1)
                    val known = knownLength(player.lengthMs)
                    // Red scrubber with the time left on the right, as Netflix does
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Scrubber(
                            dragging ?: (player.positionMs.toFloat() / len).coerceIn(0f, 1f), known, Modifier.weight(1f),
                            onChange = { dragging = it; poke() },
                            onDone = { dragging?.let { player.seekTo((it * len).toLong()) }; dragging = null },
                        )
                        Text(
                            if (known) formatTime((len - (dragging?.let { (it * len).toLong() } ?: player.positionMs)).coerceAtLeast(0)) else "LIVE",
                            color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 6.dp)) {
                        IconBtn({ player.togglePause() }, 48.dp) { Glyph(if (player.playing) IconKind.Pause else IconKind.Play, Color.White, 32.dp) }
                        SeekBtn(false) { seekBy(-10_000) }
                        SeekBtn(true) { seekBy(10_000) }
                        if (!compact) {
                            IconBtn({ player.changeMute(!player.muted) }, 40.dp) { Glyph(IconKind.Volume, if (player.muted) Brand.Muted else Color.White, 24.dp) }
                            Slider(
                                volume.coerceAtMost(100f), { setVol(it) }, valueRange = 0f..100f, modifier = Modifier.width(100.dp),
                                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                            )
                        }
                        Text(
                            listOfNotNull(s.title, s.episodeName).joinToString("  ·  "), Modifier.weight(1f).padding(horizontal = 10.dp),
                            color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (player.rate != 1f) Text(
                            player.rate.toString().removeSuffix(".0") + "×", color = Color.White, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { player.changeSpeed(1f); say("Speed normal") }.pointerHoverIcon(PointerIcon.Hand).padding(8.dp),
                        )
                        IconBtn({ panelOpen = !panelOpen }, 44.dp) { Glyph(IconKind.Settings, if (panelOpen) Brand.Red else Color.White, 26.dp) }
                        IconBtn(::fullscreen, 44.dp) { Glyph(IconKind.Fullscreen, Color.White, 26.dp) }
                    }
                }
                if (panelOpen) PlayerPanel(
                    player,
                    qualities = listOf("Auto") + variants.map { "${it.first}p" }, quality = quality, onQuality = ::pickQuality,
                    sources = links, currentSource = current, onSource = { start(it, player.positionMs); say("Source: ${it.name}") },
                    subs = subs, volume = volume, onVolume = { volume = it },
                    skipSeconds = SKIP_SECONDS, onSkip = { seekBy(SKIP_SECONDS * 1000L) },
                    onHelp = { panelOpen = false; helpOpen = true },
                    onClose = { panelOpen = false },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = side, bottom = 118.dp),
                )
            }
        }

        // Stays on screen through the first minutes of an episode, where the opening usually is
        if (anime && !panelOpen && knownLength(player.lengthMs) && (controlsVisible || player.positionMs < 360_000)) SkipPill(
            "Skip ${SKIP_SECONDS}s", Modifier.align(Alignment.BottomEnd).padding(end = side, bottom = if (controlsVisible) 124.dp else 56.dp),
        ) { seekBy(SKIP_SECONDS * 1000L) }

        if (helpOpen) ShortcutHelp(Modifier.align(Alignment.Center)) { helpOpen = false }
    }
}

@Composable
private fun IconBtn(onClick: () -> Unit, size: Dp = 40.dp, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    Box(
        Modifier.size(size).graphicsLayer { val k = 1f + 0.12f * hover; scaleX = k; scaleY = k }.clip(CircleShape)
            .clickable(src, null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Circular arrow with "10" inside; mirrored for forward. */
@Composable
private fun SeekBtn(forward: Boolean, onClick: () -> Unit) = IconBtn(onClick, 44.dp) {
    Box(contentAlignment = Alignment.Center) {
        Glyph(IconKind.Replay, Color.White, 32.dp, Modifier.graphicsLayer { scaleX = if (forward) -1f else 1f })
        Text("10", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
    }
}

/** Thin red bar that thickens on hover and shows a round handle; click or drag to seek. */
@Composable
private fun Scrubber(fraction: Float, enabled: Boolean, modifier: Modifier, onChange: (Float) -> Unit, onDone: () -> Unit) {
    var w by remember { mutableStateOf(1) }
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier.height(24.dp).onSizeChanged { w = it.width.coerceAtLeast(1) }.hoverable(src)
            .pointerInput(enabled) { if (enabled) detectTapGestures { onChange((it.x / w).coerceIn(0f, 1f)); onDone() } }
            .pointerInput(enabled) {
                if (enabled) detectDragGestures(
                    onDragStart = { onChange((it.x / w).coerceIn(0f, 1f)) }, onDragEnd = onDone, onDragCancel = onDone,
                ) { c, _ -> c.consume(); onChange((c.position.x / w).coerceIn(0f, 1f)) }
            }
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.CenterStart,
    ) {
        val h = (4 + 3 * hover).dp
        Box(Modifier.fillMaxWidth().height(h).background(Color.White.copy(alpha = 0.3f)))
        Box(Modifier.fillMaxWidth(f).height(h).background(Brand.Red))
        if (enabled) Box(Modifier.offset { IntOffset((f * w - 7.dp.toPx()).roundToInt().coerceAtLeast(0), 0) }.size(14.dp).clip(CircleShape).background(Brand.Red))
    }
}

/** Netflix's "Skip Intro" look: dark glass with a white outline. */
@Composable
private fun SkipPill(text: String, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    Text(
        "⏭  $text", color = Color.White, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f + 0.25f * hover))
            .border(1.dp, Color.White.copy(alpha = 0.7f + 0.3f * hover), RoundedCornerShape(4.dp))
            .clickable(src, null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

private val SHORTCUTS = listOf(
    "Space / K" to "Play or pause",
    "← / J, → / L" to "Back or forward 10 s",
    "Shift + ← / →" to "Back or forward $SKIP_SECONDS s",
    "S" to "Skip $SKIP_SECONDS s",
    "↑ / ↓" to "Volume up or down",
    "M" to "Mute",
    ", and ." to "Slower or faster",
    "A" to "Next audio track",
    "C" to "Next subtitle track",
    "0 – 9" to "Jump to 0 – 90 %",
    "Home" to "Back to the start",
    "P" to "Playback settings",
    "F / F11" to "Full screen",
    "Esc" to "Close panel, then leave",
    "H or ?" to "This list",
)

@Composable
private fun ShortcutHelp(modifier: Modifier, onClose: () -> Unit) {
    Column(
        modifier.width(380.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xF2181818)).border(1.dp, Brand.Line, RoundedCornerShape(8.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keyboard shortcuts", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("✕", Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClose).pointerHoverIcon(PointerIcon.Hand).padding(8.dp), color = Brand.Muted)
        }
        SHORTCUTS.forEach { (k, d) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(k, Modifier.width(150.dp), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(d, color = Brand.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
