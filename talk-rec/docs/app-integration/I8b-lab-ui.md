# I8b — 인식 실험실 화면, 디버그 도구 정리, 임시 아이콘

작업 위치: `C:\github\drive-diary-int`  
브랜치: `feat/talk-rec-integration`  
커밋·푸시·브랜치 전환 없음. Gradle/AVD 실행 없음.

## 변경 파일

| 파일 | 변경 내용 |
| --- | --- |
| `app/src/debug/java/com/dongbantest/app/debug/TalkRecLabActivity.kt` | 디버그 전용 Compose 실험실 Activity와 메모리 전용 화면 상태, 입력·이벤트·제목 시험 연결 |
| `app/src/debug/java/com/dongbantest/app/debug/V3CatalogActivity.kt` | 화면 목록과 화면 열기/뒤로가기 로직 제거, 인식 실험실 진입 추가. 요약 설정과 예시 여행 넣기/지우기 유지 |
| `app/src/debug/AndroidManifest.xml` | 인식 실험실 등록, 디버그 런처 Activity에 전용 icon/roundIcon 지정. 작업 시작 전 존재하던 taskAffinity 변경 보존 |
| `app/src/main/AndroidManifest.xml` | application의 icon/roundIcon 두 속성만 새 본 앱 아이콘으로 교체 |
| `app/src/main/java/com/dongbantest/app/ui/v3/screens/ScreenCatalog.kt` | 삭제. 목록 UI 제거 후 소스에서 `ScreenCatalog`/`ScreenEntry` 사용처 없음 확인 |
| `app/src/main/java/com/dongbantest/app/ui/v3/README.md` | 구조 표의 ScreenCatalog 언급 제거 |
| `talkrec/src/test/java/com/drivediary/talkrec/capture/CaptureProcessorDebugTest.kt` | 실제 시간 대기 대신 명시적 테스트 디스패처 사용. 네 테스트의 이벤트 구독·취소·수신을 결정적으로 진행 |
| `talkrec/src/debug/java/com/drivediary/talkrec/debug/TalkRecLab.kt` | `stopping: StateFlow<Boolean>` 추가. Activity 재생성 중에도 이전 실험 정리 상태를 표시하며, 서비스 종료/소유권 교체 시 해제 |
| 아래 아이콘 XML 6개 | 본 앱/디버그용 임시 벡터 adaptive icon |

I9b의 summary 소스·프롬프트·테스트, `V3Mappers.kt`, v3 태그 표시 코드는 수정하지 않았다. `TalkRecDebug.kt`도 I8b에서 수정하지 않았다. 아래 git 상태에는 기존 I8 코어 변경과 동시 진행 중인 I9b 변경이 함께 포함돼 있다.

## 실험실 여는 방법

디버그 빌드를 설치한 뒤 주황색 자동차/렌치 아이콘의 **동반차 디버그 → 인식 실험실 열기**를 누른다.

새 Activity는 `.debug.TalkRecLabActivity`, label은 `인식 실험실`, `exported=false`, `taskAffinity=com.dongbantest.app.debugtools`다. 별도 런처 항목은 없다. 기존 `V3CatalogActivity` 클래스 이름과 `동반차 디버그` label은 유지했다.

- **PC 마이크**: 시작을 누르면 RECORD_AUDIO가 없을 때 요청한다. 에뮬레이터 `Extended controls > Microphone > "Enable Host Microphone Access"`를 켜야 한다는 안내를 표시한다. AVD 기본값은 꺼짐이다.
- **음성 파일**: `파일 고르기`는 `OpenDocument`로 `audio/*`, `application/ogg`를 허용한다. `OpenableColumns.DISPLAY_NAME`만 조회하며 URI/path를 이름으로 대체하지 않는다. 이름 조회 실패 시 `선택한 음성 파일`로 표시한다. `소리도 같이 재생` 기본값은 켜짐이다. 동일한 microphone-type FGS를 쓰므로 파일 모드도 RECORD_AUDIO를 요청한다.
- 실험 실행 중 입력 모드/파일을 바꾸려면 **정지 → 서비스 정리 완료 → 다시 시작** 순서다. EOF 후에도 결과를 보며 유지할 수 있고 `파일 끝` 배너가 나온다.
- `canStart()`가 false이면 시작을 끈다. 실행 중인 실험, 이전 실험 정리, 여행 녹음 중을 구분해 표시한다. 권한 응답 이후 등 경합은 `LabBusyException`을 잡아 짧은 한국어 문구로 안내한다.

## 화면 구성

차량의 넓은 화면에서는 실시간 인식/묶음 판정을 좌우 두 패널로 표시한다. 폭이 900dp 미만이면 위아래로 배치한다.

### 상단

현재 설정의 **제공자와 모델**을 표시한다. 제목 시험 버튼을 눌렀을 때 해당 제공자로 대화가 전달된다는 설명, 입력 모드, 시작/정지, 파일 선택/재생 옵션, 지우기 버튼이 있다. 음성 인식 오류와 파일 오류는 예외 본문·URI 없이 한국어 배너로 표시한다.

기존 `drivingSafetyMonitor`를 사용해 정차 중에만 제목 시험을 허용한다. 요약 설정 화면과 같은 이유로 실험실이 앞에 있는 동안 모니터를 다시 시작한다.

### 실시간 인식 패널

- `CaptureState.status`, 프레임 수, 확정 수, 묶음 수, 채택 수, 오류 수.
- PCM16 RMS를 `20 * log10(rms / 32768)`로 변환한 -60..0 dBFS 막대. RMS를 0..1 정규화 값으로 오해하지 않는다.
- 현재 Partial은 회색으로 표시하며, Final/파일 종료/정지 때 비운다.
- Final 목록은 최근 200개로 제한한다. 최신은 아래이며, 200개 제한에 도달한 뒤에도 별도 증가값을 기준으로 자동 스크롤한다.
- 채택 수는 실험 메모리 승인 수이며 여행 파일 저장 수가 아님을 화면에 설명한다.

### 묶음과 판정 패널

최근 100개를 `blockId`로 upsert한다. Decision이 먼저 오거나 BlockClosed가 누락돼도 같은 카드에 판정을 붙이며, 상세 누락 문구를 표시한다.

각 카드에는 종료 이유(무음/길이/수동), source(자동/수동), 길이, 펼치기/접기가 가능한 문장, 한국어 outcome, 소수 둘째 자리 유지 확률, kind, 정책 규칙 ID, judge ms를 표시한다.

outcome 표기: 저장 / 판정 탈락 / 기록 거부 규칙 / 명령 섞여 버림 / 직전 대화 지움 / 빈 묶음 / 시간 한도 / 저장 거절 / 판정 실패. 판정 실패 후 fallback으로 채택된 경우 추가로 설명한다.

**제목 만들어 보기**는 해당 블록의 `lines.joinToString("\n")`를 앱의 기존 `summarySettingsStore.trySummarize()`에 넘긴다. 자동 호출·자동 저장은 없다. 성공 시 제목, 기존 `SummaryResult.tags` 목록, 실제 응답 모델, 경과 시간을 표시하고 실패 시 안전한 오류 코드와 경과 시간을 표시한다. 현재 I9b의 `tags` getter는 category + keywords를 반환하므로 추가 변경 없이 표시된다.

## 개인정보·작업 수명

화면 상태는 Activity 인스턴스 메모리에만 둔다. 본문·파일 URI를 `rememberSaveable`, `SavedStateHandle`, 설정, DB, 파일에 저장하지 않고 transcript를 로그로 출력하지 않는다. 장기 URI 권한도 취득하지 않는다.

- **지우기**: partial/finals/blocks/summary/배너를 지우고 진행 중인 제목 시험을 취소한다. 녹음 자체는 계속되며 이후 이벤트는 새로 표시된다. 입력 모드와 선택한 파일 이름은 유지한다.
- **새 시작**: 이전 표시 결과와 제목 시험을 정리한다.
- **목록 제한으로 밀려난 블록**: 해당 요약 job을 취소하고 카드 텍스트를 비운다.
- **Activity destroy**: `TalkRecLab.stop()` 호출 후, finally에서 모든 표시 텍스트와 파일 이름/URI를 지우고 파일 이름 조회·제목 시험 job을 취소한다. 재생성 시 이전 본문은 복구하지 않는다.
- 실제 여행이 실험을 교체하면 표시 결과와 제목 시험도 정리한다. 랩의 stop은 기존 소유권 검사를 그대로 사용하므로 교체된 실제 여행을 중단하지 않는다.

## 실패 테스트의 원인과 수정

기존 테스트는 `TalkRecDebug.enabled`를 바꾼 뒤 `delay(10)`이면 `sessions.collectLatest`의 이전 구독 취소와 새 SharedFlow 구독이 끝났다고 가정했다. 이 가정은 Gradle 테스트 런타임에서 보장되지 않는다. 각 세션의 SharedFlow는 replay=0이므로 새 구독이 붙기 전에 발행한 두 Level은 모두 유실될 수 있으며, 그때 `expected 2 / actual 0`이 된다.

레벨 throttle 초기값은 `null`이다. monotonic 0의 첫 Level을 허용하고 100은 거절, 200은 허용하는 구현이므로 초기값을 바꿀 문제가 아니다. 실제 런타임을 여기서 실행한 것은 아니지만, 코드상 실패를 만드는 재구독 경합을 결정적 순서로 검증하도록 수정했다.

테스트 내부 `EventDispatcher`에 실행 대기 작업을 모으고 `runCurrent()`로 collector 설치·collectLatest 취소/재구독·이벤트 전달을 끝까지 실행한다. 실제 시간 대기는 전부 제거했다. 새 테스트는 특히 다음을 검증한다.

1. 비활성 상태에서는 발행하지 않는다.
2. 첫 세션의 BlockClosed 수신을 확인한 후 세션을 교체한다.
3. off/on이 collector 실행 전에 합쳐지는 상황을 의도적으로 만든다.
4. 새 구독 전 Partial은 replay되지 않는다.
5. 이전 세션의 Decision은 새 세션으로 넘어오지 않는다.
6. 새 세션 Level은 `(100, 1000)`, `(300, 1200)` 두 개만 정확히 수신한다. 백로그의 epoch timestamp로 meter가 연속 발행되지 않는다.
7. 느린 collector 취소와 대기 transcript 폐기도 실제 시간 대기 없이 검증한다.

프로덕션 `TalkRecDebug` 동작은 변경하지 않았다.

## 임시 아이콘 파일

본 앱: 흰 자동차, 배경 **#0F2A4A**.

- `app/src/main/res/drawable/ic_dongban_car_foreground.xml`
- `app/src/main/res/drawable/ic_dongban_car_background.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_dongban_launcher.xml`

디버그: 동일한 흰 자동차, 배경 **#E8711A**, 작은 남색 원/흰 렌치 배지.

- `app/src/debug/res/drawable/ic_debug_car_foreground.xml`
- `app/src/debug/res/drawable/ic_debug_car_background.xml`
- `app/src/debug/res/mipmap-anydpi-v26/ic_debug_launcher.xml`

두 아이콘 모두 XML 주석에 임시임을 명시했다. 본 앱 application의 icon/roundIcon은 본 앱 리소스를 사용하고, debug manifest의 `V3CatalogActivity`만 디버그 icon/roundIcon을 사용한다. 기존 PNG mipmap과 기존 Android 기본 아이콘 리소스는 수정하지 않았다. minSdk 28이므로 새 v26 adaptive 리소스를 사용할 수 있다. 신규 의존성/소스 bitmap 없음.

## 검증 결과

Android SDK 없이 캐시된 Kotlin compiler 2.2.10/JDK를 사용했다.

| 검증 | 결과 |
| --- | --- |
| `.talkrec-notes/verify-i8-jvm.ps1` | 전체 JVM suite **95개 통과**. 최종 I9b 요약 API와 함께 컴파일/실행 |
| `.talkrec-notes/verify-i8b-jvm.ps1` | CaptureProcessorDebugTest/processor/LabSink/resampler **19개 통과**, coroutines 1.8.0 |
| `.talkrec-notes/verify-i8b-current-jvm.ps1` | 같은 **19개 통과**, coroutines 1.10.2 |
| `.talkrec-notes/verify-i8b-types.ps1` | 실제 코어/현재 summary 타입 검사 통과, 정리 상태와 소유권 호스트 테스트 **2개 통과** |
| `.talkrec-notes/verify-i8b-ui.ps1` | 실제 캐시 Compose·Lifecycle API + Compose compiler plugin으로 새 실험실과 정리된 디버그 화면 컴파일 통과 |
| 매니페스트/XML 점검 | main manifest 변경이 icon 두 속성뿐임을 비교 확인. 새 Activity 설정, vector/adaptive XML 6개와 drawable 참조, 동일 자동차 경로 확인 |
| `git diff --check` (I8b 소유 tracked 경로) | 통과. 줄 끝 변환 안내만 있음 |
| `ScreenCatalog`/`ScreenEntry` 소스 검색 | 사용처 없음 |

UI 검사에는 Android SDK 대신 `.talkrec-notes/i8b-ui-stubs/`의 Android/앱 진입점 대체 타입을 사용했다. core 검사도 기존 I8 Android 대체 타입을 사용한다. 이는 **Android 빌드·리소스 링크·manifest merge·실제 서비스 수명·UI 렌더링을 검증한 결과가 아니다**. 검사 스크립트와 호스트 fixture는 `.talkrec-notes/`에만 있으며 Android 소스셋에는 들어가지 않는다.

## 외부 확인 / 남은 항목

코드상 확인된 미해결 차단 사항은 없다. Claude의 실제 Gradle/AVD 확인이 남았다.

1. `:talkrec:testDebugUnitTest`, app debug 빌드와 manifest/resource link. release에서 실험실·디버그 아이콘 리소스가 제외되는지 확인.
2. 2560×1440 차량 화면에서 글자/버튼/좌우 패널과 긴 블록 문장 스크롤 확인. 최종 스타일링은 후속 담당 범위다.
3. AVD user 10의 RECORD_AUDIO/FGS/STT 권한, host microphone access, 권한 거부 후 재시도. 파일 모드도 권한 요청하는지 확인.
4. WAV/MP3/m4a/Ogg 실제 decode, 재생 옵션, 입력 레벨, Partial/Final, 파일 EOF 및 비지원·손상 파일 배너 확인.
5. 실제 여행/시작 대기 중 Start 거부, 정지 직후·화면 재생성 시 정리 상태, EOF 후 stop → Off → 재시작 확인.
6. 모든 블록 outcome/retro-delete/채택 수, 제목 시험의 현재 provider/model·tags 및 오류 표시, 정차 gate 확인.
7. 지우기/화면 종료/재생성 시 transcript 복구 없음, 제목 job 취소, 기존 DB/클립 파일 수 불변 확인.

`.talkrec-notes/`는 기존 로컬 git exclude 대상이다. 이 인계 문서와 검사 보조 파일은 실제 작업트리에 존재하지만 아래 git status에는 나오지 않는다.

## 최종 git status --short

아래는 문서 작성 마지막 단계에서 조회한 전체 작업트리 상태다. I8b 변경만 분리한 목록이 아니며 기존 I8 및 동시 작업 I9b를 포함한다.

```text
 M app/src/debug/AndroidManifest.xml
 M app/src/debug/java/com/dongbantest/app/debug/V3CatalogActivity.kt
 M app/src/main/AndroidManifest.xml
 M app/src/main/java/com/dongbantest/app/ui/v3/README.md
 M app/src/main/java/com/dongbantest/app/ui/v3/components/Basics.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/DrivingParts.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DiaryScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/SampleData.kt
 D app/src/main/java/com/dongbantest/app/ui/v3/screens/ScreenCatalog.kt
 M app/src/main/java/com/dongbantest/app/ui/v3connect/V3Mappers.kt
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
```
