package com.drivediary.talkrec.summary

/** Local scheduling metadata only; never passed to a provider. */
class SummaryClip(val id: Long, val transcript: String, val tripId: Long, val savedAt: Long,
    val startMs: Long, val runId: String?, val runClosed: Boolean)

fun groupSummaryClips(clips: List<SummaryClip>, nowMs: Long, captureActive: (Long) -> Boolean): List<PendingSummary> =
    clips.groupBy { it.runId?.let { run -> "${it.tripId}:$run" } ?: "clip:${it.id}" }.values.map { rows ->
        val ordered = rows.sortedWith(compareBy<SummaryClip> { it.startMs }.thenBy { it.id })
        val first = ordered.first()
        val closed = rows.any { it.runClosed } ||
            (nowMs - rows.maxOf { it.savedAt } > 180_000 && !captureActive(first.tripId))
        PendingSummary(first.id, first.transcript, ordered.map { it.id }, ordered.map { it.transcript },
            first.runId, first.runId == null || closed)
    }
