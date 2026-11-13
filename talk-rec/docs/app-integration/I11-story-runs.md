# I11 — 이야기 묶기

작성일: 2026-11-13. 작업 위치 `C:\github\drive-diary-int`, 브랜치 `feat/talk-rec-integration`.
`talkrec/**`, `app/**`를 함께 변경했다. 커밋·푸시·브랜치 전환·Gradle 실행은 하지 않았다.

## 구현 설계

### 캡처와 run

`CaptureRuns`는 직렬 저장 consumer가 소유한다. UUID는 저장 직전에 배정하고, `TalkSink.onKeptBlock()`이 true를 반환한 클립만 구성원으로 센다. 같은 여행에서 이전 종료 이유가 LENGTH이고 다음 시작이 이전 끝의 0~2,000ms 뒤이면 이어 붙인다. 12개를 저장하면 닫고 다음 클립에 새 UUID를 만든다. 판정 모델과 0.957 keep gate는 변경하지 않았다.

`KeptBlock`에 `runId`, `closeReason`을 추가했다. sink에는 기본 no-op인 `onRunClosed(tripId, runId)`를 추가했다. 닫을 때 상태를 먼저 비우므로 정상 종료에서 중복 호출하지 않고, 구성원이 없는 run에는 호출하지 않는다. SILENCE/MANUAL 클립도 저장 응답을 받은 뒤 마지막 구성원으로 포함하고 닫는다.

서비스의 pause/stop/소스 교체/여행 교체는 새 입력을 중단하고 candidate 채널을 닫은 뒤 이미 큐에 들어간 저장을 기다린다. consumer의 finally에서 남은 run을 닫는다. 아직 post-roll을 기다리던 블록과 열린 STT 블록은 기존처럼 버린다. 비정상 프로세스 종료는 아래 요약 복구 규칙으로 처리한다.

정책의 직전 클립 삭제는 UUID 배정/간격 판정보다 먼저 실행한다. 열린 run에서 지운 구성원을 빼고, 유일한 구성원을 지웠다면 빈 run을 전송하지 않는다. 이미 닫힌 run의 클립은 FK cascade로 해당 행만 제거한다. 이미 실행된 외부 요청을 취소하거나 되돌리지는 않는다. 응답 저장 중 구성원 삭제를 감지하면 전체 결과를 거부하고 남은 PENDING 구성원을 다음 복구 작업에서 다시 조회한다.

### 요약

`SummaryClip`은 앱 내부 스케줄링용 메타데이터이고 제공자에 전달하지 않는다. `groupSummaryClips()`는 여행/run별 PENDING 클립을 `blockStartMs`, id 순으로 묶는다. 다음 중 하나이면 준비된 작업이다.

- 구성원에 `runClosed=true`가 있음.
- 마지막 저장 시각이 3분보다 오래되었고 해당 여행의 캡처가 On 상태가 아님.
- `runId=null`인 과거 단일 클립.

클립별 전사는 기존과 같이 원래 줄을 LF로 합친다. run 요청에는 이 문자열 목록만 보내고 Gemini 입력에서 `[1]`, `[2]` 순서로 번호를 붙인다. `throughMemoryId` 범위 안에 모든 구성원이 들어오는 run만 처리하여 run 일부를 먼저 보내지 않는다.

`SummaryProvider.storySplit()`을 추가했다.

| 제공자 | 동작 |
| --- | --- |
| GEMINI | story_split 시스템 지시문, 구조화 JSON schema, 번호가 붙은 전사 목록 |
| AGY | 기존 relay base URL의 `POST /story-split`, `{segments, model}`, 256KiB 요청 한도, 120초 읽기 timeout |
| FAKE | 모든 번호를 한 이야기로 묶고 `keep=true`, 고정 제목/첫 분류 반환 |

`StorySplitValidator`는 relay의 원본 출력 계약처럼 정확한 키, 비어 있지 않은 그룹, 정수 번호, 전체 번호의 순서·연속·단일 포함, 종류, 제목, 분류, 키워드, keep/reason 일관성을 검증한다. `none`만 빈 제목/분류를 허용하고 반드시 false/no_content이다. AGY의 서버 부가 필드 model/elapsed_ms 및 호환 tags는 응답 매핑 단계에서 분리한다.

형식 오류는 앱에서 추가 요청 1회, 재시도 가능한 통신 오류는 기존처럼 최대 5번의 WorkManager attempt를 사용한다. relay의 `502 invalid_output`에는 추가 형식 재요청을 하지 않는다. 열린 run 대기는 DEFERRED로 3분 뒤 별도 복구 체인에 넣어 통신 실패 attempt를 소비하지 않는다. 일반/복구 워커는 프로세스 내 Mutex로 직렬화한다.

실제 요청 직전과 결과 저장 직전에 `canSend`를 확인한다. 동의가 없으면 run 구성원 전체를 SKIPPED, `<runId>:0`, 제목/태그/keep=null인 한 이야기로 남긴다. 처음부터 동의가 없는 클립도 같은 fallback ID를 가진다. run 도중 동의가 바뀌어 SKIPPED/PENDING이 섞이면 닫을 때와 복구 조회 때 PENDING도 SKIPPED로 맞추어 일부만 보내지 않는다. FAILED도 제목 없는 한 이야기로 남긴다. 설정의 연결 시험은 기존 단일 summarize를 유지했다.

전사·제목·키워드를 로그에 추가하지 않았다. 결과 객체의 toString도 생성 텍스트를 노출하지 않는다.

### 프롬프트

`C:\github\drive-diary\talk-rec\model-create\prompts\story_split.md`를 `talkrec/src/main/assets/prompts/story_split.md`로 바이트 그대로 복사했다. 양쪽 SHA-256:

```text
97C1026DE023E5F4DBF29BF2D149EA4F79DF96A4B4D28FD60020F68CA34CD567
```

참조 검증 코드는 같은 model-create의 `tools/summary_relay.py`에서 읽었다. 실제 relay/모델 요청은 실행하지 않았다.

## 데이터와 마이그레이션

Room 버전은 **5 → 6**. `MIGRATION_5_6`을 기존 migration 체인에 등록했다. destructive fallback은 없다. `talk_meta`에 다음 nullable 열만 더한다.

| 열 | SQLite 타입/의미 |
| --- | --- |
| runId | TEXT, 캡처 UUID |
| closeReason | TEXT, LENGTH / SILENCE / MANUAL |
| runClosed | INTEGER, 0/1/null |
| storyId | TEXT, `<runId>:<0부터 시작하는 이야기 index>` |
| storyIndex | INTEGER, run 안의 이야기 순서 |
| storyKind | TEXT, LLM story/moment/none |
| storyKeep | INTEGER, 0/1/null |
| storyReason | TEXT, LLM의 숨김 이유 |

기존 행의 새 열은 모두 null이다. 과거 `runId=null` 클립은 기존 per-clip 요약 경로와 단일 이야기 표시를 유지한다. 새 데이터의 기기 judge kind는 `kind`에 보존하고, 표시할 때 storyKind가 있으면 우선한다.

각 이야기의 모든 클립에 같은 title과 `[category] + keywords` JSON을 기록한다. 원래 클립 파일/전사/행은 합치거나 삭제하지 않는다. `saveStoryRows()` 트랜잭션에서 모든 대상이 여전히 PENDING인지 먼저 확인하고 전체 결과를 반영한다. 삭제/다른 워커에 의해 하나라도 달라졌으면 일부만 쓰지 않는다.

`TalkMigrationTest`는 v4→v6 체인과 현재 v5→v6을 검사하도록 확장했다. v5 제목/태그/상태 보존과 새 8개 열 null, cascade를 검사한다. `TalkStorageTest`에는 run 닫기, 동일 이야기 정보 복제, judge/LLM kind 분리, 숨김 상태 보존, 잘못된 구성원에 대한 전체 거부, 동의 혼합 fallback을 추가했다.

## 동작 표

| 상황 | run/요약 동작 | 화면 |
| --- | --- | --- |
| LENGTH → 2초 이내 저장 성공 | 같은 UUID로 이어감 | run당 이야기 정리 중 |
| SILENCE 또는 MANUAL 저장 | 해당 클립까지 포함하여 닫음 | 응답 후 이야기 카드 |
| judge drop/정책/예산/빈 입력/sink 거절 | 이전 저장 run을 닫음, 거절 클립 제외 | 저장된 것만 표시 |
| 간격 2초 초과 | 이전 run을 닫고 새 UUID | 별도 정리 단위 |
| 12개 저장 | 즉시 닫음, 다음 클립은 새 UUID | 한 요청 최대 12개 |
| pause/stop/소스 끝/여행 교체 | pending 저장 완료 후 한 번 닫음 | 확정된 클립 유지 |
| 열린 run의 유일한 클립 retro-delete | 빈 run의 닫힘 callback 없음 | 해당 카드 제거 |
| 프로세스 종료로 열린 run | 3분 경과 + 해당 여행 캡처 비활성 후 처리 | 그전까지 정리 중 |
| AI 동의 없음/철회 | 전송 없이 SKIPPED, 한 fallback 이야기 | 기존 중립 제목, keep=null |
| keep=false | 파일과 모든 행 유지 | 주행 목록 제외, 다이어리 하단 접기 |
| 요약 실패 | 기존 재시도 후 FAILED, 한 fallback 이야기 | 중립 제목, 계속 재생 가능 |
| legacy clip | 기존 summarize 경로 | 한 클립이 한 이야기 |

## UI와 실험실

v3 mapper는 storyId, 정리 중 runId, 과거 클립 id 순으로 그룹 키를 선택한다. 이야기 시작 시각은 첫 클립의 blockStartMs이고 길이는 구성 클립 durationMs 합, 클립 수는 실제 구성원 수이다. 동반이 질문은 이야기 시작 직전 10분 안의 질문으로 계산한다. 제목 준비 중에는 `이야기 정리 중`, 실패/건너뜀에는 기존 중립 fallback을 쓴다.

주행 목록은 질문과 보이는 이야기만 시간순으로 섞는다. 기존 주행 중 3개/태그 2개 제한과 제목 숨기기 설정을 유지한다. 정차 중 목록에서는 스크롤과 재생을 제공한다. 다이어리는 keep=false 이야기를 하단 `숨긴 대화 N개`로 접어 두고 펼치면 재생할 수 있다. 로컬 보이기 override는 선택 사항이므로 추가하지 않았다.

`VoicePlayer`에 목록 toggle을 추가하여 클립 완료 시 다음 파일을 재생한다. 같은 이야기 재선택은 중지, 다른 이야기 선택은 전환한다. 다이어리 재생 식별은 시각 문자열 대신 이야기 id를 써서 같은 분에 시작한 이야기끼리 혼동하지 않는다. 실제 주행 중에는 이야기 재생을 시작하지 않는다.

실험실은 BlockClosed/Decision의 runId와 RunClosed 이벤트를 받아 run 라벨을 표시한다. 닫힌 run에 `이야기 나눠 보기`를 제공하고 현재 설정 제공자로 segments/title/category/keywords/kind/keep/reason을 보여 준다. 기존 제목 시험처럼 정차 중에만 요청할 수 있다. 결과와 입력은 RAM에만 있고 초기화/종료 시 작업과 버퍼를 정리한다. 샘플과 catalog 생성자에는 새 필드 기본값이 있다.

## 실행한 검증

Gradle 없이 기존 캐시 Kotlin 2.2.10/Compose compiler 및 Android stand-in 방식으로 확인했다. Android API 실행 검증을 대체하지 않는다.

| 명령/검사 | 결과 |
| --- | --- |
| `.talkrec-notes/verify-i11-regression.ps1` | 캡처/블록/정책/judge/debug **53개 통과** |
| `.talkrec-notes/verify-i11-summary.ps1` | 기존 요약 + story split **51개 통과** |
| `.talkrec-notes/verify-i11-backend.ps1` | 캡처 서비스·summary·Worker·Room 선언·repository 타입 검사, 파일 저장 **11개 통과** |
| `.talkrec-notes/verify-i11-ui.ps1` | v3/mapper/navigation/VM/player 캐시 타입 검사, 기존 주행 **32조합**, mapper **2개 통과** |
| `.talkrec-notes/verify-i11-lab.ps1` | 실험실/catalog Compose 타입 검사 통과 |
| Python `-B .talkrec-notes/verify-i11-sqlite.py` | export v5 schema와 실제 migration/query 문자를 이용한 SQLite 보존/nullable/이야기/동의/cascade smoke 통과 |
| `git diff --check` | 통과 (기존 LF/CRLF 안내만 별도 기록) |

JVM 최종 합계는 **117개**(53+51+11+2)이고 주행 32조합은 별도다. Private dev 전사 경로를 주입하거나 실제 가족 전사/유료 모델을 사용하지 않았다. 프롬프트 SHA 일치도 확인했다. 검증 스크립트와 이 문서는 기존 `.talkrec-notes` ignore 대상이다.

## 남은 확인과 제한

- Claude 환경에서 Gradle 전체 빌드, Room KSP/실제 migration instrumentation 테스트, AVD 확인이 필요하다. v6 export schema JSON도 그 빌드에서 생성해야 한다. 이 환경에서는 SDK 디렉터리 접근이 거부되어 Android stand-in을 사용했다.
- AVD에서 LENGTH 연속 저장, 12개 cap, pause/stop 직전 저장과 닫힘 순서, 프로세스 강제 종료 후 3분 복구, 동의 철회, 숨긴 영역 펼치기, 실제 MediaPlayer의 연속 재생을 확인해야 한다.
- 실제 GEMINI/AGY 응답은 fake HTTP 매핑 테스트만 수행했다. relay가 새 prompt/route를 읽는 버전인지 실제 인계 환경에서 확인해야 한다. 실행 중 relay를 재시작하거나 변경하지 않았다.
- 클립의 기존 pre/post-roll은 그대로 재생하므로 인접 파일 사이에 겹치는 음성이 있을 수 있다. 길이 합도 저장 파일 길이 기준이다. 이 작업에서는 오디오를 다시 편집하지 않았다.
- UI는 기존 v3 구성요소로 단순하게 구현했다. 표시 디자인 보완과 선택적 per-story 보이기 override는 후속 작업이다.
- 저장/스케줄러 장애로 닫힘 enqueue가 실패한 경우 기존처럼 다음 앱 시작의 PENDING 복구가 안전망이다. 이미 전송된 외부 요청은 retro-delete로 회수할 수 없고, 삭제된 구성원의 응답을 DB에 되살리지는 않는다.

## git status --short

아래는 최종 작업 트리 전체 상태다. 시작 때 이미 있던 untracked story_split asset도 포함한다.

```text
 M app/src/androidTest/java/com/dongbantest/app/data/local/TalkMigrationTest.kt
 M app/src/androidTest/java/com/dongbantest/app/data/local/TalkStorageTest.kt
 M app/src/debug/java/com/dongbantest/app/debug/TalkRecLabActivity.kt
 M app/src/main/java/com/dongbantest/app/audio/VoicePlayer.kt
 M app/src/main/java/com/dongbantest/app/data/local/DongbanDatabase.kt
 M app/src/main/java/com/dongbantest/app/data/local/Entities.kt
 M app/src/main/java/com/dongbantest/app/data/local/TripDao.kt
 M app/src/main/java/com/dongbantest/app/data/repository/TripRepository.kt
 M app/src/main/java/com/dongbantest/app/data/talk/RepositorySummaryTarget.kt
 M app/src/main/java/com/dongbantest/app/data/talk/TalkSinkImpl.kt
 M app/src/main/java/com/dongbantest/app/domain/model/Models.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/DrivingParts.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/UiModels.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DiaryScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DrivingScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/SampleData.kt
 M app/src/main/java/com/dongbantest/app/ui/v3connect/V3Mappers.kt
 M app/src/main/java/com/dongbantest/app/ui/v3connect/V3NavHost.kt
 M app/src/main/java/com/dongbantest/app/viewmodel/TripViewModel.kt
 M talkrec/src/main/java/com/drivediary/talkrec/TalkRecDebug.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/CaptureProcessor.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/CaptureState.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/ConversationCaptureService.kt
 M talkrec/src/main/java/com/drivediary/talkrec/sink/TalkSink.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryProcessor.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryProvider.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryProviders.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummarySettingsStore.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryWorker.kt
?? app/src/test/java/com/dongbantest/app/ui/
?? talkrec/src/main/assets/prompts/story_split.md
?? talkrec/src/main/java/com/drivediary/talkrec/capture/CaptureRuns.kt
?? talkrec/src/main/java/com/drivediary/talkrec/summary/StorySplit.kt
?? talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryGrouping.kt
?? talkrec/src/test/java/com/drivediary/talkrec/capture/CaptureRunsTest.kt
?? talkrec/src/test/java/com/drivediary/talkrec/summary/StorySplitTest.kt
```
