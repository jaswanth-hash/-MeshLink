package com.meshlink.simulation

/**
 * Simulated mesh payload. [messageId] is used for duplicate suppression.
 */
data class SimMessage(
    val messageId: String,
    val sourceId: String,
    val destinationId: String,
    val body: String
) {
    init {
        require(messageId.isNotBlank()) { "messageId is required" }
        require(sourceId.isNotBlank() && destinationId.isNotBlank()) { "source/destination required" }
        require(sourceId != destinationId) { "source and destination must differ" }
    }
}
