package com.dongbantest.app.debug.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 기존 Debug*Receiver와 같은 명시적 adb broadcast 진입점. debug 매니페스트에만 등록한다. */
class DebugMusicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in DebugMusicService.actions) return
        Log.i("MusicProbe", "DebugMusicReceiver received $action")
        try {
            DebugMusicService.send(context, action)
        } catch (e: RuntimeException) {
            // OEM의 백그라운드 FGS 시작 제한도 증거로 남긴다. 예외로 앱을 죽이지 않는다.
            Log.e("MusicProbe", "Cannot start music probe; open lab and start sequence there", e)
        }
    }
}
