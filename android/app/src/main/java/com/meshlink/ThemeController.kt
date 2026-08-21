package com.meshlink

import androidx.appcompat.app.AppCompatDelegate

object ThemeController {
    fun applySaved(preferences: UserPreferences) {
        apply(preferences.getTheme())
    }

    fun apply(theme: MeshTheme) {
        val mode = when (theme) {
            MeshTheme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            MeshTheme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MeshTheme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
