# I3 상시 대화 캡처 구현·검증 기록

2026-11-13. 작업 위치는 `C:\github\drive-diary-int`, 브랜치는 `feat/talk-rec-integration`이다. 이번 쓰기는 `talkrec/`와 이 worktree의 `.talkrec-notes/`에만 했다. `app/`, 루트 Gradle 파일, 버전 카탈로그는 수정하지 않았다. 시작할 때 이미 있던 앱/루트 변경은 그대로 남겼다. 커밋·push·브랜치 전환은 하지 않았다. 참고 저장소는 읽기 전용으로 사용했고 Python은 `-B`로 실행했다.

구현은 완료했으며 **Android 빌드와 I3 완료 기준인 AVD 20분 무누락·무오류 검증은 Claude가 수행해야 한다.** SDK 접근 시도나 Gradle 실행, curl 다운로드는 하지 않았다.

## 구현한 것

| 구성 | 구현 |
| --- | --- |
| `capture/PcmRingBuffer.kt` | 16kHz/mono/PCM16 LE, 기본 180,000ms = 5,760,000바이트. epoch ms × 16 샘플 위치로 주소를 계산한다. 오래된 샘플을 덮어쓰며 `append`/`slice`/`clear`는 동기화한다. 범위는 `[startMs,endMs)`이고 반환은 보관 중인 교집합의 독립 복사본이다. 겹침/역순 입력은 거부하고 빈 시간은 zero로 채운다. 실제 서비스는 입력 중단 시 버퍼 전체를 비워 중단 구간을 클립에 연결하지 않는다. |
| `AudioSource`, `PcmFrame` | 취소 가능한 cold `Flow`, 100ms = 1,600샘플 = 3,200바이트 프레임. 캡처 시작 epoch ms, 입력 가능 여부, 경로 ID를 함께 전달한다. |
| `MicAudioSource` | 프로브에서 검증한 `MediaRecorder.AudioSource.MIC`, 16kHz mono PCM16 `AudioRecord`. nonblocking read, IO 수집, recording callback과 route/`isClientSilenced` 확인. 경로/입력 상태 변경 때 부분 프레임을 버린다. API 28은 routedDevice를 사용하고 API 29+의 recording configuration 기능은 버전 검사로 감싼다. 취소 시 stop/release한다. |
| `PcmFileAudioSource` | WAV RIFF chunk를 스트리밍 탐색해 PCM format 1/mono/16kHz/16bit를 확인한다. raw는 같은 PCM16 LE 포맷이다. 파일 전체를 메모리에 읽지 않는다. monotonic clock으로 실시간 속도를 맞추고 캡처 시각은 epoch 기준으로 계산한다. 마지막 부분 프레임은 zero pad, 비반복 파일은 기본 25초 무음 tail 후 종료한다. loop와 취소를 지원한다. |
| `PleosSttClient` | SpeechToText 3.3.1, `Mode.ON_DEVICE`, 기본 `Locale.KOREA`, `initialize` → `setLocale` → `addListener` → 100ms `sendAudio`. 최종 결과만 Flow로 전달하며 콜백 진입 순간의 wall-clock receive time을 붙인다. 에러/종료 후 재연결한다. |
| `BlockAssembler` | Android API가 없는 단일 소유 상태 기계. 수신 순서대로 줄을 보관하고 침묵/90초 길이/`forceClose()`로 닫는다. 수동은 `source=MANUAL`. |
| `PolicyGate` | assets `policy/rules.json`을 읽는 T56 Python 게이트 이식. 원본 줄 인덱스, 받침 접기, Unicode code point 편집 거리 1, 인접 조각, 엄격한 과반, 앱 신호, 이유 ID와 소급 삭제 신호를 구현했다. |
| `CaptureProcessor`, `CaptureBudget` | 정책 → 모델 → 저장 경계. 직렬 sink 호출, PCM 정리, 수동 저장, 모델 실패 fallback, 이동 1시간당 AUTO 600,000ms 상한과 실패 시 예약 반환. |
| `ConversationCaptureService` | non-sticky microphone FGS. start/stop/pause/resume/mark와 소스 변경. ring + STT + assembler + policy + I2 judge + sink 연결. 한국어 알림과 중단/재개/종료 버튼, 캡처 중 partial wake lock. |
| `TalkRec`, `CaptureOptions`, `CaptureState` | `TalkRec.start(context, tripId: Long, options)`, `stop()`, `pause()`, `resume()`, `markMoment()`, 읽기 전용 `StateFlow`. Ready/On/Paused/Off, 누적 캡처 ms, 저장 수와 진단 카운터. 프로세스 종료 후 자동 복구하지 않는다. |
| `TalkSink`, `KeptBlock` | I4가 구현할 저장 인터페이스. ID/tripId/원래 줄/시작·끝/keepScore/kind/source/PCM slice 전달. 직전 저장 조각 삭제도 별도 suspend 메서드다. |
| `src/debug` | debug 전용 exported receiver, 앱 UI 없이 FGS를 시작하는 작은 투명 Activity, RAM 카운팅 sink. release에는 debug 컴포넌트가 없다. |

상세 API와 ADB 명령은 `talkrec/README.md`에도 기록했다. 기존 골격의 의미 없는 `TalkRec()` 생성 테스트는 실제 순환 버퍼·조립기·정책·저장 경계 테스트로 대체했다.

## 파이프라인과 스레드

```text
Mic / PCM 파일 (Dispatchers.IO)
  → bounded event channel (128)
  → Main actor: ring append + RMS + assembler + STT send
      ├→ Pleos SDK (Main에서 호출, SDK 내부 비동기 전송)
      │    → final receive epoch ms → Main actor의 assembler
      └→ closed block → post-roll 대기 → ring.slice(start-3000,end+2000)
          → bounded candidate channel (2)
          → IO 직렬 worker: PolicyGate → I2 ConversationJudge → TalkSink
          → 저장 성공/삭제 결과를 Main의 StateFlow에 반영
```

모델 로드·추론과 앱 저장 작업은 마이크 read와 Main actor를 막지 않는다. sink 호출은 30초 timeout을 두고 실패하면 캡처를 Paused로 바꾼다. 후보 큐가 가득 차도 조용히 누락시키지 않고 오류 후 일시정지한다. 후보 PCM은 성공·거절·예외·취소 모두 지운다. `TalkSink.onKeptBlock()`은 호출 중 PCM을 소비하거나 별도 복사본을 소유해야 한다. 반환 뒤 같은 배열을 쓰면 이미 zero로 지워져 있다. I4는 저장+메타데이터를 원자적으로 커밋하고 취소 시 미완료 파일/행을 남기지 않아야 한다.

pause/stop, 입력 무음 처리, 경로/캡처 시각 단절, 파일 소스 교체는 STT와 판정 worker를 취소하고 열린 줄·대기 후보·RAM PCM을 지운다. 이미 저장 완료한 조각은 유지한다. stop과 동의 철회는 열린 묶음을 강제 저장하지 않는다. 마이크가 다시 유효해지면 새 STT/문맥으로 이어 간다.

`KeptBlock.audio`는 실제 존재하는 `[start−3초,end+2초]` 구간이다. 시작 직후에는 pre-roll이 짧을 수 있다. 수동/90초 닫기는 뒤 2초가 캡처될 때까지 기다리며, 침묵 닫기는 이미 뒤쪽 PCM이 충분하다. 파일 끝에서는 재연결 대기 PCM이 전송된 뒤 종료하도록 기다린다. debug sink는 파일이나 DB를 만들지 않고 후보 수만 확인한다. 따라서 debug의 savedCount는 **I4의 영구 저장 완료 수를 검증한 값이 아니다.**

## 침묵과 절대 시각

SDK의 줄/EPD 시각은 침묵 증거로 사용하지 않는다. 마지막 확정 줄의 receive time을 `R`, 연속 저음량 PCM 시작을 `Q`, PCM 증거가 실제 존재하는 끝을 `A`, 현재 시각을 `N`이라 하면 다음 조건으로 닫는다.

```text
min(N,A) - max(R,Q) >= 20,000ms
```

PCM RMS는 signed PCM16 원시 단위이며 기본 `RMS < 140`만 저음량이다. 140 이상 프레임이 끼거나 PCM 시간에 구멍이 있으면 연속 저음량 시작을 다시 센다. 결과가 20초 안 왔어도 소리가 크면 침묵으로 닫지 않고, 저음량이 20초여도 최근 최종 결과가 왔으면 그 결과 이후 다시 20초를 기다린다. PCM이 아예 없는 시간은 저음량으로 가정하지 않는다. 독립 타이머만으로 침묵 닫기를 하지 않는다.

묶음 시작은 첫 확정 결과 앞의 에너지 onset을 사용하고, 매우 늦은 첫 결과는 90초 안으로 제한한다. 침묵 묶음의 끝은 마지막 결과 수신/마지막 고음량 PCM 끝 중 늦은 시각이다. 20초의 끝 무음 전체를 클립 본문으로 넣지 않는다. 길이 90초는 PCM 공급 중에도 강제 적용한다. 수동 닫기는 모델 keep 결정을 우회하지만 정책은 우회하지 않는다. 확정 줄이 없는 상태의 `markMoment()`는 빈 조각을 저장하지 않는다.

시각은 샘플의 캡처 epoch 기준이고 source pacing은 monotonic clock을 쓴다. Pleos 최종 결과 지연 때문에 줄의 정확한 발화 시작/끝을 얻었다고 보지는 않는다. 실차 소음 RMS가 140 이상으로 유지되면 침묵 닫기가 줄고 90초 닫기가 주로 걸릴 수 있으므로 AVD/실차에서 `silenceRms`를 확인해야 한다.

## Pleos 연결과 AAOS 처리

초기 25초 timeout, 에러 또는 recognition end 뒤 세션 재개방, 최대 300프레임(30초) 재연결 대기열을 둔다. 초기 준비가 끝나기 전에는 source를 시작하지 않는다. 연결 중에는 대기 PCM을 10ms 간격으로 전송해 따라잡는다. 연속 초기화 실패 5회 또는 대기열 초과는 오류 후 일시정지한다.

Batch 프로브에 `onEndedRecognition` 뒤에도 final이 올 수 있다는 관측이 있어, 종료 콜백 즉시 release하지 않고 1.5초 drain grace를 둔다. 그 사이 새 recognition start가 오면 살아 있는 세션의 새 발화를 끊지 않는다. 에러에는 재연결이 적용되지만 이미 SDK로 넘어간 음성의 무손실 재전송/중복 제거를 보장하는 ACK API는 없다. 이 지점은 실제 20분 AVD 확인이 필수다.

캐시된 3.3.1 AAR의 listener/constructor/method를 `javap`으로 대조했다. 추가로 `OnDeviceStt$send$1`이 전달받은 배열을 비동기로 참조하는 것을 확인했다. 따라서 SDK에는 독립 복사본을 주고 앱의 전송 대기 복사본만 지운다. `sendAudio()` 반환 직후 SDK가 참조하는 배열을 zero로 만들면 입력이 무음이 되는 결함을 피했다. 이 검사는 Android SDK 접근이나 Gradle 실행 없이 캐시 AAR에서 했다.

자기 입력의 silenced/route 상태만 관측하며 이를 Gleo 유휴 상태로 해석하지 않는다. 미디어 재생 여부로 외부 소리 우세를 추정하지 않는다. STT는 3.3.1 프로브와 같은 ON_DEVICE/locale/sendAudio 경로다.

## T56 이식과 패리티

`synth/policy_cases.py`의 모듈 설명, 직접 지은 표현 목록, `generate(seed=56)`, T56 문서를 확인했다. 실제 전사나 측정 프로필을 읽지 않는 합성 생성기다. 이 자료는 테스트 리소스로 내보내도 되는 지어낸 텍스트이며 실제 전사는 복사하지 않았다.

재현 명령:

```powershell
Set-Location C:\github\drive-diary-int
C:\github\drive-diary\talk-rec\model-create\.venv\Scripts\python.exe -X utf8 -B talkrec/tools/export-policy-cases.py --reference C:\github\drive-diary\talk-rec\model-create
```

출력은 `talkrec/src/test/resources/policy/cases.json`이다. expected는 생성기가 기대하는 이상적 정책 정답이 아니라 **그 시점의 Python `policy.gate.PolicyGate.gate()`를 실제 실행한 반환값**이다. 따라서 T56이 놓치는 사례도 그대로 일치해야 한다. 공통 normalize의 지어낸 Unicode 벡터도 복사했다.

| 검증 | 결과 |
| --- | --- |
| T56 seed 56 합성 사례 | 2,140/2,140 일치 |
| 빈 줄/인덱스/조각/Unicode 보조 평면/앱 신호 경계 | 52/52 일치 |
| 전체 block/drop_lines/retro_delete/reasons | **2,192/2,192, 불일치 0** |
| 공통 NFKC/소문자/L·N 정규화 벡터 | 60/60 일치 |
| rules SHA256 | `d1762637c08bd9d7059f29b7e6e00ab61582513a03b9ba5b24164a1d60d90e73` |
| cases.json SHA256 | `d46aaa42889b60fdc7629657c153e4cd231d3e4b313d5410ed458bb760281f4e` |

Python search/fullmatch와 Kotlin containsMatchIn/matches를 대응했고, 작은 후보 우선, 최대 3줄/128 code point, 빈 줄 건너뛰기 금지, 정확히 50%는 허용, 거부는 겹친 조각도 다시 검사하는 규칙을 유지했다. 모델로 보내는 원래 줄에는 이 NFKC/소문자화를 적용하지 않는다. I2의 원문 줄 잇기·토크나이저 계약을 그대로 사용한다.

`externalAudioDominant=true`만 외부 소리 전체 차단 근거다. `playbackActive=true` 단독으로 차단하지 않는다. 일반 명령/외부 문형 일부만 제거한 `allow` 결과도 게이트에서는 Python과 동일하다. 다만 서비스는 정확한 오디오 제외 경계가 없으므로 **dropLines가 있는 혼합 묶음 전체의 오디오 후보를 보수적으로 제외**한다. 자막만 지우고 명령 음성을 저장하지 않는다. 향후 줄별 오디오 경계가 신뢰 가능해지면 이 부분을 I4 이후 별도로 개선할 수 있다.

`retroDelete=true`는 현재 후보를 저장하지 않고 `TalkSink.deletePreviousSavedClip(tripId, refusalId)`를 호출한다. 실제 파일/Room 원자 삭제와 재실행 중복 방지는 I4 구현 책임이다. 패리티 100%는 T56의 실제 정책 탐지율이나 0.95 목표 달성을 의미하지 않는다. T56의 인식 단서 소실·새 표현·한 줄에 합쳐진 명령·일반 방송 발화 한계는 그대로 남는다. Unicode NFKC/소문자/범주 계산은 플랫폼 Unicode 구현도 사용하므로 AVD에서도 벡터/테스트를 확인해야 한다.

## 모델과 상한

I2 `ConversationJudge.fromAssets`와 원래 문턱 **0.9573729634284973**을 사용한다. 로드/추론 실패(링크·메모리 오류 포함) 시 정책 통과 묶음을 keep 후보로 보고 같은 AUTO 상한을 적용한다. fallback의 keepScore/kind는 null이다. I4는 이 값을 알 수 없음으로 처리해야 한다. 모델 실패로 정책을 건너뛰지 않는다.

실제 앞뒤 여유 포함 PCM 길이 합으로 이동 1시간당 AUTO 600,000ms를 예약한다. sink 거절/예외/취소면 예약을 반환한다. 모델이 정상인 AUTO에도 같은 상한을 적용한다. MANUAL은 사용자의 명시적 저장 요청이므로 자동 분량 상한은 우회하되 정책은 유지한다. 장부는 프로세스 공용 메모리라 pause/resume·입력 변경·동일 프로세스의 다음 여행에도 유지되지만 **프로세스 재시작 후 영속 상한은 I4에서 저장 이력으로 보완해야 한다.** 소급 삭제 후에도 해당 시간의 예약은 보수적으로 유지한다.

## Manifest와 Gradle

main manifest에서 다음을 앱으로 병합한다.

- `android.permission.RECORD_AUDIO`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_MICROPHONE`
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.WAKE_LOCK`
- 프로브와 정확히 같은 `pleos.car.permission.STT_SERVICE`
- `ConversationCaptureService`: `exported=false`, `foregroundServiceType=microphone`
- 선택적 microphone feature

debug manifest에만 receiver/투명 시작 Activity를 exported=true로 등록했다. 팀 `app/src/debug` 수신기 방식과 `docs/android-mvp.md`의 user 10 명령을 참고했다.

Gradle 변경은 **`talkrec/build.gradle.kts`에 unit-test 입력으로 `src/main/assets/policy`를 추가한 한 곳뿐**이다. 이유는 JVM 테스트가 파일 경로로 rules를 직접 읽으므로 규칙 변경 때 Gradle이 과거 패리티 결과를 재사용하지 않도록 하기 위해서다. 의존성은 추가하지 않았다. 기존 WorkManager의 compile 의존성에 coroutines-android가 있는 것을 캐시 POM에서 확인했다. 모델/기존 I2 설정은 바꾸지 않았다.

## 정확한 ADB 명령

아래는 SDK 접근이 가능한 Claude 셸에서 실행한다. 패키지는 `com.dongbantest.app`이다. 파일은 직접 만든 16kHz mono PCM16 입력을 사용한다.

```powershell
Set-Location C:\github\drive-diary-int
.\gradlew.bat :talkrec:testDebugUnitTest :app:installDebug
adb shell am force-stop --user 10 com.dongbantest.app
adb shell pm grant --user 10 com.dongbantest.app android.permission.RECORD_AUDIO
adb shell pm grant --user 10 com.dongbantest.app android.permission.POST_NOTIFICATIONS
adb shell dumpsys package com.dongbantest.app | Select-String 'RECORD_AUDIO|FOREGROUND_SERVICE|STT_SERVICE'

adb push .\x.wav /data/local/tmp/x.wav
adb shell chmod 644 /data/local/tmp/x.wav
# 앱 UI 없이 시작. Activity의 onResume에서 microphone FGS를 시작하고 곧 닫는다.
adb shell am start --user 10 -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureActivity --es path /data/local/tmp/x.wav --el tripId 0

# 이미 켜진 캡처의 입력 변경. --ez loop true는 필요할 때만 붙인다.
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_INJECT -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver --es path /data/local/tmp/x.wav --ez loop true
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_MARK -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_PAUSE -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_RESUME -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_STATE -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_MIC -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_STOP -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb logcat -d -s TalkRec:I
```

`DEBUG_START`는 같은 receiver/action extra로도 제공하지만, 백그라운드 broadcast에서 microphone FGS를 직접 시작하면 Android 14+ 시작 자격에 걸릴 수 있다. **UI 없는 시작 시험의 기본 명령은 위 `am start ...DebugCaptureActivity`다.** `path`를 생략하면 MIC를 사용한다. 마이크 permission은 파일 주입에도 FGS 유형 검사 때문에 미리 부여한다. 커스텀 STT 권한은 user 10의 설치된 패키지에서 실제 granted 여부를 확인해야 한다.

SELinux가 `/data/local/tmp` 읽기를 막으면 user 10 앱 전용 파일로 복사해 시작한다.

```powershell
adb shell "cat /data/local/tmp/x.wav | run-as com.dongbantest.app --user 10 sh -c 'cat > files/talkrec-debug.wav'"
adb shell am start --user 10 -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureActivity --es path /data/user/10/com.dongbantest.app/files/talkrec-debug.wav --el tripId 0
```

위 명령 자체도 이 샌드박스에서 AVD로 실행한 것은 아니다. user 0에만 설치/실행해 오래된 user 10 앱을 검사하는 실수를 피해야 한다. 비반복 입력은 파일 끝+25초 tail 뒤 Paused이고 resume은 파일 처음부터 다시 읽는다. 테스트를 다시 시작할 때는 STOP 완료 또는 force-stop 후 시작한다.

## JVM에서 실제 검증한 것

SDK 대신 기존 I2 방식으로 캐시 Kotlin 2.2.10 컴파일러를 Java로 직접 호출했다. JVM target 11, desktop ORT 1.23.2, 테스트 JSON 구현, 캐시 coroutines 1.8.0을 사용했다. 모든 클래스/임시 파일은 이 worktree의 `.talkrec-notes/`에 썼다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i3-jvm.ps1
```

최종 결과: **`OK (36 tests)`**, 12.513초. I3 신규 23개와 I2 회귀 13개다. private 경로는 기존 I2처럼 `TALKREC_PRIVATE_DIR=C:/github/drive-diary/talk-rec/model-create/.local` 시스템 속성으로 전달하며 원문을 복사하거나 출력하지 않는다.

- ring: 절대 시각/구간 교집합/덮어쓰기/3분 용량/zero gap/큰 append/겹침 거부/동시 slice.
- assembler: 두 침묵 증거, 최근 final 지연, 큰 PCM 에너지, PCM 누락, 90초 cap/줄 순서, 수동/빈 묶음/reset, 에너지 spike.
- policy: Python 2,192건의 전체 반환 계약과 SHA, normalize 벡터 60개.
- processor: MANUAL에도 거부/소급 삭제 우선, 낮은 점수의 AUTO 거절과 MANUAL 저장, 혼합 명령 음성 제외, 모델 오류 fallback/실제 PCM 상한, sink 거절 예약 반환/PCM 지우기, 이동 시간창.
- PCM 파일: 실제 WAV chunk/포맷 검사, 100ms 간격·타임스탬프·부분 프레임 padding·무음 tail, raw loop와 취소 후 파일 해제.

main/debug manifest XML 파싱과 소스 trailing whitespace 검사를 통과했다. 로그 호출을 확인해 원문/예외 메시지를 넣지 않는 것도 확인했다. **서비스·AndroidRecord·SDK 런타임을 JVM에서 실행하거나 Android 코드 전체를 컴파일했다고 주장하지 않는다.**

## Claude가 AVD에서 확인할 것 / 남은 일

1. SDK 접근 가능한 셸에서 `:talkrec:testDebugUnitTest`, `:app:assembleDebug`, manifest merge를 확인한다. Android 전용 파일의 API/컴파일 검증은 아직 남았다. release manifest에 debug receiver/Activity가 없는지도 확인한다.
2. user 10 앱 설치/RECORD_AUDIO/STT 권한/한국어 알림/마이크 FGS 유형/시작 Activity를 확인한다. 실제 MIC와 파일 입력 둘 다 확인한다. 다른 앱으로 전환하거나 화면을 꺼도 캡처가 이어지는지 본다.
3. **정확히 20분짜리 지어낸 PCM timeline**을 비반복 주입한다. 기본 tail 포함 최종 `frameCount=12,250`, `accumulatedCaptureMs=1,225,000`인지 확인한다. 입력 본문은 12,000프레임이다. STT sent/finals/errors, source unavailable/gaps, 각 발화의 최종 결과 수, 중복/누락을 기대 타임라인과 대조한다. 정상 시험에서 오류/단절/대기열 넘침 0이어야 한다. 전사 원문을 logcat에 추가하지 않는다.
4. 실제 무음 20초 뒤 `block closed reason=SILENCE`가 생기고, 90초 넘게 소리가 이어지는 입력에서는 `LENGTH`가 생기는지 확인한다. `stt final ... receivedMs`와 닫힘 로그를 대조한다. 줄 시각이 연속이어도 침묵 닫기가 걸려야 한다.
5. 종료 콜백 뒤 늦은 final, 새 발화 시작, 재초기화 중 PCM 큐를 관찰한다. 1.5초 grace/10ms backlog 전송이 해당 AVD에서 누락/중복 없이 동작하는지는 아직 측정하지 않았다. 짧은 주입 통과만으로 20분 완료로 보지 않는다.
6. pause/stop/마이크 silenced/route 전환/파일 교체가 열린 후보를 버리고 이전 문맥을 이어 저장하지 않는지 확인한다. 수동 버튼은 정책을 통과한 열린 묶음만 저장하고, 판정/상한과 무관한 source=MANUAL이 나오는지 본다. 확정 줄 이전 버튼의 무동작도 UI 연결 때 고려한다.
7. 거부 입력 때 현재 후보 저장 0, 이전 저장 수 감소/삭제 요청 1을 확인한다. I4 연결 뒤에는 실제 AAC/Room 삭제·중복 처리·취소 원자성을 확인한다. I3 debug sink로 영구 저장/재생을 확인할 수는 없다.
8. I4에서 PCM 소비/소유권, nullable fallback 점수/kind, 저장 이력 기반 영속 상한, 이전 조각 식별/삭제를 구현한다. 줄별 신뢰할 수 있는 오디오 경계가 없다는 제한과 앞뒤 여유의 주변 발화 포함 가능성도 저장 정책에서 검토한다. PSS/CPU와 묶음당 판정 지연도 실제 장치에서 측정한다.

## git status --short

최종 상태는 아래와 같다. `M app/...`, 루트 Gradle/카탈로그 변경은 시작 시 이미 있던 변경이다. `talkrec/` 전체가 아직 untracked라 내부 I2/I3 변경이 한 줄로 표시된다. `.talkrec-notes/`는 저장소의 로컬 ignore 대상이라 이 문서는 status에 나오지 않는다.

```text
 M app/build.gradle.kts
 M app/src/main/java/com/dongbantest/app/data/repository/RecordingConsentStore.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/DrivingParts.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/UiModels.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DiaryScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DrivingScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/HomeScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/SampleData.kt
 M app/src/main/java/com/dongbantest/app/ui/v3connect/V3NavHost.kt
 M build.gradle.kts
 M gradle/libs.versions.toml
 M settings.gradle.kts
?? talkrec/
```

## 샌드박스 밖 검증 (Claude, 2026-11-13)

- `:talkrec:testDebugUnitTest :app:assembleDebug :app:assembleRelease`: BUILD SUCCESSFUL. 테스트 10묶음 36개 통과, 건너뜀 0. release 병합 manifest에 debug 수신기·Activity 없음.
- 경고: `PleosSttClient`의 override 매개변수 이름이 SDK와 다르다는 Kotlin 경고 5건(동작 영향 없음).
- **AVD 짧은 주입 (Pleos_Connect_v2.1.0, user 10)**: Windows 음성 합성(Heami)으로 만든 지어낸 대화 4묶음, 175초 WAV(묶음 사이 25초 무음).
  - STT 최종 결과 22/22줄, errors=0, unavailable=0, gaps=0. STT 세션은 발화마다 끝나 17번 다시 열렸고 모두 정상.
  - 묶음 4개 모두 `reason=SILENCE`로 닫힘. 이야기 2개 저장, 길 안내 1개 저장 안 함, 기록 거부 묶음은 저장 안 하고 `retroDelete`로 직전 조각 삭제(saved 2 → 1).
- 20분 연속 주입 결과는 아래에 덧붙인다.

### 20분 연속 주입 (I3 완료 조건)

같은 175.1초 WAV를 반복 주입하며 1,200초 실행.

| 항목 | 결과 | 기대값 |
| --- | --- | --- |
| 캡처 프레임 | 12,000 (1,200,000ms) | 12,000 |
| STT 최종 줄 | 153 | 약 151 (6.85바퀴 × 22줄) |
| 닫힌 묶음 | 27, 모두 SILENCE | 27 (6바퀴 × 4 + 3) |
| 기록 거부 소급 삭제 | 6 | 6 |
| 최종 저장 수 | 8 | 8 (바퀴당 +2 −1, 마지막 바퀴 +2) |
| errors / unavailable / gaps / 대기열 | 0 / 0 / 0 / 0 | 0 |
| STT 세션 다시 열기 | 117회, 모두 정상 | |
| 앱 충돌 | 0 | 0 |

판정: **I3 완료 조건 충족**(누락·오류 0, 묶음 닫기 정상). 다만 단일 합성 음성·깨끗한 음질 기준이다. 실차 소음에서 `silenceRms`(140) 확인은 남는다.
