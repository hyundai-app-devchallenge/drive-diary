package com.dongbantest.app.debug

import com.dongbantest.app.domain.location.summarizeDrive
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.Memory
import com.dongbantest.app.domain.model.MemoryType
import com.dongbantest.app.domain.model.Trip
import com.dongbantest.app.domain.model.TripWithMemories
import com.dongbantest.app.ui.v3.components.MomentKind
import com.dongbantest.app.ui.v3.components.MomentPin
import com.dongbantest.app.ui.v3connect.mapMoments
import com.dongbantest.app.ui.v3connect.talks
import com.dongbantest.app.ui.v3connect.toRecord
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleTalksTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun allCatalogRoutesProduceStableDistinctStoriesWithMapPinsAndTimelineSpans() {
        val names = listOf("busan", "daegu", "gangneung", "daejeon", "pangyo", "jeonju", "yeosu",
            "gyeongju", "sokcho", "nami", "eurwangni", "suwon", "andong")
        val assets = listOf(File("src/debug/assets/gpx"), File("app/src/debug/assets/gpx")).first { it.isDirectory }
        var total = 0
        names.forEachIndexed { tripIndex, name ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(assets, "uos-$name.gpx"))
            val nodes = document.getElementsByTagName("trkpt")
            val locations = (0 until nodes.length).map { i ->
                val node = nodes.item(i) as org.w3c.dom.Element
                LocationPoint(42, Instant.parse(node.getElementsByTagName("time").item(0).textContent).toEpochMilli(),
                    node.getAttribute("lat").toDouble(), node.getAttribute("lon").toDouble(), 5f)
            }
            val start = locations.first().recordedAt
            val end = locations.last().recordedAt
            val drive = summarizeDrive(locations, finished = true)
            val times = drive.path.flatten().map { it.recordedAt }
            val plans = SampleTalks.plan(tripIndex, start, end, times)
            assertEquals(plans, SampleTalks.plan(tripIndex, start, end, times))
            assertEquals(2 + tripIndex % 3, plans.size)
            assertTrue(plans.zipWithNext().all { (a, b) -> a.startMs + a.durationMs < b.startMs })
            assertTrue(plans.first().startMs < start + (end - start) / 2)
            assertTrue(plans.last().startMs > start + (end - start) / 2)
            plans.forEach { plan ->
                assertTrue(plan.startMs >= start + 60_000)
                assertTrue(plan.startMs + plan.durationMs <= end - 60_000)
                assertTrue(plan.durationMs in 5_000L..15_000L)
                assertTrue(plan.startMs in times)
                assertTrue(plan.tags.size in 2..3)
            }
            val memories = plans.mapIndexed { index, plan ->
                Memory(index + 1L, 42, MemoryType.VOICE, plan.startMs + plan.durationMs,
                    filePath = "/voice/sample-$index.wav", durationMs = plan.durationMs,
                    talk = plan.metadata(42, index))
            }
            val trip = Trip(42, "예시 여행", "바다", emptyList(), start, end, null, null, null, true)
            val data = TripWithMemories(trip, memories, drive)
            val talks = data.talks()
            assertEquals(plans.map { it.title }, talks.map { it.talk.title })
            assertEquals(plans.size, talks.map { it.talk.id }.distinct().size)
            talks.forEachIndexed { index, row ->
                assertFalse(row.talk.hidden)
                assertEquals("story", row.talk.kind)
                assertEquals(plans[index].tags.first(), row.talk.category)
                assertTrue(row.talk.tags.containsAll(plans[index].tags))
                assertEquals(listOf(memories[index].filePath), row.paths)
                assertEquals(plans[index].durationMs, row.durationMs)
            }
            val record = data.toRecord(talks)
            val pins = record.talks.map { talk ->
                val span = requireNotNull(talk.span)
                assertTrue(span.start > 0f && span.endInclusive < 1f && span.start < span.endInclusive)
                MomentPin(talk.id, MomentKind.Talk, span.start, span.endInclusive, talk.title)
            }
            // Includes the same Float round trip and two-minute nearest-GPS filter as the detail map.
            assertEquals(plans.size, data.mapMoments(pins).size)
            total += plans.size
        }
        assertEquals(38, total)
    }

    @Test fun dateShiftPreservesTheRelativePlan() {
        val times = (0L..3_600_000L step 10_000).toList()
        val shift = 1_800_000_000_000L
        val plans = SampleTalks.plan(4, 0, times.last(), times)
        val shifted = SampleTalks.plan(4, shift, shift + times.last(), times.map { it + shift })
        assertEquals(plans.map { it.copy(startMs = it.startMs + shift) }, shifted)
    }

    @Test fun wavHasDecodablePcmFormatExactDurationQuietSamplesAndFades() {
        for (duration in listOf(5_000L, 9_000L, 15_000L)) {
            val file = temp.newFile("sample-$duration.wav")
            assertEquals(duration, SampleTalks.writeAudio(file, duration))
            AudioSystem.getAudioInputStream(file).use { audio ->
                assertEquals(AudioFormat.Encoding.PCM_SIGNED, audio.format.encoding)
                assertEquals(16_000f, audio.format.sampleRate)
                assertEquals(1, audio.format.channels)
                assertEquals(16, audio.format.sampleSizeInBits)
                assertFalse(audio.format.isBigEndian)
                assertEquals(duration, audio.frameLength * 1_000 / 16_000)
            }
            val bytes = file.readBytes()
            assertEquals(44L + duration * 32, file.length())
            val samples = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            assertEquals(0, samples.get(0).toInt())
            assertEquals(0, samples.get(samples.limit() - 1).toInt())
            var peak = 0
            while (samples.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(samples.get().toInt()))
            assertTrue(peak in 600..655)
        }
    }
}
