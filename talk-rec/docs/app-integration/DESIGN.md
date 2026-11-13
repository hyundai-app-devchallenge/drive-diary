# 팀 앱 연동 설계: 대화 기록 + 외부 LLM 요약·태그 (데모)

2026-11-13 초안. 팀 앱(`feat/mapbox-map`, 커밋 `293535d`)에 우리가 만든 대화 기록 기능을 넣는 설계다. 데모의 요약 한 줄과 태그는 **외부 LLM**으로 만든다. 기기 안 요약·태그는 [미래 설계](../future/v4-on-device-summary.md)로 보류했다. 대화 모델의 설계와 근거는 [model-create/DESIGN.md](../../model-create/DESIGN.md)에 있다.

## 0. 목표와 범위

**데모에서 보여 줄 것**: 여행 중 가족 대화가 저절로 기록되고, 남길 만한 대화만 음성 조각으로 저장되며, 조금 뒤 그 조각에 **한 줄 제목과 태그**가 붙어 다이어리에 보인다.

| 범위 안 | 범위 밖 (이번 데모) |
| --- | --- |
| 여행 중 상시 대화 캡처 (동의한 여행만) | 음성 명령 인식 ("녹음 잠시 멈춰" 등) |
| Pleos STT 연속 전사 | 침묵 감지 질문(Q01·Q02) |
| 묶음 조립, 정책 규칙, M1 판정 (기기 안) | 소리 모델 (보류) |
| 남길 묶음의 음성 조각 저장 + 전사 | 기기 안 요약·태그 ([미래 설계](../future/v4-on-device-summary.md)) |
| 외부 LLM으로 한 줄 제목·태그 | 여행 전체 다이어리 문장 정리 |
| 다이어리(S14)·도착(S11)·만들기(S12) 화면 연결 | 양산 수준의 키 관리 (서버 대리 호출) |

## 1. 팀 앱 현황 (feat/mapbox-map 기준)

- Compose 확정안 v3 화면(`ui/v3`)이 `ui/v3connect`의 변환 함수로 연결돼 있다. Room DB 버전 4, 수동 DI(`AppContainer`).
- 여행 흐름: 홈 → 여행 설정 → **녹음 동의(S03, `RecordingConsentStore`)** → 여행 중(길 리본) → 도착(S11) → 다이어리 만들기(S12) → 다이어리(S14).
- 녹음: `VoiceRecorder`(MediaRecorder, AAC 44.1kHz)로 **버튼을 누를 때만** 한 조각씩 녹음한다. 화면을 떠나면 저장하고 멈춘다. 상시 녹음은 없다.
- `Memory(type=VOICE)`에 `filePath`, `durationMs`, `transcript`(STT 미지원이라 늘 null)가 있다. 다이어리의 이야기 목록(`talks[]`)은 `title = transcript ?: "목소리 HH:mm"`이다.
- 팀 문서(`docs/compose-ui-v3-integration.md`)가 **"기능 필요"**로 남긴 것 중 이번 연동이 채우는 것: 여행 전체 상시 녹음 서비스, 녹음 상태 enum(On/Paused/Off/Ready/Saved), 누적 녹음 시간, S12 "대화를 글로 옮기는 중" 단계, 이야기 제목(STT).
- 주행 중 제한은 AAOS `CarUxRestrictionsManager`로 판단하고, 주행 중에는 기록 열람을 막는다(P01·P02).
- 인터넷 권한이 이미 있다(Mapbox). Pleos 차량에는 Google Play 서비스가 없을 수 있어 Mapbox도 Play 의존성을 뺐다.

## 2. 전체 흐름

```text
[S03 동의: 녹음 + 외부 AI 전송]
        │
        ▼
ConversationCaptureService (포그라운드, microphone)          ── 우리 모듈 :talkrec
  AudioRecord 16kHz 모노 ─┬─► PCM 순환 버퍼 (최근 3분, RAM)
                          └─► Pleos STT (sendAudio, ON_DEVICE) ─► 확정 줄 + 시각
                                   → ① 정규화 → ② 묶음 조립 (침묵·90초)
                                   → ③ 정책 규칙 (기록 거부·명령·외부 소리, T56 rules.json)
                                   → ④ M1 판정 (ONNX int8, 문턱 0.957)
                                   → ⑤ 남길 것이면: 버퍼에서 [시작−3초, 끝+2초] 잘라 AAC 저장
                                   → ⑥ TalkSink → TripRepository: Memory(VOICE) + talk_meta
        ▼
SummaryWorker (WorkManager, 네트워크 있을 때)                 ── 우리 모듈
  전사 글 → 외부 LLM → {제목, 태그, 종류} 검증 → talk_meta 갱신
        ▼
팀 앱 화면 (v3connect 변환만 고침)
  여행 중: 녹음 칩 상태·누적 시간, "저장됨" 수 / 도착·다이어리: 이야기 제목·태그·재생
```

## 3. 구성 요소

우리 코드는 팀 앱 저장소의 **새 Android 라이브러리 모듈 `:talkrec`**에 둔다. 팀원 파일은 연결 부분만 고친다(§8).

| 구성 요소 | 하는 일 | 출처 |
| --- | --- | --- |
| `ConversationCaptureService` | 여행 시작·동의 시 시작, 여행 종료·동의 철회 시 종료. `AudioRecord` PCM을 순환 버퍼와 STT에 함께 보낸다. 멈춤·재개 | `test/stt`의 `MicProbeService`, `ContinuousSttService`, `RamSpeechBuffer` |
| `PleosSttClient` | Pleos SpeechToText SDK 연속 세션, 100ms 단위 `sendAudio`, 최종 결과 콜백. 세션이 끊기면 다시 연다 | `test/stt` |
| `BlockAssembler` | 확정 줄을 묶음으로. 침묵 20초 또는 90초. **알려진 결함**: Pleos 줄 시각이 빈틈없이 이어져 침묵 닫기가 안 걸린다. 줄 시각 대신 **결과 수신 시각과 PCM 음량**으로 침묵을 잰다 | `pipeline/blocks` 규칙 |
| `PolicyGate` | T56 규칙(`policy/rules.json`)을 Kotlin으로. 기록 거부면 그 묶음을 버리고 직전 저장 조각을 지운다 | `policy/` |
| `ConversationJudge` | WordPiece 토크나이저 + onnxruntime-android로 M1 int8 실행. 문턱 0.957. 입력 글은 학습과 같은 정규화·줄 잇기 | `.local/export/m1/` (T58), `common/normalize` |
| `ClipWriter` | 순환 버퍼에서 구간을 잘라 MediaCodec AAC(.m4a)로 저장. 팀 앱 `MediaFileStore`의 경로 규칙을 따른다 | 새로 |
| `TalkSink` | `TripRepository`로 `Memory(VOICE)`와 `talk_meta`를 넣는다. 녹음 상태·누적 시간을 `StateFlow`로 낸다 | 새로 |
| `SummaryWorker` + `SummaryProvider` | 저장된 조각마다 외부 LLM 호출, 결과 검증, 재시도 | 새로 (§5) |

- 판정 결과 확률은 원본과 int8이 최대 0.35 다르다. 기기 이식 검증은 **판정 일치**로 본다(T58 골든 파일 20개 + dev 판정 77개).
- 대비책: M1 로드 실패 시 정책 규칙을 통과한 묶음을 모두 저장 후보로 보고 시간당 상한(10분)으로만 거른다(model-create DESIGN §5).

## 4. 데이터 모델 변경 (Room 4 → 5)

팀원의 `MemoryEntity`는 그대로 두고, 1:1로 붙는 **새 테이블 `talk_meta`**를 더한다. 마이그레이션은 테이블 추가뿐이다.

| 열 | 형식 | 뜻 |
| --- | --- | --- |
| `memoryId` | Long (PK, FK → memories.id, CASCADE) | 대응하는 VOICE 기록 |
| `source` | TEXT | `AUTO`(상시 기록) / `MANUAL`(버튼 녹음) |
| `blockStartMs`, `blockEndMs` | Long | 여행 시작 기준이 아닌 실제 시각(epoch ms) |
| `keepScore` | REAL | M1 keep 확률 |
| `kind` | TEXT | `story` / `moment` (M1 보조 출력, 참고) |
| `title` | TEXT? | LLM 한 줄 제목 |
| `tags` | TEXT? | JSON 배열 문자열 |
| `summaryStatus` | TEXT | `PENDING` / `DONE` / `FAILED` / `SKIPPED`(외부 전송 미동의) |
| `summaryModel` | TEXT? | 사용한 LLM 이름·버전 |

- 전사 글은 기존 `memories.transcript`에 넣는다. 팀 앱이 이미 그 칸을 쓰도록 만들어 두었다.
- 도메인 모델 `Memory`에 `talk: TalkMeta?`를 더하고, `TripWithMemories`를 읽을 때 함께 읽는다.

## 5. 외부 LLM 요약·태그

**언제**: 조각이 저장되면 바로 `SummaryWorker`를 건다(네트워크 조건). 여행 중이어도 돈다. 화면에는 정차·도착 뒤에 보이고, 주행 중에는 §6처럼 최소로만 보인다.

**보내는 것**: 그 묶음의 **전사 글만**. 정규화한 줄을 줄바꿈으로 잇는다. 음성은 보내지 않는다. 동승자 이름, 위치, 목적지 이름도 보내지 않는다.

**태그 합치기**: LLM은 **화제 태그**만 고정 목록에서 1~3개 고른다. **장소·때 태그**는 기기 안에서 붙인다: 목적지 이름(NaviHelper, 팀 앱 `Trip.destination`), 요일·시간대(시계). 위치 정보가 밖으로 나가지 않는다.

**받는 것 (JSON)**:

```json
{"title": "한 줄 제목, 25자 이하",
 "tags": ["고정 목록 화제 1~3개"],
 "kind": "story 또는 moment"}
```

- 화제 태그 고정 목록(초안, 승인 필요): 추억, 아이의 하루, 학교, 놀이, 음식, 자연·풍경, 여행 계획, 가족 이야기, 노래·웃음, 동물, 계절.
- 지시문은 `talk-rec/model-create/prompts/summary.md`에 두고 저장소에서 관리한다. 지시: 대화에 있는 내용만 쓴다, 사람 이름을 쓰지 않는다(호칭은 됨), 다이어리 말투, 전사 오류로 보이는 낱말은 추측해 고치지 않고 피한다.
- **검증**: JSON 형식, 제목 길이, 태그가 목록 안인지. 어기면 1회 다시 요청, 그래도 어기면 `FAILED`.
- **실패·오프라인**: WorkManager가 지수 백오프로 재시도(최대 5회). 끝내 실패하면 제목은 팀 앱의 기존 대비 문구("목소리 HH:mm"), 태그는 기기 안 장소·때 태그만.

**제공자**: `SummaryProvider` 인터페이스 뒤에 둔다. 설정 화면(아래)에서 고른다.

| 구현 | 쓰임 |
| --- | --- |
| `GeminiSummaryProvider` (기본값) | REST `generateContent` + JSON 응답 형식. 우리가 이미 Gemini 3.8 Flash로 Pleos 전사 판정(dev 0.919)을 검증했고 지시문 자산이 있다 |
| `AgyBridgeSummaryProvider` (debug 전용) | **구현 중 시험용.** 에뮬레이터 앱이 PC의 작은 중계 서버(`10.0.2.2:<포트>`, PC 쪽은 127.0.0.1에만 열림)에 요청하고, 중계 서버가 agy(Gemini 3.8 Flash)를 불러 같은 JSON을 돌려준다. API 키 없이 앱 전체 흐름을 시험한다. 중계 서버는 `talk-rec/model-create/tools/`에 두고 기존 `pipeline/label.py`의 `AgyBackend`를 쓴다 |
| `FakeSummaryProvider` | 단위 테스트, 네트워크 없는 시연 |
| (다른 LLM) | 같은 인터페이스로 바꿔 끼운다 |

**설정 화면 (2026-11-13 결정)**: 처음 목적은 "제대로 동작하는지 확인"이므로, 키와 모델을 빌드에 박지 않고 **앱 안 설정 화면에서 정한다**.

- 위치: debug 빌드 전용 화면(팀 앱의 `V3CatalogActivity`처럼 `src/debug`에 둔다). release 빌드에는 들어가지 않는다.
- 항목: 제공자(Gemini / agy 중계 / Fake), **모델 이름**(기본값을 미리 채움: Gemini는 Flash 계열 최신 이름, agy는 `gemini-3.8-flash-medium`. 목록에서 고르거나 직접 입력), API 키(Gemini일 때만), 중계 서버 주소(agy일 때만), **"연결 시험" 버튼**(지어낸 짧은 대화 한 개를 보내 응답 JSON과 걸린 시간을 보여 줌).
- 모델은 정해 두어야 한다: 아무것도 고르지 않아도 동작하도록 **기본값**을 두고, 설정에서 바꿀 수 있게 한다. 실제로 쓴 모델 이름은 `talk_meta.summaryModel`에 남긴다.
- 키 보관: 앱 전용 저장소에 Android Keystore로 암호화해 둔다. 화면에는 끝 4자리만 보이고, 로그·오류 메시지에 남기지 않는다.
- 키 입력·설정 변경은 **정차 중에만** 열린다(팀 앱의 주행 제한 판단을 그대로 쓴다).
- 앱 안에 키가 들어가면 추출될 수 있으므로 **데모 전용**이다. 실제 배포라면 서버가 대신 호출해야 한다.

**데이터 정책**: Gemini API의 무료 등급은 보낸 내용이 서비스 개선에 쓰일 수 있다. 데모에서도 **유료 등급 키**를 쓰거나, 동의 화면에 그 사실을 적는다. 외부 전송은 녹음 동의와 **별도 체크**로 받는다. 체크하지 않으면 `SKIPPED`이고 제목은 기존 대비 문구를 쓴다.

**비용·빈도**: 저장 상한이 시간당 10분이라 호출은 시간당 많아야 7~10회다.

## 6. 화면 연결 (팀 앱 v3connect 변환)

| 화면 | v3 필드 | 연결 |
| --- | --- | --- |
| 공통 녹음 칩 | `recording: RecordingState` | 서비스 상태 → Ready(동의 전)·On·Paused·Off(미동의)·Saved(여행 끝) |
| 공통 녹음 칩 | `recordingText` | 서비스의 누적 캡처 시간 |
| 여행 중 | "지금까지 나눈 대화" `AskedItem(time, title)` + 태그 | 저장된 조각의 시각·제목·태그. **주행 중에도 보인다**(아래) |
| S11 도착 | `saved` | AUTO 조각의 `durationMs` 합 |
| S12 만들기 | `steps[]` | "녹음 저장" 완료 → "대화를 글로 옮기는 중" = `PENDING` 남은 수 → 모두 끝나면 완료 |
| S14 다이어리 | `talks[].title` | `talk.title ?: "목소리 HH:mm"` |
| S14 다이어리 | 태그 | **v3 `TalkUi`에 `tags` 필드가 없다.** 팀원과 작은 UI 변경을 맞춘다(`Tag(small = true)` 재사용) |

- **주행 중 화면 (2026-11-13 결정: 제목·태그를 주행 중에도 보인다)**: 대화가 끝나고 묶음이 닫힌 뒤, LLM 응답이 오면 여행 중 화면의 "지금까지 나눈 대화"에 제목과 태그가 나타난다(대략 30초~2분 뒤). 운전자 주의 분산을 줄이려고 다음을 지킨다.
  - 제목은 한 줄(25자 이하), 태그는 2개까지만 보인다. 스크롤·펼치기·재생 같은 조작은 주행 중에 막는다(팀 앱의 기존 주행 제한과 같다).
  - 새 항목이 나타날 때 소리·진동·깜빡임 같은 주의 끌기를 하지 않는다. 목록은 최근 3개만.
  - Pleos 앱 심사에서 주행 중 글 노출이 문제가 될 수 있으므로, 설정 화면에 **"주행 중 제목 보이기" 켜고 끄기**를 둔다(기본 켬). 규정 확인 결과에 따라 기본값을 바꾼다.
- **버튼 녹음(`VoiceRecorder`)과의 관계**: 상시 캡처와 버튼 녹음이 마이크를 동시에 열면 충돌할 수 있다. 초안: 상시 캡처 중에는 녹음 버튼을 **"이 순간 담기"**로 바꿔, 지금 열린 묶음을 판정과 상관없이 저장하게 한다(`source = MANUAL`). 상시 캡처를 동의하지 않은 여행에서는 기존 버튼 녹음을 그대로 쓴다.

## 7. 동의와 개인정보

- S03 동의를 세 가지로 나눈다: (1) 여행 중 대화 기록(상시 캡처, 남길 것만 저장), (2) 전사 글을 외부 AI로 보내 제목·태그 만들기, (3) 녹음 없이 출발.
- 저장하지 않은 묶음의 음성은 순간 버퍼에만 있다가 3분 안에 덮어쓴다. 전사 글도 저장하지 않는다.
- 기록 거부(정책 규칙)는 그 묶음을 버리고 직전 저장 조각을 지운다. 화면의 멈춤 버튼이 가장 우선이다.
- 앱 로그에 전사 글을 남기지 않는다(우리 규약과 같다).
- 여행을 지우면 `talk_meta`와 음성 파일이 함께 지워진다(CASCADE + `MediaFileStore`).

## 8. 저장소·브랜치·협업

- 브랜치: `feat/mapbox-map`에서 **`feat/talk-rec-integration`**을 만든다. 데모가 돌면 PR로 팀과 검토한다.
- 우리 쪽 새 파일: `talkrec/` 모듈 전체, M1 자산(`model.int8.onnx` 14.7MB, `vocab.txt`, `threshold.json`, `rules.json`)은 `talkrec/src/main/assets/`. 모델 파일 크기 때문에 **Git LFS 여부를 팀과 정한다**.
- 팀원 파일에서 고칠 곳(최소): `settings.gradle.kts`(모듈 추가), `app/build.gradle.kts`(의존성), `AndroidManifest.xml`(서비스·`FOREGROUND_SERVICE_MICROPHONE`·`pleos.car.permission.STT_SERVICE`), `AppContainer`(연결), `DongbanDatabase`(버전 5, `talk_meta`), `Models.kt`(`TalkMeta`), `V3Mappers`(§6), S03 동의 화면 문구.
- 의존성 추가: `onnxruntime-android`, `androidx.work`, Pleos SpeechToText SDK, HTTP(팀 앱에 이미 있는 것을 쓰고, 없으면 `HttpURLConnection`).

## 9. 데모 시나리오

1. Pleos AVD에서 앱 실행 → 여행 설정 → S03에서 두 동의 체크 → 출발.
2. 대화 입력: **디버그 명령으로 PCM 파일을 마이크 대신 넣는다**(팀 앱의 `Debug*Receiver`처럼 adb 브로드캐스트). 입력은 지어낸 가족 대화를 음성 합성한 것(`test/stt/story-dialogues.md`류) 또는 팀원이 직접 말한 것. **YouTube 평가 음성은 데모에 쓰지 않는다.**
3. 대화가 끝나고 20초쯤 뒤 묶음이 닫히고, 남길 것이면 칩에 "저장됨"이 늘어난다. 없음 대화(내비 이야기, 용무)는 저장되지 않는 것을 함께 보여 준다.
4. 디버그 도착 명령(`DebugArrivalReceiver`) → S11 → S12에서 "글로 옮기는 중"이 끝남 → S14에서 이야기마다 제목·태그·재생.
5. 기록 거부 시연: "이건 녹음하지 마"가 든 대화를 넣어 직전 조각이 사라지는 것을 보여 준다.

## 10. 작업 단계 (안)

| ID | 작업 | 담당 | 끝났다고 보는 조건 |
| --- | --- | --- | --- |
| I1 | 브랜치·`:talkrec` 모듈 골격, 팀 앱 빌드 통과, 의존성 | Codex | 팀 앱이 그대로 빌드·실행된다 |
| I2 | `ConversationJudge`: WordPiece·정규화·onnxruntime, 문턱 0.957로 `threshold.json` 고침(T58a) | Codex (xhigh) | 골든 20개 토큰 일치, dev 77묶음 판정이 Python과 일치(id·판정만 비교) |
| I3 | 캡처 서비스 + Pleos STT 연속 + 순환 버퍼 + 묶음 조립 + 정책 규칙 | Codex (xhigh) | AVD에서 PCM 주입 20분 동안 누락·오류 0, 묶음 닫기가 걸린다 |
| I4 | `ClipWriter`·`TalkSink`·Room 5 마이그레이션 | Codex | 저장 조각이 S14에서 재생된다, 마이그레이션 테스트 |
| I5 | `SummaryWorker`·`SummaryProvider`(Gemini, agy 중계, Fake)·debug 설정 화면·지시문 `prompts/summary.md`·PC 중계 서버 | Claude(지시문, agy 실행) + Codex | Fake로 단위 테스트, **agy 중계로** 에뮬레이터에서 지어낸 대화 10개의 제목·태그를 사람이 확인. 그다음 실제 키로 같은 10개를 한 번 더 |
| I6 | 화면 연결(v3connect, S03 문구, 태그 칩) | Codex + 팀원 검토 | 데모 시나리오 1~5가 끊김 없이 돈다 |
| I7 | 데모 리허설과 측정 | Claude + 사람 | 증분 PSS·CPU, 묶음당 판정 지연, LLM 응답 시간 표 |

I2와 I3는 나란히 할 수 있다. I5는 I4의 테이블만 있으면 시작할 수 있다.

## 11. 위험과 확인할 것

| 위험 | 대응 |
| --- | --- |
| Pleos 줄 시각 때문에 침묵 닫기가 안 걸림 (알려진 결함) | 결과 수신 시각 + PCM 음량으로 침묵 판정 (I3) |
| 마이크 동시 사용 (버튼 녹음, Gleo) | 상시 캡처 중 버튼은 "이 순간 담기"로, 입력이 무음 처리되면 그 구간 버림(`test/stt/feature2.md`) |
| AAOS user 10에서 포그라운드 마이크 서비스·STT 권한 | I3 초기에 AVD에서 확인 |
| 120초를 넘는 연속 STT 미검증 | I3 완료 조건을 20분 주입으로 |
| 외부 LLM 키 노출 | debug 전용, 데모 뒤 키 교체. 배포는 서버 대리 호출 |
| 차 안 네트워크 없음 | WorkManager 재시도, 기존 대비 문구 |
| LLM이 대화에 없는 내용을 씀 | 지시문 + 사람 확인(I5). 데모 대화는 지어낸 것이라 위험이 낮다 |
| 운전자 주의 분산 규정 (주행 중 제목·태그 노출) | 한 줄·태그 2개·조작 없음·주의 끌기 없음, 설정에서 끌 수 있음 (§6). Pleos 규정 확인 |
| agy 중계는 PC가 있어야 동작 | debug 전용. 데모 본편은 실제 키(Gemini)로, 리허설에서 둘 다 확인 |
| APK 크기 증가 (모델 14.7MB + onnxruntime) | 측정해 보고 (I7) |

## 12. 결정

**정한 것 (2026-11-13)**

- API 키와 모델은 debug 설정 화면에서 정한다. 모델은 기본값을 미리 채워 둔다(§5).
- 구현 중 시험은 agy 중계로 한다. 실제 API 키는 그다음에 확인한다(§5).
- 제목·태그는 주행 중에도 보인다. 단, 한 줄·태그 2개·조작 없음, 설정에서 끌 수 있다(§6).
- 우리 코드는 별도 모듈 `:talkrec`로 둔다(§8).

**남은 것**

1. **외부 전송 동의 문구**: S03에 별도 체크를 넣는 것과 문구를 팀과 맞춘다.
2. **화제 태그 목록**: §5 초안 승인.
3. **모델 파일 관리**: Git LFS 사용 여부.
4. **데모 음성**: 음성 합성 대화만 쓸지, 팀원 목소리도 쓸지.
5. **Pleos 주행 중 화면 규정**: 주행 중 글 노출이 심사에서 허용되는지 확인.
