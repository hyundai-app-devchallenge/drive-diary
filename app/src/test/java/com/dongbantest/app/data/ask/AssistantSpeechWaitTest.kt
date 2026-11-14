package com.dongbantest.app.data.ask

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AssistantSpeechWaitTest {
    private fun waitUntilDone(signal: (Long) -> Boolean?): Long = runBlocking {
        var time = 0L
        AssistantSpeechWait({ time }, { signal(time) }, { time += it }).awaitQuiet()
        time
    }

    @Test fun noPlaybackStillWaitsForLateAcknowledgement() {
        assertEquals(1_200L, waitUntilDone { false })
    }

    @Test fun playbackThatStartsAfterBroadcastMustFinish() {
        val end = waitUntilDone { it in 500L until 3_000L }
        assertTrue(end in 3_350L..3_600L)
    }

    @Test fun shortSpeechDoesNotSkipMinimumDelay() {
        assertEquals(1_200L, waitUntilDone { it < 250L })
    }

    @Test fun briefGapBetweenUtterancesIsNotCompletion() {
        val end = waitUntilDone { it < 2_000L || it in 2_250L until 4_000L }
        assertTrue(end in 4_350L..4_600L)
    }

    @Test fun continuousPlaybackHasHardDeadline() {
        assertEquals(8_000L, waitUntilDone { true })
    }

    @Test fun unavailablePlaybackUsesBoundedFallback() {
        assertEquals(8_000L, waitUntilDone { null })
    }

    @Test fun lossOfVisibilityAfterPlaybackDoesNotLookLikeSilence() {
        assertEquals(8_000L, waitUntilDone { if (it < 2_000) true else null })
    }

    @Test fun cancelDuringWaitNeverContinuesToSpeakingAndReleasesOwnership() = runBlocking {
        val suspended = CompletableDeferred<Unit>()
        var speaking = false
        var owners = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            owners++
            try {
                AssistantSpeechWait({ 0L }, { true }, { suspended.await() }).awaitQuiet()
                speaking = true
            } finally { owners-- }
        }
        assertEquals(1, owners)
        job.cancelAndJoin()
        assertFalse(speaking)
        assertEquals(0, owners)
    }
}
