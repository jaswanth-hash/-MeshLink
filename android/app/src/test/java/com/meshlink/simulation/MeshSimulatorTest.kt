package com.meshlink.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshSimulatorTest {

    private lateinit var sim: MeshSimulator

    @Before
    fun setUp() {
        sim = MeshSimulator()
        sim.loadDefaultScenario()
    }

    @Test
    fun multiHopRouting_A_to_D_via_shortest_path() {
        val path = sim.findShortestPath("A", "D")
        assertNotNull(path)
        // Default graph: A-B-C-D and B-D → shortest is A→B→D
        assertEquals(listOf("A", "B", "D"), path)

        val result = sim.send("A", "D", "hello", messageId = "m1")
        assertTrue(result is RoutingResult.Delivered)
        result as RoutingResult.Delivered
        assertEquals(listOf("A", "B", "D"), result.path)
        assertEquals("hello", result.body)
    }

    @Test
    fun multiHopRouting_linear_chain_when_shortcut_removed() {
        sim.disconnect("B", "D")
        val path = sim.findShortestPath("A", "D")
        assertEquals(listOf("A", "B", "C", "D"), path)

        val result = sim.send("A", "D", "chain", "m-chain")
        assertTrue(result is RoutingResult.Delivered)
        assertEquals(listOf("A", "B", "C", "D"), (result as RoutingResult.Delivered).path)
    }

    @Test
    fun duplicateMessageId_isBlocked() {
        val first = sim.send("A", "D", "once", "dup-1")
        assertTrue(first is RoutingResult.Delivered)

        val second = sim.send("A", "D", "again", "dup-1")
        assertTrue(second is RoutingResult.Duplicate)
        assertEquals("dup-1", (second as RoutingResult.Duplicate).messageId)
    }

    @Test
    fun nodeFailure_breaksPath_untilRerouteExists() {
        sim.disconnect("B", "D")
        assertEquals(listOf("A", "B", "C", "D"), sim.findShortestPath("A", "D"))

        sim.setNodeEnabled("C", false)
        assertNull(sim.findShortestPath("A", "D"))

        val failed = sim.send("A", "D", "nope", "fail-1")
        assertTrue(failed is RoutingResult.Failed)
    }

    @Test
    fun rerouting_afterNodeFailure_usesAlternateLink() {
        // With B—D present, disabling C still leaves A→B→D
        sim.setNodeEnabled("C", false)
        val path = sim.findShortestPath("A", "D")
        assertEquals(listOf("A", "B", "D"), path)

        val result = sim.send("A", "D", "reroute", "re-1")
        assertTrue(result is RoutingResult.Delivered)
        assertEquals(listOf("A", "B", "D"), (result as RoutingResult.Delivered).path)
    }

    @Test
    fun rerouting_afterLinkFailure() {
        assertEquals(listOf("A", "B", "D"), sim.findShortestPath("A", "D"))
        sim.disconnect("B", "D")
        assertEquals(listOf("A", "B", "C", "D"), sim.findShortestPath("A", "D"))
    }

    @Test
    fun removeNode_dropsLinks_andForcesRerouteOrFailure() {
        sim.removeNode("B")
        assertNull(sim.findShortestPath("A", "D"))
        // Reconnect via a new path A-C-D after adding links
        sim.connect("A", "C")
        assertEquals(listOf("A", "C", "D"), sim.findShortestPath("A", "D"))
    }

    @Test
    fun reset_clearsTopologyAndDeliveredIds() {
        sim.send("A", "D", "x", "keep")
        sim.reset()
        assertTrue(sim.snapshotNodes().isEmpty())
        assertTrue(sim.snapshotLinks().isEmpty())
        assertTrue(sim.deliveredIds().isEmpty())
    }

    @Test
    fun nodeConnectionStatus_activeVsDisconnectedVsDisabled() {
        assertTrue(sim.isNodeConnected("A"))
        sim.addNode("E")
        assertTrue(!sim.isNodeConnected("E"))
        val lines = sim.nodeStatusLines()
        assertTrue(lines.any { it.contains("A") && it.contains("ACTIVE") })
        assertTrue(lines.any { it.contains("E") && it.contains("DISCONNECTED") })
        sim.setNodeEnabled("E", false)
        assertTrue(!sim.isNodeConnected("E"))
        assertTrue(sim.nodeStatusLines().any { it.contains("E") && it.contains("DISABLED") })
    }

    @Test
    fun simulateRandomFailure_withSeed_isDeterministicAndBreaksOrKeepsRoute() {
        val first = MeshSimulator().also { it.loadDefaultScenario() }
        val second = MeshSimulator().also { it.loadDefaultScenario() }
        val event1 = first.simulateRandomFailure(kotlin.random.Random(42))
        val event2 = second.simulateRandomFailure(kotlin.random.Random(42))
        assertEquals(event1, event2)
        assertTrue(event1 !is FailureEvent.None)
    }

    @Test
    fun simulateRandomFailure_nodeDisable_triggersRerouteOpportunity() {
        // Force node failure by using a topology where only C is a useful intermediate
        // after removing B—D; then disable C via direct API (existing tests cover path).
        // Here verify random failure on a single-node graph disables that node.
        sim.reset()
        sim.addNode("X")
        val event = sim.simulateRandomFailure(kotlin.random.Random(0))
        assertTrue(event is FailureEvent.NodeDisabled)
        assertEquals("X", (event as FailureEvent.NodeDisabled).nodeId)
        assertTrue(!sim.isNodeEnabled("X"))
    }

    @Test
    fun simulateRandomFailure_linkRemoval_allowsAlternatePath() {
        sim.reset()
        listOf("A", "B", "C").forEach { sim.addNode(it) }
        sim.connect("A", "B")
        sim.connect("B", "C")
        sim.connect("A", "C")
        // Seed that picks a link: try several seeds until link removed, then check reroute
        var removed: SimLink? = null
        for (seed in 0..50) {
            val trial = MeshSimulator().also {
                it.addNode("A"); it.addNode("B"); it.addNode("C")
                it.connect("A", "B"); it.connect("B", "C"); it.connect("A", "C")
            }
            when (val e = trial.simulateRandomFailure(kotlin.random.Random(seed))) {
                is FailureEvent.LinkRemoved -> {
                    removed = e.link
                    val path = trial.findShortestPath("A", "C")
                    assertNotNull("Alternate path should exist after removing ${e.link}", path)
                    break
                }
                else -> Unit
            }
        }
        assertNotNull(removed)
    }

    @Test
    fun hopCount_onDeliveredResult() {
        sim.disconnect("B", "D")
        val result = sim.send("A", "D", "hops", "hop-1") as RoutingResult.Delivered
        assertEquals(3, result.hopCount)
        assertEquals(listOf("A", "B", "C", "D"), result.path)
    }
}
