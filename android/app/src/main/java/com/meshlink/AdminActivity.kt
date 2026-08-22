package com.meshlink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.meshlink.admin.AdminCommandType
import com.meshlink.admin.AdminCommandHandler
import com.meshlink.admin.AdminKeyManager
import com.meshlink.admin.AdminPacketCodec
import com.meshlink.admin.AdminTrustStore
import com.meshlink.admin.BroadcastResult
import com.meshlink.admin.NetworkStatus
import com.meshlink.admin.TopologySnapshot

/**
 * Controller screen for MeshLink Admin operations.
 *
 * Security Entry Guard:
 * Only accessible when the local device holds a valid Admin key pair in Android KeyStore
 * that matches the trusted Admin identity stored in [AdminTrustStore].
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var keyManager: AdminKeyManager
    private lateinit var trustStore: AdminTrustStore
    private lateinit var commandHandler: AdminCommandHandler

    private lateinit var adminUnauthContainer: LinearLayout
    private lateinit var adminContentContainer: LinearLayout
    private lateinit var adminFingerprintSub: TextView

    private lateinit var statConnectedCount: TextView
    private lateinit var statKnownCount: TextView
    private lateinit var statRoutesCount: TextView
    private lateinit var topologySummaryText: TextView
    private lateinit var topologyDetailsText: TextView

    private lateinit var broadcastInput: EditText
    private lateinit var sendBroadcastButton: Button
    private lateinit var broadcastStatusText: TextView

    private lateinit var nodeTargetInput: EditText
    private lateinit var blockNodeButton: Button
    private lateinit var unblockNodeButton: Button
    private lateinit var nodeManagementStatusText: TextView

    private var sequenceCounter = 1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        keyManager = AdminKeyManager()
        trustStore = AdminTrustStore(this)
        commandHandler = AdminCommandHandler(trustStore, keyManager)

        adminUnauthContainer = findViewById(R.id.adminUnauthContainer)
        adminContentContainer = findViewById(R.id.adminContentContainer)
        adminFingerprintSub = findViewById(R.id.adminFingerprintSub)

        statConnectedCount = findViewById(R.id.statConnectedCount)
        statKnownCount = findViewById(R.id.statKnownCount)
        statRoutesCount = findViewById(R.id.statRoutesCount)
        topologySummaryText = findViewById(R.id.topologySummaryText)
        topologyDetailsText = findViewById(R.id.topologyDetailsText)

        broadcastInput = findViewById(R.id.broadcastInput)
        sendBroadcastButton = findViewById(R.id.sendBroadcastButton)
        broadcastStatusText = findViewById(R.id.broadcastStatusText)

        nodeTargetInput = findViewById(R.id.nodeTargetInput)
        blockNodeButton = findViewById(R.id.blockNodeButton)
        unblockNodeButton = findViewById(R.id.unblockNodeButton)
        nodeManagementStatusText = findViewById(R.id.nodeManagementStatusText)

        findViewById<Button>(R.id.adminCloseButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.initAdminButton).setOnClickListener { initializeAdminIdentity() }

        sendBroadcastButton.setOnClickListener { handleSendBroadcast() }
        blockNodeButton.setOnClickListener { handleBlockNode() }
        unblockNodeButton.setOnClickListener { handleUnblockNode() }

        checkAdminAuthorizationAndRefresh()
    }

    private fun checkAdminAuthorizationAndRefresh() {
        val pubKeyB64 = keyManager.getPublicKeyBase64()
        val isAuthorized = pubKeyB64 != null && trustStore.isTrustedAdmin(pubKeyB64)

        if (isAuthorized) {
            adminUnauthContainer.visibility = View.GONE
            adminContentContainer.visibility = View.VISIBLE
            val fingerprint = keyManager.getFingerprint() ?: "Unknown"
            adminFingerprintSub.text = "Admin Fingerprint: ${fingerprint.take(16)}..."
            refreshNetworkTelemetry()
        } else {
            adminUnauthContainer.visibility = View.VISIBLE
            adminContentContainer.visibility = View.GONE
            adminFingerprintSub.text = "Admin Identity: Not Initialized"
        }
    }

    private fun initializeAdminIdentity() {
        val pubKey = keyManager.generateKeyPair()
        val pubKeyB64 = keyManager.getPublicKeyBase64()!!
        trustStore.setTrustedAdmin(pubKeyB64)
        Toast.makeText(this, "Admin identity generated & set as trusted Admin", Toast.LENGTH_SHORT).show()
        checkAdminAuthorizationAndRefresh()
    }

    private fun refreshNetworkTelemetry() {
        val session = MeshLinkApp.get().meshSession
        val router = session.router
        val peers = session.network?.getPeers()?.map { it.endpointId } ?: emptyList()

        val fingerprint = keyManager.getFingerprint() ?: return
        sequenceCounter = (trustStore.getLastSequenceNumber() + 1).coerceAtLeast(sequenceCounter)

        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.REQUEST_NETWORK_STATUS,
            commandId = "cmd-status-${System.currentTimeMillis()}",
            adminFingerprint = fingerprint,
            targetNodeId = "*",
            sequenceNumber = sequenceCounter++,
            timestampMs = System.currentTimeMillis(),
            commandData = "",
            keyManager = keyManager
        )

        val result = commandHandler.executeCommand(command, router) { peers }
        val netStatus = result.payload as? NetworkStatus

        if (netStatus != null) {
            statConnectedCount.text = netStatus.connectedPeerCount.toString()
            statKnownCount.text = netStatus.knownPeerCount.toString()
            statRoutesCount.text = netStatus.activeRouteCount.toString()
            topologySummaryText.text = "Network Health: ${netStatus.healthStatus} (${netStatus.knownPeerCount} known nodes)"

            val details = if (netStatus.nodeStatuses.isEmpty()) {
                "No connected peer nodes in direct mesh range."
            } else {
                netStatus.nodeStatuses.joinToString("\n") {
                    "• ${it.nodeId} [${it.connectionState}] - ${if (it.isBlocked) "BLOCKED" else "Active"}"
                }
            }
            topologyDetailsText.text = details
        }
    }

    private fun handleSendBroadcast() {
        val text = broadcastInput.text.toString().trim()
        if (text.isBlank()) {
            Toast.makeText(this, "Broadcast text cannot be blank", Toast.LENGTH_SHORT).show()
            return
        }

        val fingerprint = keyManager.getFingerprint() ?: return
        val session = MeshLinkApp.get().meshSession
        sequenceCounter = (trustStore.getLastSequenceNumber() + 1).coerceAtLeast(sequenceCounter)

        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BROADCAST,
            commandId = "cmd-bc-${System.currentTimeMillis()}",
            adminFingerprint = fingerprint,
            targetNodeId = "*",
            sequenceNumber = sequenceCounter++,
            timestampMs = System.currentTimeMillis(),
            commandData = text,
            keyManager = keyManager
        )

        val result = commandHandler.executeCommand(command, session.router)
        val bcResult = result.payload as? BroadcastResult

        broadcastStatusText.visibility = View.VISIBLE
        broadcastStatusText.text = bcResult?.statusMessage ?: result.message
        broadcastInput.setText("")
        refreshNetworkTelemetry()
    }

    private fun handleBlockNode() {
        val targetId = nodeTargetInput.text.toString().trim()
        if (targetId.isBlank()) {
            Toast.makeText(this, "Enter a valid node ID to block", Toast.LENGTH_SHORT).show()
            return
        }

        val fingerprint = keyManager.getFingerprint() ?: return
        sequenceCounter = (trustStore.getLastSequenceNumber() + 1).coerceAtLeast(sequenceCounter)

        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.BLOCK_NODE,
            commandId = "cmd-blk-${System.currentTimeMillis()}",
            adminFingerprint = fingerprint,
            targetNodeId = targetId,
            sequenceNumber = sequenceCounter++,
            timestampMs = System.currentTimeMillis(),
            commandData = "Manual block via Controller UI",
            keyManager = keyManager
        )

        val result = commandHandler.executeCommand(command)
        nodeManagementStatusText.text = result.message
        nodeTargetInput.setText("")
        refreshNetworkTelemetry()
    }

    private fun handleUnblockNode() {
        val targetId = nodeTargetInput.text.toString().trim()
        if (targetId.isBlank()) {
            Toast.makeText(this, "Enter a valid node ID to unblock", Toast.LENGTH_SHORT).show()
            return
        }

        val fingerprint = keyManager.getFingerprint() ?: return
        sequenceCounter = (trustStore.getLastSequenceNumber() + 1).coerceAtLeast(sequenceCounter)

        val command = AdminPacketCodec.createSignedCommand(
            type = AdminCommandType.UNBLOCK_NODE,
            commandId = "cmd-unblk-${System.currentTimeMillis()}",
            adminFingerprint = fingerprint,
            targetNodeId = targetId,
            sequenceNumber = sequenceCounter++,
            timestampMs = System.currentTimeMillis(),
            commandData = "Manual unblock via Controller UI",
            keyManager = keyManager
        )

        val result = commandHandler.executeCommand(command)
        nodeManagementStatusText.text = result.message
        nodeTargetInput.setText("")
        refreshNetworkTelemetry()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, AdminActivity::class.java)
            context.startActivity(intent)
        }

        fun isLocalDeviceAdmin(context: Context): Boolean {
            val km = AdminKeyManager()
            val ts = AdminTrustStore(context)
            val pubB64 = km.getPublicKeyBase64() ?: return false
            return ts.isTrustedAdmin(pubB64)
        }
    }
}
