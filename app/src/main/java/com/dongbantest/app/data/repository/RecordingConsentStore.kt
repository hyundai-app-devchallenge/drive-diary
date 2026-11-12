package com.dongbantest.app.data.repository

import android.content.Context

/**
 * 여행마다 "녹음에 동의했는지"를 기억한다 (S03 녹음 동의).
 * true = 모두 동의하고 출발, false = 녹음 없이 출발, null = 아직 묻지 않음.
 * 앱을 다시 켜도 유지되도록 SharedPreferences 에 저장한다.
 */
class RecordingConsentStore(context: Context) {
    private val prefs = context.getSharedPreferences("recording_consent", Context.MODE_PRIVATE)

    fun get(tripId: Long): Boolean? =
        if (prefs.contains(key(tripId))) prefs.getBoolean(key(tripId), false) else null

    fun set(tripId: Long, consent: Boolean) {
        prefs.edit().putBoolean(key(tripId), consent).apply()
    }

    private fun key(tripId: Long) = "trip_$tripId"
}
