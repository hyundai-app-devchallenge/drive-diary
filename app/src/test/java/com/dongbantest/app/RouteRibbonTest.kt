package com.dongbantest.app

import com.dongbantest.app.domain.model.RouteRibbon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteRibbonTest {

    @Test
    fun progressIsTravelledShareOfTotal() {
        // 전체 2,300m 중 800m 남음 → 약 65% 왔다.
        assertEquals(0.652f, RouteRibbon.progressOf(2_300, 800)!!, 0.001f)
    }

    @Test
    fun progressAtStartAndArrival() {
        assertEquals(0f, RouteRibbon.progressOf(2_300, 2_300)!!, 0f)
        assertEquals(1f, RouteRibbon.progressOf(2_300, 0)!!, 0f)
    }

    @Test
    fun progressIsClampedWhenRerouteMakesItLonger() {
        // 길을 잘못 들어 남은 거리가 처음보다 길어져도 0 아래로 내려가지 않는다.
        assertEquals(0f, RouteRibbon.progressOf(2_300, 3_000)!!, 0f)
    }

    @Test
    fun noProgressWithoutRoute() {
        assertNull(RouteRibbon.progressOf(null, 500))
        assertNull(RouteRibbon.progressOf(0, 0))
        assertNull(RouteRibbon.progressOf(2_300, null))
    }
}
