package com.dongbantest.app.data.talk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.dongbantest.app.data.repository.RecordingConsentStore
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.gleo.QuestionCommand
import com.dongbantest.app.gleo.isPauseRecordingCommand
import com.dongbantest.app.gleo.parseQuestionCommand
import com.drivediary.talkrec.CaptureOptions
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.capture.CaptureStatus
import com.drivediary.talkrec.capture.MicAudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Starts only on an explicit action from a visible Activity. */
class TalkRecordingController(
    private val context: Context,
    private val repository: TripRepository,
    private val consent: RecordingConsentStore,
    private val sink: TalkSinkImpl,
    private val recordingSettings: RecordingSettingsStore,
    scope: CoroutineScope,
    speakPause: suspend (Long) -> Unit,
    stopPauseSpeech: () -> Unit,
) {
    private var activeTripId: Long? = null
    private var stoppingCapture: Deferred<Unit>? = null
    private val requestedPause = MutableStateFlow<Long?>(null)
    val pausedTripId = requestedPause.asStateFlow()
    private val pauseFeedback = RecordingPauseFeedback(scope, awaitPaused = { id ->
        val paused = TalkRec.state.first { it.tripId != id || it.status != CaptureStatus.On }
        paused.tripId == id && paused.status == CaptureStatus.Paused &&
            activeTripId == id && requestedPause.value == id && consent.get(id) == true &&
            repository.isTripOngoing(id)
    }, speak = speakPause, stopSpeaking = stopPauseSpeech)

    /** Set by the app container: receives "ask next / stop asking" commands recognized by our own STT. */
    @Volatile var onQuestionCommand: ((QuestionCommand) -> Unit)? = null

    /** Same path as a self-recognized command; also used by the debug receiver. */
    fun dispatchQuestionCommand(command: QuestionCommand) { onQuestionCommand?.invoke(command) }

    /** Debug simulator uses the same consent/session checks and feedback as an app STT pause. */
    fun dispatchVoicePauseCommand() {
        val id = activeTripId ?: return
        if (TalkRec.state.value.let { it.tripId == id && it.status == CaptureStatus.On }) {
            pause(id, RecordingPauseSource.APP_VOICE)
        }
    }

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
        stoppingCapture?.await()
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
            pauseFeedback.cancel()
            TalkRec.start(context, tripId, CaptureOptions(sink, audioSource = {
                MicAudioSource { recordingSettings.micBoostEnabled.value }
            }, voiceCommand = { text ->
                // Latch the trip's pause before the service closes this input on its own event loop.
                val handled = activeTripId == tripId && consent.get(tripId) == true && isPauseRecordingCommand(text)
                if (handled) pauseFeedback.pause(tripId, RecordingPauseSource.APP_VOICE) {
                    // Returning true closes capture on the service actor; do not enqueue a second pause.
                    requestedPause.value = tripId
                }
                handled
            }, consumeCommand = { text ->
                // Question commands only apply to the active, consented trip; the line is consumed, capture continues.
                val command = if (activeTripId == tripId && consent.get(tripId) == true) parseQuestionCommand(text) else null
                if (command != null) dispatchQuestionCommand(command)
                command != null
            }, beforeResume = { pauseFeedback.cancel() }))
            activeTripId = tripId
            true
        } catch (_: RuntimeException) {
            Log.w("TalkRec", "capture start unavailable")
            false
        }
    }

    fun stopForTrip(tripId: Long) {
        if (activeTripId == tripId || requestedPause.value == tripId) pauseFeedback.cancel()
        if (requestedPause.value == tripId) requestedPause.value = null
        TalkRec.stopForTrip(tripId)?.let { stoppingCapture = it }
        if (activeTripId == tripId) activeTripId = null
    }

    suspend fun finishTrip(tripId: Long) {
        stopForTrip(tripId)
        stoppingCapture?.await()
    }

    suspend fun discardTrip(tripId: Long) = finishTrip(tripId)

    fun pause(tripId: Long, source: RecordingPauseSource = RecordingPauseSource.SCREEN) {
        if (consent.get(tripId) != true) return
        pauseFeedback.pause(tripId, source) {
            if (activeTripId == tripId) TalkRec.pause()
            requestedPause.value = tripId
        }
    }

    suspend fun resume(tripId: Long): Boolean {
        if (!startForTrip(tripId, resumePaused = true)) return false
        return resumeExisting(tripId)
    }

    fun resumeExisting(tripId: Long): Boolean {
        if (repository.isTripBeingDeleted(tripId) || repository.isTripEnding(tripId) ||
            activeTripId != tripId || consent.get(tripId) != true) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        // Stop actual feedback TTS synchronously before allowing microphone capture again.
        pauseFeedback.cancel()
        TalkRec.resume()
        if (requestedPause.value == tripId) requestedPause.value = null
        return true
    }

    suspend fun markMoment(tripId: Long) { if (startForTrip(tripId)) TalkRec.markMoment() }
}
