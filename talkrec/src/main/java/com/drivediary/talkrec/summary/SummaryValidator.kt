package com.drivediary.talkrec.summary

import org.json.JSONArray

/** One source for both the system instruction and the permitted topic tags. */
class SummaryPrompt(val systemInstruction: String) {
    val tags: List<String> = parseTags(systemInstruction)

    companion object {
        fun parseTags(markdown: String): List<String> {
            val section = markdown.substringAfter("## 태그", "").substringBefore("\n## ")
            val lists = section.lineSequence().map { it.trim() }
                .filter { ',' in it && !it.startsWith("#") }.toList()
            check(lists.size == 1) { "summary_prompt_tags" }
            val tags = lists.single().split(',').map { it.trim() }
            check(tags.all { it.isNotBlank() } && tags.distinct().size == tags.size) { "summary_prompt_tags" }
            return tags
        }
    }
}

/** Never attach a JSON parser exception: it can contain generated private text. */
class InvalidSummaryOutput : Exception("invalid_output")

class SummaryValidator(val allowedTags: List<String>) {
    init { check(allowedTags.isNotEmpty()) { "summary_prompt_tags" } }

    fun parse(raw: String): SummaryResult {
        try {
            val stripped = raw.trim().let { text ->
                FENCE.matchEntire(text)?.groupValues?.get(1)?.trim() ?: text
            }
            val json = summaryJsonObject(stripped)
            val title = json.opt("title") as? String ?: throw InvalidSummaryOutput()
            val array = json.opt("tags") as? JSONArray ?: throw InvalidSummaryOutput()
            val tags = (0 until array.length()).map { array.opt(it) as? String ?: throw InvalidSummaryOutput() }
            val kind = json.opt("kind") as? String ?: throw InvalidSummaryOutput()
            return validate(SummaryResult(title, tags, kind))
        } catch (_: Exception) {
            throw InvalidSummaryOutput()
        }
    }

    fun validate(result: SummaryResult): SummaryResult {
        val title = result.title
        if (title.isBlank() || title.codePointCount(0, title.length) !in 1..25 ||
            title.any { it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' } ||
            !validUnicode(title) || result.tags.size !in 1..3 ||
            result.tags.any { it !in allowedTags } || result.kind !in setOf("story", "moment")) {
            throw InvalidSummaryOutput()
        }
        return result
    }

    private fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val c = value[index++]
            if (c.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) return false
            } else if (c.isLowSurrogate()) return false
        }
        return true
    }

    private companion object {
        val FENCE = Regex("```(?:json)?\\s*\\n?(.*?)\\s*```", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}

enum class SummaryErrorCode(val retryable: Boolean = false) {
    INVALID_OUTPUT, INVALID_SETTINGS, KEY_UNAVAILABLE, AUTH, HTTP_ERROR, REQUEST_TOO_LARGE, STORAGE,
    NETWORK(true), TIMEOUT(true), RATE_LIMIT(true), SERVER_ERROR(true),
}

/** Codes only; never propagate a provider's response, URL, key, or exception message. */
class SummaryException(val code: SummaryErrorCode) : Exception(code.name.lowercase())

internal suspend fun validatedSummary(
    validator: SummaryValidator,
    request: suspend () -> SummaryResult,
): SummaryResult {
    repeat(2) { attempt ->
        try {
            return validator.validate(request())
        } catch (_: InvalidSummaryOutput) {
            if (attempt == 1) throw SummaryException(SummaryErrorCode.INVALID_OUTPUT)
        }
    }
    error("unreachable")
}
