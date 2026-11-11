# 동반차 Talk Rec

동반차의 질문 답변 녹음과 추억 후보 선별을 검증하는 공간이다. Pleos AVD의 Android 프로브는 **마이크 또는 합성 입력 → Pleos STT → 후보 판정**을 시험한다. Windows 도구는 대화 샘플을 Whisper로 전사해 검수할 때 쓴다. 차량 프로브의 전사는 Pleos SDK가 맡는다.

## 디렉터리 구성

```text
talk-rec/
├── docs/             버전별 작업과 검증 결과
│   └── v0.1.0.md
├── test/
│   └── stt/          독립 Android 검증 앱, 합성 음성 자산, 모델 비교 도구
└── dev-tools/        Windows 음성 다운로드·Whisper 전사 도구
```

`dev-tools/.local/`은 음성 원본, 전사 결과, 모델 캐시를 보관하는 로컬 디렉터리다. `dev-tools/.venv/`는 Python 환경이다. 두 디렉터리와 Android 빌드·생성 음성은 Git에서 제외한다.

## 테스트 재현

### Pleos AVD

Android Studio에서 `talk-rec/test/stt`를 별도 프로젝트로 연다. 저장소 루트의 PowerShell에서 빌드할 수도 있다.

```powershell
Set-Location './talk-rec/test/stt'
.\gradlew.bat assembleDebug
```

Pleos Connect v2.1.0 AVD에 `Drive Diary STT Probe`를 설치한 뒤 목적에 맞는 버튼을 선택한다.

| 목적 | 앱 버튼과 확인 방법 |
| --- | --- |
| 마이크 없이 질문·답변 검증 | **Record virtual answer and transcribe**로 가상 답변의 저장과 전사를 확인한다. |
| 합성 입력과 화면 전환 | **Run 120-second background STT**를 시작하고 차량 설정으로 이동한 뒤에도 최종 전사가 들어오는지 확인한다. |
| 긴 RAM 처리와 후보 저장 | **Run 20-minute RAM story test**로 합성 PCM을 재생한다. 합성 자산 생성과 결과 확인 명령은 [RAM 실험의 재현 절차](test/stt/feature2-mixed.md#재현)를 따른다. |
| 호스트 마이크 입력 | 에뮬레이터의 **Extended Controls → Microphone**에서 호스트 마이크를 켜고, 앱 화면에서 **Test live mic story for 60 seconds** 또는 **Test live mic story for 20 minutes**를 시작한다. 화면 전환 뒤 결과를 확인하고 필요하면 앱이나 알림에서 중지한다. |

Logcat 태그는 `DriveDiarySttProbe`다. AVD에서 완료한 20분 시험은 합성 PCM 직접 입력이었고, 물리 마이크를 쓴 백그라운드 시험은 TTS를 스피커로 재생해 약 11분까지 관측했다. 조건과 결과는 [v0.1.0 기록](docs/v0.1.0.md)에 정리했다.

### Windows에서 대화 샘플 검수

[dev-tools 사용법](dev-tools/README.md)을 따라 `setup.ps1`로 환경을 구성한다. `yt-dlp`로 확보한 음성이나 로컬 원본을 `transcribe.py`에 넘기면 원본 시간 기준 JSON과 SRT를 얻을 수 있다. 자동자막과 결과를 비교하고 핵심 사건, 화자 전환, 외래어가 남았는지 원음으로 확인한다.

## 문서 읽는 순서

1. [docs/v0.1.0.md](docs/v0.1.0.md)에서 전체 진행 이력, 검증 조건, 남은 항목을 본다. `docs/`는 버전별 요약을 찾는 곳이다.
2. [STT 프로브 README](test/stt/README.md)에서 Pleos AVD 설정, 질문 답변, 초기 연속 전사 재현 방법을 본다.
3. [마이크 갭과 Gleo](test/stt/feature2.md)에서 초기 대조 실험을, [RAM 캡처와 한영 혼용](test/stt/feature2-mixed.md)에서 통합 처리와 모델 비교를 본다.
4. [차량 대화 평가 시나리오](test/stt/story-dialogues.md)에서 후보와 제외를 비교할 문장을 보고, [dev-tools 사용법](dev-tools/README.md)에서 실제 대화 샘플 전사 절차를 찾는다.
