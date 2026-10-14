package dev.cambraia.openherdr.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Scripted fake socket: records what the client wrote and replies with fixed lines. */
private class ScriptedStream(reply: String, keepOpen: Boolean = false) : SocketStream {
    val written = ByteArrayOutputStream()
    var closed = false
    private val pipeOut = PipedOutputStream()
    override val input: InputStream = PipedInputStream(pipeOut, 1 shl 16)
    override val output: OutputStream = written

    init {
        pipeOut.write(reply.toByteArray())
        pipeOut.flush()
        if (!keepOpen) pipeOut.close()
    }

    override fun close() {
        closed = true
        runCatching { pipeOut.close() }
    }
}

class HerdrClientTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    @Test
    fun `call writes one request line and returns the result`() = runBlocking {
        val stream = ScriptedStream("""{"id":"oh_1","result":{"type":"pong","protocol":22}}""" + "\n")
        val client = HerdrClient({ stream }, Dispatchers.IO)
        assertEquals(22, client.ping())
        val sent = stream.written.toString()
        assertTrue(sent.endsWith("\n"))
        assertTrue(sent.contains("\"method\":\"ping\""))
        assertTrue("connection is closed after one request", stream.closed)
    }

    @Test
    fun `submit sends text and then enter on separate connections`() = runBlocking {
        val streams = mutableListOf<ScriptedStream>()
        val client = HerdrClient({ ScriptedStream("""{"id":"x","result":{"type":"ok"}}""" + "\n").also { streams += it } }, Dispatchers.IO)
        client.submit("w1:p1", "please approve")
        assertEquals(2, streams.size)
        assertTrue(streams[0].written.toString().contains("\"method\":\"pane.send_text\""))
        assertTrue(streams[0].written.toString().contains("please approve"))
        assertTrue(streams[1].written.toString().contains("\"keys\":[\"enter\"]"))
    }

    @Test
    fun `empty submit only presses enter`() = runBlocking {
        val streams = mutableListOf<ScriptedStream>()
        val client = HerdrClient({ ScriptedStream("""{"id":"x","result":{"type":"ok"}}""" + "\n").also { streams += it } }, Dispatchers.IO)
        client.submit("w1:p1", "")
        assertEquals(1, streams.size)
    }

    @Test
    fun `missing response is an error, not a hang`() {
        val client = HerdrClient({ ScriptedStream("") }, Dispatchers.IO)
        val e = runCatching { runBlocking { client.ping() } }.exceptionOrNull()
        assertEquals("connection_closed", (e as HerdrException).code)
    }

    @Test
    fun `subscribe emits pushed events after the ack`() = runBlocking {
        val lines = """{"id":"oh_1","result":{"type":"subscription_started"}}
{"data":{"agent_status":"working","pane_id":"w1:p1"},"event":"pane.agent_status_changed"}
{"data":{"agent_status":"blocked","pane_id":"w1:p1"},"event":"pane.agent_status_changed"}
"""
        val events = withTimeout(5_000) {
            HerdrClient({ ScriptedStream(lines) }, Dispatchers.IO).subscribe(listOf("w1:p1")).toList()
        }
        assertEquals(listOf(AgentStatus.Working, AgentStatus.Blocked), events.map { it.status })
    }

    @Test
    fun `subscribe fails with events_lost`() {
        val lines = """{"id":"oh_1","result":{"type":"subscription_started"}}
{"id":"oh_1","error":{"code":"events_lost","message":"fell behind"}}
"""
        val e = runCatching {
            runBlocking { withTimeout(5_000) { HerdrClient({ ScriptedStream(lines) }, Dispatchers.IO).subscribe(emptyList()).toList() } }
        }.exceptionOrNull()
        assertEquals("events_lost", (e as HerdrException).code)
    }

    @Test
    fun `cancelling a live subscription closes the stream`() = runBlocking {
        val stream = ScriptedStream(
            """{"id":"oh_1","result":{"type":"subscription_started"}}
{"data":{"agent_status":"working","pane_id":"w1:p1"},"event":"pane.agent_status_changed"}
""",
            keepOpen = true,
        )
        withTimeout(5_000) { HerdrClient({ stream }, Dispatchers.IO).subscribe(emptyList()).take(1).toList() }
        assertTrue(stream.closed)
    }
}
