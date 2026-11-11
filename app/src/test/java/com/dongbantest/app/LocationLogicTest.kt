package com.dongbantest.app

import com.dongbantest.app.domain.location.DistanceCalculator
import com.dongbantest.app.domain.location.TimelineBuilder
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.TimelineType
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationLogicTest {

    // 위도 0.0009도 ≈ 100m
    private fun point(seconds: Long, lat: Double, lng: Double = 126.97796, accuracy: Float? = 5f) =
        LocationPoint(tripId = 1, recordedAt = seconds * 1000, latitude = lat, longitude = lng, accuracyMeters = accuracy)

    @Test
    fun distance_addsRealMovement() {
        val points = listOf(point(0, 37.56654), point(10, 37.56744))
        assertEquals(100.0, DistanceCalculator.totalMeters(points), 2.0)
    }

    @Test
    fun distance_ignoresJitterWhileStopped() {
        val points = (0L..20L).map { point(it * 3, 37.56654 + if (it % 2 == 0L) 0.0 else 0.00002) } // 약 2m 흔들림
        assertEquals(0.0, DistanceCalculator.totalMeters(points), 0.001)
    }

    @Test
    fun distance_ignoresInaccuratePoints() {
        val points = listOf(point(0, 37.56654), point(10, 37.57654, accuracy = 200f), point(20, 37.56744))
        assertEquals(100.0, DistanceCalculator.totalMeters(points), 2.0)
    }

    @Test
    fun distance_recoversAfterTeleport() {
        // 에뮬레이터 기본 위치(미국) → 서울로 순간이동한 뒤 100m 이동
        val points = listOf(point(0, 37.422, -122.084), point(3, 37.56654), point(13, 37.56744))
        assertEquals(100.0, DistanceCalculator.totalMeters(points), 2.0)
    }

    @Test
    fun timeline_detectsStopAndResume() {
        val points = buildList {
            add(point(0, 37.56654))
            add(point(60, 37.56744))            // 100m 이동
            add(point(60 + 200, 37.56745))      // 같은 자리 3분 이상 → 정차
            add(point(60 + 260, 37.56844))      // 다시 100m 이동 → 다시 출발
        }
        val types = TimelineBuilder.build(points, finished = true).map { it.type }
        assertEquals(
            listOf(TimelineType.START, TimelineType.STOP, TimelineType.RESUME, TimelineType.END),
            types,
        )
    }

    @Test
    fun timeline_waitingAtStartIsNotAStop() {
        val points = listOf(point(0, 37.56654), point(300, 37.56655))
        val types = TimelineBuilder.build(points, finished = false).map { it.type }
        assertEquals(listOf(TimelineType.START), types)
    }
}
