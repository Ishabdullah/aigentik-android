package com.aigentik.app.ui

import androidx.appcompat.app.AppCompatDelegate
import com.aigentik.app.core.AigentikSettings

object ThemeHelper {
    fun applyTheme(themeMode: Int) {
        val mode = when (themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun applySavedTheme() {
        applyTheme(AigentikSettings.themeMode)
    }
}
