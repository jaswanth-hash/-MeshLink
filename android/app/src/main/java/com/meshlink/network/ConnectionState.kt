package com.meshlink.network

enum class ConnectionState {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

enum class DiscoveryStatus {
    IDLE,
    SEARCHING,
    CONNECTED,
    ERROR
}
