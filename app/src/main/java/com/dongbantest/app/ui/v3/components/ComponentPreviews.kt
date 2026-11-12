package com.dongbantest.app.ui.v3.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dongbantest.app.ui.v3.screens.Sample
import com.dongbantest.app.ui.v3.screens.SampleMap
import com.dongbantest.app.ui.v3.theme.DbTheme
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme
import com.dongbantest.app.ui.v3.theme.Spacing

/* 컴포넌트 상태별 미리보기 */

@Composable
private fun Box420(content: @Composable () -> Unit) = DongbanchaV3Theme {
    Column(
        Modifier.width(420.dp).background(DbTheme.colors.paper).padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) { content() }
}

@Preview(name = "RecordingChip 5 상태", widthDp = 420)
@Composable
private fun RecordingChipPreview() = Box420 {
    RecordingChip(RecordingState.On, Sample.REC_ON)
    RecordingChip(RecordingState.Paused, Sample.REC_PAUSED)
    RecordingChip(RecordingState.Off, Sample.REC_OFF)
    RecordingChip(RecordingState.Ready, Sample.REC_READY)
    RecordingChip(RecordingState.Saved, Sample.REC_SAVED)
    RecordingChip(RecordingState.On, Sample.REC_ON, small = true)
}

@Preview(name = "RoadRibbon 변형", widthDp = 420)
@Composable
private fun RoadRibbonPreview() = Box420 {
    val c = DbTheme.colors
    RoadRibbon(47, c.accent, Sample.FROM, Sample.TO, car = true)
    RoadRibbon(47, c.accent, Sample.FROM, Sample.TO, car = true, paused = true)
    RoadRibbon(100, c.accent, "${Sample.FROM} ${Sample.START}", "${Sample.TO} ${Sample.ARRIVE}", car = true)
    RoadRibbon(100, TripTone.Jeju.color, "제주공항", "성산일출봉")
    RoadRibbon(0, c.accent, "출발", "도착")
    RoadRibbon(47, c.accent, null, null, car = true)
}

@Preview(name = "VoiceToast 3 종류", widthDp = 420)
@Composable
private fun VoiceToastPreview() = Box420 {
    VoiceToast(Sample.TOAST_NEW)
    VoiceToast(Sample.TOAST_NOT_HEARD)
    VoiceToast(Sample.TOAST_MISHEARD)
}

@Preview(name = "Buttons", widthDp = 420)
@Composable
private fun ButtonsPreview() = Box420 {
    PrimaryButton("모두 동의하고 출발", {})
    OutlineButton("녹음 없이 출발", {})
    DbTextButton("나중에", {})
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        DbButton("편집", {}, ButtonKind.Plain, off = true)
        DbButton("다음 장", {}, ButtonKind.Plain)
        CircleIconButton(com.dongbantest.app.ui.v3.theme.DbIcons.ArrowLeft, "뒤로", {})
        PlayButton(false, "재생", {})
        PlayButton(true, "정지", {})
    }
}

@Preview(name = "DriveStatusBar · StoppedTopBar", widthDp = 420)
@Composable
private fun StatusBarsPreview() = Box420 {
    DriveStatusBar(RecordingState.On, Sample.REC_ON, stopped = false)
    DriveStatusBar(RecordingState.Saved, "기록 3개 저장됨", stopped = true)
    StoppedTopBar()
}

@Preview(name = "NavigationBar", widthDp = 420)
@Composable
private fun NavBarPreview() = Box420 { DbNavigationBar(DbTab.Trip, {}) }

@Preview(name = "NavigationRail", widthDp = 120, heightDp = 300)
@Composable
private fun NavRailPreview() = DongbanchaV3Theme { DbNavigationRail(DbTab.Record, {}) }

@Preview(name = "QuestionCard · QuietFace · Bubble", widthDp = 420)
@Composable
private fun DrivingPartsPreview() = Box420 {
    DongbaniBubble(Sample.BUBBLE_DRIVING)
    QuestionCard(Sample.RELATION, Sample.Q_FIRST, Sample.HINT, Sample.SAY_LABEL, Sample.SAY_ON, pauseCaption = Sample.PAUSE_CAPTION)
    QuietFace("대화가 이어지는 동안은 조용히 들을게요", "대화가 한동안 없으면 동반이가 먼저 말을 걸어요.", "“녹음 잠시 멈춰”라고 하면 언제든 멈춰요")
}

@Preview(name = "AskedPanel · AskedSummary", widthDp = 420, heightDp = 420)
@Composable
private fun AskedPreview() = Box420 {
    AskedPanel("지금까지 나눈 대화", "2개", Sample.ASKED_BASE, Modifier.height(260.dp), footnote = "대화 내용은 여행이 끝난 뒤 다이어리로 정리돼요")
    AskedSummary("나눈 대화 2개", "지금: 처음 만났던 날")
}

@Preview(name = "RecordCard · PhotoSlot", widthDp = 420)
@Composable
private fun RecordCardPreview() = Box420 {
    RecordCard(Sample.JEJU.title, Sample.JEJU.dateShort, TripTone.Jeju, Sample.JEJU.from, Sample.JEJU.to, {})
    RecordCard(Sample.TRIP_TITLE, "지금 여행 중", TripTone.Gangneung, Sample.FROM, Sample.TO, {}, live = true, livePercent = Sample.PROGRESS)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PhotoSlot("사진 자리", Modifier.weight(1f))
        PhotoSlot("사진 자리", Modifier.weight(1f), tile = true)
    }
}

@Preview(name = "ExampleMap 전체 · 진행 중 · 한 기록", widthDp = 420, heightDp = 640)
@Composable
private fun ExampleMapPreview() = Box420 {
    ExampleMap(Sample.RECORDS.map { SampleMap.route(it) }, SampleMap.labels(false), Modifier.fillMaxWidth().height(220.dp))
    ExampleMap(
        Sample.RECORDS.drop(1).map { SampleMap.route(it) }, SampleMap.labels(true), Modifier.fillMaxWidth().height(220.dp),
        live = SampleMap.LIVE, liveRoute = SampleMap.route(Sample.GANGNEUNG),
    )
    ExampleMap(listOf(SampleMap.route(Sample.GANGNEUNG)), SampleMap.labelsFor(Sample.GANGNEUNG), Modifier.fillMaxWidth().height(120.dp))
}
