package com.meshlink

import android.content.Context
import android.content.SharedPreferences

enum class MeshTheme(val value: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromValue(value: String?): MeshTheme =
            entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

class UserPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDisplayName(): String? = prefs.getString(KEY_DISPLAY_NAME, null)

    fun setDisplayName(name: String) {
        prefs.edit().putString(KEY_DISPLAY_NAME, name.trim()).apply()
    }

    fun getTheme(): MeshTheme = MeshTheme.fromValue(prefs.getString(KEY_THEME, null))

    fun setTheme(theme: MeshTheme) {
        prefs.edit().putString(KEY_THEME, theme.value).apply()
    }

    fun shouldAutoStartDiscovery(): Boolean =
        prefs.getBoolean(KEY_AUTO_START_DISCOVERY, false)

    fun setAutoStartDiscovery(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START_DISCOVERY, enabled).apply()
    }

    fun shouldShowNearbyDeviceNotifications(): Boolean =
        prefs.getBoolean(KEY_NEARBY_NOTIFICATIONS, false)

    fun setShowNearbyDeviceNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NEARBY_NOTIFICATIONS, enabled).apply()
    }

    fun shouldPlaySendSound(): Boolean =
        prefs.getBoolean(KEY_SEND_SOUND, true)

    fun setPlaySendSound(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SEND_SOUND, enabled).apply()
    }

    fun shouldEnterSendMessage(): Boolean =
        prefs.getBoolean(KEY_ENTER_TO_SEND, false)

    fun setEnterToSend(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENTER_TO_SEND, enabled).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "meshlink_prefs"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_THEME = "theme"
        private const val KEY_AUTO_START_DISCOVERY = "auto_start_discovery"
        private const val KEY_NEARBY_NOTIFICATIONS = "nearby_notifications"
        private const val KEY_SEND_SOUND = "send_sound"
        private const val KEY_ENTER_TO_SEND = "enter_to_send"
    }
}
