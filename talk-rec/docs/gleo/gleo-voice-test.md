# Gleo 녹음 제어 연동과 실제 음성 테스트

2026-11-14 (KST) · Pleos Connect Emulator 2.1.0 · Android 14/API 34 · AVD `emulator-5554`, Android 사용자 10 · Gleo `ai.umos.ambientai` 4.2.0.15 · 동반차 `com.dongbantest.app` 1.0

## 연동 방식

1. Gleo가 호출어 **“글레오”**를 감지한 뒤 사용자의 명령을 인식한다. “글레오, 동반차 녹음 멈춰”와 “글레오, 동반차 녹음 다시 시작해”처럼 앱 이름을 포함하면 어느 앱을 제어하는지 명확하다. 이 AVD의 Gleo 앱 도움말은 스티어링 휠의 음성인식 버튼으로도 대화를 시작할 수 있다고 안내한다. [현대차그룹 Gleo 호출 시연](https://www.hyundaimotorgroup.com/ko/story/CONT0000000000173173)
2. Gleo가 앱 스키마의 `PAUSE_TRIP_RECORDING` 또는 `RESUME_TRIP_RECORDING`을 선택하면 동반차에 패키지 지정 브로드캐스트를 보낸다. `assets/schema.json`의 `component: "service"`가 이 방식을 지정한다. 스키마의 `invokeWord` 예시는 앱 이름이며, 동반차 스키마 값은 `동반차`다. [Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts), [App Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability)
3. 동반차의 `GleoRecordingReceiver`가 진행 중인 여행과 녹음 동의를 확인하고 캡처 상태를 변경한다. 재개는 일시정지된 기존 마이크 세션에서 가능하다. 세션이 종료됐으면 정차 후 화면에서 다시 시작해야 한다. Android 14의 백그라운드 마이크 포그라운드 서비스 시작 제한을 고려한 동작이다. [Android 포그라운드 서비스 제한](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
4. 앱은 `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE`로 결과를 보낸다. 인텐트의 `packageName`과 `response`에 앱 식별자와 `functionName`, `status`, `message` JSON을 담는다. 앱의 확인 제한은 6초이며, Pleos 가이드는 Gleo Action 제한을 7초로 명시한다. `mode` 기본값은 `voice`다. [인텐트와 콜백 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples), [Callback Schema](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)

앱 구현은 `drive-diary-int`의 `app/src/main/assets/schema.json`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/dongbantest/app/gleo/GleoRecordingReceiver.kt`에 있다. 수신기의 `ai.umos.gleo.permission.APP_ACTION`은 이 AVD의 Gleo 패키지가 정의하고 보유한 서명 권한이다. 이전 시험에서 일반 ADB shell의 인텐트는 Android가 권한 부족으로 차단했다. 동반차의 ON_DEVICE STT는 여행 대화 전사에 사용한다. [Pleos SpeechToText 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)

## 녹음 중 Gleo가 마이크를 쓸 수 있나

**이 AVD에서는 두 입력 스트림이 동시에 열리는 것을 확인했다.** 동반차의 `MicAudioSource`는 `AudioRecord`의 `MediaRecorder.AudioSource.MIC`를 사용한다. Android는 권한 있는 시스템 어시스턴트와 일반 앱이 이와 같은 입력 소스를 공유할 수 있도록 한다. 어시스턴트 UI가 전면에 올라오면 앱 쪽 입력이 일시적으로 무음이 될 수 있다. 앱은 `isClientSilenced`와 경로 변경을 확인해 해당 입력을 사용 불가로 처리한다. 마이크 증폭은 앱이 받은 PCM에만 적용된다. [Android 오디오 입력 공유](https://developer.android.com/media/platform/sharing-audio-input#assistant_app)

| 관찰 | 동반차 | Gleo |
| --- | --- | --- |
| 여행 화면에서 녹음 중 | `MIC`, 활성, `not silenced` | `HOTWORD`, 활성, `not silenced` |
| 여행 화면에서 녹음 일시정지 | `RECORD_AUDIO` 사용 종료, `MIC` 스트림 해제 | `RECORD_AUDIO_HOTWORD` 계속 실행, `HOTWORD` 스트림 활성 |

두 입력의 Android 장치 표시는 `AUDIO_DEVICE_IN_BUILTIN_MIC`였다. `dumpsys voiceinteraction`에는 `Hotword detection service=null`이 표시됐고 SoundTrigger 등록 목록은 비어 있었다. Gleo 로그에는 자체 `[WWD][GLEO]initializeWakeUpWordDetector`, `ko-KR`, `startRecording audioRecord - mic num:4`가 남았다. `dumpsys media.audio_flinger`에서 `HOTWORD_TAP`의 읽은 프레임 수가 증가했고, 재부팅 후 합성 음성을 스피커로 두 번 재생하자 최근 입력 레벨 최대값이 기준 약 −96.8 dB에서 −42.8 dB로 올라갔다. 스피커의 신호가 Gleo의 핫워드 입력 경로에 도달한 관찰이다. 이 레벨과 채널 구성이 감지기의 요구를 만족하는지, 감지기가 호출어를 받아들이지 않은 이유는 확인하지 못했다.

## 실제 테스트 결과

| 조건 | 입력 | 관찰 |
| --- | --- | --- |
| 앱 녹음 중, 재부팅 전 | 사용자가 PC 마이크에 “글레오, 동반차 녹음 멈춰”와 “글레오, 지금 몇 시야?” 발화 | Gleo UI·응답 없음. 앱 녹음 유지. 새 Gleo Action 없음. |
| 앱 녹음 일시정지, 재부팅 전 | 사용자가 “글레오” 및 일반 질문 발화 | 앱의 `MIC` 해제 확인. Gleo UI·응답 없음. |
| 앱 녹음 일시정지, 재부팅 전 | 에뮬레이터 Extended Controls의 `Voice Assist` | 관찰한 화면과 ADB에서 새 Gleo 대화 세션이나 일반 마이크 시작을 확인하지 못함. |
| 앱 마이크 꺼짐, 재부팅 후 | PC 스피커로 한국어 합성음 “글레오. 지금 몇 시야?”와 분리 발화 재생 | Gleo `HOTWORD` 스트림은 활성. 듣기 세션, 일반 마이크 시작, 응답이나 앱 Action을 확인하지 못함. |
| 앱 마이크 꺼짐, 재부팅 후 | `Voice Assist` 버튼, Android `ASSIST` 인텐트, `cmd voiceinteraction show` | 버튼과 인텐트로 듣기 화면·일반 마이크 시작 없음. `show`는 `mShown=true`인 Android 세션을 만들었으나 Gleo 및 Pleos 음성 서비스의 일반 `RECORD_AUDIO`는 시작되지 않음. |
| 앱 마이크 꺼짐, 재부팅 후 | 차량 서비스 `inject-key 231` (`KEYCODE_VOICE_ASSIST`) 직후 “지금 몇 시야?” 스피커 재생, 2회 | Gleo가 `MICOM_PTT_PRESS:SHORT`를 받고 듣기 상태에 진입했다. 두 번 모두 STT 스트림을 열고 `sendAudioBufferToServer`를 기록한 뒤 약 5초 후 `RESULT_TIMEOUT`으로 종료했다. 인식 결과와 응답은 확인하지 못했다. [AOSP 차량 음성 버튼 테스트](https://android.googlesource.com/platform/packages/services/Car/+/6db87be1f7) |

차량 설정의 `‘글레오’라고 불러 대화 시작`과 에뮬레이터의 `Enable Host Microphone Access`, `Virtual microphone attached`는 켜져 있었다. 재부팅 전에는 `Active default network: none`, Wi‑Fi 미연결, Gleo 도움말 웹 연결 오류가 관찰됐다. Android 게스트를 정상 재부팅하자 가상 유선 네트워크가 기본으로 잡히고 `VALIDATED`가 됐다. Gleo 앱의 도움말 본문도 정상 로드됐다. 재부팅 후에도 호출어가 듣기 세션으로 이어지지 않았다. Gleo 초기화 로그는 `sttMode:SERVER, ttsMode:SERVER`를 표시했다. [Pleos 에뮬레이터 마이크 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator), [Android Extended Controls](https://developer.android.com/studio/run/emulator-extended-controls#microphone)

재부팅 로그에서 `Loaded schema.json from com.dongbantest.app`과 `ToolRepository | setAppCapability | converted | package=com.dongbantest.app functionCount=2`가 확인됐다. 두 함수 이름도 로그에 나왔다. `cmd package query-receivers`에서 두 Action이 실제 앱 수신기로 해석되고, Gleo의 `APP_ACTION_RESPONSE` 동적 수신기도 등록됐다. 이 AVD에서 로컬 스키마 로드와 함수 등록까지 확인했다. 출시용 스키마 심사·승인은 별도 절차다. [Gleo 스키마 전달 가이드](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)

실제 테스트에서는 Gleo가 동반차로 보낸 Action과 콜백을 받아 생성한 최종 응답을 관찰하지 못했다. 앱 녹음을 해제한 상태에서도 호출어가 열리지 않았으므로 이번 실패를 동반차의 마이크 점유로 설명하기 어렵다. 차량 음성 버튼 경로에서는 `requestWithoutRecording()`과 `onReadyToSendBuffer` 뒤 오디오 버퍼 전송이 기록됐다. 이 경로는 이미 열린 입력을 사용하므로 일반 `RECORD_AUDIO` AppOps가 시작되지 않아도 듣기 상태에 들어갈 수 있다.

## 디버그 앱에서 차량 음성 버튼 사용

동반차 디버그 상단의 **Gleo 차량 음성 버튼**은 Pleos AVD에서 스티어링 휠의 짧은 음성 버튼 입력을 보낸다. AVD에서 동반차 디버그 APK를 설치하고, PC에서 `drive-diary-int` 루트를 열어 아래 스크립트를 실행한 상태로 버튼을 누른다. Android의 차량 입력에는 권한이 필요하므로 PC의 ADB가 `cmd car_service inject-key 231`을 실행한다. ADB가 PATH에 없다면 `--adb`에 platform-tools의 adb 경로를 넘긴다.

2026-11-14 수정: 이전 버전은 `adb reverse`로 포트를 연결했다. 이 연결은 AVD 재부팅이나 ADB 서버 재연결 때 사라지지만 스크립트는 계속 떠 있어서, 재시작 뒤 버튼이 “PC 연결을 확인할 수 없어요”로 실패했다(재현: 스크립트 실행 중 `adb reverse --list`가 비어 있음). 이제 앱은 에뮬레이터의 호스트 루프백 별칭 `10.0.2.2:43192`로 직접 접속하고 스크립트는 `127.0.0.1:43192`에서 대기한다. 토큰이 없거나 맞지 않으면(재설치·데이터 삭제) 스크립트가 토큰을 다시 써 주고 앱이 한 번 재시도한다. 토큰 삭제, 앱 강제 종료, ADB 서버 재시작 뒤에도 버튼이 `MICOM_PTT_PRESS:SHORT`를 일으키는 것을 확인했다. [에뮬레이터 네트워크 주소](https://developer.android.com/studio/run/emulator-networking)

```powershell
python module/talkrec/tools/gleo-voice-button.py --serial emulator-5554
```

디버그 APK의 실제 버튼을 눌렀을 때 “운전석에서 듣는 중…” 화면과 `MICOM_PTT_PRESS:SHORT`를 확인했다. 앱 녹음이 꺼진 상태에서 사용자가 PC 마이크에 직접 “지금 몇 시야?”라고 말한 시험, 음량을 높인 한국어 합성음, 이전에 동반차 STT가 인식한 공개 영상의 한국어 음성 구간 모두에서 같은 현상을 재현했다.

- Pleos 음성 서비스는 `ko-KR`로 gRPC 스트림을 열었다. 음성이 재생되거나 발화한 시점에 인코딩된 오디오 크기가 증가했고, 원격 음성 게이트웨이의 `response audio ack`가 스트림 전반에 걸쳐 돌아왔다. 직접 발화 시험 중 한 세션에는 ACK가 7회 기록됐다.
- Gleo는 차량 음성 버튼 이벤트로부터 약 5초 뒤 `RESULT_TIMEOUT`으로 세션을 닫았다. 그 직전까지도 ACK가 돌아왔고, 인식 텍스트나 발화 종료 응답, 명시적인 gRPC 연결·인증 오류는 관찰되지 않았다. 예를 들어 게스트 시각 07:53:05.710에 버튼 이벤트, 07:53:10.741에 마지막 ACK, 07:53:10.760에 Gleo 타임아웃이 기록됐다.

이 시험에서 음성 게이트웨이는 응답했다. 실패한 구간은 Gleo가 인식 결과를 기다리는 단계이며, 이 상태에서는 일반 질문 응답과 동반차 Action 모두 확인할 수 없었다. ACK는 오디오 전송 확인이므로 게이트웨이 뒤 음성 인식 처리 상태까지 보여주지 않는다. AVD가 Gleo에 넘긴 오디오의 채널·품질, 서버의 발화 구간 판정과 인식 처리 중 어느 쪽이 원인인지는 아직 확인되지 않았다. 동일 빌드를 Gleo 일반 음성이 동작하는 Devbox에서 대조하거나, Pleos 지원 측에서 같은 시간대 gRPC 요청의 인식 로그를 조회하면 이 구간을 좁힐 수 있다.

### 2026-11-14 추가 관찰: 입력 레벨

- Gleo의 핫워드 트랙은 `AUDIO_SOURCE_HOTWORD`, 4채널 인덱스 마스크(`0x8000000F`), 16 kHz로 열려 있다. AVD 마이크 HAL 입력 스레드는 2채널(`0xc`)이다. Gleo 엔지니어링 모드(`am start --user 10 -a ambientai.intent.action.ENGINEERING_MODE -n ai.umos.ambientai/.SettingsActivity`) 상단에는 빨간 경고와 함께 `Unknown [4ch]`가 표시된다.
- PC 스피커로 한국어 TTS를 재생했을 때 `HOTWORD_TAP` 입력 레벨은 무음 −97 dBFS, 발화 최대 약 −43 dBFS였다. 일반적인 근접 발화(−20~−30 dBFS)보다 15~25 dB 낮다. Windows 기본 녹음 장치(USB 헤드셋 마이크)의 볼륨은 이미 100%였다.
- 동반차의 ON_DEVICE STT도 AVD에서 `MicAudioSource.amplifyMicFrame`의 최대 30 dB 증폭에 기대고 있다. Gleo는 이 증폭을 받지 못하므로, 호출어 감지와 서버 발화 구간 판정이 모두 낮은 레벨 때문에 실패했을 가능성이 가장 높다. 사용자가 헤드셋 마이크에 직접 말할 때의 레벨은 아직 측정하지 못했다.

### 2026-11-14 실측: 레벨이 충분하면 인식된다, 그리고 Action 이름 불일치

- 사용자가 헤드셋 마이크에 직접 크게 말해 최대 −30.9 dBFS가 들어가자, 차량 음성 버튼 경로에서 `transcript=지금 몇 시야?`가 나오고 스트리밍 TTS 답변까지 진행됐다. −40 dBFS대 발화는 서버가 발화 끝(EPD)을 잡지 못해 약 5초 뒤 `RESULT_TIMEOUT`이 났다. 이전 실패의 원인은 입력 레벨이다. 버튼 직후 1초 안에 말을 시작해야 하고, 단어 사이에 쉬면 거기서 발화가 끝난 것으로 처리된다(“동반차”만 인식돼 앱 실행으로 처리된 사례).
- “동반자 녹음 멈춰.”(오인식)에서도 Gleo AMI가 `com.dongbantest.app.PAUSE_TRIP_RECORDING`을 고르고 `sendActionCall action: com.dongbantest.app.PAUSE_TRIP_RECORDING, component: service`를 기록했다. 실제 인텐트 action에는 **패키지 접두어가 붙는다.** 앱의 intent-filter와 수신기는 `PAUSE_TRIP_RECORDING`만 받아서 브로드캐스트가 앱에 닿지 않았다. 매니페스트에 `${applicationId}.PAUSE_TRIP_RECORDING`/`RESUME_TRIP_RECORDING`을 추가하고 수신기가 두 형식을 모두 받도록 고쳤다. `cmd package query-receivers`로 접두어 action이 `GleoRecordingReceiver`로 해석되는 것을 확인했다. 수정 후 실제 음성으로 앱 콜백과 Gleo 최종 응답까지 이어지는지는 아직 확인하지 못했다.

### 2026-11-14 확인: 다른 앱이 녹음 중이면 Gleo는 시작하지 않는다

- 동반차 녹음 중 차량 음성 버튼을 누르면 Gleo가 `isUsingPrimaryMic: true`, `preProcessSystemEvent() : isUsingPrimaryMic is activated, ignoring`, `startPttButton() is ignored by preProcessSystemEvent`를 남기고 화면을 띄우지 않는다.
- Gleo 4.2.0.15 APK(`/system_ext/app/AmbientAiApp/AmbientAiApp.apk`)의 `AmbAudioManager$recordingCallback$1.onRecordingConfigChanged`는 `AudioRecordingCallback`의 활성 녹음 목록이 비어 있지 않으면 소스와 무관하게 `isUsingPrimaryMic=true`로 둔다. `VoiceChromeManager.preProcessSystemEvent`는 태블릿 빌드가 아니면 이때 PTT를 무시한다. AOSP는 HOTWORD 같은 시스템 전용 소스만 이 목록에서 빼므로 일반 앱은 피할 수 없다.
- Pleos SDK의 `SpeechToText.request()`로 GAAS(`ai.pleos.playground.caas`)가 대신 녹음하게 해도 `VOICE_RECOGNITION` 소스로 목록에 올라 같은 이유로 무시됐다.
- 결론: 동반차가 마이크를 잡고 있는 동안에는 Gleo 음성 명령(“녹음 멈춰”)을 쓸 수 없다. 앱 녹음이 멈춘 상태에서는 Gleo가 동작하므로 “녹음 다시 시작”은 Gleo로 가능하다. Gleo의 약 5초 무발화 타임아웃과 입력 레벨은 Gleo·HAL 쪽이라 앱에서 바꿀 수 없다.

두 로그를 함께 보려면 아래처럼 조회한다. `response audio ack`는 Gleo 앱 프로세스가 아닌 Pleos 음성 서비스 로그에 있다.

```powershell
adb -s emulator-5554 logcat -d -s '[GAA]:V' '[GAA-STT]:V' '[GAAS]SttStreamManagerImpl:I' |
    Select-String 'MICOM_PTT_PRESS|sendAudioBufferToServer|response audio ack|RESULT_TIMEOUT'
```

## Pleos 공식 가이드와 AVD 로그 대조

Gleo Interface의 App Schema는 Gleo가 사용자 요청을 해석한 뒤 앱의 함수를 고르고 실행할 수 있게 한다. 부팅 시 APK의 `assets/schema.json`을 찾아 로드하는 동작과 Pleos의 스키마 검토·승인 절차가 모두 안내돼 있다. AVD 로그의 `setAppCapability … functionCount=2`는 동반차 함수 두 개가 로컬에서 등록됐음을 보여준다. 앱 마켓 승인이나 실제 음성 명령에서의 함수 선택까지 확인하려면 별도 검증이 필요하다. 공개 가이드에서 ADB로 설치한 앱에 대해 어떤 조건으로 승인 없이 실제 음성 호출까지 허용하는지는 확인할 수 없었다. [Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts), [스키마 전달](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/deliver-schema)

| 단계 | 공식 가이드와 구현 | AVD에서 확인한 범위 |
| --- | --- | --- |
| 호출어 감지와 발화 인식 | Gleo가 대화를 시작하고 발화를 인식한다. | 호출어로 듣기 상태에 진입하지 않았다. 차량 음성 버튼은 듣기 상태를 열었다. 일반 질문에서도 음성 게이트웨이 ACK가 돌아왔지만 인식 결과 없이 약 5초 뒤 `RESULT_TIMEOUT`이 발생했다. |
| Gleo Action 선택 및 전달 | `component: "service"`는 앱 패키지를 지정한 브로드캐스트다. `action` 값은 스키마 함수 이름과 같다. | 두 함수 모두 APK 스키마에서 로드됐다. 실제 음성 발화에 대한 Gleo 발신 인텐트는 관찰되지 않았다. |
| 앱 콜백과 Gleo 최종 응답 | 앱이 `APP_ACTION_RESPONSE`에 `packageName`과 `response`를 넣어 전달한다. 가이드의 7초는 Gleo가 Action을 취소하는 제한이며 앱 내부 확인 제한은 6초다. | 디버그 모의 호출로 앱의 녹음 전환을 확인했다. 실제 Gleo Action에 대한 앱 콜백과 최종 응답은 아직 확인하지 못했다. |

Gleo가 일반 질문도 전사하지 못한 시점에는 앱 Action이 시작되지 않았다. 따라서 출시용 스키마 승인 여부로 이번 호출어 실패나 약 5초 음성 인식 타임아웃을 설명하기 어렵다. 일반 질문에 대한 인식이나 응답이 확인된 뒤 동반차 명령만 실패하면, 그때 스키마 승인·함수 선택·Android 전달을 조사한다. [Action과 콜백 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/examples), [콜백 제한 시간](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/action_callback)

동반차의 패키지명과 스키마 버전은 빌드의 `applicationId`, `versionName`과 일치한다. 두 함수는 가이드의 최대 3개 제한 이내이고, 파라미터가 없는 `service` Action이다. 코드에 추가한 `ai.umos.gleo.permission.APP_ACTION` 송신자 확인은 공개 예제에 명시돼 있지 않다. AVD에서 `ai.umos.ambientai`는 이 권한을 보유했고 음성 인식 서비스 패키지 `ai.pleos.playground.caas`는 보유하지 않은 것을 확인했다. 실제 Gleo Action이 어느 패키지에서 발송되는지는 이번 음성 시험으로 확인하지 못했다. 실차·Devbox에서는 실제 Action을 보내는 패키지와 권한, 콜백 수신을 확인해야 한다. [App Schema 작성 가이드](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/app-capability), [라디오 연동 예제](https://document.pleos.ai/docs/pleos-only/app-integration-guide/use-cases/radio-app)

My Project의 클라이언트 ID·시크릿 등록은 타사 앱이 SpeechToText SDK를 `SERVER` 또는 `HYBRID`로 호출할 때의 요건이다. 동반차의 대화 전사는 `ON_DEVICE` 모드다. 이 설정과 Gleo 시스템 앱의 음성 인식은 별도 경로다. [프로젝트·SDK 권한 신청](https://document.pleos.ai/docs/pleos-only/create-my-project), [SpeechToText 모드 및 등록](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)

## 재현과 합격 기준

1. Extended Controls의 호스트 마이크 접근과 차량 설정의 호출어 토글을 확인한다. 아래 명령으로 기본 네트워크와 `VALIDATED`를 확인한다. 이번 시험에서는 기본 네트워크가 없을 때 Android 게스트 재부팅 후 복구됐다.
2. 앱 녹음을 멈춘 상태에서 “글레오, 지금 몇 시야?”로 듣기 화면과 일반 응답을 확인한다. 별도로 AVD 차량 입력 `adb -s emulator-5554 shell cmd car_service inject-key 231` 또는 실차·Devbox의 음성 버튼으로 질문해 호출어 단계의 영향을 비교한다.
3. 정차 상태에서 동반차 여행의 녹음에 동의하고 화면에서 실제 녹음을 시작한다. “글레오, 동반차 녹음 멈춰”라고 말한 뒤 Gleo 발신 `PAUSE_TRIP_RECORDING`, 앱 `MIC` 중지, 앱 콜백 `status=success`, Gleo 화면·음성 확인을 같은 시각대에 확인한다.
4. 여행을 끝내지 않고 “글레오, 동반차 녹음 다시 시작해”라고 말한다. `RESUME_TRIP_RECORDING`, 앱 `MIC` 재시작, 성공 콜백 및 Gleo 응답을 확인한다. 이후 일반 발화에서 앱 STT의 `finals`가 증가하는지 확인한다. 주행 상태 시험도 같은 기준을 사용한다.
5. Gleo의 일반 질문에 응답하면서 동반차 Action만 보내지 않는다면 스키마 로드 로그와 명령의 앱 이름을 확인한다. Action을 받고도 상태 전환이나 응답이 없으면 녹음 세션, 동의, 브로드캐스트 전달과 콜백을 점검한다.

```powershell
adb -s emulator-5554 shell am get-current-user
adb -s emulator-5554 shell dumpsys connectivity | Select-String 'Active default network|VALIDATED'
adb -s emulator-5554 shell cmd appops get --user 10 ai.umos.ambientai RECORD_AUDIO_HOTWORD
adb -s emulator-5554 shell cmd appops get --user 10 ai.umos.ambientai RECORD_AUDIO
adb -s emulator-5554 shell cmd appops get --user 10 com.dongbantest.app RECORD_AUDIO
adb -s emulator-5554 shell dumpsys media.audio_policy | Select-String 'AUDIO_SOURCE_HOTWORD|AUDIO_SOURCE_MIC' -Context 5,2
adb -s emulator-5554 shell dumpsys media.audio_flinger | Select-String 'HOTWORD_TAP' -Context 0,32
adb -s emulator-5554 shell dumpsys activity broadcasts history | Select-String 'PAUSE_TRIP_RECORDING|RESUME_TRIP_RECORDING|APP_ACTION_RESPONSE' -Context 0,5
adb -s emulator-5554 logcat -d -s '[GAA]:I' 'ami-sdk:I' 'TalkRec:I' | Select-String 'Loaded schema.json from com.dongbantest.app|package=com.dongbantest.app functionCount|capture started|stt closed|stt final count'
adb -s emulator-5554 logcat -d -s '[GAA]:V' '[GAA-STT]:V' | Select-String 'MICOM_PTT_PRESS|requestSpeechStream|sendAudioBufferToServer|RESULT_TIMEOUT'
```

AVD 이미지에서 호출어가 계속 반응하지 않으면 같은 APK를 Gleo 일반 음성 질문이 동작하는 Devbox 또는 실차에서 시험한다. Pleos에 문의할 때는 AVD·Gleo 버전, `HOTWORD_TAP` 활성, 앱 녹음 ON/OFF 결과와 스키마 로드 결과를 전달하고, 이 이미지에서 지원하는 Gleo 호출어용 입력 경로와 음성 버튼 경로를 확인한다. [Pleos Playground 에뮬레이터·Devbox 안내](https://developers.pleos.ai/playground/discover/resources)
