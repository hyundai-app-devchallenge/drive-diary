package com.dongbantest.app.data.ask

import com.drivediary.talkrec.capture.NoRecordWindows
import org.junit.Assert.*
import org.junit.Test

class NoRecordWindowsTest {
    @Test fun adjacentClipPaddingCannotReintroduceTheAnnouncement() {
        val windows = NoRecordWindows()
        val finish = windows.begin(10_000)
        finish(13_000) // TTS ends at 12 s; echo grace ends at 13 s.
        assertFalse(windows.overlaps(8_000, 9_000))
        assertTrue("earlier clip's trailing PCM includes the notice", windows.overlapsCapture(8_000, 9_000))
        assertFalse(windows.overlaps(14_000, 15_000))
        assertTrue("later clip's leading PCM includes the notice", windows.overlapsCapture(14_000, 15_000))
        assertFalse(windows.overlapsCapture(6_000, 7_999))
        assertFalse(windows.overlapsCapture(16_001, 17_000))
    }

    @Test fun finishingOneNoticeCannotRemoveAnotherNoticeOrAnExistingQuestionWindow() {
        val windows = NoRecordWindows()
        windows.mark(1_000, 5_000)
        val finishFirst = windows.begin(10_000)
        val finishSecond = windows.begin(11_000)
        finishFirst(12_000)
        assertTrue(windows.overlaps(20_000, 20_000))
        finishSecond(14_000)
        assertTrue(windows.overlaps(2_000, 2_000))
        assertTrue(windows.overlaps(13_000, 13_000))
        assertFalse(windows.overlaps(14_001, 14_001))
    }

    @Test fun oldCompletedWindowsExpireWhileActiveSpeechRemainsProtected() {
        val windows = NoRecordWindows()
        windows.mark(1_000, 2_000)
        val finish = windows.begin(3_000)
        windows.mark(2_000_000, 2_001_000)
        assertFalse(windows.overlaps(1_500, 1_500))
        assertTrue(windows.overlaps(1_900_000, 1_900_000))
        finish(2_000_000)
        assertFalse(windows.overlaps(2_001_001, 2_001_001))
    }
}
