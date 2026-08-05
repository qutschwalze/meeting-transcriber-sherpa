package com.sherpa.transcript.data.local

import android.content.Context
import com.sherpa.transcript.SherpaTranscriptApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phase 5 (0.6.8): Darstellungsmodus – System folgen, hell, dunkel. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Phase 5 (0.6.8): Persistente App-Einstellungen via SharedPreferences.
 * Werte werden sofort gespeichert UND als StateFlow veröffentlicht – die UI
 * (MainActivity-Theme, LiveScreen, SettingsScreen) beobachtet die Flows und
 * reagiert live auf Änderungen.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _fontSize = MutableStateFlow(prefs.getFloat(KEY_FONT_SIZE, 16f))
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    private val _debugMode = MutableStateFlow(prefs.getBoolean(KEY_DEBUG, false))
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setFontSize(size: Float) {
        prefs.edit().putFloat(KEY_FONT_SIZE, size).apply()
        _fontSize.value = size
    }

    fun setDebugMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG, enabled).apply()
        _debugMode.value = enabled
    }

    companion object {
        private const val KEY_THEME = "themeMode"
        private const val KEY_FONT_SIZE = "fontSize"
        private const val KEY_DEBUG = "debugMode"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }

        val current: SettingsStore
            get() = get(SherpaTranscriptApp.instance)
    }
}
