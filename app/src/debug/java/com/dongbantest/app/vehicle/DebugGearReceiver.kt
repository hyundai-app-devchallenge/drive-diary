package com.dongbantest.app.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dongbantest.app.AppContainer
import com.dongbantest.app.DongbanApplication
import com.dongbantest.app.domain.model.VehicleSignalType

/**
 * 디버그 빌드 전용: 에뮬레이터에서 기어 D/P 를 흉내 낸다.
 * Pleos 에뮬레이터는 Virtual Console 에서 기어를 바꿔도 계기판·앱까지 전달되지 않기 때문이다.
 *
 * - D: 화면이 "주행 중"으로 바뀌고, 기어가 P 가 아니라는 신호를 기록한다 (정차 뒤면 "다시 출발").
 * - P: 화면이 "정차 중"으로 바뀌고, 기어 P 신호를 기록한다 (2분 넘으면 정차, 목적지 300m 안이면 도착).
 * - off: 화면의 주행/정차 표시를 차량 값으로 되돌린다.
 *
 *    adb shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_GEAR \
 *        -n com.dongbantest.app/.vehicle.DebugGearReceiver --es gear D
 */
class DebugGearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as DongbanApplication).container
        DebugGear.entries.firstOrNull { it.name.equals(intent.getStringExtra("gear"), ignoreCase = true) }
            ?.apply(container)
    }
}

internal enum class DebugGear(private val driving: Boolean?, private val signal: VehicleSignalType?) {
    D(true, VehicleSignalType.GEAR_NOT_PARKED),
    P(false, VehicleSignalType.PARKED),
    OFF(null, null);

    fun apply(container: AppContainer) {
        container.drivingSafetyMonitor.simulateDriving(driving)
        signal?.let(container.vehicleSignalTracker::simulate)
    }
}
