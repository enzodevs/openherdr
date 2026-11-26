package dev.cambraia.openherdr.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One entry of an agent conversation, as shown in the chat view. */
sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String) : ChatItem
    data class Assistant(override val key: String, val text: String) : ChatItem
    data class Thought(override val key: String, val text: String) : ChatItem
    data class Tool(
        override val key: String,
        val name: String,
        val detail: String,
        val output: String? = null,
        val failed: Boolean = false,
    ) : ChatItem {
        val finished: Boolean get() = output != null
    }

    /**
     * The agent asked the user to choose (Pi `ask_user_question`, Claude `AskUserQuestion`).
     * [answer] is the tool result once answered; [failed] when it was cancelled or rejected.
     */
    data class Question(
        override val key: String,
        val questions: List<Ask>,
        val answer: String? = null,
        val failed: Boolean = false,
    ) : ChatItem {
        val finished: Boolean get() = answer != null

        /** One question, one choice: the only shape safe to answer with keys from the phone. */
        val simple: Boolean get() = questions.size == 1 && !questions[0].multiSelect && questions[0].options.isNotEmpty()
    }

    data class Ask(val question: String, val details: String?, val options: List<Option>, val multiSelect: Boolean, val header: String? = null)

    data class Option(val label: String, val description: String?)
}

/** The session file formats OpenHerdr understands. */
enum class TranscriptFormat { Claude, Codex, Pi }

/**
 * Builds a chat from session-file lines, appended in file order. Tool results are matched to
 * their calls by id. Lines that are not valid JSON (partial writes, raw control characters)
 * or that describe internal bookkeeping are skipped.
 *
 * DEFER: Pi sessions are trees (`parentId`); abandoned branches are shown inline in file order.
 */
class ChatLog(private val format: TranscriptFormat) {
    private val items = ArrayList<ChatItem>()
    private val toolIndex = HashMap<String, Int>()
    private var counter = 0

    val size: Int get() = items.size

    fun snapshot(): List<ChatItem> = items.toList()

    fun append(line: String) {
        val root = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return
        when (format) {
            TranscriptFormat.Claude -> claude(root)
            TranscriptFormat.Codex -> codex(root)
            TranscriptFormat.Pi -> pi(root)
        }
    }

    // --- Claude Code: ~/.claude/projects/<cwd>/<session>.jsonl

    private fun claude(root: JsonObject) {
        if (root.bool("isSidechain") == true || root.bool("isMeta") == true || root.bool("isCompactSummary") == true) return
        val message = root["message"] as? JsonObject ?: return
        when (root.str("type")) {
            "user" -> when (val content = message["content"]) {
                is JsonPrimitive -> content.contentOrNull?.let(::userText)
                is JsonArray -> content.objects().forEach { block ->
                    when (block.str("type")) {
                        "text" -> block.str("text")?.let(::userText)
                        "tool_result" -> finishTool(
                            block.str("tool_use_id"),
                            textOf(block["content"]),
                            block.bool("is_error") == true,
                        )
                    }
                }
                else -> Unit
            }
            "assistant" -> (message["content"] as? JsonArray)?.objects()?.forEach { block ->
                when (block.str("type")) {
                    "text" -> block.str("text")?.let { add(ChatItem.Assistant(nextKey(), it.trim())) }
                    "thinking" -> block.str("thinking")?.let { add(ChatItem.Thought(nextKey(), it.trim())) }
                    "tool_use" -> startTool(block.str("id"), block.str("name") ?: "tool", block["input"])
                }
            }
        }
    }

    /** Claude stores slash commands and harness notices as tagged user text. */
    private fun userText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (text.startsWith("<")) {
            val command = Regex("<command-name>([^<]+)</command-name>").find(text)?.groupValues?.get(1) ?: return
            val args = Regex("<command-args>([^<]*)</command-args>").find(text)?.groupValues?.get(1).orEmpty()
            add(ChatItem.User(nextKey(), "$command $args".trim()))
            return
        }
        add(ChatItem.User(nextKey(), text))
    }

    // --- Codex: ~/.codex/sessions/YYYY/MM/DD/rollout-<time>-<thread>.jsonl

    private fun codex(root: JsonObject) {
        val payload = root["payload"] as? JsonObject ?: return
        when (root.str("type")) {
            // The response_item copy of a user message also carries injected context; this one is what was typed.
            "event_msg" -> if (payload.str("type") == "user_message") {
                payload.str("message")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(ChatItem.User(nextKey(), it)) }
            }
            "response_item" -> when (payload.str("type")) {
                "message" -> if (payload.str("role") == "assistant") {
                    val text = (payload["content"] as? JsonArray)?.objects()
                        ?.filter { it.str("type") == "output_text" }
                        ?.mapNotNull { it.str("text") }
                        ?.joinToString("\n")
                        ?.trim()
                    if (!text.isNullOrEmpty()) add(ChatItem.Assistant(nextKey(), text))
                }
                "reasoning" -> {
                    val text = (payload["summary"] as? JsonArray)?.objects()?.mapNotNull { it.str("text") }?.joinToString("\n")?.trim()
                    if (!text.isNullOrEmpty()) add(ChatItem.Thought(nextKey(), text))
                }
                "function_call" -> startTool(
                    payload.str("call_id"),
                    payload.str("name") ?: "tool",
                    payload.str("arguments")?.let { runCatching { json.parseToJsonElement(it) }.getOrElse { _ -> JsonPrimitive(it) } },
                )
                "custom_tool_call" -> startTool(payload.str("call_id"), payload.str("name") ?: "tool", payload["input"])
                "function_call_output", "custom_tool_call_output" ->
                    finishTool(payload.str("call_id"), textOf(payload["output"]), failed = false)
            }
        }
    }

    // --- Pi: ~/.pi/agent/sessions/<cwd>/<time>_<id>.jsonl

    private fun pi(root: JsonObject) {
        if (root.str("type") != "message") return
        val message = root["message"] as? JsonObject ?: return
        when (message.str("role")) {
            "user" -> textOf(message["content"])?.trim()?.takeIf { it.isNotEmpty() }?.let { add(ChatItem.User(nextKey(), it)) }
            "assistant" -> (message["content"] as? JsonArray)?.objects()?.forEach { block ->
                when (block.str("type")) {
                    "text" -> block.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(ChatItem.Assistant(nextKey(), it)) }
                    "thinking" -> block.str("thinking")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(ChatItem.Thought(nextKey(), it)) }
                    "toolCall" -> startTool(block.str("id"), block.str("name") ?: "tool", block["arguments"])
                }
            }
            "toolResult" -> finishTool(message.str("toolCallId"), textOf(message["content"]), message.bool("isError") == true)
        }
    }

    // --- shared

    private fun startTool(id: String?, name: String, input: JsonElement?) {
        val key = id?.let { "tool-$it" } ?: nextKey()
        if (id != null && id in toolIndex) return
        if (id != null) toolIndex[id] = items.size
        val questions = if (name in QUESTION_TOOLS) questions(input as? JsonObject) else emptyList()
        add(if (questions.isNotEmpty()) ChatItem.Question(key, questions) else ChatItem.Tool(key, name, describe(input)))
    }

    private fun finishTool(id: String?, output: String?, failed: Boolean) {
        val index = id?.let(toolIndex::get) ?: return
        val text = (output ?: "").trimEnd().take(MAX_OUTPUT)
        items[index] = when (val item = items[index]) {
            is ChatItem.Tool -> item.copy(output = text, failed = failed)
            is ChatItem.Question -> item.copy(answer = text, failed = failed)
            else -> return
        }
    }

    private fun add(item: ChatItem) {
        if (item is ChatItem.Thought && item.text.isEmpty()) return
        items += item
    }

    private fun nextKey(): String = "i${counter++}"

    companion object {
        private const val MAX_OUTPUT = 4_000
        private const val MAX_DETAIL = 240
        private val PREFERRED = listOf("command", "cmd", "file_path", "path", "pattern", "url", "query", "description", "prompt")
        private val json = Json { ignoreUnknownKeys = true }
        private val QUESTION_TOOLS = setOf("ask_user_question", "AskUserQuestion")

        /** Pi sends one question at the top level; Claude sends a `questions` list. */
        fun questions(input: JsonObject?): List<ChatItem.Ask> {
            if (input == null) return emptyList()
            val list = (input["questions"] as? JsonArray)?.objects() ?: listOf(input)
            return list.mapNotNull { q ->
                val question = q.str("question")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val options = (q["options"] as? JsonArray)?.objects().orEmpty().mapNotNull { o ->
                    o.str("label")?.let { ChatItem.Option(it, o.str("description")) }
                }
                ChatItem.Ask(question, q.str("details"), options, q.bool("multiSelect") == true, q.str("header"))
            }
        }

        /** The one-line summary of a tool call: its command, path or query when it has one. */
        fun describe(input: JsonElement?): String {
            val text = when (input) {
                null -> ""
                is JsonPrimitive -> input.contentOrNull.orEmpty()
                is JsonArray -> input.joinToString(" ") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
                is JsonObject -> PREFERRED.firstNotNullOfOrNull { key -> input[key]?.let(::flat) }
                    ?: input.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: ""
            }
            return text.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(MAX_DETAIL) +
                if (text.trim().lines().size > 1) " …" else ""
        }

        private fun flat(element: JsonElement): String? = when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.joinToString(" ") { (it as? JsonPrimitive)?.contentOrNull ?: "" }
            else -> null
        }

        /** Text from a string, or from an array of `{type: text|input_text|output_text, text}` blocks. */
        fun textOf(element: JsonElement?): String? = when (element) {
            null -> null
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> element.objects().mapNotNull { it.str("text") }.joinToString("\n")
            is JsonObject -> element.str("text") ?: element.str("output")
        }

        private fun JsonArray.objects(): List<JsonObject> = filterIsInstance<JsonObject>()
        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    }
}
