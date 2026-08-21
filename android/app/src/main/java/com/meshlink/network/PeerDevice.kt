package com.meshlink.network

data class PeerDevice(
    val endpointId: String,
    val name: String,
    val connectionState: ConnectionState = ConnectionState.DISCOVERED
)
