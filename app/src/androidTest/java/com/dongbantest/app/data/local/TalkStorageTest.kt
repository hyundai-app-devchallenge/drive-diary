package com.dongbantest.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TalkStorageTest {
    @Test fun transactionRelationSummaryAndRollingCap(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DongbanDatabase::class.java).build()
        try {
            val dao = db.tripDao()
            fun trip() = TripEntity(title = "test", destination = "test", companions = "", startedAt = 0,
                endedAt = null, startOdometer = null, endOdometer = null, odometerSource = null, isSample = false)
            val tripId = dao.insertTrip(trip())
            val otherTripId = dao.insertTrip(trip())
            suspend fun save(time: Long, source: String = "AUTO", owner: Long = tripId): Long = dao.insertTalkMemory(
                MemoryEntity(tripId = owner, type = "VOICE", createdAt = time, text = null,
                    filePath = "/test/$time.m4a", transcript = "synthetic\nlines", durationMs = 1000),
                TalkMetaEntity(0, source, time - 1000, time, null, null, summaryStatus = "PENDING"),
            )
            val id = save(4000)
            save(1000) // exact excluded lower bound
            save(5000, owner = otherTripId) // global, not per trip
            save(5001) // future saves excluded
            save(4500, source = "MANUAL")
            assertEquals(2000L, dao.savedAutoDurationMs(1000, 5000))
            val loaded = dao.observeMemoriesWithTalk(tripId).first().first { it.memory.id == id }
            assertEquals("synthetic\nlines", loaded.memory.transcript)
            assertNull(loaded.talk!!.keepScore); assertNull(loaded.talk.kind)
            assertEquals(5, dao.pendingTalkMemories().size)
            dao.updateTalkSummary(id, "synthetic title", "[\"test\"]", "moment", "DONE", "fake")
            assertEquals(4, dao.pendingTalkMemories().size)
            assertEquals("fake", dao.observeMemoriesWithTalk(tripId).first().first { it.memory.id == id }.talk!!.summaryModel)

            val runIds = (0..1).map { n -> dao.insertTalkMemory(
                MemoryEntity(tripId = tripId, type = "VOICE", createdAt = 8000L + n, text = null,
                    filePath = "/test/run$n", transcript = "synthetic", durationMs = 1000),
                TalkMetaEntity(0, "AUTO", 7000L + n, 8000L + n, null, "moment",
                    summaryStatus = "PENDING", runId = "run", closeReason = "LENGTH", runClosed = false)) }
            dao.closeTalkRun(tripId, "run")
            assertTrue(dao.pendingTalkMemory(runIds[0])!!.talk!!.runClosed == true)
            val storyRows = runIds.map { StoryRow(it, "run:0", 0, "synthetic story", "[\"놀이\",\"종이배\"]",
                "story", false, "procedure", "DONE", "fake") }
            dao.saveStoryRows(storyRows + storyRows.first().copy(id = Long.MAX_VALUE))
            assertEquals("PENDING", dao.pendingTalkMemory(runIds.first())!!.talk!!.summaryStatus)
            dao.saveStoryRows(storyRows)
            val grouped = dao.observeMemoriesWithTalk(tripId).first().filter { it.memory.id in runIds }
            assertTrue(grouped.all { it.talk!!.storyId == "run:0" && it.talk.storyKeep == false && it.talk.title == "synthetic story" })
            assertTrue(grouped.all { it.talk!!.kind == "moment" && it.talk.storyKind == "story" })
            runIds.forEach { dao.deleteMemory(it) }
            val mixedIds = (0..1).map { n -> dao.insertTalkMemory(
                MemoryEntity(tripId = tripId, type = "VOICE", createdAt = 9000L + n, text = null,
                    filePath = "/test/mixed$n", transcript = "synthetic", durationMs = 1000),
                TalkMetaEntity(0, "AUTO", 8000L + n, 9000L + n, null, null,
                    summaryStatus = if (n == 0) "SKIPPED" else "PENDING", runId = "mixed",
                    storyId = if (n == 0) "mixed:0" else null, storyIndex = if (n == 0) 0 else null)) }
            dao.closeTalkRun(tripId, "mixed")
            assertTrue(dao.observeMemoriesWithTalk(tripId).first().filter { it.memory.id in mixedIds }.all {
                it.talk!!.summaryStatus == "SKIPPED" && it.talk.storyId == "mixed:0" && it.talk.title == null && it.talk.storyKeep == null
            })
            mixedIds.forEach { dao.deleteMemory(it) }

            // A real failure in the SECOND insert must roll back the first insert as well.
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_meta BEFORE INSERT ON talk_meta
                BEGIN SELECT RAISE(ABORT, 'synthetic metadata failure'); END""")
            try { save(6000); fail("must fail") } catch (_: android.database.sqlite.SQLiteException) { }
            assertNull(dao.getMemoryByPath("/test/6000.m4a"))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_meta")
            dao.deleteMemory(id)
            assertEquals(4, dao.pendingTalkMemories().size)
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM talk_meta WHERE memoryId=$id").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally {
            db.close()
        }
    }
}
