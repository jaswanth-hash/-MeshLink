package com.meshlink

import android.app.Application
import android.provider.Settings
import com.meshlink.data.ChatMessage
import com.meshlink.data.MessageStore
import com.meshlink.network.ConnectionState
import com.meshlink.network.DiscoveryStatus
import com.meshlink.network.MeshNetworkManager
import com.meshlink.network.NearbyMeshTransport
import com.meshlink.network.PeerDevice
import com.meshlink.routing.MeshPacket
import com.meshlink.routing.MeshRouter
import com.meshlink.routing.PacketCodec
import com.meshlink.routing.SendResult

class MeshLinkApp : Application() {
    lateinit var messageStore: MessageStore
        private set

    lateinit var preferences: UserPreferences
        private set

    val meshSession = MeshSession()

    override fun onCreate() {
        super.onCreate()
        instance = this
        messageStore = MessageStore(this)
        preferences = UserPreferences(this)
        ThemeController.applySaved(preferences)
    }

    companion object {
        @Volatile
        private var instance: MeshLinkApp? = null

        fun get(): MeshLinkApp =
            instance ?: throw IllegalStateException("MeshLinkApp not initialized")
    }
}

/**
 * Holds the single live [MeshNetworkManager] and the multi-hop [MeshRouter] above it.
 * Simulator is independent and untouched.
 */
class MeshSession {
    private val listeners = linkedSetOf<MeshNetworkManager.Listener>()
    var network: MeshNetworkManager? = null
        private set
    var router: MeshRouter? = null
        private set

    @Synchronized
    fun ensureNetwork(app: MeshLinkApp): MeshNetworkManager {
        network?.let { return it }

        val transport = NearbyMeshTransport { network }
        val localId = resolveLocalNodeId(app)
        val meshRouter = MeshRouter(
            localNodeId = localId,
            transport = transport,
            listener = object : MeshRouter.Listener {
                override fun onDelivered(packet: MeshPacket) {
                    deliverToUi(app, packet)
                }
            }
        )
        router = meshRouter

        val manager = MeshNetworkManager(app, object : MeshNetworkManager.Listener {
            override fun onPeerFound(peer: PeerDevice) {
                if (app.preferences.shouldShowNearbyDeviceNotifications()) {
                    NearbyDeviceNotifier.showNearbyDevice(app, peer.name)
                }
                snapshotListeners().forEach { it.onPeerFound(peer) }
            }

            override fun onPeerLost(endpointId: String) {
                snapshotListeners().forEach { it.onPeerLost(endpointId) }
            }

            override fun onConnectionStateChanged(
                endpointId: String,
                state: ConnectionState,
                peerName: String?
            ) {
                when (state) {
                    ConnectionState.CONNECTED -> {
                        val remoteId = peerName?.takeIf { it.isNotBlank() } ?: endpointId
                        meshRouter.onNeighborConnected(endpointId, remoteId)
                    }
                    ConnectionState.DISCONNECTED -> {
                        meshRouter.onNeighborDisconnected(endpointId)
                    }
                    else -> Unit
                }
                snapshotListeners().forEach {
                    it.onConnectionStateChanged(endpointId, state, peerName)
                }
            }

            override fun onMessageReceived(endpointId: String, text: String) {
                if (PacketCodec.isMeshFrame(text)) {
                    meshRouter.handleIncoming(endpointId, text)
                    return
                }
                // Legacy plain-text one-hop payload (non-mesh).
                val name = network?.getPeers()
                    ?.find { it.endpointId == endpointId }
                    ?.name
                    ?: "Nearby device"
                val saved = app.messageStore.insert(
                    ChatMessage(
                        peerId = endpointId,
                        peerName = name,
                        body = text,
                        sentByMe = false
                    )
                )
                snapshotListeners().forEach { listener ->
                    if (listener is MessageAwareListener) {
                        listener.onMessageStored(saved)
                    } else {
                        listener.onMessageReceived(endpointId, text)
                    }
                }
            }

            override fun onStatusChanged(status: DiscoveryStatus, message: String) {
                snapshotListeners().forEach { it.onStatusChanged(status, message) }
            }

            override fun onError(message: String) {
                snapshotListeners().forEach { it.onError(message) }
            }
        })
        app.preferences.getDisplayName()?.let { manager.setDisplayName(it) }
        network = manager
        return manager
    }

    /**
     * Send chat text through the mesh routing layer when possible.
     * Destination mesh id is the peer's advertised name (Nearby endpoint name).
     */
    fun sendChatMessage(endpointId: String, peerName: String, body: String): SendResult? {
        val mesh = router ?: return null
        val destNodeId = mesh.nodeIdForEndpoint(endpointId)
            ?: peerName.takeIf { it.isNotBlank() }
            ?: endpointId
        return mesh.send(destNodeId, body)
    }

    private fun deliverToUi(app: MeshLinkApp, packet: MeshPacket) {
        val endpointId = router?.endpointForNode(packet.sourceId) ?: packet.sourceId
        val saved = app.messageStore.insert(
            ChatMessage(
                peerId = endpointId,
                peerName = packet.sourceId,
                body = packet.payload,
                sentByMe = false
            )
        )
        snapshotListeners().forEach { listener ->
            if (listener is MessageAwareListener) {
                listener.onMessageStored(saved)
            } else {
                listener.onMessageReceived(endpointId, packet.payload)
            }
        }
    }

    private fun resolveLocalNodeId(app: MeshLinkApp): String {
        app.preferences.getDisplayName()?.takeIf { it.isNotBlank() }?.let { return it }
        val androidId = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ANDROID_ID
        )?.takeLast(6) ?: "local"
        return "MeshLink-$androidId"
    }

    @Synchronized
    fun addListener(listener: MeshNetworkManager.Listener) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: MeshNetworkManager.Listener) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun snapshotListeners(): List<MeshNetworkManager.Listener> = listeners.toList()
}

interface MessageAwareListener : MeshNetworkManager.Listener {
    fun onMessageStored(message: ChatMessage)
}
