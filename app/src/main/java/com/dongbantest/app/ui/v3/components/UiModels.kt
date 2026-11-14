package com.dongbantest.app.ui.v3.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.dongbantest.app.ui.v3.theme.DbTheme

/** 기록 색. 색 값은 테마 토큰(trip-*)에서 읽는다. 팀 앱에서는 기록 id로 순환 배정하면 된다. */
enum class TripTone {
    Gangneung, Jeju, Yangpyeong;

    val color: Color
        @Composable @ReadOnlyComposable
        get() = when (this) {
            Gangneung -> DbTheme.colors.tripGangneung
            Jeju -> DbTheme.colors.tripJeju
            Yangpyeong -> DbTheme.colors.tripYangpyeong
        }
}

/** 녹음 상태 칩의 다섯 상태 */
enum class RecordingState { On, Paused, Off, Ready, Saved }

/** "지금까지 나눈 대화" 한 줄. now = 지금 이야기 중 */
data class AskedItem(
    val time: String,
    val title: String,
    val now: Boolean = false,
    val id: String = "",
    val audio: String? = null,
    val clipCount: Int = 1,
)

/** 음성 확인 알림 종류: 성공 / 못 들음 / 확인 필요 */
enum class ToastKind { Success, NotHeard, Confirm }

data class VoiceToastUi(
    val kind: ToastKind,
    val first: String,
    val second: String,
    /** 확인 필요일 때 "아니야" 버튼 */
    val say: String? = null,
)

/** 하단 탭바·레일의 3탭 */
enum class DbTab(val label: String) { Home("홈"), Trip("여행"), Record("기록") }
