package com.dongbantest.app.ui.v3.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected as selectedSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/**
 * 기록 카드(R01 목록): 기록 색 점 + 제목, 날짜, 기록 색 리본(100%), 오른쪽 사진 자리.
 * live = 진행 중인 여행 카드: 날짜 자리에 "지금 여행 중"(파랑), 진행률 리본과 차 표식, 사진 자리 대신 오른쪽 위 "여행 화면으로".
 *
 * selected = 지도에서 이 기록의 경로를 고름: 기록 색 굵은 테두리, 카드 아래쪽에 [action](예: "기록 보기") 버튼.
 * dimmed = 다른 기록을 고른 동안 흐리게(지도에서 다른 경로가 흐려지는 것과 같이).
 */
@Composable
fun RecordCard(
    title: String,
    date: String,
    tone: TripTone,
    from: String,
    to: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    openLabel: String = "$title 다이어리 열기",
    live: Boolean = false,
    livePercent: Int = 0,
    liveOpenText: String = "여행 화면으로",
    selected: Boolean = false,
    dimmed: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    Column(
        modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.5f else 1f)
            .clip(DbShapes.card)
            .border(if (selected) 2.dp else 1.dp, if (selected) tone.color else c.rule, DbShapes.card)
            .background(c.paper),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = openLabel; selectedSemantics = selected },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2xs)) {
                    Row(
                        Modifier.padding(end = if (live) 96.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Dot(tone.color)
                        Text(title, style = t.title.copy(fontWeight = FontWeight.Bold), color = c.ink)
                    }
                    Text(
                        date,
                        style = if (live) t.caption.copy(fontWeight = FontWeight.SemiBold) else t.caption,
                        color = if (live) c.accent else c.muted,
                        modifier = Modifier.padding(bottom = Spacing.s2xs),
                    )
                    RoadRibbon(if (live) livePercent else 100, tone.color, from, to, car = live)
                }
                if (!live) {
                    Box(Modifier.size(64.dp).clip(DbShapes.button).background(c.paper2), contentAlignment = Alignment.Center) {
                        DbIcon(DbIcons.Image, 20.dp, c.muted)
                    }
                }
            }
            if (live) {
                Text(
                    liveOpenText,
                    style = t.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = c.muted,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.md),
                )
            }
        }
        AnimatedVisibility(selected && action != null) {
            Box(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md)) { action?.invoke() }
        }
    }
}
