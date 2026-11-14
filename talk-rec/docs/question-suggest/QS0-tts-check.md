# QS0: Pleos 기기 안 TTS 확인

2026-11-14 · Pleos Connect Emulator 2.1.0 (Android 14, 사용자 10) · 음성 서비스 `ai.pleos.playground.caas` 4.2.10 · TextToSpeech SDK 3.3.1 · 동반차 debug

## 결론

**Pleos AVD에서 `Mode.ON_DEVICE` + `ko-KR`로 한국어 질문을 읽는다.** 네트워크와 My Project 앱 등록 없이 동작했다. 질문 기능의 TTS는 ON_DEVICE로 간다.

## 확인한 것 (DESIGN §6의 1~5)

| 항목 | 결과 |
| --- | --- |
| 1. ON_DEVICE + ko-KR 초기화와 재생 | `initialize` 성공까지 58ms, `speak` 직후 `onReady`·`onStart`. 26자 질문 재생 약 3.6초 뒤 `onDone`. 두 번째 호출부터는 초기화 없이 바로 시작 |
| 권한 | `pleos.car.permission.TTS_SERVICE`는 `normal` 보호 수준이라 설치 때 자동 허용(`granted=true`). 실행 중 권한 요청 필요 없음 |
| 재생 경로 | 앱이 아니라 음성 서비스(`[GAAS]AudioTrackManagerImpl`)가 `AudioTrack`을 만든다: 22.05kHz 모노, `usage=1`(MEDIA), `contentType=1`(SPEECH), `zoneId=-1`. 첫 조각 뒤 약 1초 동안 underrun 경고가 한 번 났다(합성이 재생보다 늦게 시작) |
| 2. 대화 캡처 중 재생 | 녹음(마이크 열림) 중에도 재생됐다. 오디오 포커스 스택에는 항목이 없었다(음성 서비스가 포커스를 요청하지 않는 것으로 보임). 음악과 겹칠 때 줄여 주는지는 미확인 |
| 3. TTS 소리가 우리 마이크로 들어오는가 | **들어온다.** 질문을 읽는 동안 STT 확정 줄이 2개(13자) 생겼다. 그 줄이 든 묶음은 질문 구간과 겹쳐 `block skipped reason=question_window`로 버려졌다. 설계대로 저장·판정·요약되지 않았다 |
| 4. `setSpeakerType` / `setAudioZoneId` | 공개 메서드는 있으나 AAR에 speaker type 상수가 없다. 기본값(zone -1)으로 두었다. 운전석만, 작은 음량 같은 지정은 실차·Devbox에서 Pleos에 확인할 일로 남긴다 |
| 5. 정확한 API | `speak(String, Boolean)`은 반환값 없음. 리스너 콜백은 메인이 아닌 바인더 스레드로 온다. 길이 제한·동시 요청 규칙은 문서와 AAR 모두에 없음. 앱은 한 번에 하나만 읽는다 |

## 함께 확인한 흐름

질문 실험실과 adb 명령으로 녹음 중인 여행에서 확인했다(합성음·실제 대화 없이).

- **지금 위치로 질문 만들기**: 서울역 좌표 → Open-Meteo "흐림, 10도, 3시간 안 비 98%" → Cloudflare Gemma 4가 질문 생성, 2.7초. 보낸 맥락 JSON에 좌표·이름·대화 글 없음.
- **Q01 → 반응 → Q02**: `ASK_FORCE_QUIET`로 Q01이 화면에만 떴다. 10초 안에 반응이 없으면 `Muted(no_reaction)`로 "이번 여행에서는 먼저 묻지 않을게요"가 됐다. 수락하면 미리 만든 질문(날씨 주제)을 TTS로 읽고 Q02 화면과 "지금까지 나눈 대화"에 질문이 올라갔다.
- 시험 중 고친 것: TTS 성공을 시간 초과로 잘못 보고하던 문제, 수락하면 "먼저 묻지 않기"를 풀도록 한 것, Q01 머리 줄을 "화면으로만 여쭤봐요"와 눈 아이콘으로 바꾼 것.

## 남은 확인

- 실제 사람 목소리로 "아니, 안 자"를 말했을 때 STT가 반응 창(10초) 안에 확정 줄을 주는지. AVD는 마이크 입력 레벨이 낮아([Gleo 시험](../gleo/gleo-voice-test.md)) 실제 발화 확인은 Devbox·실차에서 한다.
- 실제로 소리가 들리는 크기와 다른 소리(음악)와 겹칠 때의 동작.
