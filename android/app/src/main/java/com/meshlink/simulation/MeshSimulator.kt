package com.meshlink.simulation

import kotlin.random.Random

/**
 * Deterministic, offline mesh network simulator.
 *
 * - Does **not** use Bluetooth, Wi‑Fi, or Nearby Connections.
 * - Routing is shortest-path BFS over currently enabled nodes and links.
 * - After a node/link failure, the next send recomputes a path (rerouting).
 * - Duplicate [SimMessage.messageId] values are not delivered twice to a destination.
 */
class MeshSimulator {

    private val nodes = linkedMapOf<String, SimNode>()
    private val links = linkedSetOf<SimLink>()
    private val deliveredMessageIds = linkedSetOf<String>()
    private var nextGeneratedId = 1L

    fun snapshotNodes(): List<SimNode> = nodes.values.toList()

    fun snapshotLinks(): List<SimLink> = links.map { it.normalized() }.distinct().sortedBy { "${it.a}-${it.b}" }

    fun deliveredIds(): Set<String> = deliveredMessageIds.toSet()

    /**
     * Enabled node with at least one link to another currently enabled node.
     * Disabled nodes are never "connected" in this sense.
     */
    fun isNodeConnected(id: String): Boolean {
        val key = id.trim().uppercase()
        val node = nodes[key] ?: return false
        if (!node.enabled) return false
        return links.any { link ->
            if (!link.involves(key)) return@any false
            val other = link.other(key)
            nodes[other]?.enabled == true
        }
    }

    /** Human-readable live status lines for the simulation UI. */
    fun nodeStatusLines(): List<String> {
        return snapshotNodes().map { node ->
            val state = when {
                !node.enabled -> "DISABLED"
                isNodeConnected(node.id) -> "ACTIVE"
                else -> "DISCONNECTED"
            }
            "SIM node ${node.id}: $state"
        }
    }

    fun linkStatusLines(): List<String> {
        return snapshotLinks().map { link ->
            val aOk = nodes[link.a]?.enabled == true
            val bOk = nodes[link.b]?.enabled == true
            val state = if (aOk && bOk) "UP" else "DOWN (endpoint disabled)"
            "SIM link ${link.a}—${link.b}: $state"
        }
    }

    /**
     * Randomly disables an enabled node or removes a link using existing APIs.
     * Pass a seeded [Random] for deterministic tests. Returns [FailureEvent.None] if empty.
     */
    fun simulateRandomFailure(random: Random = Random.Default): FailureEvent {
        val enabledNodes = snapshotNodes().filter { it.enabled }.map { it.id }
        val activeLinks = snapshotLinks()
        val options = mutableListOf<Pair<String, Any>>()
        enabledNodes.forEach { options.add("node" to it) }
        activeLinks.forEach { options.add("link" to it) }
        if (options.isEmpty()) return FailureEvent.None

        val pick = options[random.nextInt(options.size)]
        return when (pick.first) {
            "node" -> {
                val id = pick.second as String
                setNodeEnabled(id, false)
                FailureEvent.NodeDisabled(id)
            }
            else -> {
                val link = pick.second as SimLink
                disconnect(link.a, link.b)
                FailureEvent.LinkRemoved(link)
            }
        }
    }

    fun reset() {
        nodes.clear()
        links.clear()
        deliveredMessageIds.clear()
        nextGeneratedId = 1L
    }

    /** Seeds a small demo topology: A—B—C—D plus B—D for alternate routes. */
    fun loadDefaultScenario() {
        reset()
        listOf("A", "B", "C", "D").forEach { addNode(it) }
        connect("A", "B")
        connect("B", "C")
        connect("C", "D")
        connect("B", "D")
    }

    fun addNode(id: String): SimNode {
        val normalized = id.trim().uppercase()
        require(normalized.isNotEmpty()) { "Node id required" }
        val existing = nodes[normalized]
        if (existing != null) return existing
        val node = SimNode(normalized, enabled = true)
        nodes[normalized] = node
        return node
    }

    fun removeNode(id: String): Boolean {
        val key = id.trim().uppercase()
        if (nodes.remove(key) == null) return false
        links.removeAll { it.involves(key) }
        return true
    }

    fun setNodeEnabled(id: String, enabled: Boolean): Boolean {
        val key = id.trim().uppercase()
        val node = nodes[key] ?: return false
        nodes[key] = node.copy(enabled = enabled)
        return true
    }

    fun isNodeEnabled(id: String): Boolean = nodes[id.trim().uppercase()]?.enabled == true

    fun connect(a: String, b: String): SimLink {
        val left = a.trim().uppercase()
        val right = b.trim().uppercase()
        require(nodes.containsKey(left)) { "Unknown node $left" }
        require(nodes.containsKey(right)) { "Unknown node $right" }
        val link = SimLink(left, right).normalized()
        links.add(link)
        return link
    }

    fun disconnect(a: String, b: String): Boolean {
        val link = SimLink(a.trim().uppercase(), b.trim().uppercase()).normalized()
        return links.remove(link)
    }

    fun nextMessageId(prefix: String = "msg"): String {
        val id = "$prefix-${nextGeneratedId.toString().padStart(4, '0')}"
        nextGeneratedId += 1
        return id
    }

    /**
     * Sends [body] from [sourceId] to [destinationId] along the current shortest path.
     * If [messageId] was already delivered, returns [RoutingResult.Duplicate].
     */
    fun send(
        sourceId: String,
        destinationId: String,
        body: String,
        messageId: String = nextMessageId()
    ): RoutingResult {
        val source = sourceId.trim().uppercase()
        val destination = destinationId.trim().uppercase()
        val message = SimMessage(messageId, source, destination, body)

        if (!nodes.containsKey(source)) {
            return RoutingResult.Failed(messageId, "Unknown source $source")
        }
        if (!nodes.containsKey(destination)) {
            return RoutingResult.Failed(messageId, "Unknown destination $destination")
        }
        if (nodes[source]?.enabled != true) {
            return RoutingResult.Failed(messageId, "Source $source is disabled")
        }
        if (nodes[destination]?.enabled != true) {
            return RoutingResult.Failed(messageId, "Destination $destination is disabled")
        }
        if (deliveredMessageIds.contains(messageId)) {
            return RoutingResult.Duplicate(messageId, destination)
        }

        val path = findShortestPath(source, destination)
            ?: return RoutingResult.Failed(
                messageId,
                "No route from $source to $destination in the current simulated topology"
            )

        deliveredMessageIds.add(messageId)
        return RoutingResult.Delivered(
            messageId = messageId,
            path = path,
            body = message.body
        )
    }

    /**
     * BFS shortest path over enabled nodes and existing links.
     * Neighbor iteration is sorted for deterministic routes.
     */
    fun findShortestPath(sourceId: String, destinationId: String): List<String>? {
        val source = sourceId.trim().uppercase()
        val destination = destinationId.trim().uppercase()
        if (source == destination) return listOf(source)
        if (nodes[source]?.enabled != true || nodes[destination]?.enabled != true) return null

        val adjacency = buildAdjacency()
        val queue = ArrayDeque<String>()
        val visited = linkedSetOf<String>()
        val parent = linkedMapOf<String, String>()

        queue.add(source)
        visited.add(source)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val neighbors = adjacency[current].orEmpty()
            for (next in neighbors) {
                if (next in visited) continue
                visited.add(next)
                parent[next] = current
                if (next == destination) {
                    return reconstructPath(parent, source, destination)
                }
                queue.add(next)
            }
        }
        return null
    }

    private fun buildAdjacency(): Map<String, List<String>> {
        val map = linkedMapOf<String, MutableList<String>>()
        nodes.values.filter { it.enabled }.forEach { map[it.id] = mutableListOf() }
        for (link in links) {
            val left = nodes[link.a]
            val right = nodes[link.b]
            if (left == null || right == null) continue
            if (!left.enabled || !right.enabled) continue
            map.getOrPut(link.a) { mutableListOf() }.add(link.b)
            map.getOrPut(link.b) { mutableListOf() }.add(link.a)
        }
        map.keys.toList().forEach { key ->
            map[key] = map[key].orEmpty().distinct().sorted().toMutableList()
        }
        return map
    }

    private fun reconstructPath(
        parent: Map<String, String>,
        source: String,
        destination: String
    ): List<String> {
        val path = ArrayDeque<String>()
        var cursor: String? = destination
        while (cursor != null) {
            path.addFirst(cursor)
            if (cursor == source) break
            cursor = parent[cursor]
        }
        return path.toList()
    }
}
