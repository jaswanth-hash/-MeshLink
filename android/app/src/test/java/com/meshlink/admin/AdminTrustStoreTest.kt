package com.meshlink.admin

import android.content.SharedPreferences
import com.meshlink.crypto.AdminKeyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdminTrustStoreTest {

    private lateinit var trustStore: AdminTrustStore
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        trustStore = AdminTrustStore(fakePrefs)
    }

    @Test
    fun setAndGetTrustedAdmin_persistsKeyAndFingerprint() {
        val keyManager = AdminKeyManager()
        keyManager.generateKeyPair()
        val pubKeyBase64 = keyManager.getPublicKeyBase64()!!
        val expectedFingerprint = keyManager.getFingerprint()!!

        assertNull(trustStore.getTrustedAdminPublicKey())
        assertNull(trustStore.getTrustedAdminFingerprint())

        val success = trustStore.setTrustedAdmin(pubKeyBase64)
        assertTrue(success)

        assertEquals(pubKeyBase64, trustStore.getTrustedAdminPublicKey())
        assertEquals(expectedFingerprint, trustStore.getTrustedAdminFingerprint())
        assertTrue(trustStore.isTrustedAdmin(pubKeyBase64))
        assertTrue(trustStore.isTrustedAdminFingerprint(expectedFingerprint))
    }

    @Test
    fun clearTrustedAdmin_removesTrustedState() {
        val keyManager = AdminKeyManager()
        keyManager.generateKeyPair()
        val pubKeyBase64 = keyManager.getPublicKeyBase64()!!

        trustStore.setTrustedAdmin(pubKeyBase64)
        trustStore.updateSequenceNumber(42L)

        assertNotNull(trustStore.getTrustedAdminPublicKey())
        assertEquals(42L, trustStore.getLastSequenceNumber())

        trustStore.clearTrustedAdmin()

        assertNull(trustStore.getTrustedAdminPublicKey())
        assertNull(trustStore.getTrustedAdminFingerprint())
        assertEquals(0L, trustStore.getLastSequenceNumber())
    }

    @Test
    fun updateSequenceNumber_rejectsReplaysAndEqualCounters() {
        assertEquals(0L, trustStore.getLastSequenceNumber())

        assertTrue(trustStore.updateSequenceNumber(10L))
        assertEquals(10L, trustStore.getLastSequenceNumber())

        // Replay attack with same sequence number -> rejected
        assertFalse(trustStore.updateSequenceNumber(10L))
        assertEquals(10L, trustStore.getLastSequenceNumber())

        // Replay attack with older sequence number -> rejected
        assertFalse(trustStore.updateSequenceNumber(5L))
        assertEquals(10L, trustStore.getLastSequenceNumber())

        // Higher sequence number -> accepted
        assertTrue(trustStore.updateSequenceNumber(11L))
        assertEquals(11L, trustStore.getLastSequenceNumber())
    }

    @Test
    fun blocklist_managesRevokedNodesCorrectly() {
        val nodeA = "node-alpha"
        val nodeB = "node-beta"

        assertFalse(trustStore.isNodeBlocked(nodeA))
        assertTrue(trustStore.getBlockedNodeIds().isEmpty())

        trustStore.blockNode(nodeA)
        assertTrue(trustStore.isNodeBlocked(nodeA))
        assertFalse(trustStore.isNodeBlocked(nodeB))
        assertEquals(setOf(nodeA), trustStore.getBlockedNodeIds())

        trustStore.blockNode(nodeB)
        assertTrue(trustStore.isNodeBlocked(nodeA))
        assertTrue(trustStore.isNodeBlocked(nodeB))
        assertEquals(setOf(nodeA, nodeB), trustStore.getBlockedNodeIds())

        trustStore.unblockNode(nodeA)
        assertFalse(trustStore.isNodeBlocked(nodeA))
        assertTrue(trustStore.isNodeBlocked(nodeB))

        trustStore.clearBlocklist()
        assertTrue(trustStore.getBlockedNodeIds().isEmpty())
    }
}

/**
 * Lightweight in-memory SharedPreferences for unit testing AdminTrustStore without Android context.
 */
class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = data.toMap()

    override fun getString(key: String, defValue: String?): String? =
        (data[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (data[key] as? Set<String>) ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        (data[key] as? Int) ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        (data[key] as? Long) ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (data[key] as? Float) ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        (data[key] as? Boolean) ?: defValue

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class EditorImpl : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            temp[key] = value
            removes.remove(key)
            return this
        }

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            temp[key] = values?.toSet()
            removes.remove(key)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            temp[key] = value
            removes.remove(key)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            temp[key] = value
            removes.remove(key)
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            temp[key] = value
            removes.remove(key)
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            temp[key] = value
            removes.remove(key)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            removes.add(key)
            temp.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            temp.clear()
            removes.clear()
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) {
                data.clear()
            }
            for (r in removes) {
                data.remove(r)
            }
            data.putAll(temp)
        }
    }
}
