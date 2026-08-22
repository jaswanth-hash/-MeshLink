package com.meshlink.admin

import com.meshlink.crypto.AdminKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminPacketCodecTest {

    private lateinit var adminKeyManager: AdminKeyManager
    private lateinit var adminPubKeyBase64: String
    private lateinit var adminFingerprint: String

    @Before
    fun setUp() {
        adminKeyManager = AdminKeyManager()
        adminKeyManager.deleteKey()
        adminKeyManager.generateKeyPair()
        adminPubKeyBase64 = adminKeyManager.getPublicKeyBase64()!!
        adminFingerprint = adminKeyManager.getFingerprint()!!
    }

    @Test
    fun encodeAndDecode_roundTripPreservesAllFields() {
        val command = AdminCommand(
            type = AdminCommandType.BLOCK_NODE,
            commandId = "cmd-1001",
            adminFingerprint = adminFingerprint,
            targetNodeId = "node-alpha",
            sequenceNumber = 42L,
            timestampMs = 1724345000000L,
            commandData = "revoking node due to policy violation",
            signatureBase64 = "test_sig_base64"
        )

        val encoded = AdminPacketCodec.encode(command)
        assertTrue(AdminPacketCodec.isAdminFrame(encoded))
        assertTrue(encoded.startsWith("ADM1|"))

        val decoded = AdminPacketCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(command, decoded)
    }

    @Test
    fun createSignedCommand_generatesValidSignatureAndVerifies() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BROADCAST,
            commandId = "cmd-1002",
            adminFingerprint = adminFingerprint,
            targetNodeId = "*",
            sequenceNumber = 1L,
            timestampMs = 1724345000000L,
            commandData = "Emergency Broadcast Text",
            keyManager = adminKeyManager
        )

        assertTrue(command.signatureBase64.isNotBlank())
        val isValid = AdminPacketCodec.verifySignature(command, adminPubKeyBase64)
        assertTrue(isValid)
    }

    @Test
    fun verifySignature_rejectsTamperedCommandData() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BLOCK_NODE,
            commandId = "cmd-1003",
            adminFingerprint = adminFingerprint,
            targetNodeId = "node-beta",
            sequenceNumber = 2L,
            timestampMs = 1724345000000L,
            commandData = "Original block reason",
            keyManager = adminKeyManager
        )

        val tamperedCommand = command.copy(commandData = "Tampered block reason")
        val isValid = AdminPacketCodec.verifySignature(tamperedCommand, adminPubKeyBase64)
        assertFalse(isValid)
    }

    @Test
    fun verifySignature_rejectsTamperedSequenceNumber() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.REQUEST_TOPOLOGY,
            commandId = "cmd-1004",
            adminFingerprint = adminFingerprint,
            targetNodeId = "*",
            sequenceNumber = 10L,
            timestampMs = 1724345000000L,
            commandData = "",
            keyManager = adminKeyManager
        )

        val tamperedCommand = command.copy(sequenceNumber = 99L)
        val isValid = AdminPacketCodec.verifySignature(tamperedCommand, adminPubKeyBase64)
        assertFalse(isValid)
    }

    @Test
    fun verifySignature_rejectsTamperedTimestamp() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.UNBLOCK_NODE,
            commandId = "cmd-1005",
            adminFingerprint = adminFingerprint,
            targetNodeId = "node-gamma",
            sequenceNumber = 3L,
            timestampMs = 1724345000000L,
            commandData = "Restoring node",
            keyManager = adminKeyManager
        )

        val tamperedCommand = command.copy(timestampMs = 1999999999999L)
        val isValid = AdminPacketCodec.verifySignature(tamperedCommand, adminPubKeyBase64)
        assertFalse(isValid)
    }

    @Test
    fun verifySignature_rejectsTamperedTargetNodeId() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BLOCK_NODE,
            commandId = "cmd-1006",
            adminFingerprint = adminFingerprint,
            targetNodeId = "bad-actor-node",
            sequenceNumber = 4L,
            timestampMs = 1724345000000L,
            commandData = "Block payload",
            keyManager = adminKeyManager
        )

        val tamperedCommand = command.copy(targetNodeId = "innocent-node")
        val isValid = AdminPacketCodec.verifySignature(tamperedCommand, adminPubKeyBase64)
        assertFalse(isValid)
    }

    @Test
    fun decode_rejectsMalformedOrInvalidADM1Packets() {
        assertNull(AdminPacketCodec.decode("NOT_ADM_FRAME"))
        assertNull(AdminPacketCodec.decode("ADM1|ONLY|THREE|FIELDS"))
        assertNull(AdminPacketCodec.decode("ADM1|BLOCK_NODE|cmd-1|fp|target|not_a_number|1000|data|sig"))
        assertNull(AdminPacketCodec.decode("ADM1|BLOCK_NODE|cmd-1|fp|target|100|not_a_number|data|sig"))
        assertNull(AdminPacketCodec.decode("ADM1|BLOCK_NODE|cmd-1|fp|target|-5|1000|data|sig"))
    }

    @Test
    fun decode_rejectsUnsupportedCommandTypes() {
        val malformedWire = "ADM1|INVALID_CMD_TYPE|cmd-1007|fp|target|1|1724345000000|data|sig"
        assertNull(AdminPacketCodec.decode(malformedWire))
    }

    @Test
    fun verifySignature_rejectsWrongAdminPublicKey() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.REQUEST_NETWORK_STATUS,
            commandId = "cmd-1008",
            adminFingerprint = adminFingerprint,
            targetNodeId = "*",
            sequenceNumber = 5L,
            timestampMs = 1724345000000L,
            commandData = "Status query",
            keyManager = adminKeyManager
        )

        val foreignKeyManager = AdminKeyManager(keyAlias = "foreign_admin_key_test")
        foreignKeyManager.deleteKey()
        foreignKeyManager.generateKeyPair()
        val foreignPubKeyBase64 = foreignKeyManager.getPublicKeyBase64()!!

        val isValid = AdminPacketCodec.verifySignature(command, foreignPubKeyBase64)
        assertFalse(isValid)

        foreignKeyManager.deleteKey()
    }

    @Test
    fun verifySignature_rejectsMismatchBetweenFingerprintAndPublicKey() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BROADCAST,
            commandId = "cmd-1009",
            adminFingerprint = "fake_fingerprint_hash",
            targetNodeId = "*",
            sequenceNumber = 6L,
            timestampMs = 1724345000000L,
            commandData = "Text",
            keyManager = adminKeyManager
        )

        val isValid = AdminPacketCodec.verifySignature(command, adminPubKeyBase64)
        assertFalse(isValid)
    }
}
