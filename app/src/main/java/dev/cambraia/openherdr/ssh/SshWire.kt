package dev.cambraia.openherdr.ssh

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.Base64

/** RFC 4251 wire encoding helpers and RFC 5656 ECDSA key/signature formats. */
object SshWire {
    const val ECDSA_P256 = "ecdsa-sha2-nistp256"
    private const val CURVE = "nistp256"
    private const val COORDINATE_BYTES = 32

    fun string(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(uint32(bytes.size))
            write(bytes)
        }.toByteArray()

    fun string(text: String): ByteArray = string(text.toByteArray(Charsets.UTF_8))

    /** Two's-complement big-endian with minimal length, as SSH `mpint`. */
    fun mpint(value: BigInteger): ByteArray =
        if (value.signum() == 0) uint32(0) else string(value.toByteArray())

    fun uint32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    /** `string "ecdsa-sha2-nistp256" || string "nistp256" || string Q` with Q uncompressed. */
    fun ecdsaPublicKeyBlob(key: ECPublicKey): ByteArray {
        val point = byteArrayOf(0x04) +
            fixed(key.w.affineX, COORDINATE_BYTES) +
            fixed(key.w.affineY, COORDINATE_BYTES)
        return string(ECDSA_P256) + string(CURVE) + string(point)
    }

    /**
     * Converts a JCA DER `SEQUENCE { INTEGER r, INTEGER s }` into the SSH signature blob:
     * `string "ecdsa-sha2-nistp256" || string (mpint r || mpint s)`.
     */
    fun ecdsaSignatureBlob(der: ByteArray): ByteArray {
        val (r, s) = parseDerSignature(der)
        return string(ECDSA_P256) + string(mpint(r) + mpint(s))
    }

    fun parseDerSignature(der: ByteArray): Pair<BigInteger, BigInteger> {
        var pos = 0
        fun next(): Int {
            require(pos < der.size) { "truncated DER" }
            return der[pos++].toInt() and 0xff
        }
        fun readLength(): Int {
            val first = next()
            if (first < 0x80) return first
            val count = first and 0x7f
            require(count in 1..2) { "unsupported DER length" }
            var length = 0
            repeat(count) { length = (length shl 8) or next() }
            return length
        }
        fun readInteger(): BigInteger {
            require(next() == 0x02) { "expected DER INTEGER" }
            val length = readLength()
            require(length in 1..der.size - pos) { "truncated DER INTEGER" }
            return BigInteger(der.copyOfRange(pos, pos + length)).also { pos += length }
        }
        require(next() == 0x30) { "expected DER SEQUENCE" }
        val sequenceLength = readLength()
        require(sequenceLength == der.size - pos) { "DER length mismatch" }
        val r = readInteger()
        val s = readInteger()
        require(pos == der.size) { "trailing DER bytes" }
        require(r.signum() > 0 && s.signum() > 0) { "non-positive ECDSA component" }
        return r to s
    }

    /** `ecdsa-sha2-nistp256 AAAA... comment`, ready for `authorized_keys`. */
    fun authorizedKeyLine(blob: ByteArray, comment: String): String =
        "${readFirstString(blob)} ${Base64.getEncoder().encodeToString(blob)} $comment"

    /** OpenSSH-style `SHA256:...` fingerprint of a public key blob. */
    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** The key type name that every SSH public key blob starts with. */
    fun readFirstString(blob: ByteArray): String {
        require(blob.size >= 4) { "blob too short" }
        val length = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
            ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
        require(length in 1..blob.size - 4) { "bad key type length" }
        return String(blob, 4, length, Charsets.US_ASCII)
    }

    private fun fixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray().let { if (it.size > size && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        require(raw.size <= size) { "coordinate too large" }
        return ByteArray(size - raw.size) + raw
    }
}
