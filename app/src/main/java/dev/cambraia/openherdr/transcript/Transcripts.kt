package dev.cambraia.openherdr.transcript

import dev.cambraia.openherdr.protocol.Agent
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Files on the herdr host (SFTP): reads for conversations, one write for attachments. Blocking. */
interface RemoteFiles {
    val home: String

    /** Size in bytes, or null when the file does not exist. */
    fun size(path: String): Long?

    fun read(path: String, offset: Long, maxBytes: Int): ByteArray

    /** Entry names in [dir]; empty when it does not exist. */
    fun list(dir: String): List<String>

    /** Stores [bytes] in a private temporary folder on the host and returns the file's path. */
    fun upload(name: String, bytes: ByteArray): String = throw UnsupportedOperationException("Uploads are not supported")
}

data class TranscriptSource(val format: TranscriptFormat, val path: String)

/**
 * Finds the session file of an agent from the session reference herdr's integrations report
 * (`agent_session`): Claude Code and Codex report an id, Pi reports the file path.
 */
object TranscriptLocator {
    private val SAFE_ID = Regex("[A-Za-z0-9-]{8,80}")
    private val DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC)

    fun format(agent: Agent): TranscriptFormat? {
        val name = (agent.session?.agent ?: agent.agent ?: "").lowercase()
        return when {
            name.startsWith("claude") -> TranscriptFormat.Claude
            name.startsWith("codex") -> TranscriptFormat.Codex
            name == "pi" -> TranscriptFormat.Pi
            else -> null
        }
    }

    fun locate(agent: Agent, files: RemoteFiles): TranscriptSource? {
        val session = agent.session ?: return null
        val format = format(agent) ?: return null
        if (session.kind == "path") {
            val path = session.value
            return if (path.startsWith("/") && path.endsWith(".jsonl") && ".." !in path && files.size(path) != null) {
                TranscriptSource(format, path)
            } else {
                null
            }
        }
        val id = session.value.takeIf { SAFE_ID.matches(it) } ?: return null
        val home = files.home.trimEnd('/')
        val path = when (format) {
            TranscriptFormat.Claude -> claude(home, id, listOfNotNull(agent.cwd, agent.foregroundCwd), files)
            TranscriptFormat.Codex -> codex(home, id, files)
            TranscriptFormat.Pi -> null
        } ?: return null
        return TranscriptSource(format, path)
    }

    /** Claude Code names project folders after the working directory with every non-alphanumeric as '-'. */
    fun claudeProjectDir(cwd: String): String = cwd.replace(Regex("[^A-Za-z0-9]"), "-")

    private fun claude(home: String, id: String, cwds: List<String>, files: RemoteFiles): String? {
        val projects = "$home/.claude/projects"
        cwds.distinct().forEach { cwd ->
            val path = "$projects/${claudeProjectDir(cwd)}/$id.jsonl"
            if (files.size(path) != null) return path
        }
        // The agent may have changed directory since it started; look through every project.
        return files.list(projects).asSequence().take(MAX_SCAN)
            .map { "$projects/$it/$id.jsonl" }
            .firstOrNull { files.size(it) != null }
    }

    /** Codex thread ids are UUIDv7, so the id itself says which day folder the rollout is in. */
    private fun codex(home: String, id: String, files: RemoteFiles): String? {
        val sessions = "$home/.codex/sessions"
        val days = uuidV7Millis(id)?.let { ms -> (-1..1).map { DAY.format(Instant.ofEpochMilli(ms + it * 86_400_000L)) } }
            ?: return null
        days.distinct().forEach { day ->
            val dir = "$sessions/$day"
            files.list(dir).firstOrNull { it.endsWith("-$id.jsonl") }?.let { return "$dir/$it" }
        }
        return null
    }

    fun uuidV7Millis(id: String): Long? {
        val hex = id.replace("-", "")
        if (hex.length != 32 || hex[12] != '7') return null
        return hex.substring(0, 12).toLongOrNull(16)
    }

    private const val MAX_SCAN = 300
}

/**
 * Follows a growing JSONL file: the first [poll] reads the last [initialBytes], later polls read
 * only what was appended. Returns complete lines; a trailing partial line waits for the next poll.
 */
class TranscriptTail(
    private val files: RemoteFiles,
    private val path: String,
    private val initialBytes: Int = 1_000_000,
    private val chunkBytes: Int = 1_000_000,
) {
    private var offset = -1L
    private val pending = ByteArrayOutputStream()

    /** True once when the file shrank or was replaced; callers should start a fresh chat. */
    var restarted = false
        private set

    fun poll(): List<String> {
        restarted = false
        val size = files.size(path) ?: return emptyList()
        var skipFirst = false
        if (offset < 0 || size < offset) {
            restarted = offset >= 0
            offset = (size - initialBytes).coerceAtLeast(0)
            skipFirst = offset > 0
            pending.reset()
        }
        val lines = ArrayList<String>()
        while (offset < size) {
            val bytes = files.read(path, offset, minOf(chunkBytes.toLong(), size - offset).toInt())
            if (bytes.isEmpty()) break
            offset += bytes.size
            var start = 0
            for (i in bytes.indices) {
                if (bytes[i] == '\n'.code.toByte()) {
                    pending.write(bytes, start, i - start)
                    if (skipFirst) skipFirst = false else lines += pending.toString(Charsets.UTF_8)
                    pending.reset()
                    start = i + 1
                }
            }
            pending.write(bytes, start, bytes.size - start)
        }
        return lines.filter { it.isNotBlank() }
    }
}

/** A live chat for one session file: each [poll] returns the whole chat when it changed, else null. Blocking. */
class ChatFeed(files: RemoteFiles, private val source: TranscriptSource) {
    private val tail = TranscriptTail(files, source.path)
    private var log = ChatLog(source.format)
    private var first = true

    fun poll(): List<ChatItem>? {
        val lines = tail.poll()
        if (tail.restarted) log = ChatLog(source.format)
        if (lines.isEmpty() && !tail.restarted && !first) return null
        first = false
        lines.forEach(log::append)
        return log.snapshot()
    }
}
