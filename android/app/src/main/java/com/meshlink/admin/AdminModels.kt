package com.meshlink.admin

/**
 * Result of an executed Admin command.
 */
data class AdminCommandResult(
    val commandId: String,
    val type: AdminCommandType,
    val success: Boolean,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val payload: Any? = null
)

/**
 * Telemetry status of a single node in the mesh network.
 */
data class NodeStatus(
    val nodeId: String,
    val displayName: String,
    val endpointId: String? = null,
    val connectionState: String = "DISCONNECTED",
    val isBlocked: Boolean = false,
    val hopCount: Int = 1
)

/**
 * Route entry telemetry information.
 */
data class RouteInfo(
    val destinationNodeId: String,
    val nextHopEndpointId: String,
    val hopCount: Int,
    val lastSeenMs: Long
)

/**
 * Snapshot of the current mesh network topology.
 */
data class TopologySnapshot(
    val localNodeId: String,
    val nodes: List<NodeStatus>,
    val routes: List<RouteInfo>,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Overall network status and health metrics.
 */
data class NetworkStatus(
    val connectedPeerCount: Int,
    val knownPeerCount: Int,
    val activeRouteCount: Int,
    val queuedMessageCount: Int,
    val healthStatus: String,
    val nodeStatuses: List<NodeStatus>,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Status result of an Admin broadcast message dispatch.
 */
data class BroadcastResult(
    val messageId: String,
    val commandId: String,
    val broadcastText: String,
    val sentViaEndpoints: List<String>,
    val targetCount: Int,
    val deliveredCount: Int,
    val statusMessage: String,
    val timestampMs: Long = System.currentTimeMillis()
)
