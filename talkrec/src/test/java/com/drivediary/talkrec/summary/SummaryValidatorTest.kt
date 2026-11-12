package com.drivediary.talkrec.summary

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SummaryValidatorTest {
    private val prompt = summaryPrompt()
    private val validator = SummaryValidator(prompt.tags)

    @Test fun goodJsonAndBothKinds() {
        val good = validSummaryJson(prompt)
        assertEquals("story", validator.parse(good).kind)
        assertEquals("moment", validator.parse(JSONObject(good).put("kind", "moment").toString()).kind)
    }

    @Test fun stripsOnlyAnEntireCodeFence() {
        val good = validSummaryJson(prompt)
        assertEquals(validator.parse(good), validator.parse("  ```JSON\n$good\n```  "))
        assertEquals(validator.parse(good), validator.parse("```\n$good\n```"))
        invalid("Explanation\n```json\n$good\n```")
    }

    @Test fun rejectsMalformedNonObjectAndTrailingJson() {
        listOf("", "no json", "{", "[]", "null", "123", "true", validSummaryJson() + " {}")
            .forEach { invalid(it) }
    }

    @Test fun rejectsNonJsonExtensionsDuplicateKeysAndExcessiveDepth() {
        val good = validSummaryJson()
        listOf(
            good.replace("\"title\"", "title"),
            good.replace('"', '\''),
            good.dropLast(1) + ",}",
            good.replace("\"kind\":\"story\"", "\"kind\":story"),
            "{\"title\":\"one\",\"title\":\"two\",\"tags\":[\"${prompt.tags.first()}\"],\"kind\":\"story\"}",
            "{\"extra\":" + "[".repeat(34) + "0" + "]".repeat(34) + "}",
        ).forEach { invalid(it) }
    }

    @Test fun titleBoundariesAndNewlines() {
        for (title in listOf("", " ", "가".repeat(26), "첫줄\n둘째줄", "첫줄\r둘째줄", "첫줄\u2028둘째줄")) {
            invalid(JSONObject(validSummaryJson()).put("title", title).toString())
        }
        for (title in listOf("가", "가".repeat(25), "😀".repeat(25))) {
            assertEquals(title, validator.parse(JSONObject(validSummaryJson()).put("title", title).toString()).title)
        }
        invalid(JSONObject(validSummaryJson()).put("title", "😀".repeat(26)).toString())
    }

    @Test fun rejectsUnknownEmptyOrTooManyTags() {
        for (tags in listOf(emptyList(), listOf("unknown"), prompt.tags.take(4))) {
            invalid(JSONObject(validSummaryJson()).put("tags", JSONArray(tags)).toString())
        }
        assertEquals(prompt.tags.take(3), validator.parse(JSONObject(validSummaryJson())
            .put("tags", JSONArray(prompt.tags.take(3))).toString()).tags)
    }

    @Test fun rejectsMissingWrongTypesAndUnknownKind() {
        for (field in listOf("title", "tags", "kind")) {
            invalid(JSONObject(validSummaryJson()).apply { remove(field) }.toString())
        }
        invalid(JSONObject(validSummaryJson()).put("title", 23).toString())
        invalid(JSONObject(validSummaryJson()).put("tags", "추억").toString())
        invalid(JSONObject(validSummaryJson()).put("tags", JSONArray().put(1)).toString())
        invalid(JSONObject(validSummaryJson()).put("kind", "other").toString())
    }

    @Test fun errorsNeverContainPrivateOutputOrParserCauses() {
        try {
            validator.parse("{private transcript and title")
            fail()
        } catch (error: InvalidSummaryOutput) {
            assertEquals("invalid_output", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun parsesTagSectionFromTheShippedPrompt() {
        assertEquals(11, prompt.tags.size)
        assertEquals("추억", prompt.tags.first())
        assertEquals("계절", prompt.tags.last())
        assertEquals(prompt.tags.size, prompt.tags.distinct().size)
        assertEquals(listOf("새 태그", "다른 태그"), SummaryPrompt.parseTags("## 태그\n안내\n\n새 태그, 다른 태그\n\n## 종류\nignored, later"))
        assertEquals(prompt.tags, SummaryPrompt.parseTags(prompt.systemInstruction.replace("\n", "\r\n")))
    }

    @Test fun failsClosedOnMissingAmbiguousOrDuplicateTagLists() {
        for (text in listOf("no section", "## 태그\na, a\n", "## 태그\na, b\nc, d\n", "## 태그\na, \n")) {
            try { SummaryPrompt.parseTags(text); fail() } catch (_: IllegalStateException) { }
        }
    }

    private fun invalid(json: String) {
        try { validator.parse(json); fail("expected invalid_output") } catch (_: InvalidSummaryOutput) { }
    }
}
