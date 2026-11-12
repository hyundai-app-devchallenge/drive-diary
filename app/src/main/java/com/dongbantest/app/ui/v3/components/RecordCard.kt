package com.dongbantest.app.ui.v3.components

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
) {
    val c = DbTheme.colors
    val t = DbTheme.type
    Box(
        modifier
            .fillMaxWidth()
            .clip(DbShapes.card)
            .border(1.dp, c.rule, DbShapes.card)
            .background(c.paper)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = openLabel },
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
}
