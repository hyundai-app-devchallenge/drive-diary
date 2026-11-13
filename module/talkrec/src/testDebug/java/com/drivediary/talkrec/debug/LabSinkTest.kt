package com.drivediary.talkrec.debug

import com.drivediary.talkrec.block.CaptureSource
import com.drivediary.talkrec.block.FinalLine
import com.drivediary.talkrec.capture.PcmSlice
import com.drivediary.talkrec.sink.KeptBlock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LabSinkTest {
    @Test fun keepsOnlyIdsDeletesOnceAndClearPreventsLateRepopulation() = runBlocking {
        val sink = LabSink()
        val block = KeptBlock("first", 0, listOf(FinalLine("synthetic", 0)), 0, 100,
            .9, null, CaptureSource.AUTO, PcmSlice(0, 100, ByteArray(3200)))
        assertFalse(sink.deletePreviousSavedClip(0, "empty"))
        assertTrue(sink.onKeptBlock(block))
        val next = KeptBlock("second", 0, block.lines, 0, 100, .9, null, block.source, block.audio)
        assertTrue(sink.onKeptBlock(next))
        assertTrue(sink.deletePreviousSavedClip(0, "refusal"))
        assertEquals("second", sink.state.value.deletedBlockId)
        assertFalse(sink.deletePreviousSavedClip(0, "refusal"))
        assertTrue(sink.deletePreviousSavedClip(0, "another"))
        assertEquals("first", sink.state.value.deletedBlockId)
        assertFalse(sink.deletePreviousSavedClip(0, "empty-again"))
        sink.clear()
        assertTrue(sink.onKeptBlock(block))
        assertEquals(LabSinkState(), sink.state.value)
    }
}
