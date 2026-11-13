package com.drivediary.talkrec.summary

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Credentials and generated text are never included in exceptions or toString. */
class CloudflareSummaryProvider(
    private val prompt: SummaryPrompt,
    accountId: String,
    private val apiToken: String,
    model: String = SummaryDefaults.CLOUDFLARE_MODEL,
    private val http: SummaryHttpClient = UrlConnectionSummaryHttpClient(),
    private val storyPrompt: SummaryPrompt = prompt,
) : SummaryProvider {
    private val account = checkedCloudflareAccountId(accountId)
    private val requestedModel = checkedModelName(model)
    @Volatile override var modelName: String = requestedModel
        private set

    override suspend fun summarize(transcript: String): SummaryResult {
        val answer = complete(prompt.systemInstruction, transcript, summarySchema(prompt.categories))
        val result = SummaryValidator(prompt.categories).parse(answer.text)
        modelName = answer.model
        return result
    }

    override suspend fun storySplit(segments: List<String>): StorySplitResult {
        checkedSegments(segments)
        val numbered = segments.mapIndexed { i, text -> "[${i + 1}]\n$text" }.joinToString("\n\n")
        val answer = complete(storyPrompt.systemInstruction, numbered, storySchema(storyPrompt.categories))
        val result = StorySplitValidator(storyPrompt.categories).parse(answer.text, segments.size)
        modelName = answer.model
        return result
    }

    private class Answer(val text: String, val model: String)

    private suspend fun complete(system: String, input: String, schema: JSONObject): Answer {
        if (apiToken.isBlank() || apiToken.any { it <= ' ' || it >= '\u007f' })
            throw SummaryException(SummaryErrorCode.KEY_UNAVAILABLE)
        val useSchema = requestedModel !in modelsWithoutJsonMode
        suspend fun send(structured: Boolean): SummaryHttpResponse {
            val body = JSONObject().put("model", requestedModel)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", input)))
                .put("max_completion_tokens", 1024).put("temperature", 0.2)
                // Gemma 4 thinks by default: 13 s -> 1.5 s with thinking off, same validated output (T63).
                .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            if (structured) body.put("response_format", JSONObject().put("type", "json_schema").put("json_schema", schema))
            return try {
                http.post(SummaryHttpRequest(
                    "https://api.cloudflare.com/client/v4/accounts/$account/ai/v1/chat/completions",
                    mapOf("Authorization" to "Bearer $apiToken"), body.toString(), readTimeoutMs = 60_000))
            } catch (safe: SummaryException) {
                if (safe.code == SummaryErrorCode.TIMEOUT) throw SummaryException(SummaryErrorCode.NETWORK)
                throw safe
            }
        }
        var response = send(useSchema)
        // This capability retry is separate from the caller's single invalid-output re-request.
        if (useSchema && rejectsJsonMode(response)) {
            modelsWithoutJsonMode.add(requestedModel)
            response = send(false)
        }
        checkSummaryHttp(response, cloudflare = true)
        try {
            val envelope = summaryJsonObject(response.body)
            if (envelope.opt("success") == false) throw SummaryException(SummaryErrorCode.HTTP_ERROR)
            val choice = envelope.getJSONArray("choices").getJSONObject(0)
            // Truncated/refused completions must go through the bounded format retry.
            if (choice.opt("finish_reason") != "stop") throw InvalidSummaryOutput()
            val content = choice.getJSONObject("message").get("content") as? String ?: throw InvalidSummaryOutput()
            val model = try { (envelope.opt("model") as? String)?.let(::checkedModelName) ?: requestedModel }
                catch (_: SummaryException) { throw InvalidSummaryOutput() }
            return Answer(firstSummaryJsonObject(content), model)
        } catch (safe: SummaryException) {
            throw safe
        } catch (_: Exception) {
            throw InvalidSummaryOutput()
        }
    }

    private fun rejectsJsonMode(response: SummaryHttpResponse): Boolean {
        if (response.status !in 400..499 || response.status in setOf(401, 403, 408, 413, 429)) return false
        val error = response.body.lowercase()
        return listOf("response_format", "response format", "json_schema", "json schema", "json mode", "json-mode", "grammar").any { it in error }
    }

    private companion object {
        // A new settings/worker provider instance must retain the process capability decision.
        val modelsWithoutJsonMode = ConcurrentHashMap.newKeySet<String>()
    }
}

internal fun summarySchema(categories: List<String>): JSONObject {
    val properties = JSONObject()
        // Workers AI(xgrammar) rejects pattern together with length limits; SummaryValidator checks them.
        .put("title", JSONObject().put("type", "string"))
        .put("category", JSONObject().put("type", "string").put("enum", JSONArray(categories)))
        .put("keywords", JSONObject().put("type", "array").put("maxItems", 2)
            .put("items", JSONObject().put("type", "string")))
        .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("story", "moment"))))
    return JSONObject().put("type", "object").put("properties", properties).put("additionalProperties", false)
        .put("required", JSONArray(listOf("title", "category", "keywords", "kind")))
}
