package com.drivediary.talkrec.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.drivediary.talkrec.CaptureOptions
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.capture.AudioSource
import com.drivediary.talkrec.capture.MicAudioSource
import com.drivediary.talkrec.capture.PcmFileAudioSource
import com.drivediary.talkrec.sink.KeptBlock
import com.drivediary.talkrec.sink.TalkSink
import java.io.File

/** Debug only. The default sink acknowledges/counts candidates in RAM; it writes no audio or text. */
class DebugCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                "com.drivediary.talkrec.DEBUG_START" -> startDebug(context, intent)
                "com.drivediary.talkrec.DEBUG_INJECT" -> {
                    check(TalkRec.switchSource != null) { "Capture is not running" }
                    TalkRec.switchSource?.invoke(fileSource(intent))
                }
                "com.drivediary.talkrec.DEBUG_MIC" -> TalkRec.switchSource?.invoke { MicAudioSource() }
                "com.drivediary.talkrec.DEBUG_PAUSE" -> TalkRec.pause()
                "com.drivediary.talkrec.DEBUG_RESUME" -> TalkRec.resume()
                "com.drivediary.talkrec.DEBUG_MARK" -> TalkRec.markMoment()
                "com.drivediary.talkrec.DEBUG_STOP" -> TalkRec.stop()
                "com.drivediary.talkrec.DEBUG_STATE" -> Log.i("TalkRec", "debug state=${TalkRec.state.value}")
            }
        } catch (_: RuntimeException) {
            // Exception messages may contain file paths or SDK input. Never log them.
            Log.w("TalkRec", "debug command failed; for background start use DebugCaptureActivity")
        }
    }
}

/** No team app UI is needed. adb starts this tiny translucent Activity; runtime permission is pre-granted. */
class DebugCaptureActivity : Activity() {
    private var started = false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState) }
    override fun onResume() {
        super.onResume()
        if (!started) {
            started = true
            try { startDebug(this, intent) }
            catch (_: RuntimeException) { Log.w("TalkRec", "debug activity start failed") }
            window.decorView.postDelayed({ finish() }, 300)
        }
    }
}

private fun fileSource(intent: Intent): () -> AudioSource {
    val path = requireNotNull(intent.getStringExtra("path")) { "Missing debug PCM path" }
    val loop = intent.getBooleanExtra("loop", false)
    return { PcmFileAudioSource(File(path), loop) }
}

private fun startDebug(context: Context, intent: Intent) {
    val source: () -> AudioSource = if (intent.hasExtra("path")) fileSource(intent) else ({ MicAudioSource() })
    TalkRec.start(context, intent.getLongExtra("tripId", 0L), CaptureOptions(DebugCountingSink(), audioSource = source))
}

private class DebugCountingSink : TalkSink {
    private var previous: String? = null
    private val refusals = mutableSetOf<String>()
    override suspend fun onKeptBlock(block: KeptBlock): Boolean {
        previous = block.id
        Log.i("TalkRec", "debug candidate lines=${block.lines.size} bytes=${block.audio.pcm.size} source=${block.source} score=${block.keepScore}")
        return true
    }
    override suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String): Boolean {
        if (!refusals.add(refusalId)) return false
        val deleted = previous != null; previous = null
        Log.i("TalkRec", "debug retroDelete=$deleted")
        return deleted
    }
}
