---
layout: default
title: 프로젝트 구조
---

[← README로 돌아가기](https://github.com/hyundai-app-devchallenge/drive-diary#3-프로젝트-구조)

# 3. 프로젝트 구조

```text
drive-diary/
├── app/                      Android 앱 (Kotlin, Jetpack Compose)
│   └── src/
│       ├── main/java/com/dongbantest/app/
│       │   ├── ui/v3/            확정 디자인 화면(홈, 여행, 기록, 다이어리)과 디자인 토큰
│       │   ├── ui/v3connect/     화면과 데이터 연결(NavHost, 상태 변환)
│       │   ├── data/             Room DB, 저장소, 대화 기록 연결(talk), 질문(ask), 데모 데이터(demo)
│       │   ├── vehicle/          Pleos Vehicle SDK, 기어·좌석·문 신호, 주행 제한 감시
│       │   ├── location/         FusedLocation·GPS 위치 기록
│       │   ├── navi/             NaviHelper 목적지·도착 판단
│       │   └── gleo/             Gleo 앱 스키마 수신기, 음성 명령
│       ├── main/assets/          Gleo 앱 스키마, 데모 여행(GPX·음성·사진)
│       └── debug/                디버그 전용 앱 "동반차 디버그"
├── module/talkrec/           대화 기록 라이브러리 (:talkrec, 앱에 함께 빌드)
├── talk-rec/                 판정 모델 제작·STT 실험·설계 문서 (빌드에 들어가지 않음)
├── tools/demo/               데모 음성 자르기·넣기 스크립트
└── docs/                     실행·검증 안내, 화면 연동 문서, README 이미지
```

이름이 비슷한 두 폴더는 역할이 다릅니다.

| 폴더 | 내용 |
|---|---|
| `module/talkrec` | 앱에 들어가는 대화 기록 코드입니다. Gradle에서 `:talkrec`으로 등록되고 `app`이 사용합니다. |
| `talk-rec` | 그 코드에 들어갈 판정 모델, 규칙, 프롬프트를 만들고 측정하는 Python 실험 공간입니다. 결과물(`m1/`, `policy/rules.json`, `prompts/`)만 `module/talkrec/src/main/assets`로 옮깁니다. |

대화 기록 모듈(`:talkrec`) 안은 다음과 같습니다.

| 패키지 | 하는 일 |
|---|---|
| `TalkRec.kt` | 앱이 부르는 입구: `start`, `pause`, `resume`, `stop`, 상태 `StateFlow` |
| `capture/` | 마이크 포그라운드 서비스, Pleos STT 연속 전사, 최근 3분 음성 버퍼 |
| `block/` | 전사 줄을 대화 묶음으로 나눔 (침묵 20초 또는 길이 90초) |
| `policy/` | 기록 거부, 음성 명령 같은 규칙 (`rules.json`) |
| `judge/` | 기기 안 판정 모델(M1, ONNX)로 남길 대화인지 판단 |
| `clip/` | 남길 묶음을 음성 조각(`.m4a`)으로 저장 |
| `summary/` | 동의한 경우 외부 AI로 제목·태그를 붙이고 이어지는 조각을 이야기로 묶음 |
| `ask/` | 조용할 때 건네는 질문: 조용함 판단, 반응 분류, 질문 생성, Pleos TTS |

자세한 내용은 [module/talkrec/README.md](https://github.com/hyundai-app-devchallenge/drive-diary/blob/main/module/talkrec/README.md)와 [talk-rec/README.md](https://github.com/hyundai-app-devchallenge/drive-diary/blob/main/talk-rec/README.md)에 있습니다.

---

← 이전: [기능](features.md) | 다음: [기술적 구현](tech.md) →
