# 기능 2 · RAM 캡처와 한영 혼용 후보 선별

기준 커밋은 `4306726`이다. 10초 마이크 오프 대조군과 모의 Gleo 충돌 실험은 [이전 기록](feature2.md)에 있다. 이번에는 Gleo와의 동시 입력을 허용하고, 시스템이 우리 입력을 차단하거나 입력 경로를 바꿀 때 구간을 폐기하는 쪽으로 설계한다.

## 앱에서 구현할 흐름

1. 동승자 동의 후 사용자가 앱 화면에서 세션을 시작한다. `microphone` Foreground Service가 입력과 중지 버튼을 유지한다. 종료나 동의 철회 시 입력을 닫고 대기 중인 PCM, 전사 문맥, 예약 작업을 폐기한다. 앱을 다시 열었을 때 세션을 자동 복구하지 않는다. [Android 마이크 서비스 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions)
2. `AudioRecord` 입력을 16kHz, 16-bit, mono로 받는다. 20ms 프레임마다 발화 시작과 끝을 감지하고, 시작 앞 300ms를 RAM 링 버퍼에서 복구한다. 실험은 연속 80ms를 시작, 무음 1초를 끝으로 삼고 한 구간을 최대 12초로 자른다. 실차 소음에서 시작·끝 기준을 재검증한다. 실제 마이크 입력이 무음 처리되거나 입력 장치가 바뀌면 부분 구간과 문맥을 버리고 다음 발화 경계에서 이어 간다. 최대 12초 PCM은 384,000B다. 작업 버퍼, 전송 대기 한 건, 프리롤, 현재 프레임까지 앱이 보관하는 원본 PCM 상한은 약 0.78MB다. SDK 내부 복사본은 이 계산에 포함되지 않는다.
3. 구간 하나를 `Mode.ON_DEVICE`의 `sendAudio()`로 전송한다. 음성 구간 대기는 최대 한 건으로 제한하고 밀리면 오래된 부분 발화를 폐기한다. 전송용 PCM은 세션 완료·실패·취소 시 지운다. 기본 STT locale은 `ko-KR`다. 앱 전체가 영어인 세션은 `en-US`를 선택할 수 있다. 공개 문서는 한 세션에 locale 하나를 지정한다. 전사 중 영어 단어가 들릴 때마다 locale을 전환하거나 모든 구간을 두 번 전사하는 경로는 기본 설계에서 제외한다. [Pleos PCM 입력](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data) · [Pleos STT 언어 설정](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/set-locale)
4. 최종 전사만 분류기에 보낸다. 현재 발화와 최대 20초 안의 직전 1~3턴을 판정 입력으로 사용하고, 경로 변경·마이크 중단·긴 무음에서 문맥을 초기화한다. 말버릇, 내비·재생 명령, 광고·시스템 음성, 민감정보, 판단 불가를 구분할 수 있도록 라벨을 따로 수집한다. 저장 단계에서는 민감정보와 명령 제외 규칙을 적용하고, 이야기와 연관된 선택 구간의 텍스트만 후보로 기록한다. 모델 점수만으로 전체 문맥을 저장하지 않는다. 후보는 사용자가 확인하거나 삭제한다. 실제 음성·일반 전사·민감한 문맥은 앱 파일과 로그에 남기지 않는다. [Pleos 개인정보 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy)

발화 감지는 음성 처리를 줄이는 장치이고 마이크가 열려 있을 때 실행된다. 10초 마이크 오프 정책을 적용하면 그 구간의 대화는 입력되지 않는다. 오프 실험은 별도로 유지하고, 이번 재생은 연속 입력에서 메모리와 저장을 제한하는 경로를 검증한다.

### Gleo 동시 사용

Android 입력 정책에 따라 Assistant와 일반 앱이 입력을 함께 받을 수 있고, Assistant UI가 최상단이거나 입력 우선순위가 바뀌면 일반 앱이 무음을 받을 수 있다. 앱은 `AudioRecord.registerAudioRecordingCallback()`과 `isClientSilenced()` 및 경로 ID로 자기 입력의 상태를 본다. 무음 처리 중에는 해당 프레임을 VAD/STT로 보내지 않는다. 복구 시 권한과 세션이 유효하면 다음 완전한 발화부터 이어 간다. 동시에 받은 음성에서 Gleo 호출·명령을 저장하지 않는 정책은 별개다. 호출어·명령 패턴이 있는 구간을 제외하되, 그것으로 Gleo 점유 여부를 추정하지 않는다. [Android 마이크 공유 정책](https://developer.android.com/media/platform/sharing-audio-input) · [AAOS 마이크 입력](https://source.android.com/docs/automotive/audio/microphone-input)

Pleos Connect v2.1.0 AVD에서 Gleo 세션을 셸로 열어 `mShown=true`를 확인했다. `AudioRecord`는 30초 동안 약 16,000 frames/s, `silenced=false`, 경로 `15/56`을 유지했다. AVD 호스트에 물리 마이크가 없어 신호는 0이었고, 해당 호출로 Gleo의 새로운 마이크 점유가 시작됐다는 증거는 없었다. 실차에서 실제 호출어·음성 명령·응답 재생 시점의 입력과 라우팅 전환을 측정해야 한다.

## 음성 및 전사 실험

AVD의 실제 Pleos STT 3.3.1을 사용했다. 12개 합성 PCM에 발화 감지를 적용하고 한국어·영어 locale로 각각 한 번씩 재생했다. 한영 혼용 8개는 영어 TTS 조각을 이어 붙인 6개와 한글 발음 외래어 2개로 구성했다. 순수 한국어와 순수 영어는 각각 2개다. 최초 재생은 합성기 경계의 인위적 무음에서 전사가 여러 번 일찍 끝났다. 그 무음을 줄인 뒤 두 locale 모두 12개 최종 전사를 받았다.

| 음성 | `ko-KR`에서 관측 | `en-US`에서 관측 |
| --- | --- | --- |
| ‘제주도에서 Bluetooth가 끊겨서 아빠가 …’ | 이야기 문맥은 남고 `Bluetooth`는 누락 | `bluetooth`는 남고 한국어 문맥은 크게 손실 |
| ‘엄마가 첫 drive through에서 앞 차 사람 커피까지 …’ | ‘엄마가 드라이브로 사람 커피까지 사줘서’ | `drive through`는 남고 한국어 문맥은 크게 손실 |
| 한글 발음 ‘할머니랑 피크닉 갔을 때 …’ | ‘할머니랑 피크닉 갔을 때 비 오니까 우산 아래에서 김밥을 먹었지’ | 한국어 구간 오인식 |
| 순수 영어 이야기 ‘We lost the map at midnight …’ | 의미를 잃은 음역 | 전체 문장 전사 |

새 경량 분류기로 `ko-KR` 전사 12건 중 후보 4건을 앱 캐시에 기록했다. 기대 후보 6건 중 4건을 찾았고 일반 대화 6건은 모두 제외했다. 놓친 사례는 `drive through`가 포함된 문장과 순수 영어 문장이다. `en-US` 전사에 같은 경량 모델을 적용하면 후보 6건을 모두 놓쳤다. 디버그 로그의 전사는 합성 문장 전용이다. 결과 파일 `cache/ram-story-candidates-ko-KR.txt`에 후보 텍스트만 저장되는 것을 ADB로 확인했다.

## 20분 연속 합성 입력

Pleos Connect v2.1.0 AVD의 화면을 켜둔 상태에서 16kHz PCM을 20ms마다 공급했다. 위의 TTS 문장 12개를 반복해 발화 80개와 무음을 구성했다. 발화 시작 사이 간격은 8~40초였다. 입력이 이어지는 동안 같은 RAM 버퍼가 발화 끝을 감지하고, Pleos STT 3.3.1 `ON_DEVICE`의 `ko-KR` 세션을 발화마다 열고 닫았다. STT 작업은 처리 중 1건과 대기 1건으로 제한했다. 경량 모델이 선택한 텍스트만 실행 도중 후보 파일에 추가했다.

| 측정 항목 | AVD 결과 |
| --- | --- |
| 입력 시간과 프레임 | 20분 0.001초, 60,000개. 프레임 공급 지연 최대 124ms, 100ms 이상 2개 |
| 발화별 최종 전사 | 80/80. 누락·중복·STT 오류·대기열 폐기 0건. 5분마다 20건, 마지막 전사는 19분 32초에 처리 |
| PCM 제출 → 첫 최종 전사 | 중앙값 449ms, p95 809ms |
| 버퍼의 발화 종료 감지 → 후보 판정 및 저장 | 중앙값 1.35초, p95 1.72초. 추가 최종 전사를 모으는 900ms 포함 |
| 앱 메모리 | 약 30초마다 41개 표본. PSS 최대 54.8MiB, RSS 최대 134.9MiB. 5분 구간별 PSS 중앙값 53.8~54.2MiB |
| 후보 파일 | 기대 후보 40건 중 27건 저장, 비후보 40건 중 오탐 0건. 파일 27줄이 후보 로그와 순서까지 일치 |

13건의 후보 누락은 `drive through`가 든 문장의 반복 7건과 순수 영어 이야기의 반복 6건이었다. `Bluetooth`가 빠져도 이야기의 나머지 문맥으로 저장된 예가 있었다. 전사 80건은 최종 텍스트 반환 건수다. 문장 12종을 반복한 실험이므로 후보 성능과 메모리 결과는 해당 AVD 워크로드에 한정된다. 실제 마이크, 앱 화면을 벗어난 상태의 20분 지속, Gleo가 실제로 마이크를 점유하는 경우와 2시간 실행은 별도 검증 범위다.

앱에서 **Run 20-minute RAM story test**를 누르거나 현재 AVD 사용자로 `adb shell am start --user 10 -n com.drivediary.sttprobe/.ProbeActivity --es synthetic story-soak`를 실행한다. 셸에서 다시 실행할 때는 이전 Activity를 종료한 뒤 시작한다. `story-soak-smoke`는 같은 경로로 35초 동안 3개 발화를 테스트한다. `adb logcat -d -s DriveDiarySttProbe:I`의 `RAM soak` 로그와 `adb shell run-as com.drivediary.sttprobe --user 10 cat cache/ram-story-soak-1200s-candidates.txt`의 후보 텍스트를 비교한다. 이 파일은 새 실행을 시작할 때 초기화된다.

## 모델 비교와 선택

손으로 작성한 106개 샘플을 장면 단위로 학습 48, 임계값 선택 16, 평가 42로 나눴다. 같은 외래어를 추억과 길 안내·명령 양쪽에 넣었다. 경량 모델은 한국어·라틴 문자 2~4자 조합을 쓰는 로지스틱 회귀다. MiniLM은 공개 다국어 인코더를 고정하고 팀 분류기만 학습한 모델이다. 두 모델에 학습·개발 세트의 구두점 제거, 외래어 한글 표기, 단어 누락을 추가한 실험도 했다. 아래 숫자는 찾아낸 후보 수와 일반 대화를 후보로 잘못 저장한 수다. [MiniLM 모델 카드](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2)

| 모델 | 정답 텍스트 후보 / 19 | 정답 텍스트 오탐 / 23 | 한영 혼용 후보 / 10 | `ko-KR` 전사 후보 / 6 | `ko-KR` 전사 오탐 / 6 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 기존 4.7kB 모델 | 15 | 3 | 9 | 5 | 0 |
| 새 경량 모델 | 15 | 1 | 9 | 4 | 0 |
| 새 경량 + 전사 변형 학습 | 16 | 1 | 10 | 4 | 0 |
| MiniLM int8 + 선형 분류기 | 16 | 0 | 8 | 1 | 0 |
| MiniLM int8 + 전사 변형 학습 | 19 | 4 | 10 | 3 | 0 |

순수 영어 평가의 후보 3건은 두 경량 모델이 모두 놓쳤고 MiniLM 두 버전이 모두 찾았다. 영어 locale의 음성에서는 MiniLM 기본판만 비교하면 순수 영어 후보 1건을 찾았고 나머지 혼용·한국어 후보 5건을 놓쳤다. 별도 12개 텍스트로 직전 발화의 효과를 비교했을 때, 새 경량 + 전사 변형 모델은 후보 검출 1/6 → 4/6, 오탐 1/6 → 2/6이었다. MiniLM + 전사 변형 모델은 후보 검출 3/6 → 6/6, 오탐은 3/6 → 3/6이었다. 문맥을 넣은 상태에서 오탐을 다시 줄여야 한다. 이 실험은 모두 합성 데이터라 실제 차내 성능 수치로 사용할 수 없다.

**한국어 중심 공모전 프로토타입의 시작 모델은 전사 변형을 포함한 84,229B 경량 분류기다.** 기존 모델의 전사 후보 5건에는 의미를 잃은 순수 영어 음역도 한 건 들어 있다. 후보 라벨이 일치하는지와 저장된 전사가 이야기를 충실히 담는지 따로 평가한다. 기존 모델도 후속 실차 비교에 포함하고, MiniLM은 영어 전사와 문맥 비교용으로 유지한다. 한국어 STT의 외래어가 누락되면 모델은 남은 문맥으로 판정하고 저장 텍스트는 전사를 그대로 사용한다.

학습은 사용자 차량 외부의 개발 환경에서 수행한다. 동의를 받아 수집한 한국어, 한영 혼용, 영어와 실제 Pleos 전사를 장면·화자·여정 단위로 나눠 원문과 전사본을 함께 라벨링한다. 같은 원문의 변형이 학습과 평가로 나뉘지 않게 한다. 정답 텍스트 분류, STT 외래어 보존율, 실제 오디오에서 최종 후보 검출률을 별개로 집계한다. 초기 제품 지표는 동의 세션에서 **시간당 잘못 저장한 후보**, 후보 정밀도와 재현율, 언어별 결과다. 경량 모델을 먼저 다시 학습하고, 의미 문맥에 한계가 남으면 고정 인코더 분류기 또는 소형 학생 모델로 지식을 이전한 뒤 실차 오탐과 비용을 비교한다. 이 실험에서 MiniLM 전체 파라미터를 파인튜닝하지 않았다.

## 메모리와 CPU

Pleos 개발자 문서는 앱 RAM 400MB 이하와 플랫폼 성능 기준 CPU 3% 이하를 권장한다. 테스트 앱의 PSS와 RSS를 따로 기록한다. CPU 수치는 AVD 프로세스의 CPU 시간을 한 코어 기준으로 나눈 값이며 Pleos 심사 측정법과의 동등성을 의미하지 않는다. [Pleos 성능 기준](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/app-runtime-environment-resources-guide/app-performance-platform-identify)

| AVD 시나리오 | 앱 PSS | 앱 RSS | 부가 측정 |
| --- | ---: | ---: | --- |
| 경량 모델과 RAM→STT 재생 | 약 54MiB 최대 표본 | 약 134MiB 최대 표본 | 앱 전체, 2초 간격 스냅샷. Pleos의 시스템 STT 프로세스는 별도 |
| MiniLM int8 x86-64 비교 | 약 210MiB | 약 288MiB | 로드 362ms. 168회 p50 5.03ms, p95 6.71ms. 초당 1회씩 30초에서 프로세스 CPU 1.34% |

MiniLM 측정에는 약 113MiB ONNX 모델과 Android ONNX Runtime이 포함됐다. 호스트에서 미리 토큰화한 입력을 사용했으므로 Android 토크나이저, 실차 ARM64, 기존 동반차 UI와 STT 동시 부하 비용은 별도로 측정해야 한다. 기본 빌드는 84kB 가중치를 읽는 Java 분류기로 실행된다. 상한을 둔 버퍼를 재사용하고, VAD 이후에만 STT를 시작하며, 최종 전사에서만 모델을 호출한다. 출시 후보는 화면 켜짐과 백그라운드에서 최소 30분씩 내비·음악·Gleo를 함께 실행해 앱 CPU, PSS/RSS 최고치와 STT 지연을 측정한다.

## 재현

1. Windows PowerShell에서 `./tools/make-story-fixtures.ps1 -Output src/main/assets/story-fixtures`로 합성 음성을 생성한다. 한국어 Heami Desktop과 영어 Zira Desktop을 사용한다. 생성물은 Git에서 제외된다.
2. `./gradlew.bat assembleDebug`로 빌드해 Pleos AVD의 현재 Android 사용자에게 설치한다. 앱에서 **Replay RAM-only Korean and mixed-language stories**를 누르거나, 셸에서 현재 사용자 번호를 넣어 `adb shell am start --user 10 -n com.drivediary.sttprobe/.ProbeActivity --es synthetic story-ram-ko`를 실행한다. 영어 대조는 기존 Activity를 닫은 다음 `story-ram-en`을 사용한다.
3. `adb logcat -d -s DriveDiarySttProbe:I`에서 `RAM story STT` 및 완료 줄을 확인한다. 후보 파일은 `adb shell run-as com.drivediary.sttprobe --user 10 cat cache/ram-story-candidates-ko-KR.txt`로 읽는다. `--user 10`은 AVD의 현재 사용자에 맞춘다.
4. Python 3에서 NumPy와 선택적으로 `onnxruntime==1.23.2`, `tokenizers==0.22.2`를 설치한다. `python tools/benchmark-story-models.py --augment-asr --context --output bench`로 경량 모델을 재현한다. 선택적으로 `--encoder model_quint8_avx2.onnx --tokenizer tokenizer.json`을 붙이면 MiniLM과 함께 비교한다. `--stt transcripts.tsv`는 합성 문장 ID와 AVD 최종 전사 두 열을 받는다. [모델 리비전 e8f8c21](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/tree/e8f8c211226b894fcb81acc59f3b34ba3efd5f42)
5. Android MiniLM 비교는 `./gradlew.bat -PencoderBench assembleDebug`로 빌드한다. 같은 리비전의 `onnx/model_quint8_avx2.onnx`를 앱의 `files/encoder.onnx`로, 4단계의 `encoder-bench.tsv`를 `files/encoder-bench.tsv`로 넣는다. `run-as`로 앱 전용 디렉터리에 복사하고 `--es synthetic encoder-bench`로 시작한다. `StoryEncoderBench` 로그를 보고 `adb shell dumpsys meminfo com.drivediary.sttprobe`를 측정한다. 위 AVD 지연은 `--augment-asr` 없이 생성한 인코더 분류기의 값이다. 모델은 별도 다운로드이며 소스에 포함되지 않는다.

`tools/RamSpeechBufferCheck.java`는 `RamSpeechBuffer.java`와 함께 JDK로 컴파일하면 실행할 수 있다. 무음, 경로 중단 시 폐기, 발화 종료 대기, 버퍼 지우기와 12초 상한을 검증한다.

현재 앱은 RAM 캡처, 실제 온디바이스 STT, 경량 후보 저장을 20분 합성 재생에서 연결했다. 실제 마이크부터 저장까지의 백그라운드 통합, 문맥 결합·민감정보 제외 정책, 내비·음악·주행 소음·Gleo 동시 사용, 실차 SDK 권한 및 마켓 배포는 다음 검증 범위다.
