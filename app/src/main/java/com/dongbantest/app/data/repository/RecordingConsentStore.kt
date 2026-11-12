package com.dongbantest.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/**
 * 여행마다 "녹음에 동의했는지"를 기억한다 (S03 녹음 동의).
 * true = 모두 동의하고 출발, false = 녹음 없이 출발, null = 아직 묻지 않음.
 * 앱을 다시 켜도 유지되도록 SharedPreferences 에 저장한다.
 *
 * talk-rec 연동: 동의 (2) "전사 글을 외부 AI로 보내 제목·태그 만들기"를 별도 키로 함께 저장한다.
 * 기존 키(trip_N)는 그대로라서 예전에 저장된 값도 그대로 읽히고, AI 키가 없으면 false(보내지 않음)다.
 */
class RecordingConsentStore(context: Context) {
    private val prefs = context.getSharedPreferences("recording_consent", Context.MODE_PRIVATE)

    /** Includes changes written by other store instances (for example S03). */
    fun changes() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun get(tripId: Long): Boolean? =
        if (prefs.contains(key(tripId))) prefs.getBoolean(key(tripId), false) else null

    fun set(tripId: Long, consent: Boolean) {
        prefs.edit().putBoolean(key(tripId), consent).apply()
    }

    /** 전사 글을 외부 AI로 보내도 되는지. 녹음 동의가 true 가 아니면 항상 false. */
    fun getSendToAi(tripId: Long): Boolean =
        get(tripId) == true && prefs.getBoolean(aiKey(tripId), false)

    fun setSendToAi(tripId: Long, sendToAi: Boolean) {
        prefs.edit().putBoolean(aiKey(tripId), sendToAi).apply()
    }

    /** 녹음 동의와 AI 전송 동의를 한 번에 저장한다. 녹음에 동의하지 않으면 AI 전송도 false 로 둔다. */
    fun set(tripId: Long, consent: Boolean, sendToAi: Boolean) {
        prefs.edit()
            .putBoolean(key(tripId), consent)
            .putBoolean(aiKey(tripId), consent && sendToAi)
            .apply()
    }

    private fun key(tripId: Long) = "trip_$tripId"
    private fun aiKey(tripId: Long) = "trip_${tripId}_ai"
}
