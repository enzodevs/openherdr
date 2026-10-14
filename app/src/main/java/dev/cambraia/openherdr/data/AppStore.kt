package dev.cambraia.openherdr.data

import androidx.core.util.AtomicFile
import dev.cambraia.openherdr.ssh.HostKeyPin
import dev.cambraia.openherdr.ssh.PinStore
import dev.cambraia.openherdr.ssh.SshTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val herdrSession: String? = null,
) {
    val target: SshTarget
        get() = SshTarget(host.trim(), port, user.trim(), herdrSession?.trim()?.takeIf { it.isNotEmpty() })

    companion object {
        private val HOST = Regex("^[A-Za-z0-9.:\\-\\[\\]]+$")
        private val USER = Regex("^[a-z_][a-z0-9_.-]*\\$?$", RegexOption.IGNORE_CASE)
        private val SESSION = Regex("^[A-Za-z0-9._-]*$")

        /** Returns a user-facing problem with the fields, or null when they are valid. */
        fun validate(host: String, port: String, user: String, herdrSession: String): String? = when {
            host.isBlank() -> "Host is required"
            !HOST.matches(host.trim()) -> "Host has invalid characters"
            port.toIntOrNull() !in 1..65535 -> "Port must be 1–65535"
            user.isBlank() -> "User is required"
            !USER.matches(user.trim()) -> "User has invalid characters"
            !SESSION.matches(herdrSession.trim()) -> "Session may only use letters, digits, '.', '_' and '-'"
            else -> null
        }
    }
}

@Serializable
data class StoreState(
    val profiles: List<ServerProfile> = emptyList(),
    val pins: Map<String, HostKeyPin> = emptyMap(),
)

/** Profiles and host key pins, persisted atomically as one small JSON file. */
class AppStore(dir: File) : PinStore {
    private val file = AtomicFile(File(dir, "openherdr-state.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val current = MutableStateFlow(load())

    val state: StateFlow<StoreState> = current.asStateFlow()

    fun upsert(profile: ServerProfile) = mutate { s ->
        val others = s.profiles.filterNot { it.id == profile.id }
        val index = s.profiles.indexOfFirst { it.id == profile.id }
        s.copy(profiles = if (index < 0) others + profile else others.toMutableList().apply { add(index, profile) })
    }

    fun delete(id: String) = mutate { s -> s.copy(profiles = s.profiles.filterNot { it.id == id }) }

    override fun get(hostId: String): HostKeyPin? = current.value.pins[hostId]

    override fun put(hostId: String, pin: HostKeyPin) = mutate { s -> s.copy(pins = s.pins + (hostId to pin)) }

    override fun remove(hostId: String) = mutate { s -> s.copy(pins = s.pins - hostId) }

    @Synchronized
    private fun mutate(change: (StoreState) -> StoreState) {
        current.update(change)
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(StoreState.serializer(), current.value).toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun load(): StoreState {
        val bytes = try {
            file.readFully()
        } catch (_: java.io.FileNotFoundException) {
            return StoreState()
        }
        return runCatching { json.decodeFromString(StoreState.serializer(), bytes.decodeToString()) }
            .getOrElse {
                // Keep the unreadable file for inspection instead of silently overwriting it.
                file.baseFile.copyTo(File(file.baseFile.path + ".corrupt-${System.currentTimeMillis()}"))
                StoreState()
            }
    }
}
