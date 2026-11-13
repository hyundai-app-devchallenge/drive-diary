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

동반차 디버그 상단의 **Gleo 차량 음성 버튼**은 Pleos AVD에서 스티어링 휠의 짧은 음성 버튼 입력을 보낸다. AVD에서 동반차 디버그 APK를 설치하고, PC에서 `drive-diary-int` 루트를 열어 아래 스크립트를 실행한 상태로 버튼을 누른다. Android의 차량 입력에는 권한이 필요하므로 PC의 ADB가 `cmd car_service inject-key 231`을 실행한다. ADB가 PATH에 없다면 `--adb`에 platform-tools의 adb 경로를 넘긴다. [Android ADB 역방향 포트 연결](https://developer.android.com/develop/ui/views/layout/webapps/access-local-server)

```powershell
python module/talkrec/tools/gleo-voice-button.py --serial emulator-5554
```

디버그 APK의 실제 버튼을 눌렀을 때 “운전석에서 듣는 중…” 화면과 `MICOM_PTT_PRESS:SHORT`를 확인했다. 앱 녹음이 꺼진 상태에서 사용자가 PC 마이크에 직접 “지금 몇 시야?”라고 말한 시험, 음량을 높인 한국어 합성음, 이전에 동반차 STT가 인식한 공개 영상의 한국어 음성 구간 모두에서 같은 현상을 재현했다.

- Pleos 음성 서비스는 `ko-KR`로 gRPC 스트림을 열었다. 음성이 재생되거나 발화한 시점에 인코딩된 오디오 크기가 증가했고, 원격 음성 게이트웨이의 `response audio ack`가 스트림 전반에 걸쳐 돌아왔다. 직접 발화 시험 중 한 세션에는 ACK가 7회 기록됐다.
- Gleo는 차량 음성 버튼 이벤트로부터 약 5초 뒤 `RESULT_TIMEOUT`으로 세션을 닫았다. 그 직전까지도 ACK가 돌아왔고, 인식 텍스트나 발화 종료 응답, 명시적인 gRPC 연결·인증 오류는 관찰되지 않았다. 예를 들어 게스트 시각 07:53:05.710에 버튼 이벤트, 07:53:10.741에 마지막 ACK, 07:53:10.760에 Gleo 타임아웃이 기록됐다.

이 시험에서 음성 게이트웨이는 응답했다. 실패한 구간은 Gleo가 인식 결과를 기다리는 단계이며, 이 상태에서는 일반 질문 응답과 동반차 Action 모두 확인할 수 없었다. ACK는 오디오 전송 확인이므로 게이트웨이 뒤 음성 인식 처리 상태까지 보여주지 않는다. AVD가 Gleo에 넘긴 오디오의 채널·품질, 서버의 발화 구간 판정과 인식 처리 중 어느 쪽이 원인인지는 아직 확인되지 않았다. 동일 빌드를 Gleo 일반 음성이 동작하는 Devbox에서 대조하거나, Pleos 지원 측에서 같은 시간대 gRPC 요청의 인식 로그를 조회하면 이 구간을 좁힐 수 있다.

두 로그를 함께 보려면 아래처럼 조회한다. `response audio ack`는 Gleo 앱 프로세스가 아닌 Pleos 음성 서비스 로그에 있다.

```powershell
adb -s emulator-5554 logcat -d -s '[GAA]:V' '[GAA-STT]:V' '[GAAS]SttStreamManagerImpl:I' |
    Select-String 'MICOM_PTT_PRESS|sendAudioBufferToServer|response audio ack|RESULT_TIMEOUT'
```

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
