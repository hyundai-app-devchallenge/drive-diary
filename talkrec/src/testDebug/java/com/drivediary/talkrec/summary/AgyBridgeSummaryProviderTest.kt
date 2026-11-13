package com.drivediary.talkrec.summary

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgyBridgeSummaryProviderTest {
    private val prompt = summaryPrompt()
    private val validator = SummaryValidator(prompt.categories)
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
        assertEquals(AgySummaryDefaults.MODEL, json.getString("model"))
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

    @Test fun relayUrlsRejectInjection() {
        for (url in listOf("http://example.com", "http://10.0.2.2.evil", "http://10.0.2.2@evil", "https://example.com?key=private",
            "https://user:pass@example.com", "file:///private", "http://10.0.2.2:0")) {
            try { checkedRelayBase(url); fail() } catch (e: SummaryException) { assertEquals(SummaryErrorCode.INVALID_SETTINGS, e.code) }
        }
        assertEquals("http://10.0.2.2:8771", checkedRelayBase("http://10.0.2.2:8771/"))
    }

    @Test fun storyRelayMapsEnvelopeAndKeepsDebugSettings() = runBlocking {
        val selected = AgyBridgeSummaryProvider(validator, http = { request ->
            assertTrue(request.url.endsWith("/story-split"))
            assertEquals(360_000, request.readTimeoutMs)
            assertEquals(listOf("first", "second"), JSONObject(request.body).getJSONArray("segments").let { a ->
                List(a.length()) { a.getString(it) } })
            SummaryHttpResponse(200, JSONObject(validStoryJson()).put("model", "relay-model").put("elapsed_ms", 1).apply {
                getJSONArray("stories").getJSONObject(0).put("tags", JSONArray().put("ignored"))
            }.toString())
        })
        assertEquals("종이배 이야기", selected.storySplit(listOf("first", "second")).stories.single().title)
        assertEquals("relay-model", selected.modelName)
        val store = SummarySettingsStore(memoryPreferences(mutableMapOf("provider" to "AGY"))) { summaryPrompt() }
        assertEquals(SummaryProviderType.AGY, store.settings.value.provider)
        store.setRelayBaseUrl("http://10.0.2.2:8772/")
        assertEquals("http://10.0.2.2:8772", store.relayBaseUrl())
        assertTrue(store.createProvider() is AgyBridgeSummaryProvider)
    }
    private suspend fun expectCode(code: SummaryErrorCode, call: suspend () -> Unit) {
        try { call(); fail() } catch (e: SummaryException) {
            assertEquals(code, e.code)
            assertEquals(code.name.lowercase(), e.message)
            assertNull(e.cause)
        }
    }
}
