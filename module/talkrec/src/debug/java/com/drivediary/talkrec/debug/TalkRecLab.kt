package com.drivediary.talkrec.debug

import android.Manifest
import android.content.Context
import android.net.Uri
import com.drivediary.talkrec.CaptureOptions
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.TalkRecDebug
import com.drivediary.talkrec.capture.AudioSource
import com.drivediary.talkrec.capture.CaptureBudget
import com.drivediary.talkrec.capture.MicAudioSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LabBusyException : IllegalStateException("Capture already active")

/** Debug variant only. Call from a visible Activity after runtime permission is granted. */
object TalkRecLab {
    /** Room autogenerates real trip IDs from 1. Zero is its unsaved sentinel, also used by I3 tools. */
    const val LAB_TRIP_ID: Long = 0L
    // File mode also uses the existing microphone-type FGS and therefore needs this permission.
    const val REQUIRED_PERMISSION: String = Manifest.permission.RECORD_AUDIO
    val events = TalkRecDebug.events
    val state = TalkRec.state
    private val mutableSinkState = MutableStateFlow<LabSink?>(null)
    private val mutableStopping = MutableStateFlow(false)
    /** Survives Activity recreation while the previous lab's service is tearing down. */
    val stopping = mutableStopping.asStateFlow()
    /** Obtain sink.value?.state for ID-only keep/retro-delete feedback. */
    val sink = mutableSinkState.asStateFlow()
    @Volatile private var ownedOptions: CaptureOptions? = null

    fun canStart(): Boolean = TalkRec.isIdle()

    fun startMic(context: Context) = start(context) { MicAudioSource() }

    /** Accepts SAF content:// and internal filesDir/lab file:// URIs; no storage permission needed. */
    fun startFile(context: Context, uri: Uri, playAloud: Boolean = false) {
        val app = context.applicationContext
        start(context) { DecodedAudioFileSource(app, uri, playAloud) }
    }

    private fun start(context: Context, source: () -> AudioSource) = synchronized(TalkRec) {
        if (!canStart()) throw LabBusyException()
        val labSink = LabSink()
        val options = CaptureOptions(labSink, audioSource = source).apply {
            // Lab saves must not consume the production session's process-local budget.
            budgetOverride = CaptureBudget()
            onReplaced = {
                TalkRecDebug.enabled = false
                labSink.clear()
                mutableSinkState.value = null
                mutableStopping.value = false
                ownedOptions = null
            }
        }
        mutableSinkState.value = labSink; ownedOptions = options
        TalkRecDebug.enabled = false
        TalkRecDebug.enabled = true
        try { TalkRec.start(context, LAB_TRIP_ID, options) }
        catch (error: RuntimeException) {
            options.onReplaced()
            throw error
        }
    }

    /** Immediate privacy cleanup; capture teardown/Off acknowledgement is asynchronous. */
    fun stop() = synchronized(TalkRec) {
        ownedOptions?.let {
            if (TalkRec.owns(it)) mutableStopping.value = true
            TalkRec.stopIfOwner(it)
        }
        TalkRecDebug.enabled = false
        mutableSinkState.value?.clear(); mutableSinkState.value = null; ownedOptions = null
    }
}
