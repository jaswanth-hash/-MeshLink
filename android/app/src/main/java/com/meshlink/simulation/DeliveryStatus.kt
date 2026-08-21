package com.meshlink.simulation

/** UI-facing delivery phases for the offline simulator (not real Bluetooth). */
enum class DeliveryStatus {
    IDLE,
    SENT,
    ROUTING,
    DELIVERED,
    FAILED
}
