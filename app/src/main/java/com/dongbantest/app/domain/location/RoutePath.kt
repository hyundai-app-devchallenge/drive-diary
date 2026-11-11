package com.dongbantest.app.domain.location

import com.dongbantest.app.domain.model.LocationPoint

/**
 * 지도에 그릴 경로. [DistanceCalculator] 와 같은 기준으로 점을 고른다.
 * - 오차가 큰 점(30m 초과)은 버린다.
 * - 5m 미만 흔들림은 건너뛴다 (선이 지저분해지지 않게).
 * - 초속 70m 를 넘는 점프(위치가 튄 것)가 있으면 선을 끊고 새 구간을 시작한다.
 */
object RoutePath {
    private const val MAX_ACCURACY_METERS = 30f
    private const val MIN_STEP_METERS = 5.0
    private const val MAX_SPEED_MPS = 70.0

    fun segments(points: List<LocationPoint>): List<List<LocationPoint>> {
        val result = mutableListOf<MutableList<LocationPoint>>()
        var current = mutableListOf<LocationPoint>()

        for (point in points) {
            val accuracy = point.accuracyMeters
            if (accuracy != null && accuracy > MAX_ACCURACY_METERS) continue

            val prev = current.lastOrNull()
            if (prev == null) {
                current += point
                continue
            }
            val step = distanceMeters(prev.latitude, prev.longitude, point.latitude, point.longitude)
            val seconds = (point.recordedAt - prev.recordedAt) / 1000.0
            if (seconds > 0 && step / seconds > MAX_SPEED_MPS) {
                result += current
                current = mutableListOf(point)
            } else if (step >= MIN_STEP_METERS) {
                current += point
            }
        }
        result += current
        return result.filter { it.isNotEmpty() }
    }
}
