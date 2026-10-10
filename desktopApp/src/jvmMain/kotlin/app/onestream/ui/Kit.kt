package app.onestream.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Card-in-a-card: an outer plate with an optional header and an inset well for the content. */
@Composable
fun Plate(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(8.dp)).background(Brand.Panel).padding(8.dp),
    ) {
        if (title != null || action != null) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                action?.invoke(this)
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Brand.Well).padding(14.dp),
            content = content,
        )
    }
}

/** Big-number tile. The value counts up when it appears. */
@Composable
fun StatTile(label: String, value: Float, unit: String = "", decimals: Int = 0, hint: String? = null, positive: Boolean = true, modifier: Modifier = Modifier) {
    val shown = animatedNumber(value)
    Plate(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (decimals == 0) shown.toInt().toString() else "%.${decimals}f".format(shown),
                fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
            )
            if (unit.isNotEmpty()) Text(unit, Modifier.padding(start = 4.dp, bottom = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = if (positive) Brand.Emerald else Brand.Muted, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Circular progress with the brand gradient. */
@Composable
fun ProgressRing(progress: Float, size: Dp = 44.dp, stroke: Dp = 4.dp, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.fade(Motion.Ms.slow))
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(Brand.Line, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            drawArc(Brand.Gradient, -90f, 360f * p, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

@Composable
fun PillTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(4.dp)).background(Brand.Well).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) Brand.PanelHi else Color.Transparent, Motion.fade(Motion.Ms.fast))
            val fg by animateColorAsState(if (on) Color.White else Brand.Muted, Motion.fade(Motion.Ms.fast))
            Text(
                label, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(bg).clickable { onSelect(i) }
                    .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: IconKind? = null) {
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = if (enabled) 1f - 0.18f * hover else 0.4f))
            .clickable(src, null, enabled = enabled, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Glyph(icon, Color.Black, 22.dp)
            if (text.isNotEmpty()) Text(text, color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: IconKind? = null) {
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF6D6D6E).copy(alpha = 0.55f + 0.2f * hover))
            .clickable(src, null, enabled = enabled, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Glyph(icon, if (enabled) Color.White else Brand.Muted, 20.dp)
            if (text.isNotEmpty()) Text(text, color = if (enabled) Color.White else Brand.Muted, fontWeight = FontWeight.Medium)
        }
    }
}

/** The 1-stream mark: a bar and a play triangle on the brand gradient. */
@Composable
fun BrandMark(size: Dp = 36.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val n = this.size.width
        drawRoundRect(Brand.Gradient, cornerRadius = androidx.compose.ui.geometry.CornerRadius(n * 0.24f), size = Size(n, n))
        val ink = Color(0xFF07090D)
        drawRoundRect(ink, Offset(n * 0.22f, n * 0.26f), Size(n * 0.085f, n * 0.48f), androidx.compose.ui.geometry.CornerRadius(n * 0.04f))
        val tri = Path().apply {
            moveTo(n * 0.44f, n * 0.30f); lineTo(n * 0.80f, n * 0.50f); lineTo(n * 0.44f, n * 0.70f); close()
        }
        drawPath(tri, ink, style = Fill)
    }
}

enum class IconKind {
    Home, Library, Plugins, Settings, Play, Pause, Back, Fullscreen, FullscreenExit, Volume, VolumeOff, Star, StarOutline,
    Search, History, Download, Replay, Shuffle, Close, Check, Add, Next,
}

/** Material Design icon outlines (Apache-2.0) on a 24 x 24 grid. */
private val ICON_PATHS = mapOf(
    IconKind.Home to "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
    IconKind.Library to "M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l6 4.5-6 4.5z",
    IconKind.Plugins to "M20.5 11H19V7c0-1.1-.9-2-2-2h-4V3.5C13 2.12 11.88 1 10.5 1S8 2.12 8 3.5V5H4c-1.1 0-1.99.9-1.99 2v3.8H3.5c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V20c0 1.1.9 2 2 2h3.8v-1.5c0-1.49 1.21-2.7 2.7-2.7 1.49 0 2.7 1.21 2.7 2.7V22H17c1.1 0 2-.9 2-2v-4h1.5c1.38 0 2.5-1.12 2.5-2.5S21.88 11 20.5 11z",
    IconKind.Settings to "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
    IconKind.Play to "M8 5v14l11-7z",
    IconKind.Pause to "M6 19h4V5H6v14zm8-14v14h4V5h-4z",
    IconKind.Back to "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
    IconKind.Fullscreen to "M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z",
    IconKind.FullscreenExit to "M5 16h3v3h2v-5H5v2zm3-8H5v2h5V5H8v3zm6 11h2v-3h3v-2h-5v5zm2-11V5h-2v5h5V8h-3z",
    IconKind.Volume to "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
    IconKind.VolumeOff to "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z",
    IconKind.Star to "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z",
    IconKind.StarOutline to "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z",
    IconKind.Search to "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
    IconKind.History to "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
    IconKind.Download to "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
    // Counter-clockwise arrow; the player mirrors it for "forward" and writes the seconds on top
    IconKind.Replay to "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z",
    IconKind.Shuffle to "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z",
    IconKind.Close to "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
    IconKind.Check to "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
    IconKind.Add to "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    IconKind.Next to "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z",
)

/** Icons from [ICON_PATHS], so there is no icon font to ship. */
@Composable
fun Glyph(kind: IconKind, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val path = remember(kind) { androidx.compose.ui.graphics.vector.PathParser().parsePathString(ICON_PATHS.getValue(kind)).toPath() }
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        withTransform({ scale(k, k, Offset.Zero) }) { drawPath(path, tint) }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (trailing != null) Text(trailing, color = Brand.Muted, style = MaterialTheme.typography.labelMedium)
    }
}