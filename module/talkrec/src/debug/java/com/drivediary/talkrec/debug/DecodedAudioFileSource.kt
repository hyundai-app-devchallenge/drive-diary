package com.drivediary.talkrec.debug

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.drivediary.talkrec.TalkDebugEvent
import com.drivediary.talkrec.TalkRecDebug
import com.drivediary.talkrec.capture.AudioSource
import com.drivediary.talkrec.capture.PcmFrame
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

enum class AudioFileError { FILE_UNREADABLE, UNSUPPORTED_AUDIO, CORRUPT_AUDIO, PLAYBACK_FAILED }

/** Fixed, UI-safe message; intentionally carries no provider URI/path or nested cause. */
class AudioFileException(val code: AudioFileError) : Exception(when (code) {
    AudioFileError.FILE_UNREADABLE -> "Cannot read audio file"
    AudioFileError.UNSUPPORTED_AUDIO -> "Unsupported audio format"
    AudioFileError.CORRUPT_AUDIO -> "Invalid audio file"
    AudioFileError.PLAYBACK_FAILED -> "Audio playback unavailable"
})

/** content/file URI -> platform decoder -> mono/16 kHz PCM16 -> paced 100 ms frames. No disk writes. */
class DecodedAudioFileSource(
    context: Context, private val uri: Uri, private val playAloud: Boolean,
) : AudioSource {
    private val app = context.applicationContext

    override fun frames(): Flow<PcmFrame> = flow {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var track: AudioTrack? = null
        var codecStarted = false
        try {
            try {
                if (uri.scheme == "file") {
                    // Use a descriptor so platform decoder diagnostics need no user-chosen path.
                    FileInputStream(requireNotNull(uri.path)).use { extractor.setDataSource(it.fd) }
                } else {
                    extractor.setDataSource(app, uri, null)
                }
            }
            catch (_: Exception) { throw AudioFileException(AudioFileError.FILE_UNREADABLE) }
            val audioIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioFileException(AudioFileError.UNSUPPORTED_AUDIO)
            extractor.selectTrack(audioIndex)
            val inputFormat = extractor.getTrackFormat(audioIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw AudioFileException(AudioFileError.UNSUPPORTED_AUDIO)

            var converter: Mono16kResampler? = null
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            fun outputFormat(format: MediaFormat) {
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (rate <= 0 || channels !in 1..32) throw AudioFileException(AudioFileError.UNSUPPORTED_AUDIO)
                val previous = converter
                if (previous != null && (previous.inputRate != rate || previous.channels != channels)) {
                    throw AudioFileException(AudioFileError.UNSUPPORTED_AUDIO)
                }
                if (previous == null) converter = Mono16kResampler(rate, channels)
                encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    else AudioFormat.ENCODING_PCM_16BIT
            }

            val frame = ByteArray(PcmFrame.BYTES)
            var used = 0
            var frameNumber = 0L
            var epoch = 0L
            var clock = 0L
            suspend fun sendFrame() {
                currentCoroutineContext().ensureActive()
                if (frameNumber == 0L) { epoch = System.currentTimeMillis(); clock = System.nanoTime() }
                val bytes = frame.copyOf()
                // Queue this frame for audible playback at the START of its 100 ms interval.
                // Emit only after that interval, like MicAudioSource/PcmFileAudioSource.
                track?.let { player ->
                    var offset = 0
                    while (offset < bytes.size) {
                        currentCoroutineContext().ensureActive()
                        val written = player.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        if (written < 0) throw AudioFileException(AudioFileError.PLAYBACK_FAILED)
                        if (written == 0) delay(5) else offset += written
                    }
                }
                val due = (frameNumber + 1) * 100
                delay((due - (System.nanoTime() - clock) / 1_000_000).coerceAtLeast(0))
                emit(PcmFrame(epoch + frameNumber * 100, bytes))
                frameNumber++; frame.fill(0); used = 0
            }
            suspend fun samples(pcm: ShortArray) {
                try {
                    for (sample in pcm) {
                        frame[used++] = sample.toByte()
                        frame[used++] = (sample.toInt() shr 8).toByte()
                        if (used == frame.size) sendFrame()
                    }
                } finally { pcm.fill(0) }
            }
            suspend fun decoded(buffer: ByteBuffer) {
                val floats = decodePcm(buffer, encoding)
                try { samples(requireNotNull(converter).push(floats)) }
                finally { floats.fill(0f) }
            }

            if (playAloud) {
                try {
                    val minimum = AudioTrack.getMinBufferSize(16_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) throw AudioFileException(AudioFileError.PLAYBACK_FAILED)
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, PcmFrame.BYTES * 2)).build()
                    track!!.play()
                } catch (_: Exception) { throw AudioFileException(AudioFileError.PLAYBACK_FAILED) }
            }

            if (mime == "audio/raw") {
                // WAV PCM does not necessarily have a MediaCodec decoder. Extractor output is PCM already.
                outputFormat(inputFormat)
                val chunk = ByteBuffer.allocate(262_144)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    chunk.clear()
                    val count = extractor.readSampleData(chunk, 0)
                    if (count < 0) break
                    chunk.position(0); chunk.limit(count)
                    decoded(chunk)
                    if (!extractor.advance()) break
                }
            } else {
                val decoder = try { MediaCodec.createDecoderByType(mime) }
                    catch (_: Exception) { throw AudioFileException(AudioFileError.UNSUPPORTED_AUDIO) }
                codec = decoder
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                decoder.configure(inputFormat, null, null, 0); decoder.start(); codecStarted = true
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var lastProgress = System.nanoTime()
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = requireNotNull(decoder.getInputBuffer(index)); buffer.clear()
                            val count = extractor.readSampleData(buffer, 0)
                            if (count < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            lastProgress = System.nanoTime()
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { outputFormat(decoder.outputFormat); lastProgress = System.nanoTime() }
                        index >= 0 -> {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    if (converter == null) outputFormat(decoder.outputFormat)
                                    val buffer = requireNotNull(decoder.getOutputBuffer(index))
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    decoded(buffer)
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                lastProgress = System.nanoTime()
                            } finally { decoder.releaseOutputBuffer(index, false) }
                        }
                        else -> {
                            if (System.nanoTime() - lastProgress > 10_000_000_000L) throw AudioFileException(AudioFileError.CORRUPT_AUDIO)
                            delay(2)
                        }
                    }
                }
            }
            samples(converter?.finish() ?: throw AudioFileException(AudioFileError.CORRUPT_AUDIO))
            if (frameNumber == 0L && used == 0) throw AudioFileException(AudioFileError.CORRUPT_AUDIO)
            if (used > 0) sendFrame() // Zero-pad the final short frame.
            repeat(250) { sendFrame() } // 25 s to close the last assembler block and its post-roll.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (safe: AudioFileException) {
            if (TalkRecDebug.enabled) TalkRecDebug.emit(TalkDebugEvent.SourceError(safe.code.name.lowercase()))
            throw safe
        } catch (_: Exception) {
            val safe = AudioFileException(AudioFileError.CORRUPT_AUDIO)
            if (TalkRecDebug.enabled) TalkRecDebug.emit(TalkDebugEvent.SourceError(safe.code.name.lowercase()))
            throw safe
        } finally {
            try { track?.pause(); track?.flush() } catch (_: Exception) { }
            try { track?.release() } catch (_: Exception) { }
            try { if (codecStarted) codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }.flowOn(Dispatchers.IO)

    private fun decodePcm(buffer: ByteBuffer, encoding: Int): FloatArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val width = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw AudioFileException(AudioFileError.UNSUPPORTED_AUDIO)
        }
        if (buffer.remaining() % width != 0) throw AudioFileException(AudioFileError.CORRUPT_AUDIO)
        return FloatArray(buffer.remaining() / width) {
            when (encoding) {
                AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) * 256f
                AudioFormat.ENCODING_PCM_16BIT -> buffer.short.toFloat()
                AudioFormat.ENCODING_PCM_FLOAT -> buffer.float * 32768f
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val value = (buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
                    value / 256f
                }
                else -> buffer.int / 65536f
            }
        }
    }
}
