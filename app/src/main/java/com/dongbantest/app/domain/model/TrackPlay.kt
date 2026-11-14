package com.dongbantest.app.domain.model

/** One listening occurrence. Timestamps share the location points' wall-clock time base. */
data class TrackPlay(
    val id: Long,
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
