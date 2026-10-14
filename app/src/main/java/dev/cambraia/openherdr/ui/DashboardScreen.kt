package dev.cambraia.openherdr.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cambraia.openherdr.data.AgentAlerts
import dev.cambraia.openherdr.data.Connection
import dev.cambraia.openherdr.data.Snapshot
import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.AgentStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DashboardScreen(
    connection: Connection,
    snapshot: Snapshot,
    onOpenAgent: (Agent) -> Unit,
    onRetry: () -> Unit,
    onDeviceKey: () -> Unit,
    onTrust: (Connection.NeedsTrust) -> Unit,
    onDisconnect: () -> Unit,
) {
    val profile = connection.activeProfile
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(profile?.name ?: "OpenHerdr", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = { Text(connection.summary(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onDisconnect) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = onDisconnect) { Icon(Icons.Default.Close, contentDescription = "Disconnect") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                connection is Connection.Failed && !connection.retrying -> Message(
                    shape = MaterialShapes.Ghostish,
                    title = "Could not sign in",
                    body = connection.message,
                    primary = "Show device key" to onDeviceKey,
                    secondary = "Try again" to onRetry,
                )
                connection is Connection.Refused -> Message(
                    shape = MaterialShapes.Burst,
                    title = "Server identity changed",
                    body = "Connection refused for your safety.",
                    primary = "Back" to onDisconnect,
                )
                !snapshot.loaded -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ContainedLoadingIndicator(Modifier.size(72.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(connection.summary(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                snapshot.agents.isEmpty() -> PullToRefreshBox(isRefreshing = false, onRefresh = onRetry, modifier = Modifier.fillMaxSize()) {
                    Message(
                        shape = MaterialShapes.Flower,
                        title = "No agents yet",
                        body = "Start Claude Code, Codex, Pi or another agent inside herdr and it shows up here.",
                    )
                }
                else -> AgentList(snapshot, onOpenAgent)
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
private fun AgentList(snapshot: Snapshot, onOpenAgent: (Agent) -> Unit) {
    val labels = snapshot.workspaces.associate { it.id to it.label.ifBlank { "Workspace ${it.number}" } }
    val groups = AgentAlerts.grouped(snapshot.agents, labels)
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "summary") { Summary(snapshot.agents) }
        groups.forEach { (workspace, agents) ->
            item(key = "ws-$workspace") {
                Row(
                    Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp).animateItem(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(workspace, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text("  ${agents.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(agents, key = { _, agent -> agent.paneId }) { index, agent ->
                AgentRow(agent, index, agents.size, Modifier.animateItem()) { onOpenAgent(agent) }
            }
        }
    }
}

@Composable
private fun Summary(agents: List<Agent>) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("Need you", agents.count { it.status == AgentStatus.Blocked }, c.errorContainer, c.onErrorContainer, Modifier.weight(1f))
        Stat("Working", agents.count { it.status == AgentStatus.Working }, c.primaryContainer, c.onPrimaryContainer, Modifier.weight(1f))
        Stat("Done", agents.count { it.status == AgentStatus.Done }, c.tertiaryContainer, c.onTertiaryContainer, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, count: Int, container: Color, content: Color, modifier: Modifier) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("$count", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AgentRow(agent: Agent, index: Int, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val blocked = agent.status == AgentStatus.Blocked
    Surface(
        onClick = onClick,
        shape = segmentShape(index, count),
        color = if (blocked) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(agent)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(agent.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                agent.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            StatusPill(agent.status)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Message(
    shape: androidx.graphics.shapes.RoundedPolygon,
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = shape.toShape(), modifier = Modifier.size(120.dp)) {}
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        primary?.let { (label, action) -> Button(onClick = action) { Text(label) } }
        secondary?.let { (label, action) -> OutlinedButton(onClick = action, modifier = Modifier.padding(top = 8.dp)) { Text(label) } }
    }
}

private fun Connection.summary(): String = when (this) {
    Connection.Idle -> "Disconnected"
    is Connection.Connecting -> if (attempt == 0) "Connecting…" else "Reconnecting…"
    is Connection.Connected -> "Live · ${profile.user}@${profile.host}"
    is Connection.NeedsTrust -> "Waiting for you to trust the server"
    is Connection.Refused -> "Refused"
    is Connection.Failed -> if (retrying) "Reconnecting… $message" else "Failed"
}
