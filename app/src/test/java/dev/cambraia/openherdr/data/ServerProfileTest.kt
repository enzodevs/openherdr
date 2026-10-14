package dev.cambraia.openherdr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerProfileTest {
    @Test
    fun `valid profiles pass`() {
        assertNull(ServerProfile.validate("10.13.13.1", "22", "rrghost", ""))
        assertNull(ServerProfile.validate("wg-host.example.dev", "2222", "dev_user", "agents"))
        assertNull(ServerProfile.validate("fe80::1", "22", "u", ""))
    }

    @Test
    fun `invalid fields are explained`() {
        assertNotNull(ServerProfile.validate("", "22", "u", ""))
        assertNotNull(ServerProfile.validate("host; rm -rf /", "22", "u", ""))
        assertNotNull(ServerProfile.validate("h", "0", "u", ""))
        assertNotNull(ServerProfile.validate("h", "70000", "u", ""))
        assertNotNull(ServerProfile.validate("h", "x", "u", ""))
        assertNotNull(ServerProfile.validate("h", "22", "", ""))
        assertNotNull(ServerProfile.validate("h", "22", "u", "../etc"))
    }

    @Test
    fun `target trims input and drops a blank session`() {
        val target = ServerProfile(name = "pc", host = " 10.13.13.1 ", user = " rrghost ", herdrSession = " ").target
        assertEquals("10.13.13.1", target.host)
        assertEquals("rrghost", target.user)
        assertNull(target.herdrSession)
        assertEquals("10.13.13.1", target.hostId)
        assertEquals("[h]:2222", ServerProfile(name = "x", host = "h", port = 2222, user = "u").target.hostId)
    }
}
