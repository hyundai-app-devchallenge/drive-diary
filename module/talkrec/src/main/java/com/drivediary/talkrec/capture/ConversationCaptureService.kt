package com.drivediary.talkrec.capture

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.TalkRecDebug
import com.drivediary.talkrec.TalkDebugEvent
import com.drivediary.talkrec.DebugBlockTrace
import com.drivediary.talkrec.block.BlockAssembler
import com.drivediary.talkrec.block.ConversationBlock
import com.drivediary.talkrec.block.FinalLine
import com.drivediary.talkrec.judge.ConversationJudge
import com.drivediary.talkrec.policy.PolicyGate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** Started, non-sticky service. Main owns assembly; IO owns source, inference and ordered sink calls. */
class ConversationCaptureService : Service() {
    private sealed interface Event {
        class Start(val request: TalkRec.Request) : Event
        class Control(val action: String) : Event
        class Switch(
            val factory: (() -> AudioSource)?, val tripId: Long?, val commandResult: CompletableDeferred<Boolean>?,
        ) : Event
        class Audio(val session: Long, val frame: PcmFrame) : Event
        class Line(val session: Long, val epoch: Long, val line: FinalLine) : Event
        class Failed(val session: Long) : Event
        class End(val session: Long) : Event
    }
    private class ClosedBlock(val block: ConversationBlock) {
        val id = UUID.randomUUID().toString()
        val trace: DebugBlockTrace? = TalkRecDebug.blockClosed(id, block, deferred = true)
    }
    private class Candidate(val closed: ClosedBlock, val audio: PcmSlice) {
        val block get() = closed.block
        fun discard() { audio.erase(); closed.trace?.finish() }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<Event>(128, onUndeliveredElement = {
        when (it) {
            is Event.Audio -> it.frame.pcm.fill(0)
            is Event.Switch -> { it.commandResult?.complete(false) }
            else -> Unit
        }
    })
    private val ring = PcmRingBuffer()
    private val pending = mutableListOf<ClosedBlock>()
    private val debugTraces = mutableListOf<DebugBlockTrace>()
    private var assembler = BlockAssembler()
    private var request: TalkRec.Request? = null
    private var factory: (() -> AudioSource)? = null
    private var session = 0L
    private var epoch = 0L
    private var stt: PleosSttClient? = null
    private var sourceJob: Job? = null
    private var initializeJob: Job? = null
    private var lineJob: Job? = null
    private var processJob: Job? = null
    private var candidates: Channel<Candidate>? = null
    private var lastEnd: Long? = null
    private var route: Int? = null
    private var wake: PowerManager.WakeLock? = null
    private var stoppedEvent = false
    private var temporarySource = false
    private var commandResult: CompletableDeferred<Boolean>? = null

    override fun onCreate() {
        super.onCreate()
        requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(
            NotificationChannel(CHANNEL, "여행 대화 기록", NotificationManager.IMPORTANCE_LOW))
        TalkRec.switchSource = { source, tripId, result -> dispatch(Event.Switch(source, tripId, result)) }
        scope.launch {
            for (event in events) {
                try { handle(event) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error(); pauseCapture() }
                catch (_: LinkageError) { error(); pauseCapture() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == START) {
            val next = TalkRec.takeRequest(intent?.getStringExtra("request"))
            if (next == null) { if (request == null) stopSelf(); return START_NOT_STICKY }
            val notification = notification(false)
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else startForeground(NOTIFICATION, notification)
            } catch (_: RuntimeException) {
                error(); TalkRec.finished(next); TalkRec.stopped(next); stopSelf(); return START_NOT_STICKY
            }
            dispatch(Event.Start(next))
        } else if (action in setOf(STOP, PAUSE, RESUME, MARK)) dispatch(Event.Control(action!!))
        else stopSelf()
        return START_NOT_STICKY
    }

    private fun dispatch(event: Event) {
        // Never silently lose a control or result on a full queue.
        if (!events.trySend(event).isSuccess) scope.launch { events.send(event) }
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Start -> {
                endInput()
                TalkRec.stopped(request)
                request = event.request; factory = event.request.options.audioSource; stoppedEvent = false
                temporarySource = false
                assembler = BlockAssembler(silenceRms = event.request.options.silenceRms)
                TalkRec.mutableState.value = CaptureState(tripId = event.request.tripId)
                startInput()
            }
            is Event.Control -> when (event.action) {
                STOP -> { endInput(); debugStopped(); state(CaptureStatus.Off); TalkRec.finished(request); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                PAUSE -> pauseCapture()
                RESUME -> if (request != null && TalkRec.state.value.status == CaptureStatus.Paused) startInput()
                MARK -> if (TalkRec.state.value.status == CaptureStatus.On) {
                    assembler.forceClose(System.currentTimeMillis())?.let { closed(it) }
                    flushCandidates(lastEnd ?: 0)
                }
            }
            is Event.Switch -> {
                val current = request
                val active = TalkRec.state.value.status == CaptureStatus.On
                if (current == null || (event.tripId != null && (event.tripId != current.tripId || !active))) {
                    event.commandResult?.complete(false)
                    return
                }
                endInput()
                temporarySource = event.tripId != null && event.factory != null
                commandResult = event.commandResult
                factory = event.factory ?: current.options.audioSource
                if (active) startInput()
            }
            is Event.Audio -> if (event.session == session) consume(event.frame) else event.frame.pcm.fill(0)
            is Event.Line -> if (event.session == session && event.epoch == epoch) {
                val handled = try { request?.options?.voiceCommand?.invoke(event.line.text) == true }
                    catch (_: RuntimeException) { false }
                val consumed = !handled && try { request?.options?.consumeCommand?.invoke(event.line.text) == true }
                    catch (_: RuntimeException) { false }
                val current = TalkRec.state.value
                TalkRec.mutableState.value = current.copy(finalCount = current.finalCount + 1)
                // A finalized line is speech evidence for the question feature, even when RMS stayed low.
                TalkRec.mutableSpeech.value = TalkRec.speech.value.copy(quietSinceMs = null)
                if (!handled && !consumed) {
                    try { TalkRec.reactionTap?.invoke(event.line.text) } catch (_: RuntimeException) { /* No text in logs. */ }
                }
                if (handled) {
                    flushCandidates(lastEnd ?: 0)
                    val result = commandResult
                    commandResult = null
                    try {
                        pauseCapture()
                        result?.complete(true)
                    } finally {
                        result?.complete(false)
                    }
                    TalkRecDebug.emit(TalkDebugEvent.VoicePaused)
                    Log.i(TAG, "paused by voice command")
                } else if (consumed) {
                    // App voice command (e.g. ask a question): keep the line out of storage and keep capturing.
                    flushCandidates(lastEnd ?: 0)
                } else if (commandResult == null) {
                    assembler.onFinal(event.line).forEach(::closed)
                    flushCandidates(lastEnd ?: 0)
                }
            }
            is Event.Failed -> if (event.session == session) { error(); pauseCapture() }
            is Event.End -> if (event.session == session) {
                if (stt?.isInputDrained == false) {
                    // Let a reconnect's queued frames and final callbacks drain; keep the actor responsive.
                    scope.launch { delay(100); events.send(Event.End(event.session)) }
                    return
                }
                // File source includes a 25 s silent drain. Wait for all closed candidates to finish first.
                candidates?.close(); processJob?.join()
                if (TalkRecDebug.enabled) TalkRecDebug.emit(TalkDebugEvent.SourceEnded)
                if (temporarySource) {
                    endInput()
                    restoreDefaultSource()
                    startInput()
                } else pauseCapture()
            }
        }
    }

    @Suppress("WakelockTimeout")
    private fun acquireWake() {
        if (wake == null) wake = requireNotNull(getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TalkRec:capture").also { it.acquire() }
    }

    private suspend fun startInput() {
        val active = request ?: return
        acquireWake(); state(CaptureStatus.On)
        val id = ++session
        openRecognition(id)
        val client = stt!!
        initializeJob = scope.launch {
            try {
                client.start()
                sourceJob = scope.launch {
                    try {
                        factory!!.invoke().frames().collect { events.send(Event.Audio(id, it)) }
                        events.send(Event.End(id))
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { events.send(Event.Failed(id)) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { events.send(Event.Failed(id)) }
        }
        Log.i(TAG, "capture started tripId=${active.tripId}")
    }

    private fun openRecognition(id: Long) {
        val active = request!!
        val e = ++epoch
        val queue = Channel<Candidate>(2, onUndeliveredElement = { it.discard() })
        candidates = queue
        processJob = scope.launch(Dispatchers.IO) {
            var judge: ConversationJudge? = null
            var processor: CaptureProcessor? = null
            try {
                val policy = PolicyGate.fromAssets { assets.open(it) }
                judge = try { ConversationJudge.fromAssets { assets.open(it) } }
                    catch (_: Exception) { Log.w(TAG, "judge unavailable fallback=hourly_budget"); null }
                    catch (_: LinkageError) { Log.w(TAG, "judge unavailable fallback=hourly_budget"); null }
                    catch (_: OutOfMemoryError) { Log.w(TAG, "judge unavailable fallback=hourly_budget"); null }
                val loaded = judge
                val consumer = CaptureProcessor(active.tripId, policy, active.options.sink, active.options.budgetOverride ?: TalkRec.budget,
                    loaded?.let { model -> { texts -> model.judge(texts) } },
                    onJudgeFailure = {
                        try { loaded?.close() } catch (_: Exception) { /* No exception messages. */ }
                        Log.w(TAG, "judge failed fallback=hourly_budget")
                    })
                processor = consumer
                for (candidate in queue) {
                    // The processor owns/erases this PCM even if the app's signal provider fails.
                    try {
                        val signals = active.options.signals(candidate.block.startMs, candidate.block.endMs)
                        val delta = withTimeout(30_000) { consumer.process(candidate.block, candidate.audio, signals,
                            candidate.closed.id, candidate.closed.trace) }
                        // A durable acknowledgement must update the count even if pause just cancelled this job.
                        // endInput joins this worker before starting/resetting another trip's state.
                        if (delta != 0) withContext(NonCancellable + Dispatchers.Main.immediate) {
                            val current = TalkRec.state.value
                            TalkRec.mutableState.value = current.copy(savedCount = (current.savedCount + delta).coerceAtLeast(0))
                            Log.i(TAG, "sink saved=${TalkRec.state.value.savedCount} delta=$delta")
                        }
                    } finally { candidate.discard() }
                }
            } catch (_: TimeoutCancellationException) { events.send(Event.Failed(id)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { events.send(Event.Failed(id)) }
            catch (_: LinkageError) { events.send(Event.Failed(id)) }
            finally {
                try { withContext(NonCancellable) { processor?.closeRun() } }
                finally { try { judge?.close() } finally { queue.cancel() } }
            }
        }
        val client = PleosSttClient(this, scope, active.options.locale,
            onStatus = { status -> TalkRec.mutableState.value = TalkRec.state.value.copy(sttStatus = status) },
            onFailure = { dispatch(Event.Failed(id)) },
        )
        stt = client
        lineJob = scope.launch { client.lines.collect { events.send(Event.Line(id, e, it)) } }
    }

    private suspend fun consume(frame: PcmFrame) {
        try {
            if (!frame.available) {
                val current = TalkRec.state.value
                TalkRec.mutableState.value = current.copy(unavailableFrameCount = current.unavailableFrameCount + 1)
                // Missing input is never evidence of quiet.
                TalkRec.mutableSpeech.value = SpeechActivity()
                if (stt != null) { resetRecognition(); Log.i(TAG, "input unavailable") }
                return
            }
            if (lastEnd != null && (frame.startMs != lastEnd || route != frame.routeId)) {
                val current = TalkRec.state.value
                TalkRec.mutableState.value = current.copy(discontinuityCount = current.discontinuityCount + 1)
                TalkRec.mutableSpeech.value = SpeechActivity()
                resetRecognition()
                Log.i(TAG, "input discontinuity discarded")
            }
            if (stt == null) {
                openRecognition(session)
                val client = stt!!
                initializeJob = scope.launch { client.start() }
            }
            ring.append(frame.startMs, frame.pcm)
            lastEnd = frame.endMs; route = frame.routeId
            stt!!.send(frame)
            val current = TalkRec.state.value
            TalkRec.mutableState.value = current.copy(accumulatedCaptureMs = current.accumulatedCaptureMs + 100,
                frameCount = current.frameCount + 1)
            val rms = frame.rms()
            if (TalkRecDebug.enabled) TalkRecDebug.level(rms, frame.endMs)
            val speech = TalkRec.speech.value
            TalkRec.mutableSpeech.value = SpeechActivity(
                quietSinceMs = if (rms >= (request?.options?.silenceRms ?: 140.0)) null else speech.quietSinceMs ?: frame.startMs,
                lastFrameEndMs = frame.endMs,
            )
            assembler.onEnergy(frame.startMs, frame.endMs, rms).forEach(::closed)
            flushCandidates(frame.endMs)
            if (TalkRec.state.value.frameCount % 100 == 0L) {
                val s = TalkRec.state.value
                Log.i(TAG, "capture frames=${s.frameCount} captureMs=${s.accumulatedCaptureMs} finals=${s.finalCount} blocks=${s.blockCount} saved=${s.savedCount} errors=${s.errorCount} unavailable=${s.unavailableFrameCount} gaps=${s.discontinuityCount}")
            }
        } finally { frame.pcm.fill(0) }
    }

    private fun closed(block: ConversationBlock) {
        val closed = ClosedBlock(block)
        pending += closed
        closed.trace?.let(debugTraces::add)
        val current = TalkRec.state.value
        TalkRec.mutableState.value = current.copy(blockCount = current.blockCount + 1)
        Log.i(TAG, "block closed reason=${block.reason} lines=${block.lines.size} durationMs=${block.endMs - block.startMs}")
    }

    private fun flushCandidates(audioUntil: Long) {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val closed = iterator.next()
            val block = closed.block
            if (audioUntil < block.endMs + 2000) continue
            if (TalkRec.inNoRecord(block.startMs, block.endMs)) {
                // Question check, question audio or its answer window (question-suggest DESIGN §6).
                closed.trace?.finish(); iterator.remove()
                Log.i(TAG, "block skipped reason=question_window")
                continue
            }
            val audio = ring.slice(block.startMs - 3000, block.endMs + 2000)
            if (candidates?.trySend(Candidate(closed, audio))?.isSuccess != true) {
                audio.erase(); closed.trace?.finish(); dispatch(Event.Failed(session))
            }
            iterator.remove()
        }
    }

    private suspend fun resetRecognition() {
        epoch++
        initializeJob?.cancelAndJoin(); initializeJob = null
        stt?.close(); stt = null
        lineJob?.cancelAndJoin(); lineJob = null
        // Drain queued saves, then close their run in the consumer's finally block.
        candidates?.close(); candidates = null
        processJob?.join(); processJob = null
        pending.forEach { it.trace?.finish() }; pending.clear()
        debugTraces.forEach { it.finish() }; debugTraces.clear()
        assembler.reset(); ring.clear(); lastEnd = null; route = null
    }

    private suspend fun endInput() {
        commandResult?.complete(false)
        commandResult = null
        TalkRec.mutableSpeech.value = SpeechActivity()
        session++
        sourceJob?.cancelAndJoin(); sourceJob = null
        resetRecognition()
        wake?.let { if (it.isHeld) it.release() }; wake = null
    }
    private fun restoreDefaultSource() {
        if (temporarySource) {
            temporarySource = false
            factory = request?.options?.audioSource
        }
    }

    private suspend fun pauseCapture() {
        endInput()
        restoreDefaultSource()
        state(CaptureStatus.Paused)
    }

    private fun state(status: CaptureStatus) {
        TalkRec.mutableState.value = TalkRec.state.value.copy(
            status = status, usingTestAudio = status == CaptureStatus.On && temporarySource,
        )
        if (status == CaptureStatus.On || status == CaptureStatus.Paused) {
            requireNotNull(getSystemService(NotificationManager::class.java)).notify(NOTIFICATION, notification(status == CaptureStatus.Paused))
        }
    }
    private fun error() {
        val current = TalkRec.state.value
        TalkRec.mutableState.value = current.copy(errorCount = current.errorCount + 1)
        Log.w(TAG, "capture error count=${TalkRec.state.value.errorCount}")
    }

    private fun debugStopped() {
        if (!stoppedEvent) {
            stoppedEvent = true
            if (TalkRecDebug.enabled) TalkRecDebug.emit(TalkDebugEvent.Stopped)
        }
    }

    private fun notification(paused: Boolean): Notification {
        fun action(label: String, command: String, code: Int): Notification.Action {
            val pending = PendingIntent.getService(this, code, Intent(this, ConversationCaptureService::class.java)
                .setAction(command), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(null, label, pending).build()
        }
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "대화 기록 일시정지" else "여행 대화를 기록하고 있어요")
            .setContentText("남길 만한 대화만 저장해요")
            .addAction(action(if (paused) "다시 시작" else "잠시 멈춤", if (paused) RESUME else PAUSE, 1))
            .addAction(action("기록 종료", STOP, 2)).setOngoing(true).build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        TalkRec.switchSource = null
        commandResult?.complete(false)
        scope.cancel(); events.cancel(); stt?.close(); candidates?.cancel()
        pending.forEach { it.trace?.finish() }; pending.clear(); assembler.reset(); ring.clear()
        debugTraces.forEach { it.finish() }; debugTraces.clear()
        debugStopped()
        wake?.let { if (it.isHeld) it.release() }; wake = null
        state(CaptureStatus.Off); TalkRec.finished(request); TalkRec.stopped(request)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TalkRec"
        private const val CHANNEL = "talkrec_capture"
        private const val NOTIFICATION = 7031
        internal const val START = "com.drivediary.talkrec.START"
        internal const val STOP = "com.drivediary.talkrec.STOP"
        internal const val PAUSE = "com.drivediary.talkrec.PAUSE"
        internal const val RESUME = "com.drivediary.talkrec.RESUME"
        internal const val MARK = "com.drivediary.talkrec.MARK"
    }
}
