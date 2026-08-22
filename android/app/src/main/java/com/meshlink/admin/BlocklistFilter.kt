package com.meshlink.admin

/**
 * Filter that queries [AdminTrustStore] to check if nodes are blocked/revoked.
 */
class BlocklistFilter(private val trustStore: AdminTrustStore) {

    /**
     * Returns true if [nodeId] is currently blocked or revoked.
     */
    fun isBlocked(nodeId: String): Boolean {
        if (nodeId.isBlank()) return false
        return trustStore.isNodeBlocked(nodeId)
    }

    /**
     * Returns true if any of the provided node IDs are blocked.
     */
    fun isAnyBlocked(vararg nodeIds: String): Boolean {
        return nodeIds.any { isBlocked(it) }
    }
}
