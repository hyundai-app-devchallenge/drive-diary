package com.dongbantest.app.navi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dongbantest.app.DongbanApplication

/**
 * 디버그 빌드 전용: 에뮬레이터에서 내비 도착을 흉내 낸다.
 * 에뮬레이터 내비는 위치가 움직이지 않아 실제 도착 이벤트를 만들 수 없기 때문이다.
 *
 * adb shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_ARRIVAL \
 *     -n com.dongbantest.app/.navi.DebugArrivalReceiver --es name "판교테크노밸리"
 */
class DebugArrivalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val app = context.applicationContext as DongbanApplication
        app.container.naviMonitor.simulateArrival(name)
    }
}
