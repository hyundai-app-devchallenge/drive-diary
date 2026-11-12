package com.dongbantest.app.data.talk

import com.dongbantest.app.data.repository.RecordingConsentStore
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.domain.model.SummaryStatus
import com.drivediary.talkrec.summary.PendingSummary
import com.drivediary.talkrec.summary.SummaryResult
import com.drivediary.talkrec.summary.SummaryTarget

/** Export only the saved transcript; trip IDs and all other metadata stay in the app. */
class RepositorySummaryTarget(
    private val repository: TripRepository,
    private val consent: RecordingConsentStore,
) : SummaryTarget {
    override suspend fun pending(): List<PendingSummary> = repository.listPendingTalkSummaries()
        .map { PendingSummary(it.id, it.transcript.orEmpty()) }

    override suspend fun canSend(id: Long): Boolean {
        val memory = repository.getPendingTalkSummary(id) ?: return false
        if (!consent.getSendToAi(memory.tripId)) {
            repository.updateTalkSummary(id, SummaryStatus.SKIPPED)
            return false
        }
        return true
    }

    override suspend fun save(id: Long, result: SummaryResult, model: String) {
        if (canSend(id)) repository.updateTalkSummary(id, SummaryStatus.DONE,
            title = result.title, tags = result.tags, kind = result.kind, model = model)
    }

    override suspend fun fail(id: Long) {
        if (canSend(id)) repository.updateTalkSummary(id, SummaryStatus.FAILED)
    }
}
