# T44 — 실제 클립의 Pleos 온디바이스 일괄 전사

2026-11-11 구현 및 T44c 수정. 커밋하지 않았다. 앱 일괄 서비스와 호스트 명령을 추가했고, 승인된 실제 클립 25개의 PCM 변환까지 직접 확인했다. 초기 구현 당시 샌드박스에서는 adb 실행·Android SDK 읽기 제한으로 빌드·설치를 검증하지 못했다. 이후 사용자가 샌드박스 밖에서 **ANDROID_HOME 설정 후 빌드·설치 성공**, 다중 사용자 AVD의 내부 저장소 stdin 전송과 MD5 일치를 확인했다. T44c 변경 APK의 빌드·설치·실제 전사는 사용자가 아래 명령으로 확인한다. 인식 품질이나 200/400% 속도의 결과 동일성에 관한 결론은 아직 없다.

## 변경 파일

| 파일 | 내용 |
| --- | --- |
| `test/stt/src/main/java/com/drivediary/sttprobe/BatchSttService.java` | 새 일괄 서비스 |
| `test/stt/src/main/java/com/drivediary/sttprobe/ProbeActivity.java` | 시작·정지 버튼, 기존 테스트의 배치 실행 중 중복 시작 방지 |
| `test/stt/src/main/AndroidManifest.xml` | dataSync 서비스·권한, CPU wake lock 권한 |
| `test/stt/build.gradle.kts` | debug 빌드에서만 서비스 외부 시작 허용 |
| `model-create/demo/pleos.py` | prepare/push/run/pull/collect/stats/clean |
| `model-create/tests/test_demo_pleos.py` | 새 테스트 30개 |
| `docs/model-create/T44-pleos-batch-stt.md` | 이 보고서 |

기존 `demo/*.py`, `pipeline/`, `tools/`, `common/`, 병렬 작업의 `demo/asr/`, `synth/`는 수정하지 않았다. 기존 합성 입력과 마이크 버튼을 유지했다.

## 앱 동작과 시간 제한

입출력은 `new File(getFilesDir(), "batch")`, 즉 현재 앱 사용자의 내부 저장소 `files/batch/`다. 사용자 10에서는 `/data/user/10/com.drivediary.sttprobe/files/batch/`에 해당한다. 호스트는 `run-as com.drivediary.sttprobe --user <id>`로 접근한다. 이름순으로 `.pcm`을 처리하고 `only` extra가 있으면 파일명 접두사로 거른다. 파일은 비어 있지 않은 16kHz s16le mono이며 원본 길이가 95초 이하여야 한다. 형식에 관한 헤더가 없는 raw PCM이므로 실제 샘플레이트·채널 수를 앱에서 자동 판별하지 않는다.

각 파일에 **새 `SpeechToText(Mode.ON_DEVICE)` 인스턴스**를 초기화하고 `Locale.KOREA`를 설정한다. 앞 클립의 인식 상태·오류가 다음 클립에 이어지는 것을 줄이기 위해 파일 간 재사용하지 않는다. 이 선택의 실기 안정성은 아직 검증하지 못했다. 초기화 제한은 25초, 전송은 최대 3,200바이트(100ms)씩이다. `speed_pct=100/200/400`이면 목표 청크 간격은 각각 100/50/25ms다. 누적 오디오 길이에 맞춘 절대 목표 시각으로 예약한다. SDK 호출이나 파일 저장이 늦으면 실제 간격은 늘거나 후속 청크가 가까워질 수 있다.

`padding_ms=500`이면 파일 앞뒤 각각 500ms 무음을 메모리에서 붙인다. 기본값은 0이다. 원본 PCM은 바꾸지 않는다. 마지막 청크 뒤 예정된 오디오 시간까지 기다린 다음 `completeAudioSend()`를 한 번 호출한다. 그 뒤 **15초의 고정 결과 대기 구간**을 둔다. 마지막 발화의 final이나 `onEndedRecognition()`을 전체 스트림의 끝으로 간주하지 않는다. 무음 클립의 final 0건도 정상 완료로 남겨 통계에 포함한다. 전송 시작 이후 별도의 상한은 예정 전송 시간 +35초다. SDK 오류는 해당 파일을 종료하고 다음 파일로 진행한다. TSV 쓰기 실패는 배치를 중단한다.

서비스는 **`dataSync` foreground service**다. 로컬 파일 처리는 Android가 명시한 dataSync 용도이며, `shortService`를 사용하지 않아 약 3분 제한에 걸리지 않는다. Android 15 이상에서는 dataSync가 백그라운드에서 앱별 24시간 중 합계 6시간 제한을 받는다. 약 40분 배치는 이 범위 안이며 `onTimeout(int,int)`에서 종료한다. API 34 AVD의 실제 동작은 이번에 확인하지 못했다. 화면이 꺼져도 CPU가 전송을 계속할 수 있도록 최대 6시간의 partial wake lock을 잡고 종료 시 해제한다. 반복 실험으로 6시간 예산을 소진한 최신 Android 기기에서는 앱을 전면으로 열고 다시 실행해야 한다. [Android 서비스 유형](https://developer.android.com/develop/background-work/services/fgs/service-types), [전경 서비스 시간 제한](https://developer.android.com/develop/background-work/services/fgs/timeout)

기존 방식대로 저우선순위 알림 채널을 만든다. 화면 버튼은 알림 권한이 없으면 요청하고 다시 누르게 한다. 알림의 Stop, 앱의 Stop batch PCM STT, `am stopservice`로 중단할 수 있다. debug APK에서만 exported=true이고 release는 false다. 이 서비스는 별도 실험용 앱의 adb 입력 경로이며 다른 STT 실험과 동시에 실행하지 않는다.

## 출력 계약

각 원본 `이름.pcm`에 대해 같은 디렉터리에 `이름.tsv`를 쓴다. UTF-8이며 열은 다음과 같다.

```text
kind    received_ms    audio_ms    text    metadata
```

실제 구분자는 탭이다. 역슬래시·탭·CR·LF를 각각 `\\`, `\t`, `\r`, `\n`으로 이스케이프한다.

- `partial` / `final`: 모든 `onUpdated` 콜백을 완료 여부에 따라 기록한다.
- `error`: SDK 오류, 초기화·전송 제한, 입력 오류, 서비스 중단, 정리 예외의 코드다.
- `event`: 초기화 성공, ready/started/ended, EPD, RMS, 입력 전송 완료 이벤트다. 텍스트 열은 비우고 metadata에 이벤트 이름과 값을 남긴다.
- `received_ms`: 전송 시작 기준 단조 시계 경과 ms. 전송 전 초기화 이벤트는 0이다. 콜백 스레드에서 수신 시각을 잡는다.
- `audio_ms`: 그 순간 SDK에 제출한 오디오 위치의 ms 근사이며 무음 패딩을 포함한다.
- SDK의 `ResultListener`는 텍스트·완료 여부와 EPD/RMS 등을 제공한다. 이 인터페이스에 없는 신뢰도·감지 언어를 만들어 넣지 않는다. EPD 값은 단위 추정을 적용하지 않고 원래 숫자를 보존한다.

TSV는 시작 때 이전 결과를 교체하고, 전송·결과 대기 중 1초마다 및 파일 종료 시 `AtomicFile`로 저장한다. 강제 프로세스 종료 시 마지막 체크포인트 이후 콜백은 잃을 수 있다. `batch-status.json`도 원자적으로 쓰며 `run_id`, `state`, `reason`, `current`, `completed`, `total`, `errors`, `speed_pct`, `padding_ms`를 담는다. 시작 직후 입력 검사와 SDK 초기화 전에 `starting`을 쓴다. 입력이 없으면 `state=error, reason=no_input_pcm`, 목록을 읽지 못하면 `state=error, reason=input_directory_unreadable`로 종료한다. 상태 파일 자체를 쓸 수 없는 경우에는 `status_write_failed`를 Logcat에 남긴다. completed는 오류 종료 파일도 포함한 처리 수이고, 개별 파일 오류가 있어도 전체 순회가 끝나면 state는 completed다. errors도 함께 확인해야 한다. 진행 상태는 파일 시작·종료 시 갱신한다. 앱의 `DriveDiarySttProbe` Logcat에는 상태·수치·고정 원인 코드만 남긴다. 전사 본문이나 예외 메시지를 출력하지 않는다.

PCM 공급 방식은 [Pleos 오디오 전송 문서](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)를 따른다. SDK 자체의 다른 Logcat 태그에서 무엇을 출력하는지는 이번에 검증하지 못했다.

호스트 파일은 전부 `model-create/.local/` 아래에 둔다.

| 경로 | 내용 |
| --- | --- |
| `.local/demo/pleos/pcm/clip_<해시>.pcm` | 변환된 원본 PCM, 무음 패딩 없음 |
| `.local/demo/pleos/manifest.json` | 이름 ↔ 원본 id 및 변환 후 seconds |
| `.local/demo/pleos/raw/pleos*/run.json` | 실행 ID·속도·패딩·대상 대응표 스냅샷 |
| `.local/demo/pleos/raw/pleos*/*.tsv` | 속도별 원본 콜백 |
| `.local/demo/pleos/raw/pleos*/batch-status.json` | pull 완료 후 저장한 기기 상태 |
| `.local/demo/asr/pleos.jsonl` | 모든 속도 구성의 collect 결과 |
| `.local/demo/pleos/stats.json` | 본문 없는 통계 |

prepare는 `sample.jsonl`의 origin=approved만 고른다. 클립 WAV 파일명은 기존 `tools.listen.storage.clip_name()` 규칙을 그대로 사용한다. PCM 이름은 id의 SHA-256 앞 32자리로 만들어 Windows·adb 셸에 안전하게 쓴다. `--ids`는 공백으로 여러 id를 받으며 승인되지 않은 id는 거부한다. `--limit`은 선택한 표본 순서의 앞 N개다. 이전 PCM이 로컬 디렉터리에 남아 있어도 push는 manifest의 파일만 전송한다.

기기를 사용하는 명령은 시작 시 `am get-current-user`로 사용자 ID를 조회한다. `--user <id>`로 덮어쓸 수 있으며 로컬 전용 prepare/collect/stats는 adb가 필요 없다. push는 **기존 기기 batch 디렉터리를 지우고 현재 선택만 전송**한다. `exec-in run-as … sh -c "cat > files/batch/<이름>.pcm"`의 stdin에 열린 로컬 파일 객체를 넘겨 스트리밍하고, 파일마다 `run-as … sh -c 'wc -c < …'`로 크기를 확인한다. Windows 셸 리다이렉션이나 `/data/local/tmp` 중간 파일을 쓰지 않는다. 기존 결과가 필요하면 먼저 pull한다. clean은 지정 사용자의 서비스를 정지하고 dumpsys의 해당 `u<id>` ServiceRecord가 사라진 뒤 내부 `files/batch`를 삭제한다. 이전 버전의 사용자 0 잔여물인 `/sdcard/Android/data/com.drivediary.sttprobe/files/batch`도 shell로 삭제한다. 이 마이그레이션 정리 외에는 외부 저장소를 사용하지 않는다. clean은 호스트 PCM·결과를 보존한다. adb 호출은 `adb()` 하나로 모았고 stdout/stderr는 콘솔로 전달하지 않는다.

run은 `am start-foreground-service --user <id>`로 시작하며 `start-service → wait-starting → wait-completed` 단계를 출력한다. UUID 실행 ID가 같은 상태만 받아 이전 상태 파일을 무시한다. 기본 15초(`--start-timeout`) 안에 `starting` 이상의 상태가 없으면 전체 timeout까지 기다리지 않고 `stage=run/wait-starting`, “서비스가 뜨지 않음”으로 실패하고 정지를 요청한다. `starting`을 확인하면 전체 처리 제한 시간까지 기다리며 error/failed/cancelled는 즉시 실패한다. 완료 수가 준비한 대상 수와 다르면 실패한다. 제한 시간 만료 시 지정 사용자의 서비스 정지를 요청한다. adb 명령 자체에는 최대 30초와 더 작은 전체 timeout을 적용한다. 시작 요청은 start-timeout, 상태 조회는 시작/전체 제한까지 남은 시간으로도 제한한다. 실패 후 정지 요청은 별도 adb 호출이므로 연결 장애 시 그 호출 시간은 추가될 수 있다. 이미 다른 배치가 실행 중이면 앱은 중복 시작을 무시하므로 먼저 중지하거나 clean/push를 수행한다. run 완료 후에는 해당 사용자의 서비스 종료도 확인한다. pull은 실행 ID·사용자·완료 상태를 확인하고 `exec-out run-as … cat`으로 TSV 바이트를 그대로 받아 원자적으로 저장한다. 새 run만 수행한 뒤 이전 TSV를 잘못 collect하지 못하도록 pull의 상태 스냅샷을 검사한다.

collect의 각 행은 `id`, `config`, `lines`, `seconds`, `callbacks`, `errors`다. final 콜백 하나가 lines 한 개이고 partial은 포함하지 않는다. callbacks는 final 수다. 줄의 end는 당시 제출 위치에서 앞 패딩을 빼고 원본 길이로 자른 값, start는 이전 final의 end(첫 줄은 0)다. 위치가 없으면 null이다. **이는 발화 경계가 아닌 오디오 공급 위치의 근사**여서 특히 가속 전송·전송 후 몰려온 결과는 같은 end나 길이 0인 구간을 가질 수 있다. seconds는 원본 클립의 오디오 길이다. 깨진 TSV 행·UTF-8·누락 파일은 errors에 포함한다.

100%는 config=`pleos`, 200%는 `pleos-200`, 400%는 `pleos-400`이다. 같은 속도의 재실행은 해당 raw 디렉터리를 대체하므로 별도 보관하려면 먼저 디렉터리를 복사해야 한다. 같은 속도에서 패딩 조건만 바꾸면 마지막 실행만 남는다.

stats는 final 수·글자 수·오류 수·결과 없는 클립 수를 전체 및 config별로 계산한다. Whisper 비교는 sample의 turns 문자열 길이 합에 대한 Pleos final 문자열 길이 합의 비율뿐이다. 공백·문장부호·줄바꿈도 글자로 센다. WAV의 앞뒤 여유 구간과 Whisper 표본 범위가 다를 수 있어 이 비율을 정확도 지표로 해석하면 안 된다. 콜백 지연은 `received_ms - audio_ms * 100 / speed_pct`의 개수·평균·최소·최대다. 전송 목표 대비 지연이며 발화 끝 기준 지연이 아니다. 청크 제출 시점의 근사 때문에 음수일 수도 있다. 통계 파일과 콘솔에는 본문을 넣지 않는다.

## 밖에서 실행할 명령 — PowerShell

AVD `emulator-5554`를 켠 상태에서 아래 순서로 실행한다. 빌드 환경은 기존 프로젝트의 SDK Platform 37.0, Build Tools 36.0.0, Gradle 9.6.0, daemon JDK 25를 따른다. SDK 접근·설치는 샌드박스 밖의 기존 Android Studio 환경에서 수행한다.

```powershell
# 1. 빌드
Set-Location C:\github\drive-diary\talk-rec\test\stt
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"
$env:ANDROID_USER_HOME = "$env:USERPROFILE\.android"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }

# 2. 설치 및 알림 권한. 기존 합성·마이크 실험은 종료해 둔다.
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 devices
$androidUser = (& $adb -s emulator-5554 shell am get-current-user).Trim()
if ($LASTEXITCODE -ne 0 -or $androidUser -notmatch '^\d+$') { throw 'Android user query failed' }
& $adb -s emulator-5554 install --user $androidUser -r .\build\outputs\apk\debug\DriveDiarySttProbe-debug.apk
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
& $adb -s emulator-5554 shell pm grant --user $androidUser com.drivediary.sttprobe android.permission.POST_NOTIFICATIONS

# 3. clean — 내부 batch와 이전 사용자 0 외부 저장소의 잔여 PCM도 삭제
Set-Location C:\github\drive-diary\talk-rec\model-create
.\.venv\Scripts\python.exe -m demo.pleos clean --serial emulator-5554 --user $androidUser

# 4. prepare — 승인된 모든 클립. 첫 시험만 하려면 --limit 1 추가
.\.venv\Scripts\python.exe -m demo.pleos prepare

# 5. push — run-as 내부 저장소 전송 및 파일별 크기 검증
.\.venv\Scripts\python.exe -m demo.pleos push --serial emulator-5554 --user $androidUser

# 6. run — 시작 확인 15초, 100%, 앞뒤 500ms 패딩, 전체 대기 1시간
.\.venv\Scripts\python.exe -m demo.pleos run --serial emulator-5554 --user $androidUser --start-timeout 15 --speed-pct 100 --padding-ms 500 --timeout 3600

# 7. pull — run이 성공한 뒤 실행
.\.venv\Scripts\python.exe -m demo.pleos pull --serial emulator-5554 --user $androidUser --speed-pct 100

# 8. collect
.\.venv\Scripts\python.exe -m demo.pleos collect

# 9. stats — 수치만 저장
.\.venv\Scripts\python.exe -m demo.pleos stats
```

먼저 1개만 시험했다면 전체 실행 전에 `prepare`를 `--limit` 없이 다시 실행한 뒤 push부터 반복한다. id 선택은 `prepare --ids <id1> <id2>`다. `--adb`로 실행 파일 경로를 명시할 수도 있다. 위 명령은 조회한 사용자를 고정한다. 기기 명령에서 `--user`를 생략하면 매 명령 시작 시 현재 사용자를 자동 조회한다.

속도 비교는 같은 prepare 결과와 패딩 조건을 유지한다. 각 속도에서 **pull을 마친 다음** 다음 속도를 실행한다. clean을 이미 수행했다면 먼저 push를 다시 한다.

```powershell
.\.venv\Scripts\python.exe -m demo.pleos push --serial emulator-5554 --user $androidUser
.\.venv\Scripts\python.exe -m demo.pleos run --serial emulator-5554 --user $androidUser --speed-pct 200 --padding-ms 500 --timeout 3600
.\.venv\Scripts\python.exe -m demo.pleos pull --serial emulator-5554 --user $androidUser --speed-pct 200
.\.venv\Scripts\python.exe -m demo.pleos run --serial emulator-5554 --user $androidUser --speed-pct 400 --padding-ms 500 --timeout 3600
.\.venv\Scripts\python.exe -m demo.pleos pull --serial emulator-5554 --user $androidUser --speed-pct 400
.\.venv\Scripts\python.exe -m demo.pleos collect
.\.venv\Scripts\python.exe -m demo.pleos stats
.\.venv\Scripts\python.exe -m demo.pleos clean --serial emulator-5554 --user $androidUser
```

호스트를 거치지 않고 기기의 PCM만 처리하는 직접 명령도 가능하다. 접두사는 실제 기기 파일명에 맞춘다. 이 경로는 호스트 run.json을 만들지 않으므로 위 collect 흐름에는 `demo.pleos run`을 사용한다.

```powershell
& $adb -s emulator-5554 shell am start-foreground-service --user $androidUser -n com.drivediary.sttprobe/.BatchSttService --ei speed_pct 100 --ei padding_ms 500
# 선택적으로 끝에 --es only clip_<접두사> 추가
& $adb -s emulator-5554 shell am stopservice --user $androidUser -n com.drivediary.sttprobe/.BatchSttService
# 내부 파일 목록 (본문은 출력하지 않음)
& $adb -s emulator-5554 shell "run-as com.drivediary.sttprobe --user $androidUser sh -c 'ls files/batch'"
```

## 직접 검증한 것과 남은 검증

- 신규 테스트 **30개 통과**, 기존 클립 변환·demo CLI 회귀 테스트 2개를 포함해 **32개 통과**했다. 합성 48kHz stereo 사인파의 16kHz s16le mono 변환, 이름 대응·승인 필터·선택, TSV 이스케이프·손상·UTF-8, collect 스키마·속도·위치 없음·빈 결과, 본문 없는 통계, 가짜 adb의 push/run/pull/collect·이전 상태 무시·실패·시간 초과·정지 대기를 검증했다.
- 실제 `prepare` 성공: approved 25개, 총 **1,780.237초**, 최대 **92.03초**, PCM **56,967,584바이트**. 음성은 `.local/demo/pleos/pcm/`에만 저장했다. 실제 본문을 출력하거나 이 문서에 옮기지 않았다.
- CLI `--help` 실행 확인. 기존 `tests/test_label.py`의 알려진 실패 9개는 이번 범위에서 실행하지 않았다.
- 기본 `assembleDebug`: `C:\.gradle\...zip.lck` 상위 경로 생성 실패. 작업 공간의 별도 Gradle/Android 사용자 경로로 재시도해 플러그인 설정까지 진행했으나, Android SDK `platforms\android-37.0\package.xml` 읽기가 Access denied로 실패했다. 이어 SDK 라이선스 미승인 메시지가 나왔으나 샌드박스 접근 실패가 있어 실제 SDK 설치·라이선스 상태를 판정하지 않았다. 별도 javac 시도 역시 android.jar 읽기가 거부되어 성공하지 못했다. **앱 컴파일·APK 생성 성공을 주장하지 않는다.**
- adb `devices`: 실행 파일 자체에 Access is denied. 설치·서비스 실행·기기 결과 회수·실제 전사·200/400% 동일성·40분 지속성·기기 clean 검증은 하지 못했다. 실제 결과용 collect/stats 파일도 만들지 않았다.
- 실제 음성·전사는 assets나 소스·문서·저장소에 추가하지 않았다. 호스트의 임시 파일은 `.local/`에서 생성하고 finally로 삭제한다. `.local/demo/`에는 새 락 파일·임시 디렉터리를 남기지 않는다.

테스트 재현 명령:

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
New-Item -ItemType Directory -Force .local/fixt | Out-Null
.\.venv\Scripts\python.exe -X utf8 -B -m pytest tests/test_demo_pleos.py tests/test_listen_clips.py tests/test_demo_cli.py --basetemp .local/fixt/pt -p no:cacheprovider
```

## 실행 후 수정

실제 실행에서는 emulator-5554의 앱 빌드·설치가 성공했지만 `demo.pleos push`가 실패했다.
서비스가 실행 중이 아닐 때 `am stopservice`가 `Service not stopped: was not running.`과 종료 코드 255를
반환했고, 공통 adb 실패 검사에서 이를 오류로 올려 정리·push·시간 초과 후 정지 절차가 중단된 것이 원인이었다.

- 고정된 BatchSttService의 `stopservice` 호출만 종료 코드 실패를 허용한다. clean/push는 기존 `dumpsys` 종료 확인을 통과한 뒤에만 삭제하며, run 시간 초과 때도 255가 원래 TimeoutError를 덮지 않는다. 나머지 adb 명령의 종료 코드 검사는 유지한다.
- 삭제는 기존 `rm -rf`를 유지해 디렉터리가 없어도 성공한다. `ls` 호출에 의존하는 절차는 없다. 상태 파일은 정확히 `batch-status.json`이 아직 없다는 응답일 때만 다음 폴링을 기다린다. 연결 끊김·권한 오류 등은 즉시 실패로 표시하며, 작성 중인 불완전 JSON은 기존처럼 다시 읽는다.
- `am start-foreground-service`는 종료 코드 0이어도 stdout/stderr의 오류 응답을 확인한다. 정상 시작 안내와 무해한 경고는 성공으로 처리한다.
- 실패 메시지에 단계 이름, 예외 종류, adb 종료 코드를 표시한다. 실행 자체가 불가능하거나 시간 초과로 종료 코드를 얻지 못하면 `adb_exit=unavailable`과 원인 예외 종류를 표시한다. 현재는 `am`/`pm` 출력만 합쳐 최대 200자까지 허용하며, run-as의 목록·크기·cat stdout/stderr 및 일반 예외 메시지는 출력하지 않는다.
- 당시에는 새 CLI 옵션 없이 기존 `--serial`, `--adb`, `--timeout`을 유지했다. 아래 T44c에서 `--user`, `--start-timeout`을 추가했다.

adb subprocess를 가짜로 대체해 stopservice 255, 없는 디렉터리, 상태 파일 미생성 후 완료,
시작 거부의 stdout/stderr, 나머지 명령의 실패 검사, 진단의 본문 차단과 정상 흐름을 검증했다.
아래 두 필수 파일은 **83개 통과**했다. 실제 adb·agy 실행은 하지 않았다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
New-Item -ItemType Directory -Force .local/fixt | Out-Null
.\.venv\Scripts\python.exe -X utf8 -B -m pytest tests/test_demo_pleos.py tests/test_synth_generate.py --basetemp .local/fixt/pt -p no:cacheprovider -q
```

실제 합성 데이터 `scenes.jsonl` 12개와 `cards.jsonl`은 변경하지 않았다.
`.local/demo/`와 `.local/synth/demo/`에 락 파일·임시 디렉터리를 남기지 않았다.
기존 테스트 임시 경로 `.local/demo/pt`는 `.local/fixt/previous-demo-pt`로 옮겼다.
관련 demo·synth·skim·공통 스키마 회귀 검사는 **765개 통과, 1개 건너뜀**이었다.

### T44c — 차량용 AVD의 다중 사용자 저장소 수정

사용자가 emulator-5554(Pleos Connect, user 빌드, root 불가)에서 확인한 현재 사용자는 **10**이고 앱 프로세스는 `u10_a176`이었다. 이전 push는 shell 소유의 사용자 0 `/sdcard/Android/data/com.drivediary.sttprobe/files/batch`에 PCM을 넣었지만, 사용자 10 앱은 `/storage/emulated/10/...`을 봐 입력을 찾지 못했다. adb shell의 사용자 10 외부 저장소 접근과 run-as의 외부 저장소·`/data/local/tmp` 접근도 Permission denied였다. 이때 서비스가 상태 없이 종료되어 호스트가 전체 timeout까지 기다렸다.

해결은 **사용자별 앱 내부 저장소 + run-as**다. 사용자가 `run-as com.drivediary.sttprobe --user 10`의 작업 디렉터리가 `/data/user/10/com.drivediary.sttprobe`임을 확인했고, `exec-in` stdin으로 2.5MB 파일을 `files/batch/`에 써서 MD5 일치를 검증했다. 이 확인된 경로를 앱과 호스트에 적용했다.

- 앱은 내부 `files/batch`에 입력·TSV·상태를 모은다. 입력 검사 전에 `starting`을 쓰고, 입력 없음·목록 읽기 실패는 `error`와 고정 `reason`을 상태와 `DriveDiarySttProbe` Logcat에 남긴다. 상태 파일 자체를 쓸 수 없으면 Logcat에 `status_write_failed`를 남기고 종료한다. 화면 버튼·안내·알림도 내부 저장소로 맞췄다.
- 호스트는 현재 사용자 자동 조회와 `--user` 덮어쓰기를 지원한다. 전송·크기 확인·읽기·삭제는 지정 사용자의 run-as를 사용하고, 서비스 시작·정지는 `--user`를 명시한다. dumpsys는 해당 사용자 레코드만 기다린다. pull은 바이트를 보존하고 다른 사용자 실행의 결과는 거부한다.
- `run --start-timeout 15`는 실행 ID가 맞는 상태가 없으면 시작 단계에서 실패한다. 오래된 상태·깨진 JSON·없는 상태 파일은 시작 확인으로 인정하지 않는다. 시작이 확인된 배치는 전체 timeout을 사용한다. 단계와 허용된 원인 코드만 출력한다.
- clean은 내부 batch와 이전 사용자 0 외부 batch를 함께 정리한다. 사용자가 보고한 기존 PCM 2개는 새 clean 실행 시 삭제 대상이다. 이번 작업에서 실제 기기의 파일을 삭제하지는 않았다.

변경 파일은 `BatchSttService.java`, `ProbeActivity.java`, 앱 `README.md`, `demo/pleos.py`, `tests/test_demo_pleos.py`, 이 문서다. 다른 demo·synth·pipeline·tools 소스는 수정하지 않았다. 빌드 명령에 `ANDROID_HOME` 설정을 포함했고 실행 목록을 **빌드 → 설치 → clean → prepare → push → run → pull → collect → stats**로 갱신했다. 커밋하지 않았다.

가짜 adb/subprocess와 가상 시계로 **Pleos 테스트 76개**, 관련 회귀를 합해 **116개 통과**했다. 사용자 조회·덮어쓰기, 사용자별 run-as/서비스 명령, 2.5MiB 합성 바이트의 파일 stdin 전달, 전송 크기 불일치 실패, UTF-8/바이너리 pull 보존, 기본 15초 및 사용자 지정 시작 제한, 남은 시간에 맞춘 adb 상태 조회 제한, error 즉시 실패, 이전 외부 경로 정리, 다른 사용자의 서비스 무시를 검증했다. CLI help도 확인했다. T44c APK 빌드·설치·실제 AVD 실행은 이번 작업에서 수행하지 않았으며 사용자가 위 명령으로 검증한다. 실제 전사 본문은 로그·문서에 추가하지 않았고 `.local/demo/` 아래에 새 락·임시물을 만들지 않았다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
.\.venv\Scripts\python.exe -X utf8 -B -m pytest tests/test_demo_pleos.py tests/test_synth_generate.py tests/test_listen_clips.py tests/test_demo_cli.py --basetemp .local/fixt/pt -p no:cacheprovider -q
```
