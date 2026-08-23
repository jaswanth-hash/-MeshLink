package com.meshlink.admin

import com.meshlink.crypto.AdminKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var trustStore: AdminTrustStore
    private lateinit var adminKeyManager: AdminKeyManager
    private lateinit var adminManager: AdminManager
    private lateinit var adminPubKeyBase64: String
    private lateinit var adminFingerprint: String

    private var currentTimeMs: Long = 1724345000000L

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        trustStore = AdminTrustStore(fakePrefs)
        adminKeyManager = AdminKeyManager()
        adminKeyManager.deleteKey()
        adminKeyManager.generateKeyPair()

        adminPubKeyBase64 = adminKeyManager.getPublicKeyBase64()!!
        adminFingerprint = adminKeyManager.getFingerprint()!!

        // Establish trusted admin in trustStore
        trustStore.setTrustedAdmin(adminPubKeyBase64)

        adminManager = AdminManager(
            trustStore = trustStore,
            clock = { currentTimeMs },
            maxClockSkewMs = 5 * 60 * 1000L // 5 minutes
        )
    }

    @Test
    fun trustedAdminCommand_valid_accepted() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BROADCAST,
            commandId = "cmd-2001",
            adminFingerprint = adminFingerprint,
            targetNodeId = "*",
            sequenceNumber = 1L,
            timestampMs = currentTimeMs,
            commandData = "Valid Admin Broadcast",
            keyManager = adminKeyManager
        )
        val payload = AdminPacketCodec.encode(command)

        val result = adminManager.validateIncomingPayload(payload)
        assertTrue("Expected Valid result, got $result", result is ValidationResult.Valid)
        val validCmd = (result as ValidationResult.Valid).command
        assertEquals(command, validCmd)
        assertEquals(1L, trustStore.getLastSequenceNumber())
    }

    @Test
    fun untrustedAdmin_noAdminEstablished_rejected() {
        trustStore.clearTrustedAdmin()

        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BROADCAST,
            commandId = "cmd-2002",
            adminFingerprint = adminFingerprint,
            targetNodeId = "*",
            sequenceNumber = 1L,
            timestampMs = currentTimeMs,
            commandData = "Test",
            keyManager = adminKeyManager
        )
        val payload = AdminPacketCodec.encode(command)

        val result = adminManager.validateIncomingPayload(payload)
        assertTrue(result is ValidationResult.UntrustedAdmin)
    }

    @Test
    fun wrongAdminKey_rejected() {
        val foreignKeyManager = AdminKeyManager(keyAlias = "foreign_admin_key_mismatch")
        foreignKeyManager.deleteKey()
        foreignKeyManager.generateKeyPair()

        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BLOCK_NODE,
            commandId = "cmd-2003",
            adminFingerprint = foreignKeyManager.getFingerprint()!!,
            targetNodeId = "node-x",
            sequenceNumber = 1L,
            timestampMs = currentTimeMs,
            commandData = "Block node-x",
            keyManager = foreignKeyManager
        )
        val payload = AdminPacketCodec.encode(command)

        val result = adminManager.validateIncomingPayload(payload)
        assertTrue(result is ValidationResult.AdminFingerprintMismatch)

        foreignKeyManager.deleteKey()
    }

    @Test
    fun invalidSignature_corruptedSignature_rejected() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.REQUEST_TOPOLOGY,
            commandId = "cmd-2004",
            adminFingerprint = adminFingerprint,
            targetNodeId = "*",
            sequenceNumber = 1L,
            timestampMs = currentTimeMs,
            commandData = "",
            keyManager = adminKeyManager
        )

        val corruptedCommand = command.copy(signatureBase64 = "AAAA" + command.signatureBase64.drop(4))
        val payload = AdminPacketCodec.encode(corruptedCommand)

        val result = adminManager.validateIncomingPayload(payload)
        assertTrue(result is ValidationResult.InvalidSignature)
    }

    @Test
    fun tamperedCommandPayload_rejected() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BLOCK_NODE,
            commandId = "cmd-2005",
            adminFingerprint = adminFingerprint,
            targetNodeId = "node-legit",
            sequenceNumber = 1L,
            timestampMs = currentTimeMs,
            commandData = "Original block reason",
            keyManager = adminKeyManager
        )

        val tamperedCommand = command.copy(targetNodeId = "node-victim")
        val payload = AdminPacketCodec.encode(tamperedCommand)

        val result = adminManager.validateIncomingPayload(payload)
        assertTrue(result is ValidationResult.InvalidSignature)
    }

    @Test
    fun sequenceNumbers_validIncreasingAccepted_replaysAndDuplicatesRejected() {
        fun sendSeq(seq: Long): ValidationResult {
            val command = AdminPacketCodec.createSignedCommand(
                type = AdminCommandType.BROADCAST,
                commandId = "cmd-seq-$seq",
                adminFingerprint = adminFingerprint,
                targetNodeId = "*",
                sequenceNumber = seq,
                timestampMs = currentTimeMs,
                commandData = "Seq $seq",
                keyManager = adminKeyManager
            )
            return adminManager.validateIncomingPayload(AdminPacketCodec.encode(command))
        }

        // 1. Seq 1 accepted
        assertTrue(sendSeq(1L) is ValidationResult.Valid)
        assertEquals(1L, trustStore.getLastSequenceNumber())

        // 2. Seq 2 accepted
        assertTrue(sendSeq(2L) is ValidationResult.Valid)
        assertEquals(2L, trustStore.getLastSequenceNumber())

        // 3. Duplicate Seq 2 rejected
        assertTrue(sendSeq(2L) is ValidationResult.ReplayedSequence)
        assertEquals(2L, trustStore.getLastSequenceNumber())

        // 4. Older Seq 1 rejected
        assertTrue(sendSeq(1L) is ValidationResult.ReplayedSequence)
        assertEquals(2L, trustStore.getLastSequenceNumber())

        // 5. Higher Seq 5 accepted
        assertTrue(sendSeq(5L) is ValidationResult.Valid)
        assertEquals(5L, trustStore.getLastSequenceNumber())
    }

    @Test
    fun timestampValidation_expiredAndFutureTimestampsRejected() {
        val tenMinutesMs = 10 * 60 * 1000L

        // Past expired timestamp (10 mins in past)
        val expiredCmd = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.REQUEST_NETWORK_STATUS,
            commandId = "cmd-ts-1",
            adminFingerprint = adminFingerprint,
            targetNodeId = "*",
            sequenceNumber = 1L,
            timestampMs = currentTimeMs - tenMinutesMs,
            commandData = "",
            keyManager = adminKeyManager
        )
        val expiredResult = adminManager.validateIncomingPayload(AdminPacketCodec.encode(expiredCmd))
        assertTrue(expiredResult is ValidationResult.ExpiredOrFutureTimestamp)

        // Future timestamp (10 mins in future)
        val futureCmd = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.REQUEST_NETWORK_STATUS,
            commandId = "cmd-ts-2",
            adminFingerprint = adminFingerprint,
            targetNodeId = "*",
            sequenceNumber = 2L,
            timestampMs = currentTimeMs + tenMinutesMs,
            commandData = "",
            keyManager = adminKeyManager
        )
        val futureResult = adminManager.validateIncomingPayload(AdminPacketCodec.encode(futureCmd))
        assertTrue(futureResult is ValidationResult.ExpiredOrFutureTimestamp)
    }

    @Test
    fun blockedNode_rejected() {
        val blockedTarget = "blocked-node-99"
        trustStore.blockNode(blockedTarget)

        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BLOCK_NODE,
            commandId = "cmd-block-1",
            adminFingerprint = adminFingerprint,
            targetNodeId = blockedTarget,
            sequenceNumber = 1L,
            timestampMs = currentTimeMs,
            commandData = "Trying to target blocked node",
            keyManager = adminKeyManager
        )
        val result = adminManager.validateIncomingPayload(AdminPacketCodec.encode(command))
        assertTrue(result is ValidationResult.BlockedNode)
    }

    @Test
    fun unblockNode_targetingBlockedNode_accepted() {
        val blockedTarget = "blocked-node-99"
        trustStore.blockNode(blockedTarget)

        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.UNBLOCK_NODE,
            commandId = "cmd-unblock-1",
            adminFingerprint = adminFingerprint,
            targetNodeId = blockedTarget,
            sequenceNumber = 1L,
            timestampMs = currentTimeMs,
            commandData = "Unblocking node",
            keyManager = adminKeyManager
        )
        val result = adminManager.validateIncomingPayload(AdminPacketCodec.encode(command))
        assertTrue("Expected Valid result for UNBLOCK_NODE, got $result", result is ValidationResult.Valid)
    }

    @Test
    fun malformedADM1Payload_rejected() {
        val result1 = adminManager.validateIncomingPayload("NOT_AN_ADM1_FRAME")
        assertTrue(result1 is ValidationResult.InvalidFormat)

        val result2 = adminManager.validateIncomingPayload("ADM1|BROADCAST|incomplete")
        assertTrue(result2 is ValidationResult.InvalidFormat)
    }

    @Test
    fun normalNonAdminML1Messages_unaffected() {
        val chatText = "Hello MeshLink friend!"
        assertFalse(adminManager.isAdminPayload(chatText))

        val result = adminManager.validateIncomingPayload(chatText)
        assertTrue(result is ValidationResult.InvalidFormat)
    }
}
