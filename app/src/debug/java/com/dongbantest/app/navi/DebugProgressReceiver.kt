package com.dongbantest.app.navi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dongbantest.app.DongbanApplication

/**
 * 디버그 빌드 전용: 에뮬레이터에서 길 리본 진행률(차 아이콘 위치)을 흉내 낸다.
 * 에뮬레이터 내비는 위치가 움직이지 않아 남은 거리가 줄지 않기 때문이다.
 * 내비에 목적지가 찍혀 있어야 보인다.
 *
 * ■ 50% 지점으로 옮기기
 *    adb shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_PROGRESS \
 *        -n com.dongbantest.app/.navi.DebugProgressReceiver --ei percent 50
 *
 * ■ 100 을 보내면 도착 신호도 함께 보내 도착 화면이 뜬다 (DEBUG_ARRIVAL 과 같다).
 *
 * ■ 흉내 끄기 (내비 값으로 돌아가기): percent 없이 보낸다.
 */
class DebugProgressReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val percent = if (intent.hasExtra("percent")) intent.getIntExtra("percent", 0) else null
        val app = context.applicationContext as DongbanApplication
        val naviMonitor = app.container.naviMonitor
        naviMonitor.simulateProgress(percent)
        // 실제 차에서는 목적지에 닿으면 내비가 도착 신호를 따로 보낸다. 100% 가 되면 그 신호도 흉내 낸다.
        if (percent != null && percent >= 100) {
            naviMonitor.simulateArrival(naviMonitor.route.value?.destinationName.orEmpty())
        }
    }
}
