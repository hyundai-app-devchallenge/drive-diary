package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.theme.DbIcons
import com.dongbantest.app.ui.v3.theme.DbShapes
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.Spacing

/**
 * 녹음 상태 칩. 곁눈으로도 구분되게 상태마다 바탕과 테두리가 다르다. 녹음 중은 채운 점(깜빡이지 않음).
 * small = 다이어리 live 머리줄의 작은 칩(.rec-sm). onTap = 데모 지름길(버튼 모양 없음).
 */
@Composable
fun RecordingChip(state: RecordingState, text: String, modifier: Modifier = Modifier, small: Boolean = false, onTap: (() -> Unit)? = null) {
    val c = DbTheme.colors
    val (bg, border, fg) = when (state) {
        RecordingState.On -> Triple(c.accentSoft, Color.Transparent, c.accent)
        RecordingState.Paused -> Triple(c.paper3, Color.Transparent, c.ink)
        RecordingState.Off -> Triple(Color.Transparent, c.rule, c.muted)
        RecordingState.Ready -> Triple(Color.Transparent, c.accent, c.accent)
        RecordingState.Saved -> Triple(c.paper3, Color.Transparent, c.ink)
    }
    val icon = when (state) {
        RecordingState.On -> null
        RecordingState.Paused -> DbIcons.Pause
        RecordingState.Off -> DbIcons.MicOff
        RecordingState.Ready -> DbIcons.Mic
        RecordingState.Saved -> DbIcons.Check
    }
    val base = if (small) DbTheme.type.caption else DbTheme.type.status
    Row(
        modifier
            .height(if (small) 28.dp else 32.dp)
            .clip(DbShapes.pill)
            .background(bg)
            .border(1.dp, border, DbShapes.pill)
            .demoTap(onTap)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = if (small) Spacing.xs else Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (small) Spacing.s2xs else Spacing.xs),
    ) {
        if (icon == null) Box(Modifier.size(8.dp).clip(DbShapes.pill).background(c.accent))
        else DbIcon(icon, 16.dp, fg)
        Text(
            text,
            style = base.copy(fontWeight = if (state == RecordingState.Paused) FontWeight.Bold else FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 상단 줄 공통 틀: 높이 56, 좌우 16(넓은 화면 32). */
@Composable
fun TopBarFrame(modifier: Modifier = Modifier, divider: Boolean = false, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val wide = DbTheme.layout.wide
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (divider) Modifier.bottomRule(DbTheme.colors.ruleSoft) else Modifier)
            .padding(horizontal = if (wide) Spacing.xl else Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        content = content,
    )
}

/** 정차 중 화면의 상단 줄: 워드마크 + "정차 중" 칩 */
@Composable
fun StoppedTopBar(stateLabel: String = "정차 중", wordmark: String = "동반차") {
    val c = DbTheme.colors
    TopBarFrame {
        Text(wordmark, style = DbTheme.type.titleMd.copy(lineHeight = DbTheme.type.titleMd.fontSize), color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
        StateChip(stateLabel, parked = true, small = true)
    }
}

/** 회색 상태 칩(.chip / .state-chip): 정차 중(P) 또는 주행 중(차) */
@Composable
fun StateChip(text: String, parked: Boolean, modifier: Modifier = Modifier, small: Boolean = false) {
    val c = DbTheme.colors
    Row(
        modifier.height(32.dp).clip(DbShapes.pill).background(c.paper3).padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (small) Spacing.s2xs else Spacing.xs),
    ) {
        DbIcon(if (parked) DbIcons.SquareParking else DbIcons.Car, 16.dp, c.ink)
        Text(text, style = if (small) DbTheme.type.bodySm.copy(fontWeight = FontWeight.SemiBold) else DbTheme.type.status, color = c.ink)
    }
}

/**
 * 주행 상태 줄: 왼쪽 "주행 중"/"정차 중", 오른쪽 녹음 칩. 아래 옅은 선.
 * onChipTap = 데모 지름길(그 상태의 녹음 명령을 말한 것으로 본다).
 */
@Composable
fun DriveStatusBar(
    recording: RecordingState,
    recordingText: String,
    stopped: Boolean,
    modifier: Modifier = Modifier,
    drivingLabel: String = "주행 중",
    stoppedLabel: String = "정차 중",
    onChipTap: (() -> Unit)? = null,
) {
    val c = DbTheme.colors
    TopBarFrame(modifier, divider = true) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            DbIcon(if (stopped) DbIcons.SquareParking else DbIcons.Car, 20.dp, c.ink)
            Text(if (stopped) stoppedLabel else drivingLabel, style = DbTheme.type.status.copy(fontWeight = FontWeight.Bold), color = c.ink)
        }
        RecordingChip(recording, recordingText, onTap = onChipTap)
    }
}
