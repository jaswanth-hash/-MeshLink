package com.meshlink.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminKeyManagerTest {

    private lateinit var keyManager: AdminKeyManager

    @Before
    fun setUp() {
        keyManager = AdminKeyManager()
        keyManager.deleteKey()
    }

    @Test
    fun keyGeneration_createsValidKeyPairAndPublicIdentity() {
        assertNull(keyManager.getPublicKey())
        assertNull(keyManager.getPublicKeyBase64())
        assertNull(keyManager.getFingerprint())

        val pubKey = keyManager.generateKeyPair()
        assertNotNull(pubKey)
        assertEquals(pubKey, keyManager.getPublicKey())

        val pubKeyBase64 = keyManager.getPublicKeyBase64()
        assertNotNull(pubKeyBase64)
        assertTrue(pubKeyBase64!!.isNotBlank())

        val fingerprint = keyManager.getFingerprint()
        assertNotNull(fingerprint)
        assertEquals(64, fingerprint!!.length) // SHA-256 hex string is 64 characters
    }

    @Test
    fun signingAndVerification_succeedsForValidData() {
        keyManager.generateKeyPair()
        val pubKeyBase64 = keyManager.getPublicKeyBase64()!!
        val payload = "ADM1|BLOCK_NODE|101|target-node-123|emergency_revocation|1724345000000"

        val signatureBase64 = keyManager.sign(payload)
        assertNotNull(signatureBase64)
        assertTrue(signatureBase64.isNotBlank())

        val isValid = keyManager.verify(payload, signatureBase64, pubKeyBase64)
        assertTrue(isValid)
    }

    @Test
    fun signatureVerification_rejectsInvalidOrCorruptedSignature() {
        keyManager.generateKeyPair()
        val pubKeyBase64 = keyManager.getPublicKeyBase64()!!
        val payload = "ADM1|EMERGENCY_ALERT|102|*|Evacuate Sector B|1724345000000"

        val signatureBase64 = keyManager.sign(payload)
        val corruptedSignature = signatureBase64.dropLast(4) + "AAAA"

        val isValid = keyManager.verify(payload, corruptedSignature, pubKeyBase64)
        assertFalse(isValid)
    }

    @Test
    fun signatureVerification_rejectsTamperedPayload() {
        keyManager.generateKeyPair()
        val pubKeyBase64 = keyManager.getPublicKeyBase64()!!
        val originalPayload = "ADM1|BLOCK_NODE|103|bad-node-99|policy_violation|1724345000000"
        val tamperedPayload = "ADM1|BLOCK_NODE|103|good-node-01|policy_violation|1724345000000"

        val signatureBase64 = keyManager.sign(originalPayload)

        val isValid = keyManager.verify(tamperedPayload, signatureBase64, pubKeyBase64)
        assertFalse(isValid)
    }

    @Test
    fun signatureVerification_rejectsWrongPublicKey() {
        keyManager.generateKeyPair()
        val payload = "ADM1|TOPOLOGY_REQ|104|*|request_graph|1724345000000"
        val signatureBase64 = keyManager.sign(payload)

        // Create a second key manager representing an attacker or different node
        val foreignKeyManager = AdminKeyManager(keyAlias = "foreign_test_key")
        foreignKeyManager.generateKeyPair()
        val foreignPubKeyBase64 = foreignKeyManager.getPublicKeyBase64()!!

        val isValid = keyManager.verify(payload, signatureBase64, foreignPubKeyBase64)
        assertFalse(isValid)

        foreignKeyManager.deleteKey()
    }

    @Test
    fun decodePublicKeyAndFingerprint_matchesOriginal() {
        val pubKey = keyManager.generateKeyPair()
        val pubKeyBase64 = keyManager.getPublicKeyBase64()!!
        val fingerprint = keyManager.getFingerprint()!!

        val decodedPubKey = AdminKeyManager.decodePublicKey(pubKeyBase64)
        assertNotNull(decodedPubKey)
        assertEquals(pubKey, decodedPubKey)

        val computedFingerprint = AdminKeyManager.computeFingerprint(pubKeyBase64)
        assertEquals(fingerprint, computedFingerprint)
    }
}
