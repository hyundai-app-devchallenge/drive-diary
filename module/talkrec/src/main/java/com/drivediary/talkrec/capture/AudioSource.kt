package com.drivediary.talkrec.capture

import kotlinx.coroutines.flow.Flow
import kotlin.math.sqrt

interface AudioSource {
    /** Cold stream; collection owns the input and cancellation releases it. */
    fun frames(): Flow<PcmFrame>
}

class PcmFrame(
    val startMs: Long,
    val pcm: ByteArray,
    val available: Boolean = true,
    val routeId: Int = 0,
) {
    init { require(pcm.size == BYTES) { "Expected 100 ms PCM16 mono at 16 kHz" } }
    val endMs: Long get() = startMs + 100
    fun rms(): Double {
        var squares = 0L
        for (i in pcm.indices step 2) {
            val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            squares += sample.toLong() * sample
        }
        return sqrt(squares / 1600.0)
    }
    companion object { const val BYTES = 3200 }
}
