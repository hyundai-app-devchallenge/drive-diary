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

internal fun geminiEnvelope(answer: String, model: String = SummaryDefaults.GEMINI_MODEL): String = JSONObject()
    .put("modelVersion", model)
    .put("candidates", JSONArray().put(JSONObject().put("content", JSONObject()
        .put("parts", JSONArray().put(JSONObject().put("text", answer)))))).toString()
