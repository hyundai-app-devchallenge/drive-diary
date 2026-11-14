package com.dongbantest.app.data.repository

import com.dongbantest.app.data.local.MemoryEntity
import com.dongbantest.app.data.local.TripDao
import com.dongbantest.app.data.local.TripEntity
import com.dongbantest.app.data.local.TalkMetaEntity
import com.dongbantest.app.domain.model.SummaryStatus
import com.dongbantest.app.domain.model.TalkMeta
import com.dongbantest.app.domain.model.TalkSource
import com.drivediary.talkrec.block.CaptureSource
import com.drivediary.talkrec.capture.PcmSlice
import com.drivediary.talkrec.sink.KeptBlock
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises the real repository/file boundary with a small in-memory DAO implementation. */
class TripDiscardTest {
    @get:Rule val temp = TemporaryFolder()
    private val trips = linkedMapOf<Long, TripEntity>()
    private val memories = linkedMapOf<Long, MemoryEntity>()
    private val metadata = linkedMapOf<Long, TalkMetaEntity>()
    private var updates = 0
    private val dao = Proxy.newProxyInstance(TripDao::class.java.classLoader, arrayOf(TripDao::class.java)) { _, method, args ->
        when (method.name) {
            "getTrip" -> trips[args[0] as Long]
            "getSampleTrips" -> trips.values.filter { it.isSample }
            "observeOngoingTrip" -> flowOf(trips.values.firstOrNull { it.endedAt == null })
            "getMemories" -> memories.values.filter { it.tripId == args[0] }
            "getMemory" -> memories[args[0] as Long]
            "getMemoryByPath" -> memories.values.firstOrNull { it.filePath == args[0] }
            "deleteTrip" -> {
                val id = args[0] as Long
                trips.remove(id)
                memories.entries.removeAll { it.value.tripId == id }
                metadata.keys.retainAll(memories.keys)
                Unit
            }
            "deleteMemory" -> { memories.remove(args[0] as Long); Unit }
            "insertMemory", "insertTalkMemory" -> {
                val memory = args[0] as MemoryEntity
                check(trips.containsKey(memory.tripId)) { "Foreign key violation" }
                val id = (memories.keys.maxOrNull() ?: 0) + 1
                memories[id] = memory.copy(id = id)
                if (method.name == "insertTalkMemory") metadata[id] = (args[1] as TalkMetaEntity).copy(memoryId = id)
                id
            }
            "updateTrip" -> { updates++; val trip = args[0] as TripEntity; trips[trip.id] = trip; Unit }
            else -> error("Unexpected DAO call: ${method.name}")
        }
    } as TripDao

    private fun addTrip(id: Long) {
        trips[id] = TripEntity(id, "test", "", "", 0, null, null, null, null, false)
    }

    private fun block(id: Long) = KeptBlock("test-block", id, emptyList(), 0, 1_000,
        null, null, CaptureSource.AUTO, PcmSlice(0, 1_000, byteArrayOf()))

    @Test fun discardFencesArrivalAndLateWritersBeforeDeletingRows() = runBlocking {
        addTrip(7)
        val files = MediaFileStore(temp.root)
        val repository = TripRepository(dao, files)
        repository.beginDiscardTrip(7)
        assertFalse(repository.isTripOngoing(7))
        assertNull(repository.observeOngoingTrip().first())
        repository.reopenTrip(7)
        repository.setStartPlace(7, "test")
        repository.setEndPlace(7, "test")
        assertEquals(0, updates)
        val lateFile = File(temp.root, "late.m4a").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(0L, repository.addVoiceMemo(7, lateFile.path, 1_000))
        assertFalse(lateFile.exists())
        assertTrue(memories.isEmpty())
        repository.deleteTrip(7)
        repository.reopenTrip(7)
        assertFalse(trips.containsKey(7))
        assertEquals(0L, repository.addQuestion(7, ""))
        assertNull(repository.closeTalkRun(7, "test-run"))
    }

    @Test fun discardWhileEncodingRejectsSaveAndThenDeletesOnlyThatTripsFiles() = runBlocking {
        addTrip(7); addTrip(8)
        val files = MediaFileStore(temp.root)
        val repository = TripRepository(dao, files)
        val other = files.talkVoiceFile(8, "other").apply { writeBytes(byteArrayOf(1)) }
        repository.addVoiceMemo(8, other.path, 1_000)
        val encoded = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var output: File? = null
        val save = async {
            repository.saveTalkClip(block(7), { true }, { false }) { file ->
                output = file
                file.writeBytes(byteArrayOf(1))
                encoded.complete(Unit)
                release.await()
                1_000L
            }
        }
        encoded.await()
        repository.beginDiscardTrip(7)
        val delete = async { repository.deleteTrip(7) }
        release.complete(Unit)
        assertNull(save.await())
        delete.await()
        assertFalse(output!!.exists())
        assertTrue(other.exists())
        assertEquals(listOf(8L), memories.values.map { it.tripId })
        assertFalse(trips.containsKey(7))
        assertTrue(trips.containsKey(8))
    }

    @Test fun deleteRemovesLegacyFinalStagedAndTemporaryAudioAlongsideNewOrphans() = runBlocking {
        addTrip(7)
        val files = MediaFileStore(temp.root)
        val repository = TripRepository(dao, files)
        val voice = File(temp.root, "voice")
        val legacy = File(voice, "voice_talk_legacy.m4a").apply { writeBytes(byteArrayOf(1)) }
        repository.addVoiceMemo(7, legacy.path, 1_000)
        val staged = File(legacy.path + ".deleting").apply { writeBytes(byteArrayOf(1)) }
        val partial = File(legacy.path + "-encoder.part").apply { writeBytes(byteArrayOf(1)) }
        val orphan = files.talkVoiceFile(7, "orphan").apply { writeBytes(byteArrayOf(1)) }
        val other = files.talkVoiceFile(8, "other").apply { writeBytes(byteArrayOf(1)) }
        repository.deleteTrip(7)
        assertTrue(listOf(legacy, staged, partial, orphan).none { it.exists() })
        assertTrue(other.exists())
        assertTrue(memories.isEmpty())
    }

    @Test fun clearSamplesRemovesWavClipsAndOrphansAndPreservesUserTrips() = runBlocking {
        addTrip(7); addTrip(8)
        trips[7] = trips.getValue(7).copy(isSample = true, endedAt = 60_000)
        val files = MediaFileStore(temp.root)
        val repository = TripRepository(dao, files)
        val sample = files.sampleTalkVoiceFile(7, "sample-trip-7:0").apply { writeBytes(byteArrayOf(1)) }
        repository.addVoiceMemo(7, sample.path, 5_000)
        val orphan = files.sampleTalkVoiceFile(7, "sample-trip-7:1").apply { writeBytes(byteArrayOf(1)) }
        val staged = File(orphan.path + ".deleting").apply { writeBytes(byteArrayOf(1)) }
        val other = files.talkVoiceFile(8, "user").apply { writeBytes(byteArrayOf(1)) }
        repository.addVoiceMemo(8, other.path, 5_000)
        repository.clearSampleTrips()
        assertTrue(listOf(sample, orphan, staged).none { it.exists() })
        assertTrue(other.exists())
        assertEquals(listOf(8L), trips.keys.toList())
        assertEquals(listOf(8L), memories.values.map { it.tripId })
        repository.clearSampleTrips()
        assertTrue(other.exists())
    }

    private fun sampleMeta() = TalkMeta(
        memoryId = 0, source = TalkSource.AUTO, blockStartMs = 10_000, blockEndMs = 15_000,
        keepScore = 1.0, kind = "story", title = "예시 이야기", summaryStatus = SummaryStatus.DONE,
        runId = "sample-trip-7", runClosed = true, storyId = "sample-trip-7:0",
        storyIndex = 0, storyKind = "story", storyKeep = true,
    )

    @Test fun sampleTalkPersistsFinishedStoryAndRealDurationOnlyOnceThenClearsBoth() = runBlocking {
        addTrip(7)
        trips[7] = trips.getValue(7).copy(isSample = true, endedAt = 60_000)
        val files = MediaFileStore(temp.root)
        val repository = TripRepository(dao, files)
        var writes = 0
        val first = repository.saveSampleTalk(7, sampleMeta()) { file ->
            writes++
            file.writeBytes(byteArrayOf(1))
            5_000L
        }
        assertNotNull(first)
        assertEquals(first, repository.saveSampleTalk(7, sampleMeta()) { error("Must reuse saved clip") })
        assertEquals(1, writes)
        val memory = memories.getValue(first!!)
        val meta = metadata.getValue(first)
        assertEquals("VOICE", memory.type)
        assertEquals(15_000L, memory.createdAt)
        assertEquals(5_000L, memory.durationMs)
        assertEquals("DONE", meta.summaryStatus)
        assertEquals("sample-trip-7:0", meta.storyId)
        assertEquals(true, meta.runClosed)
        assertEquals(true, meta.storyKeep)
        assertTrue(File(memory.filePath!!).exists())
        repository.clearSampleTrips()
        assertFalse(File(memory.filePath).exists())
        assertTrue(memories.isEmpty())
        assertTrue(metadata.isEmpty())
    }

    @Test fun failedSampleAudioWriteLeavesNoFileOrMemory() = runBlocking {
        addTrip(7)
        trips[7] = trips.getValue(7).copy(isSample = true, endedAt = 60_000)
        val files = MediaFileStore(temp.root)
        val repository = TripRepository(dao, files)
        try {
            repository.saveSampleTalk(7, sampleMeta()) { file ->
                file.writeBytes(byteArrayOf(1))
                error("Synthetic write failure")
            }
            fail("Expected write failure")
        } catch (_: IllegalStateException) {
            assertTrue(memories.isEmpty())
            assertFalse(files.sampleTalkVoiceFile(7, sampleMeta().storyId!!).exists())
        }
    }
}
