package com.dongbantest.app.data.demo

import android.content.Context
import com.dongbantest.app.data.repository.MediaFileStore
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.MemoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Main-source-set assets and startup hook make demos available in release as well as debug. */
class DemoSeeder(context: Context, private val repo: TripRepository, private val files: MediaFileStore) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("demo_seed", Context.MODE_PRIVATE)

    suspend fun seedOnce(): Int = seed(once = true)
    suspend fun seed(): Int = seed(once = false)

    private suspend fun seed(once: Boolean): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (once && prefs.getBoolean(SEEDED, false)) return@withLock 0
            // A process death during a prior import must not turn a partial demo into an existing title.
            val pending = prefs.getLong(PENDING, 0)
            if (pending != 0L) {
                repo.deleteTrip(pending)
                check(prefs.edit().remove(PENDING).commit())
            }
            val trips = app.assets.open("demo/demo_trips.json").bufferedReader().use { DemoManifest.parse(it.readText()) }
            val existing = repo.sampleTripTitles().toMutableSet()
            var added = 0
            trips.forEach { trip ->
                if (trip.title in existing) return@forEach
                val points = app.assets.open("demo/gpx/${trip.gpx}").use { GpxParser.parse(it) }
                val start = DemoTime.start(trip.daysAgo)
                val shift = start - points.first().time
                val end = points.last().time + shift
                trip.validateTimeline(start, end)
                val id = repo.createFinishedSample(trip.title, trip.from, trip.to, trip.companions,
                    start, end, points.map { LocationPoint(0, it.time + shift, it.lat, it.lng, 5f) })
                try {
                    check(prefs.edit().putLong(PENDING, id).commit())
                    var storyIndex = 0
                    trip.items.forEach { item ->
                        when (item.type) {
                            "clip" -> {
                                val meta = item.metadata(id, storyIndex++, start)
                                checkNotNull(repo.saveSampleTalk(id, meta) { target ->
                                    app.assets.open(item.audioAsset(trip.key)).use { input ->
                                        target.outputStream().use { input.copyTo(it) }
                                    }
                                    item.durationMs // Measured from decoded AAC by tools/demo/cut_demo_audio.py.
                                }) { "Demo audio insert rejected" }
                            }
                            "question" -> check(repo.addMemory(id, MemoryType.QUESTION, text = item.text,
                                createdAt = DemoTime.clamped(start, end, item.at)) > 0)
                        }
                    }
                    trip.photos.forEach { photo ->
                        val file = app.assets.open("demo/${photo.file}").use { files.copyDemoPhoto(it) }
                        try {
                            check(repo.addMemory(id, MemoryType.PHOTO, filePath = file.absolutePath,
                                createdAt = DemoTime.clamped(start, end, photo.at)) > 0)
                        } catch (failure: Throwable) {
                            withContext(NonCancellable) { files.delete(file.absolutePath) }
                            throw failure
                        }
                    }
                    check(repo.addMemory(id, MemoryType.ARRIVAL, text = trip.arrival, createdAt = end) > 0)
                    check(prefs.edit().remove(PENDING).commit())
                    existing += trip.title
                    added++
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        repo.deleteTrip(id)
                        check(prefs.edit().remove(PENDING).commit())
                    }
                    throw failure
                }
            }
            // Set only after every trip succeeded; deleted demos stay deleted on future starts.
            if (once) check(prefs.edit().putBoolean(SEEDED, true).commit())
            added
        }
    }

    private companion object {
        val mutex = Mutex()
        const val SEEDED = "demo_seeded_v1"
        const val PENDING = "demo_pending_trip_v1"
    }
}
