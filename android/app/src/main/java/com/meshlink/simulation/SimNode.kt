package com.meshlink.simulation

/**
 * A virtual mesh node used only by the offline simulator.
 * This is not a Bluetooth / Nearby Connections endpoint.
 */
data class SimNode(
    val id: String,
    val enabled: Boolean = true
) {
    init {
        require(id.isNotBlank()) { "Node id must not be blank" }
    }
}
