package dev.cambraia.openherdr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.AgentStatus
import dev.cambraia.openherdr.protocol.PaneRead
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keys for answering agent prompts without the on-screen keyboard. */
private val QuickKeys = listOf(
    "1" to listOf("1"), "2" to listOf("2"), "3" to listOf("3"),
    "↑" to listOf("up"), "↓" to listOf("down"), "Tab" to listOf("tab"),
    "⇧Tab" to listOf("shift+tab"), "Esc" to listOf("esc"),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentScreen(
    paneId: String,
    agent: Agent?,
    revision: Long,
    read: suspend (String) -> PaneRead,
    submit: suspend (String, String) -> Unit,
    sendKeys: suspend (String, List<String>) -> Unit,
    onBack: () -> Unit,
) {
    var output by remember { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf("") }
    var confirmInterrupt by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    suspend fun reload() {
        runCatching { read(paneId) }
            .onSuccess { output = it.text.trimEnd() }
            .onFailure { snackbar.showSnackbar(it.message ?: "Could not read output") }
    }

    fun act(block: suspend () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            runCatching { block() }.onFailure { snackbar.showSnackbar(it.message ?: "Could not send") }
            delay(250)
            reload()
        }
    }

    fun send() {
        val text = draft
        draft = ""
        act { submit(paneId, text) }
    }

    // Poll while visible; status events also trigger a reload through [revision].
    LaunchedEffect(paneId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                reload()
                delay(1_500)
            }
        }
    }
    LaunchedEffect(revision) { reload() }
    LaunchedEffect(output) {
        if (scroll.maxValue - scroll.value < 400) scroll.animateScrollTo(scroll.maxValue)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        agent?.let { AgentAvatar(it, size = 36.dp) }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(agent?.label ?: paneId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            agent?.subtitle?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { agent?.let { StatusPill(it.status, Modifier.padding(end = 12.dp)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Surface(
                color = Terminal.background,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Box(Modifier.verticalScroll(scroll).padding(16.dp)) {
                    TerminalText(output.ifEmpty { "…" })
                }
            }

            AnimatedVisibility(
                visible = agent?.status == AgentStatus.Blocked,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Waiting for your answer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { act { sendKeys(paneId, listOf("y")) } }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("y") }
                            FilledTonalButton(onClick = { act { sendKeys(paneId, listOf("n")) } }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("n") }
                            OutlinedButton(onClick = { act { sendKeys(paneId, listOf("enter")) } }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("Enter") }
                        }
                    }
                }
            }

            Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (agent?.status != AgentStatus.Blocked) {
                        KeyButton("Enter") { act { sendKeys(paneId, listOf("enter")) } }
                        KeyButton("y") { act { sendKeys(paneId, listOf("y")) } }
                        KeyButton("n") { act { sendKeys(paneId, listOf("n")) } }
                    }
                    QuickKeys.forEach { (label, keys) -> KeyButton(label) { act { sendKeys(paneId, keys) } } }
                    KeyButton("Ctrl+C", danger = true) { confirmInterrupt = true }
                }
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Message the agent") },
                        maxLines = 5,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.weight(1f),
                    )
                    FilledIconButton(
                        onClick = ::send,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.padding(start = 8.dp).size(56.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
                }
            }
        }
    }

    if (confirmInterrupt) {
        AlertDialog(
            onDismissRequest = { confirmInterrupt = false },
            title = { Text("Send Ctrl+C?") },
            text = { Text("This interrupts what the agent is doing and may end its session.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmInterrupt = false
                    act { sendKeys(paneId, listOf("ctrl+c")) }
                }) { Text("Interrupt") }
            },
            dismissButton = { TextButton(onClick = { confirmInterrupt = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun KeyButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        colors = if (danger) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
        contentPadding = ButtonDefaults.SmallContentPadding,
    ) { Text(label) }
}
