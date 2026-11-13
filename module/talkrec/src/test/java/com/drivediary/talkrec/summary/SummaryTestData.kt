package com.drivediary.talkrec.summary

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal fun summaryPrompt(): SummaryPrompt {
    val directory = System.getProperty("talkrec.assetsDir") ?: "src/main/assets"
    return SummaryPrompt(File(directory, "prompts/summary.md").readText(Charsets.UTF_8))
}

internal fun validSummaryJson(prompt: SummaryPrompt = summaryPrompt()): String = JSONObject()
    .put("title", "비 오던 날의 대화").put("category", prompt.categories.first())
    .put("keywords", JSONArray(listOf("만두", "신발"))).put("kind", "story").toString()

internal const val TEST_ACCOUNT_ID = "0123456789abcdef0123456789abcdef"

internal fun cloudflareEnvelope(answer: String, model: String = SummaryDefaults.CLOUDFLARE_MODEL,
    finishReason: String = "stop"): String = JSONObject().put("model", model)
    .put("choices", JSONArray().put(JSONObject().put("finish_reason", finishReason)
        .put("message", JSONObject().put("role", "assistant").put("content", answer)))).toString()

internal fun validStoryJson(): String = """{"stories":[{"segments":[1,2],"title":"종이배 이야기","category":"놀이","keywords":["종이배"],"kind":"story","keep":true,"reason":""}]}"""
