package com.dongbantest.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import com.dongbantest.app.DongbanApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * 디버그 빌드 전용: 다이어리 사진 칸을 확인하려고 기록에 예시 사진(그린 풍경)을 붙인다.
 * 가로·세로·정방형·와이드 비율을 섞어 펼친 목록(masonry)이 제대로 쌓이는지 볼 수 있다.
 *
 * adb shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_ADD_PHOTOS -n com.dongbantest.app/.debug.DebugPhotoReceiver --es title 속초 --ei count 9
 *   tripId(long) 또는 title(제목 일부)로 기록을 고른다. 둘 다 없으면 가장 최근에 끝난 기록.
 */
class DebugPhotoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as DongbanApplication).container
        val repo = container.tripRepository
        val count = intent.getIntExtra("count", 9).coerceIn(1, 30)
        val wantedId = intent.getLongExtra("tripId", -1L)
        val title = intent.getStringExtra("title")
        val pending = goAsync()
        container.appScope.launch {
            try {
                val trips = repo.observeTrips().first().filter { !it.isOngoing }
                val trip = when {
                    wantedId > 0 -> trips.firstOrNull { it.id == wantedId }
                    !title.isNullOrBlank() -> trips.firstOrNull { it.title.contains(title) }
                    else -> trips.firstOrNull()
                }
                if (trip == null) {
                    Log.w(TAG, "trip not found")
                    return@launch
                }
                val rnd = Random(trip.id)
                repeat(count) { i ->
                    val (w, h) = SIZES[i % SIZES.size]
                    val tmp = File(context.cacheDir, "debug_photo_$i.jpg")
                    withContext(Dispatchers.Default) { tmp.outputStream().use { paint(w, h, rnd).compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                    val stored = container.mediaFileStore.copyPhotoToAppStorage(Uri.fromFile(tmp))
                    tmp.delete()
                    repo.addPhoto(trip.id, stored.absolutePath)
                }
                Log.i(TAG, "added $count photos to trip ${trip.id}")
            } finally {
                pending.finish()
            }
        }
    }

    /** 하늘·바다·모래 세 띠와 해 하나. 사진마다 색과 수평선 높이를 조금씩 바꾼다. */
    private fun paint(w: Int, h: Int, rnd: Random): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val palette = PALETTES[rnd.nextInt(PALETTES.size)]
        val horizon = h * (0.38f + rnd.nextFloat() * 0.24f)
        p.shader = LinearGradient(0f, 0f, 0f, horizon, palette[0], palette[1], Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), horizon, p)
        val shore = horizon + (h - horizon) * (0.45f + rnd.nextFloat() * 0.3f)
        p.shader = LinearGradient(0f, horizon, 0f, shore, palette[2], palette[3], Shader.TileMode.CLAMP)
        cv.drawRect(0f, horizon, w.toFloat(), shore, p)
        p.shader = null
        p.color = palette[4]
        cv.drawRect(0f, shore, w.toFloat(), h.toFloat(), p)
        p.color = 0xFFFFF4D6.toInt()
        cv.drawCircle(w * (0.2f + rnd.nextFloat() * 0.6f), horizon * (0.35f + rnd.nextFloat() * 0.35f), minOf(w, h) * 0.07f, p)
        return bmp
    }

    private companion object {
        const val TAG = "DebugPhoto"
        val SIZES = listOf(1600 to 1200, 1200 to 1600, 1920 to 1080, 1200 to 1200, 1080 to 1620, 1600 to 1200, 1200 to 1500, 1800 to 1200, 1080 to 1920)
        val PALETTES = listOf(
            intArrayOf(0xFF7FB6E8.toInt(), 0xFFCFE6F7.toInt(), 0xFF2F7FB8.toInt(), 0xFF5FB3C9.toInt(), 0xFFE9D8B4.toInt()),
            intArrayOf(0xFFF2A65A.toInt(), 0xFFFBD9A7.toInt(), 0xFF3D6E9E.toInt(), 0xFF6E9CC2.toInt(), 0xFFD9C29A.toInt()),
            intArrayOf(0xFF5C6E91.toInt(), 0xFFE3A6A1.toInt(), 0xFF284A6B.toInt(), 0xFF4D7390.toInt(), 0xFFBFAF95.toInt()),
            intArrayOf(0xFF9FD3C7.toInt(), 0xFFE8F4EE.toInt(), 0xFF1F8A82.toInt(), 0xFF56B3A6.toInt(), 0xFFEADFC6.toInt()),
        )
    }
}
