package com.drivediary.talkrec.capture

import com.drivediary.talkrec.block.*
import com.drivediary.talkrec.judge.ConversationJudgment
import com.drivediary.talkrec.policy.PolicyGate
import com.drivediary.talkrec.sink.KeptBlock
import com.drivediary.talkrec.sink.TalkSink
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CaptureProcessorTest {
    private class Sink : TalkSink {
        var saves = 0; var deletes = 0; var score: Double? = null; var source: CaptureSource? = null
        var accept = true
        override suspend fun onKeptBlock(block: KeptBlock): Boolean {
            assertTrue(block.audio.pcm.any { it != 0.toByte() })
            score = block.keepScore; source = block.source
            if (accept) saves++
            return accept
        }
        override suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String): Boolean { deletes++; return true }
    }
    private fun policy() = PolicyGate.fromAssets { File(System.getProperty("talkrec.assetsDir"), it).inputStream() }
    private fun block(vararg lines: String, manual: Boolean = false) = ConversationBlock(
        lines.mapIndexed { i, text -> FinalLine(text, 1000L + i) }, 1000, 2000,
        if (manual) CaptureSource.MANUAL else CaptureSource.AUTO, CloseReason.MANUAL)
    private fun audio(ms: Int = 1000) = PcmSlice(1000, 1000L + ms, ByteArray(ms * 32) { 1 })

    @Test fun refusalDeletesPreviousAndNeverReachesJudgeEvenForManual() = runBlocking {
        val sink = Sink()
        val p = CaptureProcessor(7, policy(), sink, CaptureBudget(), { error("Judge must not run") })
        val audio = audio()
        assertEquals(-1, p.process(block("녹음하지 마", manual = true), audio))
        assertEquals(1, sink.deletes); assertEquals(0, sink.saves); assertTrue(audio.pcm.all { it == 0.toByte() })
    }
    @Test fun autoRejectsLowScoreWhileManualBypassesJudgeDecision() = runBlocking {
        val sink = Sink()
        val p = CaptureProcessor(7, policy(), sink, CaptureBudget(), { ConversationJudgment(.02, false, null) })
        assertEquals(0, p.process(block("종이별이 반짝여"), audio()))
        assertEquals(1, p.process(block("종이별이 반짝여", manual = true), audio()))
        assertEquals(.02, sink.score!!, 0.0); assertEquals(CaptureSource.MANUAL, sink.source)
    }
    @Test fun mixedCommandsDoNotLeakInAudioWhenLineTimingIsUnknown() = runBlocking {
        val sink = Sink()
        val p = CaptureProcessor(7, policy(), sink, CaptureBudget(), { error("Judge must not run") })
        assertEquals(0, p.process(block("별을 찾았어", "볼륨 올려", "다음에도 찾자"), audio()))
        assertEquals(0, sink.saves)
    }
    @Test fun failedOrMissingJudgeUsesTenMinutesPerHourIncludingPadding() = runBlocking {
        val sink = Sink(); var attempts = 0
        val p = CaptureProcessor(7, policy(), sink, CaptureBudget(), { attempts++; error("synthetic failure") })
        repeat(6) { assertEquals(1, p.process(block("종이별이 반짝여"), audio(95_000))) }
        assertEquals(0, p.process(block("종이별이 반짝여"), audio(95_000)))
        assertEquals(1, attempts); assertNull(sink.score)
        assertEquals(1, p.process(block("종이별이 반짝여"), audio(30_000)))
        assertEquals(0, p.process(block("종이별이 반짝여"), audio(1)))
    }
    @Test fun rejectedSinkRefundsBudgetAndErasesPcm() = runBlocking {
        val sink = Sink(); sink.accept = false
        val p = CaptureProcessor(7, policy(), sink, CaptureBudget(1000), null)
        val first = audio(); assertEquals(0, p.process(block("종이별"), first))
        assertTrue(first.pcm.all { it == 0.toByte() })
        sink.accept = true; assertEquals(1, p.process(block("종이별"), audio()))
    }
    @Test fun budgetUsesRollingWindowAndSurvivesProcessorReplacement() {
        val budget = CaptureBudget(600_000)
        assertNotNull(budget.reserve(1000, 600_000))
        assertNull(budget.reserve(3_600_999, 1))
        assertNotNull(budget.reserve(3_601_000, 600_000))
    }

    @Test fun persistentHistorySurvivesRestartAndDoesNotDoubleCountCommittedSaves() {
        val budget = CaptureBudget()
        val first = budget.reserve(1000, 95_000, persistedMs = 475_000)!!
        budget.commit(first)
        assertNotNull(budget.reserve(1001, 30_000, persistedMs = 570_000))
        assertNull(budget.reserve(1002, 1, persistedMs = 570_000))
        val restarted = CaptureBudget()
        assertNull(restarted.reserve(2000, 31_000, persistedMs = 570_000))
        assertNotNull(restarted.reserve(2001, 30_000, persistedMs = 570_000))
    }

    @Test fun durableHookDrivesAutoBudgetButManualBypassesIt() = runBlocking {
        var saves = 0
        val sink = object : TalkSink {
            override suspend fun onKeptBlock(block: KeptBlock): Boolean { saves++; return true }
            override suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String) = false
            override suspend fun savedAutoDurationMs(sinceMs: Long, untilMs: Long): Long {
                assertEquals(3_600_000L, untilMs - sinceMs)
                return 600_000
            }
        }
        val processor = CaptureProcessor(7, policy(), sink, CaptureBudget(), null)
        assertEquals(0, processor.process(block("종이별"), audio()))
        assertEquals(1, processor.process(block("종이별", manual = true), audio()))
        assertEquals(1, saves)
    }

    @Test fun simultaneousEqualReservationsHaveIndependentOwnership() {
        val budget = CaptureBudget(1000)
        val first = budget.reserve(0, 100, persistedMs = 0)!!
        val second = budget.reserve(0, 100, persistedMs = 0)!!
        budget.commit(first)
        assertNull(budget.reserve(1, 801, persistedMs = 100))
        budget.refund(second)
        assertNotNull(budget.reserve(1, 900, persistedMs = 100))
    }
}
