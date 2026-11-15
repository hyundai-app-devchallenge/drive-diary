package com.dongbantest.app.domain.location

/**
 * 목적지 도착으로 여행을 자동 종료할지 정한다 (PRD S11: 목적지 반경 도달 + 정차).
 *
 * - 목적지 [arriveRadiusMeters] 안에서 주차(기어 P)하면 도착.
 * - 내비가 도착 신호를 보내도 도착.
 * - 사용자가 "아직 여행 중이에요"로 되돌린 여행은, 목적지에서 [leaveRadiusMeters] 넘게
 *   벗어나기 전까지 다시 자동 종료하지 않는다 (목적지 주차장 안에서 또 끝나는 것을 막는다).
 */
class ArrivalGate(
    private val arriveRadiusMeters: Double = 300.0,
    private val leaveRadiusMeters: Double = 1_000.0,
) {
    private val keptTraveling = mutableSetOf<Long>()

    /** 주차했을 때. [distanceToDestination] 은 마지막 위치와 목적지 사이 거리(미터), 모르면 null. */
    @Synchronized
    fun shouldEndOnPark(tripId: Long, distanceToDestination: Double?): Boolean {
        if (distanceToDestination == null) return false
        if (tripId in keptTraveling) {
            if (distanceToDestination > leaveRadiusMeters) keptTraveling -= tripId
            return false
        }
        return distanceToDestination <= arriveRadiusMeters
    }

    /** 내비가 도착 신호를 보냈을 때. */
    @Synchronized
    fun shouldEndOnNaviArrival(tripId: Long): Boolean = tripId !in keptTraveling

    /** 내비 목적지가 다른 곳으로 바뀌었을 때: 이전에 "아직 여행 중"이던 여행도 새 목적지에서는 끝낼 수 있다. */
    @Synchronized
    fun destinationChanged() {
        keptTraveling.clear()
    }

    /** "아직 여행 중이에요"를 눌렀을 때. */
    @Synchronized
    fun keepTraveling(tripId: Long) {
        keptTraveling += tripId
    }
}
