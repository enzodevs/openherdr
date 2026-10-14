package dev.cambraia.openherdr.data

import dev.cambraia.openherdr.protocol.AgentStatus
import dev.cambraia.openherdr.protocol.SocketStream
import dev.cambraia.openherdr.ssh.HostKeyPin
import dev.cambraia.openherdr.ssh.MemoryPinStore
import dev.cambraia.openherdr.ssh.QueueInputStream
import dev.cambraia.openherdr.ssh.SshFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** In-memory herdr: answers requests from [statuses] and lets tests push status events. */
private class FakeHerdr {
    val statuses = linkedMapOf("w1:p1" to "idle")
    private val subscribers = CopyOnWriteArrayList<QueueInputStream>()
    @Volatile var connected = true

    fun push(pane: String, status: String) {
        statuses[pane] = status
        val line = """{"event":"pane.agent_status_changed","data":{"pane_id":"$pane","agent_status":"$status"}}""" + "\n"
        subscribers.forEach { runCatching { it.sink.write(line.toByteArray()) } }
    }

    fun drop() {
        connected = false
        subscribers.forEach { it.close() }
    }

    fun link() = object : HerdrLink {
        override val isConnected get() = connected
        override fun close() = Unit
        override fun open(): SocketStream {
            check(connected) { "link down" }
            val replies = QueueInputStream()
            return object : SocketStream {
                override val input: InputStream = replies
                override val output: OutputStream = object : OutputStream() {
                    val buffer = ByteArrayOutputStream()
                    override fun write(b: Int) {
                        if (b == '\n'.code) handle(buffer.toString(), replies).also { buffer.reset() } else buffer.write(b)
                    }
                }
                override fun close() { subscribers.remove(replies); replies.close() }
            }
        }
    }

    private fun handle(request: String, replies: QueueInputStream) {
        val obj = Json.parseToJsonElement(request).jsonObject
        val id = obj["id"]!!.jsonPrimitive.content
        val result = when (obj["method"]!!.jsonPrimitive.content) {
            "ping" -> """{"type":"pong","protocol":22}"""
            "agent.list" -> statuses.entries.joinToString(",", """{"type":"agent_list","agents":[""", "]}") { (pane, status) ->
                """{"pane_id":"$pane","workspace_id":"w1","tab_id":"w1:t1","terminal_id":"t-$pane","agent":"fake","agent_status":"$status"}"""
            }
            "workspace.list" -> """{"type":"workspace_list","workspaces":[{"workspace_id":"w1","number":1,"label":"demo"}]}"""
            "events.subscribe" -> {
                subscribers += replies
                """{"type":"subscription_started"}"""
            }
            else -> """{"type":"ok"}"""
        }
        replies.sink.write("""{"id":"$id","result":$result}""".plus("\n").toByteArray())
        if (obj["method"]!!.jsonPrimitive.content != "events.subscribe") replies.close()
    }
}

class ConnectionManagerTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val profile = ServerProfile(name = "pc", host = "10.0.0.2", user = "user")
    private val pin = HostKeyPin("ssh-ed25519", "AAAA")
    private val alerts = CopyOnWriteArrayList<AgentAlert>()

    @After
    fun tearDown() = scope.cancel()

    private fun manager(connector: Connector, pins: MemoryPinStore = MemoryPinStore()) =
        ConnectionManager(scope, connector, pins, { _, a -> alerts += a }, Dispatchers.IO, pollMillis = 60_000, livenessMillis = 50)

    private fun <T> waitFor(what: String, read: () -> T, done: (T) -> Boolean): T = runBlocking {
        withTimeout(5_000) {
            while (!done(read())) delay(20)
            read()
        }.also { assertTrue(what, done(it)) }
    }

    @Test
    fun `unknown host waits for trust, then pins and connects`() {
        val herdr = FakeHerdr()
        val pins = MemoryPinStore()
        val m = manager({ p ->
            if (pins.get(p.target.hostId) == null) throw SshFailure.UnknownHostKey(p.target.hostId, pin)
            herdr.link()
        }, pins)
        m.connect(profile)
        val needs = waitFor("needs trust", { m.connection.value }) { it is Connection.NeedsTrust } as Connection.NeedsTrust
        assertEquals(pin, needs.failure.pin)

        m.trust(needs)
        assertEquals(pin, pins.get("10.0.0.2"))
        waitFor("connected", { m.connection.value }) { it is Connection.Connected }
        val snap = waitFor("loaded", { m.snapshot.value }) { it.loaded }
        assertEquals(listOf("w1:p1"), snap.agents.map { it.paneId })
    }

    @Test
    fun `changed host key is refused without retrying`() {
        val calls = AtomicInteger()
        val m = manager({ p ->
            calls.incrementAndGet()
            throw SshFailure.HostKeyChanged(p.target.hostId, pin, pin.copy(keyBase64 = "BBBB"))
        })
        m.connect(profile)
        waitFor("refused", { m.connection.value }) { it is Connection.Refused }
        Thread.sleep(300)
        assertEquals(1, calls.get())
    }

    @Test
    fun `rejected device key stops instead of hammering the server`() {
        val calls = AtomicInteger()
        val m = manager({ calls.incrementAndGet(); throw SshFailure.AuthRejected(RuntimeException("Auth fail")) })
        m.connect(profile)
        val failed = waitFor("failed", { m.connection.value }) { it is Connection.Failed } as Connection.Failed
        assertEquals(false, failed.retrying)
        Thread.sleep(300)
        assertEquals(1, calls.get())
    }

    @Test
    fun `status events refresh the snapshot and raise an alert when blocked`() {
        val herdr = FakeHerdr()
        val m = manager({ herdr.link() })
        m.connect(profile)
        waitFor("loaded", { m.snapshot.value }) { it.loaded }
        Thread.sleep(200) // let the per-pane subscription open
        herdr.push("w1:p1", "blocked")
        val snap = waitFor("blocked", { m.snapshot.value }) { s -> s.agents.any { it.status == AgentStatus.Blocked } }
        assertEquals(AgentStatus.Blocked, snap.agents.single().status)
        waitFor("alert", { alerts.toList() }) { it.isNotEmpty() }
        assertEquals(AgentAlert.Kind.NeedsInput, alerts.single().kind)
    }

    @Test
    fun `a dropped link reconnects`() {
        val links = AtomicInteger()
        var herdr = FakeHerdr()
        val m = manager({ links.incrementAndGet(); herdr = FakeHerdr(); herdr.link() })
        m.connect(profile)
        waitFor("connected", { m.connection.value }) { it is Connection.Connected }
        herdr.drop()
        waitFor("reconnected", { links.get() }) { it >= 2 }
        waitFor("connected again", { m.connection.value }) { it is Connection.Connected }
    }

    @Test
    fun `disconnect returns to idle and clears data`() {
        val herdr = FakeHerdr()
        val m = manager({ herdr.link() })
        m.connect(profile)
        waitFor("loaded", { m.snapshot.value }) { it.loaded }
        m.disconnect()
        assertEquals(Connection.Idle, m.connection.value)
        assertEquals(false, m.snapshot.value.loaded)
    }
}
