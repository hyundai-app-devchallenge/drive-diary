package com.drivediary.talkrec.summary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** This boundary intentionally carries no audio, trip, passenger, destination, or location data. */
class PendingSummary(val id: Long, val transcript: String,
    val ids: List<Long> = listOf(id), val segments: List<String> = listOf(transcript),
    val runId: String? = null, val ready: Boolean = true, val tripId: Long? = null)

interface SummaryTarget {
    /** Only PENDING saved clips; SKIPPED records must never be returned. */
    suspend fun pending(): List<PendingSummary>
    /** Recheck existence, PENDING status and current consent immediately before each request. */
    suspend fun canSend(id: Long): Boolean
    /** Implementations recheck consent and update only still-PENDING rows. */
    suspend fun save(id: Long, result: SummaryResult, model: String)
    suspend fun fail(id: Long)
    suspend fun canSend(item: PendingSummary): Boolean = item.ids.all { canSend(it) }
    suspend fun saveStories(item: PendingSummary, result: StorySplitResult, model: String) {
        throw SummaryException(SummaryErrorCode.INVALID_SETTINGS)
    }
    suspend fun fail(item: PendingSummary) { item.ids.forEach { fail(it) } }
}

enum class SummaryRunResult { COMPLETE, RETRY, DEFERRED }

/** Pure worker logic, shared by the Android worker and JVM tests. attempt is zero-based. */
class SummaryProcessor(
    private val target: SummaryTarget,
    private val validator: SummaryValidator,
    private val provider: suspend () -> SummaryProvider,
) {
    suspend fun run(attempt: Int, throughMemoryId: Long = Long.MAX_VALUE): SummaryRunResult {
        var retry = false
        var deferred = false
        for (item in target.pending()) {
            if (item.ids.max() > throughMemoryId) continue
            if (!item.ready) { deferred = true; continue }
            currentCoroutineContext().ensureActive()
            if (!target.canSend(item)) continue
            if (item.segments.any { it.isBlank() }) {
                target.fail(item)
                continue
            }
            try {
                val selected = provider()
                if (item.runId != null) {
                    val result = validatedStorySplit(StorySplitValidator(validator.allowedCategories), item.segments.size) {
                        if (!target.canSend(item)) throw NoLongerPending()
                        withContext(SummaryTripContext(item.tripId)) { selected.storySplit(item.segments) }
                    }
                    target.saveStories(item, result, selected.modelName)
                    continue
                }
                val result = validatedSummary(validator) {
                    if (!target.canSend(item.id)) throw NoLongerPending()
                    withContext(SummaryTripContext(item.tripId)) { selected.summarize(item.transcript) }
                }
                target.save(item.id, result, selected.modelName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NoLongerPending) {
                // Deleted clips and revoked consent are never changed to FAILED.
            } catch (safe: SummaryException) {
                if (safe.code.retryable && attempt < MAX_ATTEMPTS - 1) retry = true
                else target.fail(item)
            }
        }
        // Retro-delete can invalidate a run snapshot during the HTTP call. Revisit its surviving
        // PENDING members instead of losing the only enqueue when the transaction rejects the result.
        if (target.pending().any { it.runId != null && it.ids.max() <= throughMemoryId }) deferred = true
        return if (retry) SummaryRunResult.RETRY else if (deferred) SummaryRunResult.DEFERRED else SummaryRunResult.COMPLETE
    }

    private class NoLongerPending : Exception()
    companion object { const val MAX_ATTEMPTS = 5 }
}
