package com.dongbantest.app.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

class MusicAccess(context: Context) {
    private val context = context.applicationContext
    val component = ComponentName(this.context, MusicListenerService::class.java)

    fun isGranted(): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** 상세 화면부터 시도하고, 기기에 없는 설정 화면은 제외한다. */
    fun settingsIntents(): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                component.flattenToString(),
            ))
        }
        add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }.filter { it.resolveActivity(context.packageManager) != null }
}
