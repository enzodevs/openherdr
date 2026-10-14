package dev.cambraia.openherdr.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Effective agent status as reported by herdr (protocol 22). */
@Serializable
enum class AgentStatus {
    @SerialName("idle") Idle,
    @SerialName("working") Working,
    @SerialName("blocked") Blocked,
    @SerialName("done") Done,
    @SerialName("unknown") Unknown;

    /** Lower sorts first: what needs the user floats to the top. */
    val attentionRank: Int
        get() = when (this) {
            Blocked -> 0
            Done -> 1
            Working -> 2
            Idle -> 3
            Unknown -> 4
        }
}

@Serializable
data class Agent(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("agent_status") val status: AgentStatus = AgentStatus.Unknown,
    val agent: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    val name: String? = null,
    val title: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitle: String? = null,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    @SerialName("state_change_seq") val stateChangeSeq: Long = 0,
) {
    val label: String
        get() = name ?: displayAgent ?: agent ?: paneId

    val subtitle: String?
        get() = title ?: terminalTitle ?: foregroundCwd ?: cwd
}

@Serializable
data class Workspace(
    @SerialName("workspace_id") val id: String,
    val number: Int = 0,
    val label: String = "",
    val focused: Boolean = false,
    @SerialName("agent_status") val status: AgentStatus = AgentStatus.Unknown,
)

@Serializable
data class PaneRead(
    @SerialName("pane_id") val paneId: String,
    val text: String,
    val truncated: Boolean = false,
    val revision: Long = 0,
)

/** One pushed line from an `events.subscribe` connection. */
data class HerdrEvent(val event: String, val paneId: String?, val status: AgentStatus?)

class HerdrException(val code: String, message: String) : Exception("$code: $message")
