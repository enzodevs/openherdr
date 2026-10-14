package dev.cambraia.openherdr.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import dev.cambraia.openherdr.ssh.EcdsaIdentity
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * The device's SSH key: P-256 generated inside the Android Keystore (StrongBox when the
 * phone has one). The private key never leaves secure hardware; we only ask it to sign.
 */
class DeviceKey {
    enum class Protection { StrongBox, TrustedEnvironment, Software, Unknown }

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun identity(): EcdsaIdentity {
        if (!keyStore.containsAlias(ALIAS)) generate()
        val publicKey = keyStore.getCertificate(ALIAS).publicKey as ECPublicKey
        val privateKey = keyStore.getKey(ALIAS, null) as PrivateKey
        return EcdsaIdentity(publicKey, privateKey, "openherdr@${Build.MODEL.replace(' ', '-')}")
    }

    /** Deletes the key; the next [identity] call creates a new one that must be authorized again. */
    fun regenerate() {
        keyStore.deleteEntry(ALIAS)
        generate()
    }

    fun protection(): Protection = runCatching {
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> Protection.StrongBox
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Protection.TrustedEnvironment
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> Protection.Software
                else -> Protection.Unknown
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) Protection.TrustedEnvironment else Protection.Software
        }
    }.getOrDefault(Protection.Unknown)

    private fun generate() {
        try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "openherdr-ssh-p256"
    }
}
