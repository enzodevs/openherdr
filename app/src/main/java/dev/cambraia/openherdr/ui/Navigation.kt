package dev.cambraia.openherdr.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

sealed interface Screen {
    data object Servers : Screen
    data class Editor(val profileId: String?) : Screen
    data object DeviceKey : Screen
    data object Dashboard : Screen
    data class Agent(val paneId: String) : Screen
}

/** A tiny back stack that survives configuration changes. */
class NavViewModel : ViewModel() {
    val stack = mutableStateListOf<Screen>(Screen.Servers)

    val current: Screen
        get() = stack.last()

    /** Direction of the last navigation, for slide transitions. */
    var forward = true
        private set

    fun push(screen: Screen) {
        forward = true
        if (current != screen) stack.add(screen)
    }

    fun pop(): Boolean = if (stack.size > 1) {
        forward = false
        stack.removeAt(stack.lastIndex)
        true
    } else {
        false
    }

    /** Shows [screen] on top of the dashboard, dropping anything above it. */
    fun showOverDashboard(screen: Screen) {
        val dashboard = stack.indexOf(Screen.Dashboard)
        if (dashboard < 0) return
        forward = true
        while (stack.lastIndex > dashboard) stack.removeAt(stack.lastIndex)
        stack.add(screen)
    }

    fun popTo(screen: Screen) {
        forward = false
        while (stack.size > 1 && current != screen) stack.removeAt(stack.lastIndex)
    }
}
