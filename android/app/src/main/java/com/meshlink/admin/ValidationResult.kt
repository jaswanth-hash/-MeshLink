package com.meshlink.admin

/**
 * Outcomes for incoming ADM1 Admin command validation.
 */
sealed class ValidationResult {
    data class Valid(val command: AdminCommand) : ValidationResult()
    data class InvalidFormat(val reason: String = "Invalid ADM1 envelope format") : ValidationResult()
    data class UntrustedAdmin(val reason: String = "No trusted Admin established") : ValidationResult()
    data class AdminFingerprintMismatch(val reason: String = "Admin fingerprint mismatch") : ValidationResult()
    data class InvalidSignature(val reason: String = "Signature verification failed") : ValidationResult()
    data class ExpiredOrFutureTimestamp(val reason: String = "Timestamp outside valid clock skew window") : ValidationResult()
    data class ReplayedSequence(val reason: String = "Sequence counter is replayed or older") : ValidationResult()
    data class BlockedNode(val reason: String = "Node is on the blocklist") : ValidationResult()
}
