package com.dongbantest.app.data.talk

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RecordingSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("recording_settings", Context.MODE_PRIVATE)
    private val boost = MutableStateFlow(prefs.getBoolean(MIC_BOOST, false))
    val micBoostEnabled = boost.asStateFlow()

    fun setMicBoostEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(MIC_BOOST, enabled).apply()
        boost.value = enabled
    }

    private companion object {
        const val MIC_BOOST = "mic_boost_enabled"
    }
}
