package com.drivediary.talkrec.ask

import java.util.Calendar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionContextTest {
    @Test fun coarseArea() {
        assertEquals("경기 성남시", QuestionContext.coarseArea("경기 성남시 수정구 시흥동 123"))
        assertEquals("수정구", QuestionContext.coarseArea("수정구 시흥동"))
        assertNull(QuestionContext.coarseArea(null))
        assertNull(QuestionContext.coarseArea("  "))
    }

    @Test fun roundedMinutes() {
        assertNull(QuestionContext.roundedMinutes(null))
        assertNull(QuestionContext.roundedMinutes(0))
        assertEquals(10, QuestionContext.roundedMinutes(120))
        assertEquals(20, QuestionContext.roundedMinutes(23 * 60))
        assertEquals(30, QuestionContext.roundedMinutes(26 * 60))
        assertEquals(60, QuestionContext.roundedMinutes(3600))
    }

    @Test fun timeOfDay() {
        assertEquals("새벽", QuestionContext.timeOfDay(3))
        assertEquals("아침", QuestionContext.timeOfDay(8))
        assertEquals("오전", QuestionContext.timeOfDay(11))
        assertEquals("점심", QuestionContext.timeOfDay(12))
        assertEquals("오후", QuestionContext.timeOfDay(15))
        assertEquals("저녁", QuestionContext.timeOfDay(19))
        assertEquals("밤", QuestionContext.timeOfDay(23))
    }

    @Test fun weekday() {
        val saturday = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 14) }
        assertEquals("토요일", QuestionContext.weekday(saturday))
    }

    @Test fun toJsonHasNoCoordinates() {
        val json = QuestionContext("평창", "강원 평창군", 60, "오후", "토요일", "맑음, 10도", null,
            previousQuestions = listOf("a?", "b?")).toJson()
        val obj = JSONObject(json)
        val keys = obj.keys().asSequence().toList()
        assertTrue(keys.none { it in setOf("lat", "lon", "lng", "latitude", "longitude") })
        assertFalse(json.contains("latitude") || json.contains("longitude"))
        assertEquals("가족", obj.getString("relation"))
        assertEquals(2, obj.getJSONArray("previous_questions").length())
        assertTrue(obj.isNull("weather_destination"))
    }

    @Test fun previousQuestionsLimited() {
        val ctx = QuestionContext(null, null, null, "밤", "월요일", null, null, previousQuestions = (1..9).map { "q$it?" })
        assertEquals(QuestionContext.MAX_PREVIOUS, JSONObject(ctx.toJson()).getJSONArray("previous_questions").length())
    }
}
