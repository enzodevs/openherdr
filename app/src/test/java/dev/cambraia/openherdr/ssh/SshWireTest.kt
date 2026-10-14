package dev.cambraia.openherdr.ssh

import com.jcraft.jsch.jce.SignatureECDSA256
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class SshWireTest {
    private fun p256() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test
    fun `identity signatures verify with JSch's independent ECDSA verifier`() {
        // Many iterations so both short (<32 byte) and high-bit (needs 0x00) r/s values occur.
        repeat(200) { i ->
            val pair = p256()
            val pub = pair.public as ECPublicKey
            val identity = EcdsaIdentity(pub, pair.private, "test")
            val data = "session-data-$i".toByteArray()
            val blob = identity.getSignature(data)!!

            val verifier = SignatureECDSA256().apply {
                init()
                setPubKey(pub.w.affineX.toByteArray(), pub.w.affineY.toByteArray())
                update(data)
            }
            assertTrue("iteration $i", verifier.verify(blob))
        }
    }

    @Test
    fun `signature over different data does not verify`() {
        val pair = p256()
        val pub = pair.public as ECPublicKey
        val blob = EcdsaIdentity(pub, pair.private, "t").getSignature("a".toByteArray())!!
        val verifier = SignatureECDSA256().apply {
            init()
            setPubKey(pub.w.affineX.toByteArray(), pub.w.affineY.toByteArray())
            update("b".toByteArray())
        }
        assertFalse(verifier.verify(blob))
    }

    @Test
    fun `mpint adds a zero byte when the high bit is set and is empty for zero`() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0, 0x80.toByte()), SshWire.mpint(BigInteger.valueOf(0x80)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x7f), SshWire.mpint(BigInteger.valueOf(0x7f)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), SshWire.mpint(BigInteger.ZERO))
    }

    @Test
    fun `public key blob has the RFC 5656 layout`() {
        val pub = p256().public as ECPublicKey
        val blob = SshWire.ecdsaPublicKeyBlob(pub)
        assertEquals("ecdsa-sha2-nistp256", SshWire.readFirstString(blob))
        // 4+19 type, 4+8 curve, 4+65 point
        assertEquals(4 + 19 + 4 + 8 + 4 + 65, blob.size)
        assertEquals(0x04.toByte(), blob[4 + 19 + 4 + 8 + 4])
    }

    @Test
    fun `authorized key line round-trips the blob`() {
        val blob = SshWire.ecdsaPublicKeyBlob(p256().public as ECPublicKey)
        val parts = SshWire.authorizedKeyLine(blob, "openherdr@phone").split(" ")
        assertEquals("ecdsa-sha2-nistp256", parts[0])
        assertArrayEquals(blob, Base64.getDecoder().decode(parts[1]))
        assertEquals("openherdr@phone", parts[2])
    }

    @Test
    fun `fingerprint matches OpenSSH format`() {
        // ssh-keygen -lf of this ed25519 public key prints SHA256:Qv4lubGt9N5dZ8HFV+J1CVao/XS7GTiPZoYscya+uHY
        val blob = Base64.getDecoder().decode(ED25519_HOST_KEY)
        assertEquals("SHA256:Qv4lubGt9N5dZ8HFV+J1CVao/XS7GTiPZoYscya+uHY", SshWire.fingerprint(blob))
    }

    @Test
    fun `malformed DER is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SshWire.parseDerSignature(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { SshWire.parseDerSignature(byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x01)) }
        // trailing garbage after s
        assertThrows(IllegalArgumentException::class.java) {
            SshWire.parseDerSignature(byteArrayOf(0x30, 0x07, 0x02, 0x01, 0x01, 0x02, 0x01, 0x01, 0x00))
        }
        // negative r
        assertThrows(IllegalArgumentException::class.java) {
            SshWire.parseDerSignature(byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x81.toByte(), 0x02, 0x01, 0x01))
        }
    }

    @Test
    fun `herdr socket path supports the default and named sessions`() {
        assertEquals("/home/u/.config/herdr/herdr.sock", SshSession.herdrSocketPath("/home/u/", null))
        assertEquals("/home/u/.config/herdr/sessions/dev/herdr.sock", SshSession.herdrSocketPath("/home/u", "dev"))
        assertThrows(IllegalArgumentException::class.java) { SshSession.herdrSocketPath("/home/u", "../x") }
    }

    companion object {
        const val ED25519_HOST_KEY = "AAAAC3NzaC1lZDI1NTE5AAAAIOEa/xZ56yT0DOzZBYyaCJP+cbFGcb7uPMA0anJWex8H"
    }
}
