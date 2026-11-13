package com.drivediary.talkrec.summary

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SummaryProvidersTest {
    private val prompt = summaryPrompt()
    private val validator = SummaryValidator(prompt.categories)

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
        assertEquals("추억", result.category)
        assertEquals(listOf("만두", "신발"), result.keywords)
        assertEquals(listOf("추억", "만두", "신발"), result.tags)
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
        val schema = config.getJSONObject("responseJsonSchema")
        val properties = schema.getJSONObject("properties")
        val categoryEnum = properties.getJSONObject("category").getJSONArray("enum")
        assertEquals(prompt.categories, (0 until categoryEnum.length()).map { categoryEnum.getString(it) })
        val keywords = properties.getJSONObject("keywords")
        assertEquals(0, keywords.getInt("minItems"))
        assertEquals(2, keywords.getInt("maxItems"))
        assertEquals(1, keywords.getJSONObject("items").getInt("minLength"))
        assertEquals(6, keywords.getJSONObject("items").getInt("maxLength"))
        assertFalse(keywords.getJSONObject("items").has("enum"))
        val required = schema.getJSONArray("required")
        assertEquals(setOf("title", "category", "keywords", "kind"),
            (0 until required.length()).map { required.getString(it) }.toSet())
        assertFalse(properties.has("tags"))
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
            .put("keywords", JSONArray(listOf(" 만두 ", "RC카")))
            .put("tags", JSONArray(listOf("ignored", "wrong", "legacy")))
        val provider = AgyBridgeSummaryProvider(validator, http = {
            request = it; SummaryHttpResponse(200, answer.toString())
        })
        val transcript = "첫 줄\n다음 줄"
        val result = validator.validate(provider.summarize(transcript))
        assertEquals("추억", result.category)
        assertEquals(listOf("만두", "RC카"), result.keywords)
        assertEquals(listOf("추억", "만두", "RC카"), result.tags)
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

    @Test fun bothProvidersRequireMixedShapeAndApplyTheSameKeywordValidation() = runBlocking {
        val good = JSONObject(validSummaryJson())
        val invalidAnswers = listOf(
            "{\"title\":\"옛 출력\",\"tags\":[\"추억\"],\"kind\":\"story\"}",
            JSONObject(good.toString()).put("category", "unknown").toString(),
            JSONObject(good.toString()).put("keywords", JSONArray(listOf("만두", "신발", "라켓"))).toString(),
            JSONObject(good.toString()).put("keywords", JSONArray(listOf("가나다라마바사"))).toString(),
            JSONObject(good.toString()).put("keywords", JSONArray(listOf("ABC", "abc"))).toString(),
            JSONObject(good.toString()).put("keywords", JSONArray(listOf("123"))).toString(),
            JSONObject(good.toString()).put("keywords", JSONArray(listOf("학교"))).toString(),
        )
        for (relay in listOf(false, true)) {
            for (answer in invalidAnswers) {
                var calls = 0
                val provider: SummaryProvider = if (relay) AgyBridgeSummaryProvider(validator, http = {
                    calls++; SummaryHttpResponse(200, JSONObject(answer).put("model", SummaryDefaults.AGY_MODEL).toString())
                }) else GeminiSummaryProvider(prompt, "test-key", http = {
                    calls++; SummaryHttpResponse(200, geminiEnvelope(answer))
                })
                expectCode(SummaryErrorCode.INVALID_OUTPUT) { validatedSummary(validator) { provider.summarize("invented") } }
                assertEquals(2, calls)
            }
        }
    }

    @Test fun bothProvidersAcceptNoKeywordsAndIgnoreExtraTags() = runBlocking {
        val answer = JSONObject(validSummaryJson()).put("keywords", JSONArray())
            .put("tags", JSONObject().put("ignored", true))
            .put("model", SummaryDefaults.AGY_MODEL).put("elapsed_ms", 0).toString()
        val providers = listOf(
            GeminiSummaryProvider(prompt, "test-key", http = { SummaryHttpResponse(200, geminiEnvelope(answer)) }),
            AgyBridgeSummaryProvider(validator, http = { SummaryHttpResponse(200, answer) }),
        )
        for (provider in providers) {
            val result = provider.summarize("invented")
            assertTrue(result.keywords.isEmpty())
            assertEquals(listOf(result.category), result.tags)
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

    @Test fun fakeIsDeterministicAndUsesAPromptCategoryWithNoKeywords() = runBlocking {
        val fake = FakeSummaryProvider(validator)
        assertEquals(fake.summarize("first"), fake.summarize("second"))
        assertEquals(prompt.categories.first(), fake.summarize("").category)
        assertTrue(fake.summarize("").keywords.isEmpty())
        assertEquals(prompt.categories.first(), fake.summarize("").tags.single())
    }

    private suspend fun expectCode(code: SummaryErrorCode, call: suspend () -> Unit) {
        try { call(); fail() } catch (e: SummaryException) {
            assertEquals(code, e.code)
            assertEquals(code.name.lowercase(), e.message)
            assertNull(e.cause)
        }
    }
}
