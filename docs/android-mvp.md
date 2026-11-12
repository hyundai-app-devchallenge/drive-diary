# 동반차 (Dongbancha)

차 안에서 함께 탄 사람들과 여행의 추억(음성 메모, 사진, 질문, 메모)을 기록하는 Pleos Playground용 Android Automotive 앱 MVP.

> ⚠️ **현재 상태:** 빌드(debug APK)와 Compose Preview 렌더링까지만 확인되었습니다.
> **Pleos 에뮬레이터와 실차에서는 아직 검증하지 않았습니다.** 아래 체크리스트는 검증을 위한 것입니다.

## 구현 범위

| 구분 | 기능 | 방식 |
|---|---|---|
| Pleos SDK 사용 | 차대번호(VIN), 누적 주행거리 조회와 변경 감지 | `ai.pleos.playground:Vehicle:2.3.2`, 읽기만 함 |
| AAOS 기본 API | 주행 중 화면 제한 | `CarUxRestrictionsManager` |
| 일반 안드로이드 기능 | 음성 녹음·재생, 사진 첨부, 다이어리 저장 | MediaRecorder, Photo Picker, Room |
| 대체 동작 | Pleos 연결이 실패하면 Mock 모드로 전환 | 6초 안에 응답이 없거나 VIN·주행거리 조회가 모두 실패할 때 |
| 일반 안드로이드 기능 | GPS 위치 수집, GPS 주행거리, 이동 타임라인(출발·정차·다시 출발·도착) | `LocationManager`(GPS_PROVIDER, 3초 간격), Room `location_points` 테이블 |
| **구현 안 됨** | 내비, 음성 인식(STT), 음성 안내(TTS), 기어 정보 | — |

- 차량 제어(Set) API와 `CONTROL_*`/`PRIVATE_*` 권한은 사용하지 않습니다.
- SDK의 Gear API는 deprecated이고 공식 도메인 목록에 없어서 쓰지 않았습니다.
- 위치는 일반 런타임 권한(`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`)만 사용합니다. Google Play 서비스(FusedLocation)는 쓰지 않습니다.
- 위치 기록이 추가되면서 DB 버전이 1 → 2로 올라갔습니다. `MIGRATION_1_2`가 `location_points` 테이블만 새로 만들기 때문에 기존 다이어리는 그대로 남습니다.

## 준비

```bash
./gradlew installDebug
adb logcat -s PleosVehicle DrivingSafety VoiceRecorder VoicePlayer LocationTracker
```

> 💡 **Pleos 에뮬레이터 주의:** 앱이 user 0이 아니라 **user 10**에서 실행됩니다. 그래서 Android Studio의 ▶ Run만 누르면 새 코드가 반영되지 않을 수 있습니다(옛 코드가 계속 실행됨). 아래처럼 설치 후 user 10으로 다시 실행하세요.
> ```bash
> ./gradlew installDebug && adb shell am force-stop --user 10 com.dongbantest.app && adb shell am start --user 10 -n com.dongbantest.app/.MainActivity
> ```
> `./gradlew`가 permission denied로 실행되지 않으면 `sh ./gradlew ...`로 실행하세요.

로그는 주로 **실패할 때** 남습니다. 성공 여부는 홈 화면의 차량 정보 카드(연결 상태, 차대번호, 주행거리, 주행 상태)로 판단하세요.

## 기능별 검증 체크리스트

### 1. Pleos 연결과 권한 (가장 먼저 확인)
- **확인 방법:** 앱을 처음 실행할 때 권한 요청 창이 뜨는지 봅니다. 그다음 아래 명령으로 권한이 실제로 허용됐는지 확인합니다.
  ```bash
  adb shell dumpsys package com.dongbantest.app | grep -i pleos
  adb shell pm list permissions -f | grep -A4 "pleos.car"
  ```
- **성공:** `granted=true`가 나오고, 카드 상태가 "Pleos 연결 중…"에서 연결됨으로 바뀝니다.
- **실패:** "Pleos 차량 연결 실패 → Mock 모드"와 원인이 표시됩니다. 로그에 `ERROR_SECURITY_PERMISSION`이 있으면 권한 문제입니다.
- **알아둘 점:** 권한의 보호 수준이 `signature`나 `privileged`이면 일반 앱은 요청해도 받을 수 없습니다. 공식 문서에서 확인되지 않은 부분이라 꼭 확인해야 합니다.

### 2. 차대번호(VIN)
- **성공:** `••••••••1234`처럼 마지막 4자리만 보입니다.
- **실패:** "제공되지 않음"이 뜨거나, 아래쪽 메시지에 `VIN: ...` 오류가 나옵니다.

### 3. 누적 주행거리
- **확인 방법:** 처음 값이 나오는지 본 뒤, 에뮬레이터에서 주행거리 값을 바꿔서 **화면 숫자가 따라 바뀌는지** 확인합니다. 바뀌어야 변경 감지(`registerDistanceDriven`)가 동작하는 것입니다.
  ```bash
  # 표준 AAOS 명령(PERF_ODOMETER). Pleos 에뮬레이터에서 되는지는 확인하지 않음
  adb shell cmd car_service inject-vhal-event 0x11600204 12400
  ```
- **단위 확인:** 표시값이 km인지 mile인지 실제 차량 값과 비교합니다. SDK의 단위는 아직 확인하지 않았습니다.

### 4. Mock으로 자동 전환
- **확인 방법:** 일반 폰 에뮬레이터에서 실행하면 "Mock" 표시와 "모의 주행 (테스트)" 스위치가 나와야 합니다.
- **성공:** Pleos 에뮬레이터에서는 반대로 Mock이 **나오지 않아야** 합니다. 거기서 Mock이 뜬다면 연결이 실패한 것입니다.

### 5. 주행 중 화면 제한 (CarUxRestrictions)
- **확인 방법:** 에뮬레이터의 Extended Controls → Car data에서 기어를 D로 두고 속도를 올리거나, 아래 명령을 씁니다.
  ```bash
  adb shell cmd car_service inject-vhal-event 0x11400400 8    # 기어 D
  adb shell cmd car_service inject-vhal-event 0x11600207 15   # 속도 15m/s
  ```
- **성공:** 주행 상태가 "주행 상태 (CarUx)"로 바뀌고 "(모의)"가 붙지 않습니다. 또 다음 세 가지가 확인돼야 합니다.
  - 메모 입력과 사진 기능이 숨겨짐
  - 다이어리 목록이 3개까지만 보임
  - 정차하면 원래대로 돌아옴
- **실패:** "(모의)"가 계속 보이면 CarUx에 연결되지 않은 것입니다. 로그에서 `CarUxRestrictions unavailable`을 찾아보세요.

### 6. 음성 녹음과 재생
- **확인 방법:**
  1. 에뮬레이터에서 Extended Controls → Microphone → "Virtual microphone uses host audio input"을 켭니다. **이걸 안 켜면 무음 파일이 만들어집니다.**
  2. 동승자 동의 창 → 마이크 권한 → 녹음 → 정지 → 재생 순서로 해 봅니다.
  3. 파일이 실제로 저장됐는지 확인합니다.
     ```bash
     adb shell run-as com.dongbantest.app find . -type f -newer databases
     ```
- **추가 확인:** 녹음 중에 다른 화면으로 가면 "화면을 벗어나 녹음을 취소했어요"가 떠야 합니다. 실차에서는 차량 마이크로 녹음되는지, 재생 소리가 차량 스피커로 나오는지도 확인합니다.

### 7. 사진 첨부
- **위험한 부분:** AAOS나 Pleos에는 **사진 선택 화면이나 갤러리 앱이 없을 수 있습니다.** 버튼을 눌렀을 때 아무 반응이 없거나 앱이 죽는지 꼭 확인하세요. 안 된다면 휴대폰 연동 같은 다른 방식이 필요합니다.

### 8. 여행 거리 기록과 저장 유지
- **확인 방법:**
  1. 여행을 시작하고, 주행거리를 바꾼 다음(3번 명령), 여행을 종료합니다.
  2. 다이어리 상세 화면에서 거리와 "차량 주행거리계" 표시가 맞는지 봅니다.
  3. 앱을 강제 종료하고 다시 열어서 기록이 남아 있는지 확인합니다.
     ```bash
     adb shell am force-stop com.dongbantest.app
     ```

### 9. GPS 위치 기록, 주행거리, 타임라인
- **확인 방법:**
  1. 여행을 시작하면 여행 화면에서 위치 권한 요청 창이 떠야 합니다. 허용합니다.
  2. 에뮬레이터에서 위치를 움직입니다. Extended Controls → Location에서 경로(Routes)를 재생하거나, 아래 명령으로 좌표를 몇 번 바꿉니다(경도, 위도 순서).
     ```bash
     adb emu geo fix 126.97796 37.56654
     adb emu geo fix 126.97796 37.56744   # 약 100m 북쪽
     ```
  3. 위치를 한곳에 3분 이상 두었다가 다시 움직이고, 여행을 종료합니다.
- **성공:**
  - 여행 화면 정보 카드에 "🛣️ GPS 주행거리 X km"와 마지막 이벤트가 보이고, 이동할 때마다 거리가 늘어납니다.
  - 다이어리 상세 화면에 "GPS로 기록한 거리"와 `출발 → 정차 → 다시 출발 → 도착` 타임라인이 보입니다.
  - 앱을 강제 종료했다가 다시 열어도 거리와 타임라인이 남아 있습니다.
- **권한을 거부하면:** "위치 권한이 없어 주행 경로를 기록하지 않아요."가 뜨고, 위치 없이 나머지 기능은 그대로 동작해야 합니다.
- **거리 계산 규칙:** 정확도가 30m보다 나쁜 점은 버리고, 5m 미만의 흔들림은 무시합니다. 순간 속도가 70m/s(약 250km/h)를 넘는 점은 위치가 튄 것으로 보고 거리에 넣지 않습니다.
- **타임라인 규칙:** 반경 30m 안에 3분 이상 머무르면 정차로 봅니다. 출발 지점에서 기다린 시간은 정차로 치지 않습니다.
- **실차 확인:** 차량의 GPS 신호가 앱에 들어오는지, GPS 거리와 차량 주행거리계 거리가 비슷한지 비교합니다.

## 에뮬레이터 시연 (디버그 명령)

Pleos 에뮬레이터에서는 여행 화면의 주행 상태와 길 리본이 실제처럼 바뀌지 않습니다. 그래서 디버그 빌드에 adb 명령으로 값을 넣는 기능을 두었습니다. 명령을 보내지 않으면 실제 차량·내비 값을 그대로 쓰고, 출시용(release) 앱에는 들어가지 않습니다.

### 에뮬레이터에서 안 되는 이유

기어와 내비는 서로 따로 움직입니다. 기어를 D로 바꾼다고 내비가 움직이지는 않습니다.

| 화면에 보이는 것 | 실제 차 | 에뮬레이터 | 시연 방법 |
|---|---|---|---|
| 주행 중 / 정차 중 | 기어를 바꾸면 차량 시스템(CarUx)이 알려 줍니다 | Virtual Console에서 기어를 바꿔도 계기판과 앱까지 전달되지 않습니다 | `DEBUG_GEAR` |
| 길 리본(차 아이콘 위치) | 차가 달리면 내비의 남은 거리가 줄고, 앱이 그 값으로 진행률을 계산합니다 | 차가 움직이지 않아 남은 거리가 그대로입니다. GPX 경로를 재생해도 내비는 따라가지 않습니다 | `DEBUG_PROGRESS` |
| 도착(여행 자동 종료) | 목적지에 닿으면 내비가 도착 신호를 보냅니다 | 도착 신호가 오지 않습니다 | `DEBUG_ARRIVAL`, 또는 `DEBUG_PROGRESS` 100 |

명령은 앱 화면(오른쪽)만 바꿉니다. 에뮬레이터 차량 화면(왼쪽)의 P R N D 표시는 바뀌지 않습니다.

### 0. 준비 (터미널을 새로 열 때마다)

명령을 짧게 쓰려고 adb 위치를 이름으로 저장합니다.

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
```

에뮬레이터가 연결됐는지 확인합니다. `emulator-5554 device`가 나오면 됩니다.

```bash
$ADB devices
```

### 1. 기어 (주행 중 / 정차 중)

D, 주행 중으로

```bash
$ADB shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_GEAR -n com.dongbantest.app/.vehicle.DebugGearReceiver --es gear D
```

P, 정차 중으로

```bash
$ADB shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_GEAR -n com.dongbantest.app/.vehicle.DebugGearReceiver --es gear P
```

끄기 (차량 값으로 돌아갑니다)

```bash
$ADB shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_GEAR -n com.dongbantest.app/.vehicle.DebugGearReceiver --es gear off
```

- 기어 기록(정차, 다시 출발)은 여행 탭이 열려 있을 때 저장됩니다.
- P로 두고 2분이 넘으면 정차로 기록됩니다.

### 2. 길 리본 (차 아이콘 위치)

원하는 위치로 옮기기 (끝의 숫자 0~100을 바꿉니다)

```bash
$ADB shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_PROGRESS -n com.dongbantest.app/.navi.DebugProgressReceiver --ei percent 50
```

쭉 달리기 (0% → 100%, 5%씩 1초마다. 멈추려면 Ctrl + C)

```bash
for p in $(seq 0 5 100); do $ADB shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_PROGRESS -n com.dongbantest.app/.navi.DebugProgressReceiver --ei percent $p > /dev/null; sleep 1; done
```

- `seq 시작 간격 끝`을 바꿉니다. 예: `seq 0 5 50`은 절반까지, `seq 50 5 100`은 나머지입니다.
- `sleep 1`을 `sleep 3`으로 바꾸면 더 천천히 갑니다.
- **100을 보내면 도착 신호도 함께 보내** 여행이 끝납니다. 끝내지 않으려면 `seq 0 5 95`처럼 100 앞에서 멈춥니다.

끄기 (내비 값으로 돌아갑니다)

```bash
$ADB shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_PROGRESS -n com.dongbantest.app/.navi.DebugProgressReceiver
```

### 3. 도착 (여행 끝내기)

```bash
$ADB shell am broadcast --user 10 -a com.dongbantest.app.DEBUG_ARRIVAL -n com.dongbantest.app/.navi.DebugArrivalReceiver --es name "경기도판교테크노밸리"
```

- 여행이 끝나고 도착 화면이 나옵니다. "아직 여행 중이에요"를 누르면 여행이 다시 열립니다.

### 4. 명령이 들어갔는지 확인

```bash
$ADB logcat -s NaviMonitor DrivingSafety VehicleSignal
```

- `진행률 흉내: 50` → 길 리본 명령이 들어갔습니다.
- `주행 상태 흉내: true` → 기어 D가 들어갔습니다.
- `차량 신호: GEAR_NOT_PARKED` → 기어 기록이 저장됐습니다.

### 시연 순서 예시

내비에 목적지를 찍고 여행을 시작한 뒤, 여행 탭을 연 상태에서 진행합니다.

1. 기어 D → "주행 중"
2. 쭉 달리기 `seq 0 5 50` → 절반까지 이동
3. 기어 P → 정차 장면
4. 기어 D → 다시 출발
5. 쭉 달리기 `seq 50 5 100` → 끝까지 이동하고 도착 화면

처음부터 다시 하려면 두 명령을 모두 끄거나 앱을 다시 켭니다.

## 알려진 문제
- **거리 계산 오류 가능성:** 여행 종료 때는 그 시점에 쓰는 데이터 소스의 주행거리를 그대로 저장합니다. 그래서 시작은 실제 차량 값이고 종료는 Mock 값(12,345km 근처)인 경우, 말이 안 되는 거리가 계산될 수 있습니다.
- **느린 환경에서의 타임아웃:** 연결 대기 시간이 6초(`CONNECT_TIMEOUT_MS`)라서, 에뮬레이터가 느리면 실제로는 연결이 되는데도 Mock으로 넘어갈 수 있습니다. 이럴 때는 홈 화면의 재연결 버튼을 눌러 보세요.
- **위치 기록은 앱이 켜져 있을 때만:** 포그라운드 서비스가 없어서 앱이 백그라운드로 가거나 종료되면 위치 기록이 멈출 수 있습니다. 여행 화면에 다시 들어가면 기록이 이어집니다.
- **GPS 신호만 사용:** `GPS_PROVIDER`만 쓰기 때문에 터널, 지하 주차장처럼 GPS가 안 잡히는 곳에서는 그 구간이 기록되지 않습니다.
- **에뮬레이터 기본 위치:** 에뮬레이터는 처음에 미국(구글 본사) 좌표로 시작합니다. 서울로 위치를 바꾸면 위치가 튄 것으로 처리되어 거리에 들어가지 않습니다(정상).
- **명령 호환성:** 위의 `adb ... car_service` 명령들은 표준 AAOS 기준입니다. Pleos 에뮬레이터에서 막혀 있을 수 있으니, 안 되면 Extended Controls의 Car data 화면을 쓰세요.
- **Virtual Console 기어 미반영:** Pleos 에뮬레이터의 Virtual Console에서 기어를 바꿔도 계기판과 앱에 전달되지 않습니다. `car_service` 명령도 "non-user build 필요" 오류로 막혀 있습니다. 시연할 때는 위의 `DEBUG_GEAR` 명령을 씁니다.

## 주요 코드 위치
- `app/src/main/java/com/dongbantest/app/vehicle/PleosVehicleDataSource.kt` — Pleos Vehicle SDK 연결
- `app/src/main/java/com/dongbantest/app/vehicle/VehicleRepository.kt` — Pleos 우선 시도, 실패 시 Mock 전환
- `app/src/main/java/com/dongbantest/app/vehicle/DrivingSafetyMonitor.kt` — 주행 중 제한 감지
- `app/src/main/java/com/dongbantest/app/audio/` — 녹음과 재생
- `app/src/main/java/com/dongbantest/app/location/` — GPS 위치 수집(`LocationReader`)과 여행 중 저장(`LocationTracker`)
- `app/src/main/java/com/dongbantest/app/domain/location/` — 주행거리 계산(`DistanceCalculator`), 타임라인 생성(`TimelineBuilder`)
- `app/src/test/java/com/dongbantest/app/LocationLogicTest.kt` — 거리·타임라인 단위 테스트
