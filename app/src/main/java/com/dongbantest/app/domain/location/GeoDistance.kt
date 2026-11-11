package com.dongbantest.app.domain.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 위도·경도 두 점 사이 거리(미터). 하버사인 공식.
 * Android 의 Location.distanceBetween 대신 순수 Kotlin 으로 계산해서 단위 테스트에서도 동작한다.
 */
internal fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * earthRadius * asin(sqrt(a))
}
