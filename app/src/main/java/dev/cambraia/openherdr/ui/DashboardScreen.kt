package dev.cambraia.openherdr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.cambraia.openherdr.data.TabNode
import dev.cambraia.openherdr.data.Topology
import dev.cambraia.openherdr.data.WorkspaceNode
import dev.cambraia.openherdr.data.Connection
import dev.cambraia.openherdr.data.ServerProfile
import dev.cambraia.openherdr.data.Snapshot
import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.AgentStatus

/** Server-side changes the tree can make; each suspends until herdr has answered. */
interface TreeActions {
    /** Returns the new workspace's first pane. */
    suspend fun createWorkspace(label: String?, cwd: String?, command: String?): String
    suspend fun createTab(workspaceId: String, label: String?, command: String?): String
    suspend fun renameWorkspace(workspaceId: String, label: String)
    suspend fun renameTab(tabId: String, label: String)
    suspend fun closeWorkspace(workspaceId: String)
    suspend fun closeTab(tabId: String)
}

private sealed interface TreeDialog {
    data object NewWorkspace : TreeDialog
    data class NewTab(val ws: WorkspaceNode) : TreeDialog
    data class RenameWorkspace(val ws: WorkspaceNode) : TreeDialog
    data class RenameTab(val tab: TabNode) : TreeDialog
    data class CloseWorkspace(val ws: WorkspaceNode) : TreeDialog
    data class CloseTab(val tab: TabNode) : TreeDialog
}

/** Agents tab: the live board of the connected server, or a server picker when disconnected. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentsTab(
    connection: Connection,
    snapshot: Snapshot,
    profiles: List<ServerProfile>,
    bottomInset: androidx.compose.ui.unit.Dp,
    onConnect: (ServerProfile) -> Unit,
    onAddServer: () -> Unit,
    onOpenPane: (String) -> Unit,
    actions: TreeActions,
    onRetry: () -> Unit,
    onDeviceKey: () -> Unit,
    onTrust: (Connection.NeedsTrust) -> Unit,
    onDisconnect: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    collapsed: MutableList<String> = remember { mutableStateListOf() },
) {
    val profile = connection.activeProfile
    var dialog by remember { mutableStateOf<TreeDialog?>(null) }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = profile?.name ?: "Workspaces",
                subtitle = connection.summary(),
                inset = 8.dp,
            ) {
                if (connection is Connection.Connected && snapshot.loaded) {
                    FilledTonalIconButton(onClick = { dialog = TreeDialog.NewWorkspace }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Default.Add, contentDescription = "New workspace")
                    }
                }
                if (profile != null) {
                    FilledTonalIconButton(onClick = onDisconnect, shapes = IconButtonDefaults.shapes()) {
                        Icon(Glyphs.Power, contentDescription = "Disconnect")
                    }
                }
            }
        }
        when {
            profile == null && profiles.isEmpty() -> item(key = "first-run") {
                EmptyState(
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    title = "Add your first server",
                    body = "OpenHerdr connects over SSH to the machine where herdr runs. Nothing is installed there.",
                    modifier = Modifier.padding(top = 48.dp),
                ) { Button(onClick = onAddServer) { Text("Add server") } }
            }
            profile == null -> {
                item(key = "pick") { SectionLabel("Connect to") }
                items(profiles, key = { it.id }) { server ->
                    ServerCard(server, live = false, onClick = { onConnect(server) }, modifier = Modifier.animateItem())
                }
            }
            connection is Connection.Failed && !connection.retrying -> item(key = "auth") {
                EmptyState(
                    shape = MaterialShapes.Ghostish.toShape(),
                    title = "Could not sign in",
                    body = connection.message,
                    tint = Graphite.needsYou.copy(alpha = 0.35f),
                    modifier = Modifier.padding(top = 32.dp),
                ) {
                    Button(onClick = onDeviceKey) { Text("Show device key") }
                    OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) { Text("Try again") }
                }
            }
            connection is Connection.Refused -> item(key = "refused") {
                EmptyState(
                    shape = MaterialShapes.Burst.toShape(),
                    title = "Server identity changed",
                    body = "Connection refused for your safety.",
                    tint = Graphite.needsYou.copy(alpha = 0.35f),
                    modifier = Modifier.padding(top = 32.dp),
                ) { Button(onClick = onDisconnect) { Text("Close") } }
            }
            !snapshot.loaded -> item(key = "loading") {
                Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ContainedLoadingIndicator(Modifier.size(72.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(connection.summary(), color = Graphite.muted)
                }
            }
            snapshot.panes.isEmpty() -> item(key = "empty") {
                EmptyState(
                    shape = MaterialShapes.Flower.toShape(),
                    title = "No workspaces yet",
                    body = "Create one here or in herdr. Agents and terminals show up as soon as they start.",
                    modifier = Modifier.padding(top = 32.dp),
                ) { Button(onClick = { dialog = TreeDialog.NewWorkspace }) { Text("New workspace") } }
            }
            else -> {
                val tree = Topology.build(snapshot)
                if (snapshot.agents.isNotEmpty()) item(key = "summary") { Summary(snapshot.agents) }
                val waiting = Topology.needsYou(tree)
                if (waiting.isNotEmpty()) {
                    item(key = "needs") { SectionLabel("Needs you", trailing = "${waiting.size}", modifier = Modifier.animateItem()) }
                    items(waiting, key = { "needs-${it.id}" }) { node ->
                        PaneCard(node, snapshot.home, Modifier.animateItem()) { onOpenPane(node.id) }
                    }
                }
                tree.forEach { ws ->
                    item(key = "ws-${ws.workspace.id}") {
                        WorkspaceHeader(
                            ws,
                            Modifier.animateItem(),
                            collapsed = ws.workspace.id in collapsed,
                            onToggle = { if (!collapsed.remove(ws.workspace.id)) collapsed.add(ws.workspace.id) },
                            actions = listOf(
                                MenuAction("New tab") { dialog = TreeDialog.NewTab(ws) },
                                MenuAction("Rename workspace") { dialog = TreeDialog.RenameWorkspace(ws) },
                                MenuAction("Close workspace…", danger = true) { dialog = TreeDialog.CloseWorkspace(ws) },
                            ),
                        )
                    }
                    if (ws.workspace.id !in collapsed) ws.tabs.forEach { tab ->
                        item(key = "tab-${tab.tab.id}") {
                            TabHeader(
                                tab,
                                Modifier.animateItem(),
                                collapsed = tab.tab.id in collapsed,
                                onToggle = { if (!collapsed.remove(tab.tab.id)) collapsed.add(tab.tab.id) },
                                actions = listOf(
                                    MenuAction("Rename tab") { dialog = TreeDialog.RenameTab(tab) },
                                    MenuAction("Close tab…", danger = true) { dialog = TreeDialog.CloseTab(tab) },
                                ),
                            )
                        }
                        if (tab.tab.id !in collapsed) items(tab.panes, key = { it.id }) { node ->
                            PaneCard(node, snapshot.home, Modifier.animateItem()) { onOpenPane(node.id) }
                        }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        TreeDialog.NewWorkspace -> CreateDialog(
            "New workspace", withCwd = true, home = snapshot.home,
            onCreate = { label, cwd, command -> onOpenPane(actions.createWorkspace(label, cwd, command)) },
            onDismiss = { dialog = null },
        )
        is TreeDialog.NewTab -> CreateDialog(
            "New tab in ${d.ws.title}", withCwd = false, home = snapshot.home,
            onCreate = { label, _, command -> onOpenPane(actions.createTab(d.ws.workspace.id, label, command)) },
            onDismiss = { dialog = null },
        )
        is TreeDialog.RenameWorkspace -> RenameDialog(
            "Rename workspace", d.ws.workspace.label,
            onRename = { actions.renameWorkspace(d.ws.workspace.id, it) }, onDismiss = { dialog = null },
        )
        is TreeDialog.RenameTab -> RenameDialog(
            "Rename tab", d.tab.tab.label.takeIf { it != d.tab.tab.number.toString() }.orEmpty(),
            onRename = { actions.renameTab(d.tab.tab.id, it) }, onDismiss = { dialog = null },
        )
        is TreeDialog.CloseWorkspace -> ConfirmCloseDialog(
            "Close workspace ${d.ws.title}?", describePanes(d.ws.panes, snapshot.home), agentWarning(d.ws.panes),
            onClose = { actions.closeWorkspace(d.ws.workspace.id) }, onDismiss = { dialog = null },
        )
        is TreeDialog.CloseTab -> ConfirmCloseDialog(
            "Close tab ${d.tab.title}?", describePanes(d.tab.panes, snapshot.home), agentWarning(d.tab.panes),
            onClose = { actions.closeTab(d.tab.tab.id) }, onDismiss = { dialog = null },
        )
    }

    if (connection is Connection.NeedsTrust) {
        TrustHostDialog(connection.failure.hostId, connection.failure.pin, onTrust = { onTrust(connection) }, onCancel = onDisconnect)
    }
    if (connection is Connection.Refused) {
        HostKeyChangedDialog(connection.failure.hostId, connection.failure.expected, connection.failure.actual, onClose = onDisconnect)
    }
}

@Composable
private fun Summary(agents: List<Agent>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("Need you", agents.count { it.status == AgentStatus.Blocked }, AgentStatus.Blocked, Modifier.weight(1f))
        Stat("Working", agents.count { it.status == AgentStatus.Working }, AgentStatus.Working, Modifier.weight(1f))
        Stat("Done", agents.count { it.status == AgentStatus.Done }, AgentStatus.Done, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, count: Int, status: AgentStatus, modifier: Modifier) {
    val loud = status == AgentStatus.Blocked && count > 0
    Panel(modifier, color = if (loud) MaterialTheme.colorScheme.errorContainer else Graphite.raised) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("$count", style = MaterialTheme.typography.headlineLarge, color = if (count > 0) status.hue() else Graphite.faint)
            Text(label, style = MaterialTheme.typography.labelMedium, color = Graphite.muted)
        }
    }
}

/** A server as a card: tinted mark, name and address; [live] marks the connected one. */
@Composable
fun ServerCard(
    server: ServerProfile,
    live: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = { Icon(Glyphs.Chevron, contentDescription = null, tint = Graphite.faint) },
) {
    Panel(modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ServerMark(server)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (live) Dot(Graphite.done, 6.dp, Modifier.padding(start = 6.dp))
                }
                Text(
                    "${server.user}@${server.host}" + (if (server.port != 22) ":${server.port}" else "") +
                        (server.herdrSession?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = Mono,
                    color = Graphite.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

private val MarkTints = listOf(Color(0xFF8CC4FF), Color(0xFF86DCA9), Color(0xFFE5B8FF), Color(0xFFFFC98A), Color(0xFF8FE3E0))

/**
 * Two letters and a tint picked from the host, so "PC (Wi-Fi)" and "PC (WireGuard)" differ:
 * the capitals of the part in parentheses when there is one ("WF", "WG"), else of the name.
 */
@Composable
private fun ServerMark(server: ServerProfile) {
    val letters = serverInitials(server.name)
    val tint = MarkTints[Math.floorMod(server.host.hashCode(), MarkTints.size)]
    Surface(
        color = tint.copy(alpha = 0.14f),
        contentColor = tint,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f)),
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(letters, fontFamily = Display, style = MaterialTheme.typography.labelLarge)
        }
    }
}

fun serverInitials(name: String): String {
    val focus = Regex("\\(([^)]+)\\)").find(name)?.groupValues?.get(1) ?: name
    val capitals = focus.filter { it.isUpperCase() || it.isDigit() }
    val words = focus.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return when {
        capitals.length >= 2 -> capitals.take(2)
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        words.isNotEmpty() -> words[0].take(2)
        else -> "?"
    }.uppercase()
}

fun Connection.summary(): String = when (this) {
    Connection.Idle -> "Not connected"
    is Connection.Connecting -> if (attempt == 0) "Connecting…" else "Reconnecting…"
    is Connection.Connected -> "Live · ${profile.user}@${profile.host}"
    is Connection.NeedsTrust -> "Waiting for you to trust the server"
    is Connection.Refused -> "Refused"
    is Connection.Failed -> if (retrying) "Reconnecting… $message" else "Could not sign in"
}
