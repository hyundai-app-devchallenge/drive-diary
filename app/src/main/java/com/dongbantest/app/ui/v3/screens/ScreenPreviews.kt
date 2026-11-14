package com.dongbantest.app.ui.v3.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.dongbantest.app.ui.v3.theme.DongbanchaV3Theme

/**
 * 화면 미리보기: Compact 420 / Expanded 850 / Full 1280 × 606dp.
 * Pleos 창(840 / 1700 / 2560 × 1212px, 밀도 2.0)과 같은 비율이다.
 */
@Preview(name = "Compact 420", widthDp = 420, heightDp = 606, showBackground = true)
@Preview(name = "Expanded 850", widthDp = 850, heightDp = 606, showBackground = true)
@Preview(name = "Full 1280", widthDp = 1280, heightDp = 606, showBackground = true)
annotation class V3SizePreviews

@Composable
private fun P(content: @Composable () -> Unit) = DongbanchaV3Theme { content() }

@V3SizePreviews @Composable private fun Preview_S01() = P { HomeScreen() }
@V3SizePreviews @Composable private fun Preview_S01_drive() = P { HomeScreen(HomeUiState(driving = true)) }
@V3SizePreviews @Composable private fun Preview_S02() = P { TripSetupScreen() }
@V3SizePreviews @Composable private fun Preview_S03() = P { ConsentScreen() }
@V3SizePreviews @Composable private fun Preview_S04() = P { VoiceGuideScreen() }
@V3SizePreviews @Composable private fun Preview_B01() = P { ListeningScreen() }
@V3SizePreviews @Composable private fun Preview_B01_quiet() = P { ListeningScreen(DrivingStates.B01_QUIET) }
@V3SizePreviews @Composable private fun Preview_Q01() = P { QuietCheckScreen() }
@V3SizePreviews @Composable private fun Preview_Q02() = P { SilenceQuestionScreen() }
@V3SizePreviews @Composable private fun Preview_S05() = P { DrivingScreen(DrivingStates.S05) }
@V3SizePreviews @Composable private fun Preview_S05_q_silence() = P { DrivingScreen(DrivingStates.S05_SILENCE) }
@V3SizePreviews @Composable private fun Preview_S06() = P { DrivingScreen(DrivingStates.S06) }
@V3SizePreviews @Composable private fun Preview_S07() = P { DrivingScreen(DrivingStates.S07) }
@V3SizePreviews @Composable private fun Preview_S08() = P { DrivingScreen(DrivingStates.S08) }
@V3SizePreviews @Composable private fun Preview_S09() = P { DrivingScreen(DrivingStates.S09) }
@V3SizePreviews @Composable private fun Preview_S10() = P { DrivingScreen(DrivingStates.S10) }
@V3SizePreviews @Composable private fun Preview_S11() = P { ArrivalScreen() }
@V3SizePreviews @Composable private fun Preview_S12() = P { MakingDiaryScreen() }
@V3SizePreviews @Composable private fun Preview_S13() = P { DiaryReadyScreen() }
@V3SizePreviews @Composable private fun Preview_S14() = P { DiaryScreen() }
@V3SizePreviews @Composable private fun Preview_S14_live() = P { DiaryScreen(DiaryUiState(record = Sample.JEJU, live = true)) }
@V3SizePreviews @Composable private fun Preview_S14_tabs_map() = P { DiaryScreen(DiaryUiState(tabs = true)) }
@V3SizePreviews @Composable private fun Preview_S14_tabs_no_songs() = P { DiaryScreen(DiaryUiState(record = Sample.GANGNEUNG_NO_SONGS, tabs = true)) }
@V3SizePreviews @Composable private fun Preview_R01() = P { RecordMapScreen() }
@V3SizePreviews @Composable private fun Preview_R01_live() = P { RecordMapScreen(RecordMapUiState(live = true)) }
@V3SizePreviews @Composable private fun Preview_P01() = P { StopNoticeScreen() }
@V3SizePreviews @Composable private fun Preview_P02() = P { StopNoticeScreen(StopNoticeUiState(stopped = true)) }
@V3SizePreviews @Composable private fun Preview_T01() = P { TripEmptyScreen() }
