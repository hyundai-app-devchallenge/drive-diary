package com.dongbantest.app

import com.dongbantest.app.domain.location.DistanceCalculator
import com.dongbantest.app.domain.location.RoutePath
import com.dongbantest.app.domain.location.TimelineBuilder
import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.TimelineType
import com.dongbantest.app.domain.model.VehicleSignal
import com.dongbantest.app.domain.model.VehicleSignalType
import com.dongbantest.app.domain.model.VehicleSignalType.DOOR_OPEN
import com.dongbantest.app.domain.model.VehicleSignalType.DRIVER_SEAT_EMPTY
import com.dongbantest.app.domain.model.VehicleSignalType.GEAR_NOT_PARKED
import com.dongbantest.app.domain.model.VehicleSignalType.PARKED
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

    // ---- 차량 신호(기어·운전석·문)로 정차 판단 ----

    private fun signal(seconds: Long, type: VehicleSignalType) =
        VehicleSignal(tripId = 1, recordedAt = seconds * 1000, type = type)

    /** 0초 출발 → 10분 동안 북쪽으로 조금씩 이동하는 위치 기록 */
    private val drivingPoints = (0L..20L).map { point(it * 30, 37.56654 + it * 0.0009) }

    private fun types(signals: List<VehicleSignal>, finished: Boolean = true) =
        TimelineBuilder.build(drivingPoints, finished, signals).map { it.type }

    @Test
    fun vehicle_parkedLongEnoughIsAStop() {
        val signals = listOf(
            signal(0, PARKED), signal(10, GEAR_NOT_PARKED),     // 출발
            signal(120, PARKED), signal(120 + 150, GEAR_NOT_PARKED), // P 2분 30초 → 정차
        )
        assertEquals(
            listOf(TimelineType.START, TimelineType.STOP, TimelineType.RESUME, TimelineType.END),
            types(signals),
        )
    }

    @Test
    fun vehicle_shortParkWithoutExitIsIgnored() {
        val signals = listOf(signal(10, GEAR_NOT_PARKED), signal(120, PARKED), signal(150, GEAR_NOT_PARKED))
        assertEquals(listOf(TimelineType.START, TimelineType.END), types(signals))
    }

    @Test
    fun vehicle_shortParkWithDriverOutIsAStop() {
        val signals = listOf(
            signal(10, GEAR_NOT_PARKED),
            signal(120, PARKED), signal(130, DRIVER_SEAT_EMPTY), signal(160, GEAR_NOT_PARKED),
        )
        assertEquals(
            listOf(TimelineType.START, TimelineType.STOP, TimelineType.RESUME, TimelineType.END),
            types(signals),
        )
    }

    @Test
    fun vehicle_doorOpenWhileNotParkedIsIgnored() {
        val signals = listOf(signal(10, GEAR_NOT_PARKED), signal(120, DOOR_OPEN))
        assertEquals(listOf(TimelineType.START, TimelineType.END), types(signals))
    }

    @Test
    fun vehicle_gpsOnlyStopIsHiddenWhenGearIsKnown() {
        // GPS 로는 3분 넘게 멈췄지만(신호 대기·정체) 기어는 계속 D → 정차로 보지 않는다.
        val points = listOf(point(0, 37.56654), point(60, 37.56744), point(300, 37.56745), point(360, 37.56844))
        val signals = listOf(signal(10, GEAR_NOT_PARKED))
        val result = TimelineBuilder.build(points, finished = true, signals = signals).map { it.type }
        assertEquals(listOf(TimelineType.START, TimelineType.END), result)
    }

    @Test
    fun vehicle_parkBeforeDepartureAndAfterArrivalAreNotStops() {
        val signals = listOf(
            signal(0, PARKED), signal(5, DOOR_OPEN),          // 출발 전 주차, 사람이 탐
            signal(200, GEAR_NOT_PARKED),
            signal(500, PARKED), signal(510, DRIVER_SEAT_EMPTY), // 도착 후 주차
        )
        assertEquals(listOf(TimelineType.START, TimelineType.END), types(signals))
    }

    @Test
    fun vehicle_currentlyParkedDuringOngoingTripShowsStop() {
        val signals = listOf(signal(10, GEAR_NOT_PARKED), signal(300, PARKED), signal(310, DOOR_OPEN))
        assertEquals(listOf(TimelineType.START, TimelineType.STOP), types(signals, finished = false))
    }

    // ---- 지도 경로 ----

    @Test
    fun routePath_splitsAtTeleport() {
        // 미국 → 서울 순간이동: 선을 이어 그리지 않고 구간을 나눈다.
        val points = listOf(point(0, 37.422, -122.084), point(3, 37.56654), point(13, 37.56744))
        val segments = RoutePath.segments(points)
        assertEquals(listOf(1, 2), segments.map { it.size })
    }

    @Test
    fun routePath_dropsJitterAndInaccuratePoints() {
        val points = listOf(
            point(0, 37.56654),
            point(3, 37.56655),                 // 약 1m 흔들림 → 건너뜀
            point(6, 37.57654, accuracy = 200f), // 오차 큼 → 버림
            point(9, 37.56744),
        )
        assertEquals(listOf(2), RoutePath.segments(points).map { it.size })
    }
}
