package com.meshlink.admin

/**
 * Supported Admin command types in MeshLink.
 */
enum class AdminCommandType(val wireName: String) {
    BROADCAST("BROADCAST"),
    BLOCK_NODE("BLOCK_NODE"),
    UNBLOCK_NODE("UNBLOCK_NODE"),
    REQUEST_TOPOLOGY("REQUEST_TOPOLOGY"),
    REQUEST_NETWORK_STATUS("REQUEST_NETWORK_STATUS");

    companion object {
        fun fromWireName(wireName: String): AdminCommandType? {
            return entries.firstOrNull { it.wireName.equals(wireName, ignoreCase = true) }
        }
    }
}

/**
 * Data model for an authenticated MeshLink Admin command.
 *
 * Provides a canonical string representation to prevent signature ambiguity.
 */
data class AdminCommand(
    val type: AdminCommandType,
    val commandId: String,
    val adminFingerprint: String,
    val targetNodeId: String,
    val sequenceNumber: Long,
    val timestampMs: Long,
    val commandData: String,
    val signatureBase64: String = ""
) {
    init {
        require(commandId.isNotBlank()) { "commandId must not be blank" }
        require(adminFingerprint.isNotBlank()) { "adminFingerprint must not be blank" }
        require(targetNodeId.isNotBlank()) { "targetNodeId must not be blank" }
        require(sequenceNumber > 0) { "sequenceNumber must be positive" }
        require(timestampMs > 0) { "timestampMs must be positive" }
    }

    /**
     * Canonical string representation signed by Admin and verified by receiving nodes.
     * Covers all security-critical fields to prevent tampering.
     */
    fun toCanonicalString(): String {
        return "${type.wireName}|$commandId|$adminFingerprint|$targetNodeId|$sequenceNumber|$timestampMs|$commandData"
    }

    /**
     * Canonical byte representation for digital signing and verification.
     */
    fun toSignableBytes(): ByteArray {
        return toCanonicalString().toByteArray(Charsets.UTF_8)
    }
}
