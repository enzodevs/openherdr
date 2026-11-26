package dev.cambraia.openherdr.data

import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.HerdrClient
import dev.cambraia.openherdr.protocol.HerdrException
import dev.cambraia.openherdr.protocol.Pane
import dev.cambraia.openherdr.protocol.PaneRead
import dev.cambraia.openherdr.protocol.TabInfo
import dev.cambraia.openherdr.protocol.StreamOpener
import dev.cambraia.openherdr.protocol.Workspace
import dev.cambraia.openherdr.ssh.PinStore
import dev.cambraia.openherdr.ssh.SshFailure
import dev.cambraia.openherdr.transcript.RemoteFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/** An authenticated transport to one herdr server. */
interface HerdrLink : StreamOpener, Closeable {
    val isConnected: Boolean

    /** Read-only file access on the host, used to show agent conversations; null when unsupported. */
    val files: RemoteFiles? get() = null
}

/** Opens a [HerdrLink] for a profile. Blocking; throws [SshFailure]. */
fun interface Connector {
    fun connect(profile: ServerProfile): HerdrLink
}

sealed interface Connection {
    data object Idle : Connection
    data class Connecting(val profile: ServerProfile, val attempt: Int) : Connection
    data class Connected(val profile: ServerProfile) : Connection
    data class NeedsTrust(val profile: ServerProfile, val failure: SshFailure.UnknownHostKey) : Connection
    data class Refused(val profile: ServerProfile, val failure: SshFailure.HostKeyChanged) : Connection
    data class Failed(val profile: ServerProfile, val message: String, val retrying: Boolean) : Connection

    val activeProfile: ServerProfile?
        get() = when (this) {
            Idle -> null
            is Connecting -> profile
            is Connected -> profile
            is NeedsTrust -> profile
            is Refused -> profile
            is Failed -> profile
        }
}

data class Snapshot(
    val agents: List<Agent> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val tabs: List<TabInfo> = emptyList(),
    val panes: List<Pane> = emptyList(),
    /** Foreground command of each plain pane; absent or null when it sits at the shell prompt. */
    val commands: Map<String, String?> = emptyMap(),
    /** The server user's home directory, to show paths as `~/…`; null until known. */
    val home: String? = null,
    val loaded: Boolean = false,
    val revision: Long = 0,
)

/**
 * Owns the connection to one herdr server: connects, keeps a live event subscription,
 * mirrors agents/workspaces into [snapshot], and reconnects with backoff when the link drops.
 * Events are treated as invalidation signals followed by authoritative reads, as the herdr
 * socket API recommends; a slow poll heals anything a dropped subscription missed.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val connector: Connector,
    private val pins: PinStore,
    private val onAlerts: (ServerProfile, List<AgentAlert>) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val pollMillis: Long = 30_000,
    private val livenessMillis: Long = 5_000,
) {
    private val _connection = MutableStateFlow<Connection>(Connection.Idle)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    @Volatile
    private var client: HerdrClient? = null

    @Volatile
    private var link: HerdrLink? = null

    @Volatile
    private var refresh: Channel<Unit>? = null
    private var job: Job? = null

    fun connect(profile: ServerProfile) {
        job?.cancel()
        _snapshot.value = Snapshot()
        job = scope.launch { run(profile) }
    }

    /** Pins the key the user just confirmed and connects again. */
    fun trust(state: Connection.NeedsTrust) {
        pins.put(state.failure.hostId, state.failure.pin)
        connect(state.profile)
    }

    fun disconnect() {
        job?.cancel()
        job = null
        client = null
        refresh = null
        link = null
        _connection.value = Connection.Idle
        _snapshot.value = Snapshot()
    }

    suspend fun read(paneId: String, lines: Int = 300): PaneRead = requireClient().read(paneId, lines, ansi = true)

    suspend fun submit(paneId: String, text: String) = requireClient().submit(paneId, text)

    suspend fun sendKeys(paneId: String, keys: List<String>) = requireClient().sendKeys(paneId, keys)

    /** Runs a change on the server, then reloads the snapshot right away instead of waiting for events. */
    suspend fun <T> change(block: suspend HerdrClient.() -> T): T =
        requireClient().block().also { refresh?.trySend(Unit) }

    /** Creates a background workspace, runs [command] in it if given; returns its first pane once it is listed. */
    suspend fun createWorkspace(label: String?, cwd: String?, command: String?): String =
        created(change { createWorkspace(label, cwd) }, command)

    suspend fun createTab(workspaceId: String, label: String?, command: String?): String =
        created(change { createTab(workspaceId, label) }, command)

    /** Adds a pane next to [targetPaneId] in its tab, named [label] if given. */
    suspend fun createPane(targetPaneId: String, direction: String, cwd: String?, label: String?, command: String?): String {
        val pane = change { splitPane(targetPaneId, direction, cwd).also { id -> if (label != null) renamePane(id, label) } }
        return created(pane, command)
    }

    private suspend fun created(paneId: String, command: String?): String {
        if (!command.isNullOrBlank()) requireClient().submit(paneId, command)
        withTimeoutOrNull(5_000) { snapshot.first { s -> s.panes.any { it.id == paneId } } }
        return paneId
    }

    /** Files on the connected host, or null while disconnected. */
    val files: RemoteFiles?
        get() = link?.takeIf { client != null }?.files

    private fun requireClient(): HerdrClient = client ?: throw IOException("Not connected")

    private suspend fun run(profile: ServerProfile) {
        var attempt = 0
        while (true) {
            _connection.value = Connection.Connecting(profile, attempt)
            val link = try {
                withContext(io) { connector.connect(profile) }
            } catch (e: SshFailure.UnknownHostKey) {
                _connection.value = Connection.NeedsTrust(profile, e)
                return
            } catch (e: SshFailure.HostKeyChanged) {
                _connection.value = Connection.Refused(profile, e)
                return
            } catch (e: SshFailure.AuthRejected) {
                _connection.value = Connection.Failed(profile, e.message ?: "Authentication failed", retrying = false)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                _connection.value = Connection.Failed(profile, e.message ?: "Connection failed", retrying = true)
                delay(backoff(attempt))
                continue
            }

            var lost: Throwable? = null
            try {
                attempt = 0
                session(profile, link)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lost = e
            } finally {
                client = null
                refresh = null
                this.link = null
                withContext(NonCancellable + io) { runCatching { link.close() } }
            }
            attempt++
            _connection.value = Connection.Failed(profile, lost.describe("Connection lost"), retrying = true)
            delay(backoff(attempt))
        }
    }

    private suspend fun session(profile: ServerProfile, link: HerdrLink) = coroutineScope {
        val herdr = HerdrClient(link, io)
        herdr.ping()
        this@ConnectionManager.link = link
        client = herdr
        _connection.value = Connection.Connected(profile)

        val refresh = Channel<Unit>(Channel.CONFLATED)
        this@ConnectionManager.refresh = refresh
        val subscribedPanes = MutableStateFlow<Set<String>?>(null)
        refresh.trySend(Unit)
        val home = withContext(io) { runCatching { link.files?.home }.getOrNull() }

        launch {
            var previous: List<Agent>? = null
            for (signal in refresh) {
                val agents = herdr.agents()
                val workspaces = herdr.workspaces()
                val tabs = herdr.tabs()
                val panes = herdr.panes()
                // One read per plain pane; a pane that closed meanwhile just has no command.
                val commands = panes.filter { it.agent == null }.associate { pane ->
                    pane.id to runCatching { herdr.processInfo(pane.id).command }.getOrNull()
                }
                AgentAlerts.transitions(previous, agents).takeIf { it.isNotEmpty() }?.let { onAlerts(profile, it) }
                previous = agents
                _snapshot.value = Snapshot(agents, workspaces, tabs, panes, commands, home, loaded = true, revision = _snapshot.value.revision + 1)
                subscribedPanes.value = agents.mapTo(sortedSetOf()) { it.paneId }
            }
        }

        launch {
            // collectLatest reopens the subscription whenever the set of agent panes changes.
            subscribedPanes.collectLatest { panes ->
                if (panes == null) return@collectLatest
                while (true) {
                    try {
                        herdr.subscribe(panes).collect { refresh.trySend(Unit) }
                    } catch (e: HerdrException) {
                        // events_lost and similar: resubscribe and reconcile with a fresh read.
                    }
                    refresh.trySend(Unit)
                    delay(1_000)
                }
            }
        }

        var sincePoll = 0L
        while (true) {
            delay(livenessMillis)
            if (!link.isConnected) throw IOException("SSH session closed")
            sincePoll += livenessMillis
            if (sincePoll >= pollMillis) {
                sincePoll = 0
                refresh.trySend(Unit)
            }
        }
    }

    private fun backoff(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000)

    private fun Throwable?.describe(fallback: String): String = this?.message?.takeIf { it.isNotBlank() } ?: fallback
}
