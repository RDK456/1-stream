package app.onestream.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage

/** Brand spinner: a gradient arc, rotated with a transform. */
@Composable
fun Loading(modifier: Modifier = Modifier.fillMaxSize(), label: String? = null) {
    val spin = rememberInfiniteTransition()
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart))
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Canvas(Modifier.size(38.dp).graphicsLayer { rotationZ = angle }) {
                val w = 4.dp.toPx()
                drawArc(Brand.Line, 0f, 360f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
                drawArc(Brand.Gradient, 0f, 100f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
            if (label != null) Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A grey block with a light band sweeping across it: the placeholder shown while content loads. */
@Composable
fun Modifier.shimmer(): Modifier {
    val reduce = LocalReduceMotion.current
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(-0.5f, 1.5f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "sweep")
    return drawBehind {
        drawRect(Color(0xFF232323))
        if (!reduce) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x22FFFFFF), Color.Transparent), startX = (sweep - 0.3f) * size.width, endX = (sweep + 0.3f) * size.width))
    }
}

/** What Home looks like before its rows arrive: a billboard block and two rows of poster blocks. */
@Composable
fun HomeSkeleton(posterW: Dp, hero: Boolean = true) {
    val side = LocalSide.current
    Column(Modifier.fillMaxWidth()) {
        if (hero) Box(Modifier.fillMaxWidth().height(300.dp).shimmer())
        repeat(2) {
            Column(Modifier.padding(top = 18.dp)) {
                Box(Modifier.padding(start = side).width(170.dp).height(20.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                LazyRow(Modifier.padding(top = 14.dp), contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp), userScrollEnabled = false) {
                    items(10) { Box(Modifier.width(posterW).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp)).shimmer()) }
                }
            }
        }
    }
}

@Composable
fun Message(title: String, body: String? = null, isError: Boolean = false, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            )
            if (body != null) Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Poster tile: lifts on hover, reveals a play button, shows a gradient progress bar when resuming. */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    subtitle: String? = null,
    progress: Float? = null,
    modifier: Modifier = Modifier,
    index: Int = 0,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val hover by src.hoverAmount()
    val shape = RoundedCornerShape(4.dp)
    Column(
        modifier.enter(index).zIndex(hover).hoverLift(src, 1.08f).clip(shape).clickable(src, null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape).background(Brand.PanelHi)
                .border(2.dp, Color.White.copy(alpha = 0.85f * hover), shape),
            contentAlignment = Alignment.Center,
        ) {
            if (posterUrl != null) AsyncImage(posterUrl, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(title.take(1).uppercase(), style = MaterialTheme.typography.displayMedium, color = Brand.Red, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = hover }.background(Brush.verticalGradient(listOf(Color.Transparent, Brand.Ink.copy(alpha = 0.75f)))))
            Box(
                Modifier.size(48.dp).graphicsLayer { alpha = hover; val s = 0.7f + 0.3f * hover; scaleX = s; scaleY = s }
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Glyph(IconKind.Play, Color.White, 22.dp) }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0xFF5A5A5A))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Brand.Red))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 2.dp))
        if (subtitle != null) Text(
            subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit = {}) {
    // Netflix-style filter pill: outlined grey, white fill when chosen
    val bg = if (selected) Color.White else Color.Transparent
    val fg = if (selected) Color.Black else MaterialTheme.colorScheme.onSurface
    Text(
        text, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg)
            .border(1.dp, if (selected) Color.White else Color(0xFF777777), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 5.dp),
    )
}

@Composable
fun SectionTitle(text: String) = SectionHeader(text)

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
fun RowSpacer(w: Int = 8) = Spacer(Modifier.width(w.dp))