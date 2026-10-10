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
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = if (enabled) 1f - 0.18f * hover else 0.4f))
            .clickable(src, null, enabled = enabled, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.Black, fontWeight = FontWeight.Bold) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF6D6D6E).copy(alpha = 0.55f + 0.2f * hover))
            .clickable(src, null, enabled = enabled, onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.White else Brand.Muted, fontWeight = FontWeight.Medium) }
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

enum class IconKind { Home, Library, Plugins, Settings, Play, Pause, Back, Fullscreen, Volume, Star, StarOutline, Search, History, Download, Replay }

/** Line icons drawn on a canvas, so there is no icon font to ship. */
@Composable
fun Glyph(kind: IconKind, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val n = this.size.width
        val w = n * 0.085f
        val st = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(n * x, n * y)
        when (kind) {
            IconKind.Home, IconKind.Search -> {
                drawCircle(tint, n * 0.28f, o(0.44f, 0.44f), style = st)
                drawLine(tint, o(0.66f, 0.66f), o(0.86f, 0.86f), w, StrokeCap.Round)
            }
            IconKind.Library -> drawPath(Path().apply {
                moveTo(n * 0.28f, n * 0.14f); lineTo(n * 0.72f, n * 0.14f); lineTo(n * 0.72f, n * 0.86f)
                lineTo(n * 0.5f, n * 0.68f); lineTo(n * 0.28f, n * 0.86f); close()
            }, tint, style = st)
            IconKind.Plugins -> {
                val s = Size(n * 0.34f, n * 0.34f); val r = androidx.compose.ui.geometry.CornerRadius(n * 0.08f)
                drawRoundRect(tint, o(0.14f, 0.14f), s, r, style = st)
                drawRoundRect(tint, o(0.52f, 0.14f), s, r, style = st)
                drawRoundRect(tint, o(0.14f, 0.52f), s, r, style = st)
                drawRoundRect(tint, o(0.60f, 0.60f), Size(n * 0.26f, n * 0.26f), r)
            }
            IconKind.Settings -> {
                for ((y, kx) in listOf(0.24f to 0.66f, 0.5f to 0.34f, 0.76f to 0.58f)) {
                    drawLine(tint, o(0.14f, y), o(0.86f, y), w, StrokeCap.Round)
                    drawCircle(Brand.Panel, n * 0.1f, o(kx, y)); drawCircle(tint, n * 0.1f, o(kx, y), style = st)
                }
            }
            IconKind.Play -> drawPath(Path().apply { moveTo(n * 0.28f, n * 0.16f); lineTo(n * 0.8f, n * 0.5f); lineTo(n * 0.28f, n * 0.84f); close() }, tint)
            IconKind.Pause -> {
                drawRoundRect(tint, o(0.24f, 0.16f), Size(n * 0.18f, n * 0.68f), androidx.compose.ui.geometry.CornerRadius(n * 0.05f))
                drawRoundRect(tint, o(0.58f, 0.16f), Size(n * 0.18f, n * 0.68f), androidx.compose.ui.geometry.CornerRadius(n * 0.05f))
            }
            IconKind.Back -> { drawLine(tint, o(0.8f, 0.5f), o(0.2f, 0.5f), w, StrokeCap.Round); drawLine(tint, o(0.2f, 0.5f), o(0.46f, 0.24f), w, StrokeCap.Round); drawLine(tint, o(0.2f, 0.5f), o(0.46f, 0.76f), w, StrokeCap.Round) }
            IconKind.Fullscreen -> for ((sx, sy) in listOf(0.14f to 0.14f, 0.86f to 0.14f, 0.14f to 0.86f, 0.86f to 0.86f)) {
                val dx = if (sx < 0.5f) 0.22f else -0.22f; val dy = if (sy < 0.5f) 0.22f else -0.22f
                drawLine(tint, o(sx, sy), o(sx + dx, sy), w, StrokeCap.Round); drawLine(tint, o(sx, sy), o(sx, sy + dy), w, StrokeCap.Round)
            }
            IconKind.Volume -> {
                drawPath(Path().apply { moveTo(n * 0.14f, n * 0.38f); lineTo(n * 0.34f, n * 0.38f); lineTo(n * 0.54f, n * 0.2f); lineTo(n * 0.54f, n * 0.8f); lineTo(n * 0.34f, n * 0.62f); lineTo(n * 0.14f, n * 0.62f); close() }, tint)
                drawArc(tint, -40f, 80f, false, o(0.5f, 0.3f), Size(n * 0.34f, n * 0.4f), style = st)
            }
            IconKind.History -> {
                drawCircle(tint, n * 0.36f, o(0.5f, 0.5f), style = st)
                drawLine(tint, o(0.5f, 0.28f), o(0.5f, 0.5f), w, StrokeCap.Round); drawLine(tint, o(0.5f, 0.5f), o(0.66f, 0.6f), w, StrokeCap.Round)
            }
            IconKind.Replay -> {
                // Counter-clockwise arrow; the player mirrors it for "forward" and writes the seconds on top
                drawArc(tint, -100f, 290f, false, o(0.16f, 0.24f), Size(n * 0.68f, n * 0.68f), style = st)
                drawLine(tint, o(0.44f, 0.245f), o(0.56f, 0.12f), w, StrokeCap.Round)
                drawLine(tint, o(0.44f, 0.245f), o(0.56f, 0.37f), w, StrokeCap.Round)
            }
            IconKind.Download -> {
                drawLine(tint, o(0.5f, 0.14f), o(0.5f, 0.62f), w, StrokeCap.Round)
                drawLine(tint, o(0.5f, 0.62f), o(0.3f, 0.42f), w, StrokeCap.Round); drawLine(tint, o(0.5f, 0.62f), o(0.7f, 0.42f), w, StrokeCap.Round)
                drawLine(tint, o(0.2f, 0.84f), o(0.8f, 0.84f), w, StrokeCap.Round)
            }
            IconKind.Star, IconKind.StarOutline -> {
                val p = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 0.40f else 0.17f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = n * (0.5f + r * Math.cos(a).toFloat()); val y = n * (0.52f + r * Math.sin(a).toFloat())
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                if (kind == IconKind.Star) drawPath(p, tint) else drawPath(p, tint, style = st)
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (trailing != null) Text(trailing, color = Brand.Muted, style = MaterialTheme.typography.labelMedium)
    }
}