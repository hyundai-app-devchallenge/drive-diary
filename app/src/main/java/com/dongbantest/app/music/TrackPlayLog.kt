package com.dongbantest.app.music

import java.util.Locale

/** Pure wall-clock state machine. A row token is local to this log, never a Room id. */
class TrackPlayLog {
    enum class State { NONE, PLAYING, PAUSED, STOPPED, ERROR, BUFFERING, CONNECTING }

    data class Snapshot(
        val packageName: String,
        val title: String?,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long? = null,
        val state: State = State.PLAYING,
        val positionMs: Long? = null,
    ) {
        internal fun key() = listOf(packageName, title.orEmpty(), artist.orEmpty())
            .map { it.trim().lowercase(Locale.ROOT) }
    }

    data class Row(
        val token: Long,
        val startedAt: Long,
        val endedAt: Long,
        val playedMs: Long,
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val sourcePackage: String,
    )

    sealed interface Command {
        val row: Row
        data class Insert(override val row: Row) : Command
        data class Update(override val row: Row) : Command
        data class Delete(override val row: Row) : Command
    }

    private var nextToken = 0L
    private var row: Row? = null
    private var key: List<String>? = null
    private var playing = false
    private var lastTime = Long.MIN_VALUE
    private var pausedAt = 0L
    private var lastWriteAt = 0L
    private var written: Row? = null
    private var lastPosition: Long? = null

    /** time belongs to the whole ordered session snapshot, including an empty list. */
    fun onSnapshot(time: Long, sessions: List<Snapshot>): List<Command> {
        val now = advance(time)
        val commands = mutableListOf<Command>()
        val valid = sessions.filter { !it.title.isNullOrBlank() }
        if (sessions.isNotEmpty() && valid.isEmpty()) {
            checkpointOrExpire(now, commands)
            return commands
        }
        val selected = valid.firstOrNull { it.state == State.PLAYING }
        if (selected != null) {
            val same = key == selected.key()
            val expired = row != null && !playing && now - pausedAt > RESUME_WINDOW_MS
            if (!same || expired || isRepeat(selected)) {
                close(commands, now)
                open(selected, now, commands)
            } else if (!playing) {
                playing = true
                row = row?.copy(endedAt = now)
                write(commands, now)
            }
            lastPosition = selected.positionMs?.takeIf { it >= 0 }
        } else {
            val current = valid.firstOrNull { it.key() == key }
            val continuing = current?.state in listOf(State.BUFFERING, State.CONNECTING)
            // An empty/blank attach snapshot never creates a row. A lost current session pauses it.
            if (playing && !continuing) {
                playing = false
                pausedAt = now
                write(commands, now)
            }
            current?.positionMs?.takeIf { it >= 0 }?.let { lastPosition = it }
        }
        checkpointOrExpire(now, commands)
        return commands
    }

    /** Call periodically even when the player sends no callbacks. */
    fun checkpoint(time: Long): List<Command> {
        val now = advance(time)
        return mutableListOf<Command>().also { checkpointOrExpire(now, it) }
    }

    fun stop(time: Long): List<Command> {
        val now = advance(time)
        return mutableListOf<Command>().also { close(it, now) }
    }

    private fun advance(time: Long): Long {
        val now = maxOf(time, lastTime)
        if (playing) row = row?.let {
            it.copy(endedAt = now, playedMs = it.playedMs + (now - lastTime))
        }
        lastTime = now
        return now
    }

    private fun isRepeat(snapshot: Snapshot): Boolean {
        val current = row ?: return false
        val duration = snapshot.durationMs?.takeIf { it > 0 } ?: return false
        val position = snapshot.positionMs?.takeIf { it >= 0 } ?: return false
        val previous = lastPosition ?: return false
        return current.playedMs.toDouble() > duration * 0.8 &&
            position <= REPEAT_START_MS && previous > REPEAT_START_MS && position < previous
    }

    private fun open(snapshot: Snapshot, now: Long, commands: MutableList<Command>) {
        row = Row(++nextToken, now, now, 0, snapshot.title!!.trim(),
            snapshot.artist?.trim()?.ifEmpty { null }, snapshot.album?.trim()?.ifEmpty { null },
            snapshot.durationMs, snapshot.packageName.trim())
        key = snapshot.key()
        playing = true
        lastPosition = snapshot.positionMs?.takeIf { it >= 0 }
        commands += Command.Insert(row!!)
        written = row
        lastWriteAt = now
    }

    private fun write(commands: MutableList<Command>, now: Long) {
        row?.takeIf { it != written }?.let {
            commands += Command.Update(it)
            written = it
        }
        lastWriteAt = now
    }

    private fun checkpointOrExpire(now: Long, commands: MutableList<Command>) {
        if (row == null) return
        if (!playing && now - pausedAt > RESUME_WINDOW_MS) close(commands, now)
        else if (playing && now - lastWriteAt >= CHECKPOINT_MS) write(commands, now)
    }

    private fun close(commands: MutableList<Command>, now: Long) {
        row?.let {
            if (it.playedMs < MIN_PLAYED_MS) commands += Command.Delete(it)
            else write(commands, now)
        }
        row = null
        written = null
        key = null
        playing = false
        lastPosition = null
    }

    companion object {
        const val MIN_PLAYED_MS = 10_000L
        const val RESUME_WINDOW_MS = 10 * 60_000L
        const val CHECKPOINT_MS = 30_000L
        const val REPEAT_START_MS = 3_000L
    }
}
