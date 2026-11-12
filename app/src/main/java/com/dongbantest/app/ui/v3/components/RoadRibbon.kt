package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/**
 * 길 리본: 출발 점, 채움 막대, 도착 깃발, 아래 출발지·도착지 라벨.
 * - car: 진행 위치에 차 표식. percent ≥ 100 이면 차를 숨기고 깃발을 채운 원(도착)으로 바꾼다.
 * - paused: 녹음 멈춤. 채움과 차 표식을 회색으로(위치는 그대로).
 * - from/to 가 null 이면 라벨 줄을 그리지 않는다(주행 중 홈의 진행 중 여행 면).
 * 진행 중 점선 변형은 지도(ExampleMap)의 live 경로가 맡는다.
 */
@Composable
fun RoadRibbon(
    percent: Int,
    color: Color,
    from: String?,
    to: String?,
    modifier: Modifier = Modifier,
    car: Boolean = false,
    paused: Boolean = false,
) {
    val c = DbTheme.colors
    val arrived = car && percent >= 100
    val fill = if (paused) c.muted else color
    val label = buildString {
        if (from != null && to != null) append("$from 에서 $to, ")
        append("진행 $percent%")
    }
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(Spacing.s2xs)) {
        Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                val w = maxWidth
                Box(Modifier.fillMaxWidth().height(8.dp).clip(DbShapes.pill).background(c.ruleSoft))
                if (percent > 0) {
                    Box(Modifier.fillMaxWidth(percent.coerceAtMost(100) / 100f).height(8.dp).clip(DbShapes.pill).background(fill))
                }
                // 출발 점: 흰 원 + 2dp 테두리
                Box(
                    Modifier.offset(x = (-1).dp).requiredSize(10.dp).clip(DbShapes.pill).background(c.paper)
                        .border(2.dp, color, DbShapes.pill),
                )
                if (car && !arrived) {
                    Box(
                        Modifier
                            .offset(x = w * (percent / 100f) - 14.dp)
                            .requiredSize(28.dp)
                            .clip(DbShapes.pill)
                            .background(c.paper),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(24.dp).clip(DbShapes.pill).background(fill), contentAlignment = Alignment.Center) {
                            DbIcon(DbIcons.Car, 14.dp, c.accentInk)
                        }
                    }
                }
            }
            if (arrived) {
                Box(Modifier.requiredSize(24.dp).clip(DbShapes.pill).background(color), contentAlignment = Alignment.Center) {
                    DbIcon(DbIcons.Flag, 14.dp, c.accentInk)
                }
            } else {
                DbIcon(DbIcons.Flag, 16.dp, color)
            }
        }
        if (from != null && to != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(from, style = DbTheme.type.caption, color = c.muted, modifier = Modifier.weight(1f))
                Text(to, style = DbTheme.type.caption, color = c.muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}
