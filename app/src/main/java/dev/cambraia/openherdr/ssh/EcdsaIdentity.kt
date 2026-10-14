package dev.cambraia.openherdr.ssh

import com.jcraft.jsch.Identity
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey

/**
 * JSch identity backed by a P-256 key whose private half is only reachable through
 * [Signature] (an Android Keystore key in production; it is never exported).
 */
class EcdsaIdentity(
    publicKey: ECPublicKey,
    private val privateKey: PrivateKey,
    private val label: String,
) : Identity {
    private val blob = SshWire.ecdsaPublicKeyBlob(publicKey)

    val authorizedKeyLine: String
        get() = SshWire.authorizedKeyLine(blob, label)

    val fingerprint: String
        get() = SshWire.fingerprint(blob)

    override fun getPublicKeyBlob(): ByteArray = blob.copyOf()

    override fun getSignature(data: ByteArray): ByteArray? = try {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
        SshWire.ecdsaSignatureBlob(der)
    } catch (e: Exception) {
        // JSch treats null as "this identity cannot sign"; keep the cause for diagnostics.
        lastSignError = e
        null
    }

    /** Why the last signature failed (for example a Keystore error), if it did. */
    @Volatile
    var lastSignError: Exception? = null
        private set

    override fun getAlgName(): String = SshWire.ECDSA_P256

    override fun getName(): String = label

    override fun isEncrypted(): Boolean = false

    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun clear() = Unit
}
