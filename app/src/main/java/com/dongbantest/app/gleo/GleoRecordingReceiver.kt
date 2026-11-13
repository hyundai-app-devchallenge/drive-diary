package com.dongbantest.app.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dongbantest.app.AppContainer
import com.dongbantest.app.DongbanApplication
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.capture.CaptureStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal enum class GleoRecordingAction(
    val functionName: String,
    val example: String,
    val captureStatus: CaptureStatus,
    val successMessage: String,
) {
    PAUSE("PAUSE_TRIP_RECORDING", "글레오, 녹음 꺼줘", CaptureStatus.Paused, "Dongbancha trip recording is paused."),
    RESUME("RESUME_TRIP_RECORDING", "글레오, 녹음 다시 시작해", CaptureStatus.On, "Dongbancha trip recording has resumed."),
}

/** Receives package-targeted Gleo service actions. The manifest verifies the sender's permission. */
class GleoRecordingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = GleoRecordingAction.entries.firstOrNull { it.functionName == intent.action } ?: return
        val app = context.applicationContext as DongbanApplication
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                val result = performGleoRecordingAction(app.container, action)
                val response = JSONObject()
                    .put("functionName", action.functionName)
                    .put("status", if (result.success) "success" else "fail")
                    .put("message", result.message)
                app.sendBroadcast(
                    Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE")
                        .putExtra("packageName", app.packageName)
                        .putExtra("response", response.toString()),
                    "ai.umos.gleo.permission.APP_ACTION",
                )
            } finally {
                pending.finish()
            }
        }
    }
}

internal data class GleoRecordingResult(val success: Boolean, val message: String)

/** Also used by the debug simulator without sending a callback to the assistant. */
internal suspend fun performGleoRecordingAction(
    container: AppContainer,
    action: GleoRecordingAction,
): GleoRecordingResult = try {
    withTimeoutOrNull(6_000) { executeRecordingAction(container, action) }
        ?: GleoRecordingResult(false, "Dongbancha could not confirm the recording state.")
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    Log.w("GleoRecording", "Recording action failed: ${action.functionName}")
    GleoRecordingResult(false, "Dongbancha could not change the recording state.")
}

private suspend fun executeRecordingAction(container: AppContainer, action: GleoRecordingAction): GleoRecordingResult {
    val tripId = container.tripRepository.observeOngoingTrip().first()?.id
        ?: return GleoRecordingResult(false, "There is no ongoing Dongbancha trip.")
    if (container.recordingConsentStore.get(tripId) != true) {
        return GleoRecordingResult(false, "Recording was not enabled for this trip.")
    }

    val capture = TalkRec.state.value
    val hasSession = capture.tripId == tripId && capture.status in setOf(CaptureStatus.On, CaptureStatus.Paused)
    when (action) {
        GleoRecordingAction.PAUSE -> {
            container.talkRecording.pause(tripId)
            // Latch the pause even when the trip's microphone session has not started.
            if (!hasSession) return GleoRecordingResult(true, action.successMessage)
        }
        GleoRecordingAction.RESUME -> {
            // A broadcast can resume the existing microphone foreground service.
            if (!hasSession || !container.talkRecording.resumeExisting(tripId)) {
                return GleoRecordingResult(false, "Open Dongbancha and resume recording while parked.")
            }
        }
    }

    val reachedTarget = TalkRec.state.first {
        (it.tripId == tripId && it.status == action.captureStatus) || it.status == CaptureStatus.Off
    }.status == action.captureStatus
    return if (reachedTarget) GleoRecordingResult(true, action.successMessage)
    else GleoRecordingResult(false, "The Dongbancha recording session ended.")
}
