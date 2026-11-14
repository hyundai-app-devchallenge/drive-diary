package com.drivediary.talkrec.summary

import org.json.JSONArray
import org.json.JSONObject

/** Credentials and generated text are never included in exceptions or toString. */
class CloudflareSummaryProvider(
    private val prompt: SummaryPrompt,
    private val accountId: String,
    private val apiToken: String,
    model: String = SummaryDefaults.CLOUDFLARE_MODEL,
    private val http: SummaryHttpClient = UrlConnectionSummaryHttpClient(),
    private val storyPrompt: SummaryPrompt = prompt,
) : SummaryProvider {
    init { checkedCloudflareAccountId(accountId) }
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

    private suspend fun complete(system: String, input: String, schema: JSONObject): CloudflareAnswer =
        cloudflareChat(http, accountId, apiToken, requestedModel, system, input, schema)
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
