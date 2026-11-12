package com.drivediary.talkrec.summary

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SummaryProvidersTest {
    private val prompt = summaryPrompt()
    private val validator = SummaryValidator(prompt.tags)

    @Test fun geminiUsesHeaderOnlyKeyAndCorrectJsonShape() = runBlocking {
        val transcript = "지어낸 대화 첫 줄\n두 번째 줄"
        val key = "test-private-key"
        var captured: SummaryHttpRequest? = null
        val provider = GeminiSummaryProvider(prompt, key, http = { request ->
            captured = request
            SummaryHttpResponse(200, geminiEnvelope(validSummaryJson(), "gemini-3.8-flash-001"))
        })
        val result = provider.summarize(transcript)
        validator.validate(result)
        val request = captured!!
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent", request.url)
        assertEquals(mapOf("x-goog-api-key" to key), request.headers)
        assertFalse(request.url.contains(key))
        assertFalse(request.body.contains(key))
        assertFalse(request.toString().contains(key))
        assertFalse(request.toString().contains(transcript))
        assertEquals(30_000, request.readTimeoutMs)
        val json = JSONObject(request.body)
        assertEquals(setOf("systemInstruction", "contents", "generationConfig"), json.keys().asSequence().toSet())
        assertEquals(prompt.systemInstruction, json.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val user = json.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", user.getString("role"))
        assertEquals(transcript, user.getJSONArray("parts").getJSONObject(0).getString("text"))
        val config = json.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("responseMimeType"))
        val tagEnum = config.getJSONObject("responseJsonSchema").getJSONObject("properties").getJSONObject("tags")
            .getJSONObject("items").getJSONArray("enum")
        assertEquals(prompt.tags, (0 until tagEnum.length()).map { tagEnum.getString(it) })
        assertEquals("gemini-3.8-flash-001", provider.modelName)
    }

    @Test fun geminiInvalidOutputRetriesExactlyOnceThroughTheSharedRunner() = runBlocking {
        var calls = 0
        val provider = GeminiSummaryProvider(prompt, "test-key", http = {
            calls++; SummaryHttpResponse(200, geminiEnvelope("{}"))
        })
        expectCode(SummaryErrorCode.INVALID_OUTPUT) { validatedSummary(validator) { provider.summarize("invented") } }
        assertEquals(2, calls)
    }

    @Test fun geminiParsesFencesAndIgnoresThoughtParts() = runBlocking {
        val envelope = JSONObject(geminiEnvelope("```json\n${validSummaryJson()}\n```"))
        val parts = envelope.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        parts.put(JSONObject().put("thought", true).put("text", "not output"))
        val provider = GeminiSummaryProvider(prompt, "test-key", http = { SummaryHttpResponse(200, envelope.toString()) })
        assertEquals("story", provider.summarize("invented").kind)
    }

    @Test fun relaySendsOnlyTranscriptAndModelAndParsesActualModel() = runBlocking {
        var request: SummaryHttpRequest? = null
        val answer = JSONObject(validSummaryJson()).put("model", "gemini-3.8-flash-low").put("elapsed_ms", 123)
        val provider = AgyBridgeSummaryProvider(validator, http = {
            request = it; SummaryHttpResponse(200, answer.toString())
        })
        val transcript = "첫 줄\n다음 줄"
        validator.validate(provider.summarize(transcript))
        assertEquals("http://10.0.2.2:8771/summarize", request!!.url)
        assertTrue(request!!.headers.isEmpty())
        val json = JSONObject(request!!.body)
        assertEquals(setOf("transcript", "model"), json.keys().asSequence().toSet())
        assertEquals(transcript, json.getString("transcript"))
        assertEquals(SummaryDefaults.AGY_MODEL, json.getString("model"))
        assertEquals("gemini-3.8-flash-low", provider.modelName)
    }

    @Test fun relayValidationExhaustionDoesNotMultiplyServerRetries() = runBlocking {
        var calls = 0
        val provider = AgyBridgeSummaryProvider(validator, http = {
            calls++; SummaryHttpResponse(502, "{\"error\":\"invalid_output\"}")
        })
        expectCode(SummaryErrorCode.INVALID_OUTPUT) { validatedSummary(validator) { provider.summarize("invented") } }
        assertEquals(1, calls)
    }

    @Test fun relayMalformedSuccessfulResponsesHaveOneRetry() = runBlocking {
        for (answer in listOf("[]", "{}", JSONObject(validSummaryJson()).put("model", "bad/model").toString())) {
            var calls = 0
            val provider = AgyBridgeSummaryProvider(validator, http = { calls++; SummaryHttpResponse(200, answer) })
            expectCode(SummaryErrorCode.INVALID_OUTPUT) { validatedSummary(validator) { provider.summarize("invented") } }
            assertEquals(2, calls)
        }
    }

    @Test fun errorsMapToBoundedSafeCodes() = runBlocking {
        for ((status, code) in listOf(401 to SummaryErrorCode.AUTH, 429 to SummaryErrorCode.RATE_LIMIT,
            500 to SummaryErrorCode.SERVER_ERROR, 502 to SummaryErrorCode.SERVER_ERROR, 400 to SummaryErrorCode.HTTP_ERROR)) {
            val provider = AgyBridgeSummaryProvider(validator, http = { SummaryHttpResponse(status, "private provider error") })
            expectCode(code) { provider.summarize("private transcript") }
        }
    }

    @Test fun relayRejectsOversizeBodyWithoutTruncatingOrSending() = runBlocking {
        var called = false
        val provider = AgyBridgeSummaryProvider(validator, http = { called = true; error("not expected") })
        expectCode(SummaryErrorCode.REQUEST_TOO_LARGE) { provider.summarize("가".repeat(12_000)) }
        assertFalse(called)
    }

    @Test fun modelAndRelaySettingsRejectUrlInjectionOrOtherCleartextHosts() {
        for (model in listOf("", "models/foo", "model?key=private", "model\nheader", "x".repeat(129))) {
            try { checkedModelName(model); fail() } catch (e: SummaryException) { assertEquals(SummaryErrorCode.INVALID_SETTINGS, e.code) }
        }
        for (url in listOf("http://example.com", "http://10.0.2.2.evil", "http://10.0.2.2@evil", "https://example.com?key=private",
            "https://user:pass@example.com", "file:///private", "http://10.0.2.2:0")) {
            try { checkedRelayBase(url); fail() } catch (e: SummaryException) { assertEquals(SummaryErrorCode.INVALID_SETTINGS, e.code) }
        }
        assertEquals("http://10.0.2.2:8771", checkedRelayBase("http://10.0.2.2:8771/"))
    }

    @Test fun fakeIsDeterministicAndUsesThePromptTags() = runBlocking {
        val fake = FakeSummaryProvider(validator)
        assertEquals(fake.summarize("first"), fake.summarize("second"))
        assertEquals(prompt.tags.first(), fake.summarize("").tags.single())
    }

    private suspend fun expectCode(code: SummaryErrorCode, call: suspend () -> Unit) {
        try { call(); fail() } catch (e: SummaryException) {
            assertEquals(code, e.code)
            assertEquals(code.name.lowercase(), e.message)
            assertNull(e.cause)
        }
    }
}
