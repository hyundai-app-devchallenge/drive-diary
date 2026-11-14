package com.dongbantest.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("UPDATE talk_meta SET runClosed = 1 WHERE runId = :runId AND memoryId IN (SELECT id FROM memories WHERE tripId = :tripId)")
    suspend fun markTalkRunClosed(tripId: Long, runId: String)

    @Query("""UPDATE talk_meta SET summaryStatus='SKIPPED', storyId=runId || ':0', storyIndex=0
        WHERE runId=:runId AND summaryStatus='PENDING'
        AND EXISTS (SELECT 1 FROM talk_meta AS sibling WHERE sibling.runId=:runId AND sibling.summaryStatus='SKIPPED')""")
    suspend fun skipPartiallyConsentedRun(runId: String)

    @Transaction
    suspend fun closeTalkRun(tripId: Long, runId: String) {
        markTalkRunClosed(tripId, runId)
        skipPartiallyConsentedRun(runId)
    }

    @Query("SELECT MAX(memoryId) FROM talk_meta WHERE runId = :runId AND summaryStatus = 'PENDING'")
    suspend fun pendingRunCeiling(runId: String): Long?

    @Query("""UPDATE talk_meta SET title=:title, tags=:tags, storyId=:storyId, storyIndex=:storyIndex,
        storyKind=:kind, storyKeep=:keep, storyReason=:reason, summaryStatus=:status, summaryModel=:model,
        runClosed=1 WHERE memoryId=:id AND summaryStatus='PENDING'""")
    suspend fun updateStory(id: Long, storyId: String, storyIndex: Int, title: String?, tags: String?,
        kind: String?, keep: Boolean?, reason: String?, status: String, model: String?)

    @Transaction
    suspend fun saveStoryRows(rows: List<StoryRow>) {
        // A deletion or a competing worker invalidates the entire response, never a prefix.
        if (rows.any { pendingTalkMemory(it.id) == null }) return
        rows.forEach { updateStory(it.id, it.storyId, it.index, it.title, it.tags, it.kind, it.keep, it.reason, it.status, it.model) }
    }

    @Transaction
    @Query("SELECT * FROM memories WHERE tripId = :tripId ORDER BY createdAt ASC, id ASC")
    fun observeMemoriesWithTalk(tripId: Long): Flow<List<MemoryWithTalk>>

    @Insert
    suspend fun insertTalkMeta(meta: TalkMetaEntity)

    @Transaction
    suspend fun insertTalkMemory(memory: MemoryEntity, meta: TalkMetaEntity): Long {
        val id = insertMemory(memory)
        insertTalkMeta(meta.copy(memoryId = id))
        return id
    }

    @Query("SELECT * FROM memories WHERE filePath = :path LIMIT 1")
    suspend fun getMemoryByPath(path: String): MemoryEntity?

    @Query("""SELECT memories.* FROM memories INNER JOIN talk_meta ON talk_meta.memoryId = memories.id
        WHERE memories.tripId = :tripId AND memories.type = 'VOICE' AND talk_meta.source IN ('AUTO', 'MANUAL')
        ORDER BY memories.id DESC LIMIT 1""")
    suspend fun latestTalkMemory(tripId: Long): MemoryEntity?

    @Transaction
    @Query("""SELECT memories.* FROM memories INNER JOIN talk_meta ON talk_meta.memoryId = memories.id
        WHERE talk_meta.summaryStatus = 'PENDING' AND memories.type = 'VOICE' ORDER BY memories.id ASC""")
    suspend fun pendingTalkMemories(): List<MemoryWithTalk>

    @Transaction
    @Query("""SELECT memories.* FROM memories INNER JOIN talk_meta ON talk_meta.memoryId = memories.id
        WHERE memories.id = :memoryId AND memories.type = 'VOICE' AND talk_meta.summaryStatus = 'PENDING' LIMIT 1""")
    suspend fun pendingTalkMemory(memoryId: Long): MemoryWithTalk?

    @Query("""UPDATE talk_meta SET title = :title, tags = :tags, kind = :kind,
        summaryStatus = :status, summaryModel = :model WHERE memoryId = :memoryId AND summaryStatus = 'PENDING'""")
    suspend fun updateTalkSummary(memoryId: Long, title: String?, tags: String?, kind: String?, status: String, model: String?)

    @Query("""SELECT COALESCE(SUM(memories.durationMs), 0) FROM memories
        INNER JOIN talk_meta ON talk_meta.memoryId = memories.id
        WHERE memories.type = 'VOICE' AND talk_meta.source = 'AUTO'
        AND memories.createdAt > :sinceMs AND memories.createdAt <= :untilMs""")
    suspend fun savedAutoDurationMs(sinceMs: Long, untilMs: Long): Long

    @Query("SELECT * FROM trips ORDER BY startedAt DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE endedAt IS NULL AND isSample = 0 ORDER BY startedAt DESC, id DESC LIMIT 1")
    fun observeOngoingTrip(): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE endedAt IS NULL AND isSample = 0 ORDER BY startedAt DESC, id DESC")
    suspend fun getOngoingTrips(): List<TripEntity>

    /** Talk timestamps describe the recording, rather than a possibly delayed file save. */
    @Query("""SELECT MAX(recordedAt) FROM (
        SELECT recordedAt FROM location_points WHERE tripId = :tripId
        UNION ALL
        SELECT endedAt AS recordedAt FROM track_plays WHERE tripId = :tripId
        UNION ALL
        SELECT COALESCE(talk_meta.blockEndMs, memories.createdAt) AS recordedAt
        FROM memories LEFT JOIN talk_meta ON talk_meta.memoryId = memories.id
        WHERE memories.tripId = :tripId
    )""")
    suspend fun getLastRecordAt(tripId: Long): Long?

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observeTrip(id: Long): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTrip(id: Long): TripEntity?

    @Query("SELECT * FROM trips WHERE isSample = 1")
    suspend fun getSampleTrips(): List<TripEntity>

    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    @Query("SELECT * FROM memories WHERE tripId = :tripId ORDER BY createdAt ASC")
    fun observeMemories(tripId: Long): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE tripId = :tripId")
    suspend fun getMemories(tripId: Long): List<MemoryEntity>

    /** 다이어리 목록 카드의 표지 사진용 */
    @Query("SELECT * FROM memories WHERE type = 'PHOTO' ORDER BY createdAt ASC")
    fun observePhotoMemories(): Flow<List<MemoryEntity>>

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getMemory(id: Long): MemoryEntity?

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Insert
    suspend fun insertLocationPoint(point: LocationPointEntity): Long

    @Query("SELECT * FROM location_points WHERE tripId = :tripId ORDER BY recordedAt ASC")
    fun observeLocationPoints(tripId: Long): Flow<List<LocationPointEntity>>

    @Query("SELECT * FROM location_points WHERE tripId = :tripId ORDER BY recordedAt DESC LIMIT 1")
    suspend fun getLastLocationPoint(tripId: Long): LocationPointEntity?

    /** 끝난 여행들의 위치 기록 전부 (기록 지도용). */
    @Query(
        "SELECT location_points.* FROM location_points " +
            "INNER JOIN trips ON trips.id = location_points.tripId " +
            "WHERE trips.endedAt IS NOT NULL " +
            "ORDER BY location_points.tripId, location_points.recordedAt ASC"
    )
    fun observeFinishedTripPoints(): Flow<List<LocationPointEntity>>

    @Insert
    suspend fun insertVehicleSignal(signal: VehicleSignalEntity): Long

    @Query("SELECT * FROM vehicle_signals WHERE tripId = :tripId ORDER BY recordedAt ASC")
    fun observeVehicleSignals(tripId: Long): Flow<List<VehicleSignalEntity>>
}

data class StoryRow(val id: Long, val storyId: String, val index: Int, val title: String?, val tags: String?,
    val kind: String?, val keep: Boolean?, val reason: String?, val status: String, val model: String?)
