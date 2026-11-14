package com.dongbantest.app.data.talk

import com.dongbantest.app.gleo.VoiceCommands
import com.drivediary.talkrec.ask.QuestionSpeaker
import com.drivediary.talkrec.ask.SpeakResult
import com.drivediary.talkrec.ask.TtsLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RecordingPauseFeedbackTest {
    private class UnavailableSpeaker : QuestionSpeaker {
        override val events = MutableSharedFlow<TtsLog>()
        var utterance: String? = null
        override suspend fun speak(text: String): SpeakResult {
            utterance = text
            return SpeakResult("INIT_FAILED", null, 0)
        }
        override fun stop() = Unit
        override fun release() = Unit
    }

    private class Capture {
        val events = mutableListOf<String>()
        val closed = CompletableDeferred<Boolean>()
        var paused = false
        fun requestPause() { events += "pause_requested" }
        fun closeMicrophone() {
            paused = true
            events += "microphone_closed"
            closed.complete(true)
        }
    }

    private fun feedback(
        scope: CoroutineScope,
        capture: Capture,
        speak: suspend (Long) -> Unit = { capture.events += "speak" },
        stopSpeaking: () -> Unit = {},
    ) = RecordingPauseFeedback(scope, { capture.closed.await() }, speak, stopSpeaking)

    @Test fun onlyAppVoicePauseRequestsAcknowledgement() = runBlocking {
        RecordingPauseSource.entries.forEach { source ->
            val capture = Capture()
            capture.closeMicrophone()
            capture.events.clear()
            feedback(this, capture).pause(7, source, capture::requestPause)
            assertEquals(
                if (source == RecordingPauseSource.APP_VOICE) listOf("pause_requested", "speak")
                else listOf("pause_requested"),
                capture.events,
            )
        }
    }

    @Test fun voicePauseWaitsForActualMicrophoneClosureBeforeSpeaking() = runBlocking {
        val capture = Capture()
        val handler = feedback(this, capture, speak = { id ->
            assertEquals(7L, id)
            assertTrue(capture.paused)
            capture.events += "speak"
        })
        handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
        assertEquals(listOf("pause_requested"), capture.events)
        capture.closeMicrophone()
        yield()
        assertEquals(listOf("pause_requested", "microphone_closed", "speak"), capture.events)
    }

    @Test fun failedTtsInitializationLeavesPauseSuccessful() = runBlocking {
        val capture = Capture()
        val speaker = UnavailableSpeaker()
        val handler = feedback(this, capture, speak = {
            speaker.speak(VoiceCommands.PAUSE_RECORDING_ACKNOWLEDGEMENT)
        })
        handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
        capture.closeMicrophone()
        yield()
        assertTrue(capture.paused)
        assertEquals(VoiceCommands.PAUSE_RECORDING_ACKNOWLEDGEMENT, speaker.utterance)
        assertEquals(1, capture.events.count { it == "pause_requested" })
    }

    @Test fun missingOrThrowingTtsCannotFailPause() = runBlocking {
        listOf<Throwable>(IllegalStateException("unavailable"), NoClassDefFoundError("tts")).forEach { error ->
            val capture = Capture()
            val handler = feedback(this, capture, speak = { throw error })
            handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
            capture.closeMicrophone()
            yield()
            assertTrue(capture.paused)
            assertEquals(listOf("pause_requested", "microphone_closed"), capture.events)
        }
    }

    @Test fun unconfirmedPauseNeverSpeaks() = runBlocking {
        val capture = Capture()
        val handler = feedback(this, capture)
        handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
        capture.closed.complete(false)
        yield()
        assertEquals(listOf("pause_requested"), capture.events)
    }

    @Test fun tripEndOrDiscardCancelsPendingAcknowledgement() = runBlocking {
        val capture = Capture()
        val handler = feedback(this, capture)
        handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
        handler.cancel() // stopForTrip, also used by finishTrip/discardTrip.
        capture.closeMicrophone()
        yield()
        assertTrue(capture.paused)
        assertFalse(capture.events.contains("speak"))
    }

    @Test fun resumeStopsDeviceSpeechBeforeCaptureCanRestart() = runBlocking {
        val capture = Capture()
        val released = CompletableDeferred<Unit>()
        val handler = feedback(this, capture, speak = {
            capture.events += "speak"
            try { awaitCancellation() }
            finally { released.complete(Unit) }
        }, stopSpeaking = { capture.events += "tts_stopped" })
        capture.closeMicrophone()
        handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
        handler.cancel() // resumeExisting stops device TTS synchronously before TalkRec.resume.
        capture.events += "capture_resumed"
        released.await()
        assertTrue(capture.events.indexOf("tts_stopped") < capture.events.indexOf("capture_resumed"))
    }

    @Test fun duplicateSilentPauseDoesNotInterruptVoiceAcknowledgement() = runBlocking {
        val capture = Capture()
        val handler = feedback(this, capture)
        handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
        handler.pause(7, RecordingPauseSource.GLEO, capture::requestPause)
        capture.closeMicrophone()
        yield()
        assertEquals(1, capture.events.count { it == "speak" })
        assertEquals(1, capture.events.count { it == "pause_requested" })
    }

    @Test fun unavailableTtsStopCannotBlockTheNextPause() = runBlocking {
        val capture = Capture()
        val handler = feedback(this, capture, stopSpeaking = { throw NoClassDefFoundError("tts") })
        handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
        handler.cancel() // Explicit resume/end still interrupts, even when stopping device TTS fails.
        handler.pause(7, RecordingPauseSource.SCREEN, capture::requestPause)
        capture.closeMicrophone()
        yield()
        assertTrue(capture.paused)
        assertEquals(2, capture.events.count { it == "pause_requested" })
        assertFalse(capture.events.contains("speak"))
    }

    @Test fun repeatedVoicePauseBeforeMicrophoneClosesRequestsAndSpeaksOnlyOnce() = runBlocking {
        val capture = Capture()
        val handler = feedback(this, capture)
        repeat(3) { handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause) }
        capture.closeMicrophone()
        yield()
        assertEquals(1, capture.events.count { it == "pause_requested" })
        assertEquals(1, capture.events.count { it == "speak" })
    }

    @Test fun completedPauseCannotBeAnnouncedAgainUntilResume() = runBlocking {
        val capture = Capture()
        val handler = feedback(this, capture)
        handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
        capture.closeMicrophone()
        yield()
        handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
        yield()
        assertEquals(1, capture.events.count { it == "speak" })
        handler.cancel() // Resume resets the per-pause latch.
        handler.pause(7, RecordingPauseSource.APP_VOICE, capture::requestPause)
        assertEquals(2, capture.events.count { it == "speak" })
    }
}
