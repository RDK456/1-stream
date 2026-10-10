package app.onestream.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Cinema-dark palette in the manner of Netflix: near-black surfaces, one red, white and grey text. */
object Brand {
    val Red = Color(0xFFE50914)
    val RedDeep = Color(0xFFB20710)
    val Emerald = Color(0xFF46D369)
    val Amber = Color(0xFFF5C518)
    val Rose = Color(0xFFFF5A5F)

    /** App background. */
    val Ink = Color(0xFF141414)
    /** Cards and dialogs. */
    val Panel = Color(0xFF1C1C1C)
    val PanelHi = Color(0xFF2B2B2B)
    /** Inset area inside a plate ("well"). */
    val Well = Color(0xFF181818)
    val Line = Color(0xFF333333)
    val Muted = Color(0xFFB3B3B3)

    val Gradient = Brush.linearGradient(listOf(Red, RedDeep))
    val GradientH = Brush.horizontalGradient(listOf(Red, RedDeep))
}

/** Page side margin, scaled from the window width (set once in App). */
val LocalSide = androidx.compose.runtime.compositionLocalOf { 48.dp }
/** True in narrow windows, where screens drop secondary buttons and stack their content. */
val LocalCompact = androidx.compose.runtime.compositionLocalOf { false }

private val scheme = darkColorScheme(
    primary = Brand.Red,
    onPrimary = Color.White,
    secondary = Brand.RedDeep,
    background = Brand.Ink,
    onBackground = Color(0xFFF2F2F2),
    surface = Brand.Panel,
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Brand.PanelHi,
    onSurfaceVariant = Brand.Muted,
    error = Brand.Rose,
    outline = Brand.Line,
)

@Composable
fun OneStreamTheme(reduceMotion: Boolean = false, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion, content = content)
    }
