package com.dongbantest.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dongbantest.app.DongbanApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * 디버그 빌드 전용: 터미널(adb)에서 GPX 경로를 끝난 여행으로 넣는다. 실제 저장은 [DebugGpxImporter] 가 한다.
 * 앱이 꺼져 있으면 방송이 무시되므로, 앱을 한 번 켠 뒤에 보낸다.
 *
 * ■ 번들 데모 여행 3개 넣기 (이미 있으면 건너뜀) (화면에서는 "동반차 디버그" → "예시 여행 넣기"와 같다)
 *    adb shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_IMPORT_GPX -p com.dongbantest.app --ez samples true
 *
 * ■ 내 GPX 파일 넣기
 *  1) 파일을 앱 전용 폴더(files/gpx)에 복사한다.
 *    adb shell "run-as com.dongbantest.app --user 10 sh -c 'mkdir -p files/gpx && cat > files/gpx/jeonju.gpx'" < jeonju.gpx
 *  2) 여행으로 넣는다. days_ago 만큼 전 날짜의 오전 9시에 출발한 것으로 저장한다.
 *    adb shell "am broadcast --user 10 -a com.dongbantest.app.DEBUG_IMPORT_GPX -p com.dongbantest.app \
 *        --es file jeonju.gpx --es title '전주 한옥 여행' --es from '서울시립대학교' --es to '전주 한옥마을' \
 *        --es people '나,친구1' --ei days_ago 5"
 */
class DebugGpxImportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DongbanApplication
        val importer = DebugGpxImporter(app, app.container.tripRepository)
        val pending = goAsync()
        scope.launch {
            try {
                if (intent.getBooleanExtra("samples", false)) {
                    Log.i(TAG, "예시 여행 ${app.container.demoSeeder.seed()}개를 넣었어요")
                    return@launch
                }
                val fileName = intent.getStringExtra("file") ?: return@launch
                val title = intent.getStringExtra("title") ?: fileName.removeSuffix(".gpx")
                val people = intent.getStringExtra("people")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: listOf("나")
                File(context.filesDir, "gpx/$fileName").inputStream().use {
                    importer.import(
                        input = it,
                        title = title,
                        from = intent.getStringExtra("from"),
                        to = intent.getStringExtra("to") ?: title,
                        people = people,
                        daysAgo = intent.getIntExtra("days_ago", 1),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "GPX 여행 넣기 실패", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugGpxImport"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
