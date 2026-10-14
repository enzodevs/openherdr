package dev.cambraia.openherdr.ssh

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class MemoryPinStore : PinStore {
    val pins = mutableMapOf<String, HostKeyPin>()
    override fun get(hostId: String) = pins[hostId]
    override fun put(hostId: String, pin: HostKeyPin) { pins[hostId] = pin }
    override fun remove(hostId: String) { pins.remove(hostId) }
}

class PinnedHostKeysTest {
    private val ed = Base64.getDecoder().decode(SshWireTest.ED25519_HOST_KEY)
    private val otherEd = ed.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }

    @Test
    fun `unknown host is not included and is remembered as last seen`() {
        val repo = PinnedHostKeys(MemoryPinStore())
        assertEquals(HostKeyRepository.NOT_INCLUDED, repo.check("10.0.0.2", ed))
        assertEquals("10.0.0.2", repo.lastSeen!!.first)
        assertEquals("ssh-ed25519", repo.lastSeen!!.second.keyType)
    }

    @Test
    fun `pinned key is accepted and a different key is reported as changed`() {
        val store = MemoryPinStore()
        store.put("h", HostKeyPin("ssh-ed25519", Base64.getEncoder().encodeToString(ed)))
        val repo = PinnedHostKeys(store)
        assertEquals(HostKeyRepository.OK, repo.check("h", ed))
        assertEquals(HostKeyRepository.CHANGED, repo.check("h", otherEd))
    }

    @Test
    fun `pins are per host id so ports do not share trust`() {
        val store = MemoryPinStore()
        store.put("h", HostKeyPin("ssh-ed25519", Base64.getEncoder().encodeToString(ed)))
        assertEquals(HostKeyRepository.NOT_INCLUDED, PinnedHostKeys(store).check("[h]:2222", ed))
    }

    @Test
    fun `JSch is told the pinned algorithm only`() {
        val store = MemoryPinStore()
        store.put("h", HostKeyPin("ssh-ed25519", Base64.getEncoder().encodeToString(ed)))
        val repo = PinnedHostKeys(store)
        assertEquals(1, repo.getHostKey("h", null).size)
        assertEquals("ssh-ed25519", repo.getHostKey("h", null)[0].type)
        assertEquals(0, repo.getHostKey("h", "ecdsa-sha2-nistp256").size)
        assertEquals(0, repo.getHostKey("other", null).size)
    }

    @Test
    fun `JSch add never writes a pin`() {
        val store = MemoryPinStore()
        PinnedHostKeys(store).add(com.jcraft.jsch.HostKey("h", ed), null)
        assertNull(store.get("h"))
    }
}
