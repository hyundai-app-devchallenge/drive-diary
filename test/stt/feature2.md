# 기능 2 · 대화 후보 선별 검증

기준 커밋은 `9c30514`다. 아래 실험은 Pleos Connect v2.1.0 AVD, API 34와 SpeechToText SDK 3.3.1의 `ON_DEVICE` 모드에서 수행한다.

## 제안 동작

1. 동승자 동의를 받고 사용자가 화면에서 여행 세션을 시작한다. `microphone` Foreground Service가 세션과 중지 알림을 유지한다. 여행 종료, 동의 철회, 앱 오류에 세션을 닫는다.
2. 마이크가 열려 있는 동안에만 짧게 PCM을 RAM에 보관한다. 테스트는 **6초 열기 → 10초 동안 AudioRecord 해제**를 반복한다. 6초는 시험 조건이다. 양산 설계는 발화 경계 감지로 구간을 자르고 열린 구간의 상한을 정한다.
3. 입력 구간 전체에서 Gleo가 비활성이라고 확인됐을 때만 완결된 PCM 구간을 Pleos STT로 보낸다. Gleo 시작 또는 상태 불명, 캡처 무음 처리, 창 끝으로 잘린 발화가 감지되면 해당 구간을 버린다. 이벤트가 지연될 수 있다면 시각이 포함된 충돌 정보와 안전 여유가 필요하다. 열린 세션 중 이미 STT로 보낸 음성을 나중에 소급해서 취소할 수 있다는 가정은 두지 않는다.
4. 전사 결과에 작은 온디바이스 분류기를 적용하고 고득점 문장만 추억 후보로 남긴다. 후보는 사용자가 확인하거나 삭제할 수 있게 한다. 실제 제품은 불필요한 원본 음성과 일반 대화 전사를 디스크에 쓰지 않으며, 남기는 후보 텍스트는 보존 기간과 삭제 정책을 적용한다.

마이크가 닫혀 있는 동안에는 발화 여부와 이야기 내용 모두 알 수 없다. 테스트의 6초/10초 주기는 최대 37.5%의 시간에 마이크가 열리며, 창 경계로 잘린 문장까지 포함하면 온전하게 얻는 비율은 더 낮다. 무음 탐지(VAD)를 계속 사용하면 빈 구간의 저장과 전사는 줄일 수 있지만, 그동안에도 마이크를 열어 샘플을 받아야 한다. 흥미도 판단은 발화 검출과 별도 단계다.

[Android 마이크 서비스 시작 조건](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions) · [Pleos PCM 입력](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data) · [Pleos 개인정보 설계](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy)

## Gleo와의 충돌

- Android의 녹음 상태 콜백과 `AudioRecordingConfiguration.isClientSilenced()`는 **우리 입력이 차단됐는지** 알려 준다. `false`를 Gleo 유휴 신호로 쓰면 안 된다. Android에서는 권한을 가진 Assistant와 일반 앱의 입력이 조건에 따라 함께 제공될 수 있다. `setPrivacySensitive(true)`는 Assistant의 동시 입력까지 막을 수 있어 Gleo에 우선권을 양보하는 방법으로 쓰지 않는다. 재생용 Audio Focus도 녹음 시작·종료 신호를 대신하지 않는다.
- 공개 Gleo Interface에서 확인한 기능은 음성 명령을 해석한 뒤 앱에 Action을 호출하는 연동이다. 앱이 Gleo의 호출어 대기·청취 시작·종료를 구독하거나 마이크 사용을 양보받는 공개 API는 확인하지 못했다.
- AVD에서 기본 Assistant가 `ai.umos.ambientai/.service.AiAssistantService`인 것을 확인했다. 30초 캡처 중 셸로 Assistant 세션을 열어 `mShown=true`를 관측했으나 우리 입력의 `isClientSilenced`는 계속 `false`였다. 물리 마이크가 없어 실제 호출어 인식이나 그 시점의 Gleo 마이크 점유는 입증하지 못했다.
- 양산에서 Gleo 명령의 저장을 막으려면 신뢰 가능한 시작·종료 상태와 이벤트 지연 상한이 필요하다. **마이크 사용 자체가 한순간도 겹치면 안 된다면 플랫폼이 강제하는 마이크 중재가 필요하다.** 상태 이벤트만으로는 이벤트가 도착하기 전의 동시 캡처를 막을 수 없다. 요구 수준에 맞는 중재를 확보하기 전에는 자동 캡처 모드를 켜지 않는다. 실차 시험은 호출어, 스티어링 휠 버튼, 앱 화면 전환, Gleo 응답 재생, 통화 시작 및 종료를 구분해 본다.

[Android 마이크 입력 우선순위](https://developer.android.com/media/platform/sharing-audio-input) · [Pleos Gleo Interface](https://document.pleos.ai/docs/pleos-only/app-integration-guide/guide/core-concepts) · [Pleos Audio Focus](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development)

## AVD 재현

- **Test 10s gaps, Gleo guard and local story model**은 70초 합성 대화에서 발화 일곱 건을 시험한다. 마이크 오프 구간, Gleo 충돌, 창 경계로 잘린 문장, Gleo 상태 불명으로 네 건을 가린다. 고정된 발화 위치와 모의 Gleo 타임라인으로 게이트를 시험한다. 실제 VAD와 Gleo 상태 수신은 이 실험의 입력 경계 밖에 있다.
- **Test mic close and reopen with 10s gap**은 실제 Android `AudioRecord`를 6초 쓰고 해제한 뒤 10초 후 다시 연다. 시험 중 차량 설정 화면으로 이동해 Foreground Service의 수명과 입력 장치의 수명을 구분한다. 이 시험은 입력 수치만 기록한다.
- Logcat 태그는 `DriveDiarySttProbe`다. 합성 시험에서 완료된 후보 파일은 앱 캐시의 `story-probe-candidates.txt`다.

| 시험 | 관측 결과 |
| --- | --- |
| 70초 합성 대화 | 발화 7건 중 4건을 게이트에서 제외했다. 나머지 3건을 Pleos가 모두 전사했다. |
| 후보 파일 | “아빠 신발이 파도에 떠내려가서 다 같이 웃었어요” 0.962, “할머니가 처음 바다를 보고 내 손을 꼭 잡았어요” 0.921 두 건을 저장했다. “다음 휴게소까지 몇 분 걸려요?”는 0.013으로 제외됐다. |
| 10초 실제 입력 해제 | 차량 설정 화면으로 전환한 뒤 첫 `AudioRecord`를 해제했다. 서비스가 실행 중일 때 오디오 상태에서 앱의 활성 녹음이 0개였고, 10,098ms 뒤 백그라운드에서 다시 열렸다. 두 창 모두 무음 프레임을 받았다. |

## 로컬 후보 분류

문자 2~4글자 조합을 쓰는 로지스틱 회귀를 Java에서 실행한다. Python 표준 라이브러리로 합성 문장 40개를 학습하고 가중치 282개를 TSV로 저장했다. `python tools/train-story-probe.py`로 재생성할 수 있다. 모델 점수 0.65 이상을 후보로 분류한다.

AVD에서 모델 파일은 4,697바이트였다. 워밍업 100회 이후 500회 추론에서 p50 0.329ms, p95 0.679ms였다. 별도 작성한 한국어 테스트 문장 16개는 모두 기대 분류와 일치했다. 이 표본은 제한된 합성 문장과 작성자가 고른 표현으로 구성됐다. 실제 차량 대화에서 선호도, 전사 오류, 여러 문장 맥락과 오탐률을 별도로 평가해야 한다.
