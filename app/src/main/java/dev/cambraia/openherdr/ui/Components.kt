package dev.cambraia.openherdr.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.AgentStatus
import dev.cambraia.openherdr.protocol.Ansi
import dev.cambraia.openherdr.protocol.AnsiText
import dev.cambraia.openherdr.ssh.HostKeyPin

data class StatusStyle(val label: String, val container: Color, val content: Color)

@Composable
fun AgentStatus.style(): StatusStyle = when (this) {
    AgentStatus.Blocked -> StatusStyle("Needs you", Graphite.needsYou, Color(0xFF2A0F0A))
    AgentStatus.Working -> StatusStyle("Working", Graphite.working.copy(alpha = 0.16f), Graphite.working)
    AgentStatus.Done -> StatusStyle("Done", Graphite.done.copy(alpha = 0.16f), Graphite.done)
    AgentStatus.Idle -> StatusStyle("Idle", Graphite.high, Graphite.muted)
    AgentStatus.Unknown -> StatusStyle("Unknown", Graphite.high, Graphite.faint)
}

/** The status hue alone, for dots and accents. */
fun AgentStatus.hue(): Color = when (this) {
    AgentStatus.Blocked -> Graphite.needsYou
    AgentStatus.Working -> Graphite.working
    AgentStatus.Done -> Graphite.done
    AgentStatus.Idle -> Graphite.idle
    AgentStatus.Unknown -> Graphite.faint
}

/** Short mark for well-known agents; the first letter otherwise. */
fun Agent.mark(): String {
    val name = (agent ?: displayAgent ?: label).lowercase()
    return when {
        "claude" in name -> "Cl"
        "codex" in name -> "Cx"
        name == "pi" || name.startsWith("pi ") -> "π"
        "antigravity" in name || "gemini" in name -> "Ag"
        "opencode" in name -> "Oc"
        else -> label.take(1).uppercase()
    }
}

/** Each status gets its own Material shape; a working agent's shape slowly turns. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentAvatar(agent: Agent, size: Dp = 48.dp) {
    val style = agent.status.style()
    val shape: Shape = when (agent.status) {
        AgentStatus.Blocked -> MaterialShapes.SoftBurst
        AgentStatus.Working -> MaterialShapes.Cookie9Sided
        AgentStatus.Done -> MaterialShapes.Clover4Leaf
        AgentStatus.Idle -> MaterialShapes.Circle
        AgentStatus.Unknown -> MaterialShapes.Square
    }.toShape()
    val container by animateColorAsState(style.container, label = "avatar")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6_000, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .rotate(if (agent.status == AgentStatus.Working) angle else 0f)
                .background(container, shape),
        )
        Text(agent.mark(), color = style.content, fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = (size.value * 0.3f).sp)
    }
}

@Composable
fun StatusPill(status: AgentStatus, modifier: Modifier = Modifier) {
    val style = status.style()
    val container by animateColorAsState(style.container, label = "pill")
    Surface(color = container, contentColor = style.content, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Box(Modifier.size(6.dp).background(style.content, CircleShape))
            Text(style.label, style = MaterialTheme.typography.labelMedium)

        }
    }
}

/** Segmented list shape: big outer corners on the group, small ones between items. */
fun segmentShape(index: Int, count: Int): Shape {
    val outer = 24.dp
    val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

/** Terminal output with its ANSI colors, on a fixed dark terminal background. */
@Composable
fun TerminalText(raw: String, modifier: Modifier = Modifier) {
    val text = remember(raw) { Ansi.parse(raw).toAnnotated() }
    SelectionContainer(modifier) {
        Text(text, color = Terminal.foreground, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

private fun AnsiText.toAnnotated(): AnnotatedString = buildAnnotatedString {
    append(text)
    spans.forEach { span ->
        val s = span.style
        var fg = s.fg?.let(Terminal::color)
        var bg = s.bg?.let(Terminal::color)
        if (s.inverse) {
            val swap = fg ?: Terminal.foreground
            fg = bg ?: Terminal.background
            bg = swap
        }
        if (s.dim && fg == null) fg = Terminal.dim
        addStyle(
            SpanStyle(
                color = fg ?: Color.Unspecified,
                background = bg ?: Color.Unspecified,
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                textDecoration = if (s.underline) TextDecoration.Underline else null,
            ),
            span.start, span.end,
        )
    }
}

@Composable
fun Fingerprint(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
        SelectionContainer {
            Text(text, fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(14.dp))
        }
    }
}

@Composable
fun TrustHostDialog(host: String, pin: HostKeyPin, onTrust: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text("Trust this server?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("First connection to $host. Check that this fingerprint matches the server before trusting it:")
                Fingerprint("${pin.keyType}\n${pin.fingerprint}")
                Text(
                    "On the server run: ssh-keygen -lf /etc/ssh/ssh_host_${pin.keyType.removePrefix("ssh-").substringBefore("-sha2")}_key.pub",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text("Trust and connect") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun HostKeyChangedDialog(host: String, expected: HostKeyPin, actual: HostKeyPin, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Server identity changed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "$host presented a different host key than the one you trusted. Someone may be intercepting the connection. OpenHerdr will not connect.",
                    fontWeight = FontWeight.Medium,
                )
                Text("Trusted", style = MaterialTheme.typography.labelLarge)
                Fingerprint(expected.fingerprint)
                Text("Presented", style = MaterialTheme.typography.labelLarge)
                Fingerprint(actual.fingerprint)
                Text("If you reinstalled the server on purpose, forget the old key in the server settings.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
