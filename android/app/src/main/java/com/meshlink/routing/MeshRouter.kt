package com.meshlink.routing

/**
 * Multi-hop mesh routing above a one-hop transport (e.g. Nearby Connections).
 *
 * Nearby Connections only provides direct endpoint payloads. This router explicitly
 * implements forwarding, TTL, duplicate suppression, route learning, and store-and-forward.
 */
class MeshRouter(
    val localNodeId: String,
    private val transport: MeshTransport,
    private val listener: Listener = object : Listener {},
    private val defaultTtl: Int = DEFAULT_TTL,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { "m-${clock()}-${NEXT.getAndIncrement()}" }
) {
    interface Listener {
        fun onDelivered(packet: MeshPacket) {}
        fun onForwarded(packet: MeshPacket, toEndpointId: String) {}
        fun onDropped(packet: MeshPacket, reason: String) {}
        fun onStored(packet: MeshPacket) {}
    }

    private val routes = RouteTable()
    private val seenMessageIds = linkedSetOf<String>()
    private val store = ArrayDeque<MeshPacket>()
    private val aliveEndpoints = linkedSetOf<String>()

    fun routeTable(): List<RouteEntry> = routes.snapshot()

    fun seenIds(): Set<String> = seenMessageIds.toSet()

    fun storedPackets(): List<MeshPacket> = store.toList()

    fun nodeIdForEndpoint(endpointId: String): String? = routes.knownNodeId(endpointId)

    fun endpointForNode(nodeId: String): String? = routes.endpointForNode(nodeId)

    fun onNeighborConnected(endpointId: String, remoteNodeId: String) {
        aliveEndpoints.add(endpointId)
        routes.registerDirectNeighbor(endpointId, remoteNodeId, clock())
        val hello = MeshPacket(
            messageId = idGenerator(),
            sourceId = localNodeId,
            destinationId = remoteNodeId,
            ttl = 1,
            timestampMs = clock(),
            payload = HELLO_PAYLOAD
        )
        transport.sendToEndpoint(endpointId, PacketCodec.encode(hello))
        flushStoreAndForward()
    }

    fun onNeighborDisconnected(endpointId: String) {
        aliveEndpoints.remove(endpointId)
        routes.removeEndpoint(endpointId)
        routes.recompute(aliveEndpoints.toSet())
    }

    /**
     * Originate a user message toward [destinationNodeId].
     */
    fun send(
        destinationNodeId: String,
        payload: String,
        messageId: String = idGenerator(),
        ttl: Int = defaultTtl
    ): SendResult {
        require(destinationNodeId != localNodeId) { "Cannot send to self" }
        val packet = MeshPacket(
            messageId = messageId,
            sourceId = localNodeId,
            destinationId = destinationNodeId,
            ttl = ttl,
            timestampMs = clock(),
            payload = payload
        )
        remember(messageId)
        return dispatch(packet, incomingFromEndpoint = null, originating = true)
    }

    fun handleIncoming(fromEndpointId: String, raw: String) {
        val packet = PacketCodec.decode(raw) ?: return
        processIncoming(fromEndpointId, packet)
    }

    fun processIncoming(fromEndpointId: String, packet: MeshPacket) {
        if (packet.messageId in seenMessageIds) {
            listener.onDropped(packet, "duplicate")
            return
        }
        remember(packet.messageId)

        if (packet.ttl <= 0) {
            listener.onDropped(packet, "ttl_expired_on_arrival")
            return
        }

        aliveEndpoints.add(fromEndpointId)
        routes.learnFromPacket(
            sourceNodeId = packet.sourceId,
            viaEndpointId = fromEndpointId,
            remainingTtl = packet.ttl,
            defaultTtl = defaultTtl,
            nowMs = clock()
        )

        if (packet.payload == HELLO_PAYLOAD) {
            routes.registerDirectNeighbor(fromEndpointId, packet.sourceId, clock())
            flushStoreAndForward()
            return
        }

        if (packet.destinationId == localNodeId) {
            listener.onDelivered(packet)
            return
        }

        val toForward = packet.decrementTtl()
        if (toForward.ttl <= 0) {
            listener.onDropped(packet, "ttl_expired")
            return
        }
        dispatch(toForward, incomingFromEndpoint = fromEndpointId, originating = false)
    }

    fun flushStoreAndForward() {
        if (store.isEmpty()) return
        val pending = store.toList()
        store.clear()
        for (packet in pending) {
            dispatch(packet, incomingFromEndpoint = null, originating = false)
        }
    }

    private fun dispatch(
        packet: MeshPacket,
        incomingFromEndpoint: String?,
        originating: Boolean
    ): SendResult {
        if (packet.ttl <= 0) {
            listener.onDropped(packet, "ttl_expired")
            return SendResult.Failed("ttl_expired")
        }

        syncAliveWithTransport()

        val route = routes.nextHop(packet.destinationId)
        if (route != null &&
            route.nextHopEndpointId in aliveEndpoints &&
            route.nextHopEndpointId != incomingFromEndpoint
        ) {
            if (transport.sendToEndpoint(route.nextHopEndpointId, PacketCodec.encode(packet))) {
                listener.onForwarded(packet, route.nextHopEndpointId)
                return result(originating, packet, listOf(route.nextHopEndpointId))
            }
        }

        val connected = transport.connectedEndpointIds()
        val directEndpoint = routes.endpointForNode(packet.destinationId)
        if (directEndpoint != null &&
            directEndpoint in connected &&
            directEndpoint != incomingFromEndpoint
        ) {
            if (transport.sendToEndpoint(directEndpoint, PacketCodec.encode(packet))) {
                listener.onForwarded(packet, directEndpoint)
                return result(originating, packet, listOf(directEndpoint))
            }
        }

        val candidates = connected
            .filter { it != incomingFromEndpoint }
            .filter { endpoint ->
                // Avoid reflecting a forwarded/stored packet solely back toward its source.
                originating || routes.knownNodeId(endpoint) != packet.sourceId
            }
            .sorted()

        if (candidates.isEmpty()) {
            storePacket(packet)
            return SendResult.Stored(packet)
        }

        val sentTo = mutableListOf<String>()
        for (endpoint in candidates) {
            if (transport.sendToEndpoint(endpoint, PacketCodec.encode(packet))) {
                sentTo.add(endpoint)
                listener.onForwarded(packet, endpoint)
            }
        }

        if (sentTo.isEmpty()) {
            storePacket(packet)
            return SendResult.Stored(packet)
        }
        return result(originating, packet, sentTo)
    }

    private fun result(
        originating: Boolean,
        packet: MeshPacket,
        via: List<String>
    ): SendResult {
        return if (originating) SendResult.Sent(packet, via) else SendResult.Forwarded(packet, via)
    }

    private fun storePacket(packet: MeshPacket) {
        if (store.any { it.messageId == packet.messageId }) return
        store.addLast(packet)
        while (store.size > MAX_STORE) {
            store.removeFirst()
        }
        listener.onStored(packet)
    }

    private fun syncAliveWithTransport() {
        val connected = transport.connectedEndpointIds()
        aliveEndpoints.retainAll(connected)
        aliveEndpoints.addAll(connected)
        routes.recompute(aliveEndpoints.toSet())
    }

    private fun remember(messageId: String) {
        seenMessageIds.add(messageId)
        while (seenMessageIds.size > MAX_SEEN) {
            seenMessageIds.remove(seenMessageIds.first())
        }
    }

    companion object {
        const val DEFAULT_TTL = 8
        const val HELLO_PAYLOAD = "__ML_HELLO__"
        private const val MAX_SEEN = 500
        private const val MAX_STORE = 100
        private val NEXT = java.util.concurrent.atomic.AtomicLong(1)
    }
}

sealed class SendResult {
    data class Sent(val packet: MeshPacket, val viaEndpoints: List<String>) : SendResult()
    data class Forwarded(val packet: MeshPacket, val viaEndpoints: List<String>) : SendResult()
    data class Stored(val packet: MeshPacket) : SendResult()
    data class Failed(val reason: String) : SendResult()
}
