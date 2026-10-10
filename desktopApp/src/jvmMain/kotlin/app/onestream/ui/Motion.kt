package app.onestream.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * One motion vocabulary for the whole app. Everything animates transform or opacity only,
 * and everything collapses to (near) instant when [LocalReduceMotion] is on.
 */
object Motion {
    object Ms { const val instant = 80; const val fast = 180; const val normal = 350; const val slow = 600 }

    val smooth = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val sharp = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** Default UI: buttons, chips, nav. */
    fun <T> snappy() = spring<T>(dampingRatio = 0.87f, stiffness = 300f)
    /** Cards and panels landing softly. */
    fun <T> gentle() = spring<T>(dampingRatio = 0.64f, stiffness = 120f)
    /** Playful moments. */
    fun <T> bouncy() = spring<T>(dampingRatio = 0.35f, stiffness = 400f)
    fun <T> fade(ms: Int = Ms.normal) = tween<T>(ms, easing = smooth)

    const val STAGGER_MS = 35L
    const val STAGGER_MAX = 12
}

val LocalReduceMotion = compositionLocalOf { false }

/** Fade + rise on first composition, staggered by [index]. Transform and opacity only. */
@Composable
fun Modifier.enter(index: Int = 0): Modifier {
    val reduce = LocalReduceMotion.current
    var shown by remember { mutableStateOf(reduce) }
    LaunchedEffect(Unit) {
        if (!reduce) delay(index.coerceIn(0, Motion.STAGGER_MAX) * Motion.STAGGER_MS)
        shown = true
    }
    val a by animateFloatAsState(if (shown) 1f else 0f, if (reduce) tween(Motion.Ms.fast) else Motion.fade(Motion.Ms.slow))
    return this.graphicsLayer {
        alpha = a
        translationY = if (reduce) 0f else (1f - a) * 22.dp.toPx()
    }
}

/** Hover state plus a spring-driven lift. The child decides what else to reveal on hover. */
@Composable
fun Modifier.hoverLift(source: MutableInteractionSource, scale: Float = 1.045f): Modifier {
    val reduce = LocalReduceMotion.current
    val hovered by source.collectIsHoveredAsState()
    val s by animateFloatAsState(if (hovered && !reduce) scale else 1f, Motion.snappy())
    return this.hoverable(source).graphicsLayer { scaleX = s; scaleY = s }
}

/** 0..1 hover amount, animated. */
@Composable
fun MutableInteractionSource.hoverAmount(): State<Float> {
    val hovered by collectIsHoveredAsState()
    return animateFloatAsState(if (hovered) 1f else 0f, Motion.fade(Motion.Ms.fast))
}

/** Counts a number up to [target] when it first appears (or changes). */
@Composable
fun animatedNumber(target: Float): Float {
    val reduce = LocalReduceMotion.current
    var start by remember { mutableStateOf(if (reduce) target else 0f) }
    LaunchedEffect(target) { start = target }
    val v by animateFloatAsState(start, if (reduce) tween(Motion.Ms.fast) else tween(1100, easing = Motion.smooth))
    return v
}