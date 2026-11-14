package com.drivediary.talkrec.debug

import android.content.Context
import android.net.Uri
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.capture.AudioSource
import com.drivediary.talkrec.capture.CaptureStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/** Switch only the active trip input. Its interpretation, consent and sink stay in place. */
object DebugTripAudio {
    fun useFile(context: Context, tripId: Long, uri: Uri): Boolean {
        val app = context.applicationContext
        return switch(tripId) { DecodedAudioFileSource(app, uri, playAloud = false) }
    }

    /** The fixture goes through STT and command matching, with its own result and no conversation sink. */
    fun testCommand(context: Context, tripId: Long, uri: Uri): Deferred<Boolean>? {
        val app = context.applicationContext
        val result = CompletableDeferred<Boolean>()
        return if (switch(tripId, result) { DecodedAudioFileSource(app, uri, playAloud = false) }) result else null
    }

    fun useMicrophone(tripId: Long): Boolean = switch(tripId, factory = null)

    private fun switch(
        tripId: Long, commandResult: CompletableDeferred<Boolean>? = null, factory: (() -> AudioSource)?,
    ): Boolean {
        val capture = TalkRec.state.value
        if (tripId <= TalkRecLab.LAB_TRIP_ID || capture.tripId != tripId || capture.status != CaptureStatus.On) return false
        val switchSource = TalkRec.switchSource ?: return false
        switchSource(factory, tripId, commandResult)
        return true
    }
}
