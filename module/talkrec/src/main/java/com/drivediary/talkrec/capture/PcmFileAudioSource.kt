package com.drivediary.talkrec.capture

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Bounded streaming reader. WAV must be PCM format 1, mono, 16 kHz, 16 bits. Raw is PCM16 LE. */
class PcmFileAudioSource(
    private val file: File, private val loop: Boolean = false,
    private val trailingSilenceMs: Int = 25_000,
) : AudioSource {
    init { require(trailingSilenceMs >= 0 && trailingSilenceMs % 100 == 0) }

    override fun frames(): Flow<PcmFrame> = flow {
        RandomAccessFile(file, "r").use { input ->
            val (offset, length) = dataRange(input)
            require(length > 0 && length % 2 == 0L) { "Invalid PCM data length" }
            val epoch = System.currentTimeMillis()
            val clock = System.nanoTime()
            var position = 0L
            var frameNumber = 0L
            var tail = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (position == length && loop) position = 0
                val bytes = ByteArray(PcmFrame.BYTES)
                if (position < length) {
                    val count = minOf(bytes.size.toLong(), length - position).toInt()
                    input.seek(offset + position); input.readFully(bytes, 0, count); position += count
                } else {
                    if (tail >= trailingSilenceMs) break
                    tail += 100
                }
                // Timestamp describes sample capture, emit after the frame's full 100 ms has elapsed.
                val due = (frameNumber + 1) * 100
                delay((due - (System.nanoTime() - clock) / 1_000_000).coerceAtLeast(0))
                emit(PcmFrame(epoch + frameNumber * 100, bytes))
                frameNumber++
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun dataRange(input: RandomAccessFile): Pair<Long, Long> {
        if (input.length() < 12) return 0L to input.length()
        val header = ByteArray(12); input.readFully(header)
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return 0L to input.length()
        require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Invalid WAV" }
        var fmt = false
        var data: Pair<Long, Long>? = null
        while (input.filePointer + 8 <= input.length()) {
            val name = ByteArray(4); input.readFully(name)
            val size = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
            val start = input.filePointer
            require(size <= input.length() - start) { "Truncated WAV chunk" }
            when (String(name, Charsets.US_ASCII)) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV format" }
                    val encoding = java.lang.Short.reverseBytes(input.readShort()).toInt()
                    val channels = java.lang.Short.reverseBytes(input.readShort()).toInt()
                    val rate = Integer.reverseBytes(input.readInt())
                    val byteRate = Integer.reverseBytes(input.readInt())
                    val align = java.lang.Short.reverseBytes(input.readShort()).toInt()
                    val bits = java.lang.Short.reverseBytes(input.readShort()).toInt()
                    require(encoding == 1 && channels == 1 && rate == 16000 && byteRate == 32000 && align == 2 && bits == 16) {
                        "Expected 16 kHz mono PCM16 WAV"
                    }
                    fmt = true
                }
                "data" -> { require(data == null) { "Multiple WAV data chunks" }; data = start to size }
            }
            input.seek(start + size + size % 2)
        }
        require(fmt && data != null) { "Missing WAV PCM chunks" }
        return data!!
    }
}
