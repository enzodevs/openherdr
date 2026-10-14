package dev.cambraia.openherdr.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cambraia.openherdr.protocol.Ansi
import dev.cambraia.openherdr.protocol.TermColor

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF8FD8B2), secondary = Color(0xFFB3CCBD), tertiary = Color(0xFFA5CCDF),
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Material 3 Expressive: Material You colors, springy expressive motion, rounder shapes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenHerdrTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> FallbackDark
        else -> expressiveLightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
        content = content,
    )
}

/** A fixed dark terminal palette, so agent output looks like it does in herdr. */
object Terminal {
    val background = Color(0xFF15171C)
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
