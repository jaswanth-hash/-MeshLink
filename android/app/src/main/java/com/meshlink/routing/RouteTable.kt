package com.meshlink.routing

/**
 * One known destination in the local routing table.
 * [nextHopEndpointId] is a direct Nearby (or test) neighbor used to reach [destinationNodeId].
 */
data class RouteEntry(
    val destinationNodeId: String,
    val nextHopEndpointId: String,
    val hopCount: Int,
    val lastUpdatedMs: Long
)

/**
 * Lightweight peer/route table maintained by [MeshRouter].
 * Independent of Android UI.
 */
class RouteTable {
    private val routes = linkedMapOf<String, RouteEntry>()
    private val endpointToNode = linkedMapOf<String, String>()
    private val nodeToEndpoint = linkedMapOf<String, String>()

    fun snapshot(): List<RouteEntry> = routes.values.sortedBy { it.destinationNodeId }

    fun knownNodeId(endpointId: String): String? = endpointToNode[endpointId]

    fun endpointForNode(nodeId: String): String? = nodeToEndpoint[nodeId]

    fun nextHop(destinationNodeId: String): RouteEntry? = routes[destinationNodeId]

    fun directNeighbors(): Map<String, String> = endpointToNode.toMap()

    fun registerDirectNeighbor(endpointId: String, nodeId: String, nowMs: Long) {
        endpointToNode[endpointId] = nodeId
        nodeToEndpoint[nodeId] = endpointId
        routes[nodeId] = RouteEntry(
            destinationNodeId = nodeId,
            nextHopEndpointId = endpointId,
            hopCount = 1,
            lastUpdatedMs = nowMs
        )
    }

    /**
     * Learn / refresh a route toward [sourceNodeId] via the neighbor that delivered the packet.
     * Prefer shorter hop counts; equal cost refreshes timestamp.
     */
    fun learnFromPacket(
        sourceNodeId: String,
        viaEndpointId: String,
        remainingTtl: Int,
        defaultTtl: Int,
        nowMs: Long
    ) {
        if (viaEndpointId.isBlank() || sourceNodeId.isBlank()) return
        val hopsTravelled = (defaultTtl - remainingTtl).coerceAtLeast(1)
        val existing = routes[sourceNodeId]
        if (existing == null || hopsTravelled < existing.hopCount) {
            routes[sourceNodeId] = RouteEntry(
                destinationNodeId = sourceNodeId,
                nextHopEndpointId = viaEndpointId,
                hopCount = hopsTravelled,
                lastUpdatedMs = nowMs
            )
        } else if (hopsTravelled == existing.hopCount && existing.nextHopEndpointId == viaEndpointId) {
            routes[sourceNodeId] = existing.copy(lastUpdatedMs = nowMs)
        }
        // Keep direct neighbor map only for true one-hop peers.
        if (hopsTravelled == 1) {
            endpointToNode[viaEndpointId] = sourceNodeId
            nodeToEndpoint[sourceNodeId] = viaEndpointId
        }
    }

    fun removeEndpoint(endpointId: String) {
        val nodeId = endpointToNode.remove(endpointId)
        if (nodeId != null) {
            nodeToEndpoint.remove(nodeId)
        }
        val toRemove = routes.filterValues { it.nextHopEndpointId == endpointId }.keys.toList()
        toRemove.forEach { routes.remove(it) }
    }

    fun clear() {
        routes.clear()
        endpointToNode.clear()
        nodeToEndpoint.clear()
    }

    /** Drop any route whose next hop is not in [aliveEndpoints]. */
    fun recompute(aliveEndpoints: Set<String>) {
        val dead = routes.filterValues { it.nextHopEndpointId !in aliveEndpoints }.keys.toList()
        dead.forEach { routes.remove(it) }
        endpointToNode.keys.filter { it !in aliveEndpoints }.toList().forEach { endpoint ->
            val node = endpointToNode.remove(endpoint)
            if (node != null) nodeToEndpoint.remove(node)
        }
    }
}
