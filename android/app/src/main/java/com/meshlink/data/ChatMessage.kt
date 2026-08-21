package com.meshlink.data

data class ChatMessage(
    val id: Long = 0,
    val peerId: String,
    val peerName: String,
    val body: String,
    val sentByMe: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)
