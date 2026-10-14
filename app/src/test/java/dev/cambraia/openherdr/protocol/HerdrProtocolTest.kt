package dev.cambraia.openherdr.protocol

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fixtures are real lines captured from herdr 0.9.5 (protocol 22). */
class HerdrProtocolTest {
    @Test
    fun `agent list decodes and ignores unknown fields`() {
        val line = """{"id":"cli:agent:list","result":{"agents":[{"agent":"fake","agent_status":"blocked","cwd":"/tmp","focused":true,"foreground_cwd":"/tmp","pane_id":"w1:p1","revision":0,"state_change_seq":3,"tab_id":"w1:t1","terminal_id":"term_65dd0efb41f191","workspace_id":"w1","future_field":{"x":1}}],"type":"agent_list"}}"""
        val agents = HerdrProtocol.agents(HerdrProtocol.decodeResult(line))
        assertEquals(1, agents.size)
        assertEquals(AgentStatus.Blocked, agents[0].status)
        assertEquals("fake", agents[0].label)
        assertEquals("w1:p1", agents[0].paneId)
        assertEquals("/tmp", agents[0].subtitle)
    }

    @Test
    fun `unknown status values fall back to Unknown`() {
        val line = """{"id":"r","result":{"type":"agent_list","agents":[{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","terminal_id":"t","agent_status":"sleeping"}]}}"""
        assertEquals(AgentStatus.Unknown, HerdrProtocol.agents(HerdrProtocol.decodeResult(line))[0].status)
    }

    @Test
    fun `error responses become HerdrException with the server code`() {
        val line = """{"id":"r","error":{"code":"agent_not_ready","message":"agent w1:p1 is not an active named agent"}}"""
        val e = assertThrows(HerdrException::class.java) { HerdrProtocol.decodeResult(line) }
        assertEquals("agent_not_ready", e.code)
    }

    @Test
    fun `pane read text is nested under read`() {
        val line = """{"id":"r","result":{"type":"pane_read","read":{"pane_id":"w1:p1","workspace_id":"w1","tab_id":"w1:t1","source":"recent","format":"text","text":"fake> hi\n","revision":0,"truncated":true}}}"""
        val read = HerdrProtocol.paneRead(HerdrProtocol.decodeResult(line))
        assertEquals("fake> hi\n", read.text)
        assertEquals(true, read.truncated)
    }

    @Test
    fun `status events decode pane and status`() {
        val e = HerdrProtocol.decodeEvent("""{"data":{"agent":"fake","agent_status":"blocked","pane_id":"w1:p1","workspace_id":"w1"},"event":"pane.agent_status_changed"}""")!!
        assertEquals("pane.agent_status_changed", e.event)
        assertEquals("w1:p1", e.paneId)
        assertEquals(AgentStatus.Blocked, e.status)
    }

    @Test
    fun `events_lost on a subscription is raised`() {
        val e = assertThrows(HerdrException::class.java) {
            HerdrProtocol.decodeEvent("""{"id":"sub","error":{"code":"events_lost","message":"subscriber fell behind"}}""")
        }
        assertEquals("events_lost", e.code)
    }

    @Test
    fun `non-event lines are ignored`() {
        assertNull(HerdrProtocol.decodeEvent("""{"id":"sub","result":{"type":"subscription_started"}}"""))
    }

    @Test
    fun `subscription includes lifecycle events and one status entry per pane`() {
        val subs = HerdrProtocol.subscriptionParams(listOf("w2:p1", "w1:p1"))["subscriptions"]!!.jsonArray
        assertEquals(HerdrProtocol.LIFECYCLE_EVENTS.size + 2, subs.size)
        val status = subs.map { it.jsonObject }.filter { it["type"]!!.jsonPrimitive.content == "pane.agent_status_changed" }
        assertEquals(listOf("w1:p1", "w2:p1"), status.map { it["pane_id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `requests are one JSON object per line`() {
        val line = HerdrProtocol.encodeRequest("oh_1", "pane.send_keys", HerdrProtocol.sendKeysParams("w1:p1", listOf("y", "enter")))
        assertEquals("""{"id":"oh_1","method":"pane.send_keys","params":{"pane_id":"w1:p1","keys":["y","enter"]}}""" + "\n", line)
    }

    @Test
    fun `attention order puts blocked first`() {
        val sorted = AgentStatus.entries.sortedBy { it.attentionRank }
        assertEquals(listOf(AgentStatus.Blocked, AgentStatus.Done, AgentStatus.Working, AgentStatus.Idle, AgentStatus.Unknown), sorted)
    }
}
