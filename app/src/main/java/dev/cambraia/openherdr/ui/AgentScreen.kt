package dev.cambraia.openherdr.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.cambraia.openherdr.data.PaneNode
import dev.cambraia.openherdr.data.Topology
import dev.cambraia.openherdr.protocol.AgentStatus
import dev.cambraia.openherdr.protocol.PaneRead
import dev.cambraia.openherdr.protocol.Ansi
import dev.cambraia.openherdr.transcript.ChatFeed
import dev.cambraia.openherdr.transcript.QuestionKeys
import dev.cambraia.openherdr.transcript.ChatItem
import dev.cambraia.openherdr.transcript.RemoteFiles
import dev.cambraia.openherdr.transcript.TranscriptLocator
import dev.cambraia.openherdr.voice.Dictation
import dev.cambraia.openherdr.voice.DictationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keys for answering agent prompts without the on-screen keyboard. */
private val QuickKeys = listOf(
    "Enter" to listOf("enter"), "y" to listOf("y"), "n" to listOf("n"),
    "1" to listOf("1"), "2" to listOf("2"), "3" to listOf("3"),
    "↑" to listOf("up"), "↓" to listOf("down"), "Tab" to listOf("tab"),
    "⇧Tab" to listOf("shift+tab"), "Space" to listOf("space"), "Esc" to listOf("esc"),
)

private sealed interface ChatState {
    data object Loading : ChatState
    data class Unavailable(val reason: String) : ChatState
    data class Ready(val items: List<ChatItem>) : ChatState
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentScreen(
    paneId: String,
    node: PaneNode?,
    home: String?,
    revision: Long,
    files: () -> RemoteFiles?,
    dictation: Dictation,
    read: suspend (String) -> PaneRead,
    submit: suspend (String, String) -> Unit,
    sendKeys: suspend (String, List<String>) -> Unit,
    onRename: suspend (String?) -> Unit,
    onClose: suspend () -> Unit,
    onBack: () -> Unit,
) {
    val agent = node?.agent
    val chatCapable = agent?.let(TranscriptLocator::format) != null
    var mode by rememberSaveable(paneId) { mutableIntStateOf(if (chatCapable) 0 else 1) }
    val showChat = mode == 0 && chatCapable
    val blocked = agent?.status == AgentStatus.Blocked

    var output by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf<ChatState>(ChatState.Loading) }
    var draft by rememberSaveable { mutableStateOf("") }
    var keysOpen by rememberSaveable { mutableStateOf(false) }
    var confirmInterrupt by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val voice by dictation.state.collectAsStateWithLifecycle()

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

    // A choice answered from a card: never sent twice, and only when the screen shows that question.
    var answered by remember { mutableStateOf<Set<String>>(emptySet()) }
    fun answer(question: ChatItem.Question, index: Int) {
        if (question.key in answered) return
        answered = answered + question.key
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            val screen = runCatching { read(paneId).text }.getOrNull()?.let { Ansi.parse(it).text }
            val keys = screen?.let { QuestionKeys.choose(question.questions[0], index, it) }
            if (keys == null) {
                answered = answered - question.key
                mode = 1
                snackbar.showSnackbar("The question is not on screen as expected. Answer it here in the terminal.")
                return@launch
            }
            runCatching { sendKeys(paneId, keys) }.onFailure {
                answered = answered - question.key
                snackbar.showSnackbar(it.message ?: "Could not send")
            }
            delay(250)
            reload()
            // The transcript normally marks it answered well before this; if not, let the user retry.
            delay(8_000)
            answered = answered - question.key
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        draft = ""
        act { submit(paneId, text) }
    }

    fun toggleVoice() {
        when (voice) {
            is DictationState.Recording -> scope.launch {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                runCatching { dictation.stop() }
                    .onSuccess { heard -> if (heard.isNotEmpty()) draft = listOf(draft.trimEnd(), heard).filter { it.isNotEmpty() }.joinToString(" ") }
                    .onFailure { snackbar.showSnackbar("Transcription failed: ${it.message}") }
            }
            DictationState.Transcribing -> Unit
            DictationState.Unavailable -> scope.launch { snackbar.showSnackbar("Speech model not installed. See Settings → Voice.") }
            DictationState.Idle -> {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                dictation.start()
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggleVoice() else scope.launch { snackbar.showSnackbar("Microphone permission is needed for dictation") }
    }

    fun onMic() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted || voice !is DictationState.Idle) toggleVoice() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(Unit) { dictation.refresh() }

    // Closed here or on the PC: once the pane has been seen and is gone, leave.
    LaunchedEffect(node == null) {
        if (node != null) seen = true else if (seen) onBack()
    }

    // Terminal output: polled while the terminal is shown, or while a prompt waits for an answer.
    LaunchedEffect(paneId, showChat, blocked) {
        if (showChat && !blocked) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                reload()
                delay(1_500)
            }
        }
    }
    LaunchedEffect(revision) { if (!showChat || blocked) reload() }

    // Conversation: located from the session herdr reports, then followed as the file grows.
    val session = agent?.session
    LaunchedEffect(paneId, session, chatCapable) {
        if (!chatCapable) return@LaunchedEffect
        if (session == null) {
            chat = ChatState.Unavailable("herdr has not reported this agent's session yet. Install the herdr integration for it, e.g. herdr integration install claude, then restart the agent.")
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val remote = files() ?: return@repeatOnLifecycle
            val source = withContext(Dispatchers.IO) { runCatching { TranscriptLocator.locate(agent, remote) }.getOrNull() }
            if (source == null) {
                chat = ChatState.Unavailable("Could not find this conversation's session file on the server.")
                return@repeatOnLifecycle
            }
            val feed = ChatFeed(remote, source)
            while (true) {
                withContext(Dispatchers.IO) { runCatching { feed.poll() } }
                    .onSuccess { items -> items?.let { chat = ChatState.Ready(it) } }
                delay(1_200)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Graphite.page)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            PaneHeader(
                node, paneId, home, chatCapable, mode, onMode = { mode = it }, onBack = onBack,
                actions = listOf(
                    MenuAction("Rename pane") { renaming = true },
                    MenuAction("Close pane…", danger = true) { closing = true },
                ),
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = showChat,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "mode",
                ) { chatMode ->
                    if (chatMode) {
                        ChatView(chat, answered, onAnswer = ::answer, onOpenTerminal = { mode = 1 })
                    } else {
                        TerminalView(output)
                    }
                }
            }

            AnimatedVisibility(
                visible = blocked,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                PromptBanner(
                    excerpt = if (showChat) output.lines().filter { it.isNotBlank() }.takeLast(10).joinToString("\n") else null,
                    onKeys = { keys -> act { sendKeys(paneId, keys) } },
                )
            }

            Composer(
                draft = draft,
                onDraft = { draft = it },
                voice = voice,
                placeholder = if (agent != null) "Message the agent" else "Run a command",
                keysOpen = keysOpen || !showChat,
                canToggleKeys = showChat,
                onToggleKeys = { keysOpen = !keysOpen },
                onKeys = { keys -> act { sendKeys(paneId, keys) } },
                onInterrupt = { confirmInterrupt = true },
                onMic = ::onMic,
                onSend = ::send,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
    }

    if (confirmInterrupt) {
        AlertDialog(
            onDismissRequest = { confirmInterrupt = false },
            title = { Text("Send Ctrl+C?") },
            text = {
                Text(
                    if (agent != null) "This interrupts what the agent is doing and may end its session."
                    else "This interrupts the command running in this pane.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmInterrupt = false
                    act { sendKeys(paneId, listOf("ctrl+c")) }
                }) { Text("Interrupt") }
            },
            dismissButton = { TextButton(onClick = { confirmInterrupt = false }) { Text("Cancel") } },
        )
    }
    if (renaming && node != null) {
        RenameDialog("Rename pane", node.pane.label.orEmpty(), allowEmpty = true, onRename = { onRename(it.ifEmpty { null }) }, onDismiss = { renaming = false })
    }
    if (closing && node != null) {
        ConfirmCloseDialog(
            "Close pane ${node.title}?", describePanes(listOf(node), home), agentWarning(listOf(node)),
            onClose = onClose, onDismiss = { closing = false },
        )
    }
}

@Composable
private fun PaneHeader(
    node: PaneNode?,
    paneId: String,
    home: String?,
    chatCapable: Boolean,
    mode: Int,
    onMode: (Int) -> Unit,
    onBack: () -> Unit,
    actions: List<MenuAction>,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 4.dp, bottom = 8.dp)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            node?.let { PaneMark(it, size = 40.dp) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(node?.title ?: paneId, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                node?.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Graphite.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                node?.cwd?.let { CwdLine(Topology.tilde(it, home)) }
            }
            node?.agent?.let { StatusPill(it.status) }
            if (node != null) Overflow("Pane actions", actions)
        }
        if (chatCapable) {
            Segmented(
                options = listOf("Chat", "Terminal"),
                selected = mode,
                onSelect = onMode,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatView(
    state: ChatState,
    answered: Set<String>,
    onAnswer: (ChatItem.Question, Int) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    when (state) {
        ChatState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        is ChatState.Unavailable -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(MaterialShapes.Pill.toShape(), "Chat unavailable", state.reason) {
                Button(onClick = onOpenTerminal) { Text("Open terminal") }
            }
        }
        is ChatState.Ready -> {
            val items = state.items
            val list = rememberLazyListState()
            var settled by remember { mutableStateOf(false) }
            LaunchedEffect(items.size) {
                if (items.isEmpty()) return@LaunchedEffect
                val atBottom = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } ?: true
                if (!settled) {
                    list.scrollToItem(items.lastIndex)
                    settled = true
                } else if (atBottom) {
                    list.animateScrollToItem(items.lastIndex)
                }
            }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(MaterialShapes.Puffy.toShape(), "No messages yet", "Say something to the agent below.")
                }
            } else {
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.key }, contentType = { it::class }) { item ->
                        when (item) {
                            is ChatItem.User -> UserBubble(item.text)
                            is ChatItem.Assistant -> AssistantText(item.text)
                            is ChatItem.Thought -> ThoughtRow(item.text)
                            is ChatItem.Tool -> ToolCard(item)
                            is ChatItem.Question -> QuestionCard(
                                item,
                                sending = item.key in answered,
                                onChoose = { onAnswer(item, it) },
                                onOpenTerminal = onOpenTerminal,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = Graphite.highest,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp))
            }
        }
    }
}

@Composable
private fun AssistantText(text: String) {
    val type = MaterialTheme.typography
    SelectionContainer {
        Markdown(
            content = text,
            colors = markdownColor(text = Graphite.text, codeBackground = Graphite.raised, inlineCodeBackground = Graphite.high),
            typography = markdownTypography(
                text = type.bodyLarge,
                paragraph = type.bodyLarge,
                h1 = type.titleLarge, h2 = type.titleLarge, h3 = type.titleMedium,
                h4 = type.titleSmall, h5 = type.titleSmall, h6 = type.titleSmall,
                code = type.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp),
                inlineCode = type.bodyLarge.copy(fontFamily = Mono, fontSize = 14.sp),
                bullet = type.bodyLarge, list = type.bodyLarge, ordered = type.bodyLarge,
                quote = type.bodyLarge.copy(color = Graphite.muted),
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ThoughtRow(text: String) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(horizontal = 4.dp, vertical = 4.dp).animateContentSize(),
    ) {
        Box(Modifier.padding(top = 3.dp).width(2.dp).heightIn(min = 14.dp).background(Graphite.outline, CircleShape))
        Column(Modifier.padding(start = 10.dp)) {
            Text("Thinking", style = MaterialTheme.typography.labelMedium, color = Graphite.faint)
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = Graphite.muted,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolCard(tool: ChatItem.Tool) {
    var open by remember { mutableStateOf(false) }
    val state = when {
        !tool.finished -> "running"
        tool.failed -> "failed"
        else -> "done"
    }
    Panel(
        Modifier.fillMaxWidth().animateContentSize().semantics { contentDescription = "${tool.name} ${tool.detail}, $state" },
        onClick = { if (tool.finished) open = !open },
        shape = MaterialTheme.shapes.medium,
        color = Graphite.surface,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                when {
                    !tool.finished -> LoadingIndicator(Modifier.size(20.dp))
                    tool.failed -> Dot(Graphite.needsYou, 5.dp)
                    else -> Dot(Graphite.done, 5.dp)
                }
            }
            Text(tool.name, style = MaterialTheme.typography.labelLarge, color = Graphite.text, modifier = Modifier.padding(start = 10.dp))
            Text(
                tool.detail,
                fontFamily = Mono, fontSize = 12.sp, color = Graphite.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
        }
        if (open && tool.output != null) {
            Box(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .background(Terminal.background, MaterialTheme.shapes.small)
                    .heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                SelectionContainer {
                    Text(tool.output.ifEmpty { "(no output)" }, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, color = Terminal.foreground)
                }
            }
        }
    }
}

/**
 * An agent's question with its options. A single-choice question is answered by tapping an
 * option; multi-select, free text and Claude's multi-question forms go to the terminal.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuestionCard(item: ChatItem.Question, sending: Boolean, onChoose: (Int) -> Unit, onOpenTerminal: () -> Unit) {
    val pending = !item.finished
    Panel(
        Modifier.fillMaxWidth().animateContentSize().semantics { if (pending) liveRegion = LiveRegionMode.Polite },
        color = if (pending) Graphite.raised else Graphite.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (pending) Graphite.needsYou else if (item.failed) Graphite.faint else Graphite.done, 5.dp)
                Text(
                    when {
                        pending -> "Question for you"
                        item.failed -> "Not answered"
                        else -> "Answered"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Graphite.muted,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            item.questions.forEach { ask ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ask.header?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = Graphite.faint) }
                    Text(ask.question, style = MaterialTheme.typography.titleMedium)
                    ask.details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Graphite.muted) }
                    if (pending && ask.multiSelect) {
                        Text("Pick several", style = MaterialTheme.typography.labelSmall, color = Graphite.faint)
                    }
                    if (pending) {
                        ask.options.forEachIndexed { index, option ->
                            OptionRow(
                                number = index + 1,
                                option = option,
                                enabled = item.simple && !sending,
                                onClick = { onChoose(index) },
                            )
                        }
                    }
                }
            }
            if (pending) {
                if (sending) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(20.dp))
                        Text("Sending your choice…", style = MaterialTheme.typography.bodySmall, color = Graphite.muted, modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    if (!item.simple) {
                        Text(
                            "Answer this one in the terminal: use ↑ ↓, Space to mark and Enter to confirm.",
                            style = MaterialTheme.typography.bodySmall, color = Graphite.muted,
                        )
                    }
                    OutlinedButton(onClick = onOpenTerminal, shapes = ButtonDefaults.shapes()) {
                        Text(if (item.simple) "Other answer in terminal" else "Answer in terminal")
                    }
                }
            } else if (!item.answer.isNullOrBlank()) {
                Text(item.answer, style = MaterialTheme.typography.bodySmall, color = Graphite.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun OptionRow(number: Int, option: ChatItem.Option, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Graphite.high,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$number", fontFamily = Mono, fontSize = 13.sp, color = Graphite.faint, modifier = Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Graphite.text else Graphite.muted)
                option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Graphite.muted) }
            }
        }
    }
}

@Composable
private fun TerminalView(output: String) {
    val scroll = rememberScrollState()
    LaunchedEffect(output) {
        if (scroll.maxValue - scroll.value < 400) scroll.animateScrollTo(scroll.maxValue)
    }
    Surface(
        color = Terminal.background,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
    ) {
        Box(Modifier.verticalScroll(scroll).padding(16.dp)) { TerminalText(output.ifEmpty { "…" }) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PromptBanner(excerpt: String?, onKeys: (List<String>) -> Unit) {
    Panel(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(Graphite.needsYou, 5.dp)
                Text("Waiting for your answer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp))
            }
            if (!excerpt.isNullOrBlank()) {
                Box(
                    Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(max = 180.dp)
                        .background(Terminal.background, MaterialTheme.shapes.small)
                        .verticalScroll(rememberScrollState(), reverseScrolling = true).padding(10.dp),
                ) { TerminalText(excerpt) }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onKeys(listOf("y")) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("y") }
                FilledTonalButton(onClick = { onKeys(listOf("n")) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("n") }
                OutlinedButton(onClick = { onKeys(listOf("enter")) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) { Text("Enter") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    placeholder: String,
    voice: DictationState,
    keysOpen: Boolean,
    canToggleKeys: Boolean,
    onToggleKeys: () -> Unit,
    onKeys: (List<String>) -> Unit,
    onInterrupt: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
        AnimatedVisibility(keysOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QuickKeys.forEach { (label, keys) -> KeyButton(label) { onKeys(keys) } }
                KeyButton("Ctrl+C", danger = true, onClick = onInterrupt)
            }
        }
        Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canToggleKeys) {
                IconButton(onClick = onToggleKeys) {
                    Icon(Glyphs.Keyboard, contentDescription = if (keysOpen) "Hide keys" else "Show keys", tint = if (keysOpen) Graphite.text else Graphite.muted)
                }
            } else {
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.weight(1f)) {
                when (voice) {
                    is DictationState.Recording -> VoicePill("Listening… tap stop when done", voice.level)
                    DictationState.Transcribing -> VoicePill("Transcribing on device…", null)
                    else -> TextField(
                        value = draft,
                        onValueChange = onDraft,
                        placeholder = { Text(placeholder) },
                        maxLines = 6,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Graphite.raised,
                            unfocusedContainerColor = Graphite.raised,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.Sentences),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val recording = voice is DictationState.Recording
            FilledTonalIconButton(
                onClick = onMic,
                enabled = voice != DictationState.Transcribing,
                shapes = IconButtonDefaults.shapes(),
                colors = if (recording) {
                    IconButtonDefaults.filledTonalIconButtonColors(containerColor = Graphite.needsYou, contentColor = Color(0xFF2A0F0A))
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                },
                modifier = Modifier.padding(start = 8.dp).size(52.dp),
            ) { Icon(if (recording) Glyphs.Stop else Glyphs.Mic, contentDescription = if (recording) "Stop dictation" else "Dictate") }
            AnimatedVisibility(draft.isNotBlank() && voice !is DictationState.Recording) {
                FilledIconButton(
                    onClick = onSend,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.padding(start = 8.dp).size(52.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}

/** Replaces the text field while dictating: a live level meter, or a spinner while transcribing. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoicePill(label: String, level: Float?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Graphite.raised, RoundedCornerShape(28.dp)).padding(horizontal = 18.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (level == null) {
            LoadingIndicator(Modifier.size(24.dp))
        } else {
            val bars = remember { FloatArray(5) }
            for (i in 0 until bars.size - 1) bars[i] = bars[i + 1]
            bars[bars.lastIndex] = (level * 9f).coerceIn(0.08f, 1f)
            Row(Modifier.size(width = 28.dp, height = 24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                bars.forEach { b ->
                    Box(Modifier.width(3.dp).fillMaxHeight(b).background(Graphite.needsYou, CircleShape))
                }
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Graphite.muted, modifier = Modifier.padding(start = 12.dp))
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
            ButtonDefaults.filledTonalButtonColors(containerColor = Graphite.high, contentColor = Graphite.text)
        },
        contentPadding = ButtonDefaults.SmallContentPadding,
    ) { Text(label) }
}
