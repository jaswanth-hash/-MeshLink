package com.meshlink.routing

/**
 * Application-layer mesh packet.
 * Nearby Connections is only a one-hop transport; multi-hop is implemented by [MeshRouter].
 */
data class MeshPacket(
    val messageId: String,
    val sourceId: String,
    val destinationId: String,
    val ttl: Int,
    val timestampMs: Long,
    val payload: String
) {
    init {
        require(messageId.isNotBlank()) { "messageId required" }
        require(sourceId.isNotBlank()) { "sourceId required" }
        require(destinationId.isNotBlank()) { "destinationId required" }
    }

    fun decrementTtl(): MeshPacket = copy(ttl = ttl - 1)
}
