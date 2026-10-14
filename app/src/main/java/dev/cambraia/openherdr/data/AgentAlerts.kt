package dev.cambraia.openherdr.data

import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.AgentStatus

data class AgentAlert(val agent: Agent, val kind: Kind) {
    enum class Kind { NeedsInput, Finished }
}

object AgentAlerts {
    /**
     * Alerts for agents that just became blocked or done. [previous] is null on the first
     * load after connecting, which never alerts: those states are not news.
     */
    fun transitions(previous: List<Agent>?, current: List<Agent>): List<AgentAlert> {
        if (previous == null) return emptyList()
        val before = previous.associateBy { it.paneId }
        return current.mapNotNull { agent ->
            val was = before[agent.paneId]?.status
            when {
                agent.status == was -> null
                agent.status == AgentStatus.Blocked -> AgentAlert(agent, AgentAlert.Kind.NeedsInput)
                agent.status == AgentStatus.Done && was != null -> AgentAlert(agent, AgentAlert.Kind.Finished)
                else -> null
            }
        }
    }

    /** Workspaces in attention order, agents inside each in attention order. */
    fun grouped(agents: List<Agent>, workspaceLabels: Map<String, String>): List<Pair<String, List<Agent>>> =
        agents.groupBy { it.workspaceId }
            .map { (id, list) -> (workspaceLabels[id] ?: id) to list.sortedWith(compareBy({ it.status.attentionRank }, { it.paneId })) }
            .sortedWith(compareBy({ it.second.minOf { a -> a.status.attentionRank } }, { it.first }))
}
