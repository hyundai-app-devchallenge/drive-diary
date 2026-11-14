# G01: 자체 STT로 여행 녹음 일시정지

## 배경과 사용 방법

동반차(`com.dongbantest.app`)는 자체 `AudioRecord`로 차 안의 대화를 수집하고 Pleos ON_DEVICE SpeechToText SDK로 전사한다. 기존에 확인한 Gleo 동작상, 어떤 앱이든 활성 `AudioRecord`를 가지고 있으면 `preProcessSystemEvent: isUsingPrimaryMic` 검사에서 Gleo 실행을 거부한다. 버튼과 호출어 모두 해당하므로 녹음 중 Gleo 앱 액션으로 일시정지하는 방식은 사용할 수 없다.

이제 녹음 중 **“동반차 녹음 멈춰”**라고 말하면 동반차의 STT가 명령을 인식해 스스로 일시정지한다. 일시정지되어 마이크가 비어 있을 때는 기존 Gleo 앱 액션으로 재개한다. 재개 안내는 **“글레오, 동반차 녹음 다시 시작해”**다. 기존 Gleo 수신기의 패키지 접두사 액션 처리와 재개 동작은 유지했다.

## 녹음 일시정지

여행이 녹음 중일 때 STT가 확정한 줄을 앱에서 검사한다. “녹음 정지”, “녹음 잠시 멈춰”, “녹음을 중단해 주세요” 같은 독립 발화를 지원한다. `PauseCommand.kt`는 공백과 문장부호를 제거한 줄 전체를 명령 패턴과 비교한다. 검사에 포함한 인용과 부정 표현은 일반 대화로 처리한다. `TalkRecordingController`는 현재 여행과 녹음 동의를 확인하고 일시정지를 기록한다. 캡처 서비스가 이벤트 루프에서 입력을 닫으며, 명령 줄은 대화 저장으로 넘기지 않는다.

AVD의 마이크 경로는 STT 준비 로그가 있어도 확정 전사가 0건이었다. 합성음 파일을 직접 넣은 첫 시도에서는 “그리고 동반찬 녹음 거주어”로 확정되어 명령을 놓쳤다. 더 짧고 또렷한 합성음으로 “녹음 정지”가 확정되고 `paused by voice command`와 `Paused`를 확인했다. 확정 전사에 의존하므로 발화 종료 지연과 오인식은 정지 동작에 영향을 준다.

## 디버그 화면에서 가상 입력

상단 **정지 음성 STT 테스트**는 디버그 APK에 포함된 한국어 합성 WAV “녹음 정지”를 진행 중인 여행의 Pleos ON_DEVICE STT에 보낸다. 주행 D 상태에서 하단 진단을 접어 두고도 시험할 수 있다. 확정 전사가 정지 명령과 일치하면 일반 마이크 발화와 같은 경로에서 여행 녹음이 멈춘다. 테스트 파일에서 나온 전사는 여행 대화로 저장하지 않는다. 정지 명령이 인식되지 않고 파일이 끝나면 마이크 입력으로 돌아간다.

1. 대화 녹음에 동의한 진행 중인 여행에서 녹음을 시작한다. 디버그 하단 패널에 **여행 녹음 시작** 또는 **여행 녹음 재개**가 표시되면 해당 버튼으로 녹음을 켠다. 여행 없이 파일을 시험하려면 인식 실험실을 사용한다.
2. 동반차 디버그 하단의 **녹음 진단 → 음성 파일 입력**에서 실험실 폴더의 WAV, M4A, MP3, OGG를 고르거나 기기의 파일 선택기를 연다. Pleos 이미지에서 파일 선택기가 동작하지 않으면 디버그 APK의 `files/lab`에 미리 넣어 둔 파일을 고른다.
3. 파일은 스피커 재생 없이 실제 여행 STT에 공급된다. 파일이 끝나면 마이크로 돌아간다. 파일에서 정지 명령이 인식되면 녹음이 멈추고, 재개할 때 마이크가 연결된다.
4. 패널을 펼쳐 STT 연결과 음량, 부분·확정 전사, 저장 판정, 외부 AI 동의와 처리 상태, 요청·응답 JSON을 본다. AI 전송은 여행의 기존 동의를 따른다. 진단에 표시하는 전사와 JSON은 화면이 활성인 동안 메모리에 유지한다.

일반 합성 대화에서 확정 전사 3줄과 저장 수락 1건을 확인했다. 파일 입력이 끝난 뒤 마이크로 돌아왔고, 기본 네트워크 복구 후 Cloudflare `HTTP 200`과 요청·응답 JSON을 확인했다.

이 입력은 동반차의 마이크 소스를 대체한다. Gleo 시스템 마이크에도 파일을 공급하려면 호스트의 가상 캡처 장치나 Android Emulator의 `EmulatorController.injectAudio`를 사용할 수 있다. 후자는 인증된 호스트 gRPC 세션에서 PCM을 Android 마이크로 주입하는 경로다. [Android Emulator 오디오 API](https://android.googlesource.com/platform/tools/base/+/0239135bcf32e602c77c89078cbf0d190ff581fd/emulator/proto/emulator_controller.proto)

## Gleo 차량 음성 버튼

종전 버튼은 PC 루프백의 `gleo-voice-button.py`에 연결해 Android 셸 권한으로 차량 키를 보냈다. 디버그 APK 권한으로 키를 직접 보내면 `android.permission.INJECT_EVENTS`에서 차단된다. 새 버튼은 Android 공개 `Activity.startLocalVoiceInteraction()`를 호출한다. 기존 Python 프로세스나 포트는 필요 없다. [Android Activity API](https://developer.android.com/reference/android/app/Activity#startLocalVoiceInteraction(android.os.Bundle)), [AOSP 차량 키 권한](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/android14-dev/service/src/com/android/car/CarShellCommand.java)

Pleos AVD 2.1.0의 Gleo 4.2.0.15에서 `MICOM_PTT_PRESS:SHORT`, “운전석에서 듣는 중…”, 서버 STT 스트림과 오디오 ACK까지 확인했다. 발화 없이 시험해 듣기 시간 만료까지 관찰했다. Gleo는 활성 여행 마이크가 있을 때 버튼을 거부하므로, 이때는 자체 정지 명령이나 상단의 정지 음성 STT 테스트를 사용한다.

AVD 재부팅 후 또는 동반차를 강제 종료한 뒤 Android가 `deliverNewSession does not match active session`을 남기면 듣기 세션이 열리지 않았다. 이 상태에서는 셸에서 주입한 차량 키도 Gleo까지 도달하지 않았다. 기본 네트워크가 복구된 상태에서 Gleo 프로세스를 다시 시작하자 버튼이 동작했다. 앱 API로 이전 세션을 종료한 뒤 다시 요청하는 시도는 고착을 풀지 못했다. 디버그 버튼이 이제 호출마다 독립 Activity에서 음성 세션을 시작하고 Android 종료 콜백을 받으면 그 화면을 닫는다. 시작 콜백이 오지 않으면 요청 화면을 닫고 실패를 표시한다. Android가 시작에 실패한 요청을 같은 Activity에서 계속 대기로 처리하는 문제가 다음 버튼 클릭으로 이어지는 것을 피할 수 있다. Pleos AVD에서 두 번 연속 듣기 시작과 자연 종료를 확인했다. Gleo 서비스 자체의 세션 오류와 실제 발화로 Gleo 재개 Action 및 콜백까지 이어지는지는 별도 확인이 필요하다. [Android Activity 음성 세션 콜백](https://developer.android.com/reference/android/app/Activity#onLocalVoiceInteractionStarted())

## 빌드 및 테스트

- 디버그 APK 빌드와 출시 Kotlin 컴파일이 통과했다. 앱 JVM 테스트 49개와 TalkRec 캡처·요약 테스트가 통과했다.
- 전체 TalkRec JVM 테스트에서는 ONNX 골든 테스트 1개가 Windows의 `onnxruntime.dll` 초기화 오류로 실패했고 비공개 데이터 테스트 1개가 건너뛰어졌다. AVD에서는 로컬 판별 모델의 저장 수락까지 확인했다.
