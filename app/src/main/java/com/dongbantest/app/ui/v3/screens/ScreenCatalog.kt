package com.dongbantest.app.ui.v3.screens

import androidx.compose.runtime.Composable

/** 화면 목록(웹 확정안 순서). 미리보기·스크린샷 테스트가 같은 목록을 쓴다. 기본 상태 = 웹 시연 데이터. */
data class ScreenEntry(val id: String, val name: String, val content: @Composable () -> Unit)

val ScreenCatalog: List<ScreenEntry> = listOf(
    ScreenEntry("S01", "홈") { HomeScreen() },
    ScreenEntry("S01-drive", "홈 · 주행 중") { HomeScreen(HomeUiState(driving = true)) },
    ScreenEntry("S02", "여행 설정") { TripSetupScreen() },
    ScreenEntry("S03", "녹음 동의") { ConsentScreen() },
    ScreenEntry("S04", "첫 여행 음성 안내") { VoiceGuideScreen() },
    ScreenEntry("B01", "주행 중 · 조용히 듣는 중") { ListeningScreen() },
    ScreenEntry("B01-quiet", "조용히 듣는 중 · 먼저 묻지 않음") { ListeningScreen(DrivingStates.B01_QUIET) },
    ScreenEntry("Q01", "조용함 확인 · 자고 있나요?") { QuietCheckScreen() },
    ScreenEntry("Q02", "대화를 여는 질문") { SilenceQuestionScreen() },
    ScreenEntry("S05", "여행 중 · 보기 전용") { DrivingScreen(DrivingStates.S05) },
    ScreenEntry("S05-q-silence", "여행 중 · 여행지 질문") { DrivingScreen(DrivingStates.S05_SILENCE) },
    ScreenEntry("S06", "음성 명령 피드백") { DrivingScreen(DrivingStates.S06) },
    ScreenEntry("S07", "녹음 잠시 멈춤") { DrivingScreen(DrivingStates.S07) },
    ScreenEntry("S08", "음성 인식 실패") { DrivingScreen(DrivingStates.S08) },
    ScreenEntry("S09", "녹음하지 않는 여행") { DrivingScreen(DrivingStates.S09) },
    ScreenEntry("S10", "잘못 멈춤 되돌리기") { DrivingScreen(DrivingStates.S10) },
    ScreenEntry("S11", "도착 · 여행 종료") { ArrivalScreen() },
    ScreenEntry("S12", "다이어리 만드는 중") { MakingDiaryScreen() },
    ScreenEntry("S13", "다이어리 완성") { DiaryReadyScreen() },
    ScreenEntry("S14", "다이어리") { DiaryScreen() },
    ScreenEntry("S14-live", "다이어리 · 여행 진행 중") { DiaryScreen(DiaryUiState(record = Sample.JEJU, live = true)) },
    ScreenEntry("R01", "기록 지도") { RecordMapScreen() },
    ScreenEntry("R01-live", "기록 지도 · 여행 진행 중") { RecordMapScreen(RecordMapUiState(live = true)) },
    ScreenEntry("P01", "정차 안내 · 주행 중") { StopNoticeScreen() },
    ScreenEntry("P02", "정차 안내 · 정차됨") { StopNoticeScreen(StopNoticeUiState(stopped = true)) },
    ScreenEntry("T01", "여행 · 진행 중인 여행 없음") { TripEmptyScreen() },
)
