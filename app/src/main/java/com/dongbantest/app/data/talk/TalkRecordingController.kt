package com.dongbantest.app.data.talk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.dongbantest.app.data.repository.RecordingConsentStore
import com.dongbantest.app.data.repository.TripRepository
import com.drivediary.talkrec.CaptureOptions
import com.drivediary.talkrec.TalkRec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Starts only from the visible trip flow; observing an ongoing DB trip never opens the mic. */
class TalkRecordingController(
    private val context: Context,
    private val repository: TripRepository,
    private val consent: RecordingConsentStore,
    private val sink: TalkSinkImpl,
) {
    private var activeTripId: Long? = null

    fun observeStops(scope: CoroutineScope) {
        scope.launch {
            combine(repository.observeOngoingTrip(), consent.changes()) { trip, _ -> trip }
                .collect { trip ->
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

    suspend fun startForTrip(tripId: Long): Boolean {
        if (consent.get(tripId) != true || !repository.isTripOngoing(tripId)) {
            stopForTrip(tripId)
            return false
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (activeTripId == tripId) return true
        try { repository.recoverTalkClips() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { Log.w("TalkRec", "clip recovery pending"); return false }
        // Consent/trip may have changed while recovery was suspended.
        if (consent.get(tripId) != true || !repository.isTripOngoing(tripId)) return false
        return try {
            TalkRec.start(context, tripId, CaptureOptions(sink))
            activeTripId = tripId
            true
        } catch (_: RuntimeException) {
            Log.w("TalkRec", "capture start unavailable")
            false
        }
    }

    fun stopForTrip(tripId: Long) {
        if (activeTripId != tripId) return
        TalkRec.stop()
        activeTripId = null
    }

    fun pause(tripId: Long) { if (activeTripId == tripId) TalkRec.pause() }
    suspend fun resume(tripId: Long) { if (startForTrip(tripId)) TalkRec.resume() }
    suspend fun markMoment(tripId: Long) { if (startForTrip(tripId)) TalkRec.markMoment() }
}
