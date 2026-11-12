package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.DbLayout
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/** 배경 터치를 막는 반투명 막(scrim). */
@Composable
private fun Modifier.scrim(): Modifier = background(DbTheme.colors.scrim).pointerInput(Unit) { detectTapGestures { } }

/**
 * 아래 시트: 화면 전체 scrim 위, 아래쪽 가운데, 최대 560dp, 위 모서리 20, 손잡이.
 * horizontalAlignment = 시트 안 정렬(정차 안내는 왼쪽 정렬).
 */
@Composable
fun DbBottomSheet(
    title: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DbTheme.colors
    Box(Modifier.fillMaxSize().scrim(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .floatShadow(DbShapes.sheet)
                .clip(DbShapes.sheet)
                .background(c.paper)
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = horizontalAlignment,
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 0.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.padding(bottom = 0.dp).width(36.dp).height(4.dp).clip(DbShapes.pill).background(c.rule))
            }
            content()
        }
    }
}

/**
 * 가운데 대화상자(S04) / 주행 중 알림(Q01·Q02, alert = true).
 * belowBar = scrim이 56dp 상태 줄 아래부터 덮어 녹음 칩은 밝게 둔다(넓은 화면은 레일 윗부분도 어둡게).
 */
@Composable
fun DbDialog(
    title: String,
    modifier: Modifier = Modifier,
    alert: Boolean = false,
    belowBar: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DbTheme.colors
    val layout = DbTheme.layout
    Box(Modifier.fillMaxSize()) {
        if (belowBar) {
            Box(Modifier.fillMaxSize().padding(top = 56.dp).scrim())
            if (layout.wide) Box(Modifier.width(88.dp).height(56.dp).scrim())
        } else {
            Box(Modifier.fillMaxSize().scrim())
        }
        Box(
            Modifier.fillMaxSize().padding(top = if (belowBar) 56.dp else 0.dp).padding(Spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier
                    .widthIn(max = if (layout == DbLayout.Full) 560.dp else 440.dp)
                    .fillMaxWidth()
                    .floatShadow(DbShapes.dialog)
                    .clip(DbShapes.dialog)
                    .background(c.paper)
                    .semantics {
                        paneTitle = title
                        dialog()
                        if (alert) liveRegion = LiveRegionMode.Polite
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(if (alert) Spacing.sm else Spacing.md),
                horizontalAlignment = Alignment.Start,
                content = content,
            )
        }
    }
}
