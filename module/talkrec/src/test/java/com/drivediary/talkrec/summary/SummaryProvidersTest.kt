package com.drivediary.talkrec.summary

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SummaryProvidersTest {
    private val prompt = summaryPrompt()
    private val validator = SummaryValidator(prompt.categories)
    private val storyPrompt = SummaryPrompt(java.io.File(System.getProperty("talkrec.assetsDir") ?: "src/main/assets",
        "prompts/story_split.md").readText())
    private fun provider(http: SummaryHttpClient, model: String = SummaryDefaults.CLOUDFLARE_MODEL) =
        CloudflareSummaryProvider(prompt, TEST_ACCOUNT_ID, "private-test-token", model, http, storyPrompt)
    private suspend fun request(provider: SummaryProvider, story: Boolean) {
        if (story) provider.storySplit(listOf("first", "second")) else provider.summarize("first\nsecond")
    }

    @Test fun cloudflareMapsBothPromptsInputsSchemasAndBearerHeader() = runBlocking {
        for (story in listOf(false, true)) {
            var captured: SummaryHttpRequest? = null
            val selected = provider({ captured = it
                SummaryHttpResponse(200, cloudflareEnvelope(if (story) validStoryJson() else validSummaryJson())) })
            request(selected, story)
            val req = captured!!
            assertEquals("https://api.cloudflare.com/client/v4/accounts/$TEST_ACCOUNT_ID/ai/v1/chat/completions", req.url)
            assertEquals(mapOf("Authorization" to "Bearer private-test-token"), req.headers)
            assertEquals(60_000, req.readTimeoutMs)
            assertFalse(req.body.contains("private-test-token"))
            assertFalse(req.toString().contains(TEST_ACCOUNT_ID))
            assertFalse(req.toString().contains("first"))
            assertFalse(selected.toString().contains("private-test-token"))
            val body = JSONObject(req.body)
            assertEquals(SummaryDefaults.CLOUDFLARE_MODEL, body.getString("model"))
            assertEquals(1024, body.getInt("max_completion_tokens"))
            assertEquals(0.2, body.getDouble("temperature"), 0.0)
            val messages = body.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals((if (story) storyPrompt else prompt).systemInstruction, messages.getJSONObject(0).getString("content"))
            assertEquals("user", messages.getJSONObject(1).getString("role"))
            assertEquals(if (story) "[1]\nfirst\n\n[2]\nsecond" else "first\nsecond", messages.getJSONObject(1).getString("content"))
            val format = body.getJSONObject("response_format")
            assertEquals("json_schema", format.getString("type"))
            val props = format.getJSONObject("json_schema").getJSONObject("properties")
            assertTrue(props.has(if (story) "stories" else "category"))
            // Workers AI xgrammar rejects these; validators enforce them instead (T63).
            val schemaText = format.getJSONObject("json_schema").toString()
            for (banned in listOf("pattern", "minLength", "maxLength", "anyOf", "const")) assertFalse(banned, schemaText.contains("\"$banned\""))
            assertEquals(false, body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
            if (!story) {
                assertFalse(props.getJSONObject("keywords").getJSONObject("items").has("enum"))
                assertEquals(prompt.categories, props.getJSONObject("category").getJSONArray("enum").let { a -> List(a.length()) { a.getString(it) } })
            }
            assertEquals(SummaryDefaults.CLOUDFLARE_MODEL, selected.modelName)
        }
    }

    @Test fun acceptsPlainFencedAndThinkPrefixedObjectsForBothOperations() = runBlocking {
        for (story in listOf(false, true)) {
            val answer = if (story) validStoryJson() else validSummaryJson()
            for (text in listOf(answer, "```json\n$answer\n```", "<think>{\"private\":true}</think>\nHere is the result:\n$answer",
                "prose {not json}\n```json\n$answer\n``` trailing text"))
                request(provider({ SummaryHttpResponse(200, cloudflareEnvelope(text)) }), story)
        }
        val braces = "{\"title\":\"a } { \\\" b\"}"
        assertEquals(braces, firstSummaryJsonObject("prefix $braces suffix"))
        assertEquals("{}", firstSummaryJsonObject("{} ${validSummaryJson()}"))
    }

    @Test fun jsonModeRejectionFallsBackOnceAndIsRememberedAcrossInstancesAndOperations() = runBlocking {
        for ((index, error) in listOf("response_format is unsupported", "JSON mode not supported", "json_schema not supported").withIndex()) {
            val model = "@cf/test/fallback-$index"
            val requests = mutableListOf<SummaryHttpRequest>()
            val http = SummaryHttpClient {
                requests += it
                if (requests.size == 1) SummaryHttpResponse(400, JSONObject().put("success", false)
                    .put("errors", JSONArray().put(JSONObject().put("message", error))).toString())
                else SummaryHttpResponse(200, cloudflareEnvelope(if (requests.size == 3) validStoryJson() else validSummaryJson(), model))
            }
            provider(http, model).summarize("invented")
            provider(http, model).storySplit(listOf("first", "second"))
            assertEquals(3, requests.size)
            assertTrue(JSONObject(requests[0].body).has("response_format"))
            assertTrue(requests.drop(1).all { !JSONObject(it.body).has("response_format") })
            assertEquals(JSONObject(requests[0].body).apply { remove("response_format") }.toString(), JSONObject(requests[1].body).toString())
        }
    }

    @Test fun fallbackIsBoundedEvenIfSecondResponseAlsoRejectsJsonMode() = runBlocking {
        var calls = 0
        val selected = provider({ calls++; SummaryHttpResponse(422, "response_format unsupported") }, "@cf/test/bounded")
        expectCode(SummaryErrorCode.HTTP_ERROR) { selected.summarize("invented") }
        assertEquals(2, calls)
    }

    @Test fun storyFallbackAndFormatRetryHaveIndependentBounds() = runBlocking {
        var calls = 0
        val selected = provider({ request ->
            calls++
            assertEquals(calls == 1, JSONObject(request.body).has("response_format"))
            if (calls == 1) SummaryHttpResponse(422, "response_format is not supported")
            else SummaryHttpResponse(200, cloudflareEnvelope("{}"))
        }, "@cf/test/story-fallback-format")
        expectCode(SummaryErrorCode.INVALID_OUTPUT) {
            validatedStorySplit(StorySplitValidator(storyPrompt.categories), 2) { selected.storySplit(listOf("first", "second")) }
        }
        assertEquals(3, calls) // Schema request, fallback, single invalid-output re-request.
    }

    @Test fun errorsMapToSafeCodesWithoutRetryForBothOperations() = runBlocking {
        for (story in listOf(false, true)) {
            for ((status, code) in listOf(401 to SummaryErrorCode.AUTH, 403 to SummaryErrorCode.AUTH,
                429 to SummaryErrorCode.RATE_LIMIT, 500 to SummaryErrorCode.NETWORK, 502 to SummaryErrorCode.NETWORK,
                503 to SummaryErrorCode.NETWORK, 408 to SummaryErrorCode.NETWORK, 400 to SummaryErrorCode.HTTP_ERROR)) {
                var calls = 0
                val selected = provider({ calls++; SummaryHttpResponse(status, if (status == 400) "bad model" else "JSON mode: private error") })
                expectCode(code) { request(selected, story) }
                assertEquals(1, calls)
                assertEquals(code in setOf(SummaryErrorCode.NETWORK, SummaryErrorCode.RATE_LIMIT), code.retryable)
            }
        }
        expectCode(SummaryErrorCode.NETWORK) { provider({ throw SummaryException(SummaryErrorCode.TIMEOUT) }).summarize("invented") }
        expectCode(SummaryErrorCode.HTTP_ERROR) { provider({ SummaryHttpResponse(200, "{\"success\":false,\"errors\":[]}") }).summarize("invented") }
    }

    @Test fun invalidContentAndTruncationGetOnlyOneFormatRerequest() = runBlocking {
        for (story in listOf(false, true)) {
            for (content in listOf("{}", "[]", "no answer", "{\"title\":\"old\",\"tags\":[],\"kind\":\"story\"}")) {
                var calls = 0
                val selected = provider({ calls++; SummaryHttpResponse(200, cloudflareEnvelope(content)) })
                expectCode(SummaryErrorCode.INVALID_OUTPUT) {
                    if (story) validatedStorySplit(StorySplitValidator(storyPrompt.categories), 2) { selected.storySplit(listOf("first", "second")) }
                    else validatedSummary(validator) { selected.summarize("invented") }
                }
                assertEquals(2, calls)
            }
        }
        var calls = 0
        val truncated = provider({ calls++; SummaryHttpResponse(200, cloudflareEnvelope(validSummaryJson(), finishReason = "length")) })
        expectCode(SummaryErrorCode.INVALID_OUTPUT) { validatedSummary(validator) { truncated.summarize("invented") } }
        assertEquals(2, calls)
    }

    @Test fun summaryStillEnforcesCategoryAndKeywordRules() = runBlocking {
        for (answer in listOf(JSONObject(validSummaryJson()).put("category", "unknown"),
            JSONObject(validSummaryJson()).put("keywords", JSONArray(listOf("만두", "신발", "라켓"))),
            JSONObject(validSummaryJson()).put("keywords", JSONArray(listOf("가나다라마바사"))),
            JSONObject(validSummaryJson()).put("keywords", JSONArray(listOf("ABC", "abc"))),
            JSONObject(validSummaryJson()).put("keywords", JSONArray(listOf("123"))))) {
            var calls = 0
            val selected = provider({ calls++; SummaryHttpResponse(200, cloudflareEnvelope(answer.toString())) })
            expectCode(SummaryErrorCode.INVALID_OUTPUT) { validatedSummary(validator) { selected.summarize("invented") } }
            assertEquals(2, calls)
        }
        assertTrue(provider({ SummaryHttpResponse(200, cloudflareEnvelope(JSONObject(validSummaryJson())
            .put("keywords", JSONArray()).put("tags", "ignored").toString())) }).summarize("invented").keywords.isEmpty())
    }

    @Test fun settingsValidateCloudflareModelIdsAndAccountIds() {
        assertEquals(SummaryDefaults.CLOUDFLARE_MODEL, checkedModelName(SummaryDefaults.CLOUDFLARE_MODEL))
        for (model in listOf("", "model?key=private", "model\nheader", "../foo", "x".repeat(193))) {
            try { checkedModelName(model); fail() } catch (e: SummaryException) { assertEquals(SummaryErrorCode.INVALID_SETTINGS, e.code) }
        }
        for (account in listOf("", "account/path", "x".repeat(32))) {
            try { checkedCloudflareAccountId(account); fail() } catch (e: SummaryException) { assertEquals(SummaryErrorCode.INVALID_SETTINGS, e.code) }
        }
    }

    @Test fun fakeIsDeterministicAndUsesAPromptCategoryWithNoKeywords() = runBlocking {
        val fake = FakeSummaryProvider(validator)
        assertEquals(fake.summarize("first"), fake.summarize("second"))
        assertEquals(prompt.categories.first(), fake.summarize("").category)
        assertTrue(fake.summarize("").keywords.isEmpty())
    }

    private suspend fun expectCode(code: SummaryErrorCode, call: suspend () -> Unit) {
        try { call(); fail() } catch (e: SummaryException) {
            assertEquals(code, e.code)
            assertEquals(code.name.lowercase(), e.message)
            assertNull(e.cause)
        }
    }
}
