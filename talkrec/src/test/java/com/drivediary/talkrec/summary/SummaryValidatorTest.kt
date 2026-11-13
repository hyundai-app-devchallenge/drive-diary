package com.drivediary.talkrec.summary

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SummaryValidatorTest {
    private val prompt = summaryPrompt()
    private val validator = SummaryValidator(prompt.categories)

    @Test fun goodJsonAndBothKinds() {
        val good = validSummaryJson(prompt)
        val result = validator.parse(good)
        assertEquals("story", result.kind)
        assertEquals("추억", result.category)
        assertEquals(listOf("만두", "신발"), result.keywords)
        assertEquals(listOf("추억", "만두", "신발"), result.tags)
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
            good.replaceFirst("{", "{\"title\":\"duplicate\","),
            "{\"extra\":" + "[".repeat(34) + "0" + "]".repeat(34) + "}",
        ).forEach { invalid(it) }
    }

    @Test fun titleBoundariesAndNewlines() {
        for (title in listOf("", " ", "\u0085\u00a0", "가".repeat(26), "첫줄\n둘째줄", "첫줄\r둘째줄")) {
            invalid(JSONObject(validSummaryJson()).put("title", title).toString())
        }
        for (title in listOf("가", "가".repeat(25), "😀".repeat(25), "  제목  ", "첫줄\u2028둘째줄", "제목\u2029장면")) {
            assertEquals(title, validator.parse(JSONObject(validSummaryJson()).put("title", title).toString()).title)
        }
        invalid(JSONObject(validSummaryJson()).put("title", "😀".repeat(26)).toString())
        // Escaped unpaired surrogates are JSON strings but cannot encode as valid UTF-8.
        invalid(validSummaryJson().replace("비 오던 날의 대화", "\\ud800"))
        invalid(validSummaryJson().replace("비 오던 날의 대화", "\\udc00"))
    }

    @Test fun acceptsEveryCategoryAndRejectsUnknownOrUntrimmedCategory() {
        for (category in prompt.categories) {
            assertEquals(category, validator.parse(JSONObject(validSummaryJson()).put("category", category).toString()).category)
        }
        for (category in listOf("", "unknown", " 추억", "추억 ", "자연풍경")) {
            invalid(JSONObject(validSummaryJson()).put("category", category).toString())
        }
    }

    @Test fun acceptsEmptyKeywordsAndOneToSixAllowedCharacters() {
        for (keywords in listOf(emptyList(), listOf("가"), listOf("가나다라마바"), listOf("ABC123", "RC카"), listOf("가·힣", "A·z09"))) {
            val result = validator.parse(JSONObject(validSummaryJson()).put("keywords", JSONArray(keywords)).toString())
            assertEquals(keywords, result.keywords)
            assertEquals(listOf(result.category) + keywords, result.tags)
        }
    }

    @Test fun trimsKeywordsWithoutChangingOrderOrCase() {
        val raw = listOf(" \t만두\r\n", "\u0085\u00a0RC카\u3000")
        val normalized = listOf("만두", "RC카")
        val result = validator.parse(JSONObject(validSummaryJson()).put("keywords", JSONArray(raw)).toString())
        assertEquals(normalized, result.keywords)
        assertEquals(normalized, validator.validate(result.copy(keywords = raw)).keywords)
    }

    @Test fun rejectsTooManyLongEmptyWhitespaceAndDisallowedKeywords() {
        for (keywords in listOf(listOf("만두", "신발", "라켓"), listOf("가나다라마바사"),
            listOf(""), listOf(" \t\n"), listOf("두 단어"), listOf("두\t단어"), listOf("두\n단어"),
            listOf("123"), listOf("１２３"), listOf("a-b"), listOf("a_b"), listOf("이모지😀"),
            listOf("漢字"), listOf("ㄱ"), listOf("가"), listOf("é"), listOf("만두."))) {
            invalid(JSONObject(validSummaryJson()).put("keywords", JSONArray(keywords)).toString())
        }
    }

    @Test fun rejectsKeywordsEqualToAnyCategoryAndCaseInsensitiveDuplicates() {
        for (category in prompt.categories) {
            invalid(JSONObject(validSummaryJson()).put("keywords", JSONArray(listOf(category))).toString())
        }
        for (keywords in listOf(listOf("만두", "만두"), listOf("ABC", "abc"), listOf(" RC카 ", "rc카"))) {
            invalid(JSONObject(validSummaryJson()).put("keywords", JSONArray(keywords)).toString())
        }
    }

    @Test fun tagsAreDerivedAndRoundTripAsTheExistingStorageArray() {
        val result = validator.parse(JSONObject(validSummaryJson()).put("tags", JSONArray(listOf("ignored"))).toString())
        val stored = JSONArray(result.tags).toString()
        val loaded = JSONArray(stored).let { array -> (0 until array.length()).map { array.getString(it) } }
        assertEquals(listOf(result.category) + result.keywords, loaded)
        assertEquals(result, validator.parse(JSONObject().put("title", result.title).put("category", loaded.first())
            .put("keywords", JSONArray(loaded.drop(1))).put("kind", result.kind).toString()))
        assertEquals(listOf("운전·길", "라켓"), result.copy(category = "운전·길", keywords = listOf("라켓")).tags)
    }

    @Test fun rejectsMissingWrongTypesAndUnknownKind() {
        for (field in listOf("title", "category", "keywords", "kind")) {
            invalid(JSONObject(validSummaryJson()).apply { remove(field) }.toString())
            invalid(JSONObject(validSummaryJson()).put(field, JSONObject.NULL).toString())
        }
        invalid(JSONObject(validSummaryJson()).put("title", 23).toString())
        invalid(JSONObject(validSummaryJson()).put("category", JSONArray(listOf("추억"))).toString())
        invalid(JSONObject(validSummaryJson()).put("keywords", "만두").toString())
        invalid(JSONObject(validSummaryJson()).put("keywords", JSONArray().put(1)).toString())
        invalid(JSONObject(validSummaryJson()).put("keywords", JSONArray().put(JSONObject.NULL)).toString())
        invalid(JSONObject(validSummaryJson()).put("kind", "other").toString())
        invalid(JSONObject(validSummaryJson()).put("kind", 1).toString())
        invalid("{\"title\":\"옛 출력\",\"tags\":[\"추억\"],\"kind\":\"story\"}")
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

    @Test fun parsesThirteenCategoriesOnlyFromTheShippedCategorySection() {
        assertEquals(13, prompt.categories.size)
        assertEquals(listOf("추억", "아이의 하루", "학교", "놀이", "음식", "자연·풍경", "여행 계획",
            "가족 이야기", "친구 이야기", "운전·길", "노래·웃음", "동물", "계절"), prompt.categories)
        assertEquals(listOf("새 분류", "다른 분류"), SummaryPrompt.parseCategories(
            "## 제목\nignored, before\n## 분류\n안내\n\n새 분류, 다른 분류\n\n## 키워드\nignored, later"))
        assertEquals(prompt.categories, SummaryPrompt.parseCategories(prompt.systemInstruction.replace("\n", "\r\n")))
        assertEquals(listOf("a", "b"), SummaryPrompt.parseCategories("## 분류\n\u0085a\u0085, \u00a0b\u3000\n"))
        assertFalse(prompt.systemInstruction.startsWith("\uFEFF"))
        assertFalse(prompt.systemInstruction.contains('\r'))
    }

    @Test fun failsClosedOnMissingAmbiguousEmptyOrDuplicateCategoryLists() {
        for (text in listOf("no section", "## 태그\na, b\n", "### 분류\na, b\n", "## 분류\n목록 없음\n## 키워드\na, b\n",
            "## 분류\na, a\n", "## 분류\na, b\nc, d\n", "## 분류\na, \n", "## 분류\na,,b\n")) {
            try { SummaryPrompt.parseCategories(text); fail() } catch (_: IllegalStateException) { }
        }
    }

    private fun invalid(json: String) {
        try { validator.parse(json); fail("expected invalid_output") } catch (_: InvalidSummaryOutput) { }
    }
}
