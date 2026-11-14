package com.drivediary.talkrec.capture

enum class CaptureStatus { Ready, On, Paused, Off }

enum class SttStatus { Idle, Connecting, Ready, Reconnecting, Failed }

/**
 * Quiet tracking for the question feature (question-suggest DESIGN §4).
 * [quietSinceMs] is null while someone speaks or the input is unknown/unavailable.
 * Quiet so far = [lastFrameEndMs] - [quietSinceMs]. Times are epoch ms.
 */
data class SpeechActivity(val quietSinceMs: Long? = null, val lastFrameEndMs: Long? = null) {
    fun quietMs(nowMs: Long, staleMs: Long = 3_000L): Long {
        val since = quietSinceMs ?: return 0
        val last = lastFrameEndMs ?: return 0
        if (nowMs - last > staleMs) return 0
        return (last - since).coerceAtLeast(0)
    }

    /** Frames are arriving (the microphone or a test source is live). */
    fun live(nowMs: Long, staleMs: Long = 3_000L): Boolean = lastFrameEndMs?.let { nowMs - it <= staleMs } == true
}

data class CaptureState(
    val status: CaptureStatus = CaptureStatus.Ready,
    val accumulatedCaptureMs: Long = 0,
    val savedCount: Int = 0,
    val frameCount: Long = 0,
    val finalCount: Long = 0,
    val blockCount: Long = 0,
    val errorCount: Long = 0,
    val unavailableFrameCount: Long = 0,
    val discontinuityCount: Long = 0,
    val tripId: Long? = null,
    val sttStatus: SttStatus = SttStatus.Idle,
    val usingTestAudio: Boolean = false,
)
