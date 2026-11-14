package com.drivediary.talkrec.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietWatcherTest {
    private val policy = QuestionPolicy(
        quietMs = 100_000, prepareLeadMs = 20_000, minTripAgeMs = 50_000, minRemainSeconds = 600,
        cooldownMs = 300_000, maxPerTrip = 2, reactionWindowMs = 10_000, answerWindowMs = 60_000, askTimeoutMs = 30_000,
    )
    private val watcher = QuietWatcher(policy)

    private fun inputs(now: Long, quiet: Long, listening: Boolean = true, trip: Long = 0, remain: Int? = 3600) =
        AskInputs(now, listening, quiet, trip, remain)

    /** Drives to CHECKING; returns the time of the ShowCheck event. */
    private fun toChecking(): Long {
        val t = 1_000_000L
        watcher.tick(inputs(t, 0)) // OFF -> LISTENING
        assertEquals(AskPhase.LISTENING, watcher.phase)
        assertEquals(AskEvent.Prepare, watcher.tick(inputs(t + 1, 80_000)))
        assertEquals(AskPhase.PREPARING, watcher.phase)
        assertEquals(AskEvent.ShowCheck, watcher.tick(inputs(t + 2, 100_000)))
        assertEquals(AskPhase.CHECKING, watcher.phase)
        return t + 2
    }

    @Test fun fullFlowAcceptAsksAndReturnsToListening() {
        val t = toChecking()
        assertEquals(AskEvent.Ask(false), watcher.onReaction(Reaction.ACCEPT, t + 1000))
        assertEquals(AskPhase.ASKING, watcher.phase)
        assertEquals(1, watcher.askedCount)
        watcher.spoken(t + 5000)
        assertEquals(AskPhase.ANSWERING, watcher.phase)
        assertNull(watcher.tick(inputs(t + 6000, 0)))
        assertEquals(AskEvent.Listening("answer_window"), watcher.tick(inputs(t + 5000 + 60_000, 0)))
        assertEquals(AskPhase.LISTENING, watcher.phase)
    }

    @Test fun declineMutesForRestOfTrip() {
        val t = toChecking()
        assertEquals(AskEvent.Muted("declined"), watcher.onReaction(Reaction.DECLINE, t + 500))
        assertEquals(AskPhase.MUTED, watcher.phase)
        assertTrue(watcher.muted)
        repeat(5) { assertNull(watcher.tick(inputs(t + 1000 + it * 1000L, 500_000))) }
        assertEquals(AskPhase.MUTED, watcher.phase)
    }

    @Test fun noReactionAndNoVoiceMutes() {
        val t = toChecking()
        assertNull(watcher.tick(inputs(t + 5000, 105_000)))
        assertEquals(AskEvent.Muted("no_reaction"), watcher.tick(inputs(t + 10_000, 110_000)))
        assertEquals(AskPhase.MUTED, watcher.phase)
    }

    @Test fun voiceDuringCheckWithoutReactionReturnsToListening() {
        val t = toChecking()
        watcher.tick(inputs(t + 3000, 0))
        assertEquals(AskEvent.Listening("talk"), watcher.tick(inputs(t + 10_000, 7000)))
        assertEquals(AskPhase.LISTENING, watcher.phase)
        assertFalse(watcher.muted)
    }

    @Test fun otherReactionReturnsToListening() {
        val t = toChecking()
        assertEquals(AskEvent.Listening("talk"), watcher.onReaction(Reaction.OTHER, t + 100))
        assertEquals(AskPhase.LISTENING, watcher.phase)
    }

    @Test fun reactionIgnoredOutsideChecking() {
        watcher.tick(inputs(0, 0))
        assertNull(watcher.onReaction(Reaction.ACCEPT, 10))
        assertEquals(AskPhase.LISTENING, watcher.phase)
    }

    @Test fun talkDuringPreparingGoesBackToListening() {
        val t = 1_000_000L
        watcher.tick(inputs(t, 0))
        assertEquals(AskEvent.Prepare, watcher.tick(inputs(t + 1, 85_000)))
        assertEquals(AskEvent.Listening("talk"), watcher.tick(inputs(t + 2, 0)))
        assertEquals(AskPhase.LISTENING, watcher.phase)
    }

    @Test fun tripTooYoungDoesNotPrepare() {
        watcher.tick(inputs(1000, 0, trip = 1000))
        assertNull(watcher.tick(inputs(1000 + 40_000, 90_000, trip = 1000)))
        assertEquals(AskPhase.LISTENING, watcher.phase)
        assertEquals(AskEvent.Prepare, watcher.tick(inputs(1000 + 50_000, 90_000, trip = 1000)))
    }

    @Test fun tooLittleRemainingTimeDoesNotPrepareButNoDestinationDoes() {
        val t = 1_000_000L
        watcher.tick(inputs(t, 0))
        assertNull(watcher.tick(inputs(t + 1, 90_000, remain = 599)))
        assertEquals(AskEvent.Prepare, watcher.tick(inputs(t + 2, 90_000, remain = null)))
    }

    @Test fun remainDroppingDuringPreparingCancels() {
        val t = 1_000_000L
        watcher.tick(inputs(t, 0))
        watcher.tick(inputs(t + 1, 85_000))
        assertEquals(AskEvent.Listening("conditions"), watcher.tick(inputs(t + 2, 90_000, remain = 100)))
    }

    @Test fun cooldownAndMaxPerTripGate() {
        var t = toChecking()
        watcher.onReaction(Reaction.ACCEPT, t)
        watcher.spoken(t + 1000)
        watcher.tick(inputs(t + 1000 + 60_000, 0)) // answer window over
        assertEquals(AskPhase.LISTENING, watcher.phase)
        // Within cooldown: no prepare.
        assertNull(watcher.tick(inputs(t + 100_000, 90_000)))
        // After cooldown: prepare again.
        assertEquals(AskEvent.Prepare, watcher.tick(inputs(t + 300_000, 90_000)))
        assertEquals(AskEvent.ShowCheck, watcher.tick(inputs(t + 300_001, 100_000)))
        t += 300_001
        watcher.onReaction(Reaction.ACCEPT, t)
        assertEquals(2, watcher.askedCount)
        watcher.spoken(t + 1000)
        watcher.tick(inputs(t + 61_000, 0))
        // maxPerTrip reached: never prepares again, even long after cooldown.
        assertNull(watcher.tick(inputs(t + 10_000_000, 900_000)))
        assertEquals(AskPhase.LISTENING, watcher.phase)
    }

    @Test fun notListeningStopsThenResumes() {
        watcher.tick(inputs(0, 0))
        assertEquals(AskEvent.Stopped, watcher.tick(inputs(1000, 0, listening = false)))
        assertEquals(AskPhase.OFF, watcher.phase)
        assertNull(watcher.tick(inputs(2000, 0, listening = false)))
        assertNull(watcher.tick(inputs(3000, 0)))
        assertEquals(AskPhase.LISTENING, watcher.phase)
    }

    @Test fun resumeAfterStopStaysMutedWhenMuted() {
        watcher.tick(inputs(0, 0))
        watcher.mute(10, "user")
        assertEquals(AskPhase.MUTED, watcher.phase)
        assertEquals(AskEvent.Stopped, watcher.tick(inputs(1000, 0, listening = false)))
        watcher.tick(inputs(2000, 0))
        assertEquals(AskPhase.MUTED, watcher.phase)
        watcher.unmute(3000)
        assertEquals(AskPhase.LISTENING, watcher.phase)
    }

    @Test fun forceAskDoesNotCount() {
        watcher.tick(inputs(0, 0))
        assertEquals(AskEvent.Ask(true), watcher.forceAsk(100))
        assertEquals(0, watcher.askedCount)
        assertEquals(AskPhase.ASKING, watcher.phase)
    }

    @Test fun askTimeoutMovesToAnswering() {
        val t = toChecking()
        watcher.onReaction(Reaction.ACCEPT, t)
        assertNull(watcher.tick(inputs(t + 29_000, 0)))
        assertEquals(AskPhase.ASKING, watcher.phase)
        assertNull(watcher.tick(inputs(t + 30_000, 0)))
        assertEquals(AskPhase.ANSWERING, watcher.phase)
    }

    @Test fun forceCheckIgnoresConditions() {
        watcher.tick(inputs(0, 0))
        assertEquals(AskEvent.ShowCheck, watcher.forceCheck(10))
        assertEquals(AskPhase.CHECKING, watcher.phase)
    }

    @Test fun acceptAfterMuteClearsMute() {
        watcher.tick(inputs(0, 0))
        watcher.mute(10, "declined")
        assertTrue(watcher.muted)
        assertEquals(AskEvent.ShowCheck, watcher.forceCheck(20))
        assertEquals(AskEvent.Ask(false), watcher.onReaction(Reaction.ACCEPT, 30))
        assertFalse(watcher.muted)
        assertEquals(AskPhase.ASKING, watcher.phase)
    }
}
