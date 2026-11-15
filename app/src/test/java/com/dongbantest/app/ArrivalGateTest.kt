package com.dongbantest.app

import com.dongbantest.app.domain.location.ArrivalGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalGateTest {

    @Test
    fun parkNearDestinationEndsTrip() {
        assertTrue(ArrivalGate().shouldEndOnPark(tripId = 1, distanceToDestination = 120.0))
    }

    @Test
    fun parkFarFromDestinationDoesNotEnd() {
        assertFalse(ArrivalGate().shouldEndOnPark(tripId = 1, distanceToDestination = 2_000.0))
    }

    @Test
    fun unknownLocationDoesNotEnd() {
        assertFalse(ArrivalGate().shouldEndOnPark(tripId = 1, distanceToDestination = null))
    }

    @Test
    fun keepTravelingBlocksUntilLeavingDestination() {
        val gate = ArrivalGate()
        gate.keepTraveling(1)
        // 목적지 근처에서 다시 주차해도 끝내지 않는다.
        assertFalse(gate.shouldEndOnPark(1, 150.0))
        assertFalse(gate.shouldEndOnNaviArrival(1))
        // 1km 넘게 벗어났다가
        assertFalse(gate.shouldEndOnPark(1, 5_000.0))
        // 다시 목적지 근처에 주차하면 끝낸다.
        assertTrue(gate.shouldEndOnPark(1, 150.0))
        assertTrue(gate.shouldEndOnNaviArrival(1))
    }

    @Test
    fun destinationChangedClearsKeptTraveling() {
        val gate = ArrivalGate()
        gate.keepTraveling(1)
        assertFalse(gate.shouldEndOnNaviArrival(1))
        gate.destinationChanged()
        assertTrue(gate.shouldEndOnNaviArrival(1))
        assertTrue(gate.shouldEndOnPark(1, 150.0))
    }

    @Test
    fun destinationChangedWithoutKeptTripIsHarmless() {
        val gate = ArrivalGate()
        gate.destinationChanged()
        assertTrue(gate.shouldEndOnNaviArrival(1))
    }

    @Test
    fun keepTravelingOnlyAffectsThatTrip() {
        val gate = ArrivalGate()
        gate.keepTraveling(1)
        assertTrue(gate.shouldEndOnPark(2, 100.0))
    }
}
