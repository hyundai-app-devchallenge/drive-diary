package com.dongbantest.app.data.demo

import com.dongbantest.app.domain.model.SummaryStatus
import com.dongbantest.app.domain.model.TalkMeta
import com.dongbantest.app.domain.model.TalkSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

internal data class DemoPhoto(val file: String, val at: String)
internal data class DemoItem(
    val index: Int, val type: String, val at: String, val text: String?,
    val title: String?, val tags: List<String>,
    val kind: String?, val keep: Boolean?, val durationMs: Long,
) {
    fun audioAsset(key: String) = "demo/audio/${key}_$index.m4a"

    fun metadata(tripId: Long, storyIndex: Int, startedAt: Long): TalkMeta {
        require(type == "clip" && durationMs > 0)
        val start = DemoTime.at(startedAt, at)
        val run = "demo-trip-$tripId"
        return TalkMeta(
            memoryId = 0, source = TalkSource.AUTO, blockStartMs = start,
            blockEndMs = start + durationMs, keepScore = if (keep == true) 1.0 else 0.1,
            kind = kind, title = title, tags = tags, summaryStatus = SummaryStatus.DONE,
            runId = run, runClosed = true, storyId = "$run:$storyIndex", storyIndex = storyIndex,
            storyKind = kind, storyKeep = keep,
        )
    }
}

internal data class DemoTrip(
    val key: String, val title: String, val gpx: String, val from: String, val to: String,
    val companions: List<String>, val daysAgo: Int, val photos: List<DemoPhoto>,
    val items: List<DemoItem>, val arrival: String,
) {
    fun validateTimeline(start: Long, end: Long) {
        require(end > start)
        val clips = items.filter { it.type == "clip" }.mapIndexed { i, item -> item.metadata(0, i, start) }
        require(clips.all { it.blockStartMs >= start && it.blockEndMs <= end }) { "Demo clip outside route" }
        require(clips.zipWithNext().all { (a, b) -> a.blockEndMs <= b.blockStartMs }) { "Demo clips overlap" }
    }
}

internal object DemoManifest {
    fun parse(json: String): List<DemoTrip> {
        val trips = JSONObject(json).getJSONArray("trips")
        return (0 until trips.length()).map { i ->
            val t = trips.getJSONObject(i)
            val photos = t.getJSONArray("photos")
            val items = t.getJSONArray("items")
            DemoTrip(t.getString("key"), t.getString("title"), t.getString("gpx"),
                t.getString("from"), t.getString("to"), t.getJSONArray("companions").strings(),
                t.getInt("daysAgo"), (0 until photos.length()).map { n ->
                    val p = photos.getJSONObject(n)
                    DemoPhoto(p.getString("file"), p.getString("at"))
                }, (0 until items.length()).map { n ->
                    val item = items.getJSONObject(n)
                    val type = item.getString("type")
                    require(type == "clip" || type == "question")
                    if (type == "clip") {
                        val kind = item.getString("kind")
                        require(kind == "story" || kind == "moment")
                        val tags = item.getJSONArray("tags").strings()
                        require(tags.isNotEmpty())
                        DemoItem(n, type, item.getString("at"), null,
                            item.getString("title"), tags,
                            kind, item.getBoolean("keep"), item.getLong("durationMs").also { require(it > 0) })
                    } else DemoItem(n, type, item.getString("at"), item.getString("text"),
                        null, emptyList(), null, null, 0)
                }, t.getString("arrival"))
        }.also { require(it.map { trip -> trip.key }.distinct().size == it.size) }
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
}

internal object DemoTime {
    fun at(startedAt: Long, clock: String): Long {
        val parts = clock.split(":")
        require(parts.size == 2)
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        require(hour in 0..23 && minute in 0..59)
        return startedAt + ((hour * 60L + minute) - 9 * 60L) * 60_000L
    }

    fun clamped(startedAt: Long, endedAt: Long, clock: String) =
        at(startedAt, clock).coerceIn(startedAt, endedAt)

    fun start(daysAgo: Int, now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
