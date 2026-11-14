# 조용할 때만 건네는 대화 질문: 기술 설계

2026-11-14 초안. 사용자 흐름과 규칙은 [PLAN.md](PLAN.md)에 있다. 대화 기록 연동 설계는 [app-integration/DESIGN.md](../app-integration/DESIGN.md)에 있다. 그 문서 §0이 범위 밖으로 둔 "침묵 감지 질문(Q01·Q02)"을 이 문서가 맡는다.

## 0. 범위

| 범위 안 | 범위 밖 |
| --- | --- |
| 조용함 판단과 질문 상태기 | Gleo 음성 명령으로 질문 부르기·끄기 |
| Q01 화면 확인, STT 글로 반응 나누기 | 대답 내용 이해·기록 |
| 맥락 모으기: 내비 목적지·남은 시간, 위치, Open-Meteo 날씨 | 질문 생성 모델 학습 |
| Cloudflare Workers AI로 질문 생성, 검증, 앱 안 질문 목록으로 대비 | release 빌드의 키 입력 화면 (요약과 같은 한계) |
| Pleos 기기 안 TTS로 질문 읽기 | 효과음, 음성 안내 목소리 고르기 |
| 디버그 앱 질문 실험실, adb 명령 | |

## 1. 이미 있는 것

| 필요 | 이미 있는 것 | 위치 (`drive-diary-int`) |
| --- | --- | --- |
| 말소리 유무 | 100ms 프레임 RMS(`AudioFrame.rms()`), 문턱 `silenceRms = 140`, STT 확정 결과 | `module/talkrec/.../capture/ConversationCaptureService.kt`, `block/BlockAssembler.kt` |
| 마이크 입력 불가 구간 | `isClientSilenced`·경로 변경을 입력 불가로 처리 | `capture/MicAudioSource.kt` |
| 녹음 상태 | `CaptureState.status` (Ready/On/Paused/Off) | `capture/CaptureState.kt` |
| 목적지·남은 거리·시간, 지금 주소 | `NaviMonitor.route`(`NaviRoute`), `onDestination`(`NaviDestination` 좌표), `currentAddress()` | `app/.../navi/NaviMonitor.kt` (Pleos NaviHelper 3.0.5) |
| 위도·경도 | `LocationReader.locationUpdates()` (Pleos FusedLocation → Android GPS) | `app/.../location/LocationReader.kt` |
| 외부 LLM 호출 | `CloudflareSummaryProvider`, `SummaryHttpClient`, 키 보관 `SummarySettingsStore` | `module/talkrec/.../summary/` |
| 질문 화면 | Q01 `QuietCheckScreen`, Q02 `SilenceQuestionScreen`, B01·`B01_QUIET` | `app/.../ui/v3/screens/DrivingScreens.kt` |
| 질문 기록·목록 | `Memory(type=QUESTION)`, "지금까지 나눈 대화", `QuestionBank` | `TripRepository`, `V3Mappers`, `data/repository/QuestionBank.kt` |
| 주행·정차 | `DrivingSafetyMonitor` | `app/.../vehicle/` |

## 2. 전체 흐름

```text
ConversationCaptureService (기존)
  프레임 RMS, STT 확정 수신 ──► SpeechActivity (새, 말소리/조용함/입력 불가만 전달, 글 없음)
                                         │
QuestionCoordinator (새, 앱 프로세스, 여행마다 하나)
  QuietWatcher 상태기 ◄── 여행 경과, NaviRoute.remainSeconds, 녹음 상태, 설정
     │ 조용함이 기준 −2분에 닿음
     ├──► QuestionContextBuilder ─► 내비·위치·Open-Meteo·관계·시간대
     │        └──► QuestionProvider (Cloudflare Gemma 4) ─► 검증 ─► 실패 시 QuestionBank
     │ 조용함 기준 충족
     ├──► Q01 화면 (소리 없음) ─► ReactionListener (10초, STT 글을 그 자리에서 분류 후 버림)
     │ 수락
     ├──► QuestionSpeaker (Pleos TTS ON_DEVICE, ko-KR) + Q02 화면
     │      TTS 재생 구간 + 설정한 녹음 제외 시간 = 캡처 "질문 구간" ─► 묶음 저장 안 함
     └──► Memory(QUESTION) 저장 → 쉬는 시간
```

## 3. 구성 요소와 위치

질문 로직은 대화 기록과 같은 마이크 신호를 쓰므로 `:talkrec` 모듈에 새 패키지 `com.drivediary.talkrec.ask`로 둔다. 내비·위치·화면은 팀 앱에 있으므로 앱 쪽 연결 코드가 맥락을 넘긴다.

| 구성 요소 | 위치 | 하는 일 |
| --- | --- | --- |
| `SpeechActivity` | 모듈 `capture/` | 캡처 서비스가 프레임마다 `Voice` / `Quiet` / `Unavailable` / `SttFinal(text)`을 낸다. `SttFinal` 글은 반응 듣기 창이 열렸을 때만 넘기고, 그 밖에는 글 없이 `Voice`로만 낸다 |
| `QuietWatcher` | 모듈 `ask/` | 순수 Kotlin 상태기(§4). 시계와 입력을 주입받아 단위 테스트한다 |
| `ReactionClassifier` | 모듈 `ask/` | 글 → `ACCEPT` / `DECLINE` / `OTHER`. 규칙 목록은 `assets/ask/reactions.json` |
| `QuestionContext` | 모듈 `ask/` | 보낼 맥락 데이터(§7). 좌표는 들어가지 않는다 |
| `WeatherClient` | 모듈 `ask/` | Open-Meteo 호출, 30분 캐시(§5) |
| `QuestionProvider` | 모듈 `ask/` | 인터페이스. `CloudflareQuestionProvider`(기본), `FakeQuestionProvider`, debug 전용 agy 중계 |
| `QuestionValidator` | 모듈 `ask/` | 길이·물음표·금지어·이름 검사 |
| `QuestionSpeaker` | 모듈 `ask/` | Pleos TextToSpeech 감싸기(§6). 인터페이스 뒤에 두고 Fake로 시험 |
| `QuestionCoordinator` | 앱 `data/ask/` | 여행 시작·종료에 맞춰 만들고 지운다. NaviMonitor·LocationReader·TripRepository에서 맥락을 모으고 화면 상태를 낸다 |
| v3 연결 | 앱 `ui/v3connect` | `QuestionUiState` → B01 / Q01 / Q02 / `B01_QUIET` 선택 |
| `QuestionLabActivity` | 앱 `src/debug` | 질문 실험실(§9) |

## 4. 상태기

```text
OFF ──(여행 시작 + 녹음 On)──► LISTENING
LISTENING ──(조용함 ≥ 기준−2분, 조건 충족)──► PREPARING  (질문 미리 만들기 시작)
PREPARING ──(말소리)──► LISTENING                (만든 질문은 버리지 않고 다음에 씀, 30분 유효)
PREPARING ──(조용함 ≥ 기준)──► CHECKING          (Q01 표시, 반응 창 10초)
CHECKING ──ACCEPT──► ASKING                      (TTS + Q02)
CHECKING ──DECLINE 또는 무반응──► MUTED            (이번 여행 끝까지 먼저 묻지 않음)
CHECKING ──OTHER──► LISTENING                    (대화가 다시 시작됨, 횟수에 안 셈)
ASKING ──(TTS 끝)──► ANSWERING (설정한 시간 동안 묶음 저장 안 함, 기본 90초, "빼지 않음"이면 0초)
ANSWERING ──► COOLDOWN (20분) ──► LISTENING
어느 상태든 ──(녹음 Paused/Off, 도착, 여행 종료)──► OFF   (재개하면 LISTENING, MUTED는 유지)
```

- **조용함 시간**은 연속 `Quiet` 프레임 시간이다. `Voice` 또는 `SttFinal`이 오면 0이 된다. `Unavailable`이나 프레임 끊김은 조용함으로 세지 않고, 그때까지 센 시간도 0으로 돌린다. `BlockAssembler`의 "빠진 PCM은 조용함 증거가 아니다" 규칙과 같다.
- 시간 조건(PLAN §4)은 `QuestionPolicy` 데이터 클래스 하나에 모으고, debug에서 바꿀 수 있게 한다.
- `NaviRoute`가 null(목적지 없음)이면 남은 시간 조건을 건너뛴다. 남은 시간은 `refreshRoute()`로 받은 최근 값을 쓴다.
- 여행당 질문 수는 `ASKING`에 들어갈 때 센다.
- 강제 질문(debug, 정차 중 버튼)은 시간 조건과 MUTED를 무시하고 PREPARING → ASKING으로 바로 간다. 질문 수에 세지 않는다.

## 5. 맥락 모으기

| 항목 | 출처 | 보낼 때 모양 |
| --- | --- | --- |
| 목적지 이름 | `NaviDestination.name` (없으면 팀 앱 `Trip.destination`) | 장소 이름 그대로 (예: "경포해변") |
| 지나는 지역 | `NaviMonitor.currentAddress()` | 시·구·동 수준 문자열에서 동 이하를 자른다 (예: "강원 평창군") |
| 남은 시간 | `NaviRoute.remainSeconds` | 분 단위, 10분 단위로 반올림 |
| 시간대 | 기기 시계 | 새벽/아침/점심/오후/저녁/밤 + 요일 |
| 날씨 | Open-Meteo, 지금 위치와 목적지 | 한국어 낱말 + 기온 정수 (예: "지금: 비, 12도 / 도착지: 흐림, 10도, 3시간 안 비 60%") |
| 관계 | 지금은 상수 "가족" (PLAN §10-5). 팀 앱에 관계 칸이 생기면 여행 설정 값 | 낱말 하나. 동승자 이름은 보내지 않는다 |
| 이미 한 질문 | 이번 여행의 `Memory(QUESTION)` | 최근 5개 질문 글 (반복 피하기) |

**Open-Meteo**

- 요청: `https://api.open-meteo.com/v1/forecast?latitude=…&longitude=…&current=temperature_2m,weather_code,precipitation,is_day&hourly=precipitation_probability&forecast_hours=3&timezone=Asia/Seoul`. 지금 위치와 목적지를 따로 부른다.
- 좌표는 소수 둘째 자리(약 1km)로 반올림해 보낸다. 키가 없고, 받은 좌표가 Cloudflare로 가지 않는다.
- `weather_code`(WMO)는 기기 안에서 낱말로 바꾼다: 0 맑음, 1~2 구름 조금, 3 흐림, 45·48 안개, 51~57 이슬비, 61~67 비, 71~77 눈, 80~82 소나기, 85·86 눈 소나기, 95~99 뇌우.
- 캐시 30분, 실패하면 날씨 없이 질문을 만든다.
- **조건**: 무료 API는 비상업용만 허용하고, 하루 1만 회·분당 600회 제한이 있다. 데이터는 CC BY 4.0이라 날씨를 **보여 주는 곳** 옆에 "Weather data by Open-Meteo.com" 링크를 둔다(디버그 실험실). 질문 문장 안에 녹인 날씨는 앱 정보 화면의 출처 목록에 적는다. 출시하면 유료 요금제나 기상청 단기예보 API로 바꾼다. [Open-Meteo 문서](https://open-meteo.com/en/docs), [약관](https://open-meteo.com/en/terms), [라이선스](https://open-meteo.com/en/licence)

**위치가 없을 때**: 에뮬레이터는 내비 위치가 고정이다. 디버그에서 위치 덮어쓰기(§9)를 쓴다. 위치를 못 받으면 날씨·지역을 빼고 목적지만으로 만든다.

## 6. 질문 읽기 (Pleos TextToSpeech)

공개 문서(가이드 기준 버전 3.3.1)에서 확인한 것:

- 의존성 `ai.pleos.playground:TextToSpeech:3.3.1`, 저장소는 STT와 같은 Pleos Nexus.
- 권한 `pleos.car.permission.TTS_SERVICE` (매니페스트).
- 모드: `HYBRID`(기본, 서버 우선 후 기기 안), `SERVER`, `ON_DEVICE`. **`ON_DEVICE`는 네트워크와 My Project 앱 등록이 필요 없다.** 우리는 `ON_DEVICE`를 쓴다.
- 순서: `TextToSpeech(context, Mode.ON_DEVICE)` → `initialize(OnInitializeListener)` → `addEventListener` → `setLocale(Locale.KOREA)` → `speak(text, isFinal = true)` → `stop()` → `removeEventListener` → `release()`.
- 이벤트: `onReady`, `onStart`, `onUpdatedRms`, `onDone`, `onStop`, `onError(ErrorType)`. 오류 종류에 `ON_DEVICE_ENGINE_ERROR`, `UNAVAILABLE_LANGUAGE`, `PERMISSION_DENIED`, `AUDIO_ERROR`, `NOT_SUPPORTED_API` 등이 있다.
- SDK가 차량 오디오로 직접 재생한다. 클래스에 `setAudioAttributes`, `setAudioZoneId`, `setSpeakerType`이 있으나 문서 본문이 비어 있어 뜻을 확인하지 못했다.
- **AAR(3.3.1)에서 확인한 공개 API**: `TextToSpeech(Context, Mode)`, `initialize(OnInitializeListener)`, `addEventListener/removeEventListener(EventListener)`, `speak(String, Boolean)`, `stop()`, `release()`, `setLocale(Locale)`, `setAudioAttributes(android.media.AudioAttributes)`, `setAudioZoneId(Int)`, `setSpeakerType(String)`(쓸 수 있는 값은 AAR에 상수로 없음), `registerApp(...)`. `ErrorType` 12개는 문서와 같다. AAR은 음성 서비스 `ai.pleos.playground.caas` 4.1.29 이상을 요구하고, Pleos AVD 2.1.0에는 4.2.10이 있다.
- 구현: 모듈 `ask/QuestionSpeaker.kt`의 `PleosQuestionSpeaker`(ON_DEVICE, ko-KR, 한 번에 하나, 초기화 10초·읽기 30초 시간 제한, 이벤트는 이름과 시각만 남김).
- [소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro), [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/declaring-permissions), [재생과 멈춤](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/start-and-stop), [오류 코드](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/error-code)

**AVD에서 먼저 확인할 것 (QS0)**

1. `ON_DEVICE` + `ko-KR`이 Pleos AVD에서 초기화되고 한국어를 읽는가. 첫 `speak`까지 걸리는 시간.
2. 대화 캡처(마이크 열림) 중에 TTS가 재생되는가. 오디오 포커스를 가져가 다른 소리를 줄이는가.
3. TTS 소리가 우리 마이크·STT에 다시 들어오는가. 들어온다면 몇 dB이고 STT가 글로 받는가.
4. `setSpeakerType` / `setAudioZoneId`로 운전석이나 낮은 음량을 고를 수 있는가(AAR 안 클래스·Javadoc 확인).
5. 정확한 메서드 모양(`speak` 반환값, 동시 요청 처리, 글 길이 제한).

**자기 소리 막기**: TTS `onStart`~`onDone`(+1초) 동안 캡처는 그 구간을 `Unavailable`처럼 다룬다. 묶음 조립에 말소리로 넣지 않고, 그 사이 STT 확정 결과는 버린다. TTS가 실패하면(오류·5초 안에 `onStart` 없음) 화면 Q02만 보이고 상태기는 그대로 ANSWERING으로 간다.

**질문 구간 = 저장하지 않음**: `QuestionCoordinator`가 캡처 서비스에 `markNoRecord(fromMs, untilMs)`를 보낸다. 구간은 Q01 표시부터 반응 창 끝까지, 그리고 TTS 시작부터 TTS 끝 + **설정한 녹음 제외 시간**(§7.2, 기본 90초)까지다. "빼지 않음"이면 TTS 구간만 뺀다. 값은 ASKING에 들어갈 때 한 번 읽어 그 질문에 고정한다. 이 구간과 **겹치는** 묶음은 판정·저장·요약하지 않고 버린다(구현: `TalkRec.markNoRecord`, 캡처 서비스 `flushCandidates`). Q01은 8분 넘게 조용한 뒤에만 뜨므로 구간 앞에서 시작해 걸치는 묶음은 사실상 없다. 그래서 잘라 닫지 않고 통째로 버리는 쪽을 택했다. 순환 버퍼의 음성은 평소처럼 3분 안에 덮어쓴다.

**구현 메모 (2026-11-14)**: TTS 소리가 마이크로 들어와 생긴 STT 줄과 묶음도 이 구간 안에 있으므로 따로 막지 않는다. TTS 프레임은 말소리로 잡혀 조용함 시간이 0이 되지만, 질문 뒤에는 어차피 대답 시간과 쉬는 시간이 이어진다.

## 7. 질문 만들기 (Cloudflare Workers AI)

- 모델·키·주소는 대화 요약과 같다: `@cf/google/gemma-4-26b-a4b-it`, `SummarySettingsStore`의 계정 ID·토큰, `…/ai/v1/chat/completions`. HTTP 코드는 `SummaryHttpClient`를 같이 쓴다.
- 지시문은 `talk-rec/model-create/prompts/question.md`에서 관리하고, 앱에는 `module/talkrec/src/main/assets/prompts/question.md`로 옮긴다(요약 지시문과 같은 방식, LF 고정).
- **보내는 것**: §5의 `QuestionContext` JSON만. 좌표, 동승자 이름, 대화 전사 글, 음성은 보내지 않는다.
- **받는 것**:

```json
{"question": "40자 이하 질문 한 문장",
 "hint": "20자 이하 보조 문구 또는 빈 문자열",
 "topic": "destination | weather | time | journey | relation | free"}
```

- **검증**: JSON 형식, `question` 길이 ≤ 40자, 물음표로 끝남, 물음표 하나, 금지 낱말 없음(화면·눌러·주소 같은 낱말은 규칙 목록으로, 사람 이름은 지시문으로 막는다), 이번 여행 질문과 같지 않음. 어기면 1회 다시 요청, 그래도 어기면 `QuestionBank.next()`.
- **시간**: PREPARING에 들어가면 바로 부른다(기준보다 2분 먼저). 8초 시간 제한. ASKING에 들어갈 때 준비된 질문이 없으면 `QuestionBank`를 쓴다. 질문 생성 때문에 TTS를 기다리게 하지 않는다.
- **동의**: 별도 동의를 만들지 않고 **기존 외부 AI(요약) 동의 하나에 질문용 전송을 더한다**(2026-11-14 결정). 이 동의가 없으면 Cloudflare를 부르지 않고 `QuestionBank`만 쓴다. 문구는 아래 §7.1.
- **비용**: 여행당 질문이 많아야 3번이고 재요청을 합쳐도 시간당 몇 회다.
- release 빌드는 요약과 같이 키 입력 화면이 없어 `QuestionBank`만 쓴다.

### 7.1 동의 문구 (S03 외부 AI 동의에 더함)

S03의 두 번째 체크(전사 글을 외부 AI로 보내 제목·태그 만들기, [연동 설계 §7](../app-integration/DESIGN.md))를 넓혀 질문 생성까지 한 동의로 받는다. 문구에서 가장 먼저 보여야 할 것은 **대화가 차 안 기기에서 먼저 걸러진다**는 점이다.

- 외부로 가는 대화 글은 **기기 안 판정 모델(M1)이 1차로 고른 것뿐이다.** 대화는 먼저 차 안에서 글로 옮겨지고, 기기 안 모델과 정책 규칙이 "남길 만한 이야기"인지 판단한다. 남기지 않기로 한 대화는 외부로 보내지 않고, 음성도 3분 안에 지워진다. 그다음 남긴 이야기의 **글만** 외부 AI로 보내 제목·태그를 만든다. 음성은 보내지 않는다.
- 질문을 만들 때는 **대화 글을 전혀 보내지 않는다.** 목적지 이름, 지나는 지역 이름(시·구), 날씨, 시간대만 보낸다.
- 날씨를 받으려고 대략의 위치(약 1km 단위)를 날씨 서비스(Open-Meteo)에 보낸다.

초안 (팀과 문구 확정 필요):

> **AI로 이야기 제목과 질문 만들기**
> 대화는 먼저 차 안에서 동반차의 기기 안 모델이 골라요. 남길 만한 이야기로 고른 것만 글로 외부 AI에 보내 제목과 태그를 붙여요. 고르지 않은 대화와 음성은 밖으로 나가지 않아요.
> 조용할 때 건넬 질문을 만들려고 목적지·지나는 지역 이름·날씨도 외부 AI에 보내요. 이때 대화 내용은 보내지 않아요.

- 체크하지 않으면: 제목은 "목소리 HH:mm", 태그는 기기 안 장소·때 태그만, 질문은 앱 안 질문 목록(`QuestionBank`)만 쓴다. 날씨도 부르지 않는다.
- 설정 화면에는 같은 내용을 "기기 안에서 먼저 고름 → 고른 이야기 글만 외부 AI" 순서 그림 한 줄로 보인다.

### 7.2 질문 설정

| 항목 | 값 | 기본 | 저장 |
| --- | --- | --- | --- |
| 질문 뒤 녹음하지 않는 시간 | 빼지 않음 / 30초 / 60초 / 90초 / 3분 / 5분 | 90초 | 앱 `data/ask/QuestionSettingsStore` (SharedPreferences, 초 단위 정수) |
| 조용할 때 질문하기 | 켬 / 끔 | 켬 | 같음 |

- 사용자 설정이다. 키처럼 숨길 값이 아니므로 암호화하지 않는다.
- 화면: **v3 사용자 설정 화면(`SettingsScreen`, 홈 → 설정)**에 "마이크 증폭" 아래로 둔다. 2026-11-14 구현 때 팀 앱에 이 화면이 이미 생겨 있어 처음 계획(debug 설정 화면)을 바꿨다. release 빌드에서도 바꿀 수 있다.
- 정차 중에만 바꿀 수 있다(대화 요약 설정과 같은 주행 제한).
- 질문 실험실(§9)의 시간 바꾸기는 시험용 덮어쓰기이고, 이 저장 값과 따로 둔다.

## 8. 반응 듣기

- `CHECKING`에 들어가면 `SpeechActivity`의 반응 창을 10초 연다. 그동안만 STT 확정 글이 `ReactionClassifier`로 간다.
- 규칙: 글을 정규화(공백·문장부호 제거)한 뒤 수락 목록을 먼저, 거절 목록을 그다음 본다. 둘 다 아니면 `OTHER`. 말소리 프레임이 1초 넘게 있었는데 STT 글이 없으면 `OTHER`.
- 분류 결과(수락/거절/기타)와 시각만 상태기에 남긴다. 글은 메모리에서 바로 버리고 로그에 쓰지 않는다(대화 기록 규약과 같다).
- 목록 초안은 PLAN §5. 지어낸 반응 문장 40개 정도로 단위 테스트를 만든다(실제 녹음 글은 쓰지 않는다).

## 9. 디버그 앱: 질문 실험실

`동반차 디버그` 런처에 **질문 실험실**(`QuestionLabActivity`, `src/debug`)을 더한다.

| 영역 | 내용 |
| --- | --- |
| 상황 | 지금 위치(위도·경도·출처 Fused/GPS/덮어쓰기), 내비 주소, 목적지·남은 시간, 관계(가족 고정, 질문 비교용으로만 바꿔 볼 수 있음), 시간대. **위치 덮어쓰기**: 서울역·강릉 경포해변·제주공항 프리셋, 좌표 직접 입력 |
| 날씨 | Open-Meteo 응답 요약(지금·도착지), 받은 시각, "Weather data by Open-Meteo.com" 링크 |
| 질문 만들기 | **"지금 위치로 질문 만들기"** 버튼(조용함 조건 무시). 보낸 맥락 JSON, 받은 JSON, 검증 결과, 걸린 시간, 재요청 여부. 제공자 고르기(Cloudflare / agy 중계 / Fake) |
| TTS | "읽기" / "멈춤". 이벤트 시각(준비·시작·끝·오류)과 첫 소리까지 걸린 시간. 모드 고르기(ON_DEVICE 기본, HYBRID 비교용) |
| 흐름 | 시험용 시간 덮어쓰기(조용함·쉬는 시간·녹음 제외 시간, §7.2 저장 값과 별도), "조용함 흉내"(지금 바로 PREPARING→CHECKING), 반응 글 직접 넣기(수락/거절/기타 분기 확인), MUTED 풀기 |
| 상태 기록 | 상태기 전이와 이유(예: `LISTENING→PREPARING quiet=6m remain=42m`). 전사 글은 보이지 않는다 |

adb 명령(debug 전용 수신기, 팀 앱 `Debug*Receiver`와 같은 방식):

```powershell
adb shell am broadcast -a com.dongbantest.app.debug.ASK_FORCE_QUIET -p com.dongbantest.app      # 조용함 기준 충족으로
adb shell am broadcast -a com.dongbantest.app.debug.ASK_FORCE_QUESTION -p com.dongbantest.app   # 바로 질문 (TTS + Q02)
adb shell am broadcast -a com.dongbantest.app.debug.ASK_REACTION --es kind accept -p com.dongbantest.app
```

## 10. 작업 단계 (안)

| ID | 작업 | 담당 | 끝났다고 보는 조건 |
| --- | --- | --- | --- |
| QS0 | Pleos TTS 확인: §6의 1~5를 AVD에서 시험하고 기록 | Claude + Codex | `docs/question-suggest/QS0-tts-check.md`에 결과. ON_DEVICE 한국어 재생 여부가 정해진다 |
| QS1 | 질문 지시문 `prompts/question.md`와 측정 (T64): 지어낸 맥락 30개로 Gemma 4 질문 생성, 검증 통과율·시간, 사람이 자연스러움 확인 | Claude(지시문) + Codex(도구) | 통과율 ≥ 90%, 중앙 응답 시간 표, 사람 확인 결과 |
| QS2 | 모듈 `ask/`: `QuestionContext`, `WeatherClient`, `CloudflareQuestionProvider`, `QuestionValidator`, `QuestionSettingsStore`, Fake | Codex | 단위 테스트(가짜 HTTP), 좌표가 Cloudflare 요청에 없음을 테스트 |
| QS3 | `SpeechActivity`, `QuietWatcher`, `ReactionClassifier`, 캡처 `markNoRecord` | Codex (xhigh) | 상태기 전이 단위 테스트, PCM 파일 주입으로 질문 구간 묶음이 저장되지 않음 |
| QS4 | `QuestionSpeaker` (Pleos TTS), 자기 소리 막기 | Codex | QS0 결과대로 AVD에서 읽기, TTS 구간 STT 글 버림 |
| QS5 | 앱 연결: `QuestionCoordinator`, v3connect Q01/Q02/B01_QUIET, `Memory(QUESTION)` 저장 (관계는 "가족" 상수, 팀 앱 데이터 모델은 바꾸지 않음) | Codex + 팀원 검토 | PLAN §3 흐름이 AVD에서 끊김 없이 돈다 |
| QS6 | 디버그 질문 실험실 + adb 수신기 | Codex | §9 항목 모두 동작 |
| QS7 | 리허설: 지어낸 대화 PCM 주입 + 조용함 + 반응 넣기로 수락·거절·무반응·기타 네 갈래 | Claude + 사람 | 시나리오 표와 걸린 시간 기록 |

QS0·QS1은 나란히 할 수 있다. QS2·QS3도 서로 기다리지 않는다. QS4는 QS0 결과가 필요하다.

**진행 (2026-11-14)**: QS0([결과](QS0-tts-check.md)), QS2~QS6을 구현하고 AVD에서 실험실·adb로 흐름을 확인했다(`drive-diary-int`, 커밋 전). 남은 것은 QS1(질문 지시문 측정 T64: 지어낸 맥락 30개로 통과율·자연스러움)과 QS7(지어낸 대화 PCM 주입 리허설, 실제 발화 반응은 Devbox·실차).

## 11. 위험과 확인할 것

| 위험 | 대응 |
| --- | --- |
| AVD에 기기 안 TTS 엔진이 없거나 한국어가 없음 | QS0에서 먼저 확인. 없으면 HYBRID(앱 등록 필요) 또는 Android 기본 `TextToSpeech`를 대비책으로 비교 |
| TTS 소리가 마이크로 들어와 말소리로 잡힘 | TTS 구간을 입력 불가로 처리, 그 사이 STT 글 버림(§6) |
| 음악·라디오·도로 소음 때문에 조용함이 오지 않음 | 묻지 않는 쪽으로 틀리므로 받아들인다. 리허설에서 문턱 확인 |
| 아이 혼잣말을 수락으로 잘못 봄 | 수락 목록을 좁게, 애매하면 `OTHER`(질문 안 함) |
| 주행 중 알림 규정 | 누를 필요 없는 알림, 저절로 사라짐. 대화 기록과 같이 Pleos 규정 확인 |
| 외부로 위치 관련 정보가 나감 | 좌표는 Open-Meteo에만(1km 반올림), Cloudflare에는 이름만. 동의 문구에 적음 |
| Open-Meteo 상업 이용 불가 | 시연 한정. 출시 전 교체(§5) |
| 네트워크 없음 | 날씨 없이, 그래도 실패하면 `QuestionBank`. TTS는 ON_DEVICE라 네트워크 없이 읽는다 |
| Gleo와 마이크·소리 겹침 | Gleo가 듣는 동안(입력 무음 처리) 조용함을 세지 않음. TTS 도중 Gleo가 열리면 `stop()` |

## 12. 결정

**정한 것 (2026-11-14, 사용자 지시)**

- 확인(Q01)은 화면에만 잠깐 띄우고, 소리로 수락하면 Pleos TTS로 질문한다.
- 날씨는 키 없이 쓰는 Open-Meteo. 현재 위치와 최종 목적지는 Pleos(FusedLocation, NaviHelper)에서 받는다.
- 질문 생성은 Cloudflare Workers AI(대화 요약과 같은 모델·키).
- 질문에서 나온 대답은 녹음·기록하지 않는다. 빼는 시간은 설정에서 고르고 기본은 90초다(§7.2).
- 디버그 앱에서 현재 위치 기반으로 질문을 강제로 만들 수 있게 한다.
- 동행 관계는 일단 가족으로 가정한다.
- 외부 AI 동의는 기존 요약 동의 하나에 질문용 전송을 더한다. 문구는 기기 안 모델이 1차로 고른 대화만 외부로 간다는 점을 앞세운다(§7.1).

**남은 것**: [PLAN §10](PLAN.md#10-결정이-필요한-것).
