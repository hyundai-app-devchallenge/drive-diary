# 동반차 음성 기술 검증

Pleos Connect v2.1.0 AVD에서 음성 인식과 마이크 입력을 독립적으로 확인하는 디버그 앱이다. 기존 동반차 앱과 다른 패키지인 `com.drivediary.sttprobe`로 설치한다.

## AVD에서 확인한 결과

| 항목 | 결과 |
| --- | --- |
| 환경 | Pleos Connect v2.1.0, Android 14/API 34, Pleos SpeechToText SDK 3.3.1 |
| 권한 | 테스트 앱에서 `pleos.car.permission.STT_SERVICE` 부여 확인 |
| Android 기본 인식기 | `SpeechRecognizer.isRecognitionAvailable() = true`, `isOnDeviceRecognitionAvailable() = false`. 기본 인식기에 실제 발화 요청은 보내지 않았다. |
| Pleos 온디바이스 STT | `Mode.ON_DEVICE` 초기화 및 최종 결과 콜백 확인. 한국어 합성 입력 ‘오늘 바다에 다녀왔어요.’가 정확히 전사됐다. |
| 영어 샘플 | 합성 입력 ‘We drove to the ocean.’에 결과는 왔으나 ‘we with robook 2000’으로 오인식됐다. 두 개의 개별 샘플은 언어별 품질을 비교하기에는 부족하다. |
| 30초 PCM 스트리밍 | 한 SDK 초기화 후 한국어 샘플을 0초·13초·26초 위치에 넣고 나머지는 무음으로 채웠다. PCM을 100ms 단위로 공급하는 동안 같은 문장에 대한 최종 콜백 3건을 받았다. |
| 마이크 없는 질문·답변 검증 | **Record virtual answer and transcribe**에서 한국어 합성 답변을 100ms씩 캡처하고 앱 캐시에 78,080바이트를 저장했다. 그 파일을 다시 읽어 Pleos STT로 ‘오늘 바다에 다녀왔어요’를 얻었다. |
| 120초 연속 STT | 한 SDK 인스턴스에서 한국어 합성 발화 5건을 모두 정확히 전사했다. 2건은 앱 화면에서, 3건은 약 43초에 차량 설정 화면으로 전환한 뒤 처리했다. 무음 구간에는 추가 최종 결과가 없었다. 전사 5건이 저장됐고 서비스가 종료됐다. |
| 30초 백그라운드 마이크 대조 시험 | 물리 마이크가 없는 상태에서 `AudioRecord`가 약 4.6초에 차량 설정으로 전환한 뒤에도 종료 시점까지 초당 약 16,000개의 프레임을 읽었다. `silenced=false`와 `peak=0`을 관측했다. 프레임은 무음이었으므로 이 시험은 캡처 생명주기만 확인했다. |
| 미검증 범위 | 실제 음성이 들어오는 마이크, 실차 녹음 파일의 재생, 120초를 넘는 연속 전사, 주행 제한 전환, 양산 차량 및 스토어 가용성 |

합성 음성 시험은 차량 마이크를 켜는 `request()` 호출 대신 앱이 가진 16kHz/16-bit/mono PCM을 `sendAudio()`로 보낸다. 따라서 합성 음성 결과는 인식 엔진과 앱 간 통신의 검증이다. [Pleos 오디오 데이터 전송](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)

## 실행

1. Android Studio에서 이 디렉터리를 별도 프로젝트로 연다. 빌드 설정은 Android SDK Platform 37, AGP 9.5.1, Gradle Wrapper 9.6.0과 데몬 JDK 25를 사용한다.
2. Pleos Connect v2.1.0 AVD를 Cold Boot로 실행하고 `Drive Diary STT Probe` 앱을 설치한다. Windows에서는 이 디렉터리에서 `.\gradlew.bat assembleDebug`로 APK를 빌드할 수 있다.
3. 앱에서 **Run synthetic Korean STT**, **Run synthetic English STT**, 또는 **Run 30-second synthetic STT**를 선택한다. Logcat 태그는 `DriveDiarySttProbe`다. PCM 자산은 Windows 음성 합성기로 생성한 테스트 문장이며 실제 탑승자 음성은 포함하지 않는다.
4. 실제 입력을 보려면 에뮬레이터 **Extended Controls → Microphone**에서 호스트 마이크 입력을 켠다. 앱에서 **Test mic input for 8 seconds** 또는 **Test mic input for 30 seconds**를 선택하고 마이크·알림 권한을 허용한 뒤 다시 선택한다.
5. 30초 테스트는 시작 후 Pleos 홈이나 다른 앱으로 이동해 화면 전환 뒤에도 입력이 이어지는지 확인한다. Logcat에서 `Probe activity stopped` 이후에도 `frames`와 음성에 반응하는 `peak`가 나오고 `silenced=false`인지 본다. **Stop mic test** 또는 알림의 **Stop**으로 언제든 종료할 수 있다. 앱은 캡처한 PCM을 파일이나 네트워크에 보내지 않고 입력 통계만 남긴다.

### 물리 마이크가 없는 PC

앱에서 **Record virtual answer and transcribe**를 선택한다. 질문을 표시한 뒤 합성 답변을 실시간 속도로 읽고 `cache/virtual-answer.pcm`에 저장한다. 그 파일을 온디바이스 STT로 전사한 결과를 화면과 Logcat에서 확인한다. 이 버튼은 앱 내부 입력원을 사용하므로 호스트 마이크 토글과 마이크 권한이 필요 없다. 이 시나리오는 저장과 전사를 검증한다. Android의 마이크 경로와 실제 백그라운드 녹음은 **Test mic input** 버튼에서 별도로 시험한다.

### 120초 연속 음성 입력과 화면 전환

1. **Run 120-second background STT**를 선택한다. 알림 권한 요청이 나오면 허용하고 버튼을 다시 누른다.
2. Logcat에 `Continuous STT: Synthetic PCM stream started`가 나오면 다른 앱으로 전환한다. `Probe activity stopped` 이후에도 최종 전사가 들어오는지 확인한다.
3. 완료 후 검증 앱으로 돌아와 **Show last background STT result**를 누른다. 최종 결과와 수신 시간이 앱 캐시의 `continuous-stt.txt`에 저장된다. 조기 종료는 앱의 **Stop background STT** 또는 알림의 **Stop**을 사용한다.

입력은 16kHz 모노 PCM으로, 4·24·54·87·114초 지점에 한국어 합성 발화가 시작되고 나머지는 무음이다. 한 SDK 인스턴스에 100ms씩 보내고 마지막에만 `completeAudioSend()`를 호출한다. 합성 STT 시험에는 Android의 `shortService`를 사용하고, 마이크 대조 시험에는 `microphone` Foreground Service를 사용한다.

| 발화 시작 위치 | 최종 결과 수신 | 결과 |
| ---: | ---: | --- |
| 4초 | 6.366초 | 오늘 바다에 다녀왔어요 |
| 24초 | 27.003초 | 친구들이 차 안에서 웃었어요 |
| 54초 | 57.003초 | 저녁에 노을을 봤어요 |
| 87초 | 90.982초 | 친구들이 차 안에서 웃었어요 |
| 114초 | 117.973초 | 오늘 바다에 다녀왔어요 |

발화 시작 위치는 PCM 내부 시간, 최종 결과 수신 시간은 첫 입력 전송부터의 실제 경과 시간이다. 120초 분량의 전송은 121.791초에 끝났다. 입력은 단일 합성 화자의 순차 발화와 무음으로 구성했다. 다화자 중첩과 주행 소음 조건은 별도 검증 대상이다.

## 제품 적용 판단

- 공모전 MVP는 질문 제안 → 사용자가 녹음 시작 → 답변을 로컬 파일로 저장 → 선택한 답변을 온디바이스 STT로 전사하는 흐름이 적합하다. 음성으로 질문을 읽으려면 TTS 종료 콜백 뒤 마이크를 열어 앱 자체의 질문이 답변으로 인식되는 것을 피한다.
- 연속 모드는 탑승자가 시작한 시간 제한 세션으로 실험한다. 화면이 보일 때 시작한 `microphone` foreground service를 사용하고, 음성이 있는 구간만 STT로 넘긴 뒤 사용자가 저장할 구간을 선택한다. 나중에 의미 있는 대화를 자동 선택하려면 전사 결과를 분석하는 별도 로직이 필요하다.
- 이 검증에서 합성 PCM 전사와 `AudioRecord`는 별도로 실행했다. 실차 통합에서는 마이크 캡처를 한 곳에서 소유하고, 같은 PCM을 기록과 전사에 분배하는 경로를 시험한다.
- Android의 `SpeechRecognizer`는 연속 인식용으로 설계되지 않았다. Pleos 앱 마켓의 SDK 사용 상태와 주행 허용 범위는 AVD 동작과 별도로 확인한다. 문서상 `ON_DEVICE`는 서버 앱 등록 없이 동작하고, 서버 인식은 My Project 자격 증명이 필요하다.

[Android 마이크 Foreground Service](https://developer.android.com/about/versions/14/changes/fgs-types-required#microphone) · [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) · [Pleos SpeechToText](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro) · [Pleos SDK 제공 상태](https://document.pleos.ai/docs/pleos-only/create-my-project) · [Pleos 주행 안전 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)
