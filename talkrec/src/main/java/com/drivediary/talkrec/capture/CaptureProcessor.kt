package com.drivediary.talkrec.capture

import com.drivediary.talkrec.DebugBlockTrace
import com.drivediary.talkrec.DebugOutcome
import com.drivediary.talkrec.TalkRecDebug
import com.drivediary.talkrec.block.CaptureSource
import com.drivediary.talkrec.block.ConversationBlock
import com.drivediary.talkrec.judge.ConversationJudgment
import com.drivediary.talkrec.policy.AppSignals
import com.drivediary.talkrec.policy.PolicyGate
import com.drivediary.talkrec.sink.KeptBlock
import com.drivediary.talkrec.sink.TalkSink
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Durable sinks supply saved history; the local ledger then counts only in-flight reservations. */
internal class CaptureBudget(private val limitMs: Long = 600_000, private val windowMs: Long = 3_600_000) {
    class Reservation(val timeMs: Long, val durationMs: Long, var committed: Boolean = false)
    private val entries = mutableListOf<Reservation>()
    @Synchronized fun reserve(nowMs: Long, durationMs: Long, persistedMs: Long? = null): Reservation? {
        entries.removeAll { nowMs - it.timeMs >= windowMs }
        val used = if (persistedMs == null) entries.sumOf { it.durationMs }
            else persistedMs.coerceAtLeast(0) + entries.filter { !it.committed }.sumOf { it.durationMs }
        if (durationMs <= 0 || used > limitMs - durationMs) return null
        return Reservation(nowMs, durationMs).also { entries += it }
    }
    @Synchronized fun commit(reservation: Reservation) { reservation.committed = true }
    @Synchronized fun refund(reservation: Reservation) { entries.remove(reservation) }
}

/** Single ordered consumer; policy always runs before the judge, including for MANUAL. */
internal class CaptureProcessor(
    private val tripId: Long, private val policy: PolicyGate, private val sink: TalkSink,
    private val budget: CaptureBudget,
    private var judge: ((List<String>) -> ConversationJudgment)?,
    private val onJudgeFailure: () -> Unit = {},
) {
    /** Returns saved-count delta; always erases owned PCM, including rejection/cancellation. */
    suspend fun process(
        block: ConversationBlock, audio: PcmSlice, signals: AppSignals? = null,
        blockId: String = UUID.randomUUID().toString(),
        trace: DebugBlockTrace? = TalkRecDebug.blockClosed(blockId, block),
    ): Int {
        var reservation: CaptureBudget.Reservation? = null
        var committed = false
        var outcome = DebugOutcome.SINK_REJECTED
        var judgment: ConversationJudgment? = null
        var reasons = emptyList<String>()
        var judgeMs: Long? = null
        var delta = 0
        try {
            currentCoroutineContext().ensureActive()
            val result = policy.gate(block.lines.map { it.text }, signals)
            reasons = result.reasons
            if (result.retroDelete) {
                val deleted = sink.deletePreviousSavedClip(tripId, blockId)
                outcome = if (deleted) DebugOutcome.RETRO_DELETE else DebugOutcome.POLICY_BLOCK
                delta = if (deleted) -1 else 0
                return delta
            }
            if (!result.allowed) { outcome = DebugOutcome.POLICY_BLOCK; return 0 }
            // Pleos callbacks have no trustworthy per-line audio boundaries. Do not save matched command audio.
            if (result.dropLines.isNotEmpty()) { outcome = DebugOutcome.COMMAND_LINES_DROPPED; return 0 }
            if (block.lines.none { it.text.isNotBlank() } || audio.pcm.isEmpty()) { outcome = DebugOutcome.EMPTY; return 0 }
            val judgeStart = if (trace != null && judge != null) System.nanoTime() else null
            judgment = try { judge?.invoke(block.lines.map { it.text }) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { judge = null; onJudgeFailure(); null }
            catch (_: LinkageError) { judge = null; onJudgeFailure(); null }
            catch (_: OutOfMemoryError) { judge = null; onJudgeFailure(); null }
            if (judgeStart != null) judgeMs = (System.nanoTime() - judgeStart) / 1_000_000
            if (block.source == CaptureSource.AUTO && judgment?.keep == false) { outcome = DebugOutcome.JUDGE_DROP; return 0 }
            if (block.source == CaptureSource.AUTO) {
                // Count the actual padded clip duration, including pre/post roll.
                val now = System.currentTimeMillis()
                val persisted = sink.savedAutoDurationMs(now - 3_600_000, now)
                reservation = budget.reserve(now, audio.pcm.size / 32L, persisted)
                if (reservation == null) { outcome = DebugOutcome.BUDGET; return 0 }
            }
            currentCoroutineContext().ensureActive()
            committed = sink.onKeptBlock(KeptBlock(blockId, tripId, block.lines, block.startMs, block.endMs,
                judgment?.keepProbability, judgment?.kind, block.source, audio))
            if (committed) reservation?.let(budget::commit)
            outcome = if (!committed) DebugOutcome.SINK_REJECTED
                else if (judgment == null) DebugOutcome.JUDGE_FAILED else DebugOutcome.SAVED
            delta = if (committed) 1 else 0
            return delta
        } finally {
            if (!committed) reservation?.let(budget::refund)
            audio.erase()
            trace?.finish(outcome, judgment?.keepProbability, judgment?.kind?.name, reasons, judgeMs, delta)
        }
    }
}
