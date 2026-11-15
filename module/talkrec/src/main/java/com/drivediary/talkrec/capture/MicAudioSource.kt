package com.drivediary.talkrec.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The MIC source/format used by the Pleos AVD probe. One collector owns one AudioRecord. */
class MicAudioSource(private val boostEnabled: () -> Boolean = { false }) : AudioSource {
    @Suppress("MissingPermission")
    override fun frames(): Flow<PcmFrame> = flow {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Unsupported microphone format" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6400))
        val samples = ShortArray(1600)
        val inputChanged = AtomicBoolean(false)
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                // Including a short silencing/route transition between polls: discard partial PCM.
                inputChanged.set(true)
            }
        }
        var registered = false
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            if (Build.VERSION.SDK_INT >= 29) {
                recorder.registerAudioRecordingCallback(Executor { it.run() }, callback)
                registered = true
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone start failed" }
            var epoch = System.currentTimeMillis()
            var elapsed = SystemClock.elapsedRealtime()
            var number = 0L
            var filled = 0
            var previousRoute: Int? = null
            var previousAvailable: Boolean? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val config = if (Build.VERSION.SDK_INT >= 29) recorder.activeRecordingConfiguration else null
                val device = if (Build.VERSION.SDK_INT >= 29) config?.audioDevice else recorder.routedDevice
                val route = device?.id ?: -1
                val available = if (Build.VERSION.SDK_INT >= 29) config != null && !config.isClientSilenced && device != null
                    else device != null
                if (inputChanged.getAndSet(false) || route != previousRoute || available != previousAvailable) {
                    filled = 0; samples.fill(0)
                    epoch = System.currentTimeMillis(); elapsed = SystemClock.elapsedRealtime(); number = 0
                    previousRoute = route; previousAvailable = available
                }
                val count = recorder.read(samples, filled, samples.size - filled, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Microphone read failed" }
                filled += count
                if (filled == samples.size) {
                    val bytes = ByteArray(PcmFrame.BYTES)
                    if (available) {
                        if (boostEnabled()) amplifyMicFrame(samples)
                        for (i in samples.indices) {
                            bytes[i * 2] = samples[i].toByte(); bytes[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
                        }
                    }
                    // If scheduling lost real capture time, expose a discontinuity rather than compressing it.
                    if (SystemClock.elapsedRealtime() - elapsed - (number + 1) * 100 > 300) {
                        epoch = System.currentTimeMillis() - 100; elapsed = SystemClock.elapsedRealtime() - 100; number = 0
                    }
                    emit(PcmFrame(epoch + number++ * 100, bytes, available, route))
                    filled = 0
                }
                if (count == 0) delay(10)
            }
        } finally {
            samples.fill(0)
            try {
                if (registered && Build.VERSION.SDK_INT >= 29) recorder.unregisterAudioRecordingCallback(callback)
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally { recorder.release() }
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Noise gate for [amplifyMicFrame]. Same value as the default `silenceRms` (CaptureOptions in TalkRec.kt,
 * BlockAssembler.silenceRms): frames below it count as silence downstream, so they must not be boosted
 * into "loud" frames or silence detection (block close, quiet-car questions) would never fire.
 */
private const val BOOST_GATE_RMS = 140.0

/** Apply up to 30 dB of gain, using one gain per frame to avoid clipping peaks. Frames below [BOOST_GATE_RMS] are left as is. */
internal fun amplifyMicFrame(samples: ShortArray) {
    if (samples.isEmpty()) return
    var squares = 0.0
    for (s in samples) squares += s.toDouble() * s
    if (sqrt(squares / samples.size) < BOOST_GATE_RMS) return
    val peak = samples.maxOfOrNull { abs(it.toInt()) } ?: return
    if (peak == 0) return
    val gain = (Short.MAX_VALUE.toDouble() / peak).coerceIn(1.0, 32.0)
    if (gain == 1.0) return
    for (i in samples.indices) {
        samples[i] = (samples[i] * gain).roundToInt().toShort()
    }
}
