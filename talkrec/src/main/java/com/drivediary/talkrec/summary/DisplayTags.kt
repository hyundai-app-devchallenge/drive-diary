package com.drivediary.talkrec.summary

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Device-only display enrichment; never persist this list or send it to a provider.
 * Preserve category + keywords (and legacy tag arrays) before appending device tags. */
fun displayTags(
    llmTags: List<String>,
    destination: String?,
    createdAt: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<String> {
    val time = Instant.ofEpochMilli(createdAt).atZone(zoneId)
    val period = when (time.hour) {
        in 0..5 -> "새벽"
        in 6..11 -> "아침"
        in 12..17 -> "오후"
        in 18..21 -> "저녁"
        else -> "밤"
    }
    return (llmTags + listOfNotNull(destination?.trim()?.takeIf { it.isNotBlank() }) +
        listOf(time.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN), period))
        .filter { it.isNotBlank() }.distinct()
}
