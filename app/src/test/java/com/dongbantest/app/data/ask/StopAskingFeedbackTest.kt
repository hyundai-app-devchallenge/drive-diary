package com.dongbantest.app.data.ask

import com.dongbantest.app.gleo.VoiceCommands
import com.drivediary.talkrec.ask.QuestionSpeaker
import com.drivediary.talkrec.ask.QuietWatcher
import com.drivediary.talkrec.ask.SpeakResult
import com.drivediary.talkrec.ask.TtsLog
import com.drivediary.talkrec.capture.NoRecordWindows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StopAskingFeedbackTest {
    private class Speaker : QuestionSpeaker {
        override val events = MutableSharedFlow<TtsLog>()
        val actions = mutableListOf<String>()
        var speakingQuestion = true
        var utterance: String? = null
        var say: suspend () -> SpeakResult = { SpeakResult(null, 0, 0) }
        override suspend fun speak(text: String): SpeakResult {
            assertFalse("previous question must be stopped first", speakingQuestion)
            actions += "speak"
            utterance = text
            return say()
        }
        override fun stop() { actions += "stop"; speakingQuestion = false }
        override fun release() = Unit
    }

    private class Fixture {
        var now = 10_000L
        val windows = NoRecordWindows()
        val speaker = Speaker()
        val watcher = QuietWatcher()
        val muted get() = watcher.muted
        var hasTrip = true
        var opened = 0
        val feedback = StopAskingFeedback({ now }, {
            opened++
            windows.begin(it)
        })
        suspend fun stop(source: String) = feedback.stop(source,
            applyMute = {
                if (hasTrip) watcher.mute(now, "user")
                speaker.actions += "mute"
                hasTrip
            },
            stopQuestion = speaker::stop,
            speak = {
                assertTrue(muted)
                assertTrue("protect capture before starting TTS", windows.overlaps(now, now))
                speaker.speak(VoiceCommands.STOP_ASKING_ACKNOWLEDGEMENT)
            },
        )
    }

    @Test fun onlyVoiceAcknowledgesAndEverySourceStillMutesAndStopsQuestion() = runBlocking {
        listOf("voice", "screen", "no_reaction", "declined", "gleo").forEach { source ->
            val f = Fixture()
            f.stop(source)
            assertTrue(f.muted)
            if (source == "voice") {
                assertEquals(listOf("mute", "stop", "speak"), f.speaker.actions)
                assertEquals(VoiceCommands.STOP_ASKING_ACKNOWLEDGEMENT, f.speaker.utterance)
                assertEquals(1, f.opened)
            } else {
                assertEquals(listOf("mute", "stop"), f.speaker.actions)
                assertNull(f.speaker.utterance)
                assertEquals(0, f.opened)
            }
        }
    }

    @Test fun missingTripCannotProduceAMisleadingAcknowledgement() = runBlocking {
        val f = Fixture().apply { hasTrip = false }
        f.stop("voice")
        assertFalse(f.muted)
        assertNull(f.speaker.utterance)
        assertEquals(0, f.opened)
    }

    @Test fun wholePlaybackIsExcludedThenWindowClosesAtActualEndWithOneSecondGrace() = runBlocking {
        val f = Fixture()
        val done = CompletableDeferred<Unit>()
        f.speaker.say = { done.await(); SpeakResult(null, 0, 65_000) }
        val job = async(start = CoroutineStart.UNDISPATCHED) { f.stop("voice") }
        f.now = 75_000 // Still speaking, including slow initialization or a lagging completion callback.
        assertTrue(f.windows.overlaps(70_000, f.now))
        done.complete(Unit)
        job.await()
        assertTrue(f.windows.overlaps(10_000, 10_000))
        assertTrue(f.windows.overlaps(76_000, 76_000))
        assertFalse(f.windows.overlaps(76_001, 76_001))
        assertTrue(f.muted)
    }

    @Test fun initializationFailureDoesNotUndoMuteOrLeaveProtectionOpen() = runBlocking {
        val f = Fixture()
        f.speaker.say = { f.now = 12_000; SpeakResult("INIT_FAILED", null, 2_000) }
        f.stop("voice")
        assertTrue(f.muted)
        assertTrue(f.windows.overlaps(13_000, 13_000))
        assertFalse(f.windows.overlaps(13_001, 13_001))
    }

    @Test fun throwingOrMissingTtsIsQuietAndMuteRemainsApplied() = runBlocking {
        listOf<Throwable>(IllegalStateException("unavailable"), NoClassDefFoundError("tts")).forEach { error ->
            val f = Fixture()
            f.speaker.say = { f.now = 11_000; throw error }
            f.stop("voice")
            assertTrue(f.muted)
            assertTrue(f.windows.overlaps(12_000, 12_000))
            assertFalse(f.windows.overlaps(12_001, 12_001))
        }
    }

    @Test fun cancellationClosesProtectionAndDoesNotUnmute() = runBlocking {
        val f = Fixture()
        val done = CompletableDeferred<Unit>()
        f.speaker.say = { done.await(); SpeakResult(null, null, 0) }
        val job = async(start = CoroutineStart.UNDISPATCHED) { f.stop("voice") }
        f.now = 11_500
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(f.muted)
        assertTrue(f.windows.overlaps(12_500, 12_500))
        assertFalse(f.windows.overlaps(12_501, 12_501))
    }
}
