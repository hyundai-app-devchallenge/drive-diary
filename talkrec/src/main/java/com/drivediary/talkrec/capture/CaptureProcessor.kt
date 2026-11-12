package com.drivediary.talkrec.capture

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
    suspend fun process(block: ConversationBlock, audio: PcmSlice, signals: AppSignals? = null): Int {
        var reservation: CaptureBudget.Reservation? = null
        var committed = false
        try {
            currentCoroutineContext().ensureActive()
            val id = UUID.randomUUID().toString()
            val result = policy.gate(block.lines.map { it.text }, signals)
            if (result.retroDelete) return if (sink.deletePreviousSavedClip(tripId, id)) -1 else 0
            if (!result.allowed) return 0
            // Pleos callbacks have no trustworthy per-line audio boundaries. Do not save matched command audio.
            if (result.dropLines.isNotEmpty()) return 0
            if (block.lines.none { it.text.isNotBlank() } || audio.pcm.isEmpty()) return 0
            val judgment = try { judge?.invoke(block.lines.map { it.text }) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { judge = null; onJudgeFailure(); null }
            catch (_: LinkageError) { judge = null; onJudgeFailure(); null }
            catch (_: OutOfMemoryError) { judge = null; onJudgeFailure(); null }
            if (block.source == CaptureSource.AUTO && judgment?.keep == false) return 0
            if (block.source == CaptureSource.AUTO) {
                // Count the actual padded clip duration, including pre/post roll.
                val now = System.currentTimeMillis()
                val persisted = sink.savedAutoDurationMs(now - 3_600_000, now)
                reservation = budget.reserve(now, audio.pcm.size / 32L, persisted) ?: return 0
            }
            currentCoroutineContext().ensureActive()
            committed = sink.onKeptBlock(KeptBlock(id, tripId, block.lines, block.startMs, block.endMs,
                judgment?.keepProbability, judgment?.kind, block.source, audio))
            if (committed) reservation?.let(budget::commit)
            return if (committed) 1 else 0
        } finally {
            if (!committed) reservation?.let(budget::refund)
            audio.erase()
        }
    }
}
