# 동반차 Talk Rec

여행 중 차 안의 가족 대화를 **저절로 듣고, 남길 만한 이야기만 골라 저장하고, 제목과 태그를 붙여 다이어리로 보여 주는** 동반차 대화 기록 기능을 만들고 검증하는 폴더다. 대화가 한동안 끊기면 화면으로 조용히 확인한 뒤 대화를 여는 질문도 건넨다.

이 폴더는 판정 모델, 정책 규칙, 지시문(프롬프트)을 만들고 측정하며 설계와 작업 기록을 남기는 곳이다. 앱에 실제로 들어가는 코드는 통합 저장소(`drive-diary-int`, 브랜치 `feat/talk-rec-integration`)의 Android 라이브러리 모듈 **`module/talkrec`**(Gradle `:talkrec`)과 팀 앱 `app/`에 있다. 여기서 만든 모델·규칙·지시문은 `module/talkrec/src/main/assets`로 옮긴다.

## 한눈에 보는 동작

```text
[여행 시작 · 녹음 동의]
        │
        ▼
상시 대화 캡처 (포그라운드 서비스, 마이크)
  AudioRecord 16kHz 모노 ─┬─► PCM 순환 버퍼 (최근 3분, RAM에만)
                          └─► Pleos STT (ON_DEVICE, ko-KR) ─► 확정 줄
        │
        ├─ 음성 명령이면 처리하고 기록하지 않음 ("동반차 녹음 멈춰", "동반차 다른 질문 해줘" …)
        ▼
① 묶음 조립 (침묵 20초 또는 90초) → ② 정책 규칙 → ③ 판별 모델 M1 (기기 안)
        │ 남길 것이면
        ▼
④ 버퍼에서 [시작−3초, 끝+2초] 잘라 음성 조각(AAC) + 전사 저장   ← 시간당 10분 상한
        │ 외부 AI 동의가 있으면
        ▼
⑤ 요약 (Cloudflare Workers AI · Gemma 4): 이야기 나누기 · 제목 · 분류 · 키워드
        ▼
여행 중 "지금까지 나눈 대화", 도착·다이어리 화면에 이야기 제목·태그·재생

(옆길) 대화가 오래 끊김 → 화면으로만 "혹시 다들 자고 있나요?" → 수락하면 질문을 TTS로 읽음
```

## 1. 상시 대화 녹음 (캡처)

- **언제 도나**: 녹음에 동의한 여행에서만 돈다. 여행이 끝나거나 동의를 철회하면 멈춘다. `ConversationCaptureService`가 마이크 포그라운드 서비스로 돌고, 화면 위쪽 녹음 칩에 상태(On·Paused·Off·Ready·Saved)와 누적 시간이 항상 보인다.
- **두 갈래 입력**: 같은 PCM을 순환 버퍼와 Pleos STT에 함께 보낸다. STT는 Pleos 음성 서비스가 앱 밖에서 돌려 앱 메모리·CPU 예산에 들지 않는다. 세션이 끊기면 다시 연다.
- **음성은 3분 뒤 사라진다**: 저장하지 않은 묶음의 음성은 RAM 버퍼에만 있다가 덮어써지고, 그 전사 글도 저장하지 않는다.
- **묶음 조립**: 확정 줄을 대화 묶음으로 모은다. 결과를 받은 시각과 PCM 음량으로 침묵을 재서 20초 침묵 또는 90초 길이에서 닫는다(SDK의 줄 시각은 빈틈없이 이어져 쓰지 않는다).
- **이야기 이어 붙이기**: 90초 상한으로 잘린 묶음이 곧바로 이어지면(2초 안) 같은 이야기(run)로 묶는다. 한 run은 최대 12조각이다.
- **멈춤·재개**: 정차 중 화면의 **녹음 멈추기 / 다시 녹음하기** 버튼, 또는 음성(§5). 멈추면 마이크를 놓는다.
- **이 순간 담기**: 녹음 중 화면 버튼. 지금 열린 묶음을 판정과 상관없이 저장한다(`MANUAL`). 정책 규칙은 그대로 거친다.

## 2. 판별 모델 (남길 것 / 없음)

- **정책 규칙** (`policy/rules.json`, T56): 판정 전에 먼저 거른다. "녹음하지 마", "방금 거 지워" 같은 **기록 거부**면 그 묶음을 버리고 직전에 저장한 조각까지 지운다. 공조·내비·볼륨·음악 같은 **차량 명령**과 외부 소리만 있는 묶음도 거른다.
- **M1 대화 모델**: KoELECTRA-small-v3를 미세조정한 분류기(약 1,400만 파라미터). ONNX int8 14.7MB를 onnxruntime-android로 묶음당 한 번 돌린다. 학습과 같은 정규화와 WordPiece 토크나이저를 쓴다.
  - 문턱 **0.957** (`m1/threshold.json`, T55 dev에서 남길 것 재현율 0.85 이상을 지키는 값).
  - 아이·가족 실제 dev 37묶음: 재현율 0.96, 없음 걸러 내기 0.58, 정확도 0.84, AUROC 0.79 (T55). int8 판정은 PC 원본과 77묶음 모두 같다 (T58).
  - 출력: keep 점수와 종류(`story` 이야기 / `moment` 순간).
- **학습 데이터**는 직접 만든 합성 대화를 Pleos STT 글 모양으로 다듬은 것이고, **평가 데이터**는 사람이 승인한 YouTube 차 안 영상을 Pleos 에뮬레이터로 전사한 것만 쓴다. 대상은 아이(아기~초등)와 가족이 함께 탄 대화다.
- **대비책**: 모델을 못 불러오면 정책 규칙을 통과한 묶음을 모두 후보로 보고 시간당 상한으로만 거른다.
- **소리 모델**(웃음·고함·울음)은 자리만 있고 보류 중이다.

자세한 설계는 [model-create/DESIGN.md](model-create/DESIGN.md), 라벨 기준은 [rubric.md](model-create/rubric.md), 작업 기록은 [docs/model-create](docs/model-create/)의 T번호 문서에 있다.

## 3. 요약 (제목 · 분류 · 키워드)

- **조건**: 녹음 동의와 **별도로** 외부 AI 전송에 동의한 여행만 보낸다. 동의하지 않으면 `SKIPPED`이고 제목은 "목소리 HH:mm"이다.
- **보내는 것**: 저장한 조각의 **전사 글만**. 음성, 이름, 위치, 목적지 이름은 보내지 않는다. 장소·때 태그는 기기 안에서 붙인다.
- **제공자**: Cloudflare Workers AI `@cf/google/gemma-4-26b-a4b-it` (OpenAI 호환 엔드포인트 + JSON 스키마, T63·I12). 테스트·시연용 Fake 제공자가 있다. 계정 ID·토큰은 debug 설정 화면에서 넣고 Android Keystore로 암호화해 둔다. 키는 저장소·로그에 남기지 않는다.
- **두 가지 요청**:
  - 한 조각: `prompts/summary.md` → `{"title", "category", "keywords", "kind"}` (제목 25자 이하, 분류는 고정 목록에서 1개, 키워드 0~2개).
  - 이어진 run: `prompts/story_split.md` → 묶음들을 이야기 단위로 다시 나누고(잡음뿐인 구간은 `none`) 이야기마다 제목·분류·키워드를 붙인다 (I11).
- **검증과 재시도**: 형식·길이·분류 목록을 검사해 어기면 다시 요청한다. `SummaryWorker`(WorkManager)가 네트워크가 있을 때 돌고 지수 백오프로 재시도한다.
- **화면**: 주행 중에도 제목은 한 줄, 태그 2개, 최근 3개까지만 조용히 보인다(설정 "주행 중 제목 보이기"). 재생·스크롤은 정차 중에만.

설계는 [app-integration/DESIGN.md](docs/app-integration/DESIGN.md), 지시문 원본은 [model-create/prompts](model-create/prompts/)에 있다.

## 4. 조용할 때 건네는 질문

- **감시**: `QuietWatcher` 상태기가 1초마다 조용함 시간만 본다(대화 글은 보지 않는다). 기본 8분 조용하면 확인을 띄운다. 여행 시작 10분 뒤부터, 목적지까지 15분 이상 남았을 때, 질문 사이 20분, 여행당 3번까지.
- **준비**: 확인 2분 전에 미리 질문을 만든다.
  - 맥락: 시간대, 대략의 지역(Pleos FusedLocation·NaviHelper), 목적지, 날씨(Open-Meteo, 좌표 소수 둘째 자리·30분 캐시). 좌표와 대화 글은 보내지 않는다.
  - 생성: Cloudflare Gemma 4 + `prompts/question.md`. 40자 이하, 물음표 하나, 금지어·중복 검사. 외부 AI 동의가 없거나 실패하면 앱 안 질문 목록을 쓴다.
  - 동승 관계는 지금은 **가족**으로 가정한다.
- **Q01 확인 (소리 없음)**: 자는 사람이 깨지 않게 화면으로만 "혹시 다들 자고 있나요?"를 10초 띄운다.
  - "아니, 안 자" 같은 반응 → 질문을 Pleos 기기 안 TTS(ON_DEVICE, ko-KR)로 읽고 Q02 카드를 보인다.
  - 반응이 없거나 거절 → 이번 여행에서는 **먼저 묻지 않는다**.
- **대답은 녹음하지 않는다**: 질문 TTS와 그 뒤 일정 시간(설정 "질문 뒤 녹음하지 않는 시간", 기본 90초)에 걸친 묶음은 저장·판정·요약하지 않는다. TTS 소리가 마이크로 들어오는 것도 이 구간으로 버린다 (QS0).
- **직접 요청**: 먼저 묻지 않기로 한 뒤에도 "동반차 다른 질문 해줘"로 언제든 질문을 받을 수 있다(§5). 직접 요청은 횟수 제한에 세지 않고 녹음 상태를 바꾸지 않는다.

기획은 [question-suggest/PLAN.md](docs/question-suggest/PLAN.md), 기술 설계와 단계(QS번호)는 [DESIGN.md](docs/question-suggest/DESIGN.md)에 있다.

## 5. 음성 명령과 Gleo

Gleo(Pleos 차량 음성 비서)는 스티어링 휠 버튼이나 "글레오"로 부르고, 그다음 **"동반차 ~해줘"**로 말한다. 동반차가 마이크를 쓰는 동안에는 Gleo가 시작하지 않으므로 녹음 상태에 따라 듣는 쪽이 다르다 ([Gleo 시험](docs/gleo/gleo-voice-test.md)).

| 명령 | 녹음 중 (동반차가 직접 알아들음) | 녹음 중이 아님 (Gleo를 부른 뒤) |
| --- | --- | --- |
| 동반차 녹음 멈춰 | 녹음을 멈춘다 | — |
| 동반차 녹음 다시 시작해 | — | Gleo 기능 `RESUME_TRIP_RECORDING` |
| 동반차 다른 질문 해줘 | 바로 질문을 읽는다 | Gleo 기능 `ASK_NEW_QUESTION` (녹음은 그대로) |
| 동반차 질문 그만해줘 | 이번 여행에서 먼저 묻지 않는다 | — |

- "동반차" 없이 "다른 질문 해줘"만 말하면 가족 대화로 보고 반응하지 않는다.
- 명령으로 인식한 줄은 대화로 저장하지 않는다.
- Gleo 기능은 앱의 `assets/schema.json`에 3개(`PAUSE_TRIP_RECORDING`, `RESUME_TRIP_RECORDING`, `ASK_NEW_QUESTION`)를 올린다.

## 동의와 개인정보

- 여행 시작 때 세 가지 중 고른다: (1) 여행 중 대화 기록, (2) 외부 AI로 제목·질문 만들기 — 기기 안 모델이 먼저 고른 이야기의 글만 나간다, (3) 녹음 없이 출발(질문만).
- 남기지 않은 음성과 전사는 저장하지 않는다. 앱 로그에도 전사 글을 남기지 않는다.
- 남긴 대화는 정차 후 언제든 지울 수 있고, 여행을 지우면 음성 파일과 요약이 함께 지워진다.
- 실제 영상의 음성과 전사문은 저장소와 앱 자산에 넣지 않는다. `.local/` 폴더에만 둔다.

## 디렉터리 구성

```text
talk-rec/
├── docs/
│   ├── v0.1.0.md            초기 STT 프로브 진행 이력
│   ├── model-create/        판정 모델 작업 기록 (T번호)
│   ├── app-integration/     팀 앱 연동 설계와 작업 기록 (I번호, R1 리허설)
│   ├── question-suggest/    조용할 때 건네는 질문 기획·설계 (QS번호)
│   ├── gleo/                Gleo 연동과 실제 음성 시험
│   └── future/              보류한 설계 (기기 안 요약 v4)
├── model-create/            판정 모델: 합성 대화, 학습, 실제 영상 평가, 정책 규칙, 지시문
├── test/stt/                독립 Android STT 검증 앱, 합성 음성 자산
└── dev-tools/               Windows 음성 다운로드·Whisper 전사 도구
```

`*/.local/`(음성 원본, 전사, 모델 캐시)과 `.venv/`, Android 빌드 결과는 Git에서 제외한다.

앱 쪽 모듈 구성(`module/talkrec/src/main/java/com/drivediary/talkrec/`):

| 패키지 | 하는 일 |
| --- | --- |
| `capture/` | 캡처 서비스, 마이크·파일 입력, Pleos STT 클라이언트, PCM 순환 버퍼, run 관리 |
| `block/` | 확정 줄 → 대화 묶음 |
| `policy/` | 정책 규칙 게이트 |
| `judge/` | M1 토크나이저와 ONNX 실행 |
| `clip/` | 버퍼 구간을 AAC 조각으로 저장 |
| `sink/` | 앱 저장소로 넘기는 인터페이스 |
| `summary/` | Cloudflare 호출, 요약·이야기 나누기, 검증, WorkManager |
| `ask/` | 조용함 상태기, 반응 분류, 맥락·날씨, 질문 생성·검증, TTS |

## 시험과 재현

- **통합 앱 (Pleos Connect AVD, 사용자 10)**: `drive-diary-int`에서 `.\gradlew.bat :app:installDebug`. 단위 테스트는 `:app:testDebugUnitTest`, `:talkrec:testDebugUnitTest`(Windows에서 ONNX 골든 테스트 1개는 DLL 문제로 실패하는 것이 알려져 있다).
- **디버그 화면**: debug 앱의 카탈로그에 대화 요약 설정, 인식 실험실(PC 마이크·음성 파일로 판정과 제목 시험), 질문 실험실(지금 위치로 질문 만들기, TTS 읽기)이 있다.
- **adb로 질문 흐름 흉내** (`-n com.dongbantest.app/.debug.DebugQuestionReceiver --user 10`, 액션 앞에 `com.dongbantest.app.debug.`):
  - `ASK_FORCE_QUIET` 조용함 건너뛰고 Q01, `ASK_REACTION --es kind accept|decline|other`, `ASK_FORCE_QUESTION`, `ASK_SHORT_POLICY --ez on true`
  - `ASK_VOICE --es cmd next|stop` 녹음 중 음성 명령, `ASK_GLEO` Gleo 질문 요청
- **실제 목소리 리허설**: [R1](docs/app-integration/R1-real-voice-rehearsal.md). 실제 대화로 Q01 반응과 Gleo 명령을 확인하는 일은 Devbox·실차에서 남아 있다(AVD는 마이크 입력 레벨이 낮다).
- **독립 STT 프로브**: `test/stt`를 Android Studio에서 따로 열어 빌드한다. 사용법은 [STT 프로브 README](test/stt/README.md).
- **모델 제작 환경**: [model-create README](model-create/README.md). 대화 샘플 전사는 [dev-tools](dev-tools/README.md).

## 문서 읽는 순서

1. 이 README로 전체 흐름을 잡는다.
2. [app-integration/DESIGN.md](docs/app-integration/DESIGN.md): 캡처·저장·요약을 팀 앱에 넣은 설계, 같은 폴더의 I번호 문서로 작업별 결과.
3. [model-create/DESIGN.md](model-create/DESIGN.md)와 [docs/model-create](docs/model-create/): 판정 모델 설계와 측정 (T55 선택, T58 탑재, T63 Cloudflare).
4. [question-suggest/PLAN.md](docs/question-suggest/PLAN.md) → [DESIGN.md](docs/question-suggest/DESIGN.md) → [QS0](docs/question-suggest/QS0-tts-check.md): 질문 기능.
5. [gleo/gleo-voice-test.md](docs/gleo/gleo-voice-test.md): Gleo 연동과 마이크 공유 제약.
6. [docs/v0.1.0.md](docs/v0.1.0.md), [test/stt](test/stt/README.md): 초기 STT 실험 이력.
