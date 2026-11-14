package com.dongbantest.app.ui.v3.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 화면 영역 폭으로 정하는 레이아웃(웹과 같은 규칙, 웹 px 1 = dp 1).
 * Compact < 640 ≤ Expanded < 1100 ≤ Full. DongbanchaV3Theme 이 창 크기로 밀도를 다시 정하므로(높이 606dp 기준)
 * 기기 밀도와 무관하게 Pleos 창은 Compact 420dp, Expanded 850dp, Full 1280dp 로 보인다.
 */
enum class DbLayout {
    Compact, Expanded, Full;

    val wide: Boolean get() = this != Compact

    companion object {
        fun forWidth(width: Dp): DbLayout = when {
            width < 640.dp -> Compact
            width < 1100.dp -> Expanded
            else -> Full
        }
    }
}

val LocalDbLayout = staticCompositionLocalOf { DbLayout.Compact }
