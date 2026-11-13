package com.dongbantest.app.data.talk

import com.dongbantest.app.data.repository.RecordingConsentStore
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.domain.model.SummaryStatus
import com.drivediary.talkrec.summary.PendingSummary
import com.drivediary.talkrec.summary.SummaryResult
import com.drivediary.talkrec.summary.SummaryTarget
import com.drivediary.talkrec.summary.StorySplitResult
import com.drivediary.talkrec.summary.SummaryClip
import com.drivediary.talkrec.summary.groupSummaryClips
import com.drivediary.talkrec.TalkRec
import com.drivediary.talkrec.capture.CaptureStatus

/** Export only the saved transcript; trip IDs and all other metadata stay in the app. */
class RepositorySummaryTarget(
    private val repository: TripRepository,
    private val consent: RecordingConsentStore,
) : SummaryTarget {
    override suspend fun pending(): List<PendingSummary> = groupSummaryClips(repository.listPendingTalkSummaries().map {
        SummaryClip(it.id, it.transcript.orEmpty(), it.tripId, it.createdAt, it.talk?.blockStartMs ?: it.createdAt,
            it.talk?.runId, it.talk?.runClosed == true)
    }, System.currentTimeMillis()) { tripId ->
        TalkRec.state.value.let { it.tripId == tripId && it.status == CaptureStatus.On }
    }

    override suspend fun canSend(item: PendingSummary): Boolean {
        if (item.runId == null) return canSend(item.id)
        val rows = item.ids.map { repository.getPendingTalkSummary(it) ?: return false }
        if (rows.any { !consent.getSendToAi(it.tripId) }) {
            repository.finishTalkRun(item, SummaryStatus.SKIPPED)
            return false
        }
        return true
    }

    override suspend fun saveStories(item: PendingSummary, result: StorySplitResult, model: String) {
        if (canSend(item)) repository.finishTalkRun(item, SummaryStatus.DONE, result, model)
    }

    override suspend fun fail(item: PendingSummary) {
        if (item.runId == null) fail(item.id)
        else if (canSend(item)) repository.finishTalkRun(item, SummaryStatus.FAILED)
    }

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
