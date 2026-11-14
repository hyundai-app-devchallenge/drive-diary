# M02 — 들은 노래 기록 저장

2026-11-14, `feat/talk-rec-integration`. 커밋하지 않았다.

## 변경한 파일

경로는 저장소 루트 기준이다. 시작 시 있던 M01 변경과 `ui/v3`의 다른 세션 변경은 그대로 두었다.

새 파일:

- `app/src/main/java/com/dongbantest/app/music/TrackPlayLog.kt`: Android 의존성이 없는 상태 머신.
- `app/src/main/java/com/dongbantest/app/music/MusicTracker.kt`: 앱 범위의 세션 관찰, 설정·권한 확인, 직렬 DB 저장과 종료 처리.
- `app/src/main/java/com/dongbantest/app/music/MusicSettingsStore.kt`: SharedPreferences 설정. 기본 ON.
- `app/src/main/java/com/dongbantest/app/data/local/TrackPlayEntity.kt`: Room 엔티티와 도메인 변환.
- `app/src/main/java/com/dongbantest/app/data/local/TrackPlayDao.kt`: 삽입·갱신·삭제·여행별 관찰.
- `app/src/main/java/com/dongbantest/app/domain/model/TrackPlay.kt`: 노래 기록 도메인 모델.
- `app/schemas/com.dongbantest.app.data.local.DongbanDatabase/7.json`: Room이 생성한 v7 스키마.
- `app/src/test/java/com/dongbantest/app/music/TrackPlayLogTest.kt`: 26개 단위 테스트.
- `app/src/androidTest/java/com/dongbantest/app/data/local/TrackPlayMigrationTest.kt`: 실제 v6 SQLite 파일을 Room v7으로 여는 마이그레이션 시험.
- 이 문서.

수정한 파일:

- `app/src/main/java/com/dongbantest/app/data/local/DongbanDatabase.kt`: 버전 7, 엔티티·DAO, `MIGRATION_6_7` 등록.
- `app/src/main/java/com/dongbantest/app/data/local/TripDao.kt`: 오래된 여행을 닫을 때 마지막 기록 시각에 노래의 `endedAt`도 포함.
- `app/src/main/java/com/dongbantest/app/domain/model/Models.kt`: `TripWithMemories.tracks`, 기본값 빈 목록.
- `app/src/main/java/com/dongbantest/app/data/repository/TripRepository.kt`: 노래 Flow를 여행 집계에 포함. 기존 테스트 생성자 호환을 위해 추가 DAO 인자는 기본값 null이고, 실제 앱에서는 항상 주입한다.
- `app/src/main/java/com/dongbantest/app/DongbanApplication.kt`: 앱 범위 설정·트래커 등록, 여행 종료 훅에서 `stop` 완료를 기다림. 보조 프로세스에서 조기 반환하는 기존 줄은 유지.
- `app/src/main/java/com/dongbantest/app/viewmodel/TripViewModel.kt`: 위치 수집 시작 지점에서 음악 수집도 시작. GPS 권한이 없어도 음악을 수집하도록 ViewModel 생성 시에도 시작 요청.
- `app/src/main/java/com/dongbantest/app/viewmodel/AppViewModelProvider.kt`: 필수 DI 연결 한 줄. 목록 외 수정이 필요한 이유는 새 트래커를 ViewModel에 전달해야 하기 때문이다.
- `app/src/debug/java/com/dongbantest/app/debug/music/MusicLabActivity.kt`: “이 여행의 노래 행” 덤프, 설정 스위치, `MusicProbe` DB 로그. 종료 후에도 마지막 여행을 표시하며, 다시 열면 가장 최근 실제 여행을 조회한다.
- `app/src/androidTest/java/com/dongbantest/app/data/local/TalkMigrationTest.kt`: 현재 DB가 v7이므로 기존 시험에도 6→7 경로와 기대 버전 7을 연결했다. 이 조정 없이는 기존 시험이 마이그레이션 누락으로 실패한다. 음성 런타임 코드는 수정하지 않았다.

검증용 임시 Gradle 프로젝트와 캐시는 무시되는 `.gradle/m02-verification`, `.gradle/m02-schema-export`, `.gradle/m02-home`, `.gradle/m02-android` 안에만 만들었다. 앱의 빌드 설정은 변경하지 않았다.

## 저장 스키마

`track_plays`:

| 열 | Room/SQLite 형식 | 의미 |
| --- | --- | --- |
| id | Long / INTEGER, PK 자동 증가 | DB 행 번호 |
| tripId | Long / INTEGER NOT NULL | trips.id FK, ON DELETE CASCADE |
| startedAt | Long / INTEGER NOT NULL | 처음 재생한 벽시계 ms |
| endedAt | Long / INTEGER NOT NULL | 마지막 재생 시각 또는 재생 중 체크포인트 |
| playedMs | Long / INTEGER NOT NULL | 쉰 시간을 뺀 누적 재생 ms |
| title | String / TEXT NOT NULL | 처음 저장할 때 trim한 제목 |
| artist, album | String? / TEXT | 처음 저장할 때의 메타데이터 |
| durationMs | Long? / INTEGER | 소스가 제공한 곡 길이 |
| sourcePackage | String / TEXT NOT NULL | 재생 앱 패키지 |
| sourceLabel | String? / TEXT | 삽입 시 PackageManager에서 읽은 앱 이름. 조회 불가 시 null |

`index_track_plays_tripId`를 추가한다. 위도·경도는 저장하지 않는다. 시각은 위치 포인트와 같은 `System.currentTimeMillis()` 기준이다.

`MIGRATION_6_7`은 이 테이블과 인덱스만 생성한다. v6의 기존 다섯 엔티티 정의와 v7 정의가 동일함을 JSON 비교로 확인했다. 새 identity hash는 `fd0a3cd5718798344cdcaac79fff7b09`다.

## 구현한 상태 규칙

- 입력은 벽시계 시각과 순서가 있는 세션 스냅숏 목록이다. 출력은 `Insert`, `Update(row)`, `Delete(row)`다. 상태 머신의 token은 메모리 식별자이며, MusicTracker가 실제 자동 증가 DB id에 대응시킨다.
- 키는 패키지·제목·가수를 각각 trim하고 `Locale.ROOT`로 소문자화한 값이다. 가수 null과 빈 문자열은 같은 키다. 앨범·길이는 키에서 제외한다.
- 빈 제목은 무시한다. 제목이 모두 빈 초기 스냅숏은 기존 행도 바꾸지 않는다. 유효한 PLAYING 세션 중 목록의 첫 항목만 선택한다.
- 행이 없는데 PLAYING이면 삽입한다. 같은 곡의 반복 메타데이터·PLAYING 콜백은 메모리의 시간만 계산하고 DB 쓰기는 만들지 않는다.
- 같은 곡의 BUFFERING·CONNECTING은 이미 재생 중일 때 재생 시간으로 센다. 일시정지 상태에서 이 신호만 받아 재개하지는 않는다.
- PAUSED·STOPPED·NONE·ERROR 또는 세션 소실은 재생 시간을 합산하고 `endedAt=now`로 일시정지한다. 이후 쉰 시간은 합산하지 않는다. 다른 곡이 PLAYING이 아니면 새 행을 만들지 않는다.
- 같은 곡을 일시정지 후 정확히 10분 이내에 재개하면 같은 행을 계속 쓴다. 10분을 1ms라도 넘으면 이전 행을 닫고 새로 삽입한다. 콜백이 없어도 10분을 넘긴 대기 행은 타이머로 닫는다.
- 다른 키의 곡이 PLAYING이면 이전 행을 닫고 새 행을 삽입한다. 여행 종료·삭제, 트래커 정지, 설정 OFF, 접근 권한 회수 때도 열린 행을 닫는다.
- 닫을 때 누적 `playedMs < 10,000`이면 삭제한다. 정확히 10초는 남긴다. 일시정지 자체는 닫기가 아니므로, 짧은 행도 재개할 수 있게 잠시 남는다. 따라서 5초 재생 후 STOP만 보냈다면 여행 종료·다른 곡 재생·10분 초과 중 하나까지 기다려야 삭제된다.
- 같은 곡을 길이의 80%보다 많이 들었고, 관찰된 이전 위치가 3초보다 크며 새 PLAYING 위치가 0~3초로 되돌아오면 반복 재생으로 새 행을 만든다. 길이·위치가 없거나 유효하지 않으면 적용하지 않는다. 위치가 실제로 되돌아온 증거를 요구해 시작 위치가 반복 전송되는 것만으로는 분리하지 않는다.
- 재생 중에는 마지막 쓰기로부터 30초가 지나면 `endedAt`·`playedMs`를 체크포인트로 갱신한다. 1초 타이머는 권한·여행 유효성 및 체크포인트 시점만 확인한다. 매초 DB 쓰기를 하지는 않는다. 일시정지한 행의 `endedAt`은 쉰 동안 늘리지 않는다.
- 역행하는 벽시계 입력은 직전 입력 시각 이상으로 보정해 음수 재생 시간을 막는다.

MusicTracker는 앱 범위 coroutine과 Mutex로 콜백·저장을 직렬 처리한다. `stop(tripId)`는 마지막 저장이 끝날 때까지 기다려 여행 종료 후 지연 콜백이 새 행을 넣지 못하게 한다. 같은 여행의 재시작 요청은 멱등이고, 지난 여행 화면을 열어도 진행 중인 다른 여행의 트래커를 멈추지 않는다. 화면 이동·백그라운드 전환으로 중단하지 않는다.

알림 접근 허용과 “들은 노래 기록” ON이 둘 다 필요하다. 설정은 `music_settings/enabled`에 저장하며 기본 ON이고, 여행 진행 중 설정·권한 변경도 반영한다. 음성 녹음 동의·일시정지는 보지 않는다. 진행 중 여행이 없으면 삽입하지 않는다.

## 사양 해석과 제한

10분 경계는 “이내”를 포함하는 것으로 정했다. 반복 재생은 위치가 되돌아온 관찰 증거를 추가로 확인한다. 앨범·길이 등 저장 메타데이터는 최초 삽입 시 값을 유지한다. 동일 키의 메타데이터 갱신만으로는 DB에 쓰지 않는 규칙을 따른다.

체크포인트는 프로세스가 갑자기 종료될 때 마지막으로 저장한 진행 시간을 남긴다. 프로세스 재시작 뒤 이전 메모리 상태·행을 복원해 이어 붙이는 기능은 이번 범위에 없다. 화면 전환과 프로세스가 살아 있는 백그라운드 수집을 구현했다.

일반 설정 화면의 UI는 수정하지 않았다. 저장소 API와 실험실 스위치로 ON/OFF할 수 있다. 다른 세션 소유 `ui/v3` 변경과 충돌하지 않도록 했다.

## 실행한 검증 결과

1. 앱 전체 빌드·단위 테스트: **실행 시도했으나 환경 권한 오류로 실패. 앱 컴파일과 앱 단위 테스트는 실행되지 않음.**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

첫 출력:

```text
java.lang.RuntimeException: Could not create parent directory for lock file C:\.gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0-bin.zip.lck
```

저장소 안으로 캐시를 지정해 재시도했다:

```powershell
$env:GRADLE_USER_HOME = "$PWD/.gradle/m02-home"
$env:ANDROID_USER_HOME = "$PWD/.gradle/m02-android"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

최종 출력의 핵심:

```text
Error: SDK processing. Error parsing C:\Users\ip936\AppData\Local\Android\Sdk\platforms\android-37.0\package.xml.
java.io.FileNotFoundException: ...\package.xml (액세스가 거부되었습니다)
Could not determine the dependencies of task ':app:testDebugUnitTest'.
> Failed to create MD5 hash for file: C:\Users\ip936\AppData\Local\Android\Sdk\licenses\android-sdk-license (액세스가 거부되었습니다)
BUILD FAILED in 15s
```

2. 순수 Kotlin 상태 머신 테스트: **26개 통과, 실패·오류·건너뜀 0개.**

Android SDK를 사용하지 않는 임시 Kotlin/JVM Gradle 프로젝트에서 실제 `TrackPlayLog.kt`와 `TrackPlayLogTest.kt`만 소스 경로로 연결했다. Kotlin 2.2.10, JUnit 4.13.2를 사용했다.

```powershell
$env:GRADLE_USER_HOME = "$PWD/.gradle/m02-home"
.\gradlew.bat -p .gradle/m02-verification test --no-configuration-cache
```

```text
BUILD SUCCESSFUL in 1m 3s
4 actionable tasks: 4 executed
tests="26" skipped="0" failures="0" errors="0"
```

최초 실행에서 Kotlin daemon의 저장소 밖 임시 marker 생성이 거부됐지만, Gradle이 daemon 없는 컴파일로 자동 전환한 뒤 테스트가 성공했다. XML 결과는 `.gradle/m02-verification/build/test-results/test/TEST-com.dongbantest.app.music.TrackPlayLogTest.xml`에 있다.

필수 A15초→쉼5초→B15초→종료, 메타데이터 3회·PLAYING 3회 중복, 일시정지 재개, 짧은 곡 삭제, 9,999ms/10,000ms, 10분/10분+1ms, 반복 재생·정보 누락, 다중 세션 순서, 세션 소실, 체크포인트와 벽시계 역행을 검증했다.

3. Room 스키마 생성·쿼리 검증·DAO 컴파일: **통과.**

임시 JVM 프로젝트에 실제 `Entities.kt`, `TrackPlayEntity.kt`, `TrackPlay.kt`, `TripDao.kt`, `TrackPlayDao.kt`를 연결하고, 앱과 동일한 패키지·엔티티 목록·버전의 임시 RoomDatabase 선언을 사용했다. Android Context를 사용하는 앱 DB 생성부는 포함하지 않았다. 앱과 같은 Room 2.8.5, KSP 2.3.13으로 `7.json`을 생성했다. 수작업 hash가 아니다.

```powershell
.\gradlew.bat -p .gradle/m02-schema-export kspKotlin --no-configuration-cache '-Pkotlin.compiler.execution.strategy=in-process'
.\gradlew.bat -p .gradle/m02-schema-export compileKotlin --no-configuration-cache '-Pkotlin.compiler.execution.strategy=in-process'
```

```text
> Task :kspKotlin
BUILD SUCCESSFUL in 11s
> Task :compileKotlin
BUILD SUCCESSFUL in 14s
```

이는 앱 전체 KSP·컴파일 통과를 뜻하지 않는다. Android 통합부는 실제 앱 빌드로 다시 확인해야 한다.

4. Android 마이그레이션 시험·에뮬레이터 시나리오: **실행하지 못함.**

```powershell
& 'C:/Users/ip936/AppData/Local/Android/Sdk/platform-tools/adb.exe' devices
```

```text
'adb.exe' 프로그램을 실행하지 못했습니다. Access is denied
```

`TrackPlayMigrationTest`는 기존 다섯 테이블의 정의·데이터 보존, 신규 빈 테이블, 인덱스·FK, nullable 필드, 자동 증가 id, DAO 갱신·삭제, 여행 삭제 cascade를 검사하도록 작성했다. 실제 기기 결과는 아직 없다.

5. `git diff --check`: 통과. 커밋하지 않았다.

## 다시 실행할 빌드·adb 명령

아래는 SDK와 adb를 실행할 수 있는 PowerShell 세션에서 저장소 루트 기준으로 실행한다. 이 세션에서는 실행 결과를 확인하지 못했다. Pleos 현재 사용자는 10이다.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
$M02Adb = 'C:/Users/ip936/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $M02Adb devices
& $M02Adb shell am get-current-user
& $M02Adb install --user 10 -r app/build/outputs/apk/debug/app-debug.apk
& $M02Adb install --user 10 -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $M02Adb shell am instrument --user 10 -w -e class 'com.dongbantest.app.data.local.TrackPlayMigrationTest,com.dongbantest.app.data.local.TalkMigrationTest' com.dongbantest.app.test/androidx.test.runner.AndroidJUnitRunner
& $M02Adb shell cmd notification allow_listener com.dongbantest.app/com.dongbantest.app.music.MusicListenerService 10
& $M02Adb shell am start --user 10 -n com.dongbantest.app/.MainActivity
```

앱에서 새 실제 여행을 시작하고 여행 화면을 한 번 연다. 음성 녹음 동의는 필요 없다. 이어서 아래 명령으로 디버그 목록을 열고 “노래 기록 실험실”을 누른다. 실험실 Activity는 기존대로 exported=false이므로 외부 adb에서 직접 실행하지 않는다. 실험실의 알림 접근 허용과 “들은 노래 기록” ON을 확인한다.

```powershell
& $M02Adb shell am start --user 10 -n com.dongbantest.app/.debug.V3CatalogActivity
```

다른 PowerShell 창에서 로그를 볼 수 있다:

```powershell
$M02Adb = 'C:/Users/ip936/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $M02Adb logcat -v threadtime -s MusicProbe:I
```

필수 시나리오는 자동 시퀀스를 쓰면 재생 구간이 각각 15초로 유지된다. 실험실이 앞에 있는 상태에서 시작한 뒤, 설정 앱을 앞으로 열어 백그라운드에서 받는지 본다. HOME 키로는 Pleos 앱이 배경으로 가지 않았다는 M01 결과를 따른다.

```powershell
& $M02Adb shell am broadcast --user 10 -n com.dongbantest.app/.debug.music.DebugMusicReceiver -a com.dongbantest.app.debug.MUSIC_SEQUENCE
& $M02Adb shell am start --user 10 -a android.settings.SETTINGS
1..3 | ForEach-Object { & $M02Adb shell am broadcast --user 10 -n com.dongbantest.app/.debug.music.DebugMusicReceiver -a com.dongbantest.app.debug.MUSIC_RESEND }
1..3 | ForEach-Object { & $M02Adb shell am broadcast --user 10 -n com.dongbantest.app/.debug.music.DebugMusicReceiver -a com.dongbantest.app.debug.MUSIC_RESEND_PLAYING }
Start-Sleep -Seconds 37
& $M02Adb shell am start --user 10 -n com.dongbantest.app/.MainActivity
```

이제 앱에서 여행을 종료한다. 디버그 목록에서 실험실을 다시 열어 “이 여행의 노래 행”을 확인한다. 기대값은 정확히 2행, `디버그 곡 A`의 playedMs 약 15,000, `디버그 곡 B`도 약 15,000이다. 장치의 콜백 전달 지연만큼 차이는 날 수 있다. 중복 재전송 로그가 A 일시정지 전에 들어왔는지도 확인한다. 저장 행은 다음 명령으로도 읽는다:

```powershell
& $M02Adb logcat -d -v threadtime -s MusicProbe:I | Select-String 'track_plays'
```

재개 검증은 새 여행에서 한다. 여행 화면을 연 뒤 실험실로 들어오고 아래를 실행한다. A를 8초 듣고 5초 쉰 뒤 같은 A를 8초 더 들으면, 여행 종료 후 한 행의 playedMs가 약 16초여야 한다. 실험 플레이어의 A 명령은 음원을 처음부터 다시 틀지만, 상태 머신의 키와 10분 이내 재개 규칙은 동일하다.

```powershell
& $M02Adb shell am broadcast --user 10 -n com.dongbantest.app/.debug.music.DebugMusicReceiver -a com.dongbantest.app.debug.MUSIC_PLAY_A
& $M02Adb shell am start --user 10 -a android.settings.SETTINGS
Start-Sleep -Seconds 8
& $M02Adb shell am broadcast --user 10 -n com.dongbantest.app/.debug.music.DebugMusicReceiver -a com.dongbantest.app.debug.MUSIC_PAUSE
Start-Sleep -Seconds 5
& $M02Adb shell am broadcast --user 10 -n com.dongbantest.app/.debug.music.DebugMusicReceiver -a com.dongbantest.app.debug.MUSIC_PLAY_A
Start-Sleep -Seconds 8
& $M02Adb shell am broadcast --user 10 -n com.dongbantest.app/.debug.music.DebugMusicReceiver -a com.dongbantest.app.debug.MUSIC_STOP
& $M02Adb shell am start --user 10 -n com.dongbantest.app/.MainActivity
```

앱에서 이 여행도 종료한다. 5초 스킵 검증은 다시 새 여행을 만들어 아래를 실행하고 여행을 종료한다. 최종 기대값은 A가 없고 B 한 행만 남는 것이다.

```powershell
& $M02Adb shell am broadcast --user 10 -n com.dongbantest.app/.debug.music.DebugMusicReceiver -a com.dongbantest.app.debug.MUSIC_PLAY_A
& $M02Adb shell am start --user 10 -a android.settings.SETTINGS
Start-Sleep -Seconds 5
& $M02Adb shell am broadcast --user 10 -n com.dongbantest.app/.debug.music.DebugMusicReceiver -a com.dongbantest.app.debug.MUSIC_PLAY_B
Start-Sleep -Seconds 15
& $M02Adb shell am broadcast --user 10 -n com.dongbantest.app/.debug.music.DebugMusicReceiver -a com.dongbantest.app.debug.MUSIC_STOP
& $M02Adb shell am start --user 10 -n com.dongbantest.app/.MainActivity
```

시작이 막히면 실험실의 재생 버튼으로 먼저 플레이어를 시작하고 `MusicProbe`의 foreground-service 오류 여부를 확인한다. “DebugMusicReceiver received”만으로 실제 재생·저장 성공을 판단하지 말고 `published state`, 메타데이터 콜백, 최종 `track_plays dump`를 함께 확인한다.

## 에뮬레이터 확인 결과 (2026-11-14, Claude 세션)

Codex 샌드박스 밖에서 다시 실행했다. Pleos Connect v2.1.0 에뮬레이터(Android 14, 사용자 10).

- `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest` 통과. 단위 테스트 137개 전부 통과(TrackPlayLogTest 26개 포함). 처음에는 이전 빌드의 테스트 클래스가 남아 `NoSuchMethodError` 14개가 났고, `--rerun-tasks`로 다시 컴파일하니 사라졌다(코드 문제 아님).
- `TrackPlayMigrationTest` + `TalkMigrationTest` 기기 실행: 3개 통과.
- 필수 시나리오: 앱에서 새 여행(trip=31, 녹음 없이 출발) → 실험실 열기 → `MUSIC_SEQUENCE` → 설정 앱을 앞에 띄움 → 메타데이터 재전송 3회 + PLAYING 재전송 3회 → 종료까지 대기 → 앱에서 "저장하고 끝내기".
  - `track_plays` **정확히 2행**: 디버그 곡 A `playedMs=15013`, 디버그 곡 B `playedMs=15048`.
  - 재전송 6회는 행을 늘리거나 쓰기를 만들지 않음. A 일시정지 5초는 playedMs에 들어가지 않음.
  - 여행을 끝낸 뒤에도 2행 유지.
- 재개(같은 곡 다시 틀기)와 5초 넘기기 삭제는 기기에서 따로 돌리지 않았다. 단위 테스트로만 확인했다.
