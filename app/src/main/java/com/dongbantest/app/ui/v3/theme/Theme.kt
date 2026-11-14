package com.dongbantest.app.ui.v3.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** 화면 코드에서 토큰을 읽는 입구: DbTheme.colors, DbTheme.type, DbTheme.layout */
object DbTheme {
    val colors: DbColors @Composable @ReadOnlyComposable get() = LocalDbColors.current
    val type: DbTypography @Composable @ReadOnlyComposable get() = LocalDbTypography.current
    val layout: DbLayout @Composable @ReadOnlyComposable get() = LocalDbLayout.current
}

@Composable
fun DongbanchaV3Theme(content: @Composable () -> Unit) {
    val colors = DbColors()
    val type = DbTypography()
    val scheme = lightColorScheme(
        primary = colors.action,
        onPrimary = colors.accentInk,
        primaryContainer = colors.accentSoft,
        onPrimaryContainer = colors.accent,
        secondary = colors.ink,
        onSecondary = colors.paper,
        background = colors.paper,
        onBackground = colors.ink,
        surface = colors.paper,
        onSurface = colors.ink,
        surfaceVariant = colors.paper2,
        onSurfaceVariant = colors.muted,
        outline = colors.rule,
        outlineVariant = colors.ruleSoft,
        error = colors.error,
        scrim = colors.scrim,
        surfaceTint = Color.Transparent,
    )
    // 디자인은 높이 606dp · 폭 420dp(밀도 2.0 의 Pleos Compact 창)를 기준으로 한다.
    // 기기 밀도가 달라도(AVD 는 약 1.0) 창 크기에서 배율을 다시 정해 같은 비율로 그린다.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val current = LocalDensity.current
        val byHeight = if (constraints.hasBoundedHeight) constraints.maxHeight / DESIGN_HEIGHT_DP else Float.MAX_VALUE
        val byWidth = if (constraints.hasBoundedWidth) constraints.maxWidth / DESIGN_WIDTH_DP else Float.MAX_VALUE
        val fitted = minOf(byHeight, byWidth)
        val density = if (fitted == Float.MAX_VALUE) current else Density(fitted, current.fontScale)
        CompositionLocalProvider(
            LocalDensity provides density,
            LocalDbColors provides colors,
            LocalDbTypography provides type,
        ) {
            MaterialTheme(colorScheme = scheme, typography = materialTypography(type), content = content)
        }
    }
}

private const val DESIGN_HEIGHT_DP = 606f
private const val DESIGN_WIDTH_DP = 420f

/** 받은 폭으로 Compact / Expanded / Full 을 정해 LocalDbLayout 으로 내려준다. 모든 화면의 바깥에 있다. */
@Composable
fun AdaptiveLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        CompositionLocalProvider(LocalDbLayout provides DbLayout.forWidth(maxWidth)) { content() }
    }
}
