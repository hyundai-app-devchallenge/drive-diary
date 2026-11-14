package com.drivediary.talkrec.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MicGainTest {
    @Test
    fun quietSamplesGetGainAndSilenceStaysZero() {
        val samples = shortArrayOf(0, 100, -50)
        amplifyMicFrame(samples)
        assertArrayEquals(shortArrayOf(0, 3200, -1600), samples)

        val silence = ShortArray(1600)
        amplifyMicFrame(silence)
        assertArrayEquals(ShortArray(1600), silence)
    }

    @Test
    fun peakLimitsGainForTheWholeFrame() {
        val samples = shortArrayOf(-16000, 8000, 0)
        amplifyMicFrame(samples)
        assertArrayEquals(shortArrayOf(-32767, 16384, 0), samples)

        val fullScale = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE)
        amplifyMicFrame(fullScale)
        assertArrayEquals(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE), fullScale)
    }
}
