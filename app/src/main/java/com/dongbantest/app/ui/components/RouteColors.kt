package com.dongbantest.app.ui.components

/**
 * 기록마다 고유한 경로 색 (PRD R01: 지도 경로 색과 목록 카드 색이 서로 대응).
 * 여행 id 로 정하므로 따로 저장하지 않아도 항상 같은 색이 나온다.
 *
 * 확정안 v3 의 기록 색 세 개(TripTone: Gangneung, Jeju, Yangpyeong)와 같은 값, 같은 순서다.
 * 지도 선 색([forTrip])과 카드 점 색([toneIndex] → TripTone)이 항상 같게 나온다.
 */
object RouteColors {
    private val palette = listOf(
        "#002C5F", // 남색 (TripTone.Gangneung)
        "#0A8F7A", // 청록 (TripTone.Jeju)
        "#C26A00", // 주황 (TripTone.Yangpyeong)
    )

    /** 여행 id → 0, 1, 2 중 하나 */
    fun toneIndex(tripId: Long): Int = Math.floorMod(tripId, palette.size.toLong()).toInt()

    fun forTrip(tripId: Long): String = palette[toneIndex(tripId)]
}
