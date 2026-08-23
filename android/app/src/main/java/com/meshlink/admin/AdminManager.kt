package com.meshlink.admin

import kotlin.math.abs

/**
 * Central security manager that validates incoming ADM1 command frames
 * against trusted Admin public keys, digital signatures, timestamp freshness,
 * and sequence counter anti-replay rules.
 */
class AdminManager(
    private val trustStore: AdminTrustStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxClockSkewMs: Long = DEFAULT_MAX_CLOCK_SKEW_MS
) {
    val blocklistFilter = BlocklistFilter(trustStore)

    /**
     * Validates a raw incoming payload string against the security pipeline.
     *
     * Pipeline Order:
     * 1. ADM1 Envelope Format Decoding
     * 2. Trusted Admin Public Key & Fingerprint Retrieval
     * 3. Fingerprint Match Verification
     * 4. Digital Signature Verification (MUST run before sequence counter or timestamp trust)
     * 5. Timestamp Freshness Window Verification
     * 6. Sequence Counter Anti-Replay Verification
     * 7. Blocklist Verification
     * 8. Atomic Sequence State Update (Executed ONLY if all checks pass)
     */
    fun validateIncomingPayload(payload: String, sourceNodeId: String? = null): ValidationResult {
        if (!AdminPacketCodec.isAdminFrame(payload)) {
            return ValidationResult.InvalidFormat("Payload is not an ADM1 frame")
        }

        val command = AdminPacketCodec.decode(payload)
            ?: return ValidationResult.InvalidFormat("Failed to decode ADM1 envelope")

        // 1. Untrusted Admin check
        val trustedPubKeyBase64 = trustStore.getTrustedAdminPublicKey()
            ?: return ValidationResult.UntrustedAdmin()

        val trustedFingerprint = trustStore.getTrustedAdminFingerprint()
            ?: return ValidationResult.UntrustedAdmin("Trusted Admin fingerprint missing")

        // 2. Fingerprint check
        if (command.adminFingerprint != trustedFingerprint) {
            return ValidationResult.AdminFingerprintMismatch()
        }

        // 3. Digital signature verification (CRITICAL: Must verify signature before sequence or payload trust)
        val isSignatureValid = AdminPacketCodec.verifySignature(command, trustedPubKeyBase64)
        if (!isSignatureValid) {
            return ValidationResult.InvalidSignature()
        }

        // 4. Timestamp freshness check
        val now = clock()
        val skew = abs(now - command.timestampMs)
        if (skew > maxClockSkewMs) {
            return ValidationResult.ExpiredOrFutureTimestamp(
                "Timestamp ${command.timestampMs} skewed by $skew ms from current time $now (max allowed: $maxClockSkewMs ms)"
            )
        }

        // 5. Sequence counter anti-replay check
        val lastSeq = trustStore.getLastSequenceNumber()
        if (command.sequenceNumber <= lastSeq) {
            return ValidationResult.ReplayedSequence(
                "Sequence number ${command.sequenceNumber} is <= last seen sequence number $lastSeq"
            )
        }

        // 6. Blocklist check
        if (command.type != AdminCommandType.UNBLOCK_NODE && blocklistFilter.isBlocked(command.targetNodeId)) {
            return ValidationResult.BlockedNode("Target node ${command.targetNodeId} is blocked")
        }
        if (sourceNodeId != null && blocklistFilter.isBlocked(sourceNodeId)) {
            return ValidationResult.BlockedNode("Source node $sourceNodeId is blocked")
        }

        // 7. ATOMIC STATE UPDATE: Update stored sequence counter ONLY after all validations pass
        val updated = trustStore.updateSequenceNumber(command.sequenceNumber)
        if (!updated) {
            return ValidationResult.ReplayedSequence("Sequence number conflict during state update")
        }

        return ValidationResult.Valid(command)
    }

    /**
     * Checks if a raw payload string is an Admin frame.
     */
    fun isAdminPayload(payload: String): Boolean {
        return AdminPacketCodec.isAdminFrame(payload)
    }

    companion object {
        const val DEFAULT_MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L // 5 minutes
    }
}
