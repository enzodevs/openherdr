package dev.cambraia.openherdr.ssh

import com.jcraft.jsch.ChannelDirectStreamLocal
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import dev.cambraia.openherdr.protocol.SocketStream
import dev.cambraia.openherdr.data.HerdrLink
import java.io.InputStream
import java.io.OutputStream

data class SshTarget(val host: String, val port: Int, val user: String, val herdrSession: String? = null) {
    /** The host id JSch uses for host key lookups. */
    val hostId: String
        get() = if (port == 22) host else "[$host]:$port"
}

sealed class SshFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** First contact: the user must confirm [pin] before we trust this server. */
    class UnknownHostKey(val hostId: String, val pin: HostKeyPin) :
        SshFailure("Unknown host key ${pin.keyType} ${pin.fingerprint}")

    /** The server presented a key that does not match the pin. Possible interception. */
    class HostKeyChanged(val hostId: String, val expected: HostKeyPin, val actual: HostKeyPin) :
        SshFailure("Host key changed: expected ${expected.fingerprint}, got ${actual.fingerprint}")

    class AuthRejected(cause: Throwable) :
        SshFailure("The server rejected this device's key. Add the public key to ~/.ssh/authorized_keys.", cause)

    class Unreachable(cause: Throwable) : SshFailure(cause.message ?: "Connection failed", cause)
}

/**
 * One authenticated SSH session to a herdr host. Every herdr request opens its own
 * `direct-streamlocal@openssh.com` channel to the herdr socket, so nothing is installed
 * or executed on the server.
 */
class SshSession private constructor(
    private val session: Session,
    val socketPath: String,
) : HerdrLink {

    override val isConnected: Boolean
        get() = session.isConnected

    override fun open(): SocketStream {
        val channel = session.openChannel("direct-streamlocal@openssh.com") as ChannelDirectStreamLocal
        channel.socketPath = socketPath
        val outbound = QueueInputStream()
        channel.setInputStream(outbound)
        val inbound = channel.inputStream
        channel.connect(CHANNEL_TIMEOUT_MS)
        return object : SocketStream {
            override val input: InputStream = inbound
            override val output: OutputStream = outbound.sink
            override fun close() {
                outbound.close()
                channel.disconnect()
            }
        }
    }

    override fun close() {
        session.disconnect()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val CHANNEL_TIMEOUT_MS = 10_000

        /** Blocking. Throws [SshFailure]. */
        fun connect(target: SshTarget, identity: Identity, pins: PinStore): SshSession {
            val hostKeys = PinnedHostKeys(pins)
            val jsch = JSch().apply {
                hostKeyRepository = hostKeys
                addIdentity(identity, null)
            }
            val session = jsch.getSession(target.user, target.host, target.port).apply {
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", "publickey")
                serverAliveInterval = 15_000
                serverAliveCountMax = 3
            }
            try {
                session.connect(CONNECT_TIMEOUT_MS)
            } catch (e: JSchException) {
                throw classify(e, target, hostKeys, pins)
            }
            return try {
                SshSession(session, herdrSocketPath(homeDirectory(session), target.herdrSession))
            } catch (e: Exception) {
                session.disconnect()
                throw SshFailure.Unreachable(e)
            }
        }

        fun herdrSocketPath(home: String, herdrSession: String?): String {
            val base = "${home.trimEnd('/')}/.config/herdr"
            val name = herdrSession?.trim().orEmpty()
            require(name.isEmpty() || name.matches(Regex("[A-Za-z0-9._-]+"))) { "invalid herdr session name" }
            return if (name.isEmpty()) "$base/herdr.sock" else "$base/sessions/$name/herdr.sock"
        }

        /** Asks the SFTP subsystem for the login directory; no shell command runs. */
        private fun homeDirectory(session: Session): String {
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(CHANNEL_TIMEOUT_MS)
            try {
                return sftp.home
            } finally {
                sftp.disconnect()
            }
        }

        private fun classify(e: JSchException, target: SshTarget, hostKeys: PinnedHostKeys, pins: PinStore): SshFailure {
            val seen = hostKeys.lastSeen?.takeIf { it.first == target.hostId }?.second
            val pinned = pins.get(target.hostId)
            return when {
                seen != null && pinned == null -> SshFailure.UnknownHostKey(target.hostId, seen)
                seen != null && pinned != null && pinned != seen -> SshFailure.HostKeyChanged(target.hostId, pinned, seen)
                e.message?.contains("Auth fail", ignoreCase = true) == true -> SshFailure.AuthRejected(e)
                else -> SshFailure.Unreachable(e)
            }
        }
    }
}
