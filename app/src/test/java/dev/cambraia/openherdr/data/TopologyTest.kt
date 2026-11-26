package dev.cambraia.openherdr.data

import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.AgentStatus
import dev.cambraia.openherdr.protocol.Pane
import dev.cambraia.openherdr.protocol.TabInfo
import dev.cambraia.openherdr.protocol.Workspace
import dev.cambraia.openherdr.ui.describePanes
import dev.cambraia.openherdr.ui.expandHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Shaped like the live session the feature was built against: agents in w1, a tunnel in w3. */
class TopologyTest {
    private fun pane(id: String, tab: String, agent: String? = null, label: String? = null, cwd: String = "/home/user/app") =
        Pane(id = id, workspaceId = id.substringBefore(':'), tabId = tab, agent = agent, label = label, cwd = cwd, foregroundCwd = cwd)

    private fun agent(id: String, tab: String, status: AgentStatus, name: String = "claude") =
        Agent(paneId = id, workspaceId = id.substringBefore(':'), tabId = tab, terminalId = "t", status = status, agent = name, terminalTitle = "Fix login")

    private val snapshot = Snapshot(
        agents = listOf(agent("w1:p1", "w1:t1", AgentStatus.Working), agent("w1:p2", "w1:t2", AgentStatus.Blocked, "pi")),
        workspaces = listOf(Workspace("w3", number = 2, label = "redapro"), Workspace("w1", number = 1, label = "~")),
        tabs = listOf(
            TabInfo("w1:t2", "w1", number = 2, label = "PI"),
            TabInfo("w1:t1", "w1", number = 1, label = "1"),
            TabInfo("w3:t1", "w3", number = 1, label = "tunnel"),
        ),
        panes = listOf(
            pane("w1:p1", "w1:t1", agent = "claude"),
            pane("w1:p2", "w1:t2", agent = "pi"),
            pane("w3:p1", "w3:t1"),
            pane("w3:p2", "w3:t1", cwd = "/home/user"),
        ),
        commands = mapOf("w3:p1" to "just qa-tunnel", "w3:p2" to null),
        home = "/home/user",
        loaded = true,
    )

    @Test
    fun `workspaces and tabs follow herdr's numbering and plain panes are included`() {
        val tree = Topology.build(snapshot)
        assertEquals(listOf("~", "redapro"), tree.map { it.title })
        assertEquals(listOf("Tab 1", "PI"), tree[0].tabs.map { it.title })
        assertEquals(listOf("w3:p1", "w3:p2"), tree[1].tabs.single().panes.map { it.id })
    }

    @Test
    fun `plain panes are named by their command, or Shell at the prompt`() {
        val tunnel = Topology.build(snapshot)[1].tabs.single().panes
        assertEquals("just qa-tunnel", tunnel[0].title)
        assertNull(tunnel[0].agent)
        assertEquals("Shell", tunnel[1].title)
    }

    @Test
    fun `an explicit pane name wins and the command moves to the detail line`() {
        val named = snapshot.copy(panes = snapshot.panes.map { if (it.id == "w3:p1") it.copy(label = "tunnel") else it })
        val node = Topology.find(Topology.build(named), "w3:p1")!!
        assertEquals("tunnel", node.title)
        assertEquals("just qa-tunnel", node.detail)
    }

    @Test
    fun `agents keep their status and the workspace reports the most urgent one`() {
        val tree = Topology.build(snapshot)
        assertEquals(AgentStatus.Blocked, tree[0].status)
        assertNull(tree[1].status)
        assertEquals(listOf("w1:p2"), Topology.needsYou(tree).map { it.id })
        assertEquals("Fix login", Topology.find(tree, "w1:p1")!!.detail)
    }

    @Test
    fun `a pane whose tab or workspace is not listed yet still appears`() {
        val racing = snapshot.copy(panes = snapshot.panes + pane("w9:p1", "w9:t1"))
        val tree = Topology.build(racing)
        assertEquals(listOf("w9:p1"), tree.single { it.workspace.id == "w9" }.panes.map { it.id })
        assertEquals("Tab ?", tree.single { it.workspace.id == "w9" }.tabs.single().title)
    }

    @Test
    fun `paths under home are shown with a tilde`() {
        assertEquals("~/app", Topology.tilde("/home/user/app", "/home/user"))
        assertEquals("~", Topology.tilde("/home/user", "/home/user"))
        assertEquals("/home/username/x", Topology.tilde("/home/username/x", "/home/user"))
        assertEquals("/tmp", Topology.tilde("/tmp", null))
        assertEquals("/etc", Topology.tilde("/etc", "/"))
    }

    @Test
    fun `commands drop the directory of the program only`() {
        assertEquals("node server.js --port 3000", Topology.shortCommand("/usr/bin/node server.js --port 3000"))
        assertEquals("just qa-tunnel", Topology.shortCommand("just qa-tunnel"))
    }

    @Test
    fun `typed directories expand the home tilde`() {
        assertEquals("/home/user/app", expandHome("~/app", "/home/user"))
        assertEquals("/home/user", expandHome("~", "/home/user"))
        assertEquals("/srv", expandHome("/srv", "/home/user"))
        assertNull(expandHome("", "/home/user"))
        assertEquals("~/app", expandHome("~/app", null))
    }

    @Test
    fun `close confirmations list what each pane is and where`() {
        val tunnel = Topology.build(snapshot)[1].panes
        assertEquals(listOf("just qa-tunnel · ~/app", "Shell · ~"), describePanes(tunnel, "/home/user"))
    }
}
