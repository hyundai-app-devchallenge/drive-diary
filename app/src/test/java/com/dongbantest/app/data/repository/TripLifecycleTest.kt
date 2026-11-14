package com.dongbantest.app.data.repository

import com.dongbantest.app.data.local.LocationPointEntity
import com.dongbantest.app.data.local.MemoryEntity
import com.dongbantest.app.data.local.TalkMetaEntity
import com.dongbantest.app.data.local.TripDao
import com.dongbantest.app.data.local.TripEntity
import com.dongbantest.app.domain.model.DataSourceType
import com.drivediary.talkrec.block.CaptureSource
import com.drivediary.talkrec.capture.PcmSlice
import com.drivediary.talkrec.sink.KeptBlock
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripLifecycleTest {
    @get:Rule val temp = TemporaryFolder(File("build/tmp/trip-lifecycle-tests").apply { mkdirs() })
    private val db = LifecycleDao()
    private fun repository() = TripRepository(db.dao, MediaFileStore(temp.root))

    private suspend fun TripRepository.start(now: Long = 10_000) =
        startTrip(" new ", " destination ", listOf(" friend "), 100f, DataSourceType.MOCK, now)

    @Test fun startClosesEveryPreviousRealTripBeforeInsertingAndPreservesItsData() = runBlocking {
        db.trip(1, 100, endOdometer = 42f)
        db.trip(2, 200)
        db.trip(3, 300)
        val sample = db.trip(4, 400, sample = true)
        val finished = db.trip(5, 500, endedAt = 600)
        db.memory(1, 150)
        db.point(2, 250)
        val repository = repository()
        repository.onTripEnding = { id ->
            db.events += "ending:$id"
            assertTrue(listOf(1L, 2L, 3L).all { repository.isTripEnding(it) })
            assertFalse(repository.isTripOngoing(id))
            assertNull(repository.observeOngoingTrip().first())
        }

        val id = repository.start()

        assertEquals(listOf("ending:1", "end:1", "ending:2", "end:2", "ending:3", "end:3", "insert:$id"), db.events)
        assertEquals(150L, db.trips.getValue(1).endedAt)
        assertEquals(250L, db.trips.getValue(2).endedAt)
        assertEquals(300L, db.trips.getValue(3).endedAt)
        assertEquals(42f, db.trips.getValue(1).endOdometer)
        assertEquals(sample, db.trips[4])
        assertEquals(finished, db.trips[5])
        assertEquals(1, db.memories.size)
        assertEquals(1, db.points.size)
        assertEquals(listOf(id), db.open().map { it.id })
        assertEquals(id, repository.observeOngoingTrip().first()?.id)
        assertEquals("new", db.trips.getValue(id).title)
        assertEquals("friend", db.trips.getValue(id).companions)
        assertEquals(10_000L, db.trips.getValue(id).startedAt)

        repository.onTripEnding = {}
        repository.endTrip(id, null, now = 11_000)
        assertTrue(db.open().isEmpty())
        assertNull(repository.observeOngoingTrip().first())
    }

    @Test fun reconciliationUsesLatestLocationMemoOrConversationTimeInsteadOfCurrentTime() = runBlocking {
        (1L..5L).forEach { db.trip(it, 100) }
        db.point(1, 900)
        db.memory(1, 700)
        db.talk(1, savedAt = 50_000, end = 800)
        db.point(2, 700)
        db.memory(2, 950)
        db.talk(2, savedAt = 50_000, end = 800)
        db.point(3, 700)
        db.memory(3, 800)
        db.talk(3, savedAt = 50_000, end = 980)
        // No records for trip 4; invalid pre-start records must not create a negative duration for 5.
        db.memory(5, 50)

        repository().start(now = 1_000_000)

        assertEquals(listOf(900L, 950L, 980L, 100L, 100L), (1L..5L).map { db.trips.getValue(it).endedAt })
    }

    @Test fun startupKeepsLatestRealTripExactlyAsItWasAndIsIdempotent() = runBlocking {
        db.trip(1, 100)
        db.trip(2, 200)
        // Resolve equal start times consistently with the ongoing-trip query.
        val latest = db.trip(3, 200, endOdometer = 77f)
        val sample = db.trip(4, 900, sample = true)
        db.point(1, 150)
        db.memory(2, 250)
        val repository = repository()
        val ended = mutableListOf<Long>()
        repository.onTripEnding = { id ->
            ended += id
            assertTrue(repository.isTripOngoing(latest.id))
            assertEquals(latest.id, repository.observeOngoingTrip().first()?.id)
        }

        repository.reconcileOngoingTrips()
        repository.reconcileOngoingTrips()

        assertEquals(listOf(1L, 2L), ended)
        assertEquals(150L, db.trips.getValue(1).endedAt)
        assertEquals(250L, db.trips.getValue(2).endedAt)
        assertEquals(latest, db.trips[latest.id])
        assertEquals(sample, db.trips[sample.id])
        assertEquals(listOf(latest.id), db.open().map { it.id })
    }

    @Test fun startupWithZeroOrOneRealOpenTripDoesNothing() = runBlocking {
        val repository = repository()
        repository.onTripEnding = { error("There is no stale trip to end") }
        repository.reconcileOngoingTrips()
        val sample = db.trip(1, 900, sample = true)
        repository.reconcileOngoingTrips()
        val only = db.trip(2, 100)
        repository.reconcileOngoingTrips()
        assertEquals(sample, db.trips[1])
        assertEquals(only, db.trips[2])
        assertTrue(db.events.isEmpty())
    }

    @Test fun staleRoomSnapshotCannotExposeAnEndedTripDuringOrAfterReplacement() = runBlocking {
        db.trip(1, 100)
        db.trip(2, 200)
        db.publishChanges = false
        val repository = repository()
        assertEquals(2L, repository.observeOngoingTrip().first()?.id)
        repository.onTripEnding = {
            assertNull(repository.observeOngoingTrip().first())
        }

        val id = repository.start()

        // Room has not delivered its invalidation yet; it still holds the old latest row.
        assertNull(repository.observeOngoingTrip().first())
        db.publish()
        assertEquals(id, repository.observeOngoingTrip().first()?.id)
    }

    @Test fun concurrentStartsSerializeClosingAndNeverLeaveTwoRealOpenTrips() = runBlocking {
        db.trip(1, 100)
        db.trip(2, 200)
        val repository = repository()
        val stopping = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        repository.onTripEnding = { id ->
            if (id == 1L) { stopping.complete(Unit); release.await() }
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { repository.start(now = 1_000) }
        stopping.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { repository.start(now = 2_000) }
        assertFalse(second.isCompleted)
        release.complete(Unit)
        val firstId = first.await()
        val secondId = second.await()
        assertEquals(1_000L, db.trips.getValue(firstId).endedAt)
        assertEquals(listOf(secondId), db.open().map { it.id })
    }

    @Test fun closingDrainsQueuedAudioBeforeChoosingEndTimeAndKeepsRunSummaryPath() = runBlocking {
        db.trip(1, 100)
        val repository = repository()
        val block = KeptBlock("last", 1, emptyList(), 200, 800,
            null, null, CaptureSource.AUTO, PcmSlice(200, 800, byteArrayOf()))
        repository.onTripEnding = { id ->
            assertFalse(repository.isTripOngoing(id))
            val saved = repository.saveTalkClip(block, { true }, { false }) { file ->
                file.writeBytes(byteArrayOf(1))
                600L
            }
            assertNotNull(saved)
            repository.closeTalkRun(id, "last-run")
        }

        repository.start()

        assertEquals(800L, db.trips.getValue(1).endedAt)
        assertEquals(listOf(1L to "last-run"), db.closedRuns)
        assertEquals(1, db.memories.size)
        assertTrue(File(db.memories.values.single().filePath!!).exists())
    }

    @Test fun staleArrivalCannotReopenAnOldTripOnceAnotherHasStarted() = runBlocking {
        db.trip(1, 100)
        val repository = repository()
        val id = repository.start()
        repository.reopenTrip(1)
        assertEquals(100L, db.trips.getValue(1).endedAt)
        assertEquals(listOf(id), db.open().map { it.id })
    }

    @Test fun manualEndUsesExplicitTimeAndDuplicateArrivalCannotOverwriteIt() = runBlocking {
        db.trip(1, 100)
        db.memory(1, 200)
        val repository = repository()
        var stops = 0
        repository.onTripEnding = { stops++ }
        assertTrue(repository.endTrip(1, 123f, now = 900))
        assertFalse(repository.endTrip(1, null, now = 1_000))
        assertEquals(900L, db.trips.getValue(1).endedAt)
        assertEquals(123f, db.trips.getValue(1).endOdometer)
        assertEquals(1, stops)
        // A legitimate arrival undo still works when there is no replacement trip.
        repository.reopenTrip(1)
        assertTrue(repository.isTripOngoing(1))
    }
}

/** DAO contract fake: real repository, lifecycle locks, flows and file persistence are exercised. */
private class LifecycleDao {
    val trips = linkedMapOf<Long, TripEntity>()
    val memories = linkedMapOf<Long, MemoryEntity>()
    val points = mutableListOf<LocationPointEntity>()
    private val talks = mutableMapOf<Long, TalkMetaEntity>()
    val events = mutableListOf<String>()
    val closedRuns = mutableListOf<Pair<Long, String>>()
    private val ongoing = MutableStateFlow<TripEntity?>(null)
    var publishChanges = true

    fun open() = trips.values.filter { it.endedAt == null && !it.isSample }
        .sortedWith(compareByDescending<TripEntity> { it.startedAt }.thenByDescending { it.id })

    fun publish() { ongoing.value = open().firstOrNull() }

    fun trip(id: Long, start: Long, sample: Boolean = false, endedAt: Long? = null, endOdometer: Float? = null): TripEntity =
        TripEntity(id, "trip $id", "", "", start, endedAt, null, endOdometer, null, sample).also {
            trips[id] = it
            publish()
        }

    fun memory(tripId: Long, at: Long): Long {
        val id = (memories.keys.maxOrNull() ?: 0) + 1
        memories[id] = MemoryEntity(id, tripId, "TEXT", at, "memo", null, null, null)
        return id
    }

    fun talk(tripId: Long, savedAt: Long, end: Long) {
        val id = memory(tripId, savedAt)
        memories[id] = memories.getValue(id).copy(type = "VOICE")
        talks[id] = TalkMetaEntity(id, "AUTO", end - 10, end, null, null, summaryStatus = "SKIPPED")
    }

    fun point(tripId: Long, at: Long) { points += LocationPointEntity(0, tripId, at, 0.0, 0.0, null) }

    val dao = Proxy.newProxyInstance(TripDao::class.java.classLoader, arrayOf(TripDao::class.java)) { _, method, args ->
        when (method.name) {
            "getTrip" -> trips[args[0] as Long]
            "getOngoingTrips" -> open()
            "observeOngoingTrip" -> ongoing
            "getLastRecordAt" -> {
                val id = args[0] as Long
                (points.filter { it.tripId == id }.map { it.recordedAt } +
                    memories.values.filter { it.tripId == id }.map { talks[it.id]?.blockEndMs ?: it.createdAt }).maxOrNull()
            }
            "insertTrip" -> {
                check(open().isEmpty()) { "A real open trip was left behind before insertion" }
                val id = (trips.keys.maxOrNull() ?: 0) + 1
                trips[id] = (args[0] as TripEntity).copy(id = id)
                events += "insert:$id"
                if (publishChanges) publish()
                id
            }
            "updateTrip" -> {
                val trip = args[0] as TripEntity
                trips[trip.id] = trip
                events += "end:${trip.id}"
                if (publishChanges) publish()
                Unit
            }
            "getMemory" -> memories[args[0] as Long]
            "getMemoryByPath" -> memories.values.firstOrNull { it.filePath == args[0] }
            "insertTalkMemory" -> {
                val id = (memories.keys.maxOrNull() ?: 0) + 1
                memories[id] = (args[0] as MemoryEntity).copy(id = id)
                talks[id] = (args[1] as TalkMetaEntity).copy(memoryId = id)
                id
            }
            "closeTalkRun" -> { closedRuns += (args[0] as Long) to (args[1] as String); Unit }
            "pendingRunCeiling" -> null
            else -> error("Unexpected DAO call: ${method.name}")
        }
    } as TripDao
}
