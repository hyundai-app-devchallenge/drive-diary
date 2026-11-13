package com.drivediary.talkrec.block

import org.junit.Assert.*
import org.junit.Test

class BlockAssemblerTest {
    private val t = 1_800_000_000_000L
    private fun energy(a: BlockAssembler, from: Long, until: Long, rms: Double): List<ConversationBlock> =
        (from until until step 100).flatMap { a.onEnergy(t + it, t + it + 100, rms) }

    @Test fun silenceRequiresBothReceiveGapAndTwentySecondsOfLowEnergy() {
        val a = BlockAssembler()
        energy(a, 0, 1000, 1000.0)
        a.onFinal(FinalLine("만든 별이 반짝여", t + 1000))
        assertTrue(energy(a, 1000, 20_900, 0.0).isEmpty())
        val block = energy(a, 20_900, 21_000, 0.0).single()
        assertEquals(t, block.startMs); assertEquals(t + 1000, block.endMs)
        assertEquals(CloseReason.SILENCE, block.reason)
        assertTrue(energy(a, 21_000, 50_000, 0.0).isEmpty())
    }
    @Test fun loudPcmPreventsSilenceDespiteNoFinals() {
        val a = BlockAssembler(); energy(a, 0, 1000, 800.0); a.onFinal(FinalLine("별을 찾았어", t + 1000))
        assertTrue(energy(a, 1000, 30_000, 800.0).isEmpty())
        assertTrue(energy(a, 30_000, 49_900, 0.0).isEmpty())
        assertEquals(t + 30_000, energy(a, 49_900, 50_000, 0.0).single().endMs)
    }
    @Test fun recentFinalDelaysCloseEvenWithLongQuietPcm() {
        val a = BlockAssembler(); a.onFinal(FinalLine("우리 별이야", t))
        energy(a, 0, 19_000, 0.0)
        assertTrue(a.onFinal(FinalLine("다음에도 찾자", t + 19_000)).isEmpty())
        assertTrue(energy(a, 19_000, 38_900, 0.0).isEmpty())
        assertEquals(2, energy(a, 38_900, 39_000, 0.0).single().lines.size)
    }
    @Test fun missingPcmCannotCountAsQuietEvidence() {
        val a = BlockAssembler(); a.onFinal(FinalLine("별을 찾았어", t))
        energy(a, 0, 10_000, 0.0)
        assertTrue(energy(a, 15_000, 34_900, 0.0).isEmpty())
        assertEquals(CloseReason.SILENCE, energy(a, 34_900, 35_000, 0.0).single().reason)
    }
    @Test fun closesAtNinetySecondsDuringContinuousSpeechAndKeepsOrder() {
        val a = BlockAssembler(); energy(a, 0, 100, 500.0)
        a.onFinal(FinalLine("첫 별", t + 100))
        energy(a, 100, 89_900, 500.0)
        a.onFinal(FinalLine("둘째 별", t + 89_900))
        val b = energy(a, 89_900, 90_000, 500.0).single()
        assertEquals(90_000, b.endMs - b.startMs); assertEquals(CloseReason.LENGTH, b.reason)
        assertEquals(listOf(t + 100, t + 89_900), b.lines.map { it.receivedMs })
        a.onFinal(FinalLine("새 별", t + 90_100))
        assertEquals(1, a.forceClose(t + 90_200)!!.lines.size)
    }
    @Test fun forceCloseIsManualAndResetDiscardsUnfinishedText() {
        val a = BlockAssembler(); assertNull(a.forceClose(t))
        a.onFinal(FinalLine("종이별을 접었어", t))
        val block = a.forceClose(t + 300)!!
        assertEquals(CaptureSource.MANUAL, block.source); assertEquals(CloseReason.MANUAL, block.reason)
        assertNull(a.forceClose(t + 400))
        a.onFinal(FinalLine("또 접자", t + 500)); a.reset(); assertNull(a.forceClose(t + 1000))
    }
    @Test fun loudSpikeResetsWholeQuietSpan() {
        val a = BlockAssembler(); a.onFinal(FinalLine("별이야", t))
        energy(a, 0, 19_900, 0.0); energy(a, 19_900, 20_000, 141.0)
        assertTrue(energy(a, 20_000, 39_900, 0.0).isEmpty())
        assertEquals(CloseReason.SILENCE, energy(a, 39_900, 40_000, 0.0).single().reason)
    }
}
