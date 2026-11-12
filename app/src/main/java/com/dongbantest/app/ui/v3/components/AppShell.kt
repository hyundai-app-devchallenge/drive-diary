package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.AdaptiveLayout
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/** 본문 영역 기본 여백: 좌우 16(넓은 화면 32), 아래 16 */
@Composable
fun defaultBodyPadding(): PaddingValues {
    val h = if (DbTheme.layout.wide) Spacing.xl else Spacing.md
    return PaddingValues(start = h, end = h, bottom = Spacing.md)
}

/**
 * 모든 화면의 틀. 폭으로 Compact / Expanded / Full 을 정하고,
 * Compact = 위 상태 줄 + 본문 + (요약 줄) + 하단 탭바, Expanded·Full = 왼쪽 레일 + 위 상태 줄 + 본문.
 *
 * - scroll = true: 본문을 세로 스크롤하는 Column(최소 높이 = 보이는 높이, verticalArrangement 적용).
 *   false: 본문이 직접 높이를 채운다(넓은 화면 두 열 화면).
 * - phoneSummary: 폰 하단 요약 줄(주행 화면). phoneToast가 있으면 그 자리를 알림이 덮는다(줄은 숨김, 자리 유지).
 * - overlay: 시트·대화상자. 레일과 탭바까지 전체를 덮는다.
 */
@Composable
fun AppShell(
    selectedTab: DbTab,
    onTab: (DbTab) -> Unit,
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    bodyPadding: PaddingValues? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    phoneSummary: (@Composable () -> Unit)? = null,
    phoneToast: (@Composable () -> Unit)? = null,
    overlay: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AdaptiveLayout(modifier.fillMaxSize()) {
        val c = DbTheme.colors
        val wide = DbTheme.layout.wide
        val pad = bodyPadding ?: defaultBodyPadding()
        Box(Modifier.fillMaxSize().background(c.paper)) {
            Row(
                Modifier.fillMaxSize().then(if (overlay != null) Modifier.semantics { hideFromAccessibility() } else Modifier),
            ) {
                if (wide) DbNavigationRail(selectedTab, onTab)
                Column(Modifier.weight(1f).fillMaxSize()) {
                    topBar()
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val viewport = maxHeight
                        if (scroll) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .heightIn(min = viewport)
                                    .padding(pad),
                                verticalArrangement = verticalArrangement,
                                horizontalAlignment = horizontalAlignment,
                                content = content,
                            )
                        } else {
                            Column(
                                Modifier.fillMaxSize().padding(pad),
                                verticalArrangement = verticalArrangement,
                                horizontalAlignment = horizontalAlignment,
                                content = content,
                            )
                        }
                    }
                    if (!wide) {
                        phoneSummary?.let {
                            Box(if (phoneToast != null) Modifier.alpha(0f).semantics { hideFromAccessibility() } else Modifier) { it() }
                        }
                        DbNavigationBar(selectedTab, onTab)
                    }
                }
            }
            if (!wide && phoneToast != null) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(start = Spacing.sm, end = Spacing.sm, bottom = 60.dp + Spacing.s2xs),
                ) { phoneToast() }
            }
            overlay?.invoke()
        }
    }
}
