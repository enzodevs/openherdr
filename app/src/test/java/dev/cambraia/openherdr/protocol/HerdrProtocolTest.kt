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

class ReadParamsTest {
    @Test
    fun `ansi reads keep escape codes`() {
        val ansi = HerdrProtocol.readParams("w1:p1", 50, ansi = true).toString()
        assertEquals("""{"pane_id":"w1:p1","source":"recent","lines":50,"format":"ansi","strip_ansi":false}""", ansi)
        val plain = HerdrProtocol.readParams("w1:p1", 50).toString()
        assertEquals("""{"pane_id":"w1:p1","source":"recent","lines":50,"format":"text","strip_ansi":true}""", plain)
    }
}

/** Topology reads and changes; fixtures captured from herdr 0.9.5 with the home path replaced. */
class TopologyProtocolTest {
    @Test
    fun `pane list includes plain terminals with no agent`() {
        val line = """{"id":"cli:pane:list","result":{"panes":[{"agent":"pi","agent_session":{"agent":"pi","kind":"path","source":"herdr:pi","value":"/home/user/.pi/agent/sessions/x.jsonl"},"agent_status":"working","cwd":"/home/user/app","focused":false,"foreground_cwd":"/home/user/app","pane_id":"w1:p2","revision":1,"scroll":{"max_offset_from_bottom":0,"offset_from_bottom":0,"viewport_rows":38},"tab_id":"w1:t2","terminal_id":"term_65dd23cb1a1082","terminal_title":"π - app","terminal_title_stripped":"π - app","workspace_id":"w1"},{"agent_status":"unknown","cwd":"/home/user/app","focused":false,"foreground_cwd":"/home/user/app","pane_id":"w3:p1","revision":0,"scroll":{"max_offset_from_bottom":0,"offset_from_bottom":0,"viewport_rows":38},"tab_id":"w3:t1","terminal_id":"term_65dd639714aab14","workspace_id":"w3"}],"type":"pane_list"}}"""
        val panes = HerdrProtocol.panes(HerdrProtocol.decodeResult(line))
        assertEquals(listOf("w1:p2", "w3:p1"), panes.map { it.id })
        assertEquals("pi", panes[0].agent)
        assertNull(panes[1].agent)
        assertEquals("w3:t1", panes[1].tabId)
        assertEquals("/home/user/app", panes[1].cwd)
    }

    @Test
    fun `pane label is read when set`() {
        val line = """{"id":"cli:pane:list","result":{"panes":[{"agent_status":"unknown","cwd":"/tmp","focused":true,"foreground_cwd":"/tmp","label":"dev server","pane_id":"w1:p1","revision":0,"tab_id":"w1:t1","terminal_id":"term_65dd67059dd3","workspace_id":"w1"}],"type":"pane_list"}}"""
        assertEquals("dev server", HerdrProtocol.panes(HerdrProtocol.decodeResult(line))[0].label)
    }

    @Test
    fun `tab list decodes labels and numbers`() {
        val line = """{"id":"cli:tab:list","result":{"tabs":[{"agent_status":"working","focused":true,"label":"1","number":1,"pane_count":2,"tab_id":"w1:t1","workspace_id":"w1"},{"agent_status":"unknown","focused":false,"label":"tunnel","number":1,"pane_count":1,"tab_id":"w3:t1","workspace_id":"w3"}],"type":"tab_list"}}"""
        val tabs = HerdrProtocol.tabs(HerdrProtocol.decodeResult(line))
        assertEquals(listOf("w1:t1", "w3:t1"), tabs.map { it.id })
        assertEquals("tunnel", tabs[1].label)
        assertEquals("w3", tabs[1].workspaceId)
    }

    @Test
    fun `process info names the foreground command, not the shell`() {
        val running = """{"id":"cli:pane:process_info","result":{"process_info":{"foreground_process_group_id":747467,"foreground_processes":[{"argv":["just","qa-tunnel"],"cmdline":"just qa-tunnel","cwd":"/home/user/app","name":"just","pid":747467},{"argv":["node","/home/user/.local/bin/wrangler.js","tunnel","run"],"cmdline":"node /home/user/.local/bin/wrangler.js tunnel run","cwd":"/home/user/app","name":"node-MainThread","pid":747470}],"pane_id":"w3:p1","shell_pid":747400},"type":"pane_process_info"}}"""
        assertEquals("just qa-tunnel", HerdrProtocol.processInfo(HerdrProtocol.decodeResult(running)).command)

        val sleeping = """{"id":"cli:pane:process_info","result":{"process_info":{"foreground_process_group_id":772901,"foreground_processes":[{"argv":["sleep","300"],"cmdline":"sleep 300","cwd":"/tmp","name":"sleep","pid":772901}],"pane_id":"w1:p1","shell_pid":772678},"type":"pane_process_info"}}"""
        assertEquals("sleep 300", HerdrProtocol.processInfo(HerdrProtocol.decodeResult(sleeping)).command)
    }

    @Test
    fun `a pane at its shell prompt has no command`() {
        val idle = """{"id":"cli:pane:process_info","result":{"process_info":{"foreground_process_group_id":771677,"foreground_processes":[{"argv":["-zsh"],"cmdline":"-zsh","cwd":"/tmp","name":"zsh","pid":771677}],"pane_id":"w5:p1","shell_pid":771677},"type":"pane_process_info"}}"""
        assertNull(HerdrProtocol.processInfo(HerdrProtocol.decodeResult(idle)).command)
    }

    @Test
    fun `create results return the root pane`() {
        val ws = """{"id":"cli:workspace:create","result":{"root_pane":{"agent_status":"unknown","cwd":"/tmp","focused":false,"foreground_cwd":"/tmp","pane_id":"w5:p1","revision":0,"tab_id":"w5:t1","terminal_id":"term_65dd66f22d88f16","workspace_id":"w5"},"tab":{"agent_status":"unknown","focused":false,"label":"1","number":1,"pane_count":1,"tab_id":"w5:t1","workspace_id":"w5"},"type":"workspace_created","workspace":{"active_tab_id":"w5:t1","agent_status":"unknown","focused":false,"label":"demo","number":3,"pane_count":1,"tab_count":1,"workspace_id":"w5"}}}"""
        assertEquals("w5:p1", HerdrProtocol.rootPaneId(HerdrProtocol.decodeResult(ws)))
        val ok = """{"id":"r","result":{"type":"ok"}}"""
        assertThrows(HerdrException::class.java) { HerdrProtocol.rootPaneId(HerdrProtocol.decodeResult(ok)) }
    }

    @Test
    fun `new workspaces and tabs never steal the PC's focus`() {
        assertEquals("""{"label":"demo","cwd":"/tmp","focus":false}""", HerdrProtocol.createWorkspaceParams("demo", "/tmp").toString())
        assertEquals("""{"focus":false}""", HerdrProtocol.createWorkspaceParams(null, null).toString())
        assertEquals("""{"workspace_id":"w1","label":"logs","focus":false}""", HerdrProtocol.createTabParams("w1", "logs").toString())
    }

    @Test
    fun `renaming a pane to nothing sends an explicit null`() {
        assertEquals("""{"pane_id":"w1:p1","label":null}""", HerdrProtocol.renamePaneParams("w1:p1", null).toString())
        assertEquals("""{"pane_id":"w1:p1","label":"api"}""", HerdrProtocol.renamePaneParams("w1:p1", "api").toString())
    }

    @Test
    fun `tab renames are subscribed to`() {
        assert("tab.renamed" in HerdrProtocol.LIFECYCLE_EVENTS)
    }
}
