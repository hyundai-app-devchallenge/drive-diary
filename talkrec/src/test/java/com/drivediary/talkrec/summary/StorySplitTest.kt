package com.drivediary.talkrec.summary

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StorySplitTest {
    private val prompt = SummaryPrompt(File(System.getProperty("talkrec.assetsDir"), "prompts/story_split.md").readText())
    private val validator = StorySplitValidator(prompt.categories)
    private fun json(indices: String = "[1,2]", kind: String = "story", keep: String = "true", reason: String = "") =
        """{"stories":[{"segments":$indices,"title":"종이배 이야기","category":"놀이","keywords":[" 종이배 "],"kind":"$kind","keep":$keep,"reason":"$reason"}]}"""

    @Test fun validatesExactOrderedPartitionAndKeepContract() {
        assertEquals(listOf("종이배"), validator.parse(json(), 2).stories.single().keywords)
        for (bad in listOf(json("[1]"), json("[2,1]"), json("[1,1]"), json("[1,3]"), json("[1.0,2]"),
            json("[true,2]"), json("[]"), json(keep = "false"), json(reason = "negative"),
            json(kind = "none"), json(keep = "false", reason = "unknown"),
            json().replace("놀이", "잘못된 분류"), json().replace(" 종이배 ", "내부 공백"),
            json().replace("\" 종이배 \"", "\"배\",\"배\""), json().replace("\"stories\":", "\"extra\":0,\"stories\":"))) {
            try { validator.parse(bad, 2); fail("must reject") } catch (_: InvalidSummaryOutput) { }
        }
        val none = json(kind = "none", keep = "false", reason = "no_content").replace("종이배 이야기", "").replace("놀이", "")
        assertFalse(validator.parse(none, 2).stories.single().keep)
        assertFalse(validator.parse(json(keep = "false", reason = "procedure"), 2).stories.single().keep)
    }

    @Test fun groupsRunsAndRecoversOnlyInactiveOldOpenRuns() {
        fun clip(id: Long, run: String?, closed: Boolean = false, trip: Long = 1, saved: Long = 0) =
            SummaryClip(id, "clip $id", trip, saved, id * 100, run, closed)
        val clips = listOf(clip(2, "a"), clip(1, "a", true), clip(3, null), clip(4, "b"), clip(5, "c", trip = 2), clip(6, "d", saved = 100_000))
        val units = groupSummaryClips(clips, 200_000) { it == 2L }
        assertEquals(listOf(1L, 2L), units[0].ids); assertTrue(units[0].ready)
        assertNull(units[1].runId); assertTrue(units[1].ready)
        assertTrue(units[2].ready); assertFalse(units[3].ready); assertFalse(units[4].ready)
    }

    @Test fun providersMapNumberedSegmentsSchemaRelayEnvelopeAndFake() = runBlocking {
        val requests = mutableListOf<SummaryHttpRequest>()
        val http = SummaryHttpClient { request -> requests += request
            SummaryHttpResponse(200, JSONObject().put("candidates", org.json.JSONArray().put(JSONObject()
                .put("content", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", json())))))).toString()) }
        val gemini = GeminiSummaryProvider(prompt, "key", http = http, storyPrompt = prompt)
        assertEquals(2, gemini.storySplit(listOf("first", "second")).stories.single().segments.size)
        val body = JSONObject(requests.single().body)
        assertTrue(body.getJSONArray("contents").toString().contains("[2]"))
        assertTrue(body.getJSONObject("generationConfig").getJSONObject("responseJsonSchema").getJSONObject("properties").has("stories"))
        val relay = AgyBridgeSummaryProvider(SummaryValidator(prompt.categories), http = SummaryHttpClient { request ->
            assertTrue(request.url.endsWith("/story-split"))
            assertEquals("first", JSONObject(request.body).getJSONArray("segments").getString(0))
            SummaryHttpResponse(200, JSONObject(json()).put("model", "relay-model").put("elapsed_ms", 1).apply {
                getJSONArray("stories").getJSONObject(0).put("tags", org.json.JSONArray().put("ignored"))
            }.toString())
        })
        assertEquals("종이배 이야기", relay.storySplit(listOf("first", "second")).stories.single().title)
        assertEquals("relay-model", relay.modelName)
        assertTrue(FakeSummaryProvider(SummaryValidator(prompt.categories)).storySplit(listOf("a", "b")).stories.single().keep)
    }

    @Test fun processorUsesOneRunCallHonorsCeilingConsentAndInvalidRetry() = runBlocking {
        val unit = PendingSummary(1, "first", listOf(1, 2), listOf("first", "second"), "run")
        var consent = true; var ready = true; var calls = 0; var saved = 0; var failed = 0
        val target = object : SummaryTarget {
            override suspend fun pending() = if (ready) listOf(unit) else listOf(PendingSummary(3, "open", runId = "open", ready = false))
            override suspend fun canSend(id: Long) = consent
            override suspend fun save(id: Long, result: SummaryResult, model: String) = error("legacy path")
            override suspend fun fail(id: Long) { failed++ }
            override suspend fun saveStories(item: PendingSummary, result: StorySplitResult, model: String) { saved++ }
        }
        val provider = object : SummaryProvider {
            override val modelName = "fake"
            override suspend fun summarize(transcript: String): SummaryResult = error("legacy path")
            override suspend fun storySplit(segments: List<String>): StorySplitResult {
                calls++; assertEquals(listOf("first", "second"), segments)
                if (calls == 1) throw InvalidSummaryOutput()
                return FakeSummaryProvider(SummaryValidator(prompt.categories)).storySplit(segments)
            }
        }
        val processor = SummaryProcessor(target, SummaryValidator(prompt.categories)) { provider }
        processor.run(0, 1); assertEquals(0, calls) // never send a prefix of a run
        consent = false; processor.run(0); assertEquals(0, calls)
        consent = true; processor.run(0); assertEquals(2, calls); assertEquals(1, saved); assertEquals(0, failed)
        ready = false; assertEquals(SummaryRunResult.DEFERRED, processor.run(4))
    }

    @Test fun deletionDuringRunSendSchedulesSurvivingPendingMembersAgain() = runBlocking {
        var rows = listOf(PendingSummary(1, "first", listOf(1, 2), listOf("first", "second"), "run"))
        val target = object : SummaryTarget {
            override suspend fun pending() = rows
            override suspend fun canSend(id: Long) = true
            override suspend fun save(id: Long, result: SummaryResult, model: String) = error("legacy path")
            override suspend fun fail(id: Long) = error("must not fail survivors")
            override suspend fun saveStories(item: PendingSummary, result: StorySplitResult, model: String) {
                // A concurrent delete caused the atomic store to reject the stale two-clip response.
                rows = listOf(PendingSummary(1, "first", runId = "run"))
            }
        }
        val processor = SummaryProcessor(target, SummaryValidator(prompt.categories)) { FakeSummaryProvider(SummaryValidator(prompt.categories)) }
        assertEquals(SummaryRunResult.DEFERRED, processor.run(0))
    }
}
