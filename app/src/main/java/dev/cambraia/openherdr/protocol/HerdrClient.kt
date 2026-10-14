package dev.cambraia.openherdr.protocol

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/** A bidirectional byte stream to the herdr socket (an SSH streamlocal channel in production). */
interface SocketStream : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** Opens a fresh connection to the herdr socket. Blocking; called on [Dispatchers.IO]. */
fun interface StreamOpener {
    fun open(): SocketStream
}

class HerdrClient(
    private val opener: StreamOpener,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val ids = AtomicLong()

    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        withContext(io) {
            opener.open().use { stream ->
                stream.output.write(HerdrProtocol.encodeRequest(nextId(), method, params).toByteArray())
                stream.output.flush()
                val line = stream.input.bufferedReader().readLine()
                    ?: throw HerdrException("connection_closed", "herdr closed the connection without a response")
                HerdrProtocol.decodeResult(line)
            }
        }

    suspend fun ping(): Int =
        (call("ping")["protocol"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1

    suspend fun agents(): List<Agent> = HerdrProtocol.agents(call("agent.list"))

    suspend fun workspaces(): List<Workspace> = HerdrProtocol.workspaces(call("workspace.list"))

    suspend fun read(paneId: String, lines: Int = 200, ansi: Boolean = false): PaneRead =
        HerdrProtocol.paneRead(call("pane.read", HerdrProtocol.readParams(paneId, lines, ansi)))

    suspend fun sendText(paneId: String, text: String) {
        call("pane.send_text", HerdrProtocol.sendTextParams(paneId, text))
    }

    suspend fun sendKeys(paneId: String, keys: List<String>) {
        call("pane.send_keys", HerdrProtocol.sendKeysParams(paneId, keys))
    }

    /** Sends [text] followed by Enter, the way a user types a prompt. */
    suspend fun submit(paneId: String, text: String) {
        if (text.isNotEmpty()) sendText(paneId, text)
        sendKeys(paneId, listOf("enter"))
    }

    /**
     * Opens a long-lived subscription. The flow completes when herdr closes the connection
     * and fails with [HerdrException] (for example `events_lost`) on an error line.
     */
    fun subscribe(paneIds: Collection<String>): Flow<HerdrEvent> = callbackFlow {
        val stream = withContext(io) { opener.open() }
        launch(io) {
            runCatching {
                stream.output.write(
                    HerdrProtocol.encodeRequest(nextId(), "events.subscribe", HerdrProtocol.subscriptionParams(paneIds))
                        .toByteArray(),
                )
                stream.output.flush()
                val reader = stream.input.bufferedReader()
                val ack = reader.readLine()
                    ?: throw HerdrException("connection_closed", "subscription closed before ack")
                HerdrProtocol.decodeResult(ack)
                while (true) {
                    val line = reader.readLine() ?: break
                    HerdrProtocol.decodeEvent(line)?.let { send(it) }
                }
            }.onSuccess { channel.close() }.onFailure { channel.close(it) }
        }
        awaitClose { runCatching { stream.close() } }
    }

    private fun nextId(): String = "oh_${ids.incrementAndGet()}"
}
