package dev.cambraia.openherdr.data

import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.HerdrClient
import dev.cambraia.openherdr.protocol.HerdrException
import dev.cambraia.openherdr.protocol.PaneRead
import dev.cambraia.openherdr.protocol.StreamOpener
import dev.cambraia.openherdr.protocol.Workspace
import dev.cambraia.openherdr.ssh.PinStore
import dev.cambraia.openherdr.ssh.SshFailure
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException

/** An authenticated transport to one herdr server. */
interface HerdrLink : StreamOpener, Closeable {
    val isConnected: Boolean
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
        _connection.value = Connection.Idle
        _snapshot.value = Snapshot()
    }

    suspend fun read(paneId: String, lines: Int = 300): PaneRead = requireClient().read(paneId, lines)

    suspend fun submit(paneId: String, text: String) = requireClient().submit(paneId, text)

    suspend fun sendKeys(paneId: String, keys: List<String>) = requireClient().sendKeys(paneId, keys)

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
        client = herdr
        _connection.value = Connection.Connected(profile)

        val refresh = Channel<Unit>(Channel.CONFLATED)
        val subscribedPanes = MutableStateFlow<Set<String>?>(null)
        refresh.trySend(Unit)

        launch {
            var previous: List<Agent>? = null
            for (signal in refresh) {
                val agents = herdr.agents()
                val workspaces = herdr.workspaces()
                AgentAlerts.transitions(previous, agents).takeIf { it.isNotEmpty() }?.let { onAlerts(profile, it) }
                previous = agents
                _snapshot.value = Snapshot(agents, workspaces, loaded = true, revision = _snapshot.value.revision + 1)
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
