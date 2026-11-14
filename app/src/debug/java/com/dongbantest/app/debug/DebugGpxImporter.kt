package com.dongbantest.app.debug

import android.content.Context
import android.util.Log
import com.dongbantest.app.data.demo.GpxParser
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.domain.model.LocationPoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 디버그 빌드 전용: 내 GPX 파일을 "끝난 여행" 하나로 저장한다.
 * GPX 점을 실제 주행 기록과 같은 위치 기록(location_points)으로 넣으므로,
 * 지도·거리·함께 달린 시간이 실제 주행과 같은 코드로 계산된다.
 * (앱에 들어 있는 데모 여행 3개는 main 의 DemoSeeder 가 넣는다.)
 */
class DebugGpxImporter(private val context: Context, private val repo: TripRepository) {

    /** GPX 를 끝난 여행으로 넣는다. days_ago 일 전 오전 9시에 출발한 것으로 시각을 옮긴다. */
    suspend fun import(
        input: InputStream,
        title: String,
        from: String?,
        to: String,
        people: List<String>,
        daysAgo: Int,
    ): Long {
        val points = GpxParser.parse(input)
        // GPX 의 첫 시각을 "days_ago 일 전 오전 9시"로 옮긴다. 점 사이 간격은 그대로 둔다.
        val start = startOfDay(daysAgo) + TimeUnit.HOURS.toMillis(9)
        val shift = start - points.first().time
        val tripId = repo.startTrip(title, to, people, odometer = null, odometerSource = null, now = start)
        try {
            val locations = points.map { p ->
                LocationPoint(tripId, p.time + shift, p.lat, p.lng, accuracyMeters = 5f)
            }
            locations.forEach { repo.addLocationPoint(it) }
            from?.let { repo.setStartPlace(tripId, it) }
            repo.setEndPlace(tripId, to)
            repo.endTrip(tripId, odometer = null, now = locations.last().recordedAt)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { repo.deleteTrip(tripId) }
            throw failure
        }
        Log.i(TAG, "GPX import complete: tripId=$tripId, points=${points.size}")
        return tripId
    }

    private fun startOfDay(daysAgo: Int): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private companion object {
        const val TAG = "DebugGpxImport"
    }
}
