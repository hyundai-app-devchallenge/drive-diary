package com.dongbantest.app.ui.v3.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 화면 영역 폭으로 정하는 레이아웃(웹과 같은 규칙, 웹 px 1 = dp 1).
 * Compact < 640 ≤ Expanded < 1100 ≤ Full. Pleos 창 기준 Compact 420dp, Expanded 850dp, Full 1280dp(밀도 2.0).
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
