# 확정안 v3 Compose 화면 연결 표

`app/src/main/java/com/dongbantest/app/ui/v3/`에 있는 확정 디자인 화면(웹 프로토타입 `design/final-v3` 판⑦, Figma 13페이지와 같은 디자인)을 지금 앱(`origin/main` b9e8404 기준)에 연결할 때 보는 표입니다. v3 화면은 **상태 없는 Composable**이라 데이터와 동작은 모두 파라미터로 받습니다. 아래 표는 v3 쪽 필드·람다마다 지금 앱의 어떤 값·함수를 넣으면 되는지, 아직 없는 값은 무엇을 만들어야 하는지 적은 것입니다.

> 한 줄 요약: v3 화면은 기존 화면 옆에 새 패키지로 들어와 있고 기존 코드는 하나도 바꾸지 않았습니다. `DongbanNavHost`의 각 `composable(...)`에서 기존 `XxxScreen(uiState = ...)` 호출을 v3 화면 + 아래 표의 변환으로 바꾸면 됩니다. 상시 녹음, 침묵 감지, 음성 명령처럼 확정안에 새로 생긴 동작은 기능 작업이 따로 필요합니다(5장).

## 1. 들어온 것과 바꾸는 순서

이미 이 브랜치에 들어와 있으므로 파일을 복사할 필요는 없습니다. 패키지는 `com.dongbantest.app.ui.v3.*`, 리소스는 `res/font/pretendard_*.otf`, `res/drawable/v3_dongbani.xml`, 폰트 라이선스는 `assets/licenses/pretendard-OFL.txt`입니다.

1. **테마**: 화면 바깥(예: `MainActivity`의 `DongbantestTheme { … }` 또는 NavHost의 각 목적지)을 `DongbanchaV3Theme { … }`로 감쌉니다. v3 화면은 `DbTheme.colors / type / layout`만 읽으므로 기존 `ui/theme`(Cocoa, Peach 등)와 섞여도 서로 영향이 없습니다. 기존 화면을 다 바꾼 뒤 `ui/theme`를 지울지는 팀이 정합니다.
2. **컴포넌트**: `ui/v3/components`는 그대로 씁니다. 기존 `ui/components`의 `RouteRibbonView`·`RouteMap`·`MapRoute`와 이름이 같은 것이 있으니(`MapRoute`) import 경로를 확인하세요. v3 `ExampleMap`은 시연용 Canvas 지도라, 실제 경로는 기존 MapLibre `RouteMap`을 같은 자리(R01 왼쪽, S14 지도 칸)에 넣는 것을 권합니다(3장 R01·S14 참고).
3. **화면**: `DongbanNavHost`에서 라우트별로 하나씩 바꿉니다. 각 v3 화면은 `XxxUiState` 하나와 이벤트 람다를 받으므로, ViewModel 상태를 그 `UiState`로 바꾸는 작은 함수(예: `fun TripUiState.toDrivingUiState(): DrivingUiState`)를 NavHost나 ViewModel 옆에 두면 됩니다. 기본값은 웹 시연 데이터(강릉 바다 여행)이니 실제 값으로 모두 채워야 합니다.
4. **크기**: v3 화면은 받은 폭으로 Compact(<640dp, 하단 탭바) / Expanded(<1100dp, 왼쪽 레일 + 두 열) / Full(≥1100dp)를 스스로 고릅니다. 따로 WindowSizeClass를 넘길 필요가 없습니다.

## 2. 화면 대응표

라우트는 `ui/DongbanNavHost.kt`의 `Routes`입니다.

| v3 화면 (함수) | 내용 | 지금 앱 화면 / 라우트 | 비고 |
|---|---|---|---|
| S01 `HomeScreen(HomeUiState)` | 홈(정차) | `HomeScreen` / `home` | 기존 홈의 차량 상태 패널(`VehicleStatusPanel`)·모의 주행 토글은 v3에 없음. 개발용으로 남기려면 별도 디버그 화면 필요 |
| S01 주행 중 (`HomeUiState(driving = true)`) | 주행 중 홈(보기 전용) | `HomeScreen` / `home` | `HomeUiState.safety.isRestricted`가 true일 때 이 변형 |
| S02 `TripSetupScreen` | 여행 설정 시트 | `NewTripScreen` / `newTrip` | 기존은 전체 화면 폼(제목·목적지·사람 이름). v3는 목적지·관계(가족/연인/친구)·인원 |
| S03 `ConsentScreen` | 녹음 동의 시트 | 없음 → **신규** | 기존은 녹음 버튼을 누를 때 동의 확인(`TripScreen` 안) |
| S04 `VoiceGuideScreen` | 첫 여행 음성 안내 | 없음 → **신규** | |
| B01 `ListeningScreen` | 조용히 듣는 중 | `TripScreen` / `trip/{tripId}` | 질문 카드 대신 조용한 면 |
| Q01 `QuietCheckScreen` | 자고 있나요? 알림 | 없음 → **신규** | |
| Q02 `SilenceQuestionScreen` | 대화를 여는 질문 알림 | 없음 → **신규** | |
| S05–S10 `DrivingScreen(DrivingUiState)` | 여행 중(상태별) | `TripScreen` / `trip/{tripId}` | S06 새 질문, S07 녹음 멈춤, S08 못 들음, S09 녹음 없음, S10 잘못 멈춤 되돌리기는 같은 화면의 상태 |
| S11 `ArrivalScreen(ArrivalUiState)` | 도착 시트 | `ArrivalScreen` / `arrival/{tripId}?name=` | 이름이 같으니 import 주의 |
| S12 `MakingDiaryScreen` | 다이어리 만드는 중 | 없음 → **신규** | 기존은 종료 후 바로 `diary/{tripId}` |
| S13 `DiaryReadyScreen` | 다이어리 완성 | 없음 → **신규** | |
| S14 `DiaryScreen(DiaryUiState)` | 다이어리(한 장) | `DiaryDetailScreen` / `diary/{tripId}` | 기존의 사진 붙이기·기록 삭제는 v3에 없음(편집은 비활성 버튼 자리만) |
| R01 `RecordMapScreen` | 기록 지도 + 목록 | `DiaryListScreen` / `diary` | 기존 책장 그리드 → 지도 + 기록 카드 |
| P01·P02 `StopNoticeScreen` | 정차 안내(주행 중 / 정차됨) | 없음 → **신규** | 기존은 `DrivingRestrictionBanner`·목록 3개 제한으로 처리 |
| T01 `TripEmptyScreen` | 진행 중 여행 없음 | 없음 → **신규** | 기존 홈의 "아직 진행 중인 여행이 없어요" 카드와 비슷 |
| (없음) | — | `MemoryCaptureScreen` / `capture/{tripId}` | 확정안에는 대응 화면이 없음(메모·사진은 정차 후 다이어리에서) |

## 3. 데이터 연결표

표기: `trip` = `TripWithMemories.trip`(`domain/model/Models.kt`), `mem` = `TripWithMemories.memories`, `drive` = `TripWithMemories.drive`(`DriveSummary`), `ribbon` = `TripUiState.ribbon`(`domain/model/RouteRibbon.kt`), `safety` = `DrivingSafetyState`(`vehicle/DrivingSafetyMonitor.kt`). 시간 포맷은 v3가 "09:20"(24시간 HH:mm)이고 기존 `formatTime`은 "오전 9:20"이므로 `SimpleDateFormat("HH:mm", Locale.KOREA)`를 새로 두어야 합니다.

### 공통 상단 줄·녹음 칩

| v3 필드 | 지금 앱의 값 | 변환 / 필요한 것 |
|---|---|---|
| `stopped` (주행 중 / 정차 중) | `safety.isRestricted` | `stopped = !safety.isRestricted` |
| `recording: RecordingState` (On/Paused/Off/Ready/Saved) | `TripUiState.isRecording` (Boolean) | **기능 필요**: 녹음 상태 enum(준비됨·녹음 중·멈춤·녹음 안 함·저장됨). 지금은 켜짐/꺼짐만 있음 |
| `recordingText` ("녹음 중 1시간 30분") | `TripUiState.recordingStartedAt` | 누적 녹음 시간 = 지금 − `recordingStartedAt`(+ 멈춤 전 누적). 상시 녹음이면 **기능 필요**: 여행 전체 누적 시간 |

### S01 홈 `HomeUiState` (v3) ← `viewmodel/HomeViewModel.kt`의 `HomeUiState`

| v3 필드 | 지금 앱의 값 | 변환 |
|---|---|---|
| `driving` | `safety?.isRestricted` | `== true` |
| `drivingGreeting` ("강릉 가는 중이에요") | `ongoingTrip?.destination` | `"${destination} 가는 중이에요"` |
| `liveTitle` | `ongoingTrip?.title` | `"${title} 진행 중"` |
| `liveProgress` | 없음(홈에는 ribbon이 없음) | **기능 필요**: `HomeViewModel`에 `NaviMonitor.route`를 묶어 `RouteRibbon.progressOf(total, remain)`×100 |
| `pastTrips[]` (id, title, date, tone) | 없음(홈은 지난 여행을 안 읽음) | **기능 필요**: `TripRepository.observeTrips()`에서 끝난 여행 최근 2개. `date = formatShortDate(startedAt)`, `tone`은 아래 "기록 색" 참고 |
| `pastLocked` ("지난 여행 2개는 …") | 위 목록 개수 | 문구에 개수 |
| (진행 중 여행 있음 → 여행 탭 대상) | `ongoingTrip` | `ongoingTrip == null`이면 여행 탭은 T01 |

### S02 `TripSetupUiState` ← `NewTripScreen`의 입력

| v3 필드 | 지금 앱 | 변환 / 필요한 것 |
|---|---|---|
| `destination` | `Trip.destination` (`HomeViewModel.startTrip(destination=…)`) | 그대로 |
| `selectedCompanion` (가족/연인/친구) | 없음 | **기능 필요**: 관계 필드(`Trip`에 없음). 질문 카드 태그와 다이어리에 쓰임 |
| `headcount` | `Trip.companions.size` | 기존은 이름 목록. v3는 숫자만 받으므로 이름 없이 인원만 저장할지, 이름 입력을 다른 곳에 둘지 결정 필요 |
| (여행 제목) | `Trip.title` | v3 설정 시트에는 제목 칸이 없음 → `startTrip(title = "${destination} 여행")`처럼 자동 생성 권장 |

### S04–S11, B01, Q01, Q02 `DrivingUiState` ← `TripUiState` (`viewmodel/TripViewModel.kt`)

| v3 필드 | 지금 앱의 값 | 변환 / 필요한 것 |
|---|---|---|
| `tripTitle` | `trip.title` | 그대로 |
| `pillTitle` ("강릉 경포까지 1시간 40분") | `ribbon.destinationName`, `ribbon.remainSeconds` | `"${destinationName}까지 ${h}시간 ${m}분"`(`formatElapsed(remainSeconds*1000L)`와 같은 규칙). 도착 후(S11)는 `"${destinationName}에 도착했어요"` |
| `pillSub` ("09:20 서울 마포 출발") | `ribbon.startedAt`, `ribbon.originName` | `"${HH:mm(startedAt)} ${originName} 출발"`. `originName`이 null이면 시간만 |
| `people` ("친구 4명") | `ribbon.companionCount` | `"${관계} ${companionCount}명"` — 관계는 **기능 필요**(위 S02) |
| `progress` (0–100) | `ribbon.progress` (0–1, null 가능) | `((progress ?: 0f) * 100).roundToInt()` |
| `from` / `to` | `ribbon.originName` / `ribbon.destinationName` | null이면 "출발" / "목적지" |
| `bubble` (동반이 말) | 없음 | 상태별 고정 문구(`Sample.BUBBLE_*`)를 녹음 상태로 고르면 됨 |
| `card = Question(question, say, tag, …)` | `TripUiState.question` (`QuestionBank`) | `question = uiState.question`, `tag` = 관계(**기능 필요**), `say`는 녹음 상태별 고정 목록(`Sample.SAY_ON/PAUSED/OFF`) |
| `card = Quiet(...)` (B01) | 없음 | **기능 필요**: "질문 없이 듣는 중" 상태(침묵 감지 전) |
| `asked: List<AskedItem>` (time, title, now) | `mem.filter { it.type == MemoryType.QUESTION }` | `time = HH:mm(createdAt)`, `title = text`, 맨 마지막 것만 `now = true`. 최신순 정렬 |
| `nowText` (B01 "듣는 중") | 없음 | B01·질문 그만 상태일 때 고정 문구 |
| `toast: VoiceToastUi` (S06/S08/S10) | `TripUiState.message` (Snackbar 문자열) | **기능 필요**: 음성 명령 결과(성공·못 들음·확인 필요 + 들은 명령). 지금 `message`는 문자열 한 줄이라 종류를 구분할 수 없음 |
| `footnote` | 없음 | 고정 문구 |
| Q01 `QuietCheckUiState` / Q02 `SilenceQuestionUiState` | 없음 | **기능 필요**: 침묵 감지(일정 시간 대화 없음) → Q01 → 응답 시 Q02. Q02 `question`은 `QuestionBank.next()`로 충분 |

### S11 `ArrivalUiState` ← `ArrivalScreen`(기존)과 같은 입력

| v3 필드 | 지금 앱의 값 | 변환 |
|---|---|---|
| `title` | 라우트 인자 `name`(= `ArrivalEvent.destinationName`) | `"${name}에 도착했어요"` |
| `saved` ("녹음 저장됨 3시간 10분") | `mem.filter { type == VOICE }.sumOf { durationMs ?: 0 }` | `"녹음 저장됨 ${formatElapsed(합)}"` |
| `fromLabel` / `toLabel` | `trip.startPlace`·`trip.startedAt` / `trip.endPlace`·`trip.endedAt` | `"${place} ${HH:mm(time)}"` |
| `background` | 위 `DrivingUiState` | `progress = 100`, `recording = Saved` |

### S12 `MakingDiaryUiState`, S13 `DiaryReadyUiState`

| v3 필드 | 지금 앱의 값 | 변환 / 필요한 것 |
|---|---|---|
| S12 `progress`, `steps[]` | 없음 | **기능 필요**: 다이어리 생성 단계(녹음 저장 → 글로 옮기기 → 꾸미기). `Memory.transcript`는 있지만 STT는 미지원(null) |
| S12 `body`의 녹음 시간 | VOICE `durationMs` 합 | `formatElapsed` |
| S13 `summary` 이야기 수 | `mem.count { type == VOICE }` (또는 QUESTION 포함) | `"${n}개"` |
| S13 `summary` 사진 자리 | `mem.count { type == PHOTO }` | `"${n}곳"` |
| S13 `summary` 경로, `from`/`to` | `trip.startPlace` → `trip.endPlace` | null이면 `trip.destination` |
| S13 `tone` | trip id | "기록 색" 참고 |

### S14 `DiaryUiState.record: TripRecordUi` ← `TripUiState.data`

| v3 필드 | 지금 앱의 값 | 변환 |
|---|---|---|
| `id` | `trip.id` | `toString()` |
| `title` | `trip.title` | 그대로 |
| `dateLabel` ("2026년 10월 10일 토요일") | `trip.startedAt` | `SimpleDateFormat("yyyy년 M월 d일 EEEE", Locale.KOREA)` (기존 `formatDate`는 "(토)" 형식) |
| `dateShort` | `trip.startedAt` | `formatShortDate` |
| `people` | `trip.companions` | 그대로(첫 글자가 원 안에 들어감) |
| `relation` | 없음 | **기능 필요**(관계) |
| `from`, `startTime` | `trip.startPlace`, `trip.startedAt` | `HH:mm` |
| `to`, `arriveTime` | `trip.endPlace ?: trip.destination`, `trip.endedAt` | `HH:mm` |
| `duration` ("3시간 10분") | `trip.endedAt - trip.startedAt` | `formatElapsed(...)` (같은 규칙) |
| `photos` (캡션) | `mem.filter { type == PHOTO }` | 지금은 캡션이 없어 빈 문자열 또는 시간. v3 `PhotoSlot`은 사진 자리만 그림 → 실제 사진(`filePath`)을 그리려면 `PhotoSlot` 자리에 `AsyncImage`류를 넣는 작은 수정 필요 |
| `talks[]` (time, title, audio, fromQuestion) | `mem.filter { type == VOICE }` (+ QUESTION) | `time = HH:mm(createdAt)`, `audio = formatDuration(durationMs ?: 0)`, `title = transcript ?: "목소리 ${HH:mm}"`(STT가 없으므로), `fromQuestion`은 **기능 필요**(녹음과 질문의 연결) |
| `playingTime` | `TripUiState.playingPath` | 재생 중인 VOICE의 `filePath == playingPath`인 줄의 `time` |
| 지도 칸 | `drive.path`, `drive.timeline` | 기존 `RouteMap(listOf(MapRoute(trip.id, RouteColors.forTrip(trip.id), drive.path, drive.timeline)))`을 `ExampleMap` 자리(높이 120dp, 모서리 14dp)에 |
| `live` | `trip.isOngoing && !safety.isRestricted` | 정차 중에 진행 중인 여행의 기록을 볼 때 |

### R01 `RecordMapUiState` ← `DiaryListUiState` (`viewmodel/DiaryListViewModel.kt`)

| v3 필드 | 지금 앱의 값 | 변환 |
|---|---|---|
| `records[]` | `trips` (끝난 여행만) | 위 S14 `TripRecordUi` 변환. 카드에는 `title`, `dateShort`, `from`, `to`, `tone`만 씀 |
| `subtitle` ("함께 달린 길 3개") | `trips.size` | 문구 |
| 지도 | `routes: Map<Long, List<List<LocationPoint>>>` | `ExampleMap` 대신 `RouteMap(routes.map { MapRoute(id, RouteColors.forTrip(id), it) }, onRouteClick = …)` |
| `live`, `liveProgress` | `HomeViewModel`의 `ongoingTrip` + `ribbon` | `DiaryListViewModel`은 진행 중 여행을 걸러 내므로 **기능 필요**: 진행 중 여행·진행률을 함께 내보내기 |
| `savedChip` ("기록 3개 저장됨") | `trips.size` | 문구 |
| (주행 중이면) | `isRestricted` | v3에서는 주행 중 기록 탭 = P01 정차 안내. 목록 3개 제한 대신 R01을 열지 않음 |

### P01·P02 `StopNoticeUiState`

| v3 필드 | 지금 앱의 값 | 변환 |
|---|---|---|
| `stopped` | `safety.isRestricted` | P01 = true(주행 중) → 값이 false로 바뀌면 P02. 기존 `DrivingSafetyMonitor.state`를 그대로 구독하면 됨 |
| `readyBody` | 열 대상 | 기록 지도면 기본 문구, 지난 여행이면 `"안전하게 정차했어요. ${title} 다이어리를 열게요."` |
| `drivingHome` | 들어온 곳 | 홈에서 열었으면 true(배경이 주행 중 홈) |

### 기록 색 `TripTone`

v3는 시연용 세 색(`trip-gangneung #002C5F`, `trip-jeju #0A8F7A`, `trip-yangpyeong #C26A00`)을 `TripTone` enum과 테마 토큰으로 둡니다. 지금 앱은 `ui/components/RouteColors.forTrip(tripId)`가 8색을 id로 순환합니다. **둘 중 하나로 맞춰야** 합니다: (a) `TripTone`을 id로 순환(`TripTone.entries[floorMod(id, 3)]`) — 확정 디자인 색 유지, (b) `RouteColors` 팔레트를 v3 토큰 세 색 이상으로 바꿈. 지도(`RouteMap`)와 카드 점·리본이 같은 색이어야 합니다(PRD R01).

## 4. 이벤트 연결표

| v3 람다 (화면) | 지금 앱 함수 | 비고 |
|---|---|---|
| `onTab(DbTab)` (모든 화면) | `navController.navigate(...)` | 홈 → `home`, 여행 → `trip/{ongoingTrip.id}`(없으면 T01), 기록 → `diary`. 주행 중 기록 탭은 P01. **신규**: 3탭 라우팅(지금은 탭 없이 버튼 이동) |
| `onStartTrip` (S01, T01) | `navController.navigate(Routes.NEW_TRIP)` | 기존 `onNewTrip`와 같음 |
| `onOpenPastTrip(id)` (S01) | `navController.navigate(Routes.diaryDetail(id))` | |
| `onOpenLiveTrip` (S01 주행 중, R01 live) | `navController.navigate(Routes.trip(id))` | 기존 `onContinueTrip` |
| `onPastLocked` (S01 주행 중) | P01로 이동 | **신규** 라우트 |
| `onDestinationChange` / `onCompanionSelect` / `onHeadcountChange` (S02) | 화면 로컬 상태(`rememberSaveable`) | |
| `onStart` (S02) | S03으로 이동 | 기존은 바로 `HomeViewModel.startTrip` |
| `onConsent` (S03) | `HomeViewModel.startTrip(...)` 후 S04, 그다음 상시 녹음 시작 | **기능 필요**: 여행 전체 상시 녹음. 지금 `TripViewModel.startRecording()`은 한 조각 녹음이고 `onScreenHidden()`이 화면을 떠나면 녹음을 취소함 |
| `onWithoutRecording` (S03) | `HomeViewModel.startTrip(...)` 후 S09 상태 | 녹음 안 함 상태 저장 **기능 필요** |
| `onStart` (S04 "알겠어요, 출발") | B01로 이동 | |
| `onVoiceExample(say)` (주행 화면, Q01, Q02) | 시연용 지름길 | 실제로는 음성 인식 결과가 같은 동작을 부름. "다른 질문 해 줘" → `TripViewModel.nextQuestion()`, "녹음 잠시 멈춰" → `stopRecording()`(멈춤 의미로), "다시 녹음해 줘"/"녹음 시작해 줘" → `startRecording()`, "질문 그만" → **기능 필요**(이번 여행 질문 끄기), "아니, 안 자" → Q02. **기능 필요**: 음성 명령 인식 |
| `onRecordingChip` (주행 상단 칩) | 시연용 지름길 | 실제 앱에서는 null(칩은 표시 전용) 권장 |
| `onBubble`, `onTripPill` | 시연용 지름길 | 실제 앱에서는 null |
| `onToastSay("아니야")` (S10) | `startRecording()` | 잘못 멈춤 되돌리기 |
| `onConfirm` (S11) | 기존 `ArrivalScreen.onConfirm` → S12로 | 기존은 `diary/{id}`로 바로 감 |
| `onTimeline` (S11 "시간선 보기") | `navController.navigate(Routes.diaryDetail(tripId))` | |
| `onNotYet` (S11) | `TripViewModel.keepTraveling { … }` | 기존 `onKeepTraveling`와 같음 |
| `onOpenDiary` (S13) | `navController.navigate(Routes.diaryDetail(tripId))` | |
| `onLater` (S13) | 홈으로 | |
| `onBack` (S14) | `navController.popBackStack()` | |
| `onNextPage` (S14) | 다음 기록 id로 `diary/{id}` | **신규**: 기록 순서(최신순) 다음 id 계산 |
| `onPlay(TalkUi)` (S14) | `TripViewModel.togglePlay(path)` | `TalkUi`에 `filePath`가 없으므로 time → Memory 매핑을 두거나 `TalkUi`에 경로 필드 추가 |
| `onOpenRecord(id)` (R01) | `navController.navigate(Routes.diaryDetail(id))` | 기존 `onOpenTrip` |
| `onContinue` (P02) | R01 또는 `diary/{id}` | |
| `onClose` (P01·P02) | `popBackStack()` | |
| (v3에 없음) | `saveQuestion`, `addTextMemo`, `addPhoto`, `recordArrival`, `deleteMemory`, `deleteTrip`, `endTrip` | 확정안 화면에 버튼이 없음. `endTrip`은 도착 자동 종료(`ArrivalManager`)가 대신함. 사진·메모·삭제는 정차 중 다이어리 편집(아직 비활성)으로 옮길 자리 |

## 5. 흐름 차이 요약

| 항목 | 지금 앱(`main`) | 확정안(v3) | 필요한 일 |
|---|---|---|---|
| 내비게이션 | 탭 없음, 버튼으로 이동(`DongbanNavHost`) | 3탭(홈·여행·기록), Compact 하단 탭바 / Expanded·Full 왼쪽 레일 | 화면만: 탭 라우팅 추가 |
| 녹음 | 녹음 버튼 + 동의 확인, 한 조각씩 저장, 화면을 떠나면 취소 | 출발 전 한 번 동의 → 여행 내내 상시 녹음, 말로 멈춤·재개 | **기능**: 상시 녹음 서비스, 녹음 상태 enum, 누적 시간 |
| 질문 | "다음 질문" 버튼(`nextQuestion`), "추억에 담기"(`saveQuestion`) | 대화가 끊겼을 때만 질문(B01 → Q01 → Q02), 말로 다음 질문·그만 | **기능**: 침묵 감지, 질문을 건넨 시각을 QUESTION 메모로 자동 저장 |
| 음성 명령 | 없음 | 다른 질문 해 줘 / 질문 그만 / 녹음 잠시 멈춰 / 다시 녹음해 줘 / 녹음 시작해 줘 / 아니야, 결과 알림(S06·S08·S10) | **기능**: 음성 인식과 결과 상태 |
| 길 리본 | `RouteRibbonView`(이모지 라벨) | `RoadRibbon`(출발 점·차 표식·깃발, 멈춤 회색, 도착 원) | 화면만: `ribbon` 값을 그대로 넣으면 됨 |
| 도착 | `ArrivalManager` 자동 종료 → `arrival/{id}` | S11 시트 → S12 만드는 중 → S13 완성 → S14 | 화면만 + S12 단계 상태는 **기능** |
| 주행 중 제한 | `DrivingRestrictionBanner`, 목록 3개만, 사진 숨김 | 주행 중 홈(보기 전용), 기록 탭·지난 여행 → 정차 안내 P01 → 정차하면 P02 | 화면만: `safety.isRestricted`로 분기 |
| 기록 목록 | 책장 그리드 + 지도(`DiaryListScreen`) | 기록 지도 + 기록 카드(R01), 진행 중 여행 고정 | 화면 + 진행 중 여행을 목록에 포함하는 작은 VM 수정 |
| 다이어리 | 메모·사진·삭제 가능(`DiaryDetailScreen`) | 한 장 보기(사람·지도·함께 달린 시간·사진 자리·이야기 재생), 다음 장 | 화면 + STT 제목·질문 연결은 **기능** |
| 메모 화면 | `MemoryCaptureScreen` | 없음 | 정차 중 편집으로 옮길지 팀 결정 |
| 화면 크기 | 폰 기준 | Compact 420 / Expanded 850 / Full 1280dp(Pleos 창 840·1700·2560px, 밀도 2.0) | 화면만(자동) |
| 디자인 | Cocoa·Peach 톤, 이모지 | 흰 바탕 + Hyundai Blue #002C5F, Pretendard, 이모지 없음 | 화면만: `DongbanchaV3Theme` |
