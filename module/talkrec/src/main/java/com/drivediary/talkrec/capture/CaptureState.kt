package com.drivediary.talkrec.capture

enum class CaptureStatus { Ready, On, Paused, Off }

enum class SttStatus { Idle, Connecting, Ready, Reconnecting, Failed }

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
