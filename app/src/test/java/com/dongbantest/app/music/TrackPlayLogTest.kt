package com.dongbantest.app.music

import com.dongbantest.app.music.TrackPlayLog.Command
import com.dongbantest.app.music.TrackPlayLog.Snapshot
import com.dongbantest.app.music.TrackPlayLog.State
import org.junit.Assert.*
import org.junit.Test

class TrackPlayLogTest {
    private val log = TrackPlayLog()
    private val rows = linkedMapOf<Long, TrackPlayLog.Row>()
    private val history = mutableListOf<Command>()
    private fun a(state: State = State.PLAYING, position: Long? = null, duration: Long? = null) =
        Snapshot("player", "A", "Artist", "Album", duration, state, position)
    private fun send(time: Long, vararg sessions: Snapshot): List<Command> =
        apply(log.onSnapshot(time, sessions.toList()))
    private fun stop(time: Long) = apply(log.stop(time))
    private fun tick(time: Long) = apply(log.checkpoint(time))
    private fun apply(commands: List<Command>): List<Command> {
        history += commands
        commands.forEach {
            when (it) {
                is Command.Insert -> { assertFalse(rows.containsKey(it.row.token)); rows[it.row.token] = it.row }
                is Command.Update -> { assertTrue(rows.containsKey(it.row.token)); rows[it.row.token] = it.row }
                is Command.Delete -> { assertNotNull(rows.remove(it.row.token)) }
            }
        }
        return commands
    }

    @Test fun nonePlusPlayingInsertsOneRowWithWallClockTimestamps() {
        val insert = send(1_000, a()).single() as Command.Insert
        assertEquals(1_000L, insert.row.startedAt)
        assertEquals(1_000L, insert.row.endedAt)
        assertEquals(0L, insert.row.playedMs)
        assertEquals("player", insert.row.sourcePackage)
    }

    @Test fun blankAttachAndNonPlayingSessionsNeverInsert() {
        for (state in State.entries) {
            assertTrue(send(0, a(state).copy(title = " \t ")).isEmpty())
            if (state != State.PLAYING) assertTrue(send(0, a(state)).isEmpty())
        }
        assertTrue(send(0, a().copy(title = null)).isEmpty())
        assertTrue(send(0).isEmpty())
        assertTrue(rows.isEmpty())
    }

    @Test fun blankTitleCallbackDoesNotInterruptCurrentPlayback() {
        send(0, a())
        assertTrue(send(5_000, a().copy(title = null)).isEmpty())
        stop(15_000)
        assertEquals(15_000L, rows.values.single().playedMs)
    }

    @Test fun trimmedCaseInsensitiveKeyIgnoresAlbumAndDurationChanges() {
        send(0, a())
        assertTrue(send(5_000, a().copy(packageName = " PLAYER ", title = " a ", artist = " ARTIST ",
            album = "new album", durationMs = 200_000)).isEmpty())
        stop(15_000)
        assertEquals(1, rows.size)
        assertEquals(15_000L, rows.values.single().playedMs)
        assertEquals("Album", rows.values.single().album)
    }

    @Test fun threeMetadataAndThreePlayingResendsStillProduceOneInsert() {
        send(0, a())
        repeat(3) { assertTrue(send(1_000L + it, a()).isEmpty()) }
        repeat(3) { assertTrue(send(2_000L + it, a(position = 2_000L + it)).isEmpty()) }
        stop(15_000)
        assertEquals(1, history.count { it is Command.Insert })
        assertEquals(1, rows.size)
        assertEquals(15_000L, rows.values.single().playedMs)
    }

    @Test fun bufferingAndConnectingForCurrentTrackKeepCounting() {
        send(0, a())
        assertTrue(send(5_000, a(State.BUFFERING)).isEmpty())
        assertTrue(send(8_000, a(State.CONNECTING)).isEmpty())
        stop(15_000)
        assertEquals(15_000L, rows.values.single().playedMs)
    }

    @Test fun everyInactiveStatePausesAndExcludesPausedTime() {
        for (state in listOf(State.PAUSED, State.STOPPED, State.NONE, State.ERROR)) {
            val machine = TrackPlayLog()
            machine.onSnapshot(0, listOf(a()))
            val paused = machine.onSnapshot(15_000, listOf(a(state))).single().row
            assertEquals(15_000L, paused.playedMs)
            assertEquals(15_000L, paused.endedAt)
            assertTrue(machine.onSnapshot(18_000, listOf(a(state))).isEmpty())
            val resumed = machine.onSnapshot(20_000, listOf(a())).single().row
            assertEquals(paused.token, resumed.token)
            assertEquals(15_000L, resumed.playedMs)
            val ended = machine.stop(25_000).single().row
            assertEquals(20_000L, ended.playedMs)
            assertEquals(25_000L, ended.endedAt)
        }
    }

    @Test fun sessionGonePausesAndSameTrackCanResume() {
        send(0, a())
        assertEquals(15_000L, send(15_000).single().row.playedMs)
        assertTrue(send(18_000).isEmpty())
        send(20_000, a())
        stop(25_000)
        assertEquals(1, rows.size)
        assertEquals(20_000L, rows.values.single().playedMs)
    }

    @Test fun pauseThenStopDoesNotExtendEndedAt() {
        send(0, a())
        send(15_000, a(State.PAUSED))
        assertTrue(stop(25_000).isEmpty())
        assertEquals(15_000L, rows.values.single().endedAt)
        assertTrue(stop(30_000).isEmpty())
    }

    @Test fun resumeAtExactlyTenMinutesUsesSameRow() {
        send(0, a())
        send(15_000, a(State.PAUSED))
        val resumed = send(615_000, a()).single()
        assertTrue(resumed is Command.Update)
        stop(620_000)
        assertEquals(1, rows.size)
        assertEquals(20_000L, rows.values.single().playedMs)
    }

    @Test fun resumeOneMillisecondAfterTenMinutesStartsNewRow() {
        send(0, a())
        send(15_000, a(State.PAUSED))
        assertTrue(send(615_001, a()).single() is Command.Insert)
        stop(630_001)
        assertEquals(listOf(15_000L, 15_000L), rows.values.map { it.playedMs })
        assertEquals(listOf(0L, 615_001L), rows.values.map { it.startedAt })
    }

    @Test fun pausedShortRowExpiresAfterResumeWindowWithoutCallbacks() {
        send(0, a())
        send(5_000, a(State.PAUSED))
        assertTrue(tick(605_000).isEmpty())
        assertTrue(tick(605_001).single() is Command.Delete)
        assertTrue(rows.isEmpty())
    }

    @Test fun differentPlayingTitleClosesPlayingTrack() {
        send(0, a())
        val commands = send(15_000, a().copy(title = "B"))
        assertTrue(commands[0] is Command.Update)
        assertTrue(commands[1] is Command.Insert)
        stop(30_000)
        assertEquals(listOf("A", "B"), rows.values.map { it.title })
        assertEquals(listOf(15_000L, 15_000L), rows.values.map { it.playedMs })
    }

    @Test fun differentArtistOrPackageIsDifferentTrack() {
        for (next in listOf(a().copy(artist = "other"), a().copy(packageName = "other"))) {
            val machine = TrackPlayLog()
            machine.onSnapshot(0, listOf(a()))
            assertTrue(machine.onSnapshot(15_000, listOf(next)).last() is Command.Insert)
        }
    }

    @Test fun differentPausedTrackDoesNotCreateRowOrResumeOldOne() {
        send(0, a())
        send(15_000, a(State.PAUSED).copy(title = "B"))
        stop(20_000)
        assertEquals("A", rows.values.single().title)
        assertEquals(15_000L, rows.values.single().playedMs)
    }

    @Test fun fiveSecondSkipIsDeletedOnTrackChangeAndOnTripEnd() {
        send(0, a())
        assertTrue(send(5_000, a().copy(title = "B")).first() is Command.Delete)
        assertTrue(stop(10_000).single() is Command.Delete)
        assertTrue(rows.isEmpty())
    }

    @Test fun tenSecondsIsKeptBut9999MillisecondsIsDeleted() {
        send(0, a())
        assertTrue(stop(9_999).single() is Command.Delete)
        send(10_000, a())
        assertTrue(stop(20_000).single() is Command.Update)
        assertEquals(10_000L, rows.values.single().playedMs)
    }

    @Test fun multipleSessionsPickFirstPlayingInListOrder() {
        send(0, a(State.PAUSED), a().copy(title = "B"), a().copy(title = "C"))
        assertEquals("B", rows.values.single().title)
        assertTrue(send(5_000, a(State.BUFFERING), a().copy(title = "B"), a()).isEmpty())
        stop(15_000)
        assertEquals(1, rows.size)
    }

    @Test fun firstPlayingChangingListOrderSwitchesTrack() {
        val b = a().copy(title = "B")
        send(0, a(), b)
        send(15_000, b, a())
        stop(30_000)
        assertEquals(listOf("A", "B"), rows.values.map { it.title })
    }

    @Test fun otherPlayingSessionTakesPriorityOverBufferingCurrentSession() {
        send(0, a())
        send(15_000, a(State.BUFFERING), a().copy(title = "B"))
        stop(30_000)
        assertEquals(listOf("A", "B"), rows.values.map { it.title })
    }

    @Test fun repeatOneAfter80PercentAndReturnWithinThreeSecondsStartsNewOccurrence() {
        send(0, a(position = 0, duration = 100_000))
        send(81_000, a(position = 81_000, duration = 100_000))
        val repeated = send(100_000, a(position = 3_000, duration = 100_000))
        assertTrue(repeated.last() is Command.Insert)
        assertTrue(send(100_001, a(position = 3_000, duration = 100_000)).isEmpty())
        stop(115_000)
        assertEquals(listOf(100_000L, 15_000L), rows.values.map { it.playedMs })
    }

    @Test fun repeatRequiresMoreThan80PercentAndActualPositionReturn() {
        send(0, a(position = 0, duration = 100_000))
        send(79_000, a(position = 79_000, duration = 100_000))
        assertTrue(send(80_000, a(position = 0, duration = 100_000)).isEmpty())
        send(90_000, a(position = 90_000, duration = 100_000))
        assertTrue(send(90_001, a(position = 3_001, duration = 100_000)).isEmpty())
        stop(95_000)
        assertEquals(1, rows.size)
    }

    @Test fun missingOrInvalidDurationOrPositionSkipsRepeatRule() {
        for (snapshot in listOf(a(position = 0), a(duration = 100_000),
            a(position = 0, duration = 0), a(position = -1, duration = 100_000))) {
            val machine = TrackPlayLog()
            machine.onSnapshot(0, listOf(a(position = 90_000, duration = 100_000)))
            assertFalse(machine.onSnapshot(100_000, listOf(snapshot)).any { it is Command.Insert })
        }
    }

    @Test fun checkpointEveryThirtySecondsWithoutPerCallbackWrites() {
        send(0, a())
        assertTrue(tick(29_999).isEmpty())
        assertEquals(30_000L, tick(30_000).single().row.playedMs)
        assertTrue(tick(30_001).isEmpty())
        assertTrue(send(31_000, a()).isEmpty())
        assertEquals(60_000L, tick(60_000).single().row.playedMs)
        send(65_000, a(State.PAUSED))
        assertTrue(tick(95_000).isEmpty())
        stop(100_000)
        assertEquals(65_000L, rows.values.single().endedAt)
    }

    @Test fun checkpointDoesNotDoubleCountAndBackwardClockNeverSubtractsTime() {
        send(1_000, a())
        tick(31_000)
        send(30_000, a())
        stop(41_000)
        assertEquals(40_000L, rows.values.single().playedMs)
        assertEquals(41_000L, rows.values.single().endedAt)
    }

    @Test fun requiredSequenceProducesExactlyTwoFifteenSecondRows() {
        send(0, a())
        repeat(3) { send(2_000L + it, a()) }
        repeat(3) { send(5_000L + it, a(position = 5_000L + it)) }
        send(15_000, a(State.PAUSED))
        send(20_000, a().copy(title = "B"))
        send(35_000, a(State.STOPPED).copy(title = "B"))
        stop(40_000)
        assertEquals(2, rows.size)
        assertEquals(listOf(15_000L, 15_000L), rows.values.map { it.playedMs })
        assertEquals(listOf(15_000L, 35_000L), rows.values.map { it.endedAt })
    }
}
