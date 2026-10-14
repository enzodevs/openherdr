package dev.cambraia.openherdr.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cambraia.openherdr.R
import dev.cambraia.openherdr.protocol.Ansi
import dev.cambraia.openherdr.protocol.TermColor

/**
 * Graphite: one cool-tinted neutral in four lightness steps, a near-white primary, and color
 * reserved for agent status so "needs you" is the loudest thing on screen.
 */
object Graphite {
    val page = Color(0xFF0F1012)
    val surface = Color(0xFF16171A)
    val raised = Color(0xFF1D1E22)
    val high = Color(0xFF25262B)
    val highest = Color(0xFF2E2F35)
    val hairline = Color(0x14FFFFFF)
    val outline = Color(0xFF3A3B42)
    val text = Color(0xFFEDEDF0)
    val muted = Color(0xFF9D9EA6)
    val faint = Color(0xFF6C6D75)

    val needsYou = Color(0xFFFF8F7A)
    val working = Color(0xFF8CC4FF)
    val done = Color(0xFF86DCA9)
    val idle = Color(0xFF9D9EA6)
}

private val Colors = darkColorScheme(
    primary = Graphite.text,
    onPrimary = Graphite.page,
    primaryContainer = Graphite.highest,
    onPrimaryContainer = Graphite.text,
    secondary = Graphite.muted,
    onSecondary = Graphite.page,
    secondaryContainer = Graphite.high,
    onSecondaryContainer = Graphite.text,
    tertiary = Graphite.done,
    onTertiary = Graphite.page,
    tertiaryContainer = Color(0xFF1E3328),
    onTertiaryContainer = Graphite.done,
    error = Graphite.needsYou,
    onError = Color(0xFF2A0F0A),
    errorContainer = Color(0xFF3A1F1A),
    onErrorContainer = Color(0xFFFFD3C9),
    background = Graphite.page,
    onBackground = Graphite.text,
    surface = Graphite.page,
    onSurface = Graphite.text,
    surfaceVariant = Graphite.high,
    onSurfaceVariant = Graphite.muted,
    surfaceContainerLowest = Graphite.page,
    surfaceContainerLow = Graphite.surface,
    surfaceContainer = Graphite.raised,
    surfaceContainerHigh = Graphite.high,
    surfaceContainerHighest = Graphite.highest,
    surfaceBright = Graphite.highest,
    surfaceDim = Graphite.page,
    outline = Graphite.outline,
    outlineVariant = Color(0xFF2A2B31),
    inverseSurface = Graphite.text,
    inverseOnSurface = Graphite.page,
    scrim = Color(0xCC000000),
)

private fun variable(id: Int, weight: Int) =
    Font(id, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Unbounded: wide display face for names and headings only. */
val Display = FontFamily(variable(R.font.unbounded, 400), variable(R.font.unbounded, 500), variable(R.font.unbounded, 600))

/** Geist for interface text. */
val Sans = FontFamily(variable(R.font.geist, 400), variable(R.font.geist, 500), variable(R.font.geist, 600))

/** Geist Mono for terminal output, commands and fingerprints. */
val Mono = FontFamily(variable(R.font.geist_mono, 400), variable(R.font.geist_mono, 500), variable(R.font.geist_mono, 700))

private fun display(size: Int, line: Int, weight: Int = 500) =
    TextStyle(fontFamily = Display, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = (-0.2).sp)

private fun sans(size: Int, line: Int, weight: Int = 400, tracking: Double = 0.0) =
    TextStyle(fontFamily = Sans, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

private val Type = Typography(
    displayLarge = display(48, 56),
    displayMedium = display(40, 48),
    displaySmall = display(32, 40),
    headlineLarge = display(28, 36),
    headlineMedium = display(24, 32),
    headlineSmall = display(20, 28),
    titleLarge = display(18, 26),
    titleMedium = sans(16, 22, 600),
    titleSmall = sans(14, 20, 600),
    bodyLarge = sans(16, 24),
    bodyMedium = sans(15, 22),
    bodySmall = sans(13, 18),
    labelLarge = sans(14, 20, 500, 0.1),
    labelMedium = sans(12, 16, 500, 0.2),
    labelSmall = sans(11, 14, 500, 0.3),
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Always dark: the app lives next to terminals and is glanced at in any light. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenHerdrTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = Colors,
        typography = Type,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
    ) {
        CompositionLocalProvider(LocalContentColor provides Graphite.text, content = content)
    }
}

/** A fixed dark terminal palette, so agent output looks like it does in herdr. */
object Terminal {
    val background = Color(0xFF0B0C0E)
    val foreground = Color(0xFFD8DCE3)
    val dim = Color(0xFF8A919D)

    private val base16 = listOf(
        0xFF2A2E36, 0xFFE8737E, 0xFF9BCB7E, 0xFFDDB27A, 0xFF6FB3F2, 0xFFC792EA, 0xFF63C6D1, 0xFFC2C8D2,
        0xFF5F6673, 0xFFFF8A94, 0xFFB3E68F, 0xFFF2C98D, 0xFF8DC6FF, 0xFFDDB0FF, 0xFF7FDDE8, 0xFFFFFFFF,
    ).map { Color(it) }

    fun color(term: TermColor): Color = when (term) {
        is TermColor.Indexed -> if (term.index < 16) base16[term.index] else Color(0xFF000000 or Ansi.xterm256(term.index).toLong())
        is TermColor.Rgb -> Color(term.r, term.g, term.b)
    }
}
