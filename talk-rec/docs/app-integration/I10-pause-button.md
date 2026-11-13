# I10: v3 운전 화면 녹음 일시정지·재개 버튼

작성일: 2026-11-13. 작업 위치: `C:\github\drive-diary-int`, 브랜치: `feat/talk-rec-integration`.
커밋·푸시·브랜치 전환은 하지 않았다.

## 변경 내용

- `app/src/main/java/com/dongbantest/app/ui/v3/screens/DrivingScreens.kt`
  - `DrivingUiState`에 기본값이 있는 `recordingToggleText: String? = null`, `recordingToggleEnabled: Boolean = true`를 추가했다.
  - `DrivingEvents.onRecordingToggle`에도 빈 콜백 기본값을 제공했다.
  - 기존 Outline 스타일의 “이 순간 담기” 바로 아래에 Primary 스타일의 `DbButton`을 배치했다. 녹음 제어 버튼의 터치 영역은 최소 76dp이며 폭을 채운다.
- `app/src/main/java/com/dongbantest/app/ui/v3connect/V3Mappers.kt`
  - 여행이 진행 중이고 `RecordingMode.ON` 또는 `PAUSED`일 때만 녹음 제어 버튼 문구를 매핑한다.
  - “이 순간 담기”는 진행 중 + `RecordingMode.ON` + `CaptureStatus.On`을 모두 만족할 때만 표시한다. 서비스가 Ready/Paused/Off이면 숨긴다.
  - 기존에 작업 중이던 태그 관련 변경은 유지했다.
- `app/src/main/java/com/dongbantest/app/ui/v3connect/V3NavHost.kt`
  - ON에서 터치하면 `vm.onVoiceCommand("녹음 잠시 멈춰")`, PAUSED에서 터치하면 `vm.onVoiceCommand("다시 녹음해 줘")`를 호출한다. ViewModel 상태나 컨트롤러 호출을 UI에 복제하지 않았다.
  - 기존 `showMessage` / `WithMessages` Snackbar를 통해 각각 “녹음을 잠시 멈췄어요”, “다시 녹음할게요”를 표시한다. 기존 음성 피드백도 그대로 동작한다.
  - 터치 직후 1.5초 동안 녹음 제어 버튼을 비활성화하고 콜백에서도 재입력을 차단한다. 이 전환 구간에는 “이 순간 담기” 콜백도 무시한다.
  - 두 콜백 모두 호출 시점의 최신 ViewModel/캡처 상태로 표시 자격을 다시 확인한다.

`TripViewModel.kt` 및 `TalkRecordingController`는 수정하지 않았다. 상단 녹음 칩의 터치 동작은 추가하지 않았다. 전사 텍스트를 표시하는 코드는 추가하지 않았다.
I8c 담당 경로(`app/src/debug/**`, `talkrec/src/debug/**`, `talkrec/tools/**`, `talkrec/README.md`)는 수정하지 않았다.

## 동작표

| 여행 상태 | 녹음 모드 | 캡처 서비스 상태 | 녹음 제어 버튼 | 터치 경로와 메시지 | 이 순간 담기 |
| --- | --- | --- | --- | --- | --- |
| 진행 중, 동의 있음 | ON | On | 녹음 멈추기 | 기존 일시정지 음성 명령 → “녹음을 잠시 멈췄어요” | 표시, 녹음 전환 직후 1.5초간 호출 차단 |
| 진행 중, 동의 있음 | ON | Ready / Paused / Off | 녹음 멈추기 | 기존 일시정지 음성 명령 → “녹음을 잠시 멈췄어요” | 숨김 |
| 진행 중, 동의 있음 | PAUSED | 모든 상태 | 다시 녹음하기 | 기존 재개 음성 명령 → “다시 녹음할게요” | 숨김 |
| 종료됨 | 모든 모드 | 모든 상태 | 숨김 | 호출 차단 | 숨김 |
| 진행 중, 동의 없음 | OFF | 모든 상태 | 숨김 | 호출 차단 | 숨김 |
| 진행 중, 동의 전 | READY | 모든 상태 | 숨김 | 호출 차단 | 숨김 |
| 여행 데이터 로딩 중/없음 | 모든 모드 | 모든 상태 | 숨김 | 호출 차단 | 숨김 |

정차 여부는 여행 종료 여부와 구분한다. 여행이 계속 진행 중이면 정차 중에도 녹음 제어 버튼을 제공한다.

## 검증

Gradle은 실행하지 않았다. `.talkrec-notes/verify-i8*-ui.ps1`의 캐시 컴파일러 방식을 참고하여 I10 전용 검사 파일을 만들었다. 다른 작업의 출력 폴더와 겹치지 않도록 `i10-*` 경로를 사용한다.

재실행 명령:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i10-ui.ps1
```

- 실제 캐시된 Kotlin 2.2.10 + Compose 컴파일러로 v3 화면 소스 전체, `V3Mappers.kt`, `V3NavHost.kt`, 읽기 전용으로 포함한 현재 `V3CatalogActivity.kt`의 타입 검사 통과.
- ViewModel 및 RouteRibbon 소스도 함께 검사하여, 별도 모듈의 공개 속성을 읽을 때 생기는 스마트 캐스트 제한을 피했다. 해당 소스는 수정하지 않았다.
- `.talkrec-notes/I10DrivingCheck.kt`: 종료 여부 2종 × 녹음 모드 4종 × 캡처 상태 4종 = 32가지 조합의 버튼 표시 조건 검사 통과. 여행 데이터 없음 및 기본 생성자·기본 콜백 호환성도 통과.
- 변경한 세 소스 파일에 대한 `git diff --check` 통과.
- 로그: `.talkrec-notes/i10-ui-check.log`.
- 검사에는 이전 빌드의 앱 클래스와 Android 대체 타입을 사용했다. Android SDK 빌드, 리소스·매니페스트·패키징, AVD 터치·오디오 검증을 대신하지 않는다.

## 열린 이슈 / Claude 인계

- Claude의 Gradle 빌드와 AVD 확인이 남아 있다. ON→PAUSED→ON 실제 캡처 전환, 정확한 Snackbar 문구, 빠른 연속 터치 차단, 여러 화면 폭에서 버튼 배치와 76dp 터치 영역을 확인해야 한다.
- 마이크 권한이나 서비스 시작 실패에 따른 재개 결과는 기존 음성 명령 경로의 동작을 따른다. UI 피드백은 재개 요청을 나타내며, “이 순간 담기”는 서비스가 실제 On을 보고할 때까지 나타나지 않는다.
- `CaptureState`에는 여행 ID가 없어 프로세스 전역 상태를 읽는 기존 구조가 유지된다.

## git status --short

아래는 완료 시점 작업 트리 전체 출력이다. I10의 앱 소스 변경은 `DrivingScreens.kt`, `V3Mappers.kt`, `V3NavHost.kt` 세 파일이다. 나머지는 작업 시작 전부터 있던 변경 또는 병행 작업의 변경이며 유지했다. `.talkrec-notes`는 Git 무시 대상이므로 이 문서와 검증 보조 파일은 아래에 나타나지 않는다.

```text
 M app/src/debug/AndroidManifest.xml
 M app/src/debug/java/com/dongbantest/app/debug/V3CatalogActivity.kt
 M app/src/main/AndroidManifest.xml
 M app/src/main/java/com/dongbantest/app/ui/v3/README.md
 M app/src/main/java/com/dongbantest/app/ui/v3/components/Basics.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/DrivingParts.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DiaryScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DrivingScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/SampleData.kt
 D app/src/main/java/com/dongbantest/app/ui/v3/screens/ScreenCatalog.kt
 M app/src/main/java/com/dongbantest/app/ui/v3connect/V3Mappers.kt
 M app/src/main/java/com/dongbantest/app/ui/v3connect/V3NavHost.kt
 M talkrec/README.md
 M talkrec/src/main/assets/prompts/summary.md
 M talkrec/src/main/java/com/drivediary/talkrec/TalkRec.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/CaptureProcessor.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/ConversationCaptureService.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/PleosSttClient.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/DisplayTags.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryProviders.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryResult.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummarySettingsStore.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryValidator.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/DisplayTagsTest.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryProcessorTest.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryProvidersTest.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryTestData.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryValidatorTest.kt
?? app/src/debug/java/com/dongbantest/app/debug/LabAudioFiles.kt
?? app/src/debug/java/com/dongbantest/app/debug/TalkRecLabActivity.kt
?? app/src/debug/res/
?? app/src/main/res/drawable/ic_dongban_car_background.xml
?? app/src/main/res/drawable/ic_dongban_car_foreground.xml
?? app/src/main/res/mipmap-anydpi-v26/
?? talkrec/src/debug/java/com/drivediary/talkrec/debug/DecodedAudioFileSource.kt
?? talkrec/src/debug/java/com/drivediary/talkrec/debug/LabSink.kt
?? talkrec/src/debug/java/com/drivediary/talkrec/debug/Mono16kResampler.kt
?? talkrec/src/debug/java/com/drivediary/talkrec/debug/TalkRecLab.kt
?? talkrec/src/main/java/com/drivediary/talkrec/TalkRecDebug.kt
?? talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryTryOut.kt
?? talkrec/src/test/java/com/drivediary/talkrec/capture/CaptureProcessorDebugTest.kt
?? talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryTryOutTest.kt
?? talkrec/src/testDebug/
?? talkrec/tools/push-lab-audio.ps1
```
