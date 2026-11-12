package com.dongbantest.app.debug

import android.content.Context
import android.util.Log
import android.util.Xml
import com.dongbantest.app.data.repository.TripRepository
import com.dongbantest.app.domain.model.LocationPoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 디버그 빌드 전용: GPX 경로를 "끝난 여행" 하나로 저장한다.
 * GPX 점을 실제 주행 기록과 같은 위치 기록(location_points)으로 넣으므로,
 * 지도·거리·함께 달린 시간이 실제 주행과 같은 코드로 계산된다.
 */
class DebugGpxImporter(private val context: Context, private val repo: TripRepository) {

    /** 예시 여행 한 건: assets/gpx 안의 파일과 화면에 보일 정보 */
    data class SampleRoute(
        val file: String,
        val title: String,
        val from: String,
        val to: String,
        val people: List<String>,
        val daysAgo: Int,
    )

    /** 팀 모두가 같은 예시를 보도록 디버그 앱에 함께 넣어 둔 경로 13개 (출발은 모두 서울시립대학교). */
    val sampleRoutes = listOf(
        SampleRoute("uos-busan.gpx", "부산 바다 여행", "서울시립대학교", "부산역", listOf("나", "엄마", "아빠"), 12),
        SampleRoute("uos-daegu.gpx", "대구 맛집 여행", "서울시립대학교", "동대구역", listOf("나", "친구1", "친구2"), 9),
        SampleRoute("uos-gangneung.gpx", "강릉 바다 드라이브", "서울시립대학교", "강릉 경포해변", listOf("나", "친구1", "친구2", "친구3"), 6),
        SampleRoute("uos-daejeon.gpx", "대전 빵 여행", "서울시립대학교", "대전역", listOf("나", "연인"), 4),
        SampleRoute("uos-pangyo.gpx", "판교 나들이", "서울시립대학교", "판교역", listOf("나", "가족1"), 2),
        SampleRoute("uos-jeonju.gpx", "전주 한옥 여행", "서울시립대학교", "전주 한옥마을", listOf("나", "친구1"), 15),
        SampleRoute("uos-yeosu.gpx", "여수 밤바다 여행", "서울시립대학교", "여수엑스포역", listOf("나", "연인"), 18),
        SampleRoute("uos-gyeongju.gpx", "경주 역사 여행", "서울시립대학교", "경주 불국사", listOf("나", "엄마", "아빠", "동생"), 21),
        SampleRoute("uos-sokcho.gpx", "속초 바다 여행", "서울시립대학교", "속초해수욕장", listOf("나", "친구1", "친구2"), 24),
        SampleRoute("uos-nami.gpx", "남이섬 나들이", "서울시립대학교", "남이섬 선착장", listOf("나", "연인"), 27),
        SampleRoute("uos-eurwangni.gpx", "을왕리 해변 드라이브", "서울시립대학교", "을왕리해수욕장", listOf("나", "친구1", "친구2"), 30),
        SampleRoute("uos-suwon.gpx", "수원 화성 산책", "서울시립대학교", "수원 화성행궁", listOf("나", "가족1", "가족2"), 33),
        SampleRoute("uos-andong.gpx", "안동 하회마을 여행", "서울시립대학교", "안동 하회마을", listOf("나", "엄마", "아빠"), 36),
    )

    /**
     * 예시 경로를 예시 여행으로 넣는다. 이미 같은 제목의 예시가 있으면 건너뛴다.
     * 예시 여행은 "예시 지우기"로 한 번에 지울 수 있다.
     * @return 새로 넣은 여행 수
     */
    suspend fun importSampleRoutes(): Int {
        val existing = repo.sampleTripTitles()
        var added = 0
        sampleRoutes.filter { it.title !in existing }.forEach { r ->
            val tripId = context.assets.open("gpx/${r.file}").use {
                import(it, r.title, r.from, r.to, r.people, r.daysAgo)
            }
            if (tripId != null) {
                repo.markSample(tripId)
                added++
            }
        }
        return added
    }

    /** GPX 를 끝난 여행으로 넣는다. days_ago 일 전 오전 9시에 출발한 것으로 시각을 옮긴다. */
    suspend fun import(
        input: InputStream,
        title: String,
        from: String?,
        to: String,
        people: List<String>,
        daysAgo: Int,
    ): Long? {
        val points = parse(input)
        if (points.size < 2) {
            Log.w(TAG, "점이 너무 적어요: $title (${points.size}개)")
            return null
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
        return tripId
    }

    private data class GpxPoint(val lat: Double, val lng: Double, val time: Long)

    /** <trkpt lat lon><time>…</time></trkpt> 만 읽는다. 시각이 없으면 3초 간격으로 채운다. */
    private fun parse(input: InputStream): List<GpxPoint> {
        val result = mutableListOf<GpxPoint>()
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
        return result
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
