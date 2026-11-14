package com.dongbantest.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dongbantest.app.domain.model.TrackPlay

@Entity(
    tableName = "track_plays",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tripId")],
)
data class TrackPlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val playedMs: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val sourcePackage: String,
    val sourceLabel: String?,
)

fun TrackPlayEntity.toDomain() = TrackPlay(
    id, tripId, startedAt, endedAt, playedMs, title, artist, album, durationMs, sourcePackage, sourceLabel,
)
