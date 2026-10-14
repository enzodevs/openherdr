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
import dev.cambraia.openherdr.data.AgentAlerts
import dev.cambraia.openherdr.data.Connection
import dev.cambraia.openherdr.data.ServerProfile
import dev.cambraia.openherdr.data.Snapshot
import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.AgentStatus

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
    onOpenAgent: (Agent) -> Unit,
    onRetry: () -> Unit,
    onDeviceKey: () -> Unit,
    onTrust: (Connection.NeedsTrust) -> Unit,
    onDisconnect: () -> Unit,
) {
    val profile = connection.activeProfile
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = profile?.name ?: "Agents",
                subtitle = connection.summary(),
                inset = 8.dp,
            ) {
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
            snapshot.agents.isEmpty() -> item(key = "empty") {
                EmptyState(
                    shape = MaterialShapes.Flower.toShape(),
                    title = "No agents yet",
                    body = "Start Claude Code, Codex, Pi or another agent inside herdr and it shows up here.",
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
            else -> {
                item(key = "summary") { Summary(snapshot.agents) }
                val labels = snapshot.workspaces.associate { it.id to it.label.ifBlank { "Workspace ${it.number}" } }
                AgentAlerts.grouped(snapshot.agents, labels).forEach { (workspace, agents) ->
                    item(key = "ws-$workspace") { SectionLabel(workspace, trailing = "${agents.size}", modifier = Modifier.animateItem()) }
                    items(agents, key = { it.paneId }) { agent ->
                        AgentCard(agent, Modifier.animateItem()) { onOpenAgent(agent) }
                    }
                }
            }
        }
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

@Composable
private fun AgentCard(agent: Agent, modifier: Modifier, onClick: () -> Unit) {
    val blocked = agent.status == AgentStatus.Blocked
    Panel(
        modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (blocked) MaterialTheme.colorScheme.errorContainer else Graphite.raised,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(agent)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(agent.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                agent.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Graphite.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            StatusPill(agent.status)
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
