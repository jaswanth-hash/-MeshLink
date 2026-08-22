package com.meshlink.network

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.meshlink.RadioReadinessHelper
import java.nio.charset.StandardCharsets

/**
 * Real peer-to-peer networking via Google Nearby Connections.
 * UI must not call Play Services APIs directly — use this manager.
 */
class MeshNetworkManager(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onPeerFound(peer: PeerDevice)
        fun onPeerLost(endpointId: String)
        fun onConnectionStateChanged(endpointId: String, state: ConnectionState, peerName: String?)
        fun onMessageReceived(endpointId: String, text: String)
        fun onStatusChanged(status: DiscoveryStatus, message: String)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(appContext)

    private val peers = linkedMapOf<String, PeerDevice>()
    private val pendingNames = mutableMapOf<String, String>()

    @Volatile
    private var displayName: String = defaultDisplayName()

    @Volatile
    private var discovering = false

    fun setDisplayName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) {
            displayName = trimmed
        }
    }

    fun getDisplayName(): String = displayName

    fun resetDisplayName() {
        displayName = defaultDisplayName()
    }

    fun getPeers(): List<PeerDevice> = peers.values.toList()

    fun isDiscovering(): Boolean = discovering

    fun start(): Boolean {
        if (discovering) return true
        val availability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext)
        if (availability != ConnectionResult.SUCCESS) {
            val friendly = RadioReadinessHelper.getFriendlyErrorMessage(appContext, "8000")
            listener.onError(friendly)
            listener.onStatusChanged(DiscoveryStatus.ERROR, friendly)
            return false
        }

        startAdvertising()
        startDiscovery()
        discovering = true
        listener.onStatusChanged(DiscoveryStatus.SEARCHING, "Searching for nearby devices…")
        return true
    }

    fun stop() {
        discovering = false
        runCatching { connectionsClient.stopAdvertising() }
        runCatching { connectionsClient.stopDiscovery() }
        runCatching { connectionsClient.stopAllEndpoints() }
        peers.clear()
        pendingNames.clear()
        listener.onStatusChanged(DiscoveryStatus.IDLE, "Discovery stopped")
    }

    fun connect(endpointId: String) {
        val peer = peers[endpointId]
        if (peer == null) {
            listener.onError("Device is no longer available.")
            return
        }
        updatePeer(endpointId, peer.copy(connectionState = ConnectionState.CONNECTING))
        listener.onConnectionStateChanged(endpointId, ConnectionState.CONNECTING, peer.name)
        connectionsClient
            .requestConnection(displayName, endpointId, connectionLifecycleCallback)
            .addOnFailureListener { error ->
                updatePeer(endpointId, peer.copy(connectionState = ConnectionState.DISCOVERED))
                listener.onConnectionStateChanged(endpointId, ConnectionState.DISCOVERED, peer.name)
                val friendly = RadioReadinessHelper.getFriendlyErrorMessage(appContext, error.message ?: "unknown error")
                listener.onError(friendly)
            }
    }

    fun disconnect(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        peers[endpointId]?.let { peer ->
            updatePeer(endpointId, peer.copy(connectionState = ConnectionState.DISCONNECTED))
            listener.onConnectionStateChanged(endpointId, ConnectionState.DISCONNECTED, peer.name)
        }
    }

    fun sendMessage(endpointId: String, text: String): Boolean {
        val payload = Payload.fromBytes(text.toByteArray(StandardCharsets.UTF_8))
        connectionsClient
            .sendPayload(endpointId, payload)
            .addOnFailureListener { error ->
                val friendly = RadioReadinessHelper.getFriendlyErrorMessage(appContext, error.message ?: "unknown error")
                listener.onError(friendly)
            }
        return peers[endpointId]?.connectionState == ConnectionState.CONNECTED
    }

    fun release() {
        stop()
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()
        connectionsClient
            .startAdvertising(displayName, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                // Advertising is active; discovery status already set in start().
            }
            .addOnFailureListener { error ->
                val friendly = RadioReadinessHelper.getFriendlyErrorMessage(appContext, error.message ?: "unknown error")
                listener.onError(friendly)
                listener.onStatusChanged(DiscoveryStatus.ERROR, friendly)
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()
        connectionsClient
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnFailureListener { error ->
                val friendly = RadioReadinessHelper.getFriendlyErrorMessage(appContext, error.message ?: "unknown error")
                listener.onError(friendly)
                listener.onStatusChanged(DiscoveryStatus.ERROR, friendly)
            }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peer = PeerDevice(
                endpointId = endpointId,
                name = info.endpointName.ifBlank { "Nearby device" },
                connectionState = ConnectionState.DISCOVERED
            )
            updatePeer(endpointId, peer)
            listener.onPeerFound(peer)
        }

        override fun onEndpointLost(endpointId: String) {
            val existing = peers[endpointId]
            if (existing?.connectionState == ConnectionState.CONNECTED) {
                // Keep connected peers listed even if discovery loses them briefly.
                return
            }
            peers.remove(endpointId)
            listener.onPeerLost(endpointId)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            pendingNames[endpointId] = connectionInfo.endpointName
            val name = connectionInfo.endpointName.ifBlank { peers[endpointId]?.name ?: "Nearby device" }
            val peer = peers[endpointId]?.copy(
                name = name,
                connectionState = ConnectionState.CONNECTING
            ) ?: PeerDevice(endpointId, name, ConnectionState.CONNECTING)
            updatePeer(endpointId, peer)
            listener.onConnectionStateChanged(endpointId, ConnectionState.CONNECTING, name)

            // Auto-accept for offline mesh messaging between MeshLink peers.
            connectionsClient
                .acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error ->
                    listener.onError("Could not accept connection: ${error.message ?: "unknown error"}")
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    val name = pendingNames[endpointId]
                        ?: peers[endpointId]?.name
                        ?: "Nearby device"
                    val peer = PeerDevice(endpointId, name, ConnectionState.CONNECTED)
                    updatePeer(endpointId, peer)
                    listener.onConnectionStateChanged(endpointId, ConnectionState.CONNECTED, name)
                    listener.onStatusChanged(DiscoveryStatus.CONNECTED, "Connected to $name")
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    markDisconnected(endpointId, "Connection rejected")
                }

                ConnectionsStatusCodes.STATUS_ERROR -> {
                    markDisconnected(endpointId, "Connection error")
                }

                else -> {
                    markDisconnected(
                        endpointId,
                        resolution.status.statusMessage ?: "Connection failed"
                    )
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            markDisconnected(endpointId, "Peer disconnected")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            val text = String(bytes, StandardCharsets.UTF_8)
            if (text.isNotBlank()) {
                listener.onMessageReceived(endpointId, text)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Byte payloads complete immediately; no UI action needed.
        }
    }

    private fun markDisconnected(endpointId: String, reason: String) {
        val name = peers[endpointId]?.name ?: pendingNames[endpointId]
        val peer = peers[endpointId]
        if (peer != null) {
            updatePeer(endpointId, peer.copy(connectionState = ConnectionState.DISCONNECTED))
        } else {
            peers.remove(endpointId)
        }
        listener.onConnectionStateChanged(endpointId, ConnectionState.DISCONNECTED, name)
        listener.onError(reason)
        if (peers.values.none { it.connectionState == ConnectionState.CONNECTED }) {
            listener.onStatusChanged(
                if (discovering) DiscoveryStatus.SEARCHING else DiscoveryStatus.IDLE,
                if (discovering) "Searching for nearby devices…" else "Discovery stopped"
            )
        }
    }

    private fun updatePeer(endpointId: String, peer: PeerDevice) {
        peers[endpointId] = peer
    }

    private fun defaultDisplayName(): String {
        val device = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        val suffix = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )?.takeLast(4) ?: "user"
        return "MeshLink-$device-$suffix"
    }

    companion object {
        const val SERVICE_ID = "com.meshlink.nearby"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}
