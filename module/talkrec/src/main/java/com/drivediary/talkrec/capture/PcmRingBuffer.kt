package com.drivediary.talkrec.capture

/** PCM16 LE, 16 kHz mono. All ranges are half open, in epoch milliseconds. */
class PcmRingBuffer(val durationMs: Int = 180_000) {
    private val capacity = Math.multiplyExact(durationMs, 16)
    private val samples = ShortArray(capacity)
    private var first: Long? = null
    private var until = 0L

    init { require(durationMs > 0) }

    /** Out-of-order/overlapping samples are rejected; gaps are represented by zeroes. */
    @Synchronized
    fun append(startMs: Long, pcm: ByteArray) {
        require(pcm.size % 2 == 0) { "Expected PCM16" }
        if (pcm.isEmpty()) return
        val start = Math.multiplyExact(startMs, 16L)
        val end = Math.addExact(start, pcm.size / 2L)
        val previous = first
        require(previous == null || start >= until) { "PCM time moved backwards" }
        if (previous == null || start - until >= capacity) {
            samples.fill(0)
            first = start
        } else {
            for (i in until until start) samples[index(i)] = 0
        }
        val copyFrom = maxOf(start, end - capacity)
        for (i in copyFrom until end) {
            val offset = ((i - start) * 2).toInt()
            samples[index(i)] = ((pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)).toShort()
        }
        until = end
        first = maxOf(first!!, end - capacity)
    }

    /** Copies only the retained intersection. Callers own and should erase the returned PCM. */
    @Synchronized
    fun slice(startMs: Long, endMs: Long): PcmSlice {
        require(endMs >= startMs) { "Invalid PCM range" }
        val oldest = first ?: return PcmSlice(startMs, startMs, byteArrayOf())
        val from = maxOf(Math.multiplyExact(startMs, 16L), oldest).coerceAtMost(until)
        val to = maxOf(from, minOf(Math.multiplyExact(endMs, 16L), until))
        val bytes = ByteArray(((to - from) * 2).toInt())
        for (i in from until to) {
            val value = samples[index(i)].toInt()
            val offset = ((i - from) * 2).toInt()
            bytes[offset] = value.toByte()
            bytes[offset + 1] = (value shr 8).toByte()
        }
        return PcmSlice(from / 16, to / 16, bytes)
    }

    @Synchronized
    fun clear() { samples.fill(0); first = null; until = 0 }

    private fun index(sample: Long): Int = Math.floorMod(sample, capacity.toLong()).toInt()
}

class PcmSlice(val startMs: Long, val endMs: Long, val pcm: ByteArray) {
    fun erase() = pcm.fill(0)
    override fun toString() = "PcmSlice(startMs=$startMs, endMs=$endMs, bytes=${pcm.size})"
}
