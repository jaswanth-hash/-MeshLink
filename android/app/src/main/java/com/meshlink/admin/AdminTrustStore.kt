package com.meshlink.admin

import android.content.Context
import android.content.SharedPreferences
import com.meshlink.crypto.AdminKeyManager

/**
 * Persists trusted Admin identity, sequence counter for anti-replay,
 * and node revocation/blocklist state.
 */
class AdminTrustStore(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /**
     * Returns Base64 string of trusted Admin public key, or null if no Admin established.
     */
    fun getTrustedAdminPublicKey(): String? =
        prefs.getString(KEY_ADMIN_PUBKEY, null)

    /**
     * Returns SHA-256 fingerprint of trusted Admin public key, or null if no Admin established.
     */
    fun getTrustedAdminFingerprint(): String? =
        prefs.getString(KEY_ADMIN_FINGERPRINT, null)

    /**
     * Sets or updates the trusted Admin public key and fingerprint (for Genesis or Admin Transfer).
     */
    @Synchronized
    fun setTrustedAdmin(publicKeyBase64: String): Boolean {
        val fingerprint = AdminKeyManager.computeFingerprint(publicKeyBase64) ?: return false
        prefs.edit()
            .putString(KEY_ADMIN_PUBKEY, publicKeyBase64)
            .putString(KEY_ADMIN_FINGERPRINT, fingerprint)
            .apply()
        return true
    }

    /**
     * Checks if a given public key Base64 matches the trusted Admin public key.
     */
    fun isTrustedAdmin(publicKeyBase64: String): Boolean {
        val trusted = getTrustedAdminPublicKey() ?: return false
        return trusted == publicKeyBase64
    }

    /**
     * Checks if a given fingerprint matches the trusted Admin fingerprint.
     */
    fun isTrustedAdminFingerprint(fingerprint: String): Boolean {
        val trusted = getTrustedAdminFingerprint() ?: return false
        return trusted == fingerprint
    }

    /**
     * Clears trusted Admin state (for Admin revocation/reset).
     */
    @Synchronized
    fun clearTrustedAdmin() {
        prefs.edit()
            .remove(KEY_ADMIN_PUBKEY)
            .remove(KEY_ADMIN_FINGERPRINT)
            .remove(KEY_LAST_SEQ_NUM)
            .apply()
    }

    /**
     * Returns highest sequence number processed from trusted Admin, defaulting to 0.
     */
    fun getLastSequenceNumber(): Long =
        prefs.getLong(KEY_LAST_SEQ_NUM, 0L)

    /**
     * Updates the highest sequence number if [sequenceNumber] > current sequence number.
     * Returns true if sequence number was updated, false if rejected (replay attack).
     */
    @Synchronized
    fun updateSequenceNumber(sequenceNumber: Long): Boolean {
        val current = getLastSequenceNumber()
        if (sequenceNumber <= current) {
            return false
        }
        prefs.edit().putLong(KEY_LAST_SEQ_NUM, sequenceNumber).apply()
        return true
    }

    /**
     * Returns set of blocked/revoked node IDs.
     */
    fun getBlockedNodeIds(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_NODES, emptySet()) ?: emptySet()

    /**
     * Checks if a node ID is currently in the blocklist.
     */
    fun isNodeBlocked(nodeId: String): Boolean =
        getBlockedNodeIds().contains(nodeId)

    /**
     * Blocks/revokes a node ID.
     */
    @Synchronized
    fun blockNode(nodeId: String) {
        if (nodeId.isBlank()) return
        val current = getBlockedNodeIds().toMutableSet()
        current.add(nodeId)
        prefs.edit().putStringSet(KEY_BLOCKED_NODES, current).apply()
    }

    /**
     * Unblocks/restores a node ID.
     */
    @Synchronized
    fun unblockNode(nodeId: String) {
        val current = getBlockedNodeIds().toMutableSet()
        if (current.remove(nodeId)) {
            prefs.edit().putStringSet(KEY_BLOCKED_NODES, current).apply()
        }
    }

    /**
     * Clears all blocked nodes.
     */
    @Synchronized
    fun clearBlocklist() {
        prefs.edit().remove(KEY_BLOCKED_NODES).apply()
    }

    /**
     * Clears all Admin trust store data.
     */
    @Synchronized
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val PREFS_NAME = "meshlink_admin_trust"
        private const val KEY_ADMIN_PUBKEY = "admin_pubkey"
        private const val KEY_ADMIN_FINGERPRINT = "admin_fingerprint"
        private const val KEY_LAST_SEQ_NUM = "last_seq_num"
        private const val KEY_BLOCKED_NODES = "blocked_nodes"
    }
}
