package dev.cambraia.openherdr.it

import dev.cambraia.openherdr.protocol.AgentStatus
import dev.cambraia.openherdr.protocol.HerdrClient
import dev.cambraia.openherdr.ssh.EcdsaIdentity
import dev.cambraia.openherdr.ssh.HostKeyPin
import dev.cambraia.openherdr.ssh.MemoryPinStore
import dev.cambraia.openherdr.ssh.SshFailure
import dev.cambraia.openherdr.ssh.SshSession
import dev.cambraia.openherdr.ssh.SshTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * End-to-end over a real sshd and a real herdr server. Needs scripts/it-sshd.sh running and a
 * herdr session with an agent labelled "fake" (scripts/fake-agent.sh). See AGENTS.md.
 */
class SshHerdrIntegrationTest {
    private val port = System.getenv("OPENHERDR_IT_PORT")?.toIntOrNull()
    private val stateDir = System.getenv("OPENHERDR_IT_DIR")?.let(::File)
    private val herdrSession = System.getenv("OPENHERDR_IT_HERDR_SESSION")

    @Before
    fun requireEnvironment() {
        assumeTrue("set OPENHERDR_IT_PORT and OPENHERDR_IT_DIR to run", port != null && stateDir != null)
    }

    private fun newIdentity(authorize: Boolean): EcdsaIdentity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val identity = EcdsaIdentity(pair.public as ECPublicKey, pair.private, "openherdr-it")
        if (authorize) File(stateDir, "authorized_keys").appendText(identity.authorizedKeyLine + "\n")
        return identity
    }

    private val target get() = SshTarget("127.0.0.1", port!!, System.getProperty("user.name"), herdrSession)

    private fun expectedHostFingerprint(): String {
        val out = ProcessBuilder("ssh-keygen", "-lf", File(stateDir, "host_ed25519.pub").path).start()
            .inputStream.bufferedReader().readText()
        return out.split(" ")[1]
    }

    private fun pinnedStore(): MemoryPinStore {
        val store = MemoryPinStore()
        val unknown = runCatching { SshSession.connect(target, newIdentity(true), store) }.exceptionOrNull()
        store.put(target.hostId, (unknown as SshFailure.UnknownHostKey).pin)
        return store
    }

    @Test
    fun `first contact reports the server fingerprint and trusts nothing`() {
        val failure = runCatching { SshSession.connect(target, newIdentity(true), MemoryPinStore()) }.exceptionOrNull()
        assertTrue("got $failure", failure is SshFailure.UnknownHostKey)
        val pin = (failure as SshFailure.UnknownHostKey).pin
        assertEquals("ssh-ed25519", pin.keyType)
        assertEquals(expectedHostFingerprint(), pin.fingerprint)
        assertEquals("[127.0.0.1]:$port", failure.hostId)
    }

    @Test
    fun `a different pinned key is refused as changed`() {
        val store = pinnedStore()
        val real = store.get(target.hostId)!!
        val forged = java.util.Base64.getDecoder().decode(real.keyBase64).also { it[it.size - 1] = (it.last() + 1).toByte() }
        store.put(target.hostId, HostKeyPin(real.keyType, java.util.Base64.getEncoder().encodeToString(forged)))
        val failure = runCatching { SshSession.connect(target, newIdentity(true), store) }.exceptionOrNull()
        assertTrue("got $failure", failure is SshFailure.HostKeyChanged)
    }

    @Test
    fun `an unauthorized device key is rejected`() {
        val store = pinnedStore()
        val failure = runCatching { SshSession.connect(target, newIdentity(false), store) }.exceptionOrNull()
        assertTrue("got $failure", failure is SshFailure.AuthRejected)
    }

    @Test
    fun `hardware-style identity talks to herdr through streamlocal`() = runBlocking {
        val store = pinnedStore()
        SshSession.connect(target, newIdentity(true), store).use { session ->
            val client = HerdrClient(session, Dispatchers.IO)
            assertEquals(22, client.ping())

            val fake = client.agents().firstOrNull { it.agent == "fake" }
            assumeTrue("start scripts/fake-agent.sh in the herdr session", fake != null)
            val pane = fake!!.paneId
            val marker = "it-${System.nanoTime()}"

            val working = async(Dispatchers.IO) {
                withTimeout(10_000) { client.subscribe(listOf(pane)).first { it.paneId == pane && it.status == AgentStatus.Working } }
            }
            delay(500) // let the subscription start before triggering the change
            client.submit(pane, marker)
            assertEquals(AgentStatus.Working, working.await().status)

            withTimeout(10_000) {
                while (!client.read(pane, 40).text.contains("got: $marker")) delay(200)
            }
        }
    }
}
