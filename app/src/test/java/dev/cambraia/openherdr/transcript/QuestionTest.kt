package dev.cambraia.openherdr.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool calls are real session lines (Pi `ask_user_question`, Claude `AskUserQuestion`) with the text replaced. */
class QuestionTest {
    private val piCall = """{"type":"message","id":"469debf6","parentId":"35976a90","timestamp":"2026-10-06T22:41:46.146Z","message":{"role":"assistant","content":[{"type":"toolCall","id":"call_fixture1","name":"ask_user_question","arguments":{"question":"Which license should the recipes use?","details":"MIT keeps contribution simple.","options":[{"label":"Use MIT","value":"mit"},{"label":"Use CC BY 4.0","value":"cc"},{"label":"Prepare without publishing","value":"prepare-only"}]}}]}}"""
    private val piResult = """{"type":"message","id":"5a0e","parentId":"469debf6","timestamp":"2026-10-06T22:42:10.000Z","message":{"role":"toolResult","toolCallId":"call_fixture1","toolName":"ask_user_question","content":[{"type":"text","text":"User selected: 1. Use MIT"}],"details":{"status":"answered","mode":"single-select","answers":[{"type":"option","label":"Use MIT","value":"mit","index":1}]},"isError":false}}"""
    private val claudeCall = """{"type":"assistant","isSidechain":false,"message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_fixture2","name":"AskUserQuestion","input":{"questions":[{"question":"What can I clean now?","header":"Cleanup","multiSelect":true,"options":[{"label":"Docker build cache","description":"docker builder prune -af"},{"label":"Package caches","description":"Downloaded again when needed"}]}]}}]}}"""
    private val claudeResult = """{"type":"user","isSidechain":false,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_fixture2","content":"Your questions have been answered: \"What can I clean now?\"=\"Package caches\". You can now continue with these answers in mind."}]}}"""

    private fun parse(format: TranscriptFormat, vararg lines: String) = ChatLog(format).apply { lines.forEach(::append) }.snapshot()

    @Test
    fun `a Pi question becomes a pending question card, then shows the answer`() {
        val pending = parse(TranscriptFormat.Pi, piCall).single() as ChatItem.Question
        assertFalse(pending.finished)
        assertTrue(pending.simple)
        val ask = pending.questions.single()
        assertEquals("Which license should the recipes use?", ask.question)
        assertEquals("MIT keeps contribution simple.", ask.details)
        assertEquals(listOf("Use MIT", "Use CC BY 4.0", "Prepare without publishing"), ask.options.map { it.label })

        val done = parse(TranscriptFormat.Pi, piCall, piResult).single() as ChatItem.Question
        assertEquals("User selected: 1. Use MIT", done.answer)
        assertFalse(done.failed)
    }

    @Test
    fun `a Claude question keeps header and descriptions, and multi-select is not simple`() {
        val q = parse(TranscriptFormat.Claude, claudeCall).single() as ChatItem.Question
        val ask = q.questions.single()
        assertEquals("Cleanup", ask.header)
        assertTrue(ask.multiSelect)
        assertEquals("docker builder prune -af", ask.options[0].description)
        assertFalse(q.simple)
        val done = parse(TranscriptFormat.Claude, claudeCall, claudeResult).single() as ChatItem.Question
        assertTrue(done.answer!!.contains("Package caches"))
    }

    @Test
    fun `several Claude questions at once are not simple`() {
        val two = claudeCall.replace("\"multiSelect\":true", "\"multiSelect\":false")
            .replace("]}]}}]}}", "]},{\"question\":\"And the logs?\",\"header\":\"Logs\",\"options\":[{\"label\":\"Keep\"},{\"label\":\"Delete\"}]}]}}]}}")
        val q = parse(TranscriptFormat.Claude, two).single() as ChatItem.Question
        assertEquals(2, q.questions.size)
        assertFalse(q.simple)
    }

    @Test
    fun `other tools stay tool cards`() {
        val bash = piCall.replace("ask_user_question", "bash")
        assertTrue(parse(TranscriptFormat.Pi, bash).single() is ChatItem.Tool)
    }

    // Screens as drawn by pi-core's choices.ts (› cursor) and Claude Code (❯ cursor).
    private val ask = (parse(TranscriptFormat.Pi, piCall).single() as ChatItem.Question).questions.single()

    private val piScreen = """
        ✓ read README.md
        Choose one answer
        Which license should the recipes
        use?
        MIT keeps contribution simple.
        › 1. Use MIT
          2. Use CC BY 4.0
          3. Prepare without publishing
          4. Other
        ↑↓ choose · Enter select · Tab context · Esc cancel
    """.trimIndent()

    @Test
    fun `option i is down i times then enter when the cursor is on the first option`() {
        assertEquals(listOf("enter"), QuestionKeys.choose(ask, 0, piScreen))
        assertEquals(listOf("down", "down", "enter"), QuestionKeys.choose(ask, 2, piScreen))
        val claude = piScreen.replace("› 1.", "❯ 1.")
        assertEquals(listOf("down", "enter"), QuestionKeys.choose(ask, 1, claude))
    }

    @Test
    fun `nothing is sent when the cursor moved off the first option`() {
        val moved = piScreen.replace("› 1. Use MIT", "  1. Use MIT").replace("  2. Use CC BY 4.0", "› 2. Use CC BY 4.0")
        assertNull(QuestionKeys.choose(ask, 0, moved))
    }

    @Test
    fun `nothing is sent when the question is no longer on screen`() {
        val answered = "✓ 1. Use MIT\n\n› \n"
        assertNull(QuestionKeys.choose(ask, 0, answered))
        val scrolledAway = piScreen + "\n" + List(100) { "output line $it" }.joinToString("\n")
        assertNull(QuestionKeys.choose(ask, 0, scrolledAway))
    }

    @Test
    fun `an option index outside the list is refused`() {
        assertNull(QuestionKeys.choose(ask, 3, piScreen))
        assertNull(QuestionKeys.choose(ask, -1, piScreen))
    }
}
