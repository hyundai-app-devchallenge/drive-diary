package com.dongbantest.app.data.demo

import java.io.File
import java.util.Calendar
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class DemoManifestTest {
    private val assets = File("src/main/assets/demo").takeIf { it.isDirectory } ?: File("app/src/main/assets/demo")
    private val trips by lazy { DemoManifest.parse(File(assets, "demo_trips.json").readText()) }
    private val now = 1_800_000_000_000L

    private fun gpx(trip: DemoTrip) = File(assets, "gpx/${trip.gpx}").inputStream().use { GpxParser.parse(it) }

    @Test fun parsesThreeTripsWithClipsAndQuestions() {
        assertEquals(listOf("A", "B", "C"), trips.map { it.key })
        trips.forEach { t ->
            assertTrue(t.companions.isNotEmpty() && t.arrival.isNotBlank() && t.title.isNotBlank())
            assertTrue(t.items.any { it.type == "clip" })
        }
        val clips = trips.flatMap { it.items }.filter { it.type == "clip" }
        assertEquals(12, clips.size)
        assertTrue(clips.all { it.keep == true })
        assertTrue(trips.flatMap { it.items }.any { it.type == "question" && !it.text.isNullOrBlank() })
    }

    @Test fun everyClipHasAudioAssetAndDurationMatchesReport() {
        val report = JSONArray(listOf(File("../tools/demo/audio_report.json"), File("tools/demo/audio_report.json")).first { it.exists() }
            .readText())
        val reported = (0 until report.length()).associate {
            report.getJSONObject(it).getString("asset") to report.getJSONObject(it).getLong("durationMs")
        }
        trips.forEach { t -> t.items.filter { it.type == "clip" }.forEach { c ->
            val file = File(assets, "audio/${t.key}_${c.index}.m4a")
            assertTrue("missing ${file.name}", file.isFile && file.length() > 0)
            assertTrue(c.audioAsset(t.key).endsWith("${t.key}_${c.index}.m4a"))
            if (reported.isNotEmpty()) assertEquals(file.name, reported[file.name], c.durationMs)
        } }
    }

    @Test fun photoFilesExist() {
        assertEquals(12, trips.sumOf { it.photos.size })
        trips.forEach { t -> t.photos.forEach { assertTrue(File(assets, it.file).isFile) } }
    }

    @Test fun timeMappingIsRelativeToNineOClock() {
        val start = DemoTime.start(10, now)
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertTrue(start < now)
        assertEquals(start, DemoTime.at(start, "09:00"))
        assertEquals(start + 6 * 60_000L, DemoTime.at(start, "09:06"))
        assertEquals(start + 65 * 60_000L, DemoTime.at(start, "10:05"))
    }

    @Test fun clampingKeepsTimesInsideTrip() {
        val start = 1_000_000L
        val end = start + 43 * 60_000L + 42_000L
        assertEquals(start, DemoTime.clamped(start, end, "08:59"))
        assertEquals(end, DemoTime.clamped(start, end, "09:44"))
        assertEquals(start + 60_000L, DemoTime.clamped(start, end, "09:01"))
        assertEquals(start, DemoTime.photoAt(start, "08:50"))
        assertEquals(start + (5 * 60 + 10) * 60_000L, DemoTime.photoAt(start, "14:10"))
    }

    @Test fun allTripsFitTheirRealGpxDurationWithoutOverlap() {
        trips.forEach { t ->
            val pts = gpx(t)
            val start = DemoTime.start(t.daysAgo, now)
            val end = start + (pts.last().time - pts.first().time)
            t.validateTimeline(start, end) // throws on overlap or out-of-range
            var storyIndex = 0
            val metas = t.items.filter { it.type == "clip" }.map { it.metadata(1, storyIndex++, start) }
            metas.forEachIndexed { i, m ->
                assertEquals(t.items.first { it.type == "clip" && it.title == m.title }.durationMs, m.blockEndMs - m.blockStartMs)
                assertEquals(i, m.storyIndex)
                assertEquals(1, metas.map { it.runId }.distinct().size.coerceAtMost(1))
                assertEquals(m.kind, m.storyKind)
                assertEquals(m.storyKeep == true, m.keepScore == 1.0)
            }
            val shots = t.photos.map { DemoTime.photoAt(start, it.at) }
            assertTrue(shots.all { it >= start })
            assertEquals(shots.sorted(), shots)
        }
    }

    @Test fun gpxParserFillsMissingTimesAndRejectsDisorder() {
        val ok = """<gpx><trk><trkseg>
            <trkpt lat="37.0" lon="127.0"><time>2026-01-01T00:00:00Z</time></trkpt>
            <trkpt lat="37.1" lon="127.1"/></trkseg></trk></gpx>""".byteInputStream()
        val p = GpxParser.parse(ok)
        assertEquals(2, p.size)
        assertEquals(p[0].time + 3_000, p[1].time)
        val bad = """<gpx><trk><trkseg>
            <trkpt lat="37.0" lon="127.0"><time>2026-01-01T00:00:10Z</time></trkpt>
            <trkpt lat="37.1" lon="127.1"><time>2026-01-01T00:00:00Z</time></trkpt></trkseg></trk></gpx>""".byteInputStream()
        try { GpxParser.parse(bad); fail() } catch (_: IllegalArgumentException) {}
    }
}
