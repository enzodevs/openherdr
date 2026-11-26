package dev.cambraia.openherdr.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real session lines from Claude Code, Codex and Pi with the text replaced. */
class ChatLogTest {
    private fun load(name: String, format: TranscriptFormat): List<ChatItem> {
        val log = ChatLog(format)
        javaClass.getResource("/transcripts/$name")!!.readText().lines().forEach(log::append)
        return log.snapshot()
    }

    @Test
    fun claudeConversationWithToolResultAndHiddenNotices() {
        val items = load("claude.jsonl", TranscriptFormat.Claude)
        assertEquals(
            listOf(
                ChatItem.User("i0", "Fix the login bug"),
                ChatItem.Tool("tool-toolu_fixture1", "Bash", "./gradlew test", output = "BUILD SUCCESSFUL"),
                ChatItem.Thought("i1", "Check the session handling"),
                ChatItem.Assistant("i2", "Fixed. **All tests pass.**"),
                // Typed while the agent was working: recorded as a queued command, not as a user line.
                ChatItem.User("i3", "Also update the changelog"),
            ),
            items,
        )
    }

    @Test
    fun claudeKeepsPromptsThatStartWithAnAngleBracket() {
        val log = ChatLog(TranscriptFormat.Claude)
        log.append("""{"type":"user","message":{"role":"user","content":"<div> is not closed in index.html"}}""")
        log.append("""{"type":"user","message":{"role":"user","content":"<system-reminder>internal</system-reminder>"}}""")
        assertEquals(listOf(ChatItem.User("i0", "<div> is not closed in index.html")), log.snapshot())
    }

    @Test
    fun codexUsesTypedUserMessageAndPairsBothToolKinds() {
        val items = load("codex.jsonl", TranscriptFormat.Codex)
        assertEquals(
            listOf(
                ChatItem.User("i0", "Add a dark theme"),
                ChatItem.Thought("i1", "Looking at the theme files"),
                ChatItem.Tool("tool-call_fixture1", "exec_command", "rg -n Theme src", output = "src/Theme.kt:1:object Theme"),
                ChatItem.Tool("tool-call_fixture2", "apply_patch", "*** Begin Patch …", output = "Success. Updated src/Theme.kt"),
                ChatItem.Assistant("i2", "Dark theme added."),
            ),
            items,
        )
    }

    @Test
    fun piConversationSkipsBrokenLines() {
        val items = load("pi.jsonl", TranscriptFormat.Pi)
        assertEquals(
            listOf(
                ChatItem.User("i0", "Rename the config flag"),
                ChatItem.Thought("i1", "Find the flag first"),
                ChatItem.Tool(
                    "tool-call_fixture|fc_fixture", "read", "/home/user/project/config.ts",
                    output = "export const flag = true",
                ),
                ChatItem.Assistant("i2", "Renamed `flag` to `enabled`."),
            ),
            items,
        )
    }

    @Test
    fun toolStaysRunningUntilItsResultArrivesAndErrorsAreKept() {
        val log = ChatLog(TranscriptFormat.Pi)
        log.append("""{"type":"message","message":{"role":"assistant","content":[{"type":"toolCall","id":"a","name":"bash","arguments":{"command":"make\nmake install"}}]}}""")
        val running = log.snapshot().single() as ChatItem.Tool
        assertEquals(false, running.finished)
        assertEquals("make …", running.detail)
        log.append("""{"type":"message","message":{"role":"toolResult","toolCallId":"a","content":[{"type":"text","text":"boom"}],"isError":true}}""")
        val done = log.snapshot().single() as ChatItem.Tool
        assertTrue(done.finished)
        assertTrue(done.failed)
    }

    @Test
    fun claudeSlashCommandIsShownAndUnmatchedResultIgnored() {
        val log = ChatLog(TranscriptFormat.Claude)
        log.append("""{"type":"user","message":{"role":"user","content":"<command-name>/compact</command-name>\n<command-args>keep tests</command-args>"}}""")
        log.append("""{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"nope","content":"x"}]}}""")
        log.append("""{"type":"user","isSidechain":true,"message":{"role":"user","content":"subagent prompt"}}""")
        log.append("not json")
        assertEquals(listOf(ChatItem.User("i0", "/compact keep tests")), log.snapshot())
    }
}
