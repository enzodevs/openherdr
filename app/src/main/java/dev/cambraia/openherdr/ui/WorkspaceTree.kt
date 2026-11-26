package dev.cambraia.openherdr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cambraia.openherdr.data.PaneNode
import dev.cambraia.openherdr.data.TabNode
import dev.cambraia.openherdr.data.Topology
import dev.cambraia.openherdr.data.WorkspaceNode
import dev.cambraia.openherdr.protocol.AgentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Any pane as a card: agent avatar or terminal mark, what it is, and where it runs. */
@Composable
fun PaneCard(node: PaneNode, home: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val blocked = node.agent?.status == AgentStatus.Blocked
    Panel(
        modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (blocked) MaterialTheme.colorScheme.errorContainer else Graphite.raised,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            PaneMark(node)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(node.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                node.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (node.agent == null) Mono else null,
                        color = Graphite.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                node.cwd?.let { CwdLine(Topology.tilde(it, home)) }
            }
            node.agent?.let { StatusPill(it.status) }
        }
    }
}

@Composable
fun CwdLine(path: String, modifier: Modifier = Modifier) {
    Text(
        path,
        fontFamily = Mono,
        fontSize = 12.sp,
        color = Graphite.faint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { contentDescription = "Directory $path" },
    )
}

/** The agent's avatar, or a terminal mark that lights up while a command runs. */
@Composable
fun PaneMark(node: PaneNode, size: Dp = 48.dp) {
    val agent = node.agent
    if (agent != null) {
        AgentAvatar(agent, size)
        return
    }
    val running = node.command != null
    Surface(
        color = if (running) Graphite.highest else Graphite.high,
        contentColor = if (running) Graphite.text else Graphite.faint,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(Glyphs.Terminal, contentDescription = null, modifier = Modifier.size(size * 0.46f)) }
    }
}

data class MenuAction(val label: String, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
fun Overflow(description: String, actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = description, tint = Graphite.muted) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Graphite.high) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label, color = if (action.danger) Graphite.needsYou else Graphite.text) },
                    onClick = { open = false; action.onClick() },
                )
            }
        }
    }
}

@Composable
fun WorkspaceHeader(node: WorkspaceNode, modifier: Modifier = Modifier, actions: List<MenuAction>) {
    val tabs = node.tabs.size
    val panes = node.panes.size
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        node.status?.let { Dot(it.hue(), 8.dp, Modifier.padding(end = 10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(node.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${plural(tabs, "tab")} · ${plural(panes, "pane")}",
                style = MaterialTheme.typography.labelMedium,
                color = Graphite.muted,
            )
        }
        Overflow("Workspace ${node.title} actions", actions)
    }
}

@Composable
fun TabHeader(node: TabNode, modifier: Modifier = Modifier, actions: List<MenuAction>) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            node.title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = Graphite.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Overflow("Tab ${node.title} actions", actions)
    }
}

fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

/** What closing will end, one line per pane, so the confirmation names real things. */
fun describePanes(panes: List<PaneNode>, home: String?): List<String> = panes.map { node ->
    listOfNotNull(node.title, node.command?.takeIf { it != node.title && node.agent == null }, node.cwd?.let { Topology.tilde(it, home) })
        .distinct().joinToString(" · ")
}

/**
 * A dialog whose action talks to the server: it stays open while the request runs and shows
 * the server's error inline, so a failed close or rename is never silently lost.
 */
@Composable
private fun ActionDialog(
    title: String,
    confirm: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onConfirm: suspend () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!pending) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Graphite.needsYou) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && !pending,
                onClick = {
                    pending = true
                    error = null
                    scope.launch {
                        try {
                            onConfirm()
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Request failed"
                        } finally {
                            pending = false
                        }
                    }
                },
            ) { Text(if (pending) "Working…" else confirm, color = if (danger && !pending) Graphite.needsYou else MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(enabled = !pending, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, label: String, placeholder: String? = null, mono: Boolean = false, last: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono) else MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(
            capitalization = if (mono) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            autoCorrectEnabled = !mono,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun RenameDialog(title: String, initial: String, allowEmpty: Boolean = false, onRename: suspend (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    ActionDialog(title, "Rename", enabled = allowEmpty || name.isNotBlank(), onConfirm = { onRename(name.trim()) }, onDismiss = onDismiss) {
        Field(name, { name = it }, "Name", last = true)
        if (allowEmpty) Text("Leave empty to clear the name.", style = MaterialTheme.typography.bodySmall, color = Graphite.muted)
    }
}

/** New workspace (with a directory) or new tab; both can start a command right away. */
@Composable
fun CreateDialog(
    title: String,
    withCwd: Boolean,
    home: String?,
    onCreate: suspend (label: String?, cwd: String?, command: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    ActionDialog(
        title, "Create",
        onConfirm = { onCreate(label.trim().ifEmpty { null }, if (withCwd) expandHome(cwd.trim(), home) else null, command.trim().ifEmpty { null }) },
        onDismiss = onDismiss,
    ) {
        Field(label, { label = it }, "Name", placeholder = "Optional")
        if (withCwd) Field(cwd, { cwd = it }, "Directory", placeholder = "~", mono = true)
        Field(command, { command = it }, "Run command", placeholder = "Optional, e.g. npm run dev", mono = true, last = true)
        Text("Created in the background; the PC keeps its current focus.", style = MaterialTheme.typography.bodySmall, color = Graphite.muted)
    }
}

/** `~/x` → `<home>/x`. Blank means herdr's default. */
fun expandHome(path: String, home: String?): String? = when {
    path.isEmpty() -> null
    home != null && path == "~" -> home
    home != null && path.startsWith("~/") -> home + path.removePrefix("~")
    else -> path
}

@Composable
fun ConfirmCloseDialog(title: String, lines: List<String>, warning: String?, onClose: suspend () -> Unit, onDismiss: () -> Unit) {
    ActionDialog(title, "Close", danger = true, onConfirm = onClose, onDismiss = onDismiss) {
        if (lines.isNotEmpty()) {
            Text("This ends:", style = MaterialTheme.typography.bodyMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.take(8).forEach { Text("• $it", fontFamily = Mono, fontSize = 12.sp, color = Graphite.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (lines.size > 8) Text("and ${lines.size - 8} more", style = MaterialTheme.typography.bodySmall, color = Graphite.muted)
            }
        }
        warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Graphite.needsYou) }
    }
}

/** Closing anything that holds agents stops them, which deserves its own sentence. */
fun agentWarning(panes: List<PaneNode>): String? {
    val agents = panes.count { it.agent != null }
    return if (agents == 0) null else "${plural(agents, "agent")} will be stopped. This cannot be undone."
}
