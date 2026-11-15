package com.drivediary.talkrec.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MicGainTest {
    @Test
    fun quietFrameBelowGateIsLeftUnchangedAndSilenceStaysZero() {
        val samples = shortArrayOf(0, 100, -50)
        amplifyMicFrame(samples)
        assertArrayEquals(shortArrayOf(0, 100, -50), samples)

        val silence = ShortArray(1600)
        amplifyMicFrame(silence)
        assertArrayEquals(ShortArray(1600), silence)
    }

    @Test
    fun frameJustBelowGateIsUnchanged() {
        val samples = ShortArray(1600) { 139 }
        amplifyMicFrame(samples)
        assertArrayEquals(ShortArray(1600) { 139 }, samples)
    }

    @Test
    fun frameJustAboveGateIsAmplifiedAsBefore() {
        val samples = ShortArray(1600) { 141 }
        amplifyMicFrame(samples)
        // gain = 32767 / 141 = 232 -> capped at 32
        assertArrayEquals(ShortArray(1600) { 4512 }, samples)
    }

    @Test
    fun peakLimitsGainForTheWholeFrame() {
        val samples = ShortArray(1600) { 8000 }.also { it[0] = -16000 }
        amplifyMicFrame(samples)
        val expected = ShortArray(1600) { 16384 }.also { it[0] = -32767 }
        assertArrayEquals(expected, samples)

        val fullScale = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE)
        amplifyMicFrame(fullScale)
        assertArrayEquals(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE), fullScale)
    }
}
