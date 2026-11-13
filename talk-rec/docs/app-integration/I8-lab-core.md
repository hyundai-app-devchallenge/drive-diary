# I8 — 인식 랩 코어 인계

작업 위치: `C:\github\drive-diary-int`, 브랜치 `feat/talk-rec-integration`.
앱 UI 경로(`app/src/main/java/com/dongbantest/app/ui/**`, `app/src/debug/**`)는 수정하지 않았다. 커밋·푸시·브랜치 전환 없음.

## 구현 내용

- 기본값이 꺼짐인 `TalkRecDebug`와 실시간 이벤트를 main 소스셋에 추가했다. 이벤트 내용을 로그에 기록하지 않는다. replay=0, 세션당 256개, DROP_OLDEST이며 끄면 해당 세션의 버퍼와 구독을 폐기한다. 다음 세션에는 이전 텍스트나 판정이 넘어가지 않는다. 활성화 상태는 thread-safe `MutableStateFlow`로 관리한다(`@Volatile`에 해당하는 가시성 보장).
- Pleos partial/final/error, PCM RMS, assembler 종료, policy/judge/sink 결과를 연결했다. 블록 종료 때 만든 UUID가 sink의 `KeptBlock.id` 및 `Decision.blockId`까지 유지된다. 정상 처리·거절·취소·큐 폐기·서비스 종료가 같은 완료 latch를 공유한다.
- `talkrec/src/debug/`에 `TalkRecLab`, 비영속 `LabSink`, `DecodedAudioFileSource`, 순수 Kotlin 스트리밍 다운믹스/선형 리샘플러를 추가했다. 이 클래스는 release variant에 없다. 새 라이브러리 의존성 없음.
- 파일은 content URI를 직접 읽어 플랫폼 MediaExtractor/MediaCodec으로 디코딩한다. 이미 PCM인 WAV(`audio/raw`)는 codec 없이 extractor 출력을 쓴다. PCM8/16/24/32 및 float 출력을 처리하고, 채널 평균 → mono 16 kHz PCM16 LE → 100 ms(3,200바이트) 프레임으로 보낸다. 마지막 프레임은 0으로 패딩하고 25초 무음을 덧붙인다. 파일 전체를 저장하거나 메모리에 적재하지 않는다.
- 선택 재생은 같은 변환 PCM을 `AudioTrack(USAGE_MEDIA, CONTENT_TYPE_SPEECH)`에 넣는다. 각 100 ms 구간 시작에 재생을 예약하고 구간 끝에 STT 프레임을 전달한다. 취소 시 codec/track/extractor를 finally에서 해제한다.
- 랩은 Room, ClipWriter, SummaryWorker를 호출하지 않는다. `LabSink`는 본문·PCM 없이 블록 ID와 취소 ID만 세션 메모리에 보관한다. 저장 승인 후 최근 ID부터 역순 삭제하므로 연속 retro-delete도 확인할 수 있다. 랩 전용 예산 객체를 써서 실제 여행의 process-local 예산을 소비하지 않는다.
- `SummarySettingsStore.trySummarize`를 추가하고 `testConnection`도 같은 함수로 통합했다. 현재 설정의 provider/model, 동일한 validation 1회 재시도, 시간 측정, 안전한 오류 코드, cancellation 전파를 사용한다. 입력 텍스트는 로그/설정에 저장하지 않는다.

## UI에서 사용할 정확한 API

아래 import는 앱 debug 소스셋에서만 사용한다. 공통 main UI에서 debug 클래스를 import하지 않는다.

```kotlin
import com.drivediary.talkrec.debug.TalkRecLab
import com.drivediary.talkrec.debug.LabBusyException
import com.drivediary.talkrec.debug.AudioFileException
import com.drivediary.talkrec.debug.AudioFileError
import com.drivediary.talkrec.TalkDebugEvent
import com.drivediary.talkrec.DebugOutcome
```

```kotlin
object TalkRecLab {
    const val LAB_TRIP_ID: Long = 0L
    const val REQUIRED_PERMISSION: String = "android.permission.RECORD_AUDIO"

    fun canStart(): Boolean
    fun startMic(context: Context): Unit
    fun startFile(context: Context, uri: Uri, playAloud: Boolean = false): Unit
    fun stop(): Unit

    val events: SharedFlow<TalkDebugEvent>
    val state: StateFlow<CaptureState>
    val sink: StateFlow<LabSink?>
}

class LabBusyException : IllegalStateException

class LabSink : TalkSink {
    val state: StateFlow<LabSinkState>
    suspend fun onKeptBlock(block: KeptBlock): Boolean  // 항상 true; 파일/DB 저장 없음
    suspend fun deletePreviousSavedClip(tripId: Long, refusalId: String): Boolean
    fun clear(): Unit
}

data class LabSinkState(
    val keptCount: Int = 0,
    val previousBlockId: String? = null,
    val deletedBlockId: String? = null,
    val refusalId: String? = null,
)

class DecodedAudioFileSource(context: Context, uri: Uri, playAloud: Boolean) : AudioSource {
    override fun frames(): Flow<PcmFrame>
}

class AudioFileException(val code: AudioFileError) : Exception
enum class AudioFileError { FILE_UNREADABLE, UNSUPPORTED_AUDIO, CORRUPT_AUDIO, PLAYBACK_FAILED }
```

`LAB_TRIP_ID=0`은 Room `@PrimaryKey(autoGenerate=true)`의 미저장 sentinel이다. 실제 여행 ID는 삽입 후 1 이상이며 기존 I3 debug 도구도 0을 사용한다. 이 ID로 실제 여행 행을 생성하면 안 된다.

`canStart()`는 On/Paused 상태뿐 아니라 `TalkRec.start` 직후 서비스가 아직 Ready인 시작 대기 구간도 검사한다. 이미 실행 중인 랩에 대해서도 false다. 입력을 바꾸려면 stop 후 Off를 기다린 다음 다시 시작한다. 시작 자체도 같은 TalkRec lock 아래 재검사하므로 canStart 확인 이후 경합도 `LabBusyException`으로 거부된다. 실제 여행이 랩을 대체하면 이벤트와 랩 sink를 정리한다. 랩 stop은 소유권을 검사하므로 대체된 실제 여행을 중지하지 않는다.

UI는 이벤트 collector를 먼저 붙이고 시작한다. `lifecycleScope`/`repeatOnLifecycle`/Compose `LaunchedEffect`에서 수집하고 화면이 닫힐 때 `TalkRecLab.stop()`을 호출한다. **화면 자체가 가진 partial/final/block/summary 텍스트도 화면 종료·명시적 정리 때 비운다.** 코어는 UI가 복사한 목록을 지울 수 없다. 파일 URI·텍스트를 SavedStateHandle/영속 저장에 넣지 않는다.

파일 선택은 `ActivityResultContracts.OpenDocument` 등으로 `content://` URI와 읽기 grant를 받아 `startFile`에 그대로 전달한다. `audio/*` 및 필요 시 `application/ogg`를 허용하거나 전체 파일 선택을 제공한다. 실제 지원 여부는 플랫폼 디코더가 판단한다. URI를 실제 경로로 변환할 필요가 없다. 장기 URI 보관 기능은 이 작업에 포함하지 않았다.

`startFile`은 서비스 시작만 요청하므로 디코딩 실패가 호출 스택으로 반환되지 않는다. UI는 `SourceError(code)`를 표시하고 state의 errorCount/Paused를 함께 확인한다. 소스를 직접 collect할 때에는 `AudioFileException.code` 및 고정된 짧은 `message`를 사용할 수 있다. 예외 cause와 메시지에 경로/URI를 포함하지 않는다.

파일 EOF는 마지막 25초 무음과 처리 큐 완료 후 `SourceEnded`를 내고 기존 서비스 동작대로 Paused가 된다. 결과를 보면서 유지할 수 있다. 다른 입력을 시작하려면 stop → Off → start 순서를 따른다. `stop()`은 즉시 이벤트를 끄고 랩 메모리를 지우지만 서비스 해제 및 Off 전환은 비동기다. Off 이전 canStart는 false다.

## 이벤트 목록과 해석

모든 이벤트는 `com.drivediary.talkrec` 패키지에 있다.

```kotlin
sealed interface TalkDebugEvent {
    data class Level(val rms: Double, val atMs: Long) : TalkDebugEvent
    data class Partial(val text: String) : TalkDebugEvent
    data class Final(val text: String, val receivedMs: Long) : TalkDebugEvent
    data class BlockClosed(
        val blockId: String,
        val reason: CloseReason,
        val source: CaptureSource,
        val lines: List<String>,
        val durationMs: Long,
    ) : TalkDebugEvent
    data class Decision(
        val blockId: String,
        val outcome: DebugOutcome,
        val keepProbability: Double?,
        val kind: String?,
        val policyReasons: List<String>,
        val judgeMs: Long?,
        val savedCountDelta: Int = 0,
    ) : TalkDebugEvent
    data object SourceEnded : TalkDebugEvent
    data class SttError(val code: String) : TalkDebugEvent
    data class SourceError(val code: String) : TalkDebugEvent
    data object Stopped : TalkDebugEvent
}

enum class DebugOutcome {
    SAVED, JUDGE_DROP, POLICY_BLOCK, COMMAND_LINES_DROPPED, RETRO_DELETE,
    EMPTY, BUDGET, SINK_REJECTED, JUDGE_FAILED,
}

// 기존 타입
enum class CloseReason { SILENCE, LENGTH, MANUAL }
enum class CaptureSource { AUTO, MANUAL }
enum class CaptureStatus { Ready, On, Paused, Off }
```

- Level RMS는 정규화 값이 아닌 PCM16 진폭 단위(대략 0..32768)다. 실제 monotonic 시간으로 200 ms 이상 간격을 보장한다. atMs는 프레임의 epoch 기준 끝 시각이다. 파일 백로그를 처리해도 레벨 이벤트를 한꺼번에 보내지 않는다.
- Final.receivedMs는 Pleos 콜백을 받은 wall-clock ms이다. SDK EPD 시각을 사용하지 않는다. BlockClosed.durationMs는 패딩 전 블록 길이다.
- 활성 이벤트 세션 안에서 각 BlockClosed에 대해 같은 blockId의 Decision을 정확히 한 번 **발행**한다. 본 처리 전 취소·큐 포화·서비스 해제도 SINK_REJECTED로 완료한다. 전송은 bounded/lossy이므로 느린 UI는 일부 이벤트를 놓칠 수 있다. 키로 upsert하고 무제한 UI 로그를 쌓지 않는다. stop/disable은 개인정보 정리를 우선해 대기 이벤트를 버리므로 마지막 Decision/Stopped 수신을 기다리면 안 된다. 종료 확인에는 state Off를 쓴다.
- POLICY_BLOCK은 외부 음원/명령 과반 등 차단, 또는 삭제할 이전 블록이 없는 녹음 거부다. COMMAND_LINES_DROPPED는 허용 블록에 명령 줄이 섞여 오디오 전체를 버린 경우다. per-line 오디오 경계가 없어서 기존 동작을 유지한다.
- RETRO_DELETE는 실제 lab ID가 삭제됐을 때만 발생하며 savedCountDelta=-1이다. `TalkRecLab.sink.value?.state`의 deletedBlockId/refusalId로 어떤 이전 블록인지 표시할 수 있다.
- JUDGE_FAILED는 판별기 로드 불가/실행 실패 후 기존 fallback을 쓴 경우다. **저장 승인이 이어질 수 있어 savedCountDelta=+1일 수 있다.** 확률/kind는 null이고, 실행 자체가 없었다면 judgeMs도 null이다. 예산 거절이 우선하면 BUDGET, sink 거절이 우선하면 SINK_REJECTED다. 일반 SAVED도 랩에서는 메모리 승인만 뜻한다.
- kind는 기존 `ConversationKind.name` 문자열이다. policyReasons는 PolicyGate가 반환한 짧은 규칙 ID 목록이다.
- SttError 코드는 `stt_reconnect`, `stt_failed`이다. SourceError 코드는 `file_unreadable`, `unsupported_audio`, `corrupt_audio`, `playback_failed`이다. 입력/SDK 예외 본문을 표시하거나 로깅하지 않는다.
- `TalkRec.state`의 savedCount는 랩 sink 승인 횟수에서 retro-delete를 뺀 값이며 실제 파일 저장 수가 아니다. 기존 CaptureState의 다른 카운터도 그대로 수집할 수 있다.

## 요약 try-out API

```kotlin
// com.drivediary.talkrec.summary
class SummarySettingsStore(context: Context, debugBuild: Boolean = /* 앱 debuggable 플래그 */) {
    suspend fun trySummarize(transcript: String): ConnectionTestResult
    suspend fun testConnection(): ConnectionTestResult
}

data class ConnectionTestResult(
    val ok: Boolean,
    val elapsedMs: Long,
    val result: SummaryResult? = null,
    val modelName: String? = null,
    val errorCode: String? = null,
)
```

기존 앱 settings store 인스턴스를 재사용한다. 사용자가 요약 시도 버튼을 누를 때만 원하는 final/block 텍스트를 조합해 전달한다. provider가 GEMINI/AGY면 그 설정의 서버로 텍스트가 전송되며, FAKE면 로컬 테스트 결과다. 기존 parked-only 편집/시험 gate를 그대로 적용한다. 현재 modelName과 실제 응답 modelName을 구분해 표시할 수 있다. 자동 저장·worker enqueue는 없다.

## 스레딩

- facade start/stop은 visible Activity의 Main에서 호출하는 것을 권장한다. 내부 소유권 검사는 synchronized로 원자적이다.
- 서비스 assembler·Level·BlockClosed·SourceEnded·Stopped 및 Pleos partial/final/error 발행은 Main이다. Decision은 보통 ordered worker의 IO이며 큐 폐기/취소 fallback은 Main 등 취소를 수행한 스레드일 수 있다. SourceError는 디코딩 IO에서 발행한다.
- **SharedFlow collector는 발행 스레드로 이동하지 않고 수집한 coroutine의 dispatcher에서 실행된다.** UI에서 Main scope로 collect하면 UI 콜백도 Main이다. 콜백에서 파일 작업/동기 추론을 하지 않는다. 이벤트 순서는 실제 발행 순서이며 판정 결과 사이에 다른 실시간 이벤트가 끼어들 수 있다.
- 디코딩/변환/AudioTrack write/시간 pacing은 `flowOn(Dispatchers.IO)`에 있다. write는 non-blocking이며 취소 가능한 delay를 쓴다. 장치 드라이버의 동기 configure/read 호출 자체는 반환될 때까지 기다릴 수 있다.
- 요약 요청은 suspend API다. provider의 네트워크와 설정 읽기는 기존 IO 경로를 사용하고, 검증/결과 반환은 호출 coroutine에서 이어진다. 화면 종료 시 요청 job도 취소한다.

## 권한과 AVD user 10

manifest에 RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, WAKE_LOCK, INTERNET, POST_NOTIFICATIONS, `pleos.car.permission.STT_SERVICE`가 이미 선언돼 있다. **Mic/File 두 모드 모두 RECORD_AUDIO 런타임 허용 후 visible Activity에서 시작한다.** 파일도 동일한 microphone-type FGS를 재사용하기 때문이다. 파일 선택 grant가 있으면 READ_MEDIA_AUDIO/외부 저장소 전체 접근 권한을 추가할 필요가 없다.

Pleos AVD의 앱 사용자 번호는 10이다. 외부 실행 담당자는 다음처럼 준비할 수 있다(여기서는 실행하지 않음).

```powershell
adb shell am get-current-user
adb shell pm grant --user 10 com.dongbantest.app android.permission.RECORD_AUDIO
adb shell pm grant --user 10 com.dongbantest.app android.permission.POST_NOTIFICATIONS
adb shell mkdir -p /storage/emulated/10/Download
adb push x.m4a /storage/emulated/10/Download/x.m4a
adb shell ls -l /storage/emulated/10/Download/x.m4a
```

앱을 user 10으로 열고 파일 picker의 내부 저장소/Download에서 선택한다. AVD가 다중 연결돼 있으면 모든 명령에 `-s <serial>`을 붙인다. 다른 사용자 저장소 쓰기가 거절되면 해당 개발용 AVD에서 지원되는 root/Device Explorer 경로로 user 10의 디렉터리에 넣어야 한다. STT 커스텀 권한은 설치된 Pleos 이미지에서 허용 여부를 별도로 확인한다.

에뮬레이터로 오디오 파일을 드래그해도 된다. Android 공식 문서의 기본 목적지는 `/sdcard/Download/`이다([파일 추가](https://developer.android.com/studio/run/emulator-install-add-files)). 다중 사용자 Pleos 이미지에서 이 경로가 user 10 저장소라고 가정하면 안 된다. `/storage/emulated/0/Download/`와 `/storage/emulated/10/Download/`를 확인하고, user 0에 들어갔다면 원본 PC 파일을 위 adb push로 user 10에 다시 넣는 것이 확실하다.

PC 마이크는 Emulator의 `...` → Extended controls → **Microphone → Enable Host Microphone Access**를 켠다. 기본값은 꺼짐이다. Windows 설정에서도 마이크 접근 및 데스크톱 앱 마이크 접근을 허용하고 기본 입력 장치를 선택한다. Android Studio 내장 emulator에서 해당 항목이 안 보이면 별도 창 emulator를 연다. `Virtual microphone attached`는 모의 헤드셋 설정이며 Host Microphone Access와 별개다([공식 Extended controls](https://developer.android.com/studio/run/emulator-extended-controls)). 앱 user 10의 마이크 권한도 따로 허용해야 한다.

## 검증 결과

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i8-jvm.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i8-types.ps1
```

- 캐시 Kotlin compiler 2.2.10/JDK로 Android SDK 없이 컴파일 및 JUnit 실행.
- 순수 JVM suite: **85개 통과**. 기존 judge/tokenizer/golden/private-dev, assembler, policy, PCM/ring, processor/budget, summary/provider/display-tags 회귀 테스트 포함.
- 추가 검증: 44.1 kHz stereo → 16 kHz mono의 길이·440 Hz 주파수·평균 진폭, 48 kHz/8 kHz 길이와 주파수, 16 kHz bit-exact, 반대 위상 채널 상쇄, 불규칙 청크 경계/소수 길이/빈 입력.
- 이벤트 off 무발행, 새 세션 replay 없음, 이전 세션 Decision 차단, 느린 collector의 텍스트 버퍼 폐기, RMS 제한, 각 블록의 단일 Decision 및 모든 outcome, 취소 시 PCM 삭제 확인.
- LabSink의 승인/최근 ID 역순 삭제/중복 거부/정리 후 늦은 호출 차단 확인.
- summary try-out의 정확한 입력 전달, effective model, validation retry, 안전한 error code 및 cancellation 확인.
- 호스트 API 대체 클래스 기반 전체 변경 연결 코드 타입 검사 통과. 실제 `SummarySettingsStore`의 FAKE provider/current model 적용 및 testConnection 공통 경로, TalkRecLab의 pending/Paused/소유권 처리도 **2개 추가 호스트 테스트 통과**. 이 대체 클래스들은 `.talkrec-notes/i8-host-stubs/`에만 있으며 Android 빌드에 포함되지 않는다.
- `.talkrec-notes/`는 기존 로컬 git exclude 대상이다. 인계 문서와 재실행 스크립트는 이 작업트리에 실제 생성돼 있지만 `git status`에는 나오지 않는다.

## 외부 검증 / 남은 항목

이 환경은 Android SDK를 읽을 수 없어 Gradle/에뮬레이터를 실행하지 않았다. 호스트 대체 클래스 타입 검사는 Android API, manifest merge 또는 실제 서비스 lifecycle을 검증하지 않는다. 외부 담당자가 다음을 확인해야 한다.

1. app/talkrec debug Gradle 빌드 및 release에서 debug facade가 제외되는지 확인.
2. user 10에서 마이크/FGS/STT 권한과 host mic passthrough, 이벤트 실시간 표시 확인.
3. AAC/m4a, MP3, WAV(44.1/48/8/16 kHz, stereo/mono), Ogg/Opus를 실제 플랫폼 decoder로 시험. 손상·미지원 파일의 SourceError와 Paused 표시 확인.
4. 파일 총 재생 시간 + 25초 drain 및 마지막 블록 판정, playAloud의 체감 동기·차량 media routing/음량, 도중 정지 시 codec/AudioTrack/마이크 해제 확인. AudioTrack 하드웨어 지연까지 sample-accurate 동기화하는 기능은 없다.
5. 활성/일시정지 여행 중 랩 시작 거부, 파일 종료 후 stop → Off → 재시작, 화면 종료 시 UI 텍스트 삭제, 여행으로 대체될 때 이벤트 비활성화 확인.
6. 랩 전후 DB 행/클립 파일 수가 변하지 않는지 확인. 요약 try-out은 선택 provider의 연결 설정이 정상인 상태에서 UI 버튼으로 시험.

선형 리샘플러는 테스트용 보간이며 고급 anti-alias 필터는 포함하지 않는다. 플랫폼이 지원하지 않는 코덱/DRM 자료, 동적으로 sample rate/channel 수가 바뀌는 스트림은 지원하지 않는다. 일반 파일 재생 중 소스 전환/loop 기능은 facade에 추가하지 않았다.

## 최종 git 상태

최종 검증 후 `git status --short` 결과를 아래에 기록한다.

작업 중 `app/src/debug/AndroidManifest.xml`의 외부 변경이 관찰됐다. 본 코어 작업에서는 해당 파일을 수정하지 않았다. 아래는 조회 시점의 작업트리 전체 상태다.

```text
 M app/src/debug/AndroidManifest.xml
 M talkrec/src/main/java/com/drivediary/talkrec/TalkRec.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/CaptureProcessor.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/ConversationCaptureService.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/PleosSttClient.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummarySettingsStore.kt
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
