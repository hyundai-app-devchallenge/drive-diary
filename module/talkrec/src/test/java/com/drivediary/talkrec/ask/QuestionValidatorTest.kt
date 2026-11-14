package com.drivediary.talkrec.ask

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuestionValidatorTest {
    private fun json(q: String, hint: String = "", topic: String = "free") =
        JSONObject().put("question", q).put("hint", hint).put("topic", topic).toString()

    private fun reason(body: String, previous: List<String> = emptyList()): String {
        try { QuestionValidator.parse(body, previous, "m") } catch (e: InvalidQuestion) { return e.reason }
        fail("expected InvalidQuestion"); return ""
    }

    @Test fun valid() {
        val q = QuestionValidator.parse(json("평창에 도착하면 제일 먼저 뭐 하고 싶어?", "먹는 것", "destination"), emptyList(), "m")
        assertEquals("평창에 도착하면 제일 먼저 뭐 하고 싶어?", q.question)
        assertEquals("먹는 것", q.hint)
        assertEquals("destination", q.topic)
        assertEquals("m", q.source)
    }

    @Test fun tooLong() = assertEquals("too_long", reason(json("가".repeat(41) + "?")))

    @Test fun exactlyMaxIsFine() {
        QuestionValidator.parse(json("가".repeat(QuestionValidator.MAX_QUESTION - 1) + "?"), emptyList(), "m")
    }

    @Test fun noQuestionMark() = assertEquals("not_one_question", reason(json("오늘 어디 가는지 알고 있어")))

    @Test fun twoQuestionMarks() = assertEquals("not_one_question", reason(json("어디 가? 뭐 먹고 싶어?")))

    @Test fun forbiddenWord() = assertEquals("forbidden_word", reason(json("화면에 보이는 거 뭐야?")))

    @Test fun repeatIgnoresSpacesAndPunctuation() =
        assertEquals("repeat", reason(json("오늘 뭐 먹고 싶어?"), listOf("오늘뭐 먹고 싶어 ?")))

    @Test fun notJson() = assertEquals("json", reason("nope"))

    @Test fun longHintBecomesBlank() {
        val q = QuestionValidator.parse(json("오늘 뭐 먹고 싶어?", "가".repeat(21)), emptyList(), "m")
        assertEquals("", q.hint)
    }

    @Test fun unknownTopicBecomesFree() {
        assertEquals("free", QuestionValidator.parse(json("오늘 뭐 먹고 싶어?", topic = "weird"), emptyList(), "m").topic)
        assertTrue("free" in QuestionValidator.topics)
    }
}
