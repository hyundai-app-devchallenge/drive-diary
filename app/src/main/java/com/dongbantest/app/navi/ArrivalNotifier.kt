package com.dongbantest.app.navi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dongbantest.app.MainActivity
import com.dongbantest.app.R

/**
 * 목적지 도착으로 여행이 자동 종료됐을 때, 앱이 화면에 없으면 알림을 띄운다.
 * 알림을 누르면 MainActivity 가 열리고 S11 도착 화면을 보여준다 ("아직 여행 중이에요"로 되돌릴 수 있다).
 */
class ArrivalNotifier(private val context: Context) {

    fun show(tripId: Long, destinationName: String) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(TAG, "알림 권한이 없어 도착 알림을 띄우지 못함")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "목적지 도착", NotificationManager.IMPORTANCE_HIGH)
        )

        val openApp = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TRIP_ID, tripId)
            .putExtra(EXTRA_DESTINATION, destinationName)
        val pendingIntent = PendingIntent.getActivity(
            context, tripId.toInt(), openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val place = destinationName.ifBlank { "목적지" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("${place}에 도착했어요")
            .setContentText("여행을 마치고 다이어리로 정리할게요. 아직 여행 중이면 눌러서 알려 주세요.")
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "도착 알림 표시: tripId=$tripId, 목적지=$place")
    }

    companion object {
        const val EXTRA_TRIP_ID = "arrival_trip_id"
        const val EXTRA_DESTINATION = "arrival_destination"
        private const val CHANNEL_ID = "arrival"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ArrivalNotifier"
    }
}
