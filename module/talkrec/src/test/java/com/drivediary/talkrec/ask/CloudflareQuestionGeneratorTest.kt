package com.drivediary.talkrec.ask

import com.drivediary.talkrec.summary.SummaryErrorCode
import com.drivediary.talkrec.summary.SummaryException
import com.drivediary.talkrec.summary.SummaryHttpClient
import com.drivediary.talkrec.summary.SummaryHttpRequest
import com.drivediary.talkrec.summary.SummaryHttpResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class CloudflareQuestionGeneratorTest {
    private val account = "0123456789abcdef0123456789abcdef"
    private val model = "@cf/google/gemma-4-26b-a4b-it"
    private val context = QuestionContext("평창", "강원 평창군", 60, "오후", "토요일", "맑음, 10도", null)

    private fun envelope(content: String) = JSONObject().put("model", model)
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
            .put("message", JSONObject().put("role", "assistant").put("content", content)))).toString()

    private fun answer(q: String) = JSONObject().put("question", q).put("hint", "").put("topic", "destination").toString()

    private fun generator(acct: String = account, http: SummaryHttpClient) =
        CloudflareQuestionGenerator("system prompt", acct, "test-token", model, http)

    @Test fun successSendsNoCoordinates() = runBlocking {
        val requests = mutableListOf<SummaryHttpRequest>()
        val gen = generator(http = { requests += it; SummaryHttpResponse(200, envelope(answer("평창에서 제일 기대되는 게 뭐야?"))) })
        val result = gen.generate(context)
        assertEquals("평창에서 제일 기대되는 게 뭐야?", result.question)
        assertEquals(model, result.source)
        val request = requests.single()
        assertFalse(request.body.contains("latitude"))
        assertFalse(request.body.contains("longitude"))
        assertFalse(request.body.contains("test-token"))
        assertEquals(8_000, request.readTimeoutMs)
        val body = JSONObject(request.body)
        assertEquals(256, body.getInt("max_completion_tokens"))
        assertEquals("system prompt", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun invalidThenValidRetriesOnce() = runBlocking {
        var calls = 0
        val gen = generator(http = {
            calls++
            SummaryHttpResponse(200, envelope(answer(if (calls == 1) "화면 보고 말해봐?" else "오늘 뭐 먹고 싶어?")))
        })
        assertEquals("오늘 뭐 먹고 싶어?", gen.generate(context).question)
        assertEquals(2, calls)
    }

    @Test fun twoInvalidAnswersFail() = runBlocking {
        var calls = 0
        val gen = generator(http = { calls++; SummaryHttpResponse(200, envelope(answer("질문이 아니에요"))) })
        try { gen.generate(context); fail("expected InvalidQuestion") } catch (e: InvalidQuestion) {
            assertEquals("not_one_question", e.reason)
        }
        assertEquals(2, calls)
    }

    @Test fun badAccountIdRejected() = runBlocking {
        var calls = 0
        val gen = generator("not-hex", { calls++; SummaryHttpResponse(200, envelope(answer("오늘 뭐 먹고 싶어?"))) })
        try { gen.generate(context); fail("expected exception") } catch (e: SummaryException) {
            assertEquals(SummaryErrorCode.INVALID_SETTINGS, e.code)
        }
        assertEquals(0, calls)
    }
}
