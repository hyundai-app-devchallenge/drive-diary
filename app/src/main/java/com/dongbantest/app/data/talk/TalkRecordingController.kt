package com.dongbantest.app.data.talk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.dongbantest.app.data.repository.RecordingConsentStore
import com.dongbantest.app.data.repository.TripRepository
import com.drivediary.talkrec.CaptureOptions
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.capture.CaptureStatus
import com.drivediary.talkrec.capture.MicAudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Starts only on an explicit action from a visible Activity. */
class TalkRecordingController(
    private val context: Context,
    private val repository: TripRepository,
    private val consent: RecordingConsentStore,
    private val sink: TalkSinkImpl,
    private val recordingSettings: RecordingSettingsStore,
) {
    private var activeTripId: Long? = null
    private val requestedPause = MutableStateFlow<Long?>(null)
    val pausedTripId = requestedPause.asStateFlow()

    fun observeStops(scope: CoroutineScope) {
        scope.launch {
            combine(repository.observeOngoingTrip(), consent.changes()) { trip, _ -> trip }
                .collect { trip ->
                    requestedPause.value?.let { id ->
                        if (trip?.id != id || consent.get(id) != true) requestedPause.value = null
                    }
                    activeTripId?.let { id ->
                        if (trip?.id != id || consent.get(id) != true) stopForTrip(id)
                    }
                }
        }
        scope.launch {
            try { repository.recoverTalkClips() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { Log.w("TalkRec", "clip recovery pending") }
        }
    }

    suspend fun startForTrip(tripId: Long): Boolean = startForTrip(tripId, resumePaused = false)

    private suspend fun startForTrip(tripId: Long, resumePaused: Boolean): Boolean {
        if (consent.get(tripId) != true || !repository.isTripOngoing(tripId)) {
            stopForTrip(tripId)
            return false
        }
        if (!resumePaused && requestedPause.value == tripId) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (activeTripId == tripId && TalkRec.state.value.status != CaptureStatus.Off) return true
        try { repository.recoverTalkClips() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Log.w("TalkRec", "clip recovery pending"); return false }
        // Consent/trip may have changed while recovery was suspended.
        if (consent.get(tripId) != true || !repository.isTripOngoing(tripId)) return false
        if (!resumePaused && requestedPause.value == tripId) return false
        if (activeTripId == tripId && TalkRec.state.value.status != CaptureStatus.Off) return true
        return try {
            TalkRec.start(context, tripId, CaptureOptions(sink, audioSource = {
                MicAudioSource { recordingSettings.micBoostEnabled.value }
            }))
            activeTripId = tripId
            true
        } catch (_: RuntimeException) {
            Log.w("TalkRec", "capture start unavailable")
            false
        }
    }

    fun stopForTrip(tripId: Long) {
        if (requestedPause.value == tripId) requestedPause.value = null
        if (activeTripId != tripId) return
        TalkRec.stop()
        activeTripId = null
    }

    fun pause(tripId: Long) {
        if (consent.get(tripId) != true) return
        if (activeTripId == tripId) TalkRec.pause()
        requestedPause.value = tripId
    }

    suspend fun resume(tripId: Long): Boolean {
        if (!startForTrip(tripId, resumePaused = true)) return false
        return resumeExisting(tripId)
    }

    fun resumeExisting(tripId: Long): Boolean {
        if (activeTripId != tripId || consent.get(tripId) != true) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        TalkRec.resume()
        if (requestedPause.value == tripId) requestedPause.value = null
        return true
    }

    suspend fun markMoment(tripId: Long) { if (startForTrip(tripId)) TalkRec.markMoment() }
}
