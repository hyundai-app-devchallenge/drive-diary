package com.drivediary.talkrec

import com.drivediary.talkrec.block.CaptureSource
import com.drivediary.talkrec.block.CloseReason
import com.drivediary.talkrec.block.ConversationBlock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest

enum class DebugOutcome {
    SAVED, JUDGE_DROP, POLICY_BLOCK, COMMAND_LINES_DROPPED, RETRO_DELETE, EMPTY,
    BUDGET, SINK_REJECTED, JUDGE_FAILED,
}

/** Ephemeral diagnostic data. Never log these events or persist their contents. */
sealed interface TalkDebugEvent {
    data class Level(val rms: Double, val atMs: Long) : TalkDebugEvent
    data class Partial(val text: String) : TalkDebugEvent
    data class Final(val text: String, val receivedMs: Long) : TalkDebugEvent
    data class BlockClosed(
        val blockId: String, val reason: CloseReason, val source: CaptureSource,
        val lines: List<String>, val durationMs: Long,
        val runId: String? = null,
    ) : TalkDebugEvent
    data class Decision(
        val blockId: String, val outcome: DebugOutcome, val keepProbability: Double?,
        val kind: String?, val policyReasons: List<String>, val judgeMs: Long?,
        /** Judge failure preserves the production fallback; this can still be +1. */
        val savedCountDelta: Int = 0,
        val runId: String? = null,
    ) : TalkDebugEvent
    data class RunClosed(val runId: String) : TalkDebugEvent
    data object SourceEnded : TalkDebugEvent
    data class SttError(val code: String) : TalkDebugEvent
    /** Decoder/source failures are asynchronous; show the fixed code, never a URI or cause. */
    data class SourceError(val code: String) : TalkDebugEvent
    data object Stopped : TalkDebugEvent
}

/** Off by default, zero replay, bounded and lossy. Disabling retires the entire text buffer. */
object TalkRecDebug {
    private class Session {
        val token = Any()
        val events = MutableSharedFlow<TalkDebugEvent>(extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST)
        var lastLevelMonotonicMs: Long? = null
    }
    private val sessions = MutableStateFlow<Session?>(null)

    var enabled: Boolean
        get() = sessions.value != null
        @Synchronized set(value) {
            if (value != enabled) sessions.value = if (value) Session() else null
        }

    // Switch the subscription itself, rather than leaving private text queued for slow collectors
    // in one permanent SharedFlow. collectLatest cancels the old buffer on disable/restart.
    @OptIn(InternalCoroutinesApi::class)
    val events: SharedFlow<TalkDebugEvent> = object : SharedFlow<TalkDebugEvent> {
        override val replayCache: List<TalkDebugEvent> get() = emptyList()
        override suspend fun collect(collector: FlowCollector<TalkDebugEvent>): Nothing {
            sessions.collectLatest { session ->
                session?.events?.collect { event ->
                    if (sessions.value === session) collector.emit(event)
                }
            }
            error("Debug event stream completed")
        }
    }

    internal fun emit(event: TalkDebugEvent) { sessions.value?.events?.tryEmit(event) }
    internal fun runClosed(runId: String) { emit(TalkDebugEvent.RunClosed(runId)) }

    @Synchronized internal fun level(rms: Double, atMs: Long, monotonicMs: Long = System.nanoTime() / 1_000_000) {
        val session = sessions.value ?: return
        val last = session.lastLevelMonotonicMs
        if (last == null || monotonicMs - last >= 200) {
            session.lastLevelMonotonicMs = monotonicMs
            session.events.tryEmit(TalkDebugEvent.Level(rms, atMs))
        }
    }

    internal fun blockClosed(blockId: String, block: ConversationBlock, deferred: Boolean = false): DebugBlockTrace? {
        val session = sessions.value ?: return null
        val token = session.token
        return DebugBlockTrace(blockId, onStart = { runId ->
            sessions.value?.takeIf { it.token === token }?.events?.tryEmit(TalkDebugEvent.BlockClosed(
                blockId, block.reason, block.source, block.lines.map { it.text }, block.endMs - block.startMs, runId))
        }) { event ->
            sessions.value?.takeIf { it.token === token }?.events?.tryEmit(event)
        }.also { if (!deferred) it.start(null) }
    }
}

/** Deferred close payload is retired on start; all completion paths share the decision latch. */
internal class DebugBlockTrace(
    val blockId: String, private var onStart: ((String?) -> Unit)? = null,
    private val emit: (TalkDebugEvent.Decision) -> Unit,
) {
    var runId: String? = null
    fun start(runId: String?) {
        this.runId = runId
        onStart?.invoke(runId)
        onStart = null
    }
    private val completed = AtomicBoolean()
    fun finish(
        outcome: DebugOutcome = DebugOutcome.SINK_REJECTED, keepProbability: Double? = null,
        kind: String? = null, policyReasons: List<String> = emptyList(), judgeMs: Long? = null,
        savedCountDelta: Int = 0,
    ) {
        if (completed.compareAndSet(false, true)) {
            start(runId)
            emit(TalkDebugEvent.Decision(blockId, outcome, keepProbability, kind, policyReasons, judgeMs, savedCountDelta, runId))
        }
    }
}
