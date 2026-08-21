package com.meshlink

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.data.ChatMessage
import com.meshlink.network.ConnectionState
import com.meshlink.network.DiscoveryStatus
import com.meshlink.network.MeshNetworkManager
import com.meshlink.network.PeerDevice
import com.meshlink.routing.SendResult
import java.util.Date

class ChatActivity : AppCompatActivity(), MessageAwareListener {

    private lateinit var chatTitle: TextView
    private lateinit var chatStatus: TextView
    private lateinit var emptyChatText: TextView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var messagesAdapter: MessageAdapter

    private lateinit var app: MeshLinkApp
    private lateinit var network: MeshNetworkManager

    private lateinit var endpointId: String
    private lateinit var peerName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        endpointId = intent.getStringExtra(EXTRA_ENDPOINT_ID).orEmpty()
        peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "Nearby device"
        if (endpointId.isBlank()) {
            finish()
            return
        }

        app = MeshLinkApp.get()
        network = app.meshSession.ensureNetwork(app)

        chatTitle = findViewById(R.id.chatTitle)
        chatStatus = findViewById(R.id.chatStatus)
        emptyChatText = findViewById(R.id.emptyChatText)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)

        chatTitle.text = peerName
        updateConnectionLabel(
            network.getPeers().find { it.endpointId == endpointId }?.connectionState
                ?: ConnectionState.DISCONNECTED
        )

        messagesAdapter = MessageAdapter()
        findViewById<RecyclerView>(R.id.messagesRecycler).apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            adapter = messagesAdapter
        }

        loadHistory()

        sendButton.setOnClickListener { sendCurrentMessage() }
        configureInputBehavior()
    }

    override fun onStart() {
        super.onStart()
        app.meshSession.addListener(this)
        configureInputBehavior()
    }

    override fun onStop() {
        app.meshSession.removeListener(this)
        super.onStop()
    }

    private fun loadHistory() {
        val history = app.messageStore.messagesForPeer(endpointId)
        messagesAdapter.submit(history)
        emptyChatText.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun sendCurrentMessage() {
        val text = messageInput.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return

        val peer = network.getPeers().find { it.endpointId == endpointId }
        if (peer?.connectionState != ConnectionState.CONNECTED) {
            Toast.makeText(this, "Not connected to this device", Toast.LENGTH_SHORT).show()
            return
        }

        val result = app.meshSession.sendChatMessage(endpointId, peerName, text)
        val accepted = when (result) {
            is SendResult.Sent,
            is SendResult.Stored,
            is SendResult.Forwarded -> true
            else -> false
        }
        if (!accepted) {
            Toast.makeText(this, "Unable to send right now", Toast.LENGTH_SHORT).show()
            return
        }

        val saved = app.messageStore.insert(
            ChatMessage(
                peerId = endpointId,
                peerName = peerName,
                body = text,
                sentByMe = true
            )
        )
        messagesAdapter.append(saved)
        emptyChatText.visibility = View.GONE
        messageInput.text = null
        findViewById<RecyclerView>(R.id.messagesRecycler)
            .scrollToPosition(messagesAdapter.itemCount - 1)
        if (app.preferences.shouldPlaySendSound()) {
            playSendSound()
        }

        if (result is SendResult.Stored) {
            Toast.makeText(this, "Stored for mesh forward when a route appears", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureInputBehavior() {
        messageInput.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        messageInput.imeOptions = if (app.preferences.shouldEnterSendMessage()) {
            EditorInfo.IME_ACTION_SEND
        } else {
            EditorInfo.IME_ACTION_NONE
        }
        messageInput.maxLines = 4
        messageInput.setSingleLine(false)
        messageInput.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN &&
                !event.isShiftPressed
            val sendAction = actionId == EditorInfo.IME_ACTION_SEND
            if (app.preferences.shouldEnterSendMessage() && (sendAction || enterPressed)) {
                sendCurrentMessage()
                true
            } else {
                false
            }
        }
        messageInput.setOnKeyListener { _, keyCode, event ->
            if (app.preferences.shouldEnterSendMessage() &&
                keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN &&
                !event.isShiftPressed
            ) {
                sendCurrentMessage()
                true
            } else {
                false
            }
        }
    }

    private fun playSendSound() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            sendButton.postDelayed({ tone.release() }, 180L)
        }
    }

    private fun updateConnectionLabel(state: ConnectionState) {
        chatStatus.text = when (state) {
            ConnectionState.CONNECTED -> getString(R.string.status_connected)
            ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            ConnectionState.DISCOVERED -> "Nearby"
            ConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
        }
        sendButton.isEnabled = state == ConnectionState.CONNECTED
    }

    override fun onPeerFound(peer: PeerDevice) = Unit

    override fun onPeerLost(endpointId: String) {
        if (endpointId != this.endpointId) return
        runOnUiThread {
            // Discovery loss alone does not mean the socket dropped.
        }
    }

    override fun onConnectionStateChanged(
        endpointId: String,
        state: ConnectionState,
        peerName: String?
    ) {
        if (endpointId != this.endpointId) return
        runOnUiThread {
            if (!peerName.isNullOrBlank()) {
                this.peerName = peerName
                chatTitle.text = peerName
            }
            updateConnectionLabel(state)
        }
    }

    override fun onMessageReceived(endpointId: String, text: String) = Unit

    override fun onMessageStored(message: ChatMessage) {
        if (message.peerId != endpointId) return
        runOnUiThread {
            messagesAdapter.append(message)
            emptyChatText.visibility = View.GONE
            findViewById<RecyclerView>(R.id.messagesRecycler)
                .scrollToPosition(messagesAdapter.itemCount - 1)
        }
    }

    override fun onStatusChanged(status: DiscoveryStatus, message: String) = Unit

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {
        private val items = mutableListOf<ChatMessage>()

        fun submit(messages: List<ChatMessage>) {
            items.clear()
            items.addAll(messages)
            notifyDataSetChanged()
        }

        fun append(message: ChatMessage) {
            items.add(message)
            notifyItemInserted(items.lastIndex)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_message, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val root: LinearLayout = itemView.findViewById(R.id.messageRoot)
            private val body: TextView = itemView.findViewById(R.id.messageBody)
            private val meta: TextView = itemView.findViewById(R.id.messageMeta)

            fun bind(message: ChatMessage) {
                body.text = message.body
                body.setBackgroundResource(
                    if (message.sentByMe) R.drawable.bg_message_outgoing
                    else R.drawable.bg_message_incoming
                )
                val time = DateFormat.getTimeFormat(itemView.context).format(Date(message.timestampMs))
                meta.text = if (message.sentByMe) "You · $time" else "$time"
                root.gravity = if (message.sentByMe) Gravity.END else Gravity.START
            }
        }
    }

    companion object {
        const val EXTRA_ENDPOINT_ID = "endpoint_id"
        const val EXTRA_PEER_NAME = "peer_name"
    }
}
