package com.meshlink.simulation

/**
 * Undirected virtual link between two simulation nodes.
 * Independent from real Nearby Connections.
 */
data class SimLink(
    val a: String,
    val b: String
) {
    init {
        require(a.isNotBlank() && b.isNotBlank()) { "Link endpoints must not be blank" }
        require(a != b) { "Link cannot connect a node to itself" }
    }

    fun involves(nodeId: String): Boolean = a == nodeId || b == nodeId

    fun other(nodeId: String): String = when (nodeId) {
        a -> b
        b -> a
        else -> error("$nodeId is not part of $this")
    }

    /** Canonical form so A—B and B—A are the same edge. */
    fun normalized(): SimLink {
        return if (a <= b) this else SimLink(b, a)
    }
}
