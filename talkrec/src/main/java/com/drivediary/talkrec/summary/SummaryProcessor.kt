package com.drivediary.talkrec.summary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** This boundary intentionally carries no audio, trip, passenger, destination, or location data. */
class PendingSummary(val id: Long, val transcript: String)

interface SummaryTarget {
    /** Only PENDING saved clips; SKIPPED records must never be returned. */
    suspend fun pending(): List<PendingSummary>
    /** Recheck existence, PENDING status and current consent immediately before each request. */
    suspend fun canSend(id: Long): Boolean
    /** Implementations recheck consent and update only still-PENDING rows. */
    suspend fun save(id: Long, result: SummaryResult, model: String)
    suspend fun fail(id: Long)
}

enum class SummaryRunResult { COMPLETE, RETRY }

/** Pure worker logic, shared by the Android worker and JVM tests. attempt is zero-based. */
class SummaryProcessor(
    private val target: SummaryTarget,
    private val validator: SummaryValidator,
    private val provider: suspend () -> SummaryProvider,
) {
    suspend fun run(attempt: Int, throughMemoryId: Long = Long.MAX_VALUE): SummaryRunResult {
        var retry = false
        for (item in target.pending()) {
            if (item.id > throughMemoryId) continue
            currentCoroutineContext().ensureActive()
            if (!target.canSend(item.id)) continue
            if (item.transcript.isBlank()) {
                target.fail(item.id)
                continue
            }
            try {
                val selected = provider()
                val result = validatedSummary(validator) {
                    if (!target.canSend(item.id)) throw NoLongerPending()
                    selected.summarize(item.transcript)
                }
                target.save(item.id, result, selected.modelName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NoLongerPending) {
                // Deleted clips and revoked consent are never changed to FAILED.
            } catch (safe: SummaryException) {
                if (safe.code.retryable && attempt < MAX_ATTEMPTS - 1) retry = true
                else target.fail(item.id)
            }
        }
        return if (retry) SummaryRunResult.RETRY else SummaryRunResult.COMPLETE
    }

    private class NoLongerPending : Exception()
    companion object { const val MAX_ATTEMPTS = 5 }
}
