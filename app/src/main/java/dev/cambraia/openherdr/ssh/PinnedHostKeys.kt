package dev.cambraia.openherdr.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import kotlinx.serialization.Serializable
import java.util.Base64

/** A pinned server host key: the SSH key type and the base64 public key blob. */
@Serializable
data class HostKeyPin(val keyType: String, val keyBase64: String) {
    val fingerprint: String
        get() = SshWire.fingerprint(Base64.getDecoder().decode(keyBase64))
}

interface PinStore {
    fun get(hostId: String): HostKeyPin?
    fun put(hostId: String, pin: HostKeyPin)
    fun remove(hostId: String)
}

/**
 * Trust-on-first-use host key repository for JSch.
 *
 * Unknown hosts are rejected (with StrictHostKeyChecking=yes) so the UI can show the
 * fingerprint and let the user pin it explicitly. A pinned host only accepts the exact
 * pinned key; any other key, including a different key type, is reported as CHANGED.
 * JSch passes non-default ports as `[host]:port`, which keeps pins per host and port.
 */
class PinnedHostKeys(private val store: PinStore) : HostKeyRepository {

    /** The last key the server presented, so callers can report what was rejected. */
    @Volatile
    var lastSeen: Pair<String, HostKeyPin>? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val seen = HostKeyPin(SshWire.readFirstString(key), Base64.getEncoder().encodeToString(key))
        lastSeen = host to seen
        val pinned = store.get(host) ?: return HostKeyRepository.NOT_INCLUDED
        return if (pinned == seen) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        // Pins are only written after explicit user confirmation, never implicitly by JSch.
    }

    override fun remove(host: String, type: String?) {
        store.remove(host)
    }

    override fun remove(host: String, type: String?, key: ByteArray?) {
        store.remove(host)
    }

    override fun getKnownHostsRepositoryID(): String = "openherdr-pins"

    override fun getHostKey(): Array<HostKey> = emptyArray()

    /** Lets JSch prefer the pinned algorithm so a pinned host negotiates the same key type. */
    override fun getHostKey(host: String?, type: String?): Array<HostKey> {
        val pin = host?.let(store::get) ?: return emptyArray()
        if (type != null && type != pin.keyType) return emptyArray()
        return arrayOf(HostKey(host, Base64.getDecoder().decode(pin.keyBase64)))
    }
}
