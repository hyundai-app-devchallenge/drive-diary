package com.drivediary.talkrec.capture

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmFileAudioSourceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun wav(file: File, rate: Int = 16000): ByteArray {
        val pcm = ByteArray(4800) { (it % 127).toByte() } // 150 ms, pads to two 100 ms frames.
        val bytes = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size).put(pcm).array()
        file.writeBytes(bytes)
        return pcm
    }
    @Test fun wavStreamsPacedTimestampedFramesAndSilentDrain() = runBlocking {
        val file = temp.newFile("invented.wav"); val pcm = wav(file)
        val started = System.nanoTime()
        val frames = PcmFileAudioSource(file, trailingSilenceMs = 100).frames().toList()
        assertEquals(3, frames.size)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 290)
        assertEquals(100, frames[1].startMs - frames[0].startMs)
        assertArrayEquals(pcm, frames.take(2).flatMap { it.pcm.toList() }.toByteArray().copyOf(pcm.size))
        assertTrue(frames[1].pcm.drop(1600).all { it == 0.toByte() })
        assertEquals(0.0, frames[2].rms(), 0.0)
    }
    @Test fun rawLoopCancelsAndReleasesFile() = runBlocking {
        val file = temp.newFile("invented.pcm"); file.writeBytes(ByteArray(3200) { 2 })
        val frames = PcmFileAudioSource(file, loop = true).frames().take(3).toList()
        assertEquals(3, frames.size); assertArrayEquals(frames[0].pcm, frames[2].pcm)
        assertTrue(file.delete())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsWrongWavFormat() = runBlocking {
        val file = temp.newFile("bad.wav"); wav(file, 8000)
        PcmFileAudioSource(file).frames().toList()
        Unit
    }
}
