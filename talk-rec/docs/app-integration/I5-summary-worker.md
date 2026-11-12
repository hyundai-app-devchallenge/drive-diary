# I5: 외부 LLM 제목·태그 / 설정 API / SummaryWorker

작성: 2026-11-13. 작업 위치 `C:\github\drive-diary-int`, 브랜치 `feat/talk-rec-integration`.

요청한 비 UI 구현과 호스트 JVM 검증을 완료했다. **Android 빌드·manifest 실제 병합·Keystore/WorkManager의 AVD 실행·실제 요약 10개 검토는 아직 하지 않았다.** SDK 접근 실패를 재시도하거나 Gradle/curl.exe를 실행하지 않았다.
쓰기 작업은 이 worktree 안에서만 했다. 참고 저장소는 읽기만 했고, commit/push/브랜치 전환은 하지 않았다.
`app/src/main/java/com/dongbantest/app/ui/**`, `app/src/debug/**`는 이번 작업에서 수정하지 않았다. 이 경로의 status 변경은 기존/동시 UI 작업이다.

## 구현한 것

- `talkrec/summary/SummaryProvider`: 기존 `suspend summarize(String): SummaryResult`를 유지하고 실제 사용 모델을 읽는 `modelName`을 추가했다.
- `SummaryPrompt`: `assets/prompts/summary.md` 전체가 시스템 지시문이다. `## 태그` 절의 쉼표 목록을 파싱하며, 목록이 없거나 중복/모호하면 실패한다. 고정 태그 목록을 구현 코드에 복사하지 않았다.
- `SummaryValidator`: JSON 객체, 제목 1~25 Unicode code point/공백만 금지/줄바꿈 금지, 목록 안 태그 1~3개, `story|moment`를 검증한다. 응답 전체를 감싼 JSON 코드 펜스는 제거한다. Android org.json의 느슨한 문법 차이를 막기 위해 따옴표/쉼표/숫자/중복 키/중첩 깊이도 검사한다. 원문을 포함할 수 있는 JSON 예외는 전달하지 않는다.
- `GeminiSummaryProvider`: HttpURLConnection POST `v1beta/models/{model}:generateContent`, `systemInstruction`, 사용자 전사 한 개, `generationConfig.responseMimeType=application/json`, 태그 enum을 포함한 `responseJsonSchema`. 연결/읽기 timeout 각각 30초. 키는 **`x-goog-api-key` 헤더에만** 들어간다. 응답의 `modelVersion`이 있으면 `summaryModel`로 쓰고, 없으면 요청 모델을 쓴다.
- `AgyBridgeSummaryProvider`: POST `<relayBase>/summarize`, UTF-8 JSON `{transcript, model}`만 보낸다. 응답 title/tags/kind를 검증하고 응답의 실제 `model`을 보관한다. 읽기 timeout 120초. relay의 32,768바이트 요청 제한을 넘으면 자르지 않고 `request_too_large`로 실패한다.
- `FakeSummaryProvider`: 전사와 무관한 `테스트 대화`, 프롬프트 첫 태그, `story`를 결정적으로 반환한다.
- `SummarySettingsStore`: SharedPreferences + Android Keystore AES/GCM. 새 프로덕션 의존성은 추가하지 않았다.
- `SummaryProcessor`: Android가 없는 워커 로직이다. fake target/provider로 완료·실패·재시도·동의 철회를 테스트한다.
- `SummaryWorker`: CoroutineWorker, 네트워크 제약, 지수 backoff, 고유 작업 체인. 앱 저장소 구현은 `app/data/talk/RepositorySummaryTarget.kt`다.
- `displayTags`: 화면을 읽을 때만 목적지/요일/시간대 태그를 더하는 순수 함수다. DB의 LLM 태그에는 이 결과를 저장하지 않는다.

프롬프트 원본과 asset의 SHA-256은 둘 다 다음과 같고, 바이트 단위 비교도 통과했다.

```text
aebaad3a66f46a3c281d55bc99880f4acc28fca23bb0ece74b7a57acc96c4e01
```

원본은 LF 43개/CRLF 0개다. `.gitattributes`에 이 asset만 `text eol=lf`를 지정해 Windows checkout에서도 LF를 유지하게 했다.

## UI 후속 작업용 정확한 API

패키지: `com.drivediary.talkrec.summary`. 앱 공용 인스턴스는 다음으로 얻는다.

```kotlin
val store: SummarySettingsStore =
    (context.applicationContext as DongbanApplication).container.summarySettingsStore
```

공개 설정 API의 Kotlin 시그니처는 다음과 같다. `StateFlow`는 요청한 `Flow`로도 사용할 수 있다.

```kotlin
enum class SummaryProviderType { GEMINI, AGY, FAKE }

data class SummarySettings(
    val provider: SummaryProviderType,
    val modelName: String,
    val relayBaseUrl: String = SummaryDefaults.RELAY_BASE_URL,
    val showTitlesWhileDriving: Boolean = true,
)

data class ConnectionTestResult(
    val ok: Boolean,
    val elapsedMs: Long,
    val result: SummaryResult? = null,
    val modelName: String? = null,
    val errorCode: String? = null,
)

data class SummaryResult(
    val title: String,
    val tags: List<String>,
    val kind: String,
)

class SummarySettingsStore(
    context: Context,
    debugBuild: Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
) {
    val settings: StateFlow<SummarySettings>
    suspend fun setProvider(provider: SummaryProviderType): Unit
    suspend fun setModelName(modelName: String): Unit
    suspend fun setRelayBaseUrl(relayBaseUrl: String): Unit
    suspend fun setShowTitlesWhileDriving(show: Boolean): Unit
    suspend fun setApiKey(apiKey: String): Unit
    suspend fun apiKeyLast4(): String?
    suspend fun testConnection(): ConnectionTestResult
}
```

- 설정 Flow에는 키 원문·끝 4자리·암호문을 넣지 않는다. 키 저장/삭제 후 UI coroutine에서 `apiKeyLast4()`를 다시 읽는다. 저장한 키가 없으면 null이며, 복호화할 수 없으면 `SummaryException(KEY_UNAVAILABLE)`다.
- `setApiKey("")`는 저장한 암호문과 IV를 삭제한다. `setModelName("")`는 현재 제공자의 기본 모델로 되돌린다. 제공자를 바꾸어도 각 제공자의 편집한 모델은 따로 기억한다.
- setter는 `SummaryException`의 짧은 코드로 오류를 알린다. `SummaryException.code: SummaryErrorCode`를 사용하고 예외/설정/입력값을 로그에 넣지 않는다.
- `testConnection()`은 저장된 대화 대신 아래 고정된 지어낸 두 줄만 보낸다. 성공은 `ok=true, elapsedMs, result, modelName`, 실패는 `ok=false, elapsedMs, errorCode`다. coroutine 취소는 정상 전파한다.

```text
오늘 학교 화단에서 달팽이를 봤어
비가 와서 나왔나 봐
```

주요 오류 코드는 `invalid_output`, `invalid_settings`, `key_unavailable`, `auth`, `network`, `timeout`, `rate_limit`, `server_error`, `http_error`, `request_too_large`, `storage`, 연결 시험의 예기치 않은 오류는 `connection_error`다. 원문 응답이나 플랫폼 예외 메시지를 UI 오류로 넘기지 않는다.

화면은 후속 작업에서 `app/src/debug`에 만들고, 키 입력·설정 변경·연결 시험은 기존 앱의 정차 제한으로 막아야 한다. 이 store 자체는 차량 주행 상태를 알지 않는다. `showTitlesWhileDriving`은 UI에서 읽어 적용한다. 설계 §5의 외부 AI 별도 동의와 무료/유료 등급 데이터 정책 안내도 유지해야 한다.

표시 태그 API:

```kotlin
fun displayTags(
    llmTags: List<String>,
    destination: String?,
    createdAt: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<String>
```

`createdAt`은 epoch ms다. 예를 들어 `displayTags(memory.talk?.tags.orEmpty(), trip.destination, memory.createdAt)`로 호출한다.
순서는 기존 LLM 태그 → 공백을 제거한 목적지 → 한국어 요일 → 시간대이며 공백/중복은 제거한다. 0~5시는 새벽, 6~11시는 아침, 12~17시는 오후, 18~21시는 저녁, 22~23시는 밤이다.
목적지가 없어도 요일/시간대는 붙는다. 태그 개수 제한은 UI가 적용하며, 주행 중 2개/최근 3개 등 기존 설계 제한은 UI 작업의 몫이다.
FAILED/SKIPPED에는 `llmTags=emptyList()`로 호출해 기기 태그만 보여 주고 제목은 기존 `목소리 HH:mm` fallback을 유지한다.

## 기본값과 공식 Gemini 문서 확인

| 항목 | 기본값 |
| --- | --- |
| debug 제공자 | AGY (호스트 앱 FLAG_DEBUGGABLE 기준) |
| release 제공자 | GEMINI |
| Gemini 모델 | `gemini-3.8-flash` |
| agy 모델 | `gemini-3.8-flash-medium` |
| Fake 모델 | `fake-summary-v1` |
| relay base | `http://10.0.2.2:8771` |
| 주행 중 제목 | true |
| API 키 | 없음 |

2026-11-13 Python urllib로 다음 공식 페이지를 실제 읽었다. models 페이지가 일반 텍스트 Flash를 `gemini-3.8-flash` stable로 명시하고 있다. Omni Flash는 영상 생성 모델이므로 이 요약의 기본값으로 선택하지 않았다. release에서는 AGY 선택을 거부하고, 저장된 AGY 값이 있더라도 GEMINI로 읽는다.

- [모델 목록](https://ai.google.dev/gemini-api/docs/models)
- [generateContent REST reference](https://ai.google.dev/api/generate-content) (`systemInstruction`, `responseMimeType`, `responseJsonSchema`, generateContent 경로 확인)
- [API key 문서](https://ai.google.dev/gemini-api/docs/api-key) (`x-goog-api-key` 헤더 확인)

이 확인은 문서 조회이며 실제 Gemini 키를 이용한 API 호출을 했다는 뜻은 아니다. 모델 이름은 UI에서 바꿀 수 있다.

## 워커·저장소 연결과 재시도

```kotlin
interface SummaryProvider {
    val modelName: String
    suspend fun summarize(transcript: String): SummaryResult
}

class PendingSummary(val id: Long, val transcript: String)

interface SummaryTarget {
    suspend fun pending(): List<PendingSummary>
    suspend fun canSend(id: Long): Boolean
    suspend fun save(id: Long, result: SummaryResult, model: String): Unit
    suspend fun fail(id: Long): Unit
}

interface SummaryWorkerDependencies {
    val summaryTarget: SummaryTarget
    val summarySettingsStore: SummarySettingsStore
}

interface SummaryWorkerOwner {
    val summaryWorkerDependencies: SummaryWorkerDependencies
}

object SummaryWorkScheduler {
    const val UNIQUE_WORK_NAME = "talkrec.saved-clip-summaries"
    fun enqueue(context: Context, throughMemoryId: Long): Unit
}
```

- `AppContainer`가 dependencies를, `DongbanApplication`이 owner를 구현한다. 별도 전역 singleton/WorkerFactory 설정은 필요 없다.
- `TalkSinkImpl`은 I4 파일+Room 커밋이 끝나고 해당 행이 여전히 PENDING이면 callback으로 enqueue한다. 앱 시작 시에도 PENDING의 최대 ID를 읽어 한 번 enqueue한다. PENDING이 없으면 새 작업을 만들지 않는다.
- 네트워크 `CONNECTED`, `APPEND_OR_REPLACE` 고유 작업 체인, 지수 backoff 30초다. 일시 오류의 총 실행은 최대 5회(0~4번째), 대기는 30/60/120/240초다. 네트워크 제약을 기다리는 시간은 API 시도 횟수가 아니다. Fake도 워커 경로에서는 이 제약을 사용한다.
- KEEP의 마지막 조회/종료 사이에 새 저장이 발생하면 enqueue가 사라질 수 있어 successor를 붙인다. enqueue 당시 memory ID 상한을 inputData에 고정하여 이전 작업의 backoff 중 생긴 새 조각은 다음 작업에서 새 시도 횟수로 시작한다. 같은 체인은 순서대로 실행되고 DONE/FAILED/SKIPPED는 다시 보내지 않는다.
- HTTP 제공자는 한 번 호출한다. `SummaryProcessor`와 `testConnection`이 공통 검증 함수로 최초 + **invalid output 재요청 한 번**을 담당한다. 워커는 typed Fake 결과도 다시 검증한다.
- relay의 `502 {error: invalid_output}`는 relay가 이미 최초+재요청을 모두 실패한 것이므로 앱이 다시 요청하지 않고 FAILED다. 200이지만 형식이 깨진 relay 응답은 앱이 한 번 재요청한다. 일반 backend 502/5xx·429·네트워크·timeout은 backoff 대상이다. 인증/설정/크기 오류는 즉시 FAILED다.
- 성공 시 I4 `TripRepository.updateTalkSummary`를 통해 제목/LLM 태그 JSON/종류/실제 모델/DONE을 저장한다. 실패는 title/tags/model을 null로 두어 UI fallback을 유지한다. `talk_meta` 열 추가나 DB 버전 변경은 없다.
- `TripDao`의 요약 UPDATE에는 `AND summaryStatus='PENDING'`을 추가했다. PENDING VOICE 한 개를 읽는 `pendingTalkMemory`와 저장소의 `getPendingTalkSummary`도 추가했다. 삭제되거나 이미 다른 상태인 행을 덮어쓰지 않는다.
- `RepositorySummaryTarget`은 전송 직전(검증 재요청 포함)과 저장 직전에 최신 PENDING 존재 여부 및 `getSendToAi(tripId)`를 재확인한다. 동의 철회된 PENDING은 SKIPPED로 바꾸고 보내지 않는다. 원래 SKIPPED는 pending 목록에 들어오지 않는다.

## 보안과 debug cleartext

- provider에 전달하는 사용자 데이터는 `memories.transcript`뿐이다. 요청에 오디오/파일 경로/여행·memory ID/목적지/좌표/동승자 메타데이터를 추가하지 않는다. 기기 태그는 표시용 함수에서만 붙인다.
- 키는 `AndroidKeyStore` alias `talkrec.summary.api-key.v1`의 AES-256 키, `AES/GCM/NoPadding`, 128-bit GCM tag, 매 저장마다 새 무작위 IV를 사용한다. AAD는 `talkrec_summary/api_key/v1`다.
- `talkrec_summary.xml` SharedPreferences에는 암호문과 IV만 저장한다. 공개 키 원문 getter는 없다. 앱 main의 `backup_rules.xml`과 `data_extraction_rules.xml`에서 이 파일을 클라우드 백업/기기 이전 대상에서 제외했다. 잘못 복원되거나 Keystore 키를 사용할 수 없으면 원문으로 fallback하지 않고 실패한다.
- 전사·제목·키·설정 로그 호출은 추가하지 않았다. HTTP 요청/응답 객체는 body/header를 자동 출력하는 data class가 아니다. HTTP/JSON 예외의 원문/원인은 오류로 전파하지 않는다. redirect를 따라가지 않고 URLConnection 캐시도 끈다.
- cleartext 설정은 `talkrec/src/debug/res/xml/talkrec_debug_network_security_config.xml`에만 있다. base는 false, **10.0.2.2 정확한 호스트만 true**, 하위 도메인은 불허한다. debug library manifest가 `android:networkSecurityConfig`로 참조한다. main/release에는 이 설정을 넣지 않았다.
- relay 설정 검사도 HTTP는 `10.0.2.2`만 허용한다. HTTPS relay URL은 허용하되 userinfo/query/fragment는 거부한다. 앱 debug/main manifest 원본에는 충돌하는 networkSecurityConfig가 없음을 읽어서 확인했다. **AGP의 실제 병합과 release 제외 확인은 Claude의 빌드에서 남아 있다.**

## 실제 검증 결과

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i5-jvm.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i4-jvm.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i5-types.ps1
git diff --check
```

- I5: **OK (33 tests), 0.201초**. validator/펜스/길이/태그/엄격 JSON/태그 목록 파싱, Fake DONE, 일시 오류 최대 5회와 복구, invalid output 한 번 재요청, FAILED, SKIPPED/동의 철회/취소, backoff 중 새 행의 시도 분리, Gemini 요청·키 header-only·실제 모델, relay 오류/정상 응답/32KB 제한, 표시 태그를 검사했다.
- I2/I3: **OK (39 tests), 12.240초**. I4: **OK (11 tests), 8.247초**. 합계 **83개 통과**다. I4 스크립트가 실제 Models/Entities/TripDao/TripRepository를 cached Room annotation과 함께 컴파일하므로 신규 DAO/저장소 시그니처도 포함한다.
- `verify-i5-types.ps1`: SettingsStore/Worker/RecordingConsentStore/RepositorySummaryTarget의 Kotlin 타입 검사 통과. `.talkrec-notes/i5-host-stubs`의 **손으로 작성한 Android/WorkManager API stand-in**을 썼으며 앱 빌드에는 포함되지 않는다. 실제 Android API/Keystore/WorkManager를 검증한 결과로 해석하면 안 된다.
- XML 5개 파싱, 프롬프트 바이트 동일성, `git diff --check` 통과. Git의 기존 RecordingConsentStore LF→CRLF 안내만 나왔다.
- Python urllib로 PC `http://127.0.0.1:8771/health`를 확인했다: `ok=true`, `model=gemini-3.8-flash-medium`. 이는 PC relay 프로세스 확인이며 AVD 연결/agy 생성 호출 검증은 아니다. 실제 전사는 보내지 않았다.
- Kotlin/JVM은 기존 cached compiler 2.2.10, coroutines 1.8.0, JSON 20240303, JVM target 11을 사용했다. JDK에서 URL(String) deprecation 안내가 하나 나오며 Android minSdk 호환을 위해 사용한다.

## Claude가 AVD에서 확인할 절차

PC relay는 이미 실행 중이다. 같은 포트에 또 띄울 필요 없다. 재시작이 필요한 경우 참고 명령은 다음과 같다(이 작업에서 실행/수정하지 않았다).

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
.\.venv\Scripts\python.exe -m tools.summary_relay --port 8771
```

SDK에 접근 가능한 셸에서 빌드/테스트한다.

```powershell
Set-Location C:\github\drive-diary-int
.\gradlew.bat :talkrec:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
.\gradlew.bat :app:processDebugMainManifest :app:processReleaseMainManifest
# I4 Room migration/storage 검증도 별도로 실행한다.
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dongbantest.app.data.local.TalkMigrationTest,com.dongbantest.app.data.local.TalkStorageTest
```

1. merged debug manifest가 talkrec debug network XML을 참조하고 XML이 APK에 들어갔는지 확인한다. release merged manifest에는 그 참조가 없어야 한다. 전체 cleartext=true 설정은 쓰지 않는다. 만약 다른 debug 작업이 networkSecurityConfig를 추가하여 충돌하면 **Claude가 app debug manifest/리소스에서** 같은 호스트 제한 정책으로 병합해야 한다(이번 작업의 off-limits 경로라 수정하지 않았다).
2. AVD user 10에 설치한다. 후속 debug 설정 화면 또는 debugger evaluate로 공용 store의 `settings`를 읽는다. 초기 AGY/모델/relay URL/주행 제목=true, FAKE↔AGY 변경과 제공자별 모델 보존, 프로세스 재시작 뒤 설정 복원을 확인한다.
3. AGY에서 `testConnection()`을 호출한다. `ok=true`, 양수 elapsedMs, title 1~25자/목록 내 tags 1~3개/story|moment, 실제 modelName을 확인한다. AVD의 `10.0.2.2:8771` 경로를 사용해야 한다. `/health`만으로 agy 인증/생성 성공을 판단하지 않는다.
4. I4의 정상 앱 sink 흐름에서 녹음 동의와 외부 AI 동의를 모두 켠 새 여행을 만든다. I3 DEBUG_INJECT로 지어낸 대화를 주입한다. **RAM counting sink로 바꾸는 DEBUG_START/DebugCaptureActivity는 영구 저장 시험에 쓰지 않는다.** 파일 저장 직후 PENDING → DONE이 자동으로 바뀌는지 확인한다. 앱을 종료했다가 시작해 남은 PENDING도 처리되는지 본다.
5. 다음 SQL은 원문 대신 상태와 길이만 확인한다. title/tags 실제 내용은 사람의 UI 검토에만 쓰고 logcat에 출력하지 않는다.

```sql
SELECT m.id, length(m.transcript), length(t.title), t.summaryStatus, t.summaryModel
FROM memories m JOIN talk_meta t ON t.memoryId=m.id
ORDER BY m.id DESC;
```

6. 외부 AI 동의=false인 새 여행은 SKIPPED이며 relay 요청이 생기지 않아야 한다. backoff 중 외부 AI 동의를 철회한 PENDING도 다음 실행에서 SKIPPED가 되는지 확인한다. 저장/전송 도중 삭제한 행이 다시 생기지 않아야 한다.
7. FAKE 제공자로 새 조각을 저장해 DONE을 확인한다(워커는 여전히 네트워크 조건이 필요). AGY URL을 잠시 `http://10.0.2.2:8772`처럼 비어 있는 포트로 바꾸면 기존 PC relay를 중단하지 않고 연결 실패/backoff를 시험할 수 있다. 5회 뒤 FAILED, title/tags null, UI fallback/기기 태그만 보이는지 확인하고 8771로 되돌린다. **설정 변경만으로 FAILED를 자동 PENDING으로 되돌리지는 않는다.** 다음 정상 시험에는 새 조각을 만든다.
8. 실제 키는 UI에서만 입력한다. 끝 4자리 표시, SharedPreferences가 암호문+IV만 갖는지, 재시작 후 복호화, 키 삭제 후 null을 확인한다. 키가 없는 Gemini 시험은 `key_unavailable`이어야 한다. 키/전사/제목을 adb 명령 인자나 로그로 출력하지 않는다.
9. 설계 I5 완료 조건의 **지어낸 대화 10개를 AGY로, 이어서 같은 10개를 실제 Gemini 유료 키로** 보내 사람이 제목의 사실성/사람 이름 배제/태그 적절성을 검토한다. 이 샌드박스 작업에서는 실제 키/실제 LLM 생성 호출을 실행하지 않았다.

## 남은 항목과 한계

- Android/AGP 컴파일, 실제 manifest 병합/release 제외, Room KSP/SQL, Keystore, WorkManager 및 AVD HTTP 경로 검증은 Claude가 해야 한다. 호스트 stand-in 타입 검사가 이를 대체하지 않는다.
- 후속 debug 설정 화면, 정차 제한, `showTitlesWhileDriving` 적용과 `displayTags`의 UI 매핑은 UI 작업에 남겼다. 이 작업은 UI 파일을 수정하지 않았다.
- 검증은 형식과 허용 태그를 보장한다. 제목의 사실성/사람 이름 배제는 지시문과 실제 10개 사람 검토가 필요하다.
- agy relay 내부 backend timeout은 600초이고 앱 read timeout은 120초다. PC가 오래 바쁘면 앱이 먼저 timeout/backoff할 수 있으며, 이미 시작된 relay/외부 API 호출을 앱이 취소하거나 회수하지는 않는다. 저장 직전 동의는 다시 확인한다.
- DB/초기화 자체 오류는 최대 5회 후 WorkManager failure가 되며, DB를 쓸 수 없다면 행을 FAILED로 바꾸는 것도 보장할 수 없다. 그 PENDING은 다음 앱 시작 복구 대상으로 남는다.
- 기존/동시 변경을 유지했다. 아래 status는 I5만의 변경 목록이 아니라 worktree 전체 스냅샷이다. `.talkrec-notes`는 기존 ignore 규칙 대상이므로 이 보고서/검증 스크립트는 status에 나타나지 않는다.

## git status --short

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
 M app/src/main/java/com/dongbantest/app/ui/components/Components.kt
 M app/src/main/java/com/dongbantest/app/ui/screens/MemoryCaptureScreen.kt
 M app/src/main/java/com/dongbantest/app/ui/screens/TripScreen.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/DrivingParts.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/UiModels.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DiaryScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DrivingScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/HomeScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/SampleData.kt
 M app/src/main/java/com/dongbantest/app/ui/v3connect/V3Mappers.kt
 M app/src/main/java/com/dongbantest/app/ui/v3connect/V3NavHost.kt
 M app/src/main/java/com/dongbantest/app/viewmodel/AppViewModelProvider.kt
 M app/src/main/java/com/dongbantest/app/viewmodel/HomeViewModel.kt
 M app/src/main/java/com/dongbantest/app/viewmodel/TripViewModel.kt
 M app/src/main/res/xml/backup_rules.xml
 M app/src/main/res/xml/data_extraction_rules.xml
 M build.gradle.kts
 M gradle/libs.versions.toml
 M settings.gradle.kts
?? .gitattributes
?? app/schemas/
?? app/src/androidTest/java/com/dongbantest/app/data/
?? app/src/main/java/com/dongbantest/app/data/talk/
?? app/src/test/java/com/dongbantest/app/data/
?? talkrec/
```

## 샌드박스 밖 검증 (Claude, 2026-11-13)

- I5 + I6를 합친 상태에서 `:talkrec:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` 통과. 단위 테스트 112개 통과.
- 병합 manifest: debug는 `@xml/talkrec_debug_network_security_config`(10.0.2.2만 평문 허용)를 참조하고, release에는 networkSecurityConfig가 없다.
- **AVD 끝까지 흐름**: I4 시험에서 PENDING으로 남은 조각(지어낸 바다 이야기)이 앱 재시작 때 `SummaryWorker`로 처리됐다. 에뮬레이터 → `10.0.2.2:8771` 중계 → agy → 17.0초 → `talk_meta`: DONE, 제목 17자, 태그 3개(목록 안), kind story, summaryModel `gemini-3.8-flash-medium`. 중계 로그에는 글자 수·바이트 수만 남았다.
- 주의: 재설치 뒤 위치·활동 권한 창이 뜬 상태에서는 `am start`가 기존 작업을 앞으로만 가져오고 앱 프로세스를 띄우지 않았다. 권한을 허용하고 `am force-stop` 뒤 다시 시작하자 정상 동작했다. 리허설 때 권한 창을 먼저 처리한다.
- 남은 확인: 지어낸 대화 10개 사람 검토(agy, 이어서 실제 Gemini 키), FAKE/실패 경로, 키 저장(설정 화면이 생긴 뒤).
