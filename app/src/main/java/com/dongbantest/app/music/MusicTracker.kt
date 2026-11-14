package com.dongbantest.app.music

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.session.PlaybackState
import android.util.Log
import com.dongbantest.app.data.local.TrackPlayDao
import com.dongbantest.app.data.local.TrackPlayEntity
import com.dongbantest.app.data.repository.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-scoped owner. Every transition and Room write is serialized, including trip end. */
class MusicTracker(
    context: Context,
    private val repository: TripRepository,
    private val dao: TrackPlayDao,
    private val settings: MusicSettingsStore,
    private val scope: CoroutineScope,
) {
    private val context = context.applicationContext
    private val access = MusicAccess(this.context)
    private val watcher = NowPlayingWatcher(this.context)
    private val mutex = Mutex()
    private val debug = this.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var tripId: Long? = null
    private var job: Job? = null
    private var log: TrackPlayLog? = null
    private val rows = mutableMapOf<Long, TrackPlayEntity>()

    private sealed interface Event {
        data class Sessions(val sessions: List<NowPlaying>, val time: Long) : Event
        data object Gate : Event
        data object Connection : Event
        data object Tick : Event
    }

    fun start(tripId: Long) {
        scope.launch(Dispatchers.Main.immediate) {
            mutex.withLock {
                if (this@MusicTracker.tripId == tripId && job?.isActive == true) return@withLock
                if (!repository.isTripOngoing(tripId)) return@withLock
                closeLocked()
                if (!repository.isTripOngoing(tripId)) return@withLock
                this@MusicTracker.tripId = tripId
                job = scope.launch(Dispatchers.Main.immediate) {
                    merge(
                        watcher.snapshots.map { Event.Sessions(it, System.currentTimeMillis()) },
                        settings.enabled.map { Event.Gate },
                        MusicListenerService.connected.map { Event.Connection },
                        flow {
                            while (currentCoroutineContext().isActive) {
                                delay(1_000)
                                emit(Event.Tick)
                            }
                        },
                    ).collect { event ->
                        mutex.withLock event@ {
                            if (this@MusicTracker.tripId != tripId) return@event
                            if (!repository.isTripOngoing(tripId)) {
                                closeLocked()
                                return@event
                            }
                            val now = System.currentTimeMillis()
                            val enabled = settings.enabled.value && access.isGranted()
                            if (!enabled) {
                                closeLogLocked(now)
                                return@event
                            }
                            if (log == null) {
                                log = TrackPlayLog()
                                watcher.start()
                                apply(tripId, log!!.onSnapshot(now, watcher.snapshots.value.map { it.toLog() }))
                                return@event
                            }
                            when (event) {
                                is Event.Sessions -> apply(tripId, log!!.onSnapshot(event.time, event.sessions.map { it.toLog() }))
                                Event.Tick -> apply(tripId, log!!.checkpoint(now))
                                Event.Connection -> if (MusicListenerService.connected.value) watcher.refresh()
                                Event.Gate -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    /** Await this before ending/deleting a trip: no queued callback can insert after it returns. */
    suspend fun stop(tripId: Long) = withContext(NonCancellable + Dispatchers.Main.immediate) {
        mutex.withLock {
            if (this@MusicTracker.tripId == tripId) closeLocked()
        }
    }

    private suspend fun closeLocked() {
        val previousJob = job
        job = null
        closeLogLocked(System.currentTimeMillis())
        tripId = null
        previousJob?.cancel()
    }

    private suspend fun closeLogLocked(now: Long) {
        if (log == null) return
        watcher.stop()
        tripId?.let { id -> log?.stop(now)?.let { apply(id, it) } }
        log = null
        rows.clear()
    }

    private suspend fun apply(tripId: Long, commands: List<TrackPlayLog.Command>) {
        commands.forEach { command ->
            val row = command.row
            when (command) {
                is TrackPlayLog.Command.Insert -> {
                    // Package visibility/uninstall may prevent label lookup; the package remains available.
                    val label = runCatching {
                        context.packageManager.getApplicationInfo(row.sourcePackage, 0)
                            .loadLabel(context.packageManager).toString()
                    }.getOrNull()
                    val entity = TrackPlayEntity(
                        tripId = tripId, startedAt = row.startedAt, endedAt = row.endedAt,
                        playedMs = row.playedMs, title = row.title, artist = row.artist, album = row.album,
                        durationMs = row.durationMs, sourcePackage = row.sourcePackage, sourceLabel = label,
                    )
                    rows[row.token] = entity.copy(id = dao.insert(entity))
                }
                is TrackPlayLog.Command.Update -> rows[row.token]?.let { entity ->
                    val updated = entity.copy(endedAt = row.endedAt, playedMs = row.playedMs)
                    dao.update(updated)
                    rows[row.token] = updated
                }
                is TrackPlayLog.Command.Delete -> rows.remove(row.token)?.let { dao.delete(it.id) }
            }
            // Completed tokens are not needed for future writes; only the newest can be resumed.
            if (command is TrackPlayLog.Command.Insert) rows.keys.removeAll { it != row.token }
            if (debug) Log.i("MusicProbe", "track_plays trip=$tripId ${command.javaClass.simpleName} $row")
        }
    }

    private fun NowPlaying.toLog() = TrackPlayLog.Snapshot(
        packageName, title, artist, album, durationMs,
        when (state) {
            PlaybackState.STATE_PLAYING -> TrackPlayLog.State.PLAYING
            PlaybackState.STATE_BUFFERING -> TrackPlayLog.State.BUFFERING
            PlaybackState.STATE_CONNECTING -> TrackPlayLog.State.CONNECTING
            PlaybackState.STATE_PAUSED -> TrackPlayLog.State.PAUSED
            PlaybackState.STATE_STOPPED -> TrackPlayLog.State.STOPPED
            PlaybackState.STATE_ERROR -> TrackPlayLog.State.ERROR
            else -> TrackPlayLog.State.NONE
        },
        positionMs,
    )
}
