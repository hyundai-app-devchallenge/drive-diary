package com.dongbantest.app.ui.v3.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

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
    CompositionLocalProvider(LocalDbColors provides colors, LocalDbTypography provides type) {
        MaterialTheme(colorScheme = scheme, typography = materialTypography(type), content = content)
    }
}

/** 받은 폭으로 Compact / Expanded / Full 을 정해 LocalDbLayout 으로 내려준다. 모든 화면의 바깥에 있다. */
@Composable
fun AdaptiveLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        CompositionLocalProvider(LocalDbLayout provides DbLayout.forWidth(maxWidth)) { content() }
    }
}
