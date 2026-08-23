package com.meshlink.admin

import com.meshlink.crypto.AdminKeyManager
import com.meshlink.routing.MeshRouter
import com.meshlink.routing.SendResult

/**
 * Handles execution of validated [AdminCommand] objects.
 */
class AdminCommandHandler(
    private val trustStore: AdminTrustStore,
    private val keyManager: AdminKeyManager = AdminKeyManager()
) {

    /**
     * Executes a validated [AdminCommand] and returns an [AdminCommandResult].
     */
    fun executeCommand(
        command: AdminCommand,
        router: MeshRouter? = null,
        peersProvider: (() -> List<String>)? = null
    ): AdminCommandResult {
        return when (command.type) {
            AdminCommandType.BROADCAST -> executeBroadcast(command, router, peersProvider)
            AdminCommandType.BLOCK_NODE -> executeBlockNode(command)
            AdminCommandType.UNBLOCK_NODE -> executeUnblockNode(command)
            AdminCommandType.REQUEST_TOPOLOGY -> executeRequestTopology(command, router, peersProvider)
            AdminCommandType.REQUEST_NETWORK_STATUS -> executeRequestNetworkStatus(command, router, peersProvider)
        }
    }

    private fun executeBroadcast(
        command: AdminCommand,
        router: MeshRouter?,
        peersProvider: (() -> List<String>)?
    ): AdminCommandResult {
        val payload = AdminPacketCodec.encode(command)

        if (router == null) {
            val result = BroadcastResult(
                messageId = command.commandId,
                commandId = command.commandId,
                broadcastText = command.commandData,
                sentViaEndpoints = emptyList(),
                targetCount = 0,
                deliveredCount = 0,
                statusMessage = "Broadcast prepared (No active router)"
            )
            return AdminCommandResult(
                commandId = command.commandId,
                type = command.type,
                success = true,
                message = result.statusMessage,
                payload = result
            )
        }

        // Send via MeshRouter towards broadcast address "*"
        val sendResult = router.send(destinationNodeId = "*", payload = payload)
        val knownNodes = router.routeTable().map { it.destinationNodeId }.distinct()
        val targetCount = knownNodes.size.coerceAtLeast(1)

        val broadcastResult = when (sendResult) {
            is SendResult.Sent -> {
                val deliveredCount = sendResult.viaEndpoints.size
                val statusMsg = if (deliveredCount >= targetCount) {
                    "Broadcast transmitted to all $deliveredCount reachable direct endpoints."
                } else {
                    "Broadcast sent via $deliveredCount endpoint(s). Total known mesh targets: $targetCount."
                }
                BroadcastResult(
                    messageId = sendResult.packet.messageId,
                    commandId = command.commandId,
                    broadcastText = command.commandData,
                    sentViaEndpoints = sendResult.viaEndpoints,
                    targetCount = targetCount,
                    deliveredCount = deliveredCount,
                    statusMessage = statusMsg
                )
            }
            is SendResult.Forwarded -> {
                BroadcastResult(
                    messageId = sendResult.packet.messageId,
                    commandId = command.commandId,
                    broadcastText = command.commandData,
                    sentViaEndpoints = sendResult.viaEndpoints,
                    targetCount = targetCount,
                    deliveredCount = sendResult.viaEndpoints.size,
                    statusMessage = "Broadcast forwarded via ${sendResult.viaEndpoints.size} endpoint(s)."
                )
            }
            is SendResult.Stored -> {
                BroadcastResult(
                    messageId = sendResult.packet.messageId,
                    commandId = command.commandId,
                    broadcastText = command.commandData,
                    sentViaEndpoints = emptyList(),
                    targetCount = targetCount,
                    deliveredCount = 0,
                    statusMessage = "No active neighbors connected. Broadcast queued in store-and-forward buffer."
                )
            }
            is SendResult.Failed -> {
                BroadcastResult(
                    messageId = command.commandId,
                    commandId = command.commandId,
                    broadcastText = command.commandData,
                    sentViaEndpoints = emptyList(),
                    targetCount = targetCount,
                    deliveredCount = 0,
                    statusMessage = "Broadcast dispatch failed: ${sendResult.reason}"
                )
            }
        }

        return AdminCommandResult(
            commandId = command.commandId,
            type = command.type,
            success = sendResult !is SendResult.Failed,
            message = broadcastResult.statusMessage,
            payload = broadcastResult
        )
    }

    private fun executeBlockNode(command: AdminCommand): AdminCommandResult {
        val targetId = command.targetNodeId
        if (targetId.isBlank() || targetId == "*") {
            return AdminCommandResult(
                commandId = command.commandId,
                type = command.type,
                success = false,
                message = "Invalid block target: node ID must not be blank or wildcard"
            )
        }
        trustStore.blockNode(targetId)
        return AdminCommandResult(
            commandId = command.commandId,
            type = command.type,
            success = true,
            message = "Node '$targetId' successfully blocked and added to Admin trust store."
        )
    }

    private fun executeUnblockNode(command: AdminCommand): AdminCommandResult {
        val targetId = command.targetNodeId
        if (targetId.isBlank() || targetId == "*") {
            return AdminCommandResult(
                commandId = command.commandId,
                type = command.type,
                success = false,
                message = "Invalid unblock target: node ID must not be blank or wildcard"
            )
        }
        trustStore.unblockNode(targetId)
        return AdminCommandResult(
            commandId = command.commandId,
            type = command.type,
            success = true,
            message = "Node '$targetId' successfully removed from blocklist."
        )
    }

    private fun executeRequestTopology(
        command: AdminCommand,
        router: MeshRouter?,
        peersProvider: (() -> List<String>)?
    ): AdminCommandResult {
        val localId = router?.localNodeId ?: "local"
        val routeEntries = router?.routeTable() ?: emptyList()
        val blockedNodes = trustStore.getBlockedNodeIds()
        val connectedPeers = peersProvider?.invoke() ?: emptyList()

        val routeInfos = routeEntries.map { entry ->
            RouteInfo(
                destinationNodeId = entry.destinationNodeId,
                nextHopEndpointId = entry.nextHopEndpointId,
                hopCount = entry.hopCount,
                lastSeenMs = entry.lastUpdatedMs
            )
        }

        val nodeStatuses = mutableListOf<NodeStatus>()
        val knownNodeIds = routeEntries.map { it.destinationNodeId }.toSet() + connectedPeers.toSet()

        for (nodeId in knownNodeIds) {
            val isDirect = connectedPeers.contains(nodeId) || routeEntries.any { it.destinationNodeId == nodeId && it.hopCount == 1 }
            val stateStr = if (isDirect) "CONNECTED" else "INDIRECT"
            val hopCount = routeEntries.firstOrNull { it.destinationNodeId == nodeId }?.hopCount ?: 1

            nodeStatuses.add(
                NodeStatus(
                    nodeId = nodeId,
                    displayName = nodeId,
                    endpointId = router?.endpointForNode(nodeId),
                    connectionState = stateStr,
                    isBlocked = blockedNodes.contains(nodeId),
                    hopCount = hopCount
                )
            )
        }

        val snapshot = TopologySnapshot(
            localNodeId = localId,
            nodes = nodeStatuses,
            routes = routeInfos
        )

        return AdminCommandResult(
            commandId = command.commandId,
            type = command.type,
            success = true,
            message = "Topology snapshot retrieved (${nodeStatuses.size} nodes, ${routeInfos.size} routes)",
            payload = snapshot
        )
    }

    private fun executeRequestNetworkStatus(
        command: AdminCommand,
        router: MeshRouter?,
        peersProvider: (() -> List<String>)?
    ): AdminCommandResult {
        val routeEntries = router?.routeTable() ?: emptyList()
        val connectedPeers = peersProvider?.invoke() ?: emptyList()
        val storedPackets = router?.storedPackets() ?: emptyList()

        val knownNodeIds = routeEntries.map { it.destinationNodeId }.toSet() + connectedPeers.toSet()
        val connectedCount = connectedPeers.size
        val knownCount = knownNodeIds.size
        val routeCount = routeEntries.size
        val queuedCount = storedPackets.size

        val healthStr = when {
            connectedCount > 0 -> "HEALTHY"
            knownCount > 0 || routeCount > 0 -> "DEGRADED"
            else -> "OFFLINE"
        }

        val topologyResult = executeRequestTopology(command, router, peersProvider)
        val topologySnapshot = topologyResult.payload as? TopologySnapshot
        val nodeStatuses = topologySnapshot?.nodes ?: emptyList()

        val netStatus = NetworkStatus(
            connectedPeerCount = connectedCount,
            knownPeerCount = knownCount,
            activeRouteCount = routeCount,
            queuedMessageCount = queuedCount,
            healthStatus = healthStr,
            nodeStatuses = nodeStatuses
        )

        return AdminCommandResult(
            commandId = command.commandId,
            type = command.type,
            success = true,
            message = "Network status: $healthStr ($connectedCount connected, $knownCount known, $routeCount routes)",
            payload = netStatus
        )
    }
}
