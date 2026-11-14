package com.dongbantest.app.data.talk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class RecordingPauseSource { SCREEN, GLEO, APP_VOICE }

/** Main-thread pause orchestration. Acknowledgement failure must never undo a requested pause. */
internal class RecordingPauseFeedback(
    private val scope: CoroutineScope,
    private val awaitPaused: suspend (Long) -> Boolean,
    private val speak: suspend (Long) -> Unit,
    private val stopSpeaking: () -> Unit,
) {
    private var feedbackJob: Job? = null
    private var voicePauseTripId: Long? = null

    fun pause(tripId: Long, source: RecordingPauseSource, requestPause: () -> Unit) {
        // Keep one request/utterance for this pause, even after TTS completes. Resume/end resets it.
        if (voicePauseTripId == tripId) return
        cancel()
        requestPause()
        if (source != RecordingPauseSource.APP_VOICE) return
        voicePauseTripId = tripId
        feedbackJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Paused is published only after the microphone and STT have been closed.
                if (awaitPaused(tripId)) speak(tripId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Optional device speech: no text, keys or exception messages in logs.
            } catch (_: LinkageError) {
                // The device may not provide the TTS SDK/service.
            }
        }
    }

    fun cancel() {
        voicePauseTripId = null
        val job = feedbackJob ?: return
        feedbackJob = null
        if (job.isActive) {
            job.cancel()
            try { stopSpeaking() }
            catch (_: Exception) { /* Optional TTS cannot block capture controls. */ }
            catch (_: LinkageError) { /* Device TTS may be unavailable. */ }
        }
    }
}
