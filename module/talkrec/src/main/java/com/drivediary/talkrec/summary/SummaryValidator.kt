package com.drivediary.talkrec.summary

import java.util.Locale
import org.json.JSONArray

/** One source for both the system instruction and the permitted categories. */
class SummaryPrompt(val systemInstruction: String) {
    val categories: List<String> = parseCategories(systemInstruction)
    // Source compatibility for settings callers; these are categories, never keyword enums.
    val tags: List<String> get() = categories

    companion object {
        fun parseCategories(markdown: String): List<String> {
            val section = Regex("^##[ \\t]+분류[ \\t]*\\r?\\n(.*?)(?=^##[ \\t]+|\\z)",
                setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
                .find(markdown)?.groupValues?.get(1).orEmpty()
            val lists = section.lineSequence().map { it.trim(::summaryWhitespace) }
                .filter { ',' in it }.toList()
            check(lists.size == 1) { "summary_prompt_categories" }
            val categories = lists.single().split(',').map { it.trim(::summaryWhitespace) }
            check(categories.all { it.isNotEmpty() } && categories.distinct().size == categories.size) {
                "summary_prompt_categories"
            }
            return categories
        }
    }
}

/** Never attach a JSON parser exception: it can contain generated private text. */
class InvalidSummaryOutput : Exception("invalid_output")

class SummaryValidator(val allowedCategories: List<String>) {
    init { check(allowedCategories.isNotEmpty()) { "summary_prompt_categories" } }

    fun parse(raw: String): SummaryResult {
        try {
            val stripped = raw.trim(::summaryWhitespace).let { text ->
                FENCE.matchEntire(text)?.groupValues?.get(1)?.trim(::summaryWhitespace) ?: text
            }
            val json = summaryJsonObject(stripped)
            val title = json.opt("title") as? String ?: throw InvalidSummaryOutput()
            val category = json.opt("category") as? String ?: throw InvalidSummaryOutput()
            val array = json.opt("keywords") as? JSONArray ?: throw InvalidSummaryOutput()
            val keywords = (0 until array.length()).map { array.opt(it) as? String ?: throw InvalidSummaryOutput() }
            val kind = json.opt("kind") as? String ?: throw InvalidSummaryOutput()
            return validate(SummaryResult(title, category, keywords, kind))
        } catch (_: Exception) {
            throw InvalidSummaryOutput()
        }
    }

    fun validate(result: SummaryResult): SummaryResult {
        val title = result.title
        if (title.all(::summaryWhitespace) || title.codePointCount(0, title.length) !in 1..25 ||
            title.any { it == '\n' || it == '\r' } || !validUnicode(title) ||
            result.category !in allowedCategories || result.keywords.size > 2 ||
            result.kind !in setOf("story", "moment")) {
            throw InvalidSummaryOutput()
        }
        val seen = mutableSetOf<String>()
        val keywords = result.keywords.map { raw ->
            val keyword = raw.trim(::summaryWhitespace)
            if (!KEYWORD.matches(keyword) || keyword.all { it in '0'..'9' } ||
                keyword in allowedCategories || !seen.add(keyword.lowercase(Locale.ROOT))) {
                throw InvalidSummaryOutput()
            }
            keyword
        }
        return result.copy(keywords = keywords)
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
        val KEYWORD = Regex("[가-힣A-Za-z0-9·]{1,6}")
        val FENCE = Regex("```(?:json)?\\s*\\n?(.*?)\\s*```", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}

/** Python str.strip()/isspace() also includes NEXT LINE, unlike Kotlin isWhitespace(). */
private fun summaryWhitespace(char: Char): Boolean = char.isWhitespace() || char == '\u0085'

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
