package com.drivediary.talkrec.debug

import kotlin.math.roundToInt

/** Streaming arithmetic downmix + linear interpolation. Samples use signed PCM16 amplitude units.
 * Rational sample positions avoid drift at 44.1 kHz; retain only the boundary needed by the next push.
 */
internal class Mono16kResampler(val inputRate: Int, val channels: Int) {
    init { require(inputRate > 0 && channels > 0) }
    private var pending = FloatArray(0)
    private var base = 0L
    private var inputCount = 0L
    private var outputCount = 0L
    private var finished = false

    fun push(interleaved: FloatArray): ShortArray {
        check(!finished)
        require(interleaved.size % channels == 0)
        val mono = FloatArray(interleaved.size / channels) { frame ->
            var sum = 0.0
            repeat(channels) { channel ->
                val value = interleaved[frame * channels + channel]
                sum += if (value.isFinite()) value.toDouble() else 0.0
            }
            (sum / channels).toFloat()
        }
        pending += mono
        inputCount += mono.size
        return drain(end = false)
    }

    fun finish(): ShortArray {
        check(!finished); finished = true
        return drain(end = true).also { pending.fill(0f); pending = FloatArray(0) }
    }

    private fun drain(end: Boolean): ShortArray {
        val target = inputCount * 16_000 / inputRate
        val output = ShortArray((target - outputCount).toInt())
        var size = 0
        while (outputCount < target) {
            val numerator = outputCount * inputRate
            val left = numerator / 16_000
            val fraction = (numerator % 16_000) / 16_000.0
            if (!end && fraction != 0.0 && left + 1 >= inputCount) break
            val a = pending[(left - base).toInt()]
            val b = pending[(minOf(left + 1, inputCount - 1) - base).toInt()]
            output[size++] = (a + (b - a) * fraction).roundToInt().coerceIn(-32768, 32767).toShort()
            outputCount++
        }
        if (pending.isNotEmpty()) {
            val nextLeft = outputCount * inputRate / 16_000
            val discard = (minOf(nextLeft, inputCount - 1) - base).toInt().coerceAtLeast(0)
            val old = pending
            pending = old.copyOfRange(discard, old.size); old.fill(0f)
            base += discard
        }
        return output.copyOf(size)
    }
}

/** Pure Kotlin convenience function; the Android source uses the same streaming implementation. */
internal fun downmixAndResample(pcm: ShortArray, sampleRate: Int, channels: Int): ShortArray {
    val converter = Mono16kResampler(sampleRate, channels)
    return converter.push(FloatArray(pcm.size) { pcm[it].toFloat() }) + converter.finish()
}
