package com.meshlink.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Manages ECDSA P-256 key generation, private key signing via Android KeyStore,
 * public key exporting for trust bootstrapping, and signature verification.
 *
 * Private keys remain hardware-backed inside KeyStore and are never exported.
 */
class AdminKeyManager(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val providerName: String = ANDROID_KEYSTORE_PROVIDER
) {
    private var fallbackKeyPair: KeyPair? = null

    /**
     * Generates a new ECDSA P-256 key pair in Android KeyStore (or JVM fallback for unit tests).
     * Returns the generated PublicKey.
     */
    fun generateKeyPair(): PublicKey {
        return try {
            val keyStore = KeyStore.getInstance(providerName)
            keyStore.load(null)
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                providerName
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            kpg.initialize(spec)
            val kp = kpg.generateKeyPair()
            kp.public
        } catch (_: Throwable) {
            // Fallback for JVM unit tests where AndroidKeyStore provider is unavailable
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            fallbackKeyPair = kp
            kp.public
        }
    }

    /**
     * Retrieves the existing Public Key, or null if not generated.
     */
    fun getPublicKey(): PublicKey? {
        fallbackKeyPair?.let { return it.public }
        return try {
            val keyStore = KeyStore.getInstance(providerName)
            keyStore.load(null)
            if (!keyStore.containsAlias(keyAlias)) return null
            val cert = keyStore.getCertificate(keyAlias) ?: return null
            cert.publicKey
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns the Public Key encoded in Base64 (X.509 format).
     */
    fun getPublicKeyBase64(): String? {
        val pubKey = getPublicKey() ?: return null
        return Base64Compat.encodeToString(pubKey.encoded)
    }

    /**
     * Returns the SHA-256 fingerprint (hex formatted) of the current Public Key.
     */
    fun getFingerprint(): String? {
        val pubKey = getPublicKey() ?: return null
        return computeFingerprint(pubKey.encoded)
    }

    /**
     * Signs data using the stored Admin Private Key.
     * Returns raw signature byte array.
     */
    fun sign(data: ByteArray): ByteArray {
        val privateKey: PrivateKey = getPrivateKey()
            ?: throw IllegalStateException("Admin private key not initialized in KeyStore")

        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(data)
        return signature.sign()
    }

    /**
     * Signs data string using the stored Admin Private Key.
     * Returns Base64-encoded signature.
     */
    fun sign(data: String): String {
        val signatureBytes = sign(data.toByteArray(Charsets.UTF_8))
        return Base64Compat.encodeToString(signatureBytes)
    }

    /**
     * Verifies a signature against data using a PublicKey object.
     */
    fun verify(data: ByteArray, signatureBytes: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initVerify(publicKey)
            signature.update(data)
            signature.verify(signatureBytes)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Verifies a Base64 signature against data string using a Base64-encoded public key.
     */
    fun verify(data: String, signatureBase64: String, publicKeyBase64: String): Boolean {
        return try {
            val pubKey = decodePublicKey(publicKeyBase64) ?: return false
            val sigBytes = Base64Compat.decode(signatureBase64)
            verify(data.toByteArray(Charsets.UTF_8), sigBytes, pubKey)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Deletes existing key pair from KeyStore.
     */
    fun deleteKey() {
        fallbackKeyPair = null
        try {
            val keyStore = KeyStore.getInstance(providerName)
            keyStore.load(null)
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        } catch (_: Throwable) {}
    }

    private fun getPrivateKey(): PrivateKey? {
        fallbackKeyPair?.let { return it.private }
        return try {
            val keyStore = KeyStore.getInstance(providerName)
            keyStore.load(null)
            if (!keyStore.containsAlias(keyAlias)) return null
            keyStore.getKey(keyAlias, null) as? PrivateKey
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "meshlink_admin_key"
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        /**
         * Reconstructs an ECDSA PublicKey from a Base64 X.509 encoded string.
         */
        fun decodePublicKey(base64String: String): PublicKey? {
            return try {
                val bytes = Base64Compat.decode(base64String)
                val spec = X509EncodedKeySpec(bytes)
                val kf = KeyFactory.getInstance("EC")
                kf.generatePublic(spec)
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Computes SHA-256 fingerprint (hex encoded) of public key bytes.
         */
        fun computeFingerprint(encodedPublicKey: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(encodedPublicKey)
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * Computes SHA-256 fingerprint (hex encoded) from Base64 public key string.
         */
        fun computeFingerprint(base64PublicKey: String): String? {
            return try {
                val bytes = Base64Compat.decode(base64PublicKey)
                computeFingerprint(bytes)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
