package com.dongbantest.app.ui.components

/**
 * 기록마다 고유한 경로 색 (PRD R01: 지도 경로 색과 목록 카드 색이 서로 대응).
 * 여행 id 로 정하므로 따로 저장하지 않아도 항상 같은 색이 나온다.
 */
object RouteColors {
    private val palette = listOf(
        "#1E88E5", // 파랑
        "#E53935", // 빨강
        "#43A047", // 초록
        "#8E24AA", // 보라
        "#FB8C00", // 주황
        "#00ACC1", // 청록
        "#D81B60", // 분홍
        "#6D4C41", // 갈색
    )

    fun forTrip(tripId: Long): String = palette[Math.floorMod(tripId, palette.size.toLong()).toInt()]
}
