package com.dongbantest.app.data.ask

import com.dongbantest.app.data.talk.RecordingPauseFeedback
import com.dongbantest.app.data.talk.RecordingPauseSource
import com.drivediary.talkrec.ask.AskEvent
import com.drivediary.talkrec.ask.AskInputs
import com.drivediary.talkrec.ask.QuietWatcher
import com.drivediary.talkrec.ask.TtsPlayback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PauseAcknowledgementRegressionTest {
    @Test fun previousStopBeforeStartCannotFinishTheNewSpeakCall() = runBlocking {
        val playback = TtsPlayback()
        playback.engineStopped() // Previous request's flush callback, before ready/start.
        assertFalse(playback.finished.isCompleted)
        playback.started(114)
        assertEquals(114L, playback.firstSoundAtMs)
        assertFalse(playback.finished.isCompleted)
        playback.finish("DONE")
        assertEquals("DONE", playback.finished.await())
    }

    @Test fun avdStopReadyStartThenPausedCleanupAt650msDoesNotCutOrRepeatTheNotice() = runBlocking {
        val ownership = QuestionSpeechOwnership()
        val watcher = QuietWatcher()
        fun inputs(now: Long, listening: Boolean) = AskInputs(now, listening, 0, 0, null)
        watcher.tick(inputs(0, true))
        val captureClosed = CompletableDeferred<Boolean>()
        val playback = TtsPlayback()
        val events = mutableListOf<String>()
        var requests = 0
        var stopCalls = 0
        val feedback = RecordingPauseFeedback(this, { captureClosed.await() }, speak = { id ->
            val lease = ownership.acquire(id, onDemand = true, pauseNotice = true)
            try {
                requests++
                events += "speak"
                playback.finished.await()
            } finally { ownership.release(lease) }
        }, stopSpeaking = { stopCalls++; playback.finish("STOPPED") })
        try {
            feedback.pause(7, RecordingPauseSource.APP_VOICE) { events += "pause" }
            events += "stt_closed"
            captureClosed.complete(true)
            yield()
            assertEquals(listOf("pause", "stt_closed", "speak"), events)
            playback.engineStopped() // 04:03:59.453: old onStop arrives after new speak request.
            events += "ready" // 04:03:59.454: no completion event.
            playback.started(114) // 04:03:59.456: new utterance really starts.
            yield() // Old implementation completed speak and dropped ownership here.
            val stopped = watcher.tick(inputs(650, false))
            assertEquals(AskEvent.Stopped, stopped)
            if (stopped == AskEvent.Stopped && !ownership.onDemandSpeaking && ownership.canCancel()) {
                stopCalls++
                playback.finish("STOPPED")
            }
            // Also protect against a cleanup already queued before the pause's owner was installed.
            assertFalse(ownership.canCancel())
            assertTrue(ownership.onDemandSpeaking)
            assertEquals(0, stopCalls)
            assertFalse(playback.finished.isCompleted)
            feedback.pause(7, RecordingPauseSource.APP_VOICE) { events += "duplicate_pause" }
            assertEquals(1, requests)
            playback.finish("DONE")
            yield()
            assertEquals("DONE", playback.finished.await())
            assertFalse(ownership.onDemandSpeaking)
            assertTrue(ownership.canCancel())
            assertEquals(1, requests)
            assertEquals(0, stopCalls)
        } finally { feedback.cancel() }
    }

    @Test fun sameTripResetPreservesPauseButTripChangeCanCancelIt() {
        val ownership = QuestionSpeechOwnership()
        val lease = ownership.acquire(7, onDemand = true, pauseNotice = true)
        assertFalse(ownership.canCancel(force = ownership.pauseTripId != 7L))
        assertTrue(ownership.canCancel(force = ownership.pauseTripId != 8L))
        assertTrue(ownership.canCancel(force = ownership.pauseTripId != null))
        ownership.release(lease)
        assertTrue(ownership.canCancel())
    }

    @Test fun endDiscardAndResumeCanCancelPlaybackAndReleaseItsOwner() = runBlocking {
        listOf("end", "discard", "resume").forEach { reason ->
            val ownership = QuestionSpeechOwnership()
            val playback = TtsPlayback()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                val lease = ownership.acquire(7, onDemand = true, pauseNotice = true)
                try { playback.started(0); playback.finished.await() }
                finally { ownership.release(lease) }
            }
            assertTrue(reason, ownership.canCancel(force = true))
            playback.finish("STOPPED") // Device stop is still explicit and synchronous before resume.
            job.cancelAndJoin()
            assertFalse(reason, ownership.onDemandSpeaking)
            assertTrue(reason, ownership.canCancel())
        }
    }

    @Test fun oldQuestionFinallyCannotReleaseTheNewPauseOwner() {
        val ownership = QuestionSpeechOwnership()
        val oldQuestion = ownership.acquire(7, onDemand = true)
        val pauseNotice = ownership.acquire(7, onDemand = true, pauseNotice = true)
        ownership.release(oldQuestion)
        assertTrue(ownership.onDemandSpeaking)
        assertFalse(ownership.canCancel())
        ownership.release(pauseNotice)
        assertFalse(ownership.onDemandSpeaking)
        assertTrue(ownership.canCancel())
    }

    @Test fun realStopAfterStartStillFinishesPlayback() = runBlocking {
        val playback = TtsPlayback()
        playback.started(100)
        playback.engineStopped()
        assertEquals("STOPPED", playback.finished.await())
    }

    @Test fun explicitAppStopOrReleaseWorksEvenBeforeStart() = runBlocking {
        listOf("STOPPED", "RELEASED").forEach { reason ->
            val playback = TtsPlayback()
            playback.finish(reason)
            assertEquals(reason, playback.finished.await())
        }
    }
}
