package com.meshlink.simulation

/**
 * Outcome of a simulated send. Paths are hop id lists, e.g. [A, B, C, D].
 */
sealed class RoutingResult {
    abstract val messageId: String

    data class Delivered(
        override val messageId: String,
        val path: List<String>,
        val body: String
    ) : RoutingResult() {
        val hopCount: Int get() = (path.size - 1).coerceAtLeast(0)
    }

    data class Duplicate(
        override val messageId: String,
        val destinationId: String
    ) : RoutingResult()

    data class Failed(
        override val messageId: String,
        val reason: String
    ) : RoutingResult()
}
