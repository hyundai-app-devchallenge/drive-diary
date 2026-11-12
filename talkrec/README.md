# talkrec 캡처 (I3)

앱은 녹음 동의와 `RECORD_AUDIO` 런타임 권한을 받은 뒤, 화면이 보일 때 시작한다.

```kotlin
TalkRec.start(context, tripId, CaptureOptions(sink = appTalkSink))
TalkRec.pause()
TalkRec.resume()
TalkRec.markMoment()
TalkRec.stop()
// TalkRec.state: StateFlow<CaptureState>
```

`TalkSink` 구현은 I4에서 연결한다. `onKeptBlock()`은 클립과 메타데이터를 원자적으로 저장한 뒤 성공 여부를 반환한다. 전달한 PCM은 반환 시 지워지므로 호출 중 소비하거나 필요한 복사본을 소유해야 한다. 취소 시 미완료 저장을 남기지 않아야 한다. `deletePreviousSavedClip()`은 여행의 직전 조각만 지우고 `refusalId`로 중복 실행을 막는다. `keepScore`와 `kind`는 모델 실패 시 null이다.

100ms 프레임을 3분 RAM 버퍼와 Pleos `ON_DEVICE`로 보낸다. 마지막 확정 줄 **수신 후 20초**와 **연속 저음량 PCM 20초**가 동시에 충족되거나 길이가 90초가 되면 묶는다. 기본 RMS 문턱은 PCM16 단위 140이며 `CaptureOptions.silenceRms`로 바꾼다. SDK 줄/EPD 시각으로 침묵을 추정하지 않는다. 수동 버튼은 열린 묶음을 `MANUAL`로 닫고 모델 keep 판정만 우회한다. 확정 줄이 없으면 저장할 묶음이 없다.

T56 정책은 원문 대신 정규화한 사본에 적용한다. 기록 거부는 현재 후보를 버리고 이전 저장 조각 삭제를 요청한다. 정책이 일부 줄을 제거한 혼합 묶음은 현재 전체 오디오 후보도 제외한다. Pleos 콜백만으로 해당 명령 음성을 정확히 잘라낼 수 없기 때문이다. AUTO 저장은 앞뒤 여유를 포함한 실제 PCM 길이로 이동 1시간당 10분을 제한한다. 모델 로드/추론 실패도 이 상한으로 제한한다. MANUAL은 정책을 통과하면 자동 분량 상한을 우회한다. 상한 장부는 프로세스 메모리에서 pause/resume과 여행 재시작 사이에 유지된다.

## 디버그 ADB (Pleos user 10)

디버그 빌드에만 수신기와 시작용 작은 투명 Activity가 포함된다. 기본 디버그 sink는 후보를 RAM에서 세고 폐기한다. I4 전에는 파일/DB 저장이나 재생을 검증하는 경로가 아니다. 입력은 직접 만든 PCM 또는 동의받은 음성만 사용한다.

외부 SDK 접근 가능한 셸에서 빌드/설치 후 실행한다.

```powershell
.\gradlew.bat :talkrec:testDebugUnitTest :app:installDebug
adb shell am force-stop --user 10 com.dongbantest.app
adb shell pm grant --user 10 com.dongbantest.app android.permission.RECORD_AUDIO
adb shell pm grant --user 10 com.dongbantest.app android.permission.POST_NOTIFICATIONS
adb shell dumpsys package com.dongbantest.app | Select-String 'RECORD_AUDIO|FOREGROUND_SERVICE|STT_SERVICE'

adb push .\x.wav /data/local/tmp/x.wav
adb shell chmod 644 /data/local/tmp/x.wav
# 앱 UI 없이 시작. Android 14+의 microphone FGS 시작 자격을 Activity에서 확보한다.
adb shell am start --user 10 -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureActivity --es path /data/local/tmp/x.wav --el tripId 0

# 이미 실행 중인 캡처의 입력 전환. -n은 다른 앱으로의 전달을 막고 수신기를 명확히 지정한다.
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_INJECT -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver --es path /data/local/tmp/x.wav --ez loop true
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_MARK -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_PAUSE -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_RESUME -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_STATE -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_MIC -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb shell am broadcast --user 10 -a com.drivediary.talkrec.DEBUG_STOP -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureReceiver
adb logcat -d -s TalkRec:I
```

`DEBUG_START` 브로드캐스트도 같은 extra로 시작할 수 있다. 단, 백그라운드 마이크 FGS 시작 제한이 있으므로 **앱 UI 없이 시작하는 시험은 위의 `DebugCaptureActivity` 명령을 사용**한다. `path`를 생략하면 MIC, `--ez loop true`면 파일 반복이다. 비반복 파일은 마지막 부분을 100ms까지 zero pad하고 25초 무음을 덧붙여 확정 결과/침묵 닫기를 기다린 다음 Paused가 된다. resume하면 파일 처음부터 다시 읽는다. WAV는 PCM format 1 / 16kHz / mono / 16bit만 받으며 `.pcm` raw는 같은 little-endian 포맷이어야 한다.

`/data/local/tmp`가 SELinux로 차단되는 AVD에서는 앱 전용 경로로 옮긴다. user 0의 앱 경로를 쓰면 안 된다.

```powershell
adb shell "cat /data/local/tmp/x.wav | run-as com.dongbantest.app --user 10 sh -c 'cat > files/talkrec-debug.wav'"
adb shell am start --user 10 -n com.dongbantest.app/com.drivediary.talkrec.debug.DebugCaptureActivity --es path /data/user/10/com.dongbantest.app/files/talkrec-debug.wav --el tripId 0
```

`TalkRec` 로그는 입력 프레임 수, 확정 줄 수/글자 수/수신 시각, 묶음 닫힘 이유, 점수와 저장 수만 남긴다. 원문·파일 내용·예외 메시지는 남기지 않는다. 20분 입력이면 `frameCount=12000`, 비반복 자동 tail까지 포함하면 `12250`이다. `stt ... sent`, `finals`, `errors`, `block closed reason=SILENCE/LENGTH`를 함께 확인한다. 실제 20분 무누락/무오류 판정은 AVD에서 수행해야 한다.

## JVM 정책 패리티 재생성

```powershell
C:\github\drive-diary\talk-rec\model-create\.venv\Scripts\python.exe -X utf8 -B talkrec/tools/export-policy-cases.py --reference C:\github\drive-diary\talk-rec\model-create
```

`synth/policy_cases.py`가 직접 지은 자료임을 T56 문서와 소스에서 확인했다. 기준은 생성기의 목표 정답이 아니라 **Python `policy.gate`가 실제 반환한 값**이다. T56 2,140건 + 경계/앱 신호 52건에서 block/drop_lines/retro_delete/reasons 전체를 비교한다. 규칙 파일 SHA256도 비교한다. 실제 전사는 이 자료에 없다.
