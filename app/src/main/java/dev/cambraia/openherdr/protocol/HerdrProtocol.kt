package dev.cambraia.openherdr.protocol

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Newline-delimited JSON codec for the herdr socket API.
 *
 * The server answers exactly one request per connection, except `events.subscribe`,
 * which keeps the connection open and pushes one event per line after the ack.
 */
object HerdrProtocol {
    const val PROTOCOL = 22

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun encodeRequest(id: String, method: String, params: JsonObject = JsonObject(emptyMap())): String =
        buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString() + "\n"

    /** Returns the `result` object or throws [HerdrException] for an `error` response. */
    fun decodeResult(line: String): JsonObject {
        val root = json.parseToJsonElement(line).jsonObject
        root["error"]?.let { error ->
            val obj = error.jsonObject
            throw HerdrException(obj.string("code") ?: "unknown_error", obj.string("message") ?: "")
        }
        return root["result"]?.jsonObject ?: throw HerdrException("malformed_response", line.take(200))
    }

    /** Parses a pushed subscription line; returns null for lines that are not events. */
    fun decodeEvent(line: String): HerdrEvent? {
        val root = json.parseToJsonElement(line).jsonObject
        root["error"]?.let { decodeResult(line) }
        val name = root.string("event") ?: return null
        val data = root["data"] as? JsonObject
        val status = data?.string("agent_status")?.let { raw ->
            AgentStatus.entries.firstOrNull { it.serialName() == raw } ?: AgentStatus.Unknown
        }
        return HerdrEvent(name, data?.string("pane_id"), status)
    }

    fun agents(result: JsonObject): List<Agent> =
        json.decodeFromJsonElement(ListSerializer(Agent.serializer()), result.array("agents"))

    fun workspaces(result: JsonObject): List<Workspace> =
        json.decodeFromJsonElement(ListSerializer(Workspace.serializer()), result.array("workspaces"))

    fun paneRead(result: JsonObject): PaneRead =
        json.decodeFromJsonElement(PaneRead.serializer(), result["read"] ?: result)

    /**
     * Global lifecycle events plus per-pane status changes. `pane.agent_status_changed`
     * requires a pane id, so the subscription must be reopened when panes come and go.
     */
    fun subscriptionParams(paneIds: Collection<String>): JsonObject = buildJsonObject {
        put("subscriptions", buildJsonArray {
            LIFECYCLE_EVENTS.forEach { add(buildJsonObject { put("type", it) }) }
            paneIds.sorted().forEach { pane ->
                add(buildJsonObject {
                    put("type", "pane.agent_status_changed")
                    put("pane_id", pane)
                })
            }
        })
    }

    /** With [ansi] the text keeps SGR color codes (see [Ansi]); otherwise it is plain. */
    fun readParams(paneId: String, lines: Int, ansi: Boolean = false): JsonObject = buildJsonObject {
        put("pane_id", paneId)
        put("source", "recent")
        put("lines", lines)
        put("format", if (ansi) "ansi" else "text")
        put("strip_ansi", !ansi)
    }

    fun sendTextParams(paneId: String, text: String): JsonObject = buildJsonObject {
        put("pane_id", paneId)
        put("text", text)
    }

    fun sendKeysParams(paneId: String, keys: List<String>): JsonObject = buildJsonObject {
        put("pane_id", paneId)
        put("keys", JsonArray(keys.map(::JsonPrimitive)))
    }

    val LIFECYCLE_EVENTS = listOf(
        "workspace.created", "workspace.closed", "workspace.renamed",
        "tab.created", "tab.closed",
        "pane.created", "pane.closed", "pane.exited", "pane.agent_detected",
    )

    /** Events that change which panes exist, so the per-pane subscription set is stale. */
    val TOPOLOGY_EVENTS = setOf("pane.created", "pane.closed", "pane.exited", "pane.agent_detected", "workspace.closed", "tab.closed")

    private fun AgentStatus.serialName(): String = name.lowercase()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.array(key: String): JsonElement =
        this[key]?.jsonArray ?: throw HerdrException("malformed_response", "missing $key")
}
