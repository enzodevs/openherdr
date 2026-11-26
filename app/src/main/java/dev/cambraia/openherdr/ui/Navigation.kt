package dev.cambraia.openherdr.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

enum class Tab { Agents, Servers, Settings }

sealed interface Screen {
    /** The tabbed home; the selected tab lives in [NavViewModel.tab]. */
    data object Home : Screen
    data class Editor(val profileId: String?) : Screen
    data object DeviceKey : Screen
    data class Agent(val paneId: String) : Screen
}

/** A tiny back stack over the tabbed home that survives configuration changes. */
class NavViewModel : ViewModel() {
    val stack = mutableStateListOf<Screen>(Screen.Home)

    var tab by mutableStateOf(Tab.Agents)

    /** Where the workspace list was scrolled to, so coming back from a pane lands in the same place. */
    val treeScroll = androidx.compose.foundation.lazy.LazyListState()

    /** Ids of the workspaces and tabs folded down to their header on the home list. */
    val collapsed = mutableStateListOf<String>()

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

    /** Back from the home: other tabs return to Agents first. */
    fun back(): Boolean = when {
        pop() -> true
        tab != Tab.Agents -> { tab = Tab.Agents; true }
        else -> false
    }

    fun home(tab: Tab) {
        forward = false
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        this.tab = tab
    }

    /** Opens an agent from anywhere (notification taps), on top of the Agents tab. */
    fun openAgent(paneId: String) {
        home(Tab.Agents)
        push(Screen.Agent(paneId))
    }
}
