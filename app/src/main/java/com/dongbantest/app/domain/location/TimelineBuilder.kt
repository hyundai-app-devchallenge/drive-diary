package com.dongbantest.app.domain.location

import com.dongbantest.app.domain.model.LocationPoint
import com.dongbantest.app.domain.model.TimelineEvent
import com.dongbantest.app.domain.model.TimelineType

/**
 * 저장된 위치 목록에서 출발·정차·다시 출발·도착 이벤트를 뽑는다.
 * 기준점에서 반경 30m 안에 3분 이상 머무르면 정차로 본다. 출발 지점에서 기다린 시간은 정차로 치지 않는다.
 */
object TimelineBuilder {
    private const val MAX_ACCURACY_METERS = 30f
    private const val STOP_RADIUS_METERS = 30.0
    private const val STOP_MIN_MILLIS = 3 * 60 * 1000L

    fun build(points: List<LocationPoint>, finished: Boolean): List<TimelineEvent> {
        val good = points.filter { (it.accuracyMeters ?: 0f) <= MAX_ACCURACY_METERS }
        if (good.isEmpty()) return emptyList()

        val first = good.first()
        val events = mutableListOf(first.toEvent(TimelineType.START))
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

        if (finished) events += good.last().toEvent(TimelineType.END)
        return events
    }

    private fun LocationPoint.toEvent(type: TimelineType) =
        TimelineEvent(recordedAt, type, latitude, longitude)
}
