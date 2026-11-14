package com.drivediary.talkrec.ask

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** TTS event for the debug lab: name and wall-clock time. Never the spoken text. */
data class TtsLog(val atMs: Long, val name: String)

/** [error] is null on success. Times are from the speak() call. */
data class SpeakResult(val error: String?, val firstSoundMs: Long?, val totalMs: Long) {
    val ok: Boolean get() = error == null
}

interface QuestionSpeaker {
    val events: SharedFlow<TtsLog>
    /** Speaks one question and suspends until it finishes, stops or fails. One at a time. */
    suspend fun speak(text: String): SpeakResult
    fun stop()
    fun release()
}

/**
 * Pleos TextToSpeech SDK (3.3.1). ON_DEVICE needs no network and no My Project registration.
 * SDK calls and callbacks stay on Main. See question-suggest DESIGN §6.
 */
class PleosQuestionSpeaker(
    context: Context,
    private val mode: Mode = Mode.ON_DEVICE,
) : QuestionSpeaker {
    private val app = context.applicationContext
    private val log = MutableSharedFlow<TtsLog>(extraBufferCapacity = 32)
    override val events: SharedFlow<TtsLog> = log.asSharedFlow()
    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    @Volatile private var current: CompletableDeferred<String>? = null
    @Volatile private var firstSoundAt: Long? = null

    private fun emit(name: String) {
        Log.i(TAG, "tts $name mode=$mode")
        log.tryEmit(TtsLog(System.currentTimeMillis(), name))
    }

    private val listener = object : EventListener {
        override fun onReady() = emit("ready")
        override fun onStart() { if (firstSoundAt == null) firstSoundAt = SystemClock.elapsedRealtime(); emit("start") }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { emit("done"); current?.complete(DONE) }
        override fun onStop() { emit("stop"); current?.complete("STOPPED") }
        override fun onError(errorType: ErrorType) { emit("error:${errorType.name}"); current?.complete(errorType.name) }
    }

    /** Call on Main. Creates and initializes the SDK once; a failed init is retried on the next speak. */
    private suspend fun ensureReady(): Boolean {
        ready?.let { return it.await() }
        val done = CompletableDeferred<Boolean>()
        ready = done
        try {
            val engine = TextToSpeech(app, mode)
            tts = engine
            emit("init")
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    try {
                        engine.addEventListener(listener)
                        engine.setLocale(Locale.KOREA)
                        emit("init_ok"); done.complete(true)
                    } catch (_: RuntimeException) { emit("init_error:setup"); done.complete(false) }
                }
                override fun onFailure(errorType: ErrorType) { emit("init_error:${errorType.name}"); done.complete(false) }
            })
        } catch (_: RuntimeException) { emit("init_error:exception"); done.complete(false) }
        val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { done.await() } ?: false.also { emit("init_error:TIMEOUT") }
        if (!ok) { releaseEngine(); ready = null }
        return ok
    }

    override suspend fun speak(text: String): SpeakResult = mutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val begin = SystemClock.elapsedRealtime()
            if (!ensureReady()) return@withContext SpeakResult("INIT_FAILED", null, SystemClock.elapsedRealtime() - begin)
            val engine = tts ?: return@withContext SpeakResult("INIT_FAILED", null, 0)
            val finished = CompletableDeferred<String>()
            current = finished
            firstSoundAt = null
            val error = try {
                currentCoroutineContext().ensureActive()
                engine.speak(text, true)
                val outcome = withTimeoutOrNull(SPEAK_TIMEOUT_MS) { finished.await() }
                when (outcome) {
                    null -> "TIMEOUT".also { runCatching { engine.stop() } }
                    DONE -> null
                    else -> outcome
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) { "SPEAK_EXCEPTION" }
            finally {
                // Cancellation must release actual device TTS, including while stop callbacks lag.
                if (!finished.isCompleted) runCatching { engine.stop() }
                current = null
            }
            val end = SystemClock.elapsedRealtime()
            SpeakResult(error, firstSoundAt?.let { it - begin }, end - begin)
        }
    }

    override fun stop() {
        current?.complete("STOPPED")
        runCatching { tts?.stop() }
    }

    override fun release() {
        current?.complete("RELEASED")
        releaseEngine()
        ready = null
    }

    private fun releaseEngine() {
        val engine = tts ?: return
        tts = null
        runCatching { engine.removeEventListener(listener) }
        runCatching { engine.release() }
    }

    private companion object {
        const val TAG = "QuestionSpeaker"
        const val DONE = "DONE"
        const val INIT_TIMEOUT_MS = 10_000L
        const val SPEAK_TIMEOUT_MS = 30_000L
    }
}
