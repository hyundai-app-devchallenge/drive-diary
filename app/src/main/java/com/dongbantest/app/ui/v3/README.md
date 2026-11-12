# ui/v3 — 확정 디자인 Compose 화면

동반차 확정안(PRD v3.0, 웹 프로토타입 `design/final-v3` 판⑦, Figma 13페이지와 같은 디자인)을 Jetpack Compose로 옮긴 **화면과 컴포넌트만** 있는 패키지입니다. 데이터·녹음·GPS·DB·ViewModel은 없습니다. 기존 `ui/theme`, `ui/screens`, `ui/components`, `DongbanNavHost`, `MainActivity`는 건드리지 않았고, 아직 아무 데도 연결되어 있지 않습니다.

## 구조

| 폴더 | 내용 |
|---|---|
| `theme/` | `DongbanchaV3Theme`, 색 토큰 `DbColors`(흰 바탕 + Hyundai Blue #002C5F, 기록 색 3개, 예시 지도 색), 글자 `DbTypography`(Pretendard, 웹 크기 토큰과 같은 sp, Material3 역할에도 매핑), `DbShapes`(8/14/16/20/알약), `Spacing`(2–48), `DbLayout`(Compact/Expanded/Full), 아이콘 `DbIcons`(웹과 같은 Lucide 경로) |
| `components/` | `AppShell`(상단 줄 + 본문 + 하단 탭바 또는 왼쪽 레일), `RecordingChip`(5 상태), `RoadRibbon`(진행·멈춤·도착), `VoiceToast`(성공/못 들음/확인 필요 + "아니야" 48dp), `RecordCard`(기록·진행 중), `PrimaryButton`/`OutlineButton`/`DbTextButton`/`CircleIconButton`/`PlayButton`, `DriveStatusBar`·`StoppedTopBar`·`StateChip`, `DbNavigationBar`·`DbNavigationRail`, `QuestionCard`·`SayList`·`QuietFace`, `DongbaniBubble`, `PhotoSlot`, `AskedPanel`·`AskedSummary`, `ExampleMap`(웹 SVG 예시 지도를 Canvas로), `DbBottomSheet`·`DbDialog`, 한국어 어절 줄바꿈 `Text` |
| `screens/` | 21화면. 각 화면은 `XxxUiState`(기본값 = 웹 시연 데이터)와 이벤트 람다만 받는 상태 없는 Composable. `ScreenCatalog`(화면 목록), `ScreenPreviews`(화면마다 3크기 미리보기), `SampleData`(시연 데이터) |

리소스: `res/font/pretendard_{regular,medium,semibold,bold}.otf`(SIL OFL 1.1, 라이선스 `assets/licenses/pretendard-OFL.txt`), `res/drawable/v3_dongbani.xml`(동반이).

## 화면 목록

| id | 함수 | 상태 |
|---|---|---|
| S01 홈 / 주행 중 홈 | `HomeScreen` | `HomeUiState(driving = true)`면 보기 전용 |
| S02 여행 설정 | `TripSetupScreen` | |
| S03 녹음 동의 | `ConsentScreen` | |
| S04 첫 여행 음성 안내 | `VoiceGuideScreen` | |
| B01 조용히 듣는 중 | `ListeningScreen` | `DrivingStates.B01`, `B01_QUIET` |
| Q01 자고 있나요? | `QuietCheckScreen` | |
| Q02 대화를 여는 질문 | `SilenceQuestionScreen` | |
| S05–S10 여행 중 | `DrivingScreen` | `DrivingStates.S05`, `S05_SILENCE`, `S06`…`S10` |
| S11 도착 | `ArrivalScreen` | |
| S12 다이어리 만드는 중 | `MakingDiaryScreen` | |
| S13 다이어리 완성 | `DiaryReadyScreen` | |
| S14 다이어리 | `DiaryScreen` | `DiaryUiState(live = true)`면 정차 중 진행 중 여행 |
| R01 기록 지도 | `RecordMapScreen` | `RecordMapUiState(live = true)` |
| P01·P02 정차 안내 | `StopNoticeScreen` | `stopped`, `drivingHome` |
| T01 진행 중인 여행 없음 | `TripEmptyScreen` | |

## 보는 법

Android Studio에서 `screens/ScreenPreviews.kt`를 열면 화면마다 **Compact 420 / Expanded 850 / Full 1280 × 606dp** 세 미리보기가 나옵니다(Pleos 창 840 / 1700 / 2560 × 1212px, 밀도 2.0과 같은 비율). 컴포넌트 상태별 미리보기는 `components/ComponentPreviews.kt`에 있습니다.

## 쓰는 법

```kotlin
DongbanchaV3Theme {
    DrivingScreen(
        state = DrivingUiState(
            tripTitle = trip.title,
            pillTitle = "${ribbon.destinationName}까지 1시간 40분",
            progress = ((ribbon.progress ?: 0f) * 100).roundToInt(),
            // …
        ),
        events = DrivingEvents(onTab = ::openTab, onVoiceExample = { /* 음성 명령 */ }),
    )
}
```

- 화면은 받은 폭으로 Compact(<640dp, 한 열 + 하단 탭바) / Expanded(<1100dp, 왼쪽 레일 + 두 열) / Full(≥1100dp, 넓은 폭 규칙)을 스스로 고릅니다.
- 색은 `DbTheme.colors`, 글자는 `DbTheme.type`만 씁니다(하드코딩 색 없음).
- 주행 화면의 음성 예시·녹음 칩·말풍선·진행 알약은 버튼 모양이 아닌 시연용 지름길입니다. 실제 앱에서는 해당 람다를 `null`로 두거나 음성 인식 결과에 연결하세요.
- 반복 애니메이션은 없습니다(녹음 점은 깜빡이지 않음). 터치 영역은 48dp 이상, 녹음 칩·알림은 `liveRegion = Polite`.

지금 앱의 ViewModel·라우트와 어떻게 잇는지는 [`docs/compose-ui-v3-integration.md`](../../../../../../../../../docs/compose-ui-v3-integration.md)를 보세요.
