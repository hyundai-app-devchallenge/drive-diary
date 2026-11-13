package com.drivediary.talkrec.clip

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Consumes borrowed PCM16 LE / 16 kHz / mono before returning. Call on IO. */
class ClipWriter {
    /** Publishes only a finished MPEG-4 file, by a same-directory atomic rename. */
    suspend fun write(pcm: ByteArray, target: File): Long {
        require(pcm.isNotEmpty() && pcm.size % 2 == 0) { "Expected nonempty PCM16" }
        check(!target.exists()) { "Clip already exists" }
        val directory = requireNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create clip directory" }
        val temp = File.createTempFile(target.name + "-", ".part", directory)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var published = false
        try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = encoder
            encoder.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 16_000, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val container = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = container
            encoder.start()
            codecStarted = true
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var inputEnded = false
            var outputEnded = false
            var track = -1
            var samplesWritten = 0
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(encoder.getInputBuffer(inputIndex))
                        input.clear()
                        val size = minOf(input.remaining() / 2 * 2, pcm.size - offset)
                        check(size > 0 || offset == pcm.size) { "Encoder input buffer too small" }
                        if (size > 0) input.put(pcm, offset, size)
                        val ptsUs = offset / 2L * 1_000_000L / 16_000L
                        inputEnded = offset == pcm.size
                        encoder.queueInputBuffer(inputIndex, 0, size, ptsUs,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        offset += size
                    }
                }
                when (val outputIndex = encoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Encoder format changed twice" }
                        track = container.addTrack(encoder.outputFormat)
                        container.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted) { "Missing AAC output format" }
                                val output = requireNotNull(encoder.getOutputBuffer(outputIndex))
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                container.writeSampleData(track, output, info)
                                samplesWritten++
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { encoder.releaseOutputBuffer(outputIndex, false) }
                    }
                }
            }
            check(muxerStarted && samplesWritten > 0) { "Empty AAC output" }
            container.stop()
            muxerStarted = false
            container.release()
            muxer = null
            FileOutputStream(temp, true).use { it.fd.sync() }
            currentCoroutineContext().ensureActive()
            check(!target.exists() && temp.renameTo(target)) { "Cannot publish clip" }
            published = true
            return pcm.size / 2L * 1000L / 16_000L
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!published) temp.delete()
        }
    }
}
