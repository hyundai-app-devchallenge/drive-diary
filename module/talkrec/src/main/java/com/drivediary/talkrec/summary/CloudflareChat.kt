package com.drivediary.talkrec.summary

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Deliberately not a data class: toString must not expose the token. */
internal class CloudflareCredentials(val accountId: String, val apiToken: String, val model: String) {
    override fun toString(): String = "CloudflareCredentials(redacted)"
}

/** Answer text is the first JSON object in the completion; never logged. */
internal class CloudflareAnswer(val text: String, val model: String)

/**
 * One Workers AI chat completion (OpenAI-compatible endpoint), shared by summaries and questions.
 * Credentials and generated text are never included in exceptions.
 */
internal suspend fun cloudflareChat(
    http: SummaryHttpClient,
    accountId: String,
    apiToken: String,
    model: String,
    system: String,
    input: String,
    schema: JSONObject,
    maxTokens: Int = 1024,
    temperature: Double = 0.2,
    readTimeoutMs: Int = 60_000,
): CloudflareAnswer {
    val account = checkedCloudflareAccountId(accountId)
    val requestedModel = checkedModelName(model)
    if (apiToken.isBlank() || apiToken.any { it <= ' ' || it >= '\u007f' })
        throw SummaryException(SummaryErrorCode.KEY_UNAVAILABLE)
    val useSchema = requestedModel !in CloudflareJsonMode.unsupported
    suspend fun send(structured: Boolean): SummaryHttpResponse {
        val body = JSONObject().put("model", requestedModel)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", input)))
            .put("max_completion_tokens", maxTokens).put("temperature", temperature)
            // Gemma 4 thinks by default: 13 s -> 1.5 s with thinking off, same validated output (T63).
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        if (structured) body.put("response_format", JSONObject().put("type", "json_schema").put("json_schema", schema))
        return try {
            http.post(SummaryHttpRequest(
                "https://api.cloudflare.com/client/v4/accounts/$account/ai/v1/chat/completions",
                mapOf("Authorization" to "Bearer $apiToken"), body.toString(), readTimeoutMs = readTimeoutMs))
        } catch (safe: SummaryException) {
            if (safe.code == SummaryErrorCode.TIMEOUT) throw SummaryException(SummaryErrorCode.NETWORK)
            throw safe
        }
    }
    var response = send(useSchema)
    // This capability retry is separate from the caller's single invalid-output re-request.
    if (useSchema && rejectsJsonMode(response)) {
        CloudflareJsonMode.unsupported.add(requestedModel)
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
        val answered = try { (envelope.opt("model") as? String)?.let(::checkedModelName) ?: requestedModel }
            catch (_: SummaryException) { throw InvalidSummaryOutput() }
        return CloudflareAnswer(firstSummaryJsonObject(content), answered)
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

/** A new settings/worker provider instance must retain the process capability decision. */
private object CloudflareJsonMode {
    val unsupported: MutableSet<String> = ConcurrentHashMap.newKeySet()
}
