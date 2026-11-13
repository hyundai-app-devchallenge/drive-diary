package com.drivediary.talkrec.capture

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import com.drivediary.talkrec.block.FinalLine
import com.drivediary.talkrec.TalkRecDebug
import com.drivediary.talkrec.TalkDebugEvent
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

/** SDK operations and callbacks are serialized on Main, as in SyntheticStreamProbe. */
class PleosSttClient(
    context: Context, private val scope: CoroutineScope, private val locale: Locale = Locale.KOREA,
    private val onFailure: () -> Unit,
) {
    private val app = context.applicationContext
    private val output = Channel<FinalLine>(256)
    val lines = output.receiveAsFlow()
    private val initialized = CompletableDeferred<Unit>()
    private val queued = ArrayDeque<ByteArray>()
    private var sdk: SpeechToText? = null
    private var listener: ResultListener? = null
    private var timeout: Job? = null
    private var reopen: Job? = null
    private var draining: Job? = null
    private var generation = 0
    private var ready = false
    private var closed = false
    private var finals = 0L
    private var sent = 0L
    private var restarts = 0
    private var failures = 0
    private var consecutiveFailures = 0
    val isInputDrained: Boolean get() = ready && queued.isEmpty() && draining?.isActive != true

    suspend fun start() = withContext(Dispatchers.Main.immediate) {
        initialize()
        initialized.await()
    }

    private fun initialize() {
        if (closed) return
        val id = ++generation
        ready = false
        timeout = scope.launch { delay(25_000); if (generation == id) reconnect(true) }
        try {
            sdk = SpeechToText(app, Mode.ON_DEVICE).also { client ->
                client.initialize(object : OnInitializeListener {
                    override fun onSuccess() { scope.launch(Dispatchers.Main.immediate) {
                        if (closed || generation != id) return@launch
                        try {
                            timeout?.cancel()
                            client.setLocale(locale)
                            val resultListener = results(id)
                            listener = resultListener; client.addListener(resultListener)
                            ready = true; consecutiveFailures = 0; initialized.complete(Unit); drain()
                            Log.i(TAG, "stt ready restarts=$restarts errors=$failures")
                        } catch (_: RuntimeException) { reconnect(true) }
                    } }
                    override fun onFailure(error: ErrorType) { scope.launch(Dispatchers.Main.immediate) {
                        if (generation == id) reconnect(true)
                    } }
                })
            }
        } catch (_: RuntimeException) { reconnect(true) }
    }

    private fun results(id: Int) = object : ResultListener {
        override fun onUpdated(text: String, completed: Boolean) {
            if (!completed) {
                if (TalkRecDebug.enabled) scope.launch(Dispatchers.Main.immediate) {
                    if (!closed && generation == id && TalkRecDebug.enabled) TalkRecDebug.emit(TalkDebugEvent.Partial(text))
                }
                return
            }
            val received = System.currentTimeMillis() // Capture BEFORE dispatch, never SDK's contiguous EPD clock.
            scope.launch(Dispatchers.Main.immediate) {
                if (closed || generation != id) return@launch
                if (TalkRecDebug.enabled) TalkRecDebug.emit(TalkDebugEvent.Final(text, received))
                if (!output.trySend(FinalLine(text, received)).isSuccess) { fail(); return@launch }
                finals++
                Log.i(TAG, "stt final count=$finals chars=${text.length} receivedMs=$received")
            }
        }
        override fun onError(error: ErrorType) { scope.launch(Dispatchers.Main.immediate) {
            if (!closed && generation == id) reconnect(true)
        } }
        override fun onEndedRecognition() { scope.launch(Dispatchers.Main.immediate) {
            if (!closed && generation == id && reopen == null) {
                // Batch probe observed finals AFTER ended. Keep listener/input alive during this drain grace.
                reopen = scope.launch { delay(1500); reopen = null; if (generation == id) reconnect(false) }
            }
        } }
        override fun onReady() = Unit
        override fun onStartedRecognition() { scope.launch(Dispatchers.Main.immediate) {
            // Some engines automatically begin another utterance in the drain window.
            // That is evidence the session is live; do not interrupt its new speech.
            if (!closed && generation == id) { reopen?.cancel(); reopen = null }
        } }
        override fun onUpdatedEpdData(start: Long, end: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }

    suspend fun send(frame: PcmFrame) = withContext(Dispatchers.Main.immediate) {
        if (closed) return@withContext
        if (queued.size >= 300) { fail(); return@withContext }
        queued.addLast(frame.pcm.copyOf())
        drain()
    }

    private fun drain() {
        if (!ready || draining?.isActive == true) return
        draining = scope.launch(Dispatchers.Main.immediate) {
            while (ready && !closed && queued.isNotEmpty()) {
                val bytes = queued.removeFirst()
                // SDK 3.3.1's OnDeviceStt$send$1 retains the array in an asynchronous coroutine.
                // Give it its own copy; erasing that copy here would turn the submitted audio into silence.
                try { sdk!!.sendAudio(bytes.copyOf()); sent++ }
                catch (_: RuntimeException) { reconnect(true) }
                finally { bytes.fill(0) }
                // Normal input arrives every 100 ms. A bounded reconnect backlog catches up at 10 ms.
                if (queued.isNotEmpty()) delay(10)
            }
        }
    }

    private fun reconnect(error: Boolean) {
        if (closed) return
        if (error && TalkRecDebug.enabled) TalkRecDebug.emit(TalkDebugEvent.SttError("stt_reconnect"))
        if (error) { failures++; consecutiveFailures++ }
        release()
        restarts++
        Log.i(TAG, "stt reopen count=$restarts errors=$failures sent=$sent queued=${queued.size}")
        if (consecutiveFailures >= 5) { fail(); return }
        reopen = scope.launch { delay(300); reopen = null; initialize() }
    }

    private fun release() {
        ready = false; generation++
        timeout?.cancel(); reopen?.cancel(); reopen = null
        draining?.cancel(); draining = null
        val client = sdk; sdk = null
        try { listener?.let { client?.removeListener(it) } } catch (_: RuntimeException) { failures++ }
        listener = null
        try { client?.release() } catch (_: RuntimeException) { failures++ }
    }

    private fun fail() {
        if (closed) return
        if (TalkRecDebug.enabled) TalkRecDebug.emit(TalkDebugEvent.SttError("stt_failed"))
        close(); onFailure()
    }

    /** Call on Main. Release cancels in-flight recognition; pause/stop do not flush private candidates. */
    fun close() {
        if (closed) return
        closed = true; release()
        queued.forEach { it.fill(0) }; queued.clear()
        initialized.cancel(); output.cancel()
        Log.i(TAG, "stt closed finals=$finals sent=$sent restarts=$restarts errors=$failures")
    }

    companion object { private const val TAG = "TalkRec" }
}
