package com.dongbantest.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Xml
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.domain.model.LocationPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 디버그 빌드 전용: GPX 경로 파일을 "끝난 여행" 하나로 넣는다.
 * 에뮬레이터에서 몇 시간씩 주행을 재생하지 않고도, 도로를 따라가는 실제 모양의 경로로
 * 기록 지도(R01)·다이어리(S14)를 확인하려고 만들었다.
 *
 * 1) GPX 파일을 앱 전용 폴더(files/gpx)에 넣는다.
 *    adb shell run-as com.dongbantest.app --user 10 sh -c 'mkdir -p files/gpx && cat > files/gpx/uos-busan.gpx' < uos-busan.gpx
 * 2) 여행으로 넣는다. days_ago 만큼 전 날짜의 오전 9시에 출발한 것으로 저장한다.
 *    adb shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_IMPORT_GPX -p com.dongbantest.app \
 *        --es file uos-busan.gpx --es title "부산 바다 여행" --es from "서울시립대학교" --es to "부산역" \
 *        --es people "나,친구1,친구2" --ei days_ago 3
 */
class DebugGpxImportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val fileName = intent.getStringExtra("file") ?: return
        val title = intent.getStringExtra("title") ?: fileName.removeSuffix(".gpx")
        val from = intent.getStringExtra("from")
        val to = intent.getStringExtra("to") ?: title
        val people = intent.getStringExtra("people")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("나")
        val daysAgo = intent.getIntExtra("days_ago", 1)

        val app = context.applicationContext as DongbanApplication
        val repo = app.container.tripRepository
        val pending = goAsync()
        scope.launch {
            try {
                val file = File(context.filesDir, "gpx/$fileName")
                val points = parseGpx(file)
                if (points.size < 2) {
                    Log.w(TAG, "점이 너무 적어요: $file (${points.size}개)")
                    return@launch
                }
                // GPX 의 첫 시각을 "days_ago 일 전 오전 9시"로 옮긴다. 점 사이 간격은 그대로 둔다.
                val start = startOfDay(daysAgo) + TimeUnit.HOURS.toMillis(9)
                val shift = start - points.first().time
                val tripId = repo.startTrip(title, to, people, odometer = null, odometerSource = null, now = start)
                points.forEach { p ->
                    repo.addLocationPoint(LocationPoint(tripId, p.time + shift, p.lat, p.lng, accuracyMeters = 5f))
                }
                from?.let { repo.setStartPlace(tripId, it) }
                repo.setEndPlace(tripId, to)
                repo.endTrip(tripId, odometer = null, now = points.last().time + shift)
                Log.i(TAG, "GPX 여행 넣기 완료: tripId=$tripId, $title, 점 ${points.size}개")
            } catch (e: Exception) {
                Log.e(TAG, "GPX 여행 넣기 실패: $fileName", e)
            } finally {
                pending.finish()
            }
        }
    }

    private data class GpxPoint(val lat: Double, val lng: Double, val time: Long)

    /** <trkpt lat lon><time>…</time></trkpt> 만 읽는다. 시각이 없으면 3초 간격으로 채운다. */
    private fun parseGpx(file: File): List<GpxPoint> {
        val result = mutableListOf<GpxPoint>()
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            var lat = 0.0
            var lng = 0.0
            var time: Long? = null
            var inPoint = false
            var inTime = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "trkpt" -> {
                            inPoint = true
                            lat = parser.getAttributeValue(null, "lat").toDouble()
                            lng = parser.getAttributeValue(null, "lon").toDouble()
                            time = null
                        }
                        "time" -> inTime = inPoint
                    }
                    XmlPullParser.TEXT -> if (inTime) time = runCatching { Instant.parse(parser.text.trim()).toEpochMilli() }.getOrNull()
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "time" -> inTime = false
                        "trkpt" -> {
                            inPoint = false
                            val t = time ?: (result.lastOrNull()?.time?.plus(3_000) ?: 0L)
                            result += GpxPoint(lat, lng, t)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun startOfDay(daysAgo: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private companion object {
        const val TAG = "DebugGpxImport"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
