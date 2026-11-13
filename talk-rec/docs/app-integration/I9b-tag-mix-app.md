# I9b — Android MIXED 태그 계약 적용

작성일: 2026-11-13. 작업 위치: `C:\github\drive-diary-int`, 브랜치: `feat/talk-rec-integration`.

고정 분류 1개와 LLM 키워드 0~2개를 받도록 요약 결과·검증·제공자·v3 태그 표시를 변경했다. 기존 DB의 태그 JSON 배열을 그대로 사용하며 마이그레이션은 없다. 커밋·푸시·브랜치 전환 및 Gradle 실행은 하지 않았다.

## 변경 파일

| 파일 | 변경 내용 |
| --- | --- |
| `talkrec/src/main/assets/prompts/summary.md` | I9a 새 프롬프트를 바이트 그대로 복사 |
| `talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryResult.kt` | `category`, `keywords` 추가, `tags`는 매번 `[category] + keywords`로 계산 |
| `talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryValidator.kt` | 프롬프트 분류 파싱과 MIXED 구조 검증·정규화 |
| `talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryProviders.kt` | Gemini JSON schema, Gemini/AGY 공통 새 필드 검증, Fake 결과 형식 변경 |
| `talkrec/src/main/java/com/drivediary/talkrec/summary/DisplayTags.kt` | MIXED 및 과거 배열의 순서 보존 설명 추가; 표시 함수 동작은 유지 |
| `app/src/main/java/com/dongbantest/app/ui/v3connect/V3Mappers.kt` | DONE 행의 첫 LLM 태그를 표시용 분류로 전달 |
| `app/src/main/java/com/dongbantest/app/ui/v3/components/Basics.kt` | 분류 칩에 옅은 1dp 테두리 옵션 추가 |
| `app/src/main/java/com/dongbantest/app/ui/v3/components/DrivingParts.kt` | 첫 LLM 태그에 분류 테두리 적용; 기존 주행 2개 제한 유지 |
| `app/src/main/java/com/dongbantest/app/ui/v3/screens/DiaryScreens.kt` | 다이어리의 첫 분류 칩에 테두리 적용 |
| `app/src/main/java/com/dongbantest/app/ui/v3/screens/SampleData.kt` | `TalkUi.category` 표시 메타데이터 추가; 샘플 내용은 유지 |
| `talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryValidatorTest.kt` | 분류 13개, 필수 필드·문자·길이·공백·중복·펜스·JSON 배열 왕복 검증 |
| `talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryProvidersTest.kt` | Gemini schema/파싱, AGY 응답 파싱과 `tags` 무시, 동일 검증·재요청 확인 |
| `talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryProcessorTest.kt` | 새 생성자 적용, 잘못된 키워드 1회 재요청 후 FAILED, 정규화된 태그 저장 확인 |
| `talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryTestData.kt` | 공통 fixture를 새 JSON으로 변경 |
| `talkrec/src/test/java/com/drivediary/talkrec/summary/DisplayTagsTest.kt` | MIXED 뒤 기기 태그, 기존 고정 태그 배열 표시 확인 |
| `talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryTryOutTest.kt` | 동시 작업의 기존 테스트에서 `SummaryResult` 생성자 한 줄만 새 형태에 맞춤 |

검증 재현용으로 `.talkrec-notes/verify-i9b-jvm.ps1`, `.talkrec-notes/verify-i9b-types.ps1`도 작성했다. 기존 캐시 컴파일러 스크립트를 기반으로 결과 디렉터리를 `i9b-*`로 분리했다. `.talkrec-notes`는 기존 Git ignore 대상이다.

I8b가 편집하는 `app/src/debug/**`, 아이콘 리소스, main manifest, `ScreenCatalog.kt`, `talkrec/src/debug/**`, capture debug 테스트, `TalkRecDebug.kt`는 수정하지 않았다. 시작 시 이미 변경되어 있던 `SummarySettingsStore.kt`와 새 `SummaryTryOut.kt`도 수정하지 않았다. 설정 호출부의 소스 호환을 위해 `SummaryPrompt.tags`는 파싱한 `categories`를 반환하는 별칭으로 유지했다. 별도의 분류 상수 목록은 없다.

## 프롬프트와 분류

원본은 `C:\github\drive-diary\talk-rec\model-create\prompts\summary.md`다. 외부 작업 트리는 읽기만 했다.

- 복사본과 원본 SHA-256이 일치한다: `63E3F3AFFB0712B0B41C9E224EB2890BBDEF8E52C30D21249EEC52143780E7CC`.
- BOM 없는 UTF-8, LF이며 CR 바이트가 없음을 확인했다.
- `## 분류`부터 다음 `##` 제목 전까지 쉼표가 있는 유일한 줄에서 분류를 읽는다. 항목 앞뒤 공백을 제거하고 목록 누락·모호함·빈 항목·중복을 거부한다. LF/CRLF를 모두 처리한다.
- 복사된 프롬프트에서 정확한 13개 분류를 읽는 테스트를 통과했다. 목록 전체는 테스트 기대값에만 있으며 제품 코드에는 중복 정의하지 않았다.

## 검증 규칙

입력 JSON 필수 필드는 `title`, `category`, `keywords`, `kind`다. 추가 `tags` 필드는 읽지 않는다. 예전 `{title, tags, kind}`만 가진 LLM 응답은 실패한다.

- 제목: 문자열, 원문 1~25 Unicode 코드 포인트, 공백만인 값 금지, CR/LF 금지, 짝이 맞지 않는 surrogate 금지. 제목은 trim하지 않는다. I9a와 같이 U+2028/U+2029를 별도로 금지하지 않는다.
- 분류: 프롬프트에서 읽은 목록 중 하나와 정확히 일치해야 한다. 자동 trim이나 치환은 없다.
- 키워드: 필수 배열, 0~2개. 각 문자열은 Python `strip()`과 같은 바깥 공백 제거 후 `[가-힣A-Za-z0-9·]{1,6}`에 완전히 일치해야 한다. 내부 공백, 이모지, 한자, 분리 자모, 다른 구두점 등은 거부한다.
- 숫자만인 키워드, 어느 분류와든 같은 키워드, trim 후 영문 대소문자를 무시한 중복은 거부한다. 정규화된 순서와 영문 대소문자는 보존한다. 잘못된 원소를 삭제하거나 개수 초과를 잘라내지 않는다.
- 종류: 문자열 `story` 또는 `moment`만 허용한다.
- 출력 바깥 공백과 전체를 감싼 JSON/일반 코드 펜스를 제거한다. 기존 엄격 JSON 검사와 원문을 포함하지 않는 오류 처리는 유지한다.

제공자는 각각 공통 `SummaryValidator`를 사용한다. Gemini는 분류 enum과 최대 2개 자유 키워드를 schema에 요청한다. AGY는 릴레이의 `category`, `keywords`를 검증하고 호환 `tags`는 무시한다. 실제 응답 모델 처리와 요청 필드는 유지한다.

기존 `validatedSummary`/`SummaryProcessor`의 최초 요청 + 검증 실패 재요청 1회, 두 번째도 실패하면 FAILED 동작은 유지한다. 릴레이의 `502 invalid_output`는 서버에서 이미 재요청까지 끝났으므로 앱 요청을 추가하지 않는다. 200이지만 형식이 잘못된 응답은 앱에서 한 번 재요청한다. 기존 네트워크 오류 backoff 정책도 유지한다.

## 저장 형식과 과거 행

`SummaryResult.tags`는 항상 `listOf(category) + keywords`다. `RepositorySummaryTarget.save()`가 이를 기존 `TripRepository.updateTalkSummary()`로 전달하며 저장소는 기존 `talk_meta.tags` JSON 문자열 열에 쓴다. 예: `["추억","만두","신발"]`, 키워드가 없으면 `["추억"]`.

저장 어댑터·DB 스키마·DB 버전은 변경하지 않았다. 기존 저장소가 배열을 순서대로 복원하며 읽을 때 새 LLM 검증을 적용하지 않으므로, 과거의 `["가족 이야기","음식","자연·풍경"]`도 표시된다. 과거 행 재요약이나 backfill은 하지 않는다. 분류와 키워드는 결과 객체에서 분리되지만 DB에는 호환 배열로 저장한다.

## 표시 동작

`displayTags()` 순서는 분류 → LLM 키워드 → 목적지 → 한국어 요일 → 시간대다. 기존 공백/중복 제거는 유지한다. 기기 태그는 표시 때만 추가하며 LLM 태그 저장 값이나 제공자 요청에 넣지 않는다.

v3 다이어리는 요약 DONE 행의 첫 LLM 태그만 분류로 표시해 얇은 테두리를 준다. PENDING/FAILED/SKIPPED 행의 목적지·요일·시간대에는 분류 테두리를 주지 않는다. 기존 다이어리 3개 표시 제한은 유지한다. 주행 목록은 기존대로 LLM 태그만 사용하고 최대 2개이므로 새 행에서는 분류 + 첫 키워드가 보인다. 제목 숨김 설정도 유지한다.

전사 원문을 화면에 표시하는 경로는 추가하지 않았다. 매핑은 기존 요약 제목 또는 중립 fallback을 사용한다.

## 실행한 검증

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i9b-jvm.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i9b-types.ps1
git diff --check
```

- 캐시 Kotlin 2.2.10, JVM target 11, coroutines 1.8.0, JSON 20240303, JUnit 4.13.2 사용. Gradle은 실행하지 않았다.
- **JVM 테스트 46개 통과**, 최종 실행 `OK (46 tests)`, 0.254초. Validator/Providers/Processor/DisplayTags/SummaryTryOut 테스트를 포함한다.
- 정상 값, 모든 분류, 미등록 분류, 키워드 3개·7자·내부 공백·숫자 전용·분류와 동일·대소문자 중복·비허용 문자 거부, 외부 공백 정규화, 빈 키워드 허용, 코드 펜스, 제목 경계·UTF-8 가능 여부, 13개 분류 파싱을 검증했다.
- Gemini/AGY 새 응답 파싱과 동일 실패 처리, 임의의 응답 `tags` 무시, 요청 schema, `[category]+keywords` JSON 배열 왕복, 기존 고정 태그 행 표시, 한 번 재요청 후 FAILED/정상화된 저장 결과를 검증했다.
- **호스트 타입 검사 통과**: 실제 summary 소스, `SummarySettingsStore`, `SummaryWorker`, `RepositorySummaryTarget`와 기존 Android/WorkManager 수작성 stand-in을 컴파일했다. Android API 실행 검증은 아니다.
- 프롬프트 바이트 일치·BOM 없음·LF 확인 및 `git diff --check` 통과. 기존 `URL(String)` JVM deprecation과 Git LF→CRLF 안내만 나왔다.

## 남은 확인 사항

- Claude 환경에서 Gradle `:talkrec:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`와 AVD 확인이 필요하다. 이번 호스트 검사에는 Compose 화면 컴파일·실행, 실제 Room/WorkManager/Keystore가 포함되지 않는다.
- 새 코드와 새 프롬프트를 읽은 릴레이 인스턴스로 AGY 연동을 확인해야 한다. 기존 프로세스는 시작 시 읽은 과거 프롬프트를 계속 사용할 수 있다. 이 작업은 실행 중인 릴레이를 종료·재시작하지 않았다.
- AVD에서 새 요약 저장 후 배열 첫 원소/키워드 순서, 주행 중 최대 2개, 다이어리 분류 테두리, 실패·건너뜀 행의 기기 태그, 과거 고정 태그 행을 확인한다.
- 이름·브랜드·민감한 내용 배제 등 의미 판단은 I9a 프롬프트에 의존한다. 문자/구조 검증과 실제 모델 출력 품질 평가는 구별해야 한다. 실제 전사·실제 유료 키·실제 모델 생성 호출은 사용하지 않았다.

## git status --short

아래는 기록 시점의 worktree 전체 상태다. I9b 변경만의 목록이 아니며 동시 I8b 및 시작 전 변경을 포함한다.

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
```
