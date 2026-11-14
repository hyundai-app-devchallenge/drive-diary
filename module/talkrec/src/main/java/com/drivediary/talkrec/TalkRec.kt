package com.drivediary.talkrec

import android.content.Context
import android.content.Intent
import com.drivediary.talkrec.capture.AudioSource
import com.drivediary.talkrec.capture.CaptureBudget
import com.drivediary.talkrec.capture.CaptureState
import com.drivediary.talkrec.capture.ConversationCaptureService
import com.drivediary.talkrec.capture.MicAudioSource
import com.drivediary.talkrec.capture.SpeechActivity
import com.drivediary.talkrec.policy.AppSignals
import com.drivediary.talkrec.sink.TalkSink
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CaptureOptions(
    val sink: TalkSink,
    val locale: Locale = Locale.KOREA,
    val silenceRms: Double = 140.0,
    val audioSource: () -> AudioSource = { MicAudioSource() },
    /** Evidence must describe the entire block. Playback alone is insufficient. */
    val signals: (startMs: Long, endMs: Long) -> AppSignals? = { _, _ -> null },
    /** Runs on the capture service's main coroutine. Return true to pause capture and consume that line. */
    val voiceCommand: (text: String) -> Boolean = { false },
    /** Runs after [voiceCommand]. Return true to consume that line (not stored, no reaction tap) without pausing. */
    val consumeCommand: (text: String) -> Boolean = { false },
) {
    init { require(silenceRms.isFinite() && silenceRms >= 0) }
    internal var budgetOverride: CaptureBudget? = null
    internal var onReplaced: () -> Unit = {}
}

/** Call start after consent + runtime RECORD_AUDIO permission, from a visible Activity. No auto restore. */
object TalkRec {
    internal data class Request(val id: String, val tripId: Long, val options: CaptureOptions,
        val stopped: CompletableDeferred<Unit> = CompletableDeferred())
    private var application: Context? = null
    private var requested = false
    private var pending: Request? = null
    private var activeOptions: CaptureOptions? = null
    private var activeRequest: Request? = null
    internal val mutableState = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = mutableState.asStateFlow()
    internal val budget = CaptureBudget()

    @Synchronized
    fun start(context: Context, tripId: Long, options: CaptureOptions) {
        require(tripId >= 0) { "Invalid trip ID" }
        val app = context.applicationContext
        val request = Request(UUID.randomUUID().toString(), tripId, options)
        activeOptions?.onReplaced?.invoke()
        activeOptions = options
        activeRequest = request
        application = app; pending = request; requested = true
        try {
            app.startForegroundService(Intent(app, ConversationCaptureService::class.java)
                .setAction(ConversationCaptureService.START).putExtra("request", request.id))
        } catch (error: RuntimeException) { finished(request); stopped(request); throw error }
    }

    @Synchronized internal fun takeRequest(id: String?): Request? = pending?.takeIf { it.id == id }?.also { pending = null }
    @Synchronized internal fun finished(request: Request?) {
        if (request == null || activeRequest !== request) return
        requested = false; pending = null
        activeOptions?.onReplaced?.invoke(); activeOptions = null
        activeRequest = null
    }
    internal fun stopped(request: Request?) { request?.stopped?.complete(Unit) }

    /** Includes a pending start-service handoff. Completion means the service released this session. */
    @Synchronized fun stopForTrip(tripId: Long): Deferred<Unit>? {
        val request = activeRequest?.takeIf { it.tripId == tripId } ?: return null
        control(ConversationCaptureService.STOP)
        return request.stopped
    }
    /** Includes the start-service handoff and paused captures, which state alone cannot detect. */
    @Synchronized internal fun isIdle(): Boolean = !requested && state.value.status !in setOf(
        com.drivediary.talkrec.capture.CaptureStatus.On, com.drivediary.talkrec.capture.CaptureStatus.Paused)
    @Synchronized internal fun owns(options: CaptureOptions): Boolean = requested && activeOptions === options
    @Synchronized internal fun stopIfOwner(options: CaptureOptions) { if (owns(options)) stop() }
    fun stop() = control(ConversationCaptureService.STOP)
    fun pause() = control(ConversationCaptureService.PAUSE)
    fun resume() = control(ConversationCaptureService.RESUME)
    fun markMoment() = control(ConversationCaptureService.MARK)

    @Synchronized private fun control(action: String) {
        if (!requested) return
        val app = application ?: return
        app.startService(Intent(app, ConversationCaptureService::class.java).setAction(action))
    }

    internal val mutableSpeech = MutableStateFlow(SpeechActivity())
    /** Quiet tracking for the question feature. Only times, never text. */
    val speech: StateFlow<SpeechActivity> = mutableSpeech.asStateFlow()

    /**
     * Set only while the question check (Q01) waits for a spoken reaction; null otherwise.
     * Called on the capture service's main coroutine with each finalized line. The receiver must not store it.
     */
    @Volatile var reactionTap: ((text: String) -> Unit)? = null

    private val noRecordWindows = mutableListOf<LongRange>()

    /** Blocks overlapping [fromMs, untilMs] (epoch ms) are dropped: not judged, saved or summarized. */
    fun markNoRecord(fromMs: Long, untilMs: Long) {
        require(untilMs >= fromMs)
        synchronized(noRecordWindows) {
            noRecordWindows.removeAll { it.last < fromMs - WINDOW_KEEP_MS }
            noRecordWindows += fromMs..untilMs
        }
    }

    internal fun inNoRecord(startMs: Long, endMs: Long): Boolean = synchronized(noRecordWindows) {
        noRecordWindows.any { startMs <= it.last && endMs >= it.first }
    }

    private const val WINDOW_KEEP_MS = 30 * 60_000L

    /** Debug receiver's in-process seam. It cannot be invoked through a release exported component. */
    internal var switchSource: ((factory: (() -> AudioSource)?, tripId: Long?, commandResult: CompletableDeferred<Boolean>?) -> Unit)? = null
}
