# M01 — 들은 노래 기록: 가능성 확인

작성일: 2026-11-14. 작업 브랜치: `feat/talk-rec-integration`.

## 지금 결론 (2026-11-14 Pleos 에뮬레이터 확인 후 갱신)

**Pleos Connect v2.1.0 에뮬레이터(Android 14, API 34, 사용자 10)에서 A·B·C·D·F 통과. E(실제 음원)는 실기기가 없어 확인 불가로 두고 M02를 진행한다.**

| # | 결과 | 근거 |
|---|---|---|
| A | 통과 | 설정 → 앱 → 알림 액세스에 "동반차 / 동반차 노래 기록" 항목과 스위치가 보임 |
| B | 통과 | `ACTION_NOTIFICATION_LISTENER_SETTINGS`, `NOTIFICATION_LISTENER_DETAIL_SETTINGS` 둘 다 `CarSettingActivities$NotificationAccessActivity`로 열림 |
| C | 통과(adb 설치 기준) | 스위치를 누르면 제한된 설정 차단 없이 시스템 확인창("…모든 알림을 읽을 수 있게 됩니다", 허용/거부)이 뜨고 허용하면 `enabled_notification_listeners`에 등록됨. 파일 관리자·브라우저로 설치한 APK는 따로 확인하지 않음 |
| D | 통과 | `cmd notification allow_listener COMPONENT [user_id]` 지원 확인. 앱 재설치 뒤에도 권한 유지 |
| E | 확인 불가 | Pleos 실기기가 없음(2026-11-14 결정). 에뮬레이터에는 블루투스·라디오 소스가 없음. 실기기가 생기면 다시 확인 |
| F | 통과 | 실험실 위에 설정 앱을 띄워 `lab onStop` 이후 A 재생 → 일시정지 → B 재생 → 정지의 onMetadataChanged / onPlaybackStateChanged / onSessionDestroyed가 관찰자 PID(30770)에서, 플레이어는 다른 PID(1898, :musicprobe)에서 찍힘 |

추가로 알게 된 점:

- **Pleos에서 HOME 키는 앱을 뒤로 보내지 않는다.** 오른쪽 화면에 앱이 그대로 남아 `topResumedActivity`가 유지된다. 백그라운드 확인은 다른 앱(설정)을 앞에 띄워서 했다.
- **같은 값 재전송도 콜백이 매번 온다.** 같은 메타데이터 재전송 2회 → onMetadataChanged 2회, PLAYING 재전송 2회 → onPlaybackStateChanged 2회. M02의 중복 방지 상태 기계가 꼭 필요하다.
- 세션이 처음 붙을 때 `attached`(제목 null, state=1) 뒤에 메타데이터가 따로 온다. 제목 없는 첫 스냅숏은 기록하지 않아야 한다.
- **버그 수정:** `:musicprobe` 프로세스에서도 `DongbanApplication.onCreate`가 돌아 Mapbox 초기화에서 죽었다. 주 프로세스가 아니면 초기화를 건너뛰도록 고쳤다.
- 시스템 확인창 문구가 "모든 알림을 읽을 수 있다"로 무섭다. M04 권한 안내에서 "알림 내용은 읽지 않고 재생 중인 곡 정보만 본다"를 먼저 보여 줘야 한다.
- 빌드·단위 테스트는 샌드박스 밖에서 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest` 통과(MusicSessionFilterTest 3/3).

증거(로컬): logcat `m01-logcat.txt`, `m01-logcat-bg.txt`, `m01-logcat-dup.txt`, dumpsys `session-*.txt`, 화면 `m01-settings.png`, `m01-home-A.png`.

아래는 Codex가 처음 작성한 원문이다(기기 확인 전).

### 처음 결론(기기 확인 전)

확인에 필요한 코드와 실험실은 만들었다. **Pleos에서 실제로 되는지는 아직 확인하지 못했다. M02는 보류한다.**

Android 공식 API는 일반 앱도 사용자가 켠 알림 리스너의 `ComponentName`으로 활성 미디어 세션을 읽을 수 있다고 설명한다. 다만 Pleos에 권한 메뉴가 있는지, 실제 음악 소스가 제목과 가수를 보내는지는 별도로 확인해야 한다. 근거: [MediaSessionManager.getActiveSessions](https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName)).

이 환경에서는 설치된 Android SDK 파일 읽기와 `adb.exe` 실행이 “액세스 거부”로 막혔다. 기기가 없는 것으로 확인된 것은 아니다. **기기 연결 여부 자체가 미확인**이며 설치·권한 허용·자동 재생·dumpsys·logcat 수집을 실행하지 못했다.

요청한 문서 경로는 `C:\github\drive-diary\talk-rec\docs\music-save\M01-feasibility.md`였다. 작업 범위와 쓰기 권한이 `C:\github\drive-diary-int`로 제한되어 이 작업 폴더의 같은 상대 경로에 작성했다. 바깥 경로의 파일은 수정하지 않았다.

## 만든 파일

경로는 모두 작업 폴더 기준이다.

| 파일 | 하는 일 |
|---|---|
| `app/src/main/java/com/dongbantest/app/music/MusicListenerService.kt` | 알림 리스너 연결 상태를 StateFlow로 제공한다. `onNotificationPosted`를 오버라이드하지 않으며 알림 내용은 읽지 않는다. |
| `app/src/main/java/com/dongbantest/app/music/MusicAccess.kt` | `NotificationManagerCompat.getEnabledListenerPackages`로 권한을 확인하고 열 수 있는 설정 인텐트를 순서대로 반환한다. |
| `app/src/main/java/com/dongbantest/app/music/MusicSessionFilter.kt` | 출시 빌드는 자기 패키지를 제외한다. 디버그는 태그가 정확히 `dongban-debug-music`인 자기 세션만 허용한다. |
| `app/src/main/java/com/dongbantest/app/music/NowPlayingWatcher.kt` | 활성 세션 목록 변경과 메타데이터·재생 상태·세션 파괴 콜백을 관찰한다. 스냅숏과 최근 200개 콜백 로그를 StateFlow로 제공한다. 권한 예외는 상태와 로그로 알린다. |
| `app/src/main/AndroidManifest.xml` | 알림 리스너 서비스 선언만 추가했다. `BIND_NOTIFICATION_LISTENER_SERVICE`, `exported=true`, 리스너 intent-filter를 지정했다. |
| `app/src/debug/java/com/dongbantest/app/debug/music/DebugMusicService.kt` | `:musicprobe` 프로세스의 mediaPlayback 포그라운드 서비스. WAV 재생, 미디어 세션, 중복 전송, 자동 순서를 제공한다. |
| `app/src/debug/java/com/dongbantest/app/debug/music/DebugMusicReceiver.kt` | 명시적 adb broadcast를 플레이어 서비스에 전달한다. |
| `app/src/debug/java/com/dongbantest/app/debug/music/MusicLabProbe.kt` | 화면 수명과 관찰자를 분리한다. HOME과 화면 재생성 뒤에도 관찰을 유지하며 리스너가 다시 연결되면 재조회한다. |
| `app/src/debug/java/com/dongbantest/app/debug/music/MusicLabActivity.kt` | “노래 기록 실험실”. 권한·연결 상태, 설정 후보, adb 명령, 재생 버튼, 세션 목록, 시각이 붙은 콜백 로그를 보여 준다. |
| `app/src/debug/java/com/dongbantest/app/debug/V3CatalogActivity.kt` | 기존 스타일의 “노래 기록 실험실” 버튼 하나만 추가했다. |
| `app/src/debug/AndroidManifest.xml` | 실험실·플레이어·수신기, 포그라운드 서비스 권한 두 개, 설정 화면 조회용 queries를 추가했다. |
| `app/src/debug/assets/music/tone_a.wav` | 직접 만든 440Hz 톤, 20초, 320,044바이트. |
| `app/src/debug/assets/music/tone_b.wav` | 직접 만든 660Hz 톤, 20초, 320,044바이트. |
| `tools/GenerateMusicTones.java` | 외부 음원 없이 WAV 두 개를 다시 만드는 JDK 스크립트. |
| `app/src/test/java/com/dongbantest/app/music/MusicSessionFilterTest.kt` | 자기 세션 제외·정확한 디버그 태그 허용·다른 패키지 허용을 검사하는 테스트 3개. |
| `talk-rec/docs/music-save/M01-feasibility.md` | 이 결과 문서. |

스냅숏에는 패키지, 태그, 제목, 가수, 앨범, 길이(ms), 재생 상태, 위치(ms), 수신 시각(벽시계 ms)이 들어간다. 위치는 소스가 마지막으로 보낸 값이며 화면에서 매초 계산해 늘리는 값은 아니다. M01은 DB·여행 기록에 저장하지 않는다. 디버그 logcat의 태그는 모두 `MusicProbe`다. 메모리 로그가 200개를 넘어도 logcat에는 각 콜백을 계속 출력한다.

## 실험실 실행

정차한 상태에서 다음 순서로 확인한다.

1. `app-debug.apk`를 설치한다.
2. 런처의 **동반차 디버그 → 노래 기록 실험실**을 연다. 이 화면을 한 번 열어 관찰자를 시작해야 한다.
3. “설정 열기”를 누른다. API 30 이상은 리스너 상세 설정을 먼저 시도하고 일반 알림 접근 설정을 다음으로 시도한다. `resolveActivity`로 찾은 후보만 사용하며 실제 실행이 실패하면 다음 후보로 넘어간다. 관련 API: [Settings](https://developer.android.com/reference/android/provider/Settings#ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).
4. 동반차의 알림 접근을 켜고 돌아온다. “알림 접근: 허용”, “리스너: 연결됨”, “getActiveSessions 성공”을 따로 확인한다. 권한이 없을 때는 SecurityException 안내가 나와야 하며 앱이 종료되면 안 된다.
5. A 재생 / 일시정지 / B 재생 / 정지 버튼으로 목록과 로그를 확인한다.
6. 재생 중 “같은 메타데이터 다시 보내기”, “PLAYING 상태 다시 보내기”를 여러 번 누른다. 콜백 로그는 중복도 남겨야 한다. PLAYING 재전송은 재생 중에만 동작한다.
7. “순서 자동 실행”은 A 15초 → 일시정지 5초 → B 15초 → 정지다. HOME 뒤에도 로그를 확인한다. 정지 상태를 전송하고 약 0.75초 뒤 세션과 서비스를 정리한다.

태그를 읽는 `MediaController.getTag()`는 [API 30부터 제공](https://developer.android.com/reference/android/media/session/MediaController#getTag())된다. 앱의 최소 버전인 API 28~29에서는 태그를 null로 표시하고 자기 패키지의 디버그 세션도 제외한다. 다른 앱 세션 관찰은 그대로 가능하다. Pleos에서 자체 테스트 플레이어를 보려면 API 30 이상이 필요하다.

WAV를 다시 만들 때는 저장소 루트에서 실행한다. 이미 생성한 WAV 두 개도 소스에 포함했다.

```powershell
java tools/GenerateMusicTones.java
```

## Pleos / AAOS에서 실행할 adb 명령

아래는 PowerShell 예시다. 여러 기기가 있으면 각 `adb`에 `-s <serial>`을 추가한다. AAOS는 사용자 0과 운전자 사용자가 다를 수 있으므로 현재 사용자 번호를 먼저 확인한다. 기기 모델·OS·빌드 번호도 증거와 함께 남긴다.

```powershell
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
$musicUser = (adb shell am get-current-user).Trim()
New-Item -ItemType Directory -Force .cache/music-probe/evidence | Out-Null
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start --user $musicUser -n com.dongbantest.app/.debug.V3CatalogActivity
```

카탈로그에서 실험실 버튼을 누른다. 실험실 Activity는 외부에 공개하지 않았다. A~C는 **adb로 허용하기 전에** 확인해야 일반 사용자의 경로를 판정할 수 있다.

설정 Activity 조회(요청한 명령 그대로):

```powershell
adb shell cmd package query-activities -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
adb shell cmd package query-activities --user $musicUser -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
adb shell cmd package query-activities --user $musicUser -a android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS
```

설정이 없거나 사용자 경로 확인을 마쳤다면 개발용으로 허용한다. 첫 줄은 기본 사용자 대상의 요청 명령이고 두 번째는 현재 AAOS 사용자 번호를 명시한 형태다. 기기의 `adb shell cmd notification help`에서 사용자 인수 지원도 확인한다.

```powershell
adb shell cmd notification allow_listener com.dongbantest.app/com.dongbantest.app.music.MusicListenerService
adb shell cmd notification allow_listener com.dongbantest.app/com.dongbantest.app.music.MusicListenerService $musicUser
```

실험실에서 권한·세션 다시 확인을 누른다. 첫 번째 터미널에서 수집을 시작한다. 아래 `-T 1`은 기존 버퍼 전체 대신 최근부터 수집하기 위한 옵션이다.

```powershell
adb logcat -v threadtime -T 1 -s MusicProbe:I | Tee-Object .cache/music-probe/evidence/background-logcat.txt
```

두 번째 터미널에서도 `$musicUser`를 정의하고 HOME 뒤 자동 순서를 보낸다. 약 35초 동안 실행된다. 재생 중 dumpsys를 남겨야 한다. 정지 후에는 세션이 없어질 수 있다.

```powershell
$musicUser = (adb shell am get-current-user).Trim()
adb shell input keyevent KEYCODE_HOME
adb shell am broadcast --user $musicUser -a com.dongbantest.app.debug.MUSIC_SEQUENCE -n com.dongbantest.app/.debug.music.DebugMusicReceiver
Start-Sleep -Seconds 3
adb shell dumpsys media_session | Out-File -Encoding utf8 .cache/music-probe/evidence/session-A.txt
Start-Sleep -Seconds 13
adb shell dumpsys media_session | Out-File -Encoding utf8 .cache/music-probe/evidence/session-paused.txt
Start-Sleep -Seconds 6
adb shell dumpsys media_session | Out-File -Encoding utf8 .cache/music-probe/evidence/session-B.txt
Start-Sleep -Seconds 16
adb shell dumpsys media_session | Out-File -Encoding utf8 .cache/music-probe/evidence/session-stopped.txt
```

첫 터미널은 Ctrl+C로 끝낸다. `lab onStop` 뒤에 **관찰자 쪽** `onMetadataChanged` / `onPlaybackStateChanged`가 시각과 함께 있어야 F의 증거다. 서비스 쪽 `published` 또는 수신기의 `received`만 있는 것으로는 수신 성공으로 판정하지 않는다. logcat PID와 `DebugMusicService created ... process=:musicprobe`의 PID가 다른 것도 확인한다. HOME 상태는 화면 사진이나 아래 결과로 남긴다.

```powershell
adb shell dumpsys activity activities | Out-File -Encoding utf8 .cache/music-probe/evidence/foreground-activity.txt
```

기기가 adb broadcast의 백그라운드 FGS 시작을 막으면 오류가 `MusicProbe`에 남는다. 그 경우 실험실의 자동 순서 버튼을 누르고 바로 HOME으로 나가 수신을 확인하고, adb 시작 제한은 별도 결과로 적는다.

수동 동작도 모두 같은 수신기로 보낼 수 있다. 재전송은 A 또는 B 재생 중에 실행한다. 자동 순서 도중 수동 재생·일시정지·정지를 보내면 자동 순서는 취소된다. 두 재전송 명령은 자동 순서를 취소하지 않는다.

```powershell
adb shell am broadcast --user $musicUser -a com.dongbantest.app.debug.MUSIC_PLAY_A -n com.dongbantest.app/.debug.music.DebugMusicReceiver
adb shell am broadcast --user $musicUser -a com.dongbantest.app.debug.MUSIC_RESEND -n com.dongbantest.app/.debug.music.DebugMusicReceiver
adb shell am broadcast --user $musicUser -a com.dongbantest.app.debug.MUSIC_RESEND_PLAYING -n com.dongbantest.app/.debug.music.DebugMusicReceiver
adb shell am broadcast --user $musicUser -a com.dongbantest.app.debug.MUSIC_PAUSE -n com.dongbantest.app/.debug.music.DebugMusicReceiver
adb shell am broadcast --user $musicUser -a com.dongbantest.app.debug.MUSIC_PLAY_B -n com.dongbantest.app/.debug.music.DebugMusicReceiver
adb shell am broadcast --user $musicUser -a com.dongbantest.app.debug.MUSIC_STOP -n com.dongbantest.app/.debug.music.DebugMusicReceiver
```

권한 회수도 확인하려면 아래 명령 뒤 실험실에서 다시 확인을 누른다. 앱이 종료되지 않아야 한다. 테스트 종료 후 필요하면 알림 접근 설정에서 권한을 끈다.

```powershell
adb shell cmd notification disallow_listener com.dongbantest.app/com.dongbantest.app.music.MusicListenerService $musicUser
```

## A~F 확인표 (M00 2.1)

기기/OS: Pleos 실기기 및 AAOS 에뮬레이터 모두 미확인. adb 실행 권한 문제로 연결 목록을 얻지 못했다.

| # | 질문 | 결과 | 남은 확인과 필요한 증거 |
|---|---|---|---|
| A | Pleos 설정의 “알림 접근” 메뉴에 동반차가 보이는가? | **실기기 확인 필요** | 정차 후 설정 → 앱 → 특별한 앱 접근 → 알림 접근에 해당하는 메뉴를 찾아 동반차 항목과 허용 스위치 화면 사진을 남긴다. 메뉴가 없으면 그 사실과 OS 빌드 번호를 적는다. |
| B | 앱의 설정 인텐트로 해당 화면이 실제로 열리는가? | **실기기 확인 필요** | 위 query-activities 출력, 실험실의 resolveActivity 후보와 “설정 실행 요청” 로그를 저장한다. 버튼을 눌러 실제 열린 화면까지 촬영한다. resolve 성공만으로 통과시키지 않는다. |
| C | Android 13+ 제한된 설정에 막히는가? 해제하면 되는가? | **실기기 확인 필요** | 스토어 밖 설치 상태에서 알림 접근 스위치를 눌러 본다. 막히면 앱 정보 ⋮ → 제한된 설정 허용 전후 사진을 남긴다. 그런 메뉴도 없으면 그대로 적는다. API 32 이하는 해당 없음으로 적는다. |
| D | 설정 메뉴가 없을 때 adb 허용으로 세션 조회가 되는가? | **실기기 확인 필요** | 현재 사용자에 allow_listener 실행 후 onListenerConnected와 `getActiveSessions success`를 확보한다. SecurityException이 없어야 한다. A 재생 후 자체 세션의 제목·태그까지 확인한다. |
| E | 블루투스·라디오·설치 음악 앱의 메타데이터가 오는가? | **실기기 확인 필요** | 자체 톤 시험을 마친 뒤 각 실제 소스를 따로 재생한다. dumpsys와 관찰자 콜백의 패키지·제목·가수·앨범·길이를 비교한다. 빈 값과 지연도 적는다. |
| F | 동반차가 백그라운드일 때도 콜백이 오는가? | **실기기 확인 필요** | HOME 후 adb 자동 순서를 실행한다. 위 시각별 dumpsys, 앞에 있는 Activity, 관찰자 PID의 콜백 로그를 저장한다. 실제 소스도 HOME 상태에서 곡 변경·일시정지해 같은 방법으로 확인한다. |

## 소스별 메타데이터

다음 디버그 값은 코드가 보내도록 만든 **기대값**이다. 실제 수신 증거로 쓰지 않았다.

| 소스 | 제목 | 가수 | 앨범 / 길이 | 수신 확인 |
|---|---|---|---|---|
| 디버그 A | 디버그 곡 A | 동반차 테스트 | 동반차 노래 기록 실험 / 20,000ms | 실기기 확인 필요 |
| 디버그 B | 디버그 곡 B | 동반차 테스트 | 동반차 노래 기록 실험 / 20,000ms | 실기기 확인 필요 |
| 폰 연결 블루투스 | 아직 수집하지 못함 | 아직 수집하지 못함 | 아직 수집하지 못함 | 실기기 확인 필요 |
| 라디오 | 아직 수집하지 못함 | 아직 수집하지 못함 | 아직 수집하지 못함 | 실기기 확인 필요 |
| 설치 음악 앱 | 아직 수집하지 못함 | 아직 수집하지 못함 | 아직 수집하지 못함 | 실기기 확인 필요 |

## 빌드·테스트 결과

전체 빌드·테스트는 아래 요청 작업을 함께 실행했으나 **실패**했다. Kotlin 소스 컴파일과 앱 단위 테스트에 도달하지 못했으므로 통과라고 볼 수 없다.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain
```

실행 결과와 조치:

1. 기본 Gradle 캐시 `C:\.gradle`에 잠금 파일을 만들 수 없어 실패했다.
2. Gradle 캐시를 작업 폴더 안으로 지정하니 Android 도구가 `C:\.android`에 쓸 수 없어 실패했다.
3. `GRADLE_USER_HOME`과 `ANDROID_USER_HOME`을 모두 작업 폴더 안으로 지정해 재시도했다. 이번에는 설치된 SDK의 `platforms\android-37.0\package.xml`, `licenses\android-sdk-license` 읽기가 “액세스 거부”여서 `BUILD FAILED in 39s`로 끝났다.

마지막 시도의 전체 로그: `.cache/music-probe/build-test.log` (로컬 검증 파일, Git 제외).

앱 설정이나 `local.properties`를 바꾸지 않고 실행하려고 환경 변수만 지정했다. SDK 접근 권한이 있는 일반 터미널에서는 다음과 같이 다시 확인한다.

```powershell
$env:GRADLE_USER_HOME = "$PWD/.cache/music-probe/gradle"
$env:ANDROID_USER_HOME = "$PWD/.cache/music-probe/android"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

세션 필터의 테스트 3개는 별도의 작은 JVM 프로젝트에서 **원본 MusicSessionFilter.kt와 원본 MusicSessionFilterTest.kt**를 대상으로 실행했고 **3개 모두 통과**했다(실패 0, 오류 0). Android SDK를 쓰지 않는 검사이며 앱 전체 테스트를 대신하지 않는다. Kotlin 데몬의 사용자 경로 쓰기가 거부되었지만 컴파일러가 데몬 없는 방식으로 자동 전환해 `BUILD SUCCESSFUL in 1m 17s`로 끝났다.

실행 명령: `.\gradlew.bat -p .cache/music-probe/filter-tests test --console=plain`. 별도 검증 프로젝트와 결과는 Git에서 제외되는 `.cache/music-probe` 아래에만 있다. 로그는 `filter-tests.log`, JUnit 결과는 `filter-tests/build/test-results/test/TEST-com.dongbantest.app.music.MusicSessionFilterTest.xml`이다.

WAV 생성은 `java tools/GenerateMusicTones.java`로 **성공**했다. 각각 20초, 320,044바이트다.

추가로 main/debug 매니페스트 XML 파싱, WAV 헤더·8kHz·20초 길이·파일 크기를 확인했다. 허용된 기존 파일 3개의 `git diff --check`도 통과했다. 이 정적 확인은 Android 컴파일이나 실제 재생을 대신하지 않는다.

adb는 `C:\Users\ip936\AppData\Local\Android\Sdk\platform-tools\adb.exe devices -l` 실행을 시도했으나 “프로그램을 실행하지 못했습니다. 액세스가 거부되었습니다”로 실패했다. 연결 기기 없음이라고 판정할 수 없다. 설치·설정 조회·권한 허용·dumpsys·logcat 결과는 없다.

## M02 진행 조건

현재는 **보류**한다. 코드 추가나 공식 API 설명만으로 Pleos 통과로 판정하지 않는다.

- Pleos에서 A 또는 B를 통해 사용자가 직접 권한을 켤 수 있고, 실제 소스(E)와 백그라운드 수신(F)을 확인하면 M02를 진행한다.
- A·B는 안 되고 D만 되면 개발 시험용으로만 가능하다. M02는 보류하고 Pleos SDK 또는 권한 제공 방식을 확인한다.
- D도 안 되면 이 방식은 사용할 수 없다.
- 빌드와 전체 단위 테스트도 SDK 접근이 가능한 환경에서 통과해야 한다.

## 설계와 달라진 점

- 결과 문서는 쓰기 가능한 `drive-diary-int` 안에 남겼다. 요청한 옆 작업 폴더에는 쓰지 않았다.
- `getTag()`의 API 30 제약을 반영했다. API 28~29에서는 자기 앱 세션을 안전하게 제외한다.
- PLAYING 중복 전송도 adb로 확인할 수 있도록 `MUSIC_RESEND_PLAYING` 동작을 추가했다.
- 실기기·에뮬레이터 결과와 전체 빌드·테스트 통과 여부는 환경 권한 문제로 미완료다. A~F의 성공 증거를 만들거나 추정해 적지 않았다.
