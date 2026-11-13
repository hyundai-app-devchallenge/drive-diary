# I8c — DocumentsUI 없는 Pleos AVD의 실험실 파일 입력

작업트리: `C:\github\drive-diary-int`  
브랜치: `feat/talk-rec-integration`  
커밋·푸시·브랜치 전환 없음. Gradle/AVD 실행 없음.

## 변경 내용

| 파일 | 변경 |
| --- | --- |
| `app/src/debug/java/com/dongbantest/app/debug/LabAudioFiles.kt` | `context.filesDir.resolve("lab")` 생성/조회. 바로 아래 wav/m4a/mp3/ogg 파일을 이름순으로 정렬. 크기와 가능한 경우 재생 시간 반환 |
| `app/src/debug/java/com/dongbantest/app/debug/TalkRecLabActivity.kt` | 내부 파일 목록, 새로고침, 선택 표시, 빈 폴더의 추가 명령 안내. 선택 파일을 `Uri.fromFile()`로 기존 `startFile()`에 연결. `소리도 같이 재생` 유지 |
| `app/src/debug/AndroidManifest.xml` | 선택적 ACTION_OPEN_DOCUMENT 지원 확인을 위한 debug 전용 `<queries>` 추가. 권한 추가 없음 |
| `talkrec/src/debug/java/com/drivediary/talkrec/debug/DecodedAudioFileSource.kt` | `file://`이면 FileInputStream의 FileDescriptor로 MediaExtractor 입력 설정. 기존 `content://` 경로 유지 |
| `talkrec/src/debug/java/com/drivediary/talkrec/debug/TalkRecLab.kt` | `startFile`의 content/file URI 지원을 API 주석에 명시. 기존 URI API로 두 경로를 모두 처리하므로 File overload 불필요 |
| `talkrec/tools/push-lab-audio.ps1` | PowerShell 5.1 호환, UTF-8 BOM. 단일 파일/폴더, 선택적 Serial, 기본 User 10, 명시적 이름으로 복사, 임시 파일 정리, 최종 내부 폴더 목록 |
| `talkrec/README.md` | 실험실 파일 추가/선택 방법, debug/run-as 요건, 실제 음성 반입 금지 원칙 안내 |

파일 목록과 메타데이터 조회는 `Dispatchers.IO`에서 실행한다. MediaExtractor의 오디오 트랙 헤더에 `KEY_DURATION`이 있는 경우만 분:초를 표시하고, 없거나 조회에 실패하면 시간을 생략한다. 전체 디코딩/길이 추정 스캔은 하지 않는다. 크기는 1 MB = 1,000,000 bytes로 소수 둘째 자리까지 표시한다. 140dp 높이의 스크롤 목록으로 실시간 결과 영역을 남긴다.

`다른 파일 고르기`는 보조 옵션이다. 실제 OpenDocument 계약이 생성하는 Intent의 `resolveActivity(packageManager)` 결과가 없으면 버튼을 비활성화하고 시스템 선택기 미지원 안내를 표시한다. 새로고침/실제 실행 직전에도 지원 여부를 재확인하고, 실행 시 ActivityNotFoundException도 처리한다. DocumentsUI가 없는 AVD에서는 내부 목록만으로 선택 → 시작할 수 있다.

파일 선택과 재생은 기존 LabSink, AudioTrack, 다운믹스/16 kHz 리샘플러, 100ms 프레임, EOF 후 25초 무음 처리 경로를 공유한다. 입력 변경은 기존처럼 정지 후 정리가 끝난 상태에서 가능하다. 파일 목록 새로고침 후 삭제된 내부 파일이 선택돼 있으면 선택을 해제한다. 목록과 선택 이름/URI는 Activity 메모리만 사용하고 종료 시 비운다.

새 파일명/경로/예외 본문 로그는 추가하지 않았다. 내부 파일 입력과 메타데이터 조회는 디스크립터를 전달해 네이티브 디코더에 사용자 파일명 문자열을 전달하지 않는다. 앱 로그 대신 화면에서만 이름을 표시하며, 디코딩 실패는 기존 안전한 AudioFileError 코드로 전달한다. 복사 스크립트의 마지막 콘솔 목록은 요청한 `ls -lh files/lab` 출력이다.

## 파일 추가 방법

해당 Android user에 **debug 빌드**가 설치돼 있어야 하며 `adb`가 PC의 PATH에 있어야 한다. 저장소 밖의 로컬 시험 자료를 지정한다.

```powershell
# 폴더 바로 아래 wav/m4a/mp3/ogg. 하위 폴더는 제외.
.\talkrec\tools\push-lab-audio.ps1 -Path "C:\audio" -User 10

# 단일 파일, 특정 AVD 지정. -User를 생략하면 10.
.\talkrec\tools\push-lab-audio.ps1 -Path "C:\audio\lab-convert-44k-stereo.wav" -Serial emulator-5554
```

파일별 실행 순서는 다음과 같다. 실제 스크립트는 공백·작은따옴표·셸 특수문자가 있는 파일명을 인자로 안전하게 전달하며, 실패 시에도 finally에서 임시 복사본을 삭제한다.

```text
adb [-s SERIAL] push <로컬 파일> /data/local/tmp/<이름>
adb [-s SERIAL] shell chmod 644 /data/local/tmp/<이름>
adb [-s SERIAL] shell run-as com.dongbantest.app --user 10 mkdir -p files/lab
adb [-s SERIAL] shell run-as com.dongbantest.app --user 10 cp /data/local/tmp/<이름> files/lab/<이름>
adb [-s SERIAL] shell rm -f /data/local/tmp/<이름>
# 모든 복사가 끝나면 한 번
adb [-s SERIAL] shell run-as com.dongbantest.app --user 10 ls -lh files/lab
```

`/data/local/tmp/*` glob은 쓰지 않는다. 같은 이름의 내부 파일은 덮어쓴다. 앱 데이터 삭제/앱 제거 시 내부 자료도 삭제된다. **실제 음성은 APK/assets/repo에 포함하지 않는다.** 이번 변경에 음성 자료를 추가하지 않았다.

복사 후 **동반차 디버그 → 인식 실험실 → 음성 파일 → 새로고침 → 파일 선택 → 시작**한다. 필요하면 `소리도 같이 재생`을 켠다. 기존 microphone-type FGS 때문에 파일 모드에서도 RECORD_AUDIO 요청은 유지되며 추가 권한은 없다.

## 검증

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i8c-types.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i8c-ui.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i8c-push.ps1
```

- 캐시된 Kotlin compiler 2.2.10/JDK로 코어 타입 검사 통과. 기존 lab 소유권/정리 상태 호스트 테스트 **2개 통과**.
- 캐시된 실제 Compose/Lifecycle 라이브러리와 Compose compiler plugin으로 Activity 및 새 `LabAudioFiles.kt` 타입 검사 통과.
- Android SDK 대신 기존 I8/I8b의 호스트 대체 타입을 I8c 별도 폴더로 복사하고 FileDescriptor, Uri, 메타데이터, picker API 선언만 보강했다. 기존 검증 fixture는 수정하지 않았다.
- Windows PowerShell 5.1에서 스크립트 구문 파싱 및 UTF-8 BOM(`EF BB BF`) 확인.
- 모의 `adb.exe`로 native process argv를 기록해 폴더/단일 파일, 대소문자 확장자, 하위 폴더 제외, 공백·작은따옴표·달러·앰퍼샌드·세미콜론·백틱·한글 파일명의 인자 보존/셸 인용 확인.
- 모든 adb 명령의 Serial 전달, 기본 User 10/명시 User 0, 명시적 cp, 최종 목록, cp 실패 시 중단/임시 파일 삭제, 미지원 확장자의 adb 호출 전 거부 확인.
- debug manifest XML과 OPEN_DOCUMENT query, 변경한 소스의 공백, tracked 변경의 `git diff --check` 통과. Git의 LF→CRLF 안내만 있었다.
- 관련 Activity/helper/decoder/facade에서 Log/println/printStackTrace/Timber 호출 없음 확인.

위 검사는 **실제 Android 빌드·manifest merge·UI 렌더링·플랫폼 디코더 동작을 검증한 결과가 아니다.** Gradle은 요청대로 실행하지 않았다. `.talkrec-notes/`는 기존 로컬 git exclude 대상이므로 인계 문서와 검사 스크립트/fixture는 아래 상태에 표시되지 않는다.

## 열린 항목 / Claude의 AVD 확인

코드상 확인된 차단 문제는 없다. 다음 실제 검증은 Claude가 이어서 수행해야 한다.

1. Gradle debug 빌드/설치, release에서 실험실 제외, manifest query 병합 확인.
2. DocumentsUI 없는 Pleos AVD user 10에서 내부 목록과 비활성 `다른 파일 고르기` 안내 확인. 제공된 현황의 `lab-short-1min.wav`, `lab-long-5min.wav`, `lab-full-10min.wav`, `lab-convert-44k-stereo.wav`가 이름순으로 나타나는지 확인. 이번 작업에서 AVD 파일을 직접 조회하지 않았다.
3. 앞의 16 kHz mono 세 파일의 선택/시작/Partial/Final/EOF(+25초 drain), 소리 재생 켜짐/꺼짐과 정지 후 재시작 확인.
4. `lab-convert-44k-stereo.wav`로 44.1 kHz stereo → mono 16 kHz 변환, 인식과 재생 확인. 기존 변환 구현은 변경하지 않았다.
5. 실제 adb/run-as 복사, 다른 Serial/User, 빈 폴더 안내 → 파일 추가 → 새로고침, 삭제된 선택 해제 확인. 실제 음성 파일명/경로가 앱 logcat에 나오지 않는지 확인.
6. 실제 2560×1440 화면에서 목록/버튼/결과 패널 높이와 파일명 표시 확인. 메타데이터가 없거나 손상된 파일도 목록에 남고, 시작 시 안전한 오류 안내를 표시하는지 확인.

## 최종 git status --short

아래는 기존 I8/I8b/I9b 작업을 포함한 전체 작업트리 상태다. I8c에서 직접 변경한 소스/문서는 위 표의 7개 경로이며, 그중 기존 untracked Activity/decoder/facade도 계속 untracked로 표시된다.

```text
 M app/src/debug/AndroidManifest.xml
 M app/src/debug/java/com/dongbantest/app/debug/V3CatalogActivity.kt
 M app/src/main/AndroidManifest.xml
 M app/src/main/java/com/dongbantest/app/ui/v3/README.md
 M app/src/main/java/com/dongbantest/app/ui/v3/components/Basics.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/components/DrivingParts.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/DiaryScreens.kt
 M app/src/main/java/com/dongbantest/app/ui/v3/screens/SampleData.kt
 D app/src/main/java/com/dongbantest/app/ui/v3/screens/ScreenCatalog.kt
 M app/src/main/java/com/dongbantest/app/ui/v3connect/V3Mappers.kt
 M talkrec/README.md
 M talkrec/src/main/assets/prompts/summary.md
 M talkrec/src/main/java/com/drivediary/talkrec/TalkRec.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/CaptureProcessor.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/ConversationCaptureService.kt
 M talkrec/src/main/java/com/drivediary/talkrec/capture/PleosSttClient.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/DisplayTags.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryProviders.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryResult.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummarySettingsStore.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryValidator.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/DisplayTagsTest.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryProcessorTest.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryProvidersTest.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryTestData.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryValidatorTest.kt
?? app/src/debug/java/com/dongbantest/app/debug/LabAudioFiles.kt
?? app/src/debug/java/com/dongbantest/app/debug/TalkRecLabActivity.kt
?? app/src/debug/res/
?? app/src/main/res/drawable/ic_dongban_car_background.xml
?? app/src/main/res/drawable/ic_dongban_car_foreground.xml
?? app/src/main/res/mipmap-anydpi-v26/
?? talkrec/src/debug/java/com/drivediary/talkrec/debug/DecodedAudioFileSource.kt
?? talkrec/src/debug/java/com/drivediary/talkrec/debug/LabSink.kt
?? talkrec/src/debug/java/com/drivediary/talkrec/debug/Mono16kResampler.kt
?? talkrec/src/debug/java/com/drivediary/talkrec/debug/TalkRecLab.kt
?? talkrec/src/main/java/com/drivediary/talkrec/TalkRecDebug.kt
?? talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryTryOut.kt
?? talkrec/src/test/java/com/drivediary/talkrec/capture/CaptureProcessorDebugTest.kt
?? talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryTryOutTest.kt
?? talkrec/src/testDebug/
?? talkrec/tools/push-lab-audio.ps1
```
