package com.dongbantest.app.domain.location

import com.dongbantest.app.domain.model.LocationPoint

/**
 * 저장된 위치 목록으로 주행거리(미터)를 계산한다.
 * - 오차가 큰 점(30m 초과)은 버린다.
 * - 5m 미만 이동은 GPS 흔들림으로 보고 더하지 않는다 (기준점도 유지해서 천천히 움직인 거리는 잃지 않는다).
 * - 초속 70m(약 250km/h)를 넘는 점프는 버리고, 그 점을 새 기준점으로 삼는다.
 */
object DistanceCalculator {
    private const val MAX_ACCURACY_METERS = 30f
    private const val MIN_STEP_METERS = 5.0
    private const val MAX_SPEED_MPS = 70.0

    fun totalMeters(points: List<LocationPoint>): Double {
        var total = 0.0
        var previous: LocationPoint? = null

        for (point in points) {
            val accuracy = point.accuracyMeters
            if (accuracy != null && accuracy > MAX_ACCURACY_METERS) continue

            val prev = previous
            if (prev == null) {
                previous = point
                continue
            }

            val step = distanceMeters(prev.latitude, prev.longitude, point.latitude, point.longitude)

            val seconds = (point.recordedAt - prev.recordedAt) / 1000.0
            if (seconds > 0 && step / seconds > MAX_SPEED_MPS) {
                previous = point
                continue
            }

            if (step >= MIN_STEP_METERS) {
                total += step
                previous = point
            }
        }
        return total
    }
}
