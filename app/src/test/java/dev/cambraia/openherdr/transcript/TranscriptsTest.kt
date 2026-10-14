package dev.cambraia.openherdr.transcript

import dev.cambraia.openherdr.protocol.Agent
import dev.cambraia.openherdr.protocol.AgentSessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeFiles(override val home: String = "/home/u") : RemoteFiles {
    val files = linkedMapOf<String, ByteArray>()
    val reads = mutableListOf<Pair<Long, Int>>()

    fun put(path: String, text: String) { files[path] = text.toByteArray() }

    override fun size(path: String): Long? = files[path]?.size?.toLong()

    override fun read(path: String, offset: Long, maxBytes: Int): ByteArray {
        reads += offset to maxBytes
        val bytes = files.getValue(path)
        return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + maxBytes))
    }

    override fun list(dir: String): List<String> =
        files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/").substringBefore('/') }.distinct().sorted()
}

class TranscriptsTest {
    private fun agent(name: String, kind: String, value: String, cwd: String? = "/home/u/my.app") = Agent(
        paneId = "w1:p1", workspaceId = "w1", tabId = "w1:t1", terminalId = "t", agent = name, cwd = cwd,
        session = AgentSessionRef(agent = name, kind = kind, value = value),
    )

    @Test
    fun claudeIdResolvesThroughTheProjectFolderNamedAfterCwd() {
        val files = FakeFiles().apply { put("/home/u/.claude/projects/-home-u-my-app/39f1790f-4114-4c9f-ad20-9b6649f0fb60.jsonl", "{}\n") }
        val source = TranscriptLocator.locate(agent("claude", "id", "39f1790f-4114-4c9f-ad20-9b6649f0fb60"), files)
        assertEquals(TranscriptSource(TranscriptFormat.Claude, "/home/u/.claude/projects/-home-u-my-app/39f1790f-4114-4c9f-ad20-9b6649f0fb60.jsonl"), source)
    }

    @Test
    fun claudeFallsBackToScanningProjectsWhenCwdMoved() {
        val files = FakeFiles().apply { put("/home/u/.claude/projects/-elsewhere/39f1790f-4114-4c9f-ad20-9b6649f0fb60.jsonl", "{}\n") }
        val source = TranscriptLocator.locate(agent("claude", "id", "39f1790f-4114-4c9f-ad20-9b6649f0fb60"), files)
        assertEquals("/home/u/.claude/projects/-elsewhere/39f1790f-4114-4c9f-ad20-9b6649f0fb60.jsonl", source?.path)
    }

    @Test
    fun codexThreadIdPointsAtItsDayFolder() {
        val id = "019f8c02-f209-7bb2-a74c-5011ae3695bf"
        val files = FakeFiles().apply {
            put("/home/u/.codex/sessions/2026/07/22/rollout-2026-07-22T19-47-11-$id.jsonl", "{}\n")
            put("/home/u/.codex/sessions/2026/07/22/rollout-2026-07-22T10-00-00-019f0000-0000-7000-8000-000000000000.jsonl", "{}\n")
        }
        val source = TranscriptLocator.locate(agent("codex", "id", id), files)
        assertEquals("/home/u/.codex/sessions/2026/07/22/rollout-2026-07-22T19-47-11-$id.jsonl", source?.path)
    }

    @Test
    fun piPathMustBeAnAbsoluteExistingJsonl() {
        val path = "/home/u/.pi/agent/sessions/--home-u--/s.jsonl"
        // A server resolves "..", so a traversal path can point at an existing file.
        val files = FakeFiles().apply {
            put(path, "{}\n")
            put("/home/u/../../etc/passwd.jsonl", "x\n")
            put("/etc/passwd", "x\n")
        }
        assertEquals(TranscriptSource(TranscriptFormat.Pi, path), TranscriptLocator.locate(agent("pi", "path", path), files))
        assertNull(TranscriptLocator.locate(agent("pi", "path", "/home/u/../../etc/passwd.jsonl"), files))
        assertNull(TranscriptLocator.locate(agent("pi", "path", "/etc/passwd"), files))
    }

    @Test
    fun unsafeIdsAndUnknownAgentsAreRejected() {
        val files = FakeFiles()
        assertNull(TranscriptLocator.locate(agent("claude", "id", "../../x"), files))
        assertNull(TranscriptLocator.locate(agent("opencode", "id", "39f1790f-4114-4c9f-ad20-9b6649f0fb60"), files))
        assertNull(TranscriptLocator.locate(agent("claude", "id", "39f1790f-4114-4c9f-ad20-9b6649f0fb60").copy(session = null), files))
    }

    @Test
    fun claudeFolderNameReplacesEveryNonAlphanumeric() {
        assertEquals("-home-user-SoftEng-my-app", TranscriptLocator.claudeProjectDir("/home/user/SoftEng/my_app"))
    }

    @Test
    fun uuidV7TimestampIsDecoded() {
        assertEquals(0x019f8c02f209L, TranscriptLocator.uuidV7Millis("019f8c02-f209-7bb2-a74c-5011ae3695bf"))
        assertNull(TranscriptLocator.uuidV7Millis("39f1790f-4114-4c9f-ad20-9b6649f0fb60"))
    }

    @Test
    fun tailReadsTheEndFirstThenOnlyAppendedCompleteLines() {
        val files = FakeFiles()
        files.put("/f", "aaaa\nbbbb\ncc")
        val tail = TranscriptTail(files, "/f", initialBytes = 7, chunkBytes = 3)
        // Starts mid-file at offset 6: the partial "bbb" line is dropped, "cc" waits for its newline.
        assertEquals(emptyList<String>(), tail.poll())
        files.put("/f", "aaaa\nbbbb\ncc\ndd\n")
        assertEquals(listOf("cc", "dd"), tail.poll())
        assertEquals(emptyList<String>(), tail.poll())
        assertFalse(tail.restarted)
    }

    @Test
    fun tailStartsOverWhenTheFileShrinks() {
        val files = FakeFiles()
        files.put("/f", "one\ntwo\n")
        val tail = TranscriptTail(files, "/f")
        assertEquals(listOf("one", "two"), tail.poll())
        files.put("/f", "new\n")
        assertEquals(listOf("new"), tail.poll())
        assertTrue(tail.restarted)
    }

    @Test
    fun tailKeepsMultibyteCharactersSplitAcrossChunks() {
        val files = FakeFiles()
        files.put("/f", "ação ✓\n")
        val tail = TranscriptTail(files, "/f", chunkBytes = 2)
        assertEquals(listOf("ação ✓"), tail.poll())
    }
}

class ChatFeedTest {
    @Test
    fun reportsOnlyChangesAndResetsWhenTheFileIsReplaced() {
        val files = FakeFiles()
        val path = "/home/u/.pi/agent/sessions/x/s.jsonl"
        files.put(path, "")
        val feed = ChatFeed(files, TranscriptSource(TranscriptFormat.Pi, path))
        assertEquals(emptyList<ChatItem>(), feed.poll())
        assertNull(feed.poll())
        val hello = """{"type":"message","message":{"role":"user","content":[{"type":"text","text":"hello"}]}}"""
        files.put(path, hello + "\n")
        assertEquals(listOf(ChatItem.User("i0", "hello")), feed.poll())
        assertNull(feed.poll())
        files.put(path, "")
        assertEquals(emptyList<ChatItem>(), feed.poll())
    }
}
