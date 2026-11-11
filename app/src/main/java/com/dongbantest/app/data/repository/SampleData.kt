package com.dongbantest.app.data.repository

import com.dongbantest.app.data.local.LocationPointEntity
import com.dongbantest.app.data.local.MemoryEntity
import com.dongbantest.app.data.local.TripEntity
import com.dongbantest.app.domain.model.DataSourceType
import com.dongbantest.app.domain.model.MemoryType
import java.util.Calendar
import kotlin.math.hypot

/**
 * 테스트용 예시 여행 기록. isSample = true 로 저장되어 실제 사용자 기록과 구분된다.
 * 주행거리 값은 Mock 출처로 표시된다. 기록 지도 확인용 예시 경로도 함께 넣는다.
 */
internal object SampleData {

    class SampleTrip(val trip: TripEntity, val memories: List<MemoryEntity>, val route: List<LocationPointEntity>)

    fun trips(): List<SampleTrip> = listOf(
        sample(
            title = "가족과 함께한 속초 여행",
            destination = "속초 영금정",
            companions = "엄마,아빠,지우,지호",
            daysAgo = 40, hours = 9,
            odometer = 12_480f to 12_690f,
            // 서울 → 하남 → 양평 → 홍천 → 인제 → 속초
            waypoints = listOf(
                37.5665 to 126.9780, 37.5390 to 127.2140, 37.4917 to 127.4875,
                37.6970 to 127.8880, 38.0690 to 128.1700, 38.2137 to 128.6007,
            ),
            memories = listOf(
                MemoryType.QUESTION to "오늘 여행에서 가장 기대되는 건 뭐야?",
                MemoryType.TEXT to "지호가 바다 보자마자 '파도가 춤춘다!'라고 외쳤다.",
                MemoryType.ARRIVAL to "속초 영금정 도착! 바람이 시원했다.",
                MemoryType.TEXT to "닭강정은 다음에도 꼭 다시 먹기로 약속.",
            ),
        ),
        sample(
            title = "연인과 함께한 제주도 여행",
            destination = "제주 협재해수욕장",
            companions = "민지,현우",
            daysAgo = 20, hours = 6,
            odometer = 30_105f to 30_188f,
            // 제주공항 → 애월 → 협재
            waypoints = listOf(33.5067 to 126.4930, 33.4626 to 126.3094, 33.3940 to 126.2397),
            memories = listOf(
                MemoryType.QUESTION to "우리가 처음 함께 여행했던 날 기억나?",
                MemoryType.TEXT to "노을 질 때 차 안에서 같은 노래를 따라 불렀다.",
                MemoryType.ARRIVAL to "협재 도착. 에메랄드빛 바다!",
            ),
        ),
        sample(
            title = "친구들과의 강릉 드라이브",
            destination = "강릉 안목 커피거리",
            companions = "수빈,도윤,하린",
            daysAgo = 5, hours = 4,
            odometer = 8_002f to 8_231f,
            // 서울 → 이천 → 원주 → 평창 → 강릉 안목
            waypoints = listOf(
                37.5665 to 126.9780, 37.2720 to 127.4350, 37.3420 to 127.9200,
                37.5610 to 128.4100, 37.7716 to 128.9475,
            ),
            memories = listOf(
                MemoryType.QUESTION to "다음에는 우리 어디로 떠나볼까?",
                MemoryType.TEXT to "휴게소 감자 3개를 순식간에 해치움.",
                MemoryType.ARRIVAL to "안목해변 커피 한 잔.",
                MemoryType.TEXT to "다음 여행은 전주 한옥마을로 결정!",
            ),
        ),
    )

    private fun sample(
        title: String,
        destination: String,
        companions: String,
        daysAgo: Int,
        hours: Int,
        odometer: Pair<Float, Float>,
        waypoints: List<Pair<Double, Double>>,
        memories: List<Pair<MemoryType, String>>,
    ): SampleTrip {
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
        val end = start + hours * 60 * 60 * 1000L
        val step = (end - start) / (memories.size + 1)
        return SampleTrip(
            trip = TripEntity(
                title = title,
                destination = destination,
                companions = companions,
                startedAt = start,
                endedAt = end,
                startOdometer = odometer.first,
                endOdometer = odometer.second,
                odometerSource = DataSourceType.MOCK.name,
                isSample = true,
            ),
            memories = memories.mapIndexed { i, (type, text) ->
                MemoryEntity(
                    tripId = 0,
                    type = type.name,
                    createdAt = start + step * (i + 1),
                    text = text,
                    filePath = null,
                    transcript = null,
                    durationMs = null,
                )
            },
            route = routeAlong(waypoints, start, end),
        )
    }

    /**
     * 경유지 사이를 직선으로 잘게 나눠 GPS 기록처럼 만든다 (약 300m 간격).
     * 기록 지도(R01)에서 여행마다 색 경로가 보이는지 확인하기 위한 예시다. 실제 도로를 따르지 않는다.
     */
    private fun routeAlong(waypoints: List<Pair<Double, Double>>, start: Long, end: Long): List<LocationPointEntity> {
        val coords = mutableListOf(waypoints.first())
        waypoints.zipWithNext { (lat1, lng1), (lat2, lng2) ->
            val steps = maxOf(1, (hypot(lat2 - lat1, lng2 - lng1) / 0.003).toInt())
            for (i in 1..steps) {
                val t = i.toDouble() / steps
                coords += (lat1 + (lat2 - lat1) * t) to (lng1 + (lng2 - lng1) * t)
            }
        }
        val interval = (end - start) / coords.size
        return coords.mapIndexed { i, (lat, lng) ->
            LocationPointEntity(
                tripId = 0,
                recordedAt = start + interval * i,
                latitude = lat,
                longitude = lng,
                accuracyMeters = 5f,
            )
        }
    }
}
