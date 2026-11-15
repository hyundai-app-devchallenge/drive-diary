package com.dongbantest.app.debug

import com.drivediary.talkrec.capture.CaptureStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicSilenceDecisionTest {
    private fun decide(
        status: CaptureStatus = CaptureStatus.On,
        test: Boolean = false,
        quietMs: Long,
        armed: Boolean = true,
    ) = MicSilenceDecision.decide(status, test, quietMs, armed)

    @Test fun warnsOnceAtThreshold() {
        val first = decide(quietMs = 5_000)
        assertTrue(first.warn)
        assertFalse(first.armed)
        val again = decide(quietMs = 30_000, armed = first.armed)
        assertFalse(again.warn)
    }

    @Test fun noWarningBelowThreshold() {
        assertFalse(decide(quietMs = 4_900).warn)
    }

    @Test fun noWarningForTestAudio() {
        assertFalse(decide(test = true, quietMs = 60_000).warn)
    }

    @Test fun noWarningWhenNotOn() {
        for (s in listOf(CaptureStatus.Ready, CaptureStatus.Paused, CaptureStatus.Off)) {
            assertFalse(decide(status = s, quietMs = 60_000).warn)
        }
    }

    @Test fun rearmsWhenCaptureLeavesOn() {
        val first = decide(quietMs = 5_000)
        val paused = decide(status = CaptureStatus.Paused, quietMs = 0, armed = first.armed)
        assertTrue(paused.armed)
        assertTrue(decide(quietMs = 5_000, armed = paused.armed).warn)
    }
}
