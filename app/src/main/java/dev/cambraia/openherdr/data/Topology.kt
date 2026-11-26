package dev.cambraia.openherdr.data

import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.AgentStatus
import dev.cambraia.openherdr.protocol.Pane
import dev.cambraia.openherdr.protocol.TabInfo
import dev.cambraia.openherdr.protocol.Workspace

/** One pane with what is known about it: its agent, if any, and the command in its foreground. */
data class PaneNode(val pane: Pane, val agent: Agent?, val command: String?) {
    val id: String get() = pane.id
    val status: AgentStatus get() = agent?.status ?: pane.status
    val cwd: String? get() = pane.foregroundCwd ?: pane.cwd

    /** The pane's own name, else the agent, else what it runs, else "Shell". */
    val title: String
        get() = pane.label?.takeIf { it.isNotBlank() } ?: agent?.label ?: command?.let(Topology::shortCommand) ?: "Shell"

    /** A second line that adds something the title does not already say. */
    val detail: String?
        get() = when {
            agent != null -> agent.title ?: agent.terminalTitle ?: pane.terminalTitle
            command != null && pane.label?.isNotBlank() == true -> command
            else -> pane.terminalTitle?.takeIf { it.isNotBlank() && it != title }
        }
}

data class TabNode(val tab: TabInfo, val panes: List<PaneNode>) {
    /** herdr names unlabeled tabs by their number ("1"); show that as "Tab 1". */
    val title: String
        get() = tab.label.takeIf { it.isNotBlank() && it != tab.number.toString() } ?: "Tab ${tab.number.takeIf { it > 0 } ?: "?"}"
}

data class WorkspaceNode(val workspace: Workspace, val tabs: List<TabNode>) {
    val title: String get() = workspace.label.ifBlank { "Workspace ${workspace.number}" }
    val panes: List<PaneNode> get() = tabs.flatMap { it.panes }

    /** The most urgent agent status inside, or null when no pane runs an agent. */
    val status: AgentStatus?
        get() = panes.mapNotNull { it.agent?.status }.minByOrNull { it.attentionRank }
}

object Topology {
    /**
     * Workspace → tab → pane, in herdr's own order. `pane.list` is authoritative for which panes
     * exist; tabs or workspaces missing from their lists (a race between reads) are synthesized
     * so no pane ever disappears from view.
     */
    fun build(snapshot: Snapshot): List<WorkspaceNode> {
        val agents = snapshot.agents.associateBy { it.paneId }
        val panesByTab = snapshot.panes.groupBy { it.tabId }
        val tabs = (snapshot.tabs + snapshot.panes
            .filter { pane -> snapshot.tabs.none { it.id == pane.tabId } }
            .distinctBy { it.tabId }
            .map { TabInfo(id = it.tabId, workspaceId = it.workspaceId) })
            .groupBy { it.workspaceId }
        val workspaces = snapshot.workspaces + tabs.keys
            .filter { id -> snapshot.workspaces.none { it.id == id } }
            .map { Workspace(id = it) }

        return workspaces.sortedBy { it.number }.map { workspace ->
            WorkspaceNode(
                workspace,
                tabs[workspace.id].orEmpty().sortedBy { it.number }.map { tab ->
                    TabNode(tab, panesByTab[tab.id].orEmpty().map { PaneNode(it, agents[it.id], snapshot.commands[it.id]) })
                },
            )
        }
    }

    /** Blocked agents across all workspaces: pinned above the tree. */
    fun needsYou(tree: List<WorkspaceNode>): List<PaneNode> =
        tree.flatMap { it.panes }.filter { it.agent?.status == AgentStatus.Blocked }

    fun find(tree: List<WorkspaceNode>, paneId: String): PaneNode? =
        tree.firstNotNullOfOrNull { ws -> ws.panes.firstOrNull { it.id == paneId } }

    /** `/usr/bin/node server.js --port 3000` → `node server.js --port 3000`. */
    fun shortCommand(cmdline: String): String {
        val parts = cmdline.trim().split(Regex("\\s+"))
        val head = parts.first().substringAfterLast('/')
        return (listOf(head) + parts.drop(1)).joinToString(" ")
    }

    /** Replaces the home prefix with `~`; other paths are returned unchanged. */
    fun tilde(path: String, home: String?): String = when {
        home.isNullOrEmpty() || home == "/" -> path
        path == home -> "~"
        path.startsWith("$home/") -> "~" + path.removePrefix(home)
        else -> path
    }
}
