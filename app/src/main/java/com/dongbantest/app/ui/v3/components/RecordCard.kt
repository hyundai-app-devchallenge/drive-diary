package com.dongbantest.app.ui.v3.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    // 고른 카드는 기록 색 테두리 대신 살짝 떠오르게(그림자) 하고, 테두리는 무채색으로 한 단계 진하게 남긴다.
    // 색은 점·리본·지도 경로가 맡는다.
    val lift by animateDpAsState(if (selected) 10.dp else 0.dp, tween(220), label = "Record card lift")
    val edge by animateColorAsState(if (selected) c.muted.copy(alpha = 0.4f) else c.rule, tween(220), label = "Record card edge")
    val fade by animateFloatAsState(if (dimmed) 0.55f else 1f, tween(220), label = "Record card dim")
    // 누름 물결(ripple)이 펼쳐진 버튼 자리까지 번지도록 카드 전체를 누르는 면으로 둔다.
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = fade }
            .shadow(lift, DbShapes.card, clip = false, ambientColor = c.shadow, spotColor = c.shadow)
            .clip(DbShapes.card)
            .background(c.paper)
            .border(1.dp, edge, DbShapes.card)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = openLabel; selectedSemantics = selected },
    ) {
        Box(Modifier.fillMaxWidth()) {
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
        AnimatedVisibility(
            selected && action != null,
            enter = expandVertically(tween(240), expandFrom = Alignment.Top) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(tween(200), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
        ) {
            Box(Modifier.fillMaxWidth().padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md)) { action?.invoke() }
        }
    }
}
