package com.dongbantest.app.ui.v3connect

import com.dongbantest.app.domain.model.*
import com.dongbantest.app.viewmodel.TripUiState
import org.junit.Assert.*
import org.junit.Test

class StoryMapperTest {
    private val trip = Trip(1, "여행", "바다", emptyList(), 0, null, null, null, null, false)
    private fun clip(id: Long, run: String? = "run", story: String? = "run:0", keep: Boolean? = true,
        status: SummaryStatus = SummaryStatus.DONE, start: Long = id * 90_000) = Memory(
        id, 1, MemoryType.VOICE, start + 90_000, filePath = "/clip$id", durationMs = 90_000,
        talk = TalkMeta(id, TalkSource.AUTO, start, start + 90_000, 0.99, "moment", "종이배 이야기",
            listOf("놀이", "종이배"), status, runId = run, storyId = story, storyKind = "story", storyKeep = keep))

    @Test fun oneCardPerStoryAggregatesDurationPathsAndUsesStoryStartAndKind() {
        val question = Memory(20, 1, MemoryType.QUESTION, 80_000, text = "질문")
        val afterStart = Memory(21, 1, MemoryType.QUESTION, 300_000, text = "나중 질문")
        val data = TripWithMemories(trip, listOf(clip(2), question, clip(1), afterStart))
        val row = data.talks().single()
        assertEquals(listOf("/clip1", "/clip2"), row.paths)
        assertEquals(2, row.talk.clipCount); assertEquals(90_000L, row.startMs)
        assertEquals("3:00", row.talk.audio); assertTrue(row.talk.fromQuestion)
        assertEquals("story", row.talk.kind); assertEquals("놀이", row.talk.category)
        assertFalse(TripWithMemories(trip, listOf(clip(1), afterStart)).talks().single().talk.fromQuestion)
    }

    @Test fun hiddenStoriesStayInDiaryAndDisappearFromDrivingWhilePendingAndLegacyWork() {
        val data = TripWithMemories(trip, listOf(clip(1), clip(2),
            clip(3, story = "run:1", keep = false), clip(4, story = "run:1", keep = false),
            clip(5, run = "open", story = null, keep = null, status = SummaryStatus.PENDING),
            clip(6, run = null, story = null), clip(7, run = null, story = null)))
        val rows = data.talks()
        assertEquals(5, rows.size); assertEquals(1, rows.count { it.talk.hidden })
        assertEquals(5, data.toRecord().talks.size)
        val asked = TripUiState(data = data).toDriving(1_000_000).asked
        assertEquals(4, asked.size)
        assertEquals(1, asked.count { it.title == "이야기 정리 중" })
        assertTrue(rows.filter { it.talk.id.startsWith("clip:") }.all { it.talk.clipCount == 1 })
    }
}
