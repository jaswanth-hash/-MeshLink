package com.meshlink.network

import com.meshlink.routing.MeshTransport

/**
 * Adapts [MeshNetworkManager] as a one-hop [MeshTransport] for [com.meshlink.routing.MeshRouter].
 * Does not alter discovery/connection behavior.
 */
class NearbyMeshTransport(
    private val networkProvider: () -> MeshNetworkManager?
) : MeshTransport {

    override fun sendToEndpoint(endpointId: String, encodedPacket: String): Boolean {
        val network = networkProvider() ?: return false
        return network.sendMessage(endpointId, encodedPacket)
    }

    override fun connectedEndpointIds(): Set<String> {
        val network = networkProvider() ?: return emptySet()
        return network.getPeers()
            .filter { it.connectionState == ConnectionState.CONNECTED }
            .map { it.endpointId }
            .toSet()
    }
}
