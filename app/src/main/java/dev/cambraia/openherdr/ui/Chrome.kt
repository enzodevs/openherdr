package dev.cambraia.openherdr.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule

/** A hairline that is brighter at the top, as if lit from above; the depth cue for every card. */
private val LitEdge = Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x05FFFFFF)))

/** The app's card: one step above the page, a lit edge, generous corners. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = Graphite.raised,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(1.dp, LitEdge)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, border = border, modifier = modifier) { Column(content = content) }
    } else {
        Surface(shape = shape, color = color, border = border, modifier = modifier) { Column(content = content) }
    }
}

/** Large page title in the display face, with an optional line under it and trailing actions. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    inset: Dp = 24.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(start = inset, end = inset / 2, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Graphite.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        actions()
    }
}

/** Small uppercase-free section label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.padding(start = 8.dp, top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = Graphite.text)
        trailing?.let { Text("  $it", style = MaterialTheme.typography.labelMedium, color = Graphite.faint) }
    }
}

/** Centered message for empty, error and first-run states, led by a soft shape. */
@Composable
fun EmptyState(
    shape: Shape,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tint: Color = Graphite.high,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).background(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.4f))), shape))
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Graphite.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        actions()
    }
}

data class TabItem(val label: String, val icon: ImageVector, val badge: Int = 0)

/**
 * Floating capsule tab bar. On Android 13+ it refracts and blurs the content scrolling under it
 * (liquid glass); on 12 it only blurs; below that it is a solid, slightly translucent capsule.
 */
@Composable
fun GlassTabBar(backdrop: Backdrop, tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val surface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Graphite.surface.copy(alpha = 0.55f) else Graphite.high.copy(alpha = 0.96f)
    BoxWithConstraints(
        modifier
            .height(64.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(12.dp.toPx())
                    lens(20.dp.toPx(), 28.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0.6f) },
                shadow = { Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.45f)) },
                onDrawSurface = { drawRect(surface) },
            )
            .padding(6.dp),
    ) {
        val width = maxWidth / tabs.size
        val indicator by animateDpAsState(width * selected, spring(dampingRatio = 0.72f, stiffness = 420f), label = "tab")
        Box(
            Modifier
                .offset(x = indicator)
                .width(width)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                val tint by animateColorAsState(if (active) Graphite.text else Graphite.muted, label = "tint")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        if (tab.badge > 0) Badge(tab.badge, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp))
                    }
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun Badge(count: Int, modifier: Modifier) {
    Box(
        modifier.size(16.dp).background(Graphite.needsYou, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 9) "9+" else "$count", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2A0F0A))
    }
}

/** A status dot with a soft halo, for live indicators. */
@Composable
fun Dot(color: Color, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size * 2), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 2).background(color.copy(alpha = 0.18f), CircleShape))
        Box(Modifier.size(size).background(color, CircleShape))
    }
}

/** Pill-shaped segmented control, used for Chat / Terminal. */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Graphite.raised, CircleShape).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            val bg by animateColorAsState(if (active) Graphite.highest else Color.Transparent, label = "seg")
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) Graphite.text else Graphite.muted)
            }
        }
    }
}
