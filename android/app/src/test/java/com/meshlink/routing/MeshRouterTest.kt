package com.meshlink.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory one-hop fabric for exercising [MeshRouter] without Nearby Connections.
 */
private class TestFabric {
    private val links = mutableMapOf<String, MutableSet<String>>()
    private val routers = mutableMapOf<String, MeshRouter>()

    fun install(nodeId: String): MeshRouter {
        val transport = object : MeshTransport {
            override fun sendToEndpoint(endpointId: String, encodedPacket: String): Boolean {
                val neighbors = links[nodeId] ?: return false
                if (endpointId !in neighbors) return false
                val remote = routers[endpointId] ?: return false
                remote.handleIncoming(nodeId, encodedPacket)
                return true
            }

            override fun connectedEndpointIds(): Set<String> = links[nodeId]?.toSet() ?: emptySet()
        }
        val delivered = mutableListOf<MeshPacket>()
        val router = MeshRouter(
            localNodeId = nodeId,
            transport = transport,
            listener = object : MeshRouter.Listener {
                override fun onDelivered(packet: MeshPacket) {
                    delivered.add(packet)
                }
            },
            clock = { 1_000L },
            idGenerator = { "id-${nodeId}-${delivered.size}-${System.nanoTime()}" }
        )
        routers[nodeId] = router
        links.putIfAbsent(nodeId, mutableSetOf())
        routerDelivered[nodeId] = delivered
        return router
    }

    private val routerDelivered = mutableMapOf<String, MutableList<MeshPacket>>()

    fun delivered(nodeId: String): List<MeshPacket> = routerDelivered[nodeId].orEmpty()

    fun connect(a: String, b: String) {
        links.getOrPut(a) { mutableSetOf() }.add(b)
        links.getOrPut(b) { mutableSetOf() }.add(a)
        routers.getValue(a).onNeighborConnected(b, b)
        routers.getValue(b).onNeighborConnected(a, a)
    }

    fun disconnect(a: String, b: String) {
        links[a]?.remove(b)
        links[b]?.remove(a)
        routers[a]?.onNeighborDisconnected(b)
        routers[b]?.onNeighborDisconnected(a)
    }
}

class MeshRouterTest {

    private lateinit var fabric: TestFabric
    private lateinit var a: MeshRouter
    private lateinit var b: MeshRouter
    private lateinit var c: MeshRouter

    @Before
    fun setUp() {
        fabric = TestFabric()
        a = fabric.install("A")
        b = fabric.install("B")
        c = fabric.install("C")
    }

    @Test
    fun multiHop_A_to_C_via_B() {
        fabric.connect("A", "B")
        fabric.connect("B", "C")

        val result = a.send("C", "hello-mesh", messageId = "m-abc", ttl = 8)
        assertTrue(result is SendResult.Sent)
        assertEquals(1, fabric.delivered("C").size)
        assertEquals("hello-mesh", fabric.delivered("C").single().payload)
        assertEquals("A", fabric.delivered("C").single().sourceId)
        assertTrue(fabric.delivered("A").isEmpty())
        assertTrue(fabric.delivered("B").isEmpty())
    }

    @Test
    fun duplicatePrevention_doesNotDeliverTwice() {
        fabric.connect("A", "B")
        fabric.connect("B", "C")

        a.send("C", "once", messageId = "dup-1", ttl = 8)
        assertEquals(1, fabric.delivered("C").size)

        // Re-inject the same encoded packet toward C as if looped.
        val packet = MeshPacket("dup-1", "A", "C", 5, 1_000L, "once")
        c.processIncoming("B", packet)
        assertEquals(1, fabric.delivered("C").size)
    }

    @Test
    fun ttlExpiry_dropsBeforeDestination() {
        fabric.connect("A", "B")
        fabric.connect("B", "C")

        // TTL 1: B receives, decrements to 0, must not forward to C.
        a.send("C", "expire", messageId = "ttl-1", ttl = 1)
        assertTrue(fabric.delivered("C").isEmpty())
    }

    @Test
    fun disconnectedRoute_doesNotDeliver() {
        fabric.connect("A", "B")
        fabric.connect("B", "C")
        fabric.disconnect("B", "C")

        a.send("C", "nope", messageId = "disc-1", ttl = 8)
        assertTrue(fabric.delivered("C").isEmpty())
    }

    @Test
    fun reconnection_allowsDeliveryAgain() {
        fabric.connect("A", "B")
        fabric.connect("B", "C")
        fabric.disconnect("B", "C")
        a.send("C", "wait", messageId = "re-1", ttl = 8)
        assertTrue(fabric.delivered("C").isEmpty())

        fabric.connect("B", "C")
        // Store-and-forward may deliver the queued "wait" on reconnect.
        a.send("C", "again", messageId = "re-2", ttl = 8)
        val payloads = fabric.delivered("C").map { it.payload }
        assertTrue(payloads.contains("again"))
        assertTrue(
            "expected again after reconnect, got $payloads",
            payloads.last() == "again" || payloads.contains("again")
        )
    }

    @Test
    fun storeAndForward_deliversAfterLinkAppears() {
        // A has no neighbors yet — message is stored.
        val stored = a.send("C", "held", messageId = "sf-1", ttl = 8)
        assertTrue(stored is SendResult.Stored)
        assertEquals(1, a.storedPackets().size)
        assertTrue(fabric.delivered("C").isEmpty())

        fabric.connect("A", "B")
        fabric.connect("B", "C")
        // Connecting flushes A's store toward B, then B→C.
        assertEquals(1, fabric.delivered("C").size)
        assertEquals("held", fabric.delivered("C").single().payload)
        assertTrue(a.storedPackets().isEmpty())
    }

    @Test
    fun packetCodec_roundTrip() {
        val original = MeshPacket(
            messageId = "id|1",
            sourceId = "A",
            destinationId = "B",
            ttl = 3,
            timestampMs = 42L,
            payload = "hello|world\\x"
        )
        val encoded = PacketCodec.encode(original)
        assertTrue(PacketCodec.isMeshFrame(encoded))
        assertEquals(original, PacketCodec.decode(encoded))
    }

    @Test
    fun routeTable_updatesOnConnectAndDisconnect() {
        fabric.connect("A", "B")
        assertTrue(a.routeTable().any { it.destinationNodeId == "B" && it.hopCount == 1 })
        fabric.disconnect("A", "B")
        assertFalse(a.routeTable().any { it.destinationNodeId == "B" })
    }
}
