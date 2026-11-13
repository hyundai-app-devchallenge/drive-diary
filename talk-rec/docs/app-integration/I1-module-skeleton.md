# I1 모듈 골격 작업 기록

작성일: 2026-11-13

**결과: 모듈 골격과 자산 추가 완료. Codex 샌드박스에서는 SDK 읽기 권한으로 빌드가 막혔고, Claude가 샌드박스 밖에서 팀 설정 그대로 다시 빌드해 통과했다(아래 "샌드박스 밖 검증").**

## 샌드박스 밖 검증 (Claude, 2026-11-13)

- 명령: `.\gradlew.bat :app:assembleDebug :talkrec:testDebugUnitTest` (기본 Gradle 홈, 팀의 daemon JVM 설정 그대로, 환경 변수 바꾸지 않음).
- 결과: `BUILD SUCCESSFUL in 2m 19s`. 새 경고 없음(팀 코드의 기존 deprecation 경고 2건만).
- debug APK 크기 268,654,697 bytes. 팀 앱만의 크기와 아직 비교하지 않았다. Mapbox·ONNX Runtime의 모든 ABI 네이티브 라이브러리가 들어간다. I7에서 비교하고 필요하면 ABI를 줄인다.
- **앞으로의 작업 방식**: Codex 샌드박스는 Android SDK를 읽지 못해 Gradle 빌드를 할 수 없다. Codex가 코드를 쓰고, Claude가 밖에서 빌드·테스트해 오류를 다시 넘긴다.

## 작업 범위와 구현

`C:\github\drive-diary-int` worktree의 `feat/talk-rec-integration`에서 작업했다. 시작 HEAD는 `e5279c579224998fecc9de0a7596c6a696aac1cc`이며 브랜치 전환, 커밋, 푸시는 하지 않았다. 설계 문서 §3·§4·§5·§8·§10과 앞선 차단 기록을 읽었다. 원본 `C:\github\drive-diary\talk-rec`은 지정 문서 및 자산 읽기에만 사용했다.

Android 라이브러리 `:talkrec`을 추가했다. namespace는 `com.drivediary.talkrec`, compileSdk 37, minSdk 28, Java 소스·타깃 11이다. 팀 앱과 같이 AGP 내장 Kotlin을 사용한다. 별도의 Kotlin Android 플러그인은 추가하지 않았다.

다음 12개 타입에 한 줄 한국어 KDoc을 넣었다.

- `TalkRec`: 팀 앱에 제공할 진입점.
- `capture/ConversationCaptureService`: `Service` 상속, `onBind`는 null. manifest에는 등록하지 않았다.
- `capture/PleosSttClient`, `capture/PcmRingBuffer`: 연속 STT와 메모리 버퍼 골격.
- `block/BlockAssembler`, `policy/PolicyGate`, `judge/ConversationJudge`: 묶음 조립·정책·M1 판정 골격.
- `clip/ClipWriter`, `sink/TalkSink`: 음성 저장 및 팀 앱 연결 경계. `TalkSink`는 빈 인터페이스다.
- `summary/SummaryProvider`: `suspend fun summarize(transcript: String): SummaryResult`.
- `summary/SummaryResult`: `title: String`, `tags: List<String>`, `kind: String`.
- `summary/SummaryWorker`: `CoroutineWorker` 상속. 아직 처리하지 않으므로 `Result.failure()`를 반환하며 작업을 예약하지 않는다.

골격 이외의 녹음·추론·외부 통신·요약 검증은 구현하지 않았다. UI, Room, 권한, 동의 흐름, AppContainer는 변경하지 않았다. `TalkRecTest.facadeCanBeCreated` 한 개를 추가하여 JUnit 진입점을 마련했다. 테스트 입력에 전사 글은 없다.

## 팀 파일 변경과 이유

| 파일 | 변경 이유 |
| --- | --- |
| `build.gradle.kts` | 라이브러리 플러그인 별칭을 `apply false`로 선언해 AGP의 버전을 루트에서 함께 해석 |
| `settings.gradle.kts` | `include(":talkrec")`로 새 모듈 등록 |
| `app/build.gradle.kts` | `implementation(project(":talkrec"))`로 팀 앱 의존성 연결, 최종 APK의 ONNX 무압축 설정 |
| `gradle/libs.versions.toml` | 기존 AGP 버전의 Android library 플러그인 별칭과 ONNX·WorkManager·Pleos STT 의존성 등록 |

새로 추가한 주석은 한국어로 작성했다. 최상위 `build.gradle.kts`는 라이브러리 플러그인의 버전 해석을 위한 한 개 별칭 선언과 한국어 주석만 추가했다.

## 버전 선택

| 항목 | 버전 |
| --- | --- |
| AGP | 기존 9.5.1 |
| Kotlin 카탈로그 | 기존 2.2.10 (모듈은 AGP 내장 Kotlin 사용) |
| Gradle wrapper | 기존 9.6.0 |
| JDK | 지정된 Microsoft 21.0.12.101-hotspot |
| ONNX Runtime Android | `com.microsoft.onnxruntime:onnxruntime-android:1.23.2` |
| WorkManager | `androidx.work:work-runtime-ktx:2.12.0` |
| Pleos STT | `ai.pleos.playground:SpeechToText:3.3.1` |
| JUnit | 기존 별칭의 4.13.2 |

WorkManager 2.12.0은 [공식 안정 릴리스](https://developer.android.com/jetpack/androidx/releases/work/)를 확인해 선택했다(2026-09-23 배포, minSdk 24). 실제 의존성 해석 성공 여부는 아래 검증 결과와 구분한다. 별도 kotlinx-coroutines 의존성은 현재 골격에 필요하지 않아 추가하지 않았다. Pleos Maven은 기존 설정을 사용한다. OkHttp/Retrofit 직접 의존성은 추가하지 않았으며 후속 HTTP 구현은 HttpURLConnection을 사용한다.

## 복사한 자산

| 원본 (`C:\github\drive-diary\talk-rec` 기준) | 대상 (`talkrec/src/main/assets` 기준) | 크기(bytes) |
| --- | --- | ---: |
| `model-create/.local/export/m1/model.int8.onnx` | `m1/model.int8.onnx` | 14,731,423 |
| `model-create/.local/export/m1/vocab.txt` | `m1/vocab.txt` | 263,327 |
| `model-create/.local/export/m1/threshold.json` | `m1/threshold.json` | 443 |
| `model-create/.local/export/m1/tokenizer_settings.json` | `m1/tokenizer_settings.json` | 2,609 |
| `model-create/policy/rules.json` | `policy/rules.json` | 5,963 |

합계 15,003,765 bytes. 다섯 파일 모두 원본·대상 SHA-256이 같다. 특히 `threshold.json`은 변경하지 않았고, 문턱 0.957 적용은 I2에서 한다. `.local`의 다른 파일은 복사하지 않았다. `golden.json`, `predictions.json`, `labels.json`, `results.json`과 비공개 전사 데이터는 가져오지 않았다.

요청에 따라 Git LFS를 설정하지 않았다. `git check-attr filter -- talkrec/src/main/assets/m1/model.int8.onnx` 결과는 `unspecified`다. 모델을 일반 Git 바이너리로 관리하므로 향후 모델 교체에 따른 저장소 크기 증가는 팀이 판단할 사항이다. 라이브러리와 최종 APK를 만드는 앱 양쪽에 `androidResources { noCompress += "onnx" }`를 설정했다. 최종 APK 내 실제 압축 방식은 빌드 성공 후 별도 확인이 필요하다.

## 빌드 방법

`git check-ignore -v local.properties`로 `.gitignore:40:/local.properties` 적용을 확인한 다음 아래 내용을 작성했다.

```properties
sdk.dir=C\:\\Users\\ip936\\AppData\\Local\\Android\\Sdk
```

PowerShell:

```powershell
Set-Location -LiteralPath 'C:\github\drive-diary-int'
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME = 'C:\github\drive-diary-int\.gradle-home'
$env:ANDROID_USER_HOME = 'C:\github\drive-diary-int\.gradle-home\android'
# 기존 daemon 기준(JDK 25)이 JAVA_HOME보다 우선하므로 실행 동안만 보관한다.
$taskCriteriaPath = 'C:\github\drive-diary-int\gradle\gradle-daemon-jvm.properties'
$taskCriteriaBackup = 'C:\github\drive-diary-int\.talkrec-notes\gradle-daemon-jvm.properties.backup'
Move-Item -LiteralPath $taskCriteriaPath -Destination $taskCriteriaBackup
try {
    .\gradlew.bat :app:assembleDebug :talkrec:testDebugUnitTest --console=plain --no-daemon
} finally {
    Move-Item -LiteralPath $taskCriteriaBackup -Destination $taskCriteriaPath
}
```

`local.properties`, `.gradle-home/`, `.talkrec-notes/`는 모두 기존 ignore 규칙을 확인했다. 뒤의 두 경로는 공통 `.git/info/exclude`에 이미 등록되어 있어 Git 설정을 수정하지 않았다. 전역 설치는 하지 않았다. Mapbox 토큰을 추가하지 않았으며 앱의 기존 빈 문자열 기본값을 유지했다.

## 검증 결과

최종 실행은 종료 코드 1, `BUILD FAILED in 32s`다. JDK 21로 앱과 라이브러리 플러그인 설정을 지나 작업 그래프를 계산하는 중 기존 Android SDK 파일 읽기가 차단됐다.

```text
Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'.
> Failed to create MD5 hash for file: C:\Users\ip936\AppData\Local\Android\Sdk\build-tools\36.0.0\source.properties (액세스가 거부되었습니다)
```

이는 Maven 저장소의 특정 좌표를 찾지 못한 오류가 아니라 지정 SDK 경로의 읽기 권한 오류다. 작업 영역 밖 SDK의 권한이나 파일을 변경하지 않았다. 같은 환경에서는 컴파일·테스트를 진행할 수 없어 이 오류를 최종 차단 사유로 남긴다. SDK 읽기를 허용하는 환경에서 위 명령을 다시 실행해야 한다.

- `:app:assembleDebug`: 미통과, 작업 그래프 구성 중 차단.
- 새 모듈 Kotlin 컴파일: 미실행.
- `:talkrec:testDebugUnitTest`: 미실행. 테스트 성공을 주장하지 않는다.
- ONNX·WorkManager·Pleos STT의 실제 아티팩트 해석 및 전이 의존성 호환성: 완전 검증하지 못함. WorkManager는 공식 최신 안정판을 선택했으나 실제 resolvable 여부는 후속 재실행에서 확인해야 한다.
- 최종 APK 생성과 ONNX ZIP 저장 방식 확인: 미실행.
- 지정 자산 5개 원본·대상 SHA-256 일치, 전사 데이터 파일 미복사, Git LFS 미설정: 확인.
- `git diff --check`: 통과.
- daemon JVM 기준 파일: 실행 후 원위치에 복원했고 `git diff --exit-code -- gradle/gradle-daemon-jvm.properties`가 통과했다. 임시 backup도 남아 있지 않다.

초기 실행은 기본 Gradle user home에 잠금 파일 디렉터리를 생성하지 못했다.

```text
Exception in thread "main" java.lang.RuntimeException: Could not create parent directory for lock file C:\.gradle\wrapper\dists\gradle-9.6.0-bin\42k10rwplmzkhuboz9kdazi7s\gradle-9.6.0-bin.zip.lck
```

worktree 내부 `.gradle-home`으로 재실행했고 Gradle 9.6.0 다운로드 및 데몬 시작까지 진행했다. 로그는 같은 notes 폴더의 `I1-build.log`에 저장한다. Windows curl.exe는 사용하지 않았다. 사전 PowerShell SDK platforms 폴더 조회는 접근 거부였다. 이후 실제 Gradle 실행에서도 위의 SDK 파일 읽기 권한 오류를 확인했다.

`git diff --check`는 통과했다. 단말 실행, 음성 캡처, 모델 추론, 외부 요약 동작은 I1 검증 범위에 포함하지 않았다.

### 환경 우회 사항

`.gradle-home` 재실행에서는 앱 플러그인 적용 중 Android 로컬 설정 경로 `C:\.android` 쓰기가 차단됐다.

```text
Unable to initialize metrics, ensure C:\.android is writable, details: C:\.android\analytics.settings (지정된 경로를 찾을 수 없습니다)
An exception occurred applying plugin request [id: 'com.android.application', version: '9.5.1']
> Failed to apply plugin 'com.android.internal.application'.
   > C:\.android
```

따라서 `ANDROID_USER_HOME`도 git-ignored `.gradle-home/android`로 지정했다.

또한 팀의 기존 `gradle/gradle-daemon-jvm.properties`에는 `toolchainVersion=25`가 있어 JAVA_HOME보다 우선한다. 최초 재실행에서 Gradle이 JDK 25를 worktree 내부 `.gradle-home/jdks`에 자동 내려받았다(전역 설치 아님). 요청한 JDK 21로 검증하기 위해 해당 설정 파일을 `.talkrec-notes`에 잠시 옮기고 `try/finally`로 원위치에 복원하는 실행 방법을 사용했다. 팀의 daemon 설정 내용은 변경하지 않는다. JDK 21 재실행의 실제 daemon 로그에서 `javaHome=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`, `javaVersion=21`, `javaVendor=Microsoft`를 확인했다.

### AGP 플러그인 해석 수정

루트에 application 플러그인만 선언하고 모듈에서 library 플러그인의 버전 별칭을 사용했을 때 다음 오류가 발생했다.

```text
Error resolving plugin [id: 'com.android.library', version: '9.5.1']
> The request for this plugin could not be satisfied because the plugin is already on the classpath with an unknown version, so compatibility cannot be checked.
```

루트 `build.gradle.kts`에도 `alias(libs.plugins.android.library) apply false`를 선언하여 같은 AGP 버전을 루트에서 함께 해석하도록 수정했다. 이 수정 이후 두 태스크를 다시 실행했다. 최종 실행 로그는 `I1-build-final.log`다.


## 최종 Git 상태

git status --short:

```text
 M app/build.gradle.kts
 M build.gradle.kts
 M gradle/libs.versions.toml
 M settings.gradle.kts
?? talkrec/
```

git diff --stat:

```text
 app/build.gradle.kts      | 7 +++++++
 build.gradle.kts          | 2 ++
 gradle/libs.versions.toml | 8 ++++++++
 settings.gradle.kts       | 2 ++
 4 files changed, 19 insertions(+)
```

`git diff --stat`에는 아직 untracked인 `talkrec/`가 포함되지 않는다. 새 모듈은 20개 파일(빌드 설정·빈 manifest·12개 타입·1개 테스트·자산 5개)이며 staging하지 않았다. 브랜치와 HEAD는 시작 시점 그대로이고 커밋·푸시·브랜치 전환은 하지 않았다. 작업 노트 및 빌드 로그는 git-ignored `.talkrec-notes`에만 저장했다.
