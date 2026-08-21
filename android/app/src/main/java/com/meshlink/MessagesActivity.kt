package com.meshlink

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.data.ChatMessage
import com.meshlink.network.ConnectionState
import com.meshlink.network.DiscoveryStatus
import com.meshlink.network.MeshNetworkManager
import com.meshlink.network.PeerDevice

/**
 * Always-reachable Messages hub. Does not require a peer merely to open.
 * Sending still needs a connected Nearby peer (via ChatActivity + MeshRouter).
 */
class MessagesActivity : AppCompatActivity(), MessageAwareListener {

    private lateinit var app: MeshLinkApp
    private lateinit var network: MeshNetworkManager
    private lateinit var emptyState: LinearLayout
    private lateinit var listHeading: TextView
    private lateinit var peersAdapter: ConnectedPeerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)

        app = MeshLinkApp.get()
        network = app.meshSession.ensureNetwork(app)

        emptyState = findViewById(R.id.messagesEmptyState)
        listHeading = findViewById(R.id.messagesListHeading)

        peersAdapter = ConnectedPeerAdapter { peer ->
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra(ChatActivity.EXTRA_ENDPOINT_ID, peer.endpointId)
                    .putExtra(ChatActivity.EXTRA_PEER_NAME, peer.name)
            )
        }

        findViewById<RecyclerView>(R.id.messagesPeersRecycler).apply {
            layoutManager = LinearLayoutManager(this@MessagesActivity)
            adapter = peersAdapter
        }

        refreshConnectedPeers()
    }

    override fun onStart() {
        super.onStart()
        app.meshSession.addListener(this)
        refreshConnectedPeers()
    }

    override fun onStop() {
        app.meshSession.removeListener(this)
        super.onStop()
    }

    private fun refreshConnectedPeers() {
        val connected = network.getPeers()
            .filter { it.connectionState == ConnectionState.CONNECTED }
        if (connected.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            listHeading.visibility = View.GONE
            peersAdapter.submit(emptyList())
        } else {
            emptyState.visibility = View.GONE
            listHeading.visibility = View.VISIBLE
            peersAdapter.submit(connected)
        }
    }

    override fun onPeerFound(peer: PeerDevice) = Unit

    override fun onPeerLost(endpointId: String) {
        runOnUiThread { refreshConnectedPeers() }
    }

    override fun onConnectionStateChanged(
        endpointId: String,
        state: ConnectionState,
        peerName: String?
    ) {
        runOnUiThread { refreshConnectedPeers() }
    }

    override fun onMessageReceived(endpointId: String, text: String) = Unit

    override fun onMessageStored(message: ChatMessage) = Unit

    override fun onStatusChanged(status: DiscoveryStatus, message: String) = Unit

    override fun onError(message: String) = Unit

    private class ConnectedPeerAdapter(
        private val onOpen: (PeerDevice) -> Unit
    ) : RecyclerView.Adapter<ConnectedPeerAdapter.Holder>() {

        private val items = mutableListOf<PeerDevice>()

        fun submit(peers: List<PeerDevice>) {
            items.clear()
            items.addAll(peers)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_peer, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.peerName)
            private val status: TextView = itemView.findViewById(R.id.peerStatus)
            private val avatar: TextView = itemView.findViewById(R.id.peerAvatar)
            private val statusDot: View = itemView.findViewById(R.id.peerStatusDot)
            private val action: Button = itemView.findViewById(R.id.peerActionButton)
            private val disconnect: Button = itemView.findViewById(R.id.peerDisconnectButton)

            fun bind(peer: PeerDevice) {
                name.text = peer.name
                avatar.text = peer.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"
                status.text = itemView.context.getString(R.string.status_connected)
                statusDot.setBackgroundResource(R.drawable.status_dot_connected)
                action.text = itemView.context.getString(R.string.open_chat)
                action.isEnabled = true
                action.setOnClickListener { onOpen(peer) }
                disconnect.visibility = View.GONE
                itemView.setOnClickListener { onOpen(peer) }
            }
        }
    }
}
