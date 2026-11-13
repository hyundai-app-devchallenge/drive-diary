package com.drivediary.talkrec.summary

import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DisplayTagsTest {
    @Test fun mergesDeviceTagsAtReadTimeWithoutChangingLlmList() {
        val llm = listOf("추억", "만두", "RC카")
        val at = Instant.parse("2026-11-13T01:00:00Z").toEpochMilli()
        assertEquals(listOf("추억", "만두", "RC카", "서울숲", "금요일", "아침"), displayTags(llm, " 서울숲 ", at, ZoneId.of("Asia/Seoul")))
        assertEquals(listOf("추억", "만두", "RC카"), llm)
    }

    @Test fun oldStoredFixedTagArraysStillDisplayWithoutMixedValidation() {
        val stored = JSONArray("[\"가족 이야기\",\"음식\",\"자연·풍경\"]")
        val oldTags = (0 until stored.length()).map { stored.getString(it) }
        val at = Instant.parse("2026-11-13T01:00:00Z").toEpochMilli()
        assertEquals(listOf("가족 이야기", "음식", "자연·풍경", "금요일", "아침"),
            displayTags(oldTags, "음식", at, ZoneId.of("Asia/Seoul")))
    }

    @Test fun failedSummaryStillGetsOnlyLocalTagsAndDeduplicates() {
        val at = Instant.parse("2026-11-13T13:00:00Z").toEpochMilli()
        assertEquals(listOf("금요일", "밤"), displayTags(emptyList(), null, at, ZoneId.of("Asia/Seoul")))
        assertEquals(listOf("금요일", "밤"), displayTags(listOf("금요일"), "금요일", at, ZoneId.of("Asia/Seoul")))
    }
}
