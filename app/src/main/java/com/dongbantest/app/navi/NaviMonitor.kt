package com.dongbantest.app.navi

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/**
 * Pleos NaviHelper 연결.
 *
 * 1단계(연결 확인)에서는 내비가 보내는 값을 로그로만 남긴다.
 * 확인 명령: adb logcat -s NaviMonitor
 */
class NaviMonitor(context: Context) {

    private val naviHelper = NaviHelper(context.applicationContext)
    private var started = false

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            Log.i(TAG, "현재 위치: $currentLocationInfo")
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            Log.i(TAG, "목적지: $destinationInfo")
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            Log.i(TAG, "경로 상태: $routeStateInfo")
        }

        override fun onDestinationChanged() {
            Log.i(TAG, "목적지가 바뀜 → 목적지 다시 조회")
            naviHelper.getDestinationInfo()
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "길 안내 시작: $routeStartInfo")
        }

        override fun onRouteEnded() {
            Log.i(TAG, "길 안내 끝남")
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "길 안내 취소")
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "목적지 도착: $destinationArrivedInfo")
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            Log.i(TAG, "내비 상태: $status")
        }

        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "내비 오류: $errorCode")
        }
    }

    /** 앱이 켜질 때 한 번 연결한다. 이미 연결했으면 아무것도 하지 않는다. */
    fun start() {
        if (started) return
        started = true
        naviHelper.addListener(listener)
        naviHelper.initialize(
            onConnected = {
                Log.i(TAG, "NaviHelper 연결됨 → 현재 위치·목적지·경로 상태 조회")
                requestSnapshot()
            },
            onFailed = { e ->
                Log.w(TAG, "NaviHelper 연결 실패", e)
                started = false
            },
        )
    }

    /** 지금 상태를 한 번 물어본다. 답은 위 listener로 온다. */
    fun requestSnapshot() {
        runCatching {
            naviHelper.getCurrentLocationInfo()
            naviHelper.getDestinationInfo()
            naviHelper.getRouteStateInfo()
        }.onFailure { Log.w(TAG, "내비 정보 조회 실패", it) }
    }

    fun stop() {
        if (!started) return
        started = false
        naviHelper.removeListener(listener)
        naviHelper.release()
    }

    private companion object {
        const val TAG = "NaviMonitor"
    }
}
