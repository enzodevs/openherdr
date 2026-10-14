package dev.cambraia.openherdr.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Room left under scrolling content so its last item clears the floating tab bar. */
val TabBarClearance: Dp = 120.dp

/**
 * The tabbed home. Tab content is recorded into a backdrop layer, so the floating glass tab
 * bar can refract and blur what scrolls beneath it.
 */
@Composable
fun HomeScreen(tab: Tab, blocked: Int, onTab: (Tab) -> Unit, content: @Composable (Tab) -> Unit) {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize().background(Graphite.page)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Graphite.page)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                label = "tabs",
            ) { content(it) }
        }
        GlassTabBar(
            backdrop = backdrop,
            tabs = listOf(
                TabItem("Agents", Glyphs.Agents, badge = blocked),
                TabItem("Servers", Glyphs.Servers),
                TabItem("Settings", Icons.Default.Settings),
            ),
            selected = tab.ordinal,
            onSelect = { onTab(Tab.entries[it]) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 40.dp, end = 40.dp, bottom = 12.dp)
                .fillMaxWidth(),
        )
    }
}
