package com.drivediary.talkrec.summary

import org.json.JSONArray
import org.json.JSONObject

data class SplitStory(
    val segments: List<Int>, val title: String, val category: String, val keywords: List<String>,
    val kind: String, val keep: Boolean, val reason: String,
) {
    val tags: List<String> get() = listOf(category).filter { it.isNotEmpty() } + keywords
    override fun toString() = "SplitStory(segments=$segments, kind=$kind, keep=$keep)"
}

// Android's org.json has keys() but no keySet().
private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

class StorySplitResult(val stories: List<SplitStory>) {
    override fun toString() = "StorySplitResult(count=${stories.size})"
}

/** Mirrors summary_relay.validate_split_output, including exact keys and integer indices. */
class StorySplitValidator(categories: List<String>) {
    private val fields = SummaryValidator(categories)

    fun parse(raw: String, segmentCount: Int): StorySplitResult {
        try {
            val text = raw.trim { it.isWhitespace() || it == '\u0085' }
            val unfenced = Regex("```(?:json)?\\s*\\n?(.*?)\\s*```",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).matchEntire(text)?.groupValues?.get(1) ?: text
            val root = summaryJsonObject(unfenced)
            if (root.keyNames() != setOf("stories")) throw InvalidSummaryOutput()
            val array = root.getJSONArray("stories")
            val stories = (0 until array.length()).map { index ->
                val story = array.getJSONObject(index)
                if (story.keyNames() != KEYS) throw InvalidSummaryOutput()
                val segments = story.getJSONArray("segments")
                SplitStory((0 until segments.length()).map {
                    val number = segments.get(it)
                    if (number !is Int && number !is Long) throw InvalidSummaryOutput()
                    val long = (number as Number).toLong()
                    if (long !in 1..12) throw InvalidSummaryOutput()
                    long.toInt()
                }, story.get("title") as? String ?: throw InvalidSummaryOutput(),
                    story.get("category") as? String ?: throw InvalidSummaryOutput(),
                    story.getJSONArray("keywords").let { words -> List(words.length()) {
                        words.get(it) as? String ?: throw InvalidSummaryOutput()
                    } }, story.get("kind") as? String ?: throw InvalidSummaryOutput(),
                    story.get("keep") as? Boolean ?: throw InvalidSummaryOutput(),
                    story.get("reason") as? String ?: throw InvalidSummaryOutput())
            }
            return validate(StorySplitResult(stories), segmentCount)
        } catch (_: Exception) { throw InvalidSummaryOutput() }
    }

    fun validate(result: StorySplitResult, segmentCount: Int): StorySplitResult {
        if (segmentCount !in 1..12 || result.stories.isEmpty() ||
            result.stories.any { it.segments.isEmpty() } ||
            result.stories.flatMap { it.segments } != (1..segmentCount).toList()) throw InvalidSummaryOutput()
        return StorySplitResult(result.stories.map { story ->
            if (story.kind !in setOf("story", "moment", "none") || story.reason !in REASONS ||
                story.keep != story.reason.isEmpty() ||
                (story.kind == "none" && (story.keep || story.reason != "no_content"))) throw InvalidSummaryOutput()
            val none = story.kind == "none"
            val checked = fields.validate(SummaryResult(
                if (none && story.title.isEmpty()) "검증" else story.title,
                if (none && story.category.isEmpty()) fields.allowedCategories.first() else story.category,
                story.keywords, if (none) "moment" else story.kind))
            story.copy(keywords = checked.keywords)
        })
    }

    companion object {
        val REASONS = listOf("", "not_in_car", "not_family", "negative", "procedure", "no_content")
        private val KEYS = setOf("segments", "title", "category", "keywords", "kind", "keep", "reason")
    }
}

internal fun checkedSegments(segments: List<String>) {
    if (segments.size !in 1..12 || segments.any { it.isBlank() }) throw SummaryException(SummaryErrorCode.INVALID_OUTPUT)
}

internal suspend fun validatedStorySplit(validator: StorySplitValidator, count: Int,
    request: suspend () -> StorySplitResult): StorySplitResult {
    repeat(2) { attempt ->
        try { return validator.validate(request(), count) }
        catch (_: InvalidSummaryOutput) {
            if (attempt == 1) throw SummaryException(SummaryErrorCode.INVALID_OUTPUT)
        }
    }
    error("unreachable")
}

internal fun storySchema(categories: List<String>): JSONObject {
    val properties = JSONObject()
        .put("segments", JSONObject().put("type", "array").put("minItems", 1)
            .put("items", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 12)))
        .put("title", JSONObject().put("type", "string").put("maxLength", 25))
        .put("category", JSONObject().put("type", "string").put("enum", JSONArray(categories + "")))
        .put("keywords", JSONObject().put("type", "array").put("maxItems", 2)
            .put("items", JSONObject().put("type", "string").put("pattern", "^[가-힣A-Za-z0-9·]{1,6}$")))
        .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(listOf("story", "moment", "none"))))
        .put("keep", JSONObject().put("type", "boolean"))
        .put("reason", JSONObject().put("type", "string").put("enum", JSONArray(StorySplitValidator.REASONS)))
    val item = JSONObject().put("type", "object").put("properties", properties)
        .put("required", JSONArray(listOf("segments", "title", "category", "keywords", "kind", "keep", "reason")))
        .put("additionalProperties", false)
    return JSONObject().put("type", "object").put("additionalProperties", false)
        .put("required", JSONArray(listOf("stories"))).put("properties", JSONObject().put("stories",
            JSONObject().put("type", "array").put("minItems", 1).put("items", item)))
}
