package com.drivediary.talkrec.debug

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class Mono16kResamplerTest {
    @Test fun stereo44100DownmixHasCorrectLengthAmplitudeAndSineFrequency() {
        val input = ShortArray(44_100 * 2) { index ->
            val amplitude = if (index % 2 == 0) 12_000 else 4_000
            (amplitude * sin(2 * PI * 440 * (index / 2) / 44_100)).toInt().toShort()
        }
        val actual = downmixAndResample(input, 44_100, 2)
        assertEquals(16_000, actual.size)
        val rising = (1 until actual.size).count { actual[it - 1] <= 0 && actual[it] > 0 }
        assertEquals(440.0, rising.toDouble(), 1.0)
        assertEquals(8_000.0, actual.max().toDouble(), 20.0)
    }

    @Test fun rates48000And8000ProduceOneSecondAndCorrectTone() {
        for (rate in listOf(48_000, 8_000)) {
            val input = ShortArray(rate) { (10_000 * sin(2 * PI * 200 * it / rate)).toInt().toShort() }
            val actual = downmixAndResample(input, rate, 1)
            assertEquals(16_000, actual.size)
            val rising = (1 until actual.size).count { actual[it - 1] <= 0 && actual[it] > 0 }
            assertEquals(200.0, rising.toDouble(), 1.0)
        }
    }

    @Test fun mono16000IsBitExactAndStereoOppositeChannelsCancel() {
        val input = shortArrayOf(Short.MIN_VALUE, -10, 0, 10, Short.MAX_VALUE)
        assertArrayEquals(input, downmixAndResample(input, 16_000, 1))
        val stereo = shortArrayOf(1000, -1000, 30000, -30000)
        assertArrayEquals(shortArrayOf(0, 0), downmixAndResample(stereo, 16_000, 2))
    }

    @Test fun irregularDecoderChunksMatchSingleBufferWithoutRateDrift() {
        for (rate in listOf(44_100, 48_000, 8_000, 16_000)) {
            val input = ShortArray(rate * 2 + 34) { (12_000 * sin(it * .025)).toInt().toShort() }
            val expected = downmixAndResample(input, rate, 2)
            val converter = Mono16kResampler(rate, 2)
            val chunks = mutableListOf<Short>()
            var offset = 0
            while (offset < input.size) {
                val size = minOf(74, input.size - offset)
                chunks += converter.push(FloatArray(size) { input[offset + it].toFloat() }).toList()
                offset += size
            }
            chunks += converter.finish().toList()
            assertArrayEquals(expected, chunks.toShortArray())
            assertEquals(input.size / 2L * 16_000 / rate, chunks.size.toLong())
        }
    }

    @Test fun fractionalTailAndEmptyInputAreWellDefined() {
        assertTrue(downmixAndResample(shortArrayOf(), 44_100, 1).isEmpty())
        assertArrayEquals(shortArrayOf(1000, 1000), downmixAndResample(shortArrayOf(1000), 8000, 1))
        assertTrue(downmixAndResample(shortArrayOf(1000), 48000, 1).isEmpty())
        val converter = Mono16kResampler(16_000, 1)
        val result = converter.push(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 100000f)) + converter.finish()
        assertArrayEquals(shortArrayOf(0, 0, Short.MAX_VALUE), result)
    }
}
