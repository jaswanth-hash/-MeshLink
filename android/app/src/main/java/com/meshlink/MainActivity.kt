package com.meshlink

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.data.ChatMessage
import com.meshlink.network.ConnectionState
import com.meshlink.network.DiscoveryStatus
import com.meshlink.network.MeshNetworkManager
import com.meshlink.network.PeerDevice
import com.meshlink.simulation.SimulationActivity

class MainActivity : AppCompatActivity(), MessageAwareListener {

    private lateinit var statusText: TextView
    private lateinit var statusLabelText: TextView
    private lateinit var statusDot: View
    private lateinit var discoveryButton: Button
    private lateinit var emptyPeersText: TextView
    private lateinit var peersMetaText: TextView
    private lateinit var peersAdapter: PeerAdapter

    private lateinit var app: MeshLinkApp
    private lateinit var network: MeshNetworkManager

    private val peers = linkedMapOf<String, PeerDevice>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            startDiscovery()
        } else {
            showPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        app = MeshLinkApp.get()
        network = app.meshSession.ensureNetwork(app)

        statusText = findViewById(R.id.statusText)
        statusLabelText = findViewById(R.id.statusLabelText)
        statusDot = findViewById(R.id.statusDot)
        discoveryButton = findViewById(R.id.discoveryButton)
        emptyPeersText = findViewById(R.id.emptyPeersText)
        peersMetaText = findViewById(R.id.peersMetaText)

        peersAdapter = PeerAdapter(
            onConnect = { peer ->
                if (!PermissionHelper.hasAllPermissions(this)) {
                    requestPermissionsOrExplain()
                    return@PeerAdapter
                }
                when (peer.connectionState) {
                    ConnectionState.CONNECTED -> openChat(peer)
                    ConnectionState.CONNECTING -> {
                        Toast.makeText(this, R.string.status_connecting, Toast.LENGTH_SHORT).show()
                    }
                    else -> network.connect(peer.endpointId)
                }
            },
            onOpenChat = { peer -> openChat(peer) },
            onDisconnect = { peer -> network.disconnect(peer.endpointId) }
        )

        findViewById<RecyclerView>(R.id.peersRecycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = peersAdapter
        }

        discoveryButton.setOnClickListener {
            if (network.isDiscovering()) {
                stopDiscovery()
            } else {
                requestPermissionsOrExplain()
            }
        }

        findViewById<Button>(R.id.simulatorButton).setOnClickListener {
            startActivity(Intent(this, SimulationActivity::class.java))
        }

        findViewById<Button>(R.id.messagesButton).setOnClickListener {
            startActivity(Intent(this, MessagesActivity::class.java))
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        refreshPeers(network.getPeers())
        updateStatusUi(
            if (network.isDiscovering()) DiscoveryStatus.SEARCHING else DiscoveryStatus.IDLE,
            if (network.isDiscovering()) getString(R.string.status_searching) else getString(R.string.status_idle)
        )
        maybeAutoStartDiscovery()
    }

    override fun onStart() {
        super.onStart()
        app.meshSession.addListener(this)
        refreshPeers(network.getPeers())
        maybeAutoStartDiscovery()
    }

    override fun onStop() {
        app.meshSession.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            // Keep networking alive across ChatActivity; only tear down if process is leaving.
        }
        super.onDestroy()
    }

    private fun requestPermissionsOrExplain() {
        val missing = PermissionHelper.missingPermissions(this)
        if (missing.isEmpty()) {
            startDiscovery()
            return
        }
        val shouldExplain = missing.any { shouldShowRequestPermissionRationale(it) }
        if (shouldExplain) {
            AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.permissions_required)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    permissionLauncher.launch(missing)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            permissionLauncher.launch(missing)
        }
    }

    private fun showPermissionDenied() {
        updateStatusUi(DiscoveryStatus.ERROR, getString(R.string.permissions_denied))
        AlertDialog.Builder(this)
            .setMessage(R.string.permissions_denied)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                PermissionHelper.openAppSettings(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startDiscovery() {
        if (!PermissionHelper.hasAllPermissions(this)) {
            requestPermissionsOrExplain()
            return
        }
        app.preferences.getDisplayName()?.takeIf { it.isNotBlank() }?.let {
            network.setDisplayName(it)
        }
        if (network.start()) {
            discoveryButton.setText(R.string.stop_discovery)
        }
    }

    private fun stopDiscovery() {
        network.stop()
        peers.clear()
        updatePeersUi()
        discoveryButton.setText(R.string.start_discovery)
    }

    private fun openChat(peer: PeerDevice) {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_ENDPOINT_ID, peer.endpointId)
                .putExtra(ChatActivity.EXTRA_PEER_NAME, peer.name)
        )
    }

    private fun refreshPeers(list: List<PeerDevice>) {
        peers.clear()
        list.forEach { peers[it.endpointId] = it }
        updatePeersUi()
    }

    private fun updateStatusUi(status: DiscoveryStatus, message: String) {
        statusLabelText.setText(
            when (status) {
                DiscoveryStatus.IDLE -> R.string.status_offline_label
                DiscoveryStatus.SEARCHING -> R.string.status_searching_label
                DiscoveryStatus.CONNECTED -> R.string.status_connected_label
                DiscoveryStatus.ERROR -> R.string.status_attention_label
            }
        )
        statusText.text = message
        val dot = when (status) {
            DiscoveryStatus.IDLE -> R.drawable.status_dot_idle
            DiscoveryStatus.SEARCHING -> R.drawable.status_dot_searching
            DiscoveryStatus.CONNECTED -> R.drawable.status_dot_connected
            DiscoveryStatus.ERROR -> R.drawable.status_dot_error
        }
        statusDot.setBackgroundResource(dot)
        discoveryButton.setText(
            if (network.isDiscovering()) R.string.stop_discovery else R.string.start_discovery
        )
    }

    private fun updatePeersUi() {
        val currentPeers = peers.values.toList()
        peersAdapter.submit(currentPeers)
        emptyPeersText.visibility = if (currentPeers.isEmpty()) View.VISIBLE else View.GONE
        peersMetaText.text = if (currentPeers.isEmpty()) {
            getString(R.string.peers_summary_empty)
        } else {
            resources.getQuantityString(
                R.plurals.peers_summary_count,
                currentPeers.size,
                currentPeers.size
            )
        }
    }

    private fun maybeAutoStartDiscovery() {
        if (!app.preferences.shouldAutoStartDiscovery()) return
        if (network.isDiscovering()) return
        if (PermissionHelper.hasAllPermissions(this)) {
            startDiscovery()
        }
    }

    override fun onPeerFound(peer: PeerDevice) {
        runOnUiThread {
            peers[peer.endpointId] = peer
            updatePeersUi()
        }
    }

    override fun onPeerLost(endpointId: String) {
        runOnUiThread {
            peers.remove(endpointId)
            updatePeersUi()
        }
    }

    override fun onConnectionStateChanged(
        endpointId: String,
        state: ConnectionState,
        peerName: String?
    ) {
        runOnUiThread {
            val existing = peers[endpointId]
            val updated = (existing ?: PeerDevice(endpointId, peerName ?: "Nearby device"))
                .copy(
                    name = peerName ?: existing?.name ?: "Nearby device",
                    connectionState = state
            )
            peers[endpointId] = updated
            updatePeersUi()
            if (state == ConnectionState.CONNECTED) {
                openChat(updated)
            }
        }
    }

    override fun onMessageReceived(endpointId: String, text: String) = Unit

    override fun onMessageStored(message: ChatMessage) = Unit

    override fun onStatusChanged(status: DiscoveryStatus, message: String) {
        runOnUiThread { updateStatusUi(status, message) }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private class PeerAdapter(
        private val onConnect: (PeerDevice) -> Unit,
        private val onOpenChat: (PeerDevice) -> Unit,
        private val onDisconnect: (PeerDevice) -> Unit
    ) : RecyclerView.Adapter<PeerAdapter.Holder>() {

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
                status.text = when (peer.connectionState) {
                    ConnectionState.DISCOVERED -> itemView.context.getString(R.string.peer_available)
                    ConnectionState.CONNECTING -> itemView.context.getString(R.string.peer_connecting)
                    ConnectionState.CONNECTED -> itemView.context.getString(R.string.peer_connected)
                    ConnectionState.DISCONNECTED -> itemView.context.getString(R.string.peer_disconnected)
                }
                statusDot.setBackgroundResource(
                    when (peer.connectionState) {
                        ConnectionState.DISCOVERED -> R.drawable.status_dot_available
                        ConnectionState.CONNECTING -> R.drawable.status_dot_searching
                        ConnectionState.CONNECTED -> R.drawable.status_dot_connected
                        ConnectionState.DISCONNECTED -> R.drawable.status_dot_idle
                    }
                )
                action.text = when (peer.connectionState) {
                    ConnectionState.CONNECTED -> itemView.context.getString(R.string.open_chat)
                    ConnectionState.CONNECTING -> itemView.context.getString(R.string.status_connecting)
                    else -> itemView.context.getString(R.string.connect)
                }
                action.isEnabled = peer.connectionState != ConnectionState.CONNECTING
                disconnect.visibility =
                    if (peer.connectionState == ConnectionState.CONNECTED) View.VISIBLE else View.GONE
                action.setOnClickListener {
                    when (peer.connectionState) {
                        ConnectionState.CONNECTED -> onOpenChat(peer)
                        ConnectionState.CONNECTING -> Unit
                        else -> onConnect(peer)
                    }
                }
                disconnect.setOnClickListener { onDisconnect(peer) }
                itemView.setOnClickListener {
                    if (peer.connectionState == ConnectionState.CONNECTED) {
                        onOpenChat(peer)
                    }
                }
            }
        }
    }
}
