package com.meshlink.routing

/**
 * One-hop send API used by [MeshRouter].
 * Production uses Nearby Connections; unit tests use an in-memory fabric.
 */
interface MeshTransport {
    fun sendToEndpoint(endpointId: String, encodedPacket: String): Boolean

    fun connectedEndpointIds(): Set<String>
}
