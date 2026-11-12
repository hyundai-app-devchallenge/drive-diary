# I4: 대화 조각 AAC 저장 · TalkSink · Room v5

2026-11-13. 작업 경로 `C:\github\drive-diary-int`, 브랜치 `feat/talk-rec-integration`.
쓰기 작업은 이 worktree 안에서만 했다. 참고 저장소는 읽기만 했고, 커밋·push·브랜치 전환은 하지 않았다.
시작할 때 이미 있던 I1/I2/I3 및 UI 준비 변경은 유지했다. **이번 I4에서 UI 파일은 수정하지 않았다.**

구현과 가능한 JVM 검증은 끝났다. **Android 빌드, Room KSP/schema export, migration androidTest, AAC 재생 확인은 아직 실행하지 않았다.**
샌드박스의 Android SDK 접근 실패를 재시도하거나 Gradle/curl.exe를 실행하지 않았다.

## 구현

- `talkrec/clip/ClipWriter`: 빌린 PCM16 LE, 16 kHz, mono를 MediaCodec AAC-LC 64 kbps로 인코딩한다. MediaMuxer로 MPEG-4 컨테이너를 만들고 EOS까지 drain한다. codec-config 버퍼는 트랙 샘플로 쓰지 않는다. 완성·stop·release·파일 fsync 후 같은 디렉터리에서 최종 `.m4a`로 rename한다. 반환값은 원본 PCM 샘플 수로 계산한 ms다. 루프에서 취소를 확인하고 예외/취소 시 임시 파일과 codec/muxer를 정리한다.
- `app/data/talk/TalkSinkImpl`: 인코딩과 DB 커밋이 끝날 때까지 반환하지 않는다. PCM을 비동기로 보관하지 않으며, I3가 반환 후 배열을 zero로 지우는 소유권 계약을 지킨다.
- `ClipPersistence`: 파일/DB 작업을 한 저장소 mutex로 직렬화한다. 저장 재시도, 취소, 삭제 대상 고정, 삭제 재시도 및 시작 시 복구를 담당한다. Android API가 없는 Kotlin 코드라 JVM에서 검증했다.
- `TalkMeta`, `TalkSource`, `SummaryStatus` 및 기본값 null인 `Memory.talk`를 추가했다. 기존 Memory 생성자는 그대로 사용할 수 있다.
- `MemoryWithTalk`의 `@Relation`을 통해 기억과 메타데이터를 함께 읽는다. 관계 조회에는 `@Transaction`을 붙였다. 기존 `TripWithMemories` 호출 형태는 바꾸지 않았다.
- 저장소 API는 `saveTalkClip`, `updateTalkSummary`, `listPendingTalkSummaries`, `deletePreviousSavedClip`, `savedAutoDurationMs`, `recoverTalkClips`다. 전사 글은 원래 줄을 **LF(`\n`)**로 연결하여 기존 `memories.transcript`에 넣는다. 모델 fallback의 `keepScore`와 `kind`는 null을 유지한다.
- AI 전송 동의가 있으면 `PENDING`, 없으면 `SKIPPED`로 저장한다. title/tags/summaryModel은 처음에 null이다. **SummaryWorker/LLM 호출은 구현하지 않았다(I5).** 요약 갱신은 태그 목록을 JSON 배열 문자열로 저장한다.
- 전사 글을 출력하는 로그는 추가하지 않았다. 오류 로그에는 고정된 메시지만 남긴다.

## 팀 파일을 고친 이유

| 파일 | I4 변경 이유 |
| --- | --- |
| `app/build.gradle.kts` | Room schema export 위치, androidTest schema assets, Room testing 의존성 |
| `data/local/DongbanDatabase.kt` | v5 등록, exportSchema=true, 명시적 4→5 migration 연결 |
| `data/local/Entities.kt` | 기존 엔티티 열은 그대로 두고 TalkMetaEntity/MemoryWithTalk 추가 |
| `data/local/TripDao.kt` | memory+meta 트랜잭션 삽입, 관계 읽기, 요약 조회/갱신, 최신 조각/상한 조회 |
| `domain/model/Models.kt` | TalkMeta 및 nullable Memory.talk |
| `data/repository/TripRepository.kt` | 저장·복구·삭제·요약 API, 관계 매핑, 종료 훅과 파일 작업 직렬화. 존재하지 않는 trip을 진행 중으로 보던 null 비교도 수정 |
| `data/repository/MediaFileStore.kt` | 기존 voice 폴더 안의 안정적인 조각 경로, journal 위치, 복구 대상 열거. 삭제 경계에 디렉터리 구분자를 포함하고 삭제 실패를 확인 |
| `data/repository/RecordingConsentStore.kt` | 기존 getSendToAi는 유지하고 다른 store 인스턴스의 동의 변경도 감지하는 Flow 추가 |
| `DongbanApplication.kt` | AppContainer에서 동의 store/sink/controller 생성 및 종료·동의 감시 연결 |
| `viewmodel/HomeViewModel.kt` | 여행 생성 콜백이 S03 동의를 저장한 뒤 캡처 시작 시도 |
| `viewmodel/TripViewModel.kt` | 권한 허용/화면 복귀 후 시작, 동의 철회 시 정지, pause/resume/mark 연결, 수동 MediaRecorder와 마이크 중복 방지 |
| `viewmodel/AppViewModelProvider.kt` | 공용 controller와 consent store 주입 |

신규 앱 파일은 `data/talk/{ClipPersistence,TalkSinkImpl,TalkRecordingController}.kt`, JVM 테스트 `data/talk/ClipPersistenceTest.kt`, androidTest `data/local/{TalkMigrationTest,TalkStorageTest}.kt`, `app/schemas/README.md`다.
talkrec에서는 `ClipWriter.kt`, `TalkSink.kt`, `CaptureProcessor.kt`, `CaptureProcessorTest.kt`만 이번 I4에서 변경했다.
루트 Gradle/버전 카탈로그/기존 ui 변경은 작업 시작 전에 있던 변경이다. `VoiceRecorder.kt`, `ArrivalManager.kt`는 수정하지 않았다.

**새 의존성은 androidTest 전용 `androidx.room:room-testing:2.8.5` 한 개뿐**이다(기존 room 버전 카탈로그 참조). 프로덕션 의존성 추가는 없다.

## 스키마와 마이그레이션

`MIGRATION_4_5`는 아래 테이블과 `summaryStatus` 인덱스만 만든다. 기존 trips/memories/location_points/vehicle_signals는 ALTER/DROP/복사하지 않는다.
기존 앱에는 fallbackToDestructiveMigration 호출이 없었고, 추가하지 않았다. 1→2→3→4 migration 연결도 유지했다.

```sql
CREATE TABLE IF NOT EXISTS talk_meta (
  memoryId INTEGER NOT NULL PRIMARY KEY,
  source TEXT NOT NULL,
  blockStartMs INTEGER NOT NULL,
  blockEndMs INTEGER NOT NULL,
  keepScore REAL,
  kind TEXT,
  title TEXT,
  tags TEXT,
  summaryStatus TEXT NOT NULL,
  summaryModel TEXT,
  FOREIGN KEY(memoryId) REFERENCES memories(id)
    ON UPDATE NO ACTION ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_talk_meta_summaryStatus
  ON talk_meta(summaryStatus);
```

시각은 epoch ms이고 blockStartMs/endMs는 원래 묶음 경계다. durationMs는 앞뒤 PCM 여유까지 포함한 실제 조각 길이다.
source는 AUTO/MANUAL, kind는 story/moment/null, summaryStatus는 PENDING/DONE/FAILED/SKIPPED다.
기존 VOICE 및 기존 수동 `addVoiceMemo` 경로의 기록은 talk=null로 읽힌다. 상시 캡처의 `markMoment`로 저장한 조각은 MANUAL 메타데이터를 가진다.

v4는 `exportSchema=false`였으므로 역사적 `4.json`이 없다. 이를 임의로 만들지 않았다.
`TalkMigrationTest`는 원래 4개 테이블/인덱스와 합성 데이터를 가진 SQLite v4 fixture를 직접 만들고,
실제 migration을 [MigrationTestHelper](https://developer.android.com/reference/androidx/room/testing/MigrationTestHelper)로 실행하여 **빌드가 생성하는 5.json**과 대조한다.
기존 테이블/인덱스 SQL 및 데이터가 바뀌지 않았는지, fallback null 값과 memory/trip 삭제 CASCADE도 확인한다.

KSP 실행 시 `app/schemas/com.dongbantest.app.data.local.DongbanDatabase/5.json`이 생성되어야 한다.
현재 sandbox에서는 KSP를 돌리지 않았으므로 이 JSON은 아직 없다. Claude가 빌드 후 생성 결과를 확인하고 후속 변경에 포함해야 한다.

## 파일·DB 원자성 및 삭제 규칙

저장 경로는 기존 VoiceRecorder와 같은 `files/voice/`이고, `voice_talk_<SHA256(tripId:blockId)>.m4a`를 쓴다.
기존 수동 녹음의 `voice_<timestamp>.m4a` 경로는 유지했다. 안정적인 경로로 동일 block.id의 저장 재시도를 구분한다.

1. 저장소 lock 안에서 동의와 진행 중 여행을 확인한다.
2. ClipWriter가 같은 폴더의 `.part`에 쓰고 완성된 파일을 rename한다.
3. 취소를 다시 확인하고, 짧은 NonCancellable 구간에서 동의/여행을 다시 확인한 뒤 `TripDao.insertTalkMemory`를 실행한다. **VOICE memory와 talk_meta는 하나의 Room 트랜잭션**이다.
4. 인코딩/검증/트랜잭션 실패 또는 커밋 전 취소에는 최종 파일도 지운다. 기존 행이 있으면 다시 인코딩하거나 새 행을 만들지 않는다.
5. 커밋 경계에서 취소되면 완성된 파일+두 행은 유지한다. 호출자가 IO dispatcher 복귀 때 취소를 받더라도 파일만 삭제하지 않는다. 미완료 파일이나 메타데이터만 있는 기록을 성공으로 취급하지 않는다.

retroDelete는 해당 trip의 talk_meta가 AUTO 또는 MANUAL인 VOICE 중 **가장 나중에 삽입된 memory.id 한 개**만 대상으로 한다.
다른 여행/사진/질문/메타데이터 없는 예전 녹음은 선택하지 않는다.

- `files/talkrec-journal/<SHA256(tripId:refusalId)>.receipt`에 **대상 memory ID와 파일 경로를 먼저 고정**한다. 대상이 없었던 요청도 ID=0으로 고정한다. receipt는 임시 파일→fsync→rename으로 쓴다.
- 재시도와 재시작은 같은 receipt만 사용한다. 다음 조각이 새로 저장되어도 대상을 다시 고르지 않는다. 이미 지워진 대상은 false를 반환한다.
- 파일을 `.deleting`으로 staging한 뒤 행을 삭제한다. talk_meta는 CASCADE된다. DB 삭제 실패 시 파일명을 복구한다. 성공 후 staged 파일을 unlink한다.
- 삭제가 끝난 receipt도 유지하여 같은 refusalId의 늦은 재시도를 막는다. 삭제 대상이 된 block의 저장 재시도도 receipt 경로를 검사하여 부활시키지 않는다.
- 앱 시작 및 첫 저장 전 복구에서 receipt를 재생하고, DB 커밋 뒤 남은 `.deleting`, 저장 도중 죽어서 남은 `.part`/DB 미참조 `voice_talk_*.m4a`를 정리한다. 일반 memory 삭제도 같은 조정기를 사용한다.
- 파일 작업은 앱 filesDir 하위 경계만 허용한다. 전사나 PCM은 journal에 쓰지 않는다.

파일시스템과 SQLite는 하나의 물리적 트랜잭션을 공유하지 않는다. 정상 예외/취소는 위 보상 경로로 정리하고,
프로세스 강제 종료로 생긴 중간 상태는 다음 시작에서 복구한다. unlink/rename 자체가 파일시스템 오류로 실패하면 성공으로 숨기지 않고 오류를 내며 journal을 유지한다.
전원 손실까지 포함한 저장 장치 보장은 AVD 테스트로 증명한 것이 아니다.

## 영속 시간당 상한

TalkSink에 기본 구현이 null인 `savedAutoDurationMs(sinceMs, untilMs)` 훅을 추가했다. 앱 sink는 DB 합계를 반환하고, I3 debug counting sink는 기존 프로세스 RAM 장부를 쓴다.

- DB는 모든 여행의 AUTO VOICE `durationMs`를 `createdAt > now−3,600,000 && createdAt <= now` 범위에서 합한다. MANUAL은 제외한다.
- AUTO마다 저장 전 DB 합계를 새로 읽는다. 프로세스 재시작/새 여행/일시정지 뒤에도 DB 이력으로 600,000ms 상한이 유지된다.
- CaptureBudget은 DB 이력에 **아직 커밋되지 않은 예약**만 더한다. 이미 커밋된 조각을 DB와 RAM에서 두 번 계산하지 않는다. 같은 시각·같은 길이 예약도 서로 다른 객체로 관리한다.
- 저장 거절/실패/취소는 예약을 반환한다. MANUAL은 정책 검사를 유지하면서 자동 분량 상한만 우회한다.
- 합계 기준은 현재 저장된 이력이므로 retroDelete/여행 삭제로 없어진 AUTO는 DB 합계에서 빠진다. I3 RAM 전용 장부의 보수적 예약 유지와 다른 점이다.
- 실제 캡처는 I3의 단일 순서 소비자이며, 저장소 파일/행 변경도 직렬화한다. 다중 프로세스 캡처는 구현 범위가 아니다.

## 여행 연결점

`AppContainer`가 공용 TalkSink/동의 store/TalkRecordingController를 만든다.

- `HomeViewModel.startTrip`: 기존 onStarted 콜백(S03에서 동의 저장)이 반환한 다음 `TalkRec.start(context, tripId, CaptureOptions(sink))`를 시도한다. 동의=true·유효한 진행 중 여행·RECORD_AUDIO 권한일 때만 시작한다.
- 권한을 나중에 허용한 경우 기존 `TripViewModel.onScreenShown`이 시작을 재시도한다. 같은 여행에 이미 시작한 서비스는 다시 초기화하지 않는다.
- `TripRepository.endTrip` 공통 훅에서 stop한다. ArrivalManager의 자동 도착과 TripViewModel의 수동 종료가 모두 이 경로를 사용하므로 ArrivalManager/UI를 고칠 필요가 없었다. 여행 삭제도 stop한다.
- 동의 변경 Flow와 진행 중 여행 Flow를 감시하여 동의가 false/null이 되거나 해당 여행이 끝나면 stop한다. 이 감시 자체는 마이크를 시작하지 않는다. 앱 프로세스 재시작만으로 자동 녹음하지 않는다.
- 동의한 여행은 화면을 떠나도 FGS가 이어진다. 기존 pause/resume 및 명시적 저장 버튼 경로는 TalkRec pause/resume/markMoment로 연결하여 MediaRecorder를 동시에 열지 않는다. 수동 녹음이 켜져 있다면 먼저 저장·종료한다.
- 동의 없는 여행에서는 명시적 `startRecording`의 기존 VoiceRecorder 경로를 유지한다. 기존 화면 종료 시 수동 조각 저장도 유지했다. 아직 여행 중이에요(reopen)는 다시 여행 화면이 보일 때 권한·동의를 확인하고 시작한다.
- Home 시작 시 이미 마이크 권한이 있으면 S04 안내 확인 전에도 캡처가 시작된다(요청한 여행 시작 시 연결). 권한이 아직 없으면 기존 안내/권한 흐름 뒤 시작된다. 안내 음성과의 시점 조율이 필요하면 I6에서 UI 정책을 정한다.

UI 상태 칩·제목·태그 매핑은 I6 범위로 남겼다. 새 오디오 경로는 기존 VOICE 재생 경로가 사용한다.

## 실제 실행한 검증

```powershell
Set-Location C:\github\drive-diary-int
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i4-jvm.ps1
git diff --check
```

최종 결과: **`OK (39 tests)` + `OK (11 tests)` = 50개 통과**. 각각 12.238초, 8.268초.
Kotlin 2.2.10 cached compiler, JVM target 11, 기존 I3 coroutines/ORT/JUnit 경로를 사용했다.
전체 I2/I3 회귀, 영속 상한의 재시작/중복 계산/동일 시각 예약/MANUAL 우회,
LF 전사 연결, 삭제의 대상 없음/여행 키 분리/재시작/DB 실패 복구/staging 복구,
저장 중복·실패·취소·커밋 경계 취소를 검사했다.

추가로 실제 Models/Entities/TripDao/TripRepository/순수 domain/location 코드를 cached Room annotations와 함께 JVM 컴파일했다.
Android MediaFileStore만 `.talkrec-notes/i4-host-stubs.kt`의 **타입 검사 전용 stand-in**으로 대체했다.
이는 Android API나 Room SQL/KSP/codec가 검증되었다는 뜻이 아니다. stub은 앱 빌드에 포함되지 않는다.
`git diff --check`도 통과했다(CRLF 변환 안내만 표시).

## Claude가 AVD에서 확인할 것

### 1. 빌드·스키마·테스트

SDK 접근 가능한 셸에서 실행한다.

```powershell
Set-Location C:\github\drive-diary-int
.\gradlew.bat :talkrec:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
# 연결된 AVD에서 두 Room 테스트 실행
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dongbantest.app.data.local.TalkMigrationTest,com.dongbantest.app.data.local.TalkStorageTest
```

`5.json` 생성, 타입/nullability/PK/FK/index가 migration과 같은지 확인한다. `TalkStorageTest`는 두 번째 INSERT를 trigger로 실패시켜 memory도 rollback되는지,
relation/요약 갱신/PENDING 조회/여행을 가로지른 상한 및 시간 경계를 확인한다. Android/KSP 오류가 나오면 그대로 전달한다.

### 2. 실제 저장 → 파일 존재·재생·행

1. 새 앱을 **user 10**에 설치·실행한다. 기존 여행/마이크 권한이 있으면 시작 직후 FGS가 켜질 수 있으므로 시작 상태를 확인한다.
2. S03 기록 동의=true로 실제 여행을 만든다. 외부 AI 동의=false에서는 SKIPPED, true에서는 PENDING인 것을 각각 확인한다.
3. 마이크로 합성 대화를 입력하거나, 앱 sink가 활성화된 상태에서 아래 I3 DEBUG_INJECT만 사용한다.
   **DebugCaptureActivity/DEBUG_START는 RAM counting sink를 새로 선택하므로 영구 저장 확인에 사용하지 않는다.**

```powershell
# x.wav는 직접 만든 16kHz/mono/PCM16 음성이다. I3 노트의 run-as 복사 방법을 사용한다.
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_INJECT -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver --es path /data/user/10/com.dongbantest.app/files/talkrec-debug.wav
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_STATE -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell run-as com.dongbantest.app --user 10 ls -l files/voice
```

4. 묶음 닫힘+저장 후 `voice_talk_*.m4a`가 존재하고 길이가 0이 아닌지 확인한다. 임시 `.part`가 남지 않아야 한다. S14의 해당 VOICE를 눌러 실제 소리를 재생한다. 16 kHz AAC의 정상 속도·앞뒤 여유·길이·컨테이너를 확인한다.
5. Database Inspector 또는 AVD의 SQLite 도구로 다음을 확인한다(전사 원문을 로그로 보내지 않는다).

```sql
SELECT m.id,m.tripId,m.type,m.filePath,m.durationMs,length(m.transcript),
       t.source,t.blockStartMs,t.blockEndMs,t.keepScore,t.kind,t.summaryStatus
FROM memories m JOIN talk_meta t ON t.memoryId=m.id ORDER BY m.id DESC;
PRAGMA foreign_key_check;
```

### 3. retroDelete·재시도·복구

- 두 조각을 저장하고 기록 거부 대화를 주입한다. 해당 여행의 마지막 talk VOICE **한 개만** 파일/행/meta가 함께 사라지고 이전 조각은 남는지 확인한다.
- 동일 `refusalId`로 sink/repository 메서드를 두 번 호출한다(디버거 evaluate 또는 추가 계측). 두 번째는 false이며 새 조각을 저장한 뒤 같은 ID를 다시 호출해도 새 조각을 지우지 않아야 한다. 단순히 같은 문장을 두 번 발화하는 것은 I3에서 서로 다른 refusalId가 되므로 동일 요청 재시도 시험과 다르다.
- 파일이 이미 없는 경우에도 해당 행/meta만 정리되는지 확인한다. 실패/강제 종료 후 다음 앱 시작에서 `.part`/`.deleting`/미참조 talk 파일이 정리되는지 확인한다.
- 프로세스를 종료한 뒤 새 여행을 시작해도 최근 AUTO 합계가 유지되는지, MANUAL은 합계에서 제외되는지 확인한다.
- 탭 이동·화면 종료에서는 상시 캡처가 유지되고, 수동 종료/DEBUG_ARRIVAL/동의 철회에는 멈추는지 확인한다. 동의=false 여행의 기존 명시적 VoiceRecorder 녹음도 확인한다.

### 4. 기존 v4 DB의 실제 업그레이드

먼저 v4 APK에서 여행/음성/사진/위치/차량 신호가 있는 DB를 준비하고, **앱 데이터 삭제/재설치 uninstall 없이** 새 APK로 업데이트한다.
기존 기록·파일·테이블 4개가 유지되는지, DB user_version=5인지, 기존 Memory.talk가 null인지 확인한다.
그 뒤 새 대화 저장과 재생, 여행 삭제 시 파일 및 talk_meta CASCADE를 확인한다.
MigrationTestHelper의 합성 fixture 통과와 실제 사용자 v4 DB 업그레이드는 별도 확인 항목이다.

## 남은 항목

- Android 빌드 및 두 androidTest 실행, KSP의 `5.json` 생성/보관은 Claude 확인 대기다.
- MediaCodec/MediaMuxer 인코더 동작·재생 품질·실제 음원 길이는 AVD 확인 대기다.
- SummaryWorker 및 PENDING 처리 재시도는 I5다. 현재 PENDING이 남는 것은 의도한 상태다.
- 상태 칩/제목/태그 등 UI 매핑과 S04 안내 시점 정책은 I6다. 현재 기존 mapper의 전사 표시 방식은 수정하지 않았다.
- receipt는 정확한 늦은 재시도 방지를 위해 계속 보관한다. 장기 보관 정리 정책은 구현하지 않았다. 내용은 ID/내부 경로뿐이다.
- 파일시스템 unlink/rename 실패나 실제 전원 손실 시나리오는 계측하지 않았다. 복구 실패는 숨기지 않고 캡처 시작/저장을 실패시킨다.

## git status --short

아래 UI/루트 Gradle/버전 카탈로그 변경 및 `?? talkrec/`는 작업 시작 때부터 있었다. talkrec가 통째로 untracked이므로 이번 모듈 내 수정은 이 상태 출력에 파일별로 나오지 않는다.
`.talkrec-notes/`는 로컬 ignore 대상이어서 아래 출력에는 나타나지 않지만 이 문서와 검증 스크립트는 실제 파일로 작성했다.

```text
 M app/build.gradle.kts
 M app/src/main/java/com/dongbantest/app/DongbanApplication.kt
 M app/src/main/java/com/dongbantest/app/data/local/DongbanDatabase.kt
 M app/src/main/java/com/dongbantest/app/data/local/Entities.kt
 M app/src/main/java/com/dongbantest/app/data/local/TripDao.kt
 M app/src/main/java/com/dongbantest/app/data/repository/MediaFileStore.kt
 M app/src/main/java/com/dongbantest/app/data/repository/RecordingConsentStore.kt
 M app/src/main/java/com/dongbantest/app/data/repository/TripRepository.kt
 M app/src/main/java/com/dongbantest/app/domain/model/Models.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/DrivingParts.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/UiModels.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DiaryScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DrivingScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/HomeScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/SampleData.kt
 M app/src/main/java/com/dongbantest/app/ui/v3connect/V3NavHost.kt
 M app/src/main/java/com/dongbantest/app/viewmodel/AppViewModelProvider.kt
 M app/src/main/java/com/dongbantest/app/viewmodel/HomeViewModel.kt
 M app/src/main/java/com/dongbantest/app/viewmodel/TripViewModel.kt
 M build.gradle.kts
 M gradle/libs.versions.toml
 M settings.gradle.kts
?? app/schemas/
?? app/src/androidTest/java/com/dongbantest/app/data/
?? app/src/main/java/com/dongbantest/app/data/talk/
?? app/src/test/java/com/dongbantest/app/data/
?? talkrec/
```

## 샌드박스 밖 검증 (Claude, 2026-11-13)

**빌드·테스트**
- `TalkStorageTest`의 `RoomDatabase.use { }`가 컴파일되지 않아(이 Room 버전의 RoomDatabase는 Closeable이 아님) try/finally로 고쳤다.
- `TalkMigrationTest`는 `room-testing`의 `MigrationTestHelper`가 앱의 `kotlinx-serialization 1.7.3`(Mapbox 경유)과 충돌해 `AbstractMethodError`로 실패했다. 앱 의존성을 올리지 않고, v4 테이블을 직접 만든 뒤 `Room.databaseBuilder(...).addMigrations(MIGRATION_4_5)`로 실제 앱처럼 여는 방식으로 바꿨다(Room이 열 때 엔티티와 테이블을 검사). `room-testing` 의존성과 androidTest의 schemas assets 설정은 뺐다. 스키마 내보내기(`app/schemas/.../5.json`)는 유지한다.
- 결과: talkrec JVM 39개, app JVM 40개, AVD androidTest 2개(TalkMigrationTest, TalkStorageTest) 모두 통과. debug·release·androidTest 빌드 통과.
- 주의: `connectedDebugAndroidTest`는 끝나면서 AVD의 앱을 지운다. 다시 `installDebug` 해야 한다.

**실제 v4 DB 업그레이드 (AVD)**
- 팀 원본(e5279c5)을 임시 worktree에서 빌드·설치하고, 디버그 화면의 "예시 여행 넣기"로 v4 DB를 만들었다: trips 10, location_points 23,914.
- 새 APK를 덮어 설치하고 앱을 열었다: user_version 5, talk_meta 생김, trips 10·location_points 23,914 그대로, foreign_key_check 빈 결과, integrity ok.

**실제 여행에서 저장 (AVD, user 10)**
- 홈 → 여행 시작 → 가족 → S03에서 "여행 중 대화 기록" + "외부 AI 전송" 체크 → 출발. `ConversationCaptureService`가 포그라운드(microphone)로 자동 시작(tripId=1).
- 지어낸 대화 4묶음 WAV를 `DEBUG_INJECT`로 주입.
- 결과: `files/voice/voice_talk_*.m4a` 1개(225,953 bytes, MP4 + AAC, 27.84초). DB에는 VOICE memory 1행 + talk_meta 1행: source AUTO, 묶음 23.7초, keepScore 0.985, kind story, summaryStatus PENDING, title null.
- 줄넘기 이야기 조각은 기록 거부로 파일·행이 함께 지워졌고 journal receipt 1개가 남았다. 길 안내 묶음은 저장되지 않았다. `.part`/`.deleting` 남은 파일 없음, foreign_key_check 빈 결과.
- 아직 확인하지 않은 것: S14에서 실제 소리 재생(사람 확인), 프로세스 강제 종료 뒤 복구, 수동 "이 순간 담기".

**I6에 넘길 것**
- 팀 mapper의 다이어리 제목은 `transcript ?: "목소리 HH:mm"`이다. 이제 `transcript`가 채워지므로 **전사 글이 그대로 제목으로 보인다.** I6에서 `talk.title ?: "목소리 HH:mm"`로 바꿔야 한다.
