package dev.cambraia.openherdr.data

import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAlertsTest {
    private fun agent(pane: String, status: AgentStatus, ws: String = "w1") =
        Agent(paneId = pane, workspaceId = ws, tabId = "$ws:t1", terminalId = "t-$pane", status = status)

    @Test
    fun `first load never alerts`() {
        assertTrue(AgentAlerts.transitions(null, listOf(agent("w1:p1", AgentStatus.Blocked))).isEmpty())
    }

    @Test
    fun `becoming blocked alerts once`() {
        val before = listOf(agent("w1:p1", AgentStatus.Working))
        val after = listOf(agent("w1:p1", AgentStatus.Blocked))
        assertEquals(listOf(AgentAlert.Kind.NeedsInput), AgentAlerts.transitions(before, after).map { it.kind })
        assertTrue("staying blocked is not news", AgentAlerts.transitions(after, after).isEmpty())
    }

    @Test
    fun `finishing alerts, but a brand new agent that is already done does not`() {
        val before = listOf(agent("w1:p1", AgentStatus.Working))
        val after = listOf(agent("w1:p1", AgentStatus.Done), agent("w1:p2", AgentStatus.Done))
        val alerts = AgentAlerts.transitions(before, after)
        assertEquals(1, alerts.size)
        assertEquals("w1:p1", alerts[0].agent.paneId)
        assertEquals(AgentAlert.Kind.Finished, alerts[0].kind)
    }

    @Test
    fun `a new agent that starts blocked alerts`() {
        val alerts = AgentAlerts.transitions(emptyList(), listOf(agent("w1:p9", AgentStatus.Blocked)))
        assertEquals(AgentAlert.Kind.NeedsInput, alerts.single().kind)
    }

    @Test
    fun `working and idle transitions are quiet`() {
        val before = listOf(agent("w1:p1", AgentStatus.Idle))
        assertTrue(AgentAlerts.transitions(before, listOf(agent("w1:p1", AgentStatus.Working))).isEmpty())
    }

    @Test
    fun `workspaces needing attention come first`() {
        val agents = listOf(
            agent("w1:p1", AgentStatus.Idle, "w1"),
            agent("w2:p2", AgentStatus.Working, "w2"),
            agent("w2:p1", AgentStatus.Blocked, "w2"),
        )
        val groups = AgentAlerts.grouped(agents, mapOf("w1" to "api", "w2" to "web"))
        assertEquals(listOf("web", "api"), groups.map { it.first })
        assertEquals(listOf("w2:p1", "w2:p2"), groups[0].second.map { it.paneId })
        assertNull(groups.find { it.first == "w3" })
    }
}
