package com.drivediary.talkrec.capture

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class PcmRingBufferTest {
    private fun pcm(samples: Int, value: Int) = ByteArray(samples * 2).also { bytes ->
        for (i in 0 until samples) { bytes[2 * i] = value.toByte(); bytes[2 * i + 1] = (value shr 8).toByte() }
    }
    @Test fun absoluteRangesClipAndOverwriteOldest() {
        val time = 1_800_000_000_000L
        val ring = PcmRingBuffer(1000)
        ring.append(time, pcm(8000, -1234))
        ring.append(time + 500, pcm(16000, 4321))
        val slice = ring.slice(time - 100, time + 2000)
        assertEquals(time + 500, slice.startMs); assertEquals(time + 1500, slice.endMs)
        assertArrayEquals(pcm(16000, 4321), slice.pcm)
        assertArrayEquals(pcm(16, 4321), ring.slice(time + 1001, time + 1002).pcm)
        slice.pcm.fill(0)
        assertArrayEquals(pcm(16, 4321), ring.slice(time + 1001, time + 1002).pcm)
    }
    @Test fun gapsAreZeroAndLargeWritesStayBounded() {
        val ring = PcmRingBuffer(100)
        ring.append(1000, pcm(160, 1)); ring.append(1020, pcm(160, 2))
        assertArrayEquals(pcm(160, 0), ring.slice(1010, 1020).pcm)
        ring.append(2000, pcm(3200, 3))
        assertEquals(2100L, ring.slice(0, 3000).startMs)
        assertEquals(3200, ring.slice(0, 3000).pcm.size)
        ring.clear(); assertTrue(ring.slice(0, 3000).pcm.isEmpty())
    }
    @Test fun defaultHoldsOnlyLastThreeMinutes() {
        val ring = PcmRingBuffer()
        repeat(1801) { ring.append(it * 100L, pcm(1600, it)) }
        val slice = ring.slice(0, 200_000)
        assertEquals(100L, slice.startMs); assertEquals(180_100L, slice.endMs)
        assertEquals(5_760_000, slice.pcm.size)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOverlappingCapture() {
        val ring = PcmRingBuffer(); ring.append(1000, pcm(1600, 1)); ring.append(1050, pcm(1600, 2))
    }
    @Test fun concurrentSlicesRemainConsistent() {
        val ring = PcmRingBuffer(1000)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try { start.await(); repeat(1000) { ring.append(it * 100L, pcm(1600, 0x1234)) } }
            catch (t: Throwable) { failure.set(t) }
        }
        writer.start(); start.countDown()
        repeat(1000) {
            val bytes = ring.slice(0, 200_000).pcm
            for (i in bytes.indices step 2) { assertEquals(0x34.toByte(), bytes[i]); assertEquals(0x12.toByte(), bytes[i + 1]) }
        }
        writer.join(); failure.get()?.let { throw it }
    }
}
