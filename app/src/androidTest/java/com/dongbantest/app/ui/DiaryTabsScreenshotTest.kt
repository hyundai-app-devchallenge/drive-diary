package com.dongbantest.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dongbantest.app.ui.v3.components.keepAll
import com.dongbantest.app.ui.v3.screens.DiaryScreen
import com.dongbantest.app.ui.v3.screens.DiaryUiState
import com.dongbantest.app.ui.v3.screens.Sample
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 기록 상세 탭(M03) 화면을 예시 데이터로 그려 PNG 로 남긴다. 판정은 하지 않는 눈 확인용.
 * 결과: 앱 files/diary-tabs 폴더의 PNG
 *   adb exec-out run-as com.dongbantest.app cat files/diary-tabs/full-map.png > full-map.png
 */
@RunWith(AndroidJUnit4::class)
class DiaryTabsScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shoot(name: String, w: Int, h: Int, state: DiaryUiState = DiaryUiState(tabs = true), act: () -> Unit = {}) {
        rule.setContent { Frame(w, h) { DiaryScreen(state) } }
        act()
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "diary-tabs").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Frame(w: Int, h: Int, content: @Composable () -> Unit) = DongbanchaV3Theme {
        Box(Modifier.requiredSize(w.dp, h.dp).testTag(TAG)) { content() }
    }

    @Test fun fullMap() = shoot("full-map", 1280, 606)
    @Test fun fullMapSong() = shoot("full-map-song", 1280, 606) { rule.onNodeWithText(keepAll("밤편지 · 아이유")).performClick() }
    @Test fun fullMapTalk() = shoot("full-map-talk", 1280, 606) { rule.onNodeWithText(keepAll("휴게소 호두과자 논쟁")).performClick() }
    @Test fun expandedMap() = shoot("expanded-map", 850, 606)
    @Test fun compactMap() = shoot("compact-map", 420, 900) { rule.onNodeWithText(keepAll("Hype Boy · NewJeans")).performClick() }
    @Test fun fullSongs() = shoot("full-songs", 1280, 606) { rule.onNodeWithText(keepAll("노래 9")).performClick() }
    @Test fun fullSongsOff() = shoot("full-songs-off", 1280, 606, DiaryUiState(record = Sample.GANGNEUNG_NO_SONGS, tabs = true)) {
        rule.onNodeWithText(keepAll("노래 꺼짐")).performClick()
    }

    private companion object { const val TAG = "diary-frame" }
}
