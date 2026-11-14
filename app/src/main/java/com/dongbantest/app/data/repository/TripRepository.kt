package com.dongbantest.app.data.repository

import com.dongbantest.app.data.local.LocationPointEntity
import com.dongbantest.app.data.local.MemoryEntity
import com.dongbantest.app.data.local.MemoryWithTalk
import com.dongbantest.app.data.local.TalkMetaEntity
import com.dongbantest.app.data.local.StoryRow
import com.drivediary.talkrec.summary.PendingSummary
import com.drivediary.talkrec.summary.StorySplitResult
import com.dongbantest.app.data.talk.ClipPersistence
import com.dongbantest.app.data.talk.ClipRow
import com.dongbantest.app.data.talk.clipTranscript
import com.dongbantest.app.domain.model.TalkMeta
import com.dongbantest.app.domain.model.TalkSource
import com.dongbantest.app.domain.model.SummaryStatus
import com.drivediary.talkrec.sink.KeptBlock
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.dongbantest.app.data.local.TripDao
import com.dongbantest.app.data.local.TripEntity
import com.dongbantest.app.data.local.VehicleSignalEntity
import com.dongbantest.app.domain.location.RoutePath
import com.dongbantest.app.domain.location.summarizeDrive
import com.dongbantest.app.domain.model.DataSourceType
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.Memory
import com.dongbantest.app.domain.model.MemoryType
import com.dongbantest.app.domain.model.Trip
import com.dongbantest.app.domain.model.TripWithMemories
import com.dongbantest.app.domain.model.VehicleSignal
import com.dongbantest.app.domain.model.VehicleSignalType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 여행 기록 저장/조회. 서버 없이 Room 에만 저장한다. */
class TripRepository(
    private val dao: TripDao,
    private val files: MediaFileStore,
) {
    private val clips = ClipPersistence(files.talkJournalDir)
    private var recoveredClips = false
    private val deletingTrips = ConcurrentHashMap.newKeySet<Long>()
    private val lifecycle = Mutex()
    private val endingTrips = MutableStateFlow<Set<Long>>(emptySet())
    /** Fence writers synchronously, before any coroutine involved in discard can suspend. */
    fun beginDiscardTrip(tripId: Long) { deletingTrips += tripId }
    fun isTripBeingDeleted(tripId: Long): Boolean = tripId in deletingTrips
    fun isTripEnding(tripId: Long): Boolean = tripId in endingTrips.value
    /** Stop producers and drain saved capture work before committing an end. Also used by deletion. */
    var onTripEnding: suspend (Long) -> Unit = {}

    fun observeTrips(): Flow<List<Trip>> =
        combine(dao.observeTrips(), dao.observePhotoMemories()) { trips, photos ->
            val coverByTrip = photos.groupBy { it.tripId }.mapValues { it.value.first().filePath }
            trips.map { it.toDomain(coverByTrip[it.id]) }
        }

    /** 끝난 여행별 지도 경로 (여행 id → 위치가 튄 곳에서 끊긴 구간들). */
    fun observeFinishedRoutes(): Flow<Map<Long, List<List<LocationPoint>>>> =
        dao.observeFinishedTripPoints().map { rows ->
            rows.groupBy { it.tripId }
                .mapValues { (_, points) -> RoutePath.segments(points.map { it.toDomain() }) }
                .filterValues { it.isNotEmpty() }
        }

    fun observeOngoingTrip(): Flow<Trip?> =
        combine(dao.observeOngoingTrip(), endingTrips) { trip, _ ->
            // Recheck Room snapshots: an invalidation can still be queued when a batch finishes.
            trip?.takeIf { isTripOngoing(it.id) }?.toDomain()
        }.distinctUntilChanged()

    fun observeTripWithMemories(tripId: Long): Flow<TripWithMemories?> =
        combine(
            dao.observeTrip(tripId),
            dao.observeMemoriesWithTalk(tripId),
            dao.observeLocationPoints(tripId),
            dao.observeVehicleSignals(tripId),
        ) { trip, memories, points, signals ->
            trip?.let { t ->
                val domainMemories = memories.map { it.toDomain() }
                TripWithMemories(
                    trip = t.toDomain(domainMemories.firstOrNull { it.type == MemoryType.PHOTO }?.filePath),
                    memories = domainMemories,
                    drive = summarizeDrive(
                        points = points.map { it.toDomain() },
                        signals = signals.mapNotNull { it.toDomain() },
                        finished = t.endedAt != null,
                    ),
                )
            }
        }

    suspend fun isTripOngoing(tripId: Long): Boolean = !isTripEnding(tripId) &&
        canSaveOngoingTrip(tripId) && !isTripEnding(tripId)

    // Already queued audio may finish saving while onTripEnding drains the capture service.
    private suspend fun canSaveOngoingTrip(tripId: Long): Boolean = !isTripBeingDeleted(tripId) &&
        dao.getTrip(tripId)?.let { it.endedAt == null && !it.isSample } == true && !isTripBeingDeleted(tripId)

    suspend fun tripExists(tripId: Long): Boolean = !isTripBeingDeleted(tripId) &&
        dao.getTrip(tripId) != null && !isTripBeingDeleted(tripId)

    /** One ownership boundary for encoding + atomic Room insert; no asynchronous PCM retention. */
    suspend fun saveTalkClip(
        block: KeptBlock,
        canSave: () -> Boolean,
        sendToAi: () -> Boolean,
        writeAudio: suspend (File) -> Long,
    ): Long? = withContext(Dispatchers.IO) {
        clips.locked {
            recoverTalkClipsLocked()
            if (!canSave() || !canSaveOngoingTrip(block.tripId)) return@locked null
            val file = files.talkVoiceFile(block.tripId, block.id)
            if (clips.wasDeleted(file)) return@locked null
            clips.save(file,
                existing = { dao.getMemoryByPath(file.absolutePath)?.id },
                write = { writeAudio(file) },
                insert = { duration ->
                    // Discard/stop during encoding is a normal rejection, not a capture failure.
                    if (!canSave() || !canSaveOngoingTrip(block.tripId)) return@save null
                    val ai = sendToAi()
                    dao.insertTalkMemory(
                        MemoryEntity(tripId = block.tripId, type = MemoryType.VOICE.name,
                            createdAt = System.currentTimeMillis(), text = null, filePath = file.absolutePath,
                            transcript = clipTranscript(block.lines.map { it.text }), durationMs = duration),
                        TalkMetaEntity(memoryId = 0, source = block.source.name,
                            blockStartMs = block.startMs, blockEndMs = block.endMs,
                            keepScore = block.keepScore, kind = block.kind?.name?.lowercase(Locale.ROOT),
                            runId = block.runId, closeReason = block.closeReason.name, runClosed = false,
                            summaryStatus = if (ai) SummaryStatus.PENDING.name else SummaryStatus.SKIPPED.name,
                            storyId = if (ai) null else "${block.runId}:0",
                            storyIndex = if (ai) null else 0),
                    )
                },
            )
        }
    }

    /** Import a finished fixture through the same file lock/rollback and Room transaction as clips. */
    internal suspend fun saveSampleTalk(
        tripId: Long,
        meta: TalkMeta,
        writeAudio: suspend (File) -> Long,
    ): Long? = withContext(Dispatchers.IO) {
        clips.locked {
            recoverTalkClipsLocked()
            if (!tripExists(tripId)) return@locked null
            val trip = dao.getTrip(tripId) ?: return@locked null
            require(trip.isSample && trip.endedAt != null)
            require(meta.summaryStatus == SummaryStatus.DONE && meta.runClosed == true && meta.storyKeep != null)
            require(meta.blockStartMs >= trip.startedAt && meta.blockEndMs <= trip.endedAt && meta.blockEndMs > meta.blockStartMs)
            val file = files.sampleTalkVoiceFile(tripId, requireNotNull(meta.storyId))
            if (clips.wasDeleted(file)) return@locked null
            clips.save(file,
                existing = { dao.getMemoryByPath(file.absolutePath)?.id },
                write = { writeAudio(file) },
                insert = { duration ->
                    require(duration == meta.blockEndMs - meta.blockStartMs)
                    if (!tripExists(tripId)) return@save null
                    dao.insertTalkMemory(
                        MemoryEntity(tripId = tripId, type = MemoryType.VOICE.name,
                            createdAt = meta.blockEndMs, text = null, filePath = file.absolutePath,
                            transcript = null, durationMs = duration),
                        TalkMetaEntity(memoryId = 0, source = meta.source.name,
                            blockStartMs = meta.blockStartMs, blockEndMs = meta.blockEndMs,
                            keepScore = meta.keepScore, kind = meta.kind, title = meta.title,
                            tags = meta.tags?.let { JSONArray(it).toString() },
                            summaryStatus = meta.summaryStatus.name, summaryModel = meta.summaryModel,
                            runId = meta.runId, closeReason = meta.closeReason, runClosed = meta.runClosed,
                            storyId = meta.storyId, storyIndex = meta.storyIndex, storyKind = meta.storyKind,
                            storyKeep = meta.storyKeep, storyReason = meta.storyReason),
                    )
                },
            )
        }
    }

    suspend fun updateTalkSummary(
        memoryId: Long, status: SummaryStatus, title: String? = null, tags: List<String>? = null,
        kind: String? = null, model: String? = null,
    ) = dao.updateTalkSummary(memoryId, title, tags?.let { JSONArray(it).toString() }, kind, status.name, model)

    suspend fun listPendingTalkSummaries(): List<Memory> {
        val rows = dao.pendingTalkMemories()
        rows.mapNotNull { it.talk?.runId }.distinct().forEach { dao.skipPartiallyConsentedRun(it) }
        return dao.pendingTalkMemories().map { it.toDomain() }
    }

    suspend fun getPendingTalkSummary(memoryId: Long): Memory? = dao.pendingTalkMemory(memoryId)?.toDomain()

    suspend fun closeTalkRun(tripId: Long, runId: String): Long? = clips.locked {
        if (!tripExists(tripId)) return@locked null
        dao.closeTalkRun(tripId, runId)
        dao.pendingRunCeiling(runId)
    }

    suspend fun finishTalkRun(item: PendingSummary, status: SummaryStatus, result: StorySplitResult? = null, model: String? = null) {
        val runId = item.runId ?: return
        val rows = if (result == null) item.ids.map { id ->
            StoryRow(id, "$runId:0", 0, null, null, null, null, null, status.name, null)
        } else result.stories.flatMapIndexed { index, story -> story.segments.map { segment ->
            StoryRow(item.ids[segment - 1], "$runId:$index", index, story.title,
                JSONArray(story.tags).toString(), story.kind, story.keep, story.reason, status.name, model)
        } }
        dao.saveStoryRows(rows)
    }

    /** Across ALL trips, counted at save time; MANUAL clips are excluded. */
    suspend fun savedAutoDurationMs(sinceMs: Long, untilMs: Long): Long = dao.savedAutoDurationMs(sinceMs, untilMs)

    suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String): Boolean = withContext(Dispatchers.IO) {
        clips.deleteLatest(tripId, refusalId,
            latest = { dao.latestTalkMemory(tripId)?.clipRow() },
            find = { dao.getMemory(it)?.clipRow() }, delete = { dao.deleteMemory(it) })
    }

    suspend fun recoverTalkClips() = withContext(Dispatchers.IO) { clips.locked { recoverTalkClipsLocked() } }

    private suspend fun recoverTalkClipsLocked() {
        if (recoveredClips) return
        clips.recover(find = { dao.getMemory(it)?.clipRow() }, delete = { dao.deleteMemory(it) })
        files.talkFiles().forEach { file ->
            if (file.extension == "part" || dao.getMemoryByPath(file.absolutePath) == null) ClipPersistence.remove(file)
        }
        recoveredClips = true
    }

    suspend fun addLocationPoint(point: LocationPoint): Long = clips.locked {
        if (!isTripOngoing(point.tripId)) return@locked 0L
        dao.insertLocationPoint(
            LocationPointEntity(
                tripId = point.tripId,
                recordedAt = point.recordedAt,
                latitude = point.latitude,
                longitude = point.longitude,
                accuracyMeters = point.accuracyMeters,
            )
        )
    }

    suspend fun addVehicleSignal(signal: VehicleSignal): Long = clips.locked {
        if (!isTripOngoing(signal.tripId)) return@locked 0L
        dao.insertVehicleSignal(
            VehicleSignalEntity(
                tripId = signal.tripId,
                recordedAt = signal.recordedAt,
                type = signal.type.name,
            )
        )
    }

    suspend fun startTrip(
        title: String,
        destination: String,
        companions: List<String>,
        odometer: Float?,
        odometerSource: DataSourceType?,
        now: Long = System.currentTimeMillis(),
    ): Long = lifecycle.withLock {
        closeOngoingTrips(dao.getOngoingTrips())
        dao.insertTrip(TripEntity(
            title = title.trim(),
            destination = destination.trim(),
            companions = companions.joinToString(",") { it.trim() },
            startedAt = now,
            endedAt = null,
            startOdometer = odometer,
            endOdometer = null,
            odometerSource = odometerSource?.name,
            isSample = false,
        ))
    }

    /** Called once at application startup, before arrival/question observers are started. */
    suspend fun reconcileOngoingTrips() = lifecycle.withLock {
        closeOngoingTrips(dao.getOngoingTrips().drop(1))
    }

    private suspend fun closeOngoingTrips(trips: List<TripEntity>) = withTripsEnding(trips.map { it.id }.toSet()) {
        // Keep the latest row selected until every older row has been saved and closed.
        trips.asReversed().forEach { endTripLocked(it.id, odometer = null, now = null) }
    }

    suspend fun endTrip(tripId: Long, odometer: Float?, now: Long = System.currentTimeMillis()): Boolean =
        lifecycle.withLock {
            withTripsEnding(setOf(tripId)) { endTripLocked(tripId, odometer, now) }
        }

    private suspend fun <T> withTripsEnding(ids: Set<Long>, block: suspend () -> T): T {
        endingTrips.update { it + ids }
        try { return block() }
        finally { endingTrips.update { it - ids } }
    }

    /** null now uses the last persisted record, after capture has drained, for abandoned trips. */
    private suspend fun endTripLocked(tripId: Long, odometer: Float?, now: Long?): Boolean {
        if (isTripBeingDeleted(tripId)) return false
        val current = dao.getTrip(tripId) ?: return false
        if (current.endedAt != null) return false
        onTripEnding(tripId)
        return clips.locked {
            if (isTripBeingDeleted(tripId)) return@locked false
            val trip = dao.getTrip(tripId) ?: return@locked false
            if (trip.endedAt != null) return@locked false
            val endedAt = now ?: maxOf(trip.startedAt, dao.getLastRecordAt(tripId) ?: trip.startedAt)
            dao.updateTrip(trip.copy(endedAt = endedAt, endOdometer = odometer ?: trip.endOdometer))
            true
        }
    }

    suspend fun setStartPlace(tripId: Long, place: String) = clips.locked {
        if (isTripBeingDeleted(tripId)) return@locked
        val trip = dao.getTrip(tripId) ?: return@locked
        dao.updateTrip(trip.copy(startPlace = place))
    }

    suspend fun setEndPlace(tripId: Long, place: String) = clips.locked {
        if (isTripBeingDeleted(tripId)) return@locked
        val trip = dao.getTrip(tripId) ?: return@locked
        dao.updateTrip(trip.copy(endPlace = place))
    }

    suspend fun startPlaceOf(tripId: Long): String? = dao.getTrip(tripId)?.startPlace

    /** 자동 종료를 되돌린다 ("아직 여행 중이에요"). */
    suspend fun reopenTrip(tripId: Long) = lifecycle.withLock {
        clips.locked {
            if (isTripBeingDeleted(tripId)) return@locked
            val trip = dao.getTrip(tripId) ?: return@locked
            // An old arrival notification must not resurrect a trip after another has started.
            if (dao.getOngoingTrips().any { it.id != tripId }) return@locked
            dao.updateTrip(trip.copy(endedAt = null, endPlace = null))
        }
    }

    suspend fun lastLocation(tripId: Long): LocationPoint? = dao.getLastLocationPoint(tripId)?.toDomain()

    suspend fun addTextMemo(tripId: Long, text: String) =
        addMemory(tripId, MemoryType.TEXT, text = text.trim())

    suspend fun addVoiceMemo(tripId: Long, path: String, durationMs: Long) =
        addMemory(tripId, MemoryType.VOICE, filePath = path, durationMs = durationMs)

    suspend fun addPhoto(tripId: Long, path: String) =
        addMemory(tripId, MemoryType.PHOTO, filePath = path)

    suspend fun addArrival(tripId: Long, note: String) =
        addMemory(tripId, MemoryType.ARRIVAL, text = note)

    suspend fun addQuestion(tripId: Long, question: String) =
        addMemory(tripId, MemoryType.QUESTION, text = question)

    suspend fun deleteMemory(memoryId: Long): Unit = withContext(Dispatchers.IO) {
        clips.deleteLatest(0, "memory:$memoryId", latest = { dao.getMemory(memoryId)?.clipRow() },
            find = { dao.getMemory(it)?.clipRow() }, delete = { dao.deleteMemory(it) })
        Unit
    }

    /** 기록 삭제 시 관련 음성/사진 파일도 함께 정리한다. */
    suspend fun deleteTrip(tripId: Long) {
        beginDiscardTrip(tripId)
        onTripEnding(tripId)
        withContext(NonCancellable + Dispatchers.IO) {
            clips.locked {
                val paths = dao.getMemories(tripId).mapNotNull { it.filePath }
                paths.forEach { files.delete(it) }
                files.deleteTalkFiles(tripId, paths)
                // FK cascades: memories, talk_meta, location_points and vehicle_signals.
                dao.deleteTrip(tripId)
                clips.forgetTrip(tripId, paths)
            }
        }
    }

    internal suspend fun createFinishedSample(
        title: String, from: String, to: String, companions: List<String>,
        start: Long, end: Long, points: List<LocationPoint>,
    ): Long = clips.locked {
        require(end > start && points.size >= 2)
        dao.insertFinishedSample(
            TripEntity(title = title, destination = to, companions = companions.joinToString(","),
                startedAt = start, endedAt = end, startOdometer = null, endOdometer = null,
                odometerSource = null, isSample = true, startPlace = from, endPlace = to),
            points.map { LocationPointEntity(tripId = 0, recordedAt = it.recordedAt,
                latitude = it.latitude, longitude = it.longitude, accuracyMeters = it.accuracyMeters) },
        )
    }

    suspend fun clearSampleTrips() {
        dao.getSampleTrips().forEach { deleteTrip(it.id) }
    }

    /** 예시 여행으로 표시한다. "예시 지우기"([clearSampleTrips])로 한 번에 지울 수 있다. */
    suspend fun markSample(tripId: Long) = clips.locked {
        if (isTripBeingDeleted(tripId)) return@locked
        val trip = dao.getTrip(tripId) ?: return@locked
        dao.updateTrip(trip.copy(isSample = true))
    }

    /** 이미 들어 있는 예시 여행 제목들 (같은 예시를 두 번 넣지 않으려고 쓴다) */
    suspend fun sampleTripTitles(): Set<String> = dao.getSampleTrips().map { it.title }.toSet()

    internal suspend fun addMemory(
        tripId: Long,
        type: MemoryType,
        text: String? = null,
        filePath: String? = null,
        durationMs: Long? = null,
        createdAt: Long = System.currentTimeMillis(),
    ): Long = clips.locked {
        if (!tripExists(tripId)) {
            files.delete(filePath)
            return@locked 0L
        }
        dao.insertMemory(
            MemoryEntity(
                tripId = tripId,
                type = type.name,
                createdAt = createdAt,
                text = text,
                filePath = filePath,
                transcript = null, // 수동 메모 등 기존 경로는 전사 없이 저장한다.
                durationMs = durationMs,
            )
        )
    }
}

private fun TripEntity.toDomain(coverPhotoPath: String? = null) = Trip(
    id = id,
    title = title,
    destination = destination,
    companions = companions.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    startedAt = startedAt,
    endedAt = endedAt,
    startOdometer = startOdometer,
    endOdometer = endOdometer,
    odometerSource = odometerSource?.let { runCatching { DataSourceType.valueOf(it) }.getOrNull() },
    isSample = isSample,
    coverPhotoPath = coverPhotoPath,
    startPlace = startPlace,
    endPlace = endPlace,
)

private fun MemoryWithTalk.toDomain() = memory.toDomain(talk?.toDomain())

private fun TalkMetaEntity.toDomain() = TalkMeta(
    memoryId = memoryId, source = TalkSource.valueOf(source), blockStartMs = blockStartMs,
    blockEndMs = blockEndMs, keepScore = keepScore, kind = kind, title = title,
    tags = tags?.let { raw ->
        val array = JSONArray(raw)
        List(array.length()) { array.getString(it) }
    }, summaryStatus = SummaryStatus.valueOf(summaryStatus), summaryModel = summaryModel,
    runId = runId, closeReason = closeReason, runClosed = runClosed, storyId = storyId,
    storyIndex = storyIndex, storyKind = storyKind, storyKeep = storyKeep, storyReason = storyReason,
)

private fun MemoryEntity.clipRow() = ClipRow(id, filePath)

private fun MemoryEntity.toDomain(talk: TalkMeta? = null) = Memory(
    id = id,
    tripId = tripId,
    type = runCatching { MemoryType.valueOf(type) }.getOrDefault(MemoryType.TEXT),
    createdAt = createdAt,
    text = text,
    filePath = filePath,
    transcript = transcript,
    durationMs = durationMs,
    talk = talk,
)

private fun LocationPointEntity.toDomain() = LocationPoint(
    tripId = tripId,
    recordedAt = recordedAt,
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracyMeters,
)

/** 알 수 없는 이름(예전 버전에서 저장한 값 등)은 건너뛴다. */
private fun VehicleSignalEntity.toDomain(): VehicleSignal? =
    VehicleSignalType.entries.firstOrNull { it.name == type }
        ?.let { VehicleSignal(tripId = tripId, recordedAt = recordedAt, type = it) }
