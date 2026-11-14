package com.dongbantest.app.music

import android.service.notification.NotificationListenerService
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 알림 접근의 연결 상태만 제공한다. 알림 내용은 읽지 않는다. */
class MusicListenerService : NotificationListenerService() {
    // Intentionally never override onNotificationPosted or inspect notification contents.
    override fun onListenerConnected() {
        super.onListenerConnected()
        mutableConnected.value = true
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.i("MusicProbe", "onListenerConnected")
        }
    }

    override fun onListenerDisconnected() {
        mutableConnected.value = false
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.i("MusicProbe", "onListenerDisconnected")
        }
        super.onListenerDisconnected()
    }

    companion object {
        private val mutableConnected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    }
}
