package com.dongbantest.app.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** "들은 노래 기록", independent of voice recording consent/pause. */
class MusicSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("music_settings", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled = mutableEnabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
        mutableEnabled.value = enabled
    }
}
