package com.dongbantest.app.domain.model

/**
 * 길 리본(PRD S05 진행 카드, 기록 카드): 출발지 → 목적지 진행 막대에 필요한 값.
 *
 * @property progress 0.0(출발) ~ 1.0(도착). 목적지가 없으면 null 이라 막대를 채우지 않는다.
 * @property remainMeters 목적지까지 남은 거리. 모르면 null.
 * @property remainSeconds 목적지까지 남은 시간(내비 예상). 모르면 null.
 */
data class RouteRibbon(
    val originName: String?,
    val destinationName: String?,
    val startedAt: Long,
    val progress: Float?,
    val remainMeters: Int?,
    val remainSeconds: Int?,
    val companionCount: Int,
) {
    companion object {
        /**
         * 진행률 = 1 - 남은 거리 / 전체 거리. 0~1 사이로 자른다.
         * 전체 거리를 모르거나 0이면 null.
         */
        fun progressOf(totalMeters: Int?, remainMeters: Int?): Float? {
            if (totalMeters == null || remainMeters == null || totalMeters <= 0) return null
            return (1f - remainMeters.toFloat() / totalMeters).coerceIn(0f, 1f)
        }
    }
}
