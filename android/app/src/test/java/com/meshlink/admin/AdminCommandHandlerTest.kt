package com.meshlink.admin

import com.meshlink.crypto.AdminKeyManager
import com.meshlink.routing.MeshRouter
import com.meshlink.routing.MeshTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminCommandHandlerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var trustStore: AdminTrustStore
    private lateinit var keyManager: AdminKeyManager
    private lateinit var commandHandler: AdminCommandHandler
    private lateinit var pubKeyBase64: String
    private lateinit var fingerprint: String

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        trustStore = AdminTrustStore(fakePrefs)
        keyManager = AdminKeyManager()
        keyManager.deleteKey()
        keyManager.generateKeyPair()

        pubKeyBase64 = keyManager.getPublicKeyBase64()!!
        fingerprint = keyManager.getFingerprint()!!
        trustStore.setTrustedAdmin(pubKeyBase64)

        commandHandler = AdminCommandHandler(trustStore, keyManager)
    }

    @Test
    fun executeBroadcast_validCommand_returnsBroadcastResult() {
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BROADCAST,
            commandId = "cmd-bc-1",
            adminFingerprint = fingerprint,
            targetNodeId = "*",
            sequenceNumber = 1L,
            timestampMs = System.currentTimeMillis(),
            commandData = "Emergency Evacuation Alert",
            keyManager = keyManager
        )

        val result = commandHandler.executeCommand(command)
        assertTrue(result.success)
        assertEquals(AdminCommandType.BROADCAST, result.type)

        val bcResult = result.payload as? BroadcastResult
        assertNotNull(bcResult)
        assertEquals("Emergency Evacuation Alert", bcResult!!.broadcastText)
        assertTrue(bcResult.statusMessage.isNotBlank())
    }

    @Test
    fun executeBlockAndUnblockNode_persistsInTrustStore() {
        val targetNode = "malicious-node-1"
        assertFalse(trustStore.isNodeBlocked(targetNode))

        // BLOCK_NODE
        val blockCommand = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BLOCK_NODE,
            commandId = "cmd-blk-1",
            adminFingerprint = fingerprint,
            targetNodeId = targetNode,
            sequenceNumber = 2L,
            timestampMs = System.currentTimeMillis(),
            commandData = "Revoking due to protocol violation",
            keyManager = keyManager
        )
        val blockResult = commandHandler.executeCommand(blockCommand)
        assertTrue(blockResult.success)
        assertTrue(trustStore.isNodeBlocked(targetNode))

        // UNBLOCK_NODE
        val unblockCommand = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.UNBLOCK_NODE,
            commandId = "cmd-unblk-1",
            adminFingerprint = fingerprint,
            targetNodeId = targetNode,
            sequenceNumber = 3L,
            timestampMs = System.currentTimeMillis(),
            commandData = "Restoring access",
            keyManager = keyManager
        )
        val unblockResult = commandHandler.executeCommand(unblockCommand)
        assertTrue(unblockResult.success)
        assertFalse(trustStore.isNodeBlocked(targetNode))
    }

    @Test
    fun blockedNode_cannotExecuteAdminCommands() {
        val blockedNode = "revoked-node-99"
        trustStore.blockNode(blockedNode)

        val adminManager = AdminManager(trustStore)
        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BROADCAST,
            commandId = "cmd-test",
            adminFingerprint = fingerprint,
            targetNodeId = "*",
            sequenceNumber = 4L,
            timestampMs = System.currentTimeMillis(),
            commandData = "Test",
            keyManager = keyManager
        )
        val payload = AdminPacketCodec.encode(command)

        val result = adminManager.validateIncomingPayload(payload, sourceNodeId = blockedNode)
        assertTrue(result is ValidationResult.BlockedNode)
    }

    @Test
    fun executeRequestTopologyAndNetworkStatus_returnsExpectedTelemetry() {
        val dummyTransport = object : MeshTransport {
            override fun sendToEndpoint(endpointId: String, payload: String): Boolean = true
            override fun connectedEndpointIds(): List<String> = listOf("ep-101", "ep-102")
        }

        val router = MeshRouter(
            localNodeId = "LocalNode-Alpha",
            transport = dummyTransport
        )
        router.onNeighborConnected("ep-101", "Node-B")
        router.onNeighborConnected("ep-102", "Node-C")

        val statusCommand = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.REQUEST_NETWORK_STATUS,
            commandId = "cmd-net-1",
            adminFingerprint = fingerprint,
            targetNodeId = "*",
            sequenceNumber = 5L,
            timestampMs = System.currentTimeMillis(),
            commandData = "",
            keyManager = keyManager
        )

        val result = commandHandler.executeCommand(statusCommand, router) { listOf("Node-B", "Node-C") }
        assertTrue(result.success)

        val netStatus = result.payload as? NetworkStatus
        assertNotNull(netStatus)
        assertEquals(2, netStatus!!.connectedPeerCount)
        assertEquals("HEALTHY", netStatus.healthStatus)
        assertTrue(netStatus.knownPeerCount >= 2)
    }

    @Test
    fun broadcastResult_reportsDeliveryStateAccurately() {
        val dummyTransport = object : MeshTransport {
            override fun sendToEndpoint(endpointId: String, payload: String): Boolean = true
            override fun connectedEndpointIds(): List<String> = listOf("ep-1")
        }
        val router = MeshRouter(localNodeId = "LocalNode", transport = dummyTransport)
        router.onNeighborConnected("ep-1", "Peer-1")

        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BROADCAST,
            commandId = "cmd-bc-2",
            adminFingerprint = fingerprint,
            targetNodeId = "*",
            sequenceNumber = 6L,
            timestampMs = System.currentTimeMillis(),
            commandData = "Hello Mesh",
            keyManager = keyManager
        )

        val result = commandHandler.executeCommand(command, router)
        val bcResult = result.payload as? BroadcastResult
        assertNotNull(bcResult)
        assertEquals(1, bcResult!!.sentViaEndpoints.size)
        assertEquals("ep-1", bcResult.sentViaEndpoints[0])
    }

    @Test
    fun adminUiActivationGuard_requiresValidAdminIdentity() {
        val authorizedPubKey = keyManager.getPublicKeyBase64()!!
        assertTrue(trustStore.isTrustedAdmin(authorizedPubKey))

        trustStore.clearTrustedAdmin()
        assertFalse(trustStore.isTrustedAdmin(authorizedPubKey))
    }
}
