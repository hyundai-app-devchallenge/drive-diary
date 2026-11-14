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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dongbantest.app.ui.v3.components.keepAll
import com.dongbantest.app.ui.v3.screens.DiaryScreen
import com.dongbantest.app.ui.v3.screens.DiaryUiState
import com.dongbantest.app.ui.v3.screens.RecordMapScreen
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 기록 지도(R01)와 다이어리 한 장(S14)을 예시 데이터로 그려 PNG 로 남긴다. 판정은 하지 않는 눈 확인용.
 * 결과: 앱 files/diary-shots 폴더의 PNG
 *   adb exec-out run-as com.dongbantest.app cat files/diary-shots/diary-full.png > diary-full.png
 */
@RunWith(AndroidJUnit4::class)
class DiaryScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shoot(name: String, w: Int, h: Int, content: @Composable () -> Unit, act: () -> Unit = {}) {
        rule.setContent { Frame(w, h, content) }
        act()
        rule.waitForIdle()
        val bmp = rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "diary-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Frame(w: Int, h: Int, content: @Composable () -> Unit) = DongbanchaV3Theme {
        Box(Modifier.requiredSize(w.dp, h.dp).testTag(TAG)) { content() }
    }

    private val diary: @Composable () -> Unit = { DiaryScreen(DiaryUiState()) }
    private val pickTalk = { rule.onAllNodesWithText(keepAll("처음 만났던 날")).onFirst().performClick(); Unit }

    @Test fun diaryFull() = shoot("diary-full", 1280, 606, diary)
    @Test fun diaryFullTalk() = shoot("diary-full-talk", 1280, 606, diary, pickTalk)
    @Test fun diaryExpanded() = shoot("diary-expanded", 850, 606, diary)
    @Test fun diaryCompact() = shoot("diary-compact", 420, 1100, diary, pickTalk)

    @Test fun recordFull() = shoot("record-full", 1280, 606, { RecordMapScreen() })
    @Test fun recordFullPicked() = shoot("record-full-picked", 1280, 606, { RecordMapScreen() }) {
        rule.onAllNodesWithText(keepAll("제주 가족 여행")).onFirst().performClick()
    }

    private companion object { const val TAG = "diary-frame" }
}
