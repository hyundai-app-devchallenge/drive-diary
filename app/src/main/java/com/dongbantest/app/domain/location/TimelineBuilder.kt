package com.dongbantest.app.domain.location

import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.TimelineEvent
import com.dongbantest.app.domain.model.TimelineType
import com.dongbantest.app.domain.model.VehicleSignal
import com.dongbantest.app.domain.model.VehicleSignalType
import kotlin.math.abs

/**
 * 저장된 위치 목록과 차량 신호로 출발·정차·다시 출발·도착 이벤트를 만든다.
 *
 * 정차 판단 (기어 신호가 있을 때):
 *  - 기어 P 가 2분 넘게 유지되거나,
 *  - 기어 P 인 동안 운전석이 비거나 / 운전석 안전벨트가 풀리거나 / 문이 열리면 "정차"로 본다.
 *  - 기어 P 가 아닌 채로 멈춘 것(신호 대기·정체)은 타임라인에 넣지 않는다.
 *  - 출발 전 주차, 도착 후 주차는 정차로 치지 않는다 (출발·도착으로 이미 표시됨).
 *
 * 기어 신호가 하나도 없으면(차량 신호를 못 받는 기기) 예전 GPS 규칙을 쓴다:
 *  기준점에서 반경 30m 안에 3분 이상 머무르면 정차. 출발 지점에서 기다린 시간은 정차로 치지 않는다.
 */
object TimelineBuilder {
    private const val MAX_ACCURACY_METERS = 30f
    private const val STOP_RADIUS_METERS = 30.0
    private const val STOP_MIN_MILLIS = 3 * 60 * 1000L
    const val PARK_CONFIRM_MILLIS = 2 * 60 * 1000L

    private val EXIT_SIGNALS = setOf(
        VehicleSignalType.DRIVER_SEAT_EMPTY,
        VehicleSignalType.DRIVER_BELT_OFF,
        VehicleSignalType.DOOR_OPEN,
    )

    fun build(
        points: List<LocationPoint>,
        finished: Boolean,
        signals: List<VehicleSignal> = emptyList(),
    ): List<TimelineEvent> {
        val good = points.filter { (it.accuracyMeters ?: 0f) <= MAX_ACCURACY_METERS }
        if (good.isEmpty()) return emptyList()

        val hasGear = signals.any { it.type == VehicleSignalType.PARKED || it.type == VehicleSignalType.GEAR_NOT_PARKED }
        val middle = if (hasGear) stopsFromVehicle(good, signals, finished) else stopsFromGps(good)

        val events = mutableListOf(good.first().toEvent(TimelineType.START))
        events += middle
        if (finished) events += good.last().toEvent(TimelineType.END)
        return events
    }

    /** 기어 P 구간마다 정차 여부를 판단한다. */
    private fun stopsFromVehicle(
        points: List<LocationPoint>,
        signals: List<VehicleSignal>,
        finished: Boolean,
    ): List<TimelineEvent> {
        val sorted = signals.sortedBy { it.recordedAt }
        val lastKnownTime = maxOf(points.last().recordedAt, sorted.lastOrNull()?.recordedAt ?: 0L)
        val events = mutableListOf<TimelineEvent>()
        var hasMoved = false      // 기어를 P 가 아닌 곳으로 넣은 적이 있는지 (출발 전 주차 구분)
        var parkStart: Long? = null

        fun closePark(end: Long?) {
            val start = parkStart ?: return
            parkStart = null
            if (!hasMoved) return                   // 출발 전 주차
            if (end == null && finished) return     // 도착 후 주차 → 도착으로 표시됨
            val until = end ?: lastKnownTime
            val longEnough = until - start >= PARK_CONFIRM_MILLIS
            val someoneGotOut = sorted.any { it.type in EXIT_SIGNALS && it.recordedAt in start..until }
            if (!longEnough && !someoneGotOut) return
            events += points.nearestTo(start).toEvent(TimelineType.STOP, start)
            if (end != null) events += points.nearestTo(end).toEvent(TimelineType.RESUME, end)
        }

        for (signal in sorted) {
            when (signal.type) {
                VehicleSignalType.PARKED -> if (parkStart == null) parkStart = signal.recordedAt
                VehicleSignalType.GEAR_NOT_PARKED -> {
                    closePark(signal.recordedAt)
                    hasMoved = true
                }
                else -> Unit
            }
        }
        closePark(null)
        return events
    }

    /** 예전 규칙: GPS 로 반경 30m 안에 3분 이상 머무르면 정차. */
    private fun stopsFromGps(good: List<LocationPoint>): List<TimelineEvent> {
        val first = good.first()
        val events = mutableListOf<TimelineEvent>()
        var anchor = first
        var stopped = false

        for (point in good.drop(1)) {
            val distance = distanceMeters(anchor.latitude, anchor.longitude, point.latitude, point.longitude)

            if (distance <= STOP_RADIUS_METERS) {
                val stayedLongEnough = point.recordedAt - anchor.recordedAt >= STOP_MIN_MILLIS
                if (!stopped && anchor != first && stayedLongEnough) {
                    events += anchor.toEvent(TimelineType.STOP)
                    stopped = true
                }
            } else {
                if (stopped) events += point.toEvent(TimelineType.RESUME)
                stopped = false
                anchor = point
            }
        }
        return events
    }

    private fun List<LocationPoint>.nearestTo(time: Long): LocationPoint =
        minBy { abs(it.recordedAt - time) }

    private fun LocationPoint.toEvent(type: TimelineType, time: Long = recordedAt) =
        TimelineEvent(time, type, latitude, longitude)
}
