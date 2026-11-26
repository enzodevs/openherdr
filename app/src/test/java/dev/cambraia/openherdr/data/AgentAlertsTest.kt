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
}
