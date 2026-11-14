package com.dongbantest.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackPlayDao {
    @Insert
    suspend fun insert(row: TrackPlayEntity): Long

    @Update
    suspend fun update(row: TrackPlayEntity)

    @Query("DELETE FROM track_plays WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM track_plays WHERE tripId = :tripId ORDER BY startedAt, id")
    fun observe(tripId: Long): Flow<List<TrackPlayEntity>>
}
