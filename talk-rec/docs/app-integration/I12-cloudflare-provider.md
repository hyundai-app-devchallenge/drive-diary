# I12 — Cloudflare Workers AI 전환

작성일: 2026-11-13. 작업 위치: `C:\github\drive-diary-int`, 브랜치: `feat/talk-rec-integration`.
Gradle, 실제 AI 요청, AVD 설치·실행, 커밋·푸시·브랜치 전환은 하지 않았다.

## 변경 사항

- `GeminiSummaryProvider`와 Gemini 요청/인증 코드를 제거했다. 실제 앱 외부 AI 기본값은 **Cloudflare Workers AI**, 모델 기본값은 `@cf/google/gemma-4-26b-a4b-it`이다. 모델 ID는 편집할 수 있다.
- `CloudflareSummaryProvider`가 단일 제목 요약과 이야기 나누기를 모두 처리한다. 기존 `summary.md`, `story_split.md` asset을 변경하지 않았다. 프롬프트 전체는 system 메시지, 전사 또는 `[1]\n전사\n\n[2]\n전사` 형식의 목록은 user 메시지로 보낸다.
- POST 경로는 `https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1/chat/completions`, 인증은 Bearer 토큰이다. `max_completion_tokens=1024`, `temperature=0.2`, 읽기 timeout은 **60초**, 연결 timeout은 기존 **30초**다.
- 처음에는 `response_format: {type: json_schema, json_schema: ...}`를 보낸다. 인증/요청량 제한 등을 제외한 4xx 응답이 `response_format`, JSON schema/mode 문제를 가리키면 이 필드 없이 **한 번만** 다시 보낸다. 미지원 모델 ID는 프로세스 메모리에 기억하므로 다음 provider 인스턴스와 다른 요약 작업도 해당 모델에는 이 필드를 보내지 않는다. 앱 프로세스가 재시작되면 다시 확인한다.
- `choices[0].message.content`에서 완결된 `<think>…</think>` 블록을 제거하고, 설명/코드 펜스를 건너뛴 첫 번째 엄격한 JSON 객체를 찾는다. 문자열 안의 중괄호·이스케이프를 구분한다. 첫 번째 파싱 가능한 객체가 계약에 맞지 않으면 이후의 다른 객체를 고르지 않고 기존 validator로 실패시킨다.
- summary/story split의 기존 validator와 **형식 오류 추가 요청 1회**를 유지했다. JSON 모드 fallback과 형식 재요청은 별개다. 둘이 겹치면 최초 요청 + fallback + 형식 재요청, 최대 세 요청이다. `finish_reason`이 `stop`이 아닌 잘린/거절된 응답도 형식 오류로 처리한다.
- Cloudflare 오류는 401/403 → `AUTH`(재시도 없음), 429 → `RATE_LIMIT`(재시도 가능), 5xx/408/timeout → `NETWORK`(재시도 가능)로 매핑한다. `success=false`인 성공 상태 응답도 성공 요약으로 취급하지 않는다. WorkManager의 기존 최대 5 attempt/backoff와 동의 확인 경로는 변경하지 않았다.
- 토큰·계정 ID·전사·생성 결과를 로그에 추가하지 않았다. HTTP 요청/응답은 원문이 자동 출력되는 data class가 아니며, `SummarySettings.toString()`과 `SummaryResult.toString()`도 내용을 숨긴다. JSON/플랫폼 예외의 원문이나 cause를 UI 오류로 전달하지 않는다.

## debug 설정 화면

기존 ‘대화 요약 설정’ 화면과 정차 중 편집/시험 제한을 유지했다. 여행의 AI 전송 동의 저장소·판정은 변경하지 않았다.

| 필드/동작 | 내용 |
| --- | --- |
| 제공자 | Cloudflare / agy 중계 / Fake. 새 설치 기본값은 debug/release 모두 Cloudflare |
| 모델 ID | 제공자별로 별도 저장. 빈 값/‘기본값으로’는 해당 제공자 기본 모델 복원. Cloudflare의 `@cf/...` ID 허용 |
| Cloudflare 계정 ID | Cloudflare 선택 시 표시. 32자리 16진수 검증, ‘계정 ID 저장’으로 반영 |
| Cloudflare API 토큰 | 비밀번호 입력칸, 저장 요청 즉시 비움. 저장된 토큰은 끝 4자리만 표시. ‘토큰 지우기’는 복호화 불가 상태에서도 사용 가능 |
| agy 중계 주소 | agy 선택 시에만 표시. 기존 HTTPS 또는 에뮬레이터 `http://10.0.2.2:포트` 제한 유지 |
| 연결 시험 | 저장된 설정으로 기존의 지어낸 두 줄 대화만 전송. 진행 상태와 성공/짧은 오류 코드 표시 |
| 주행 중 제목 보이기 | 기존 설정과 화면 적용 유지 |

모델·계정 ID·중계 주소는 저장 버튼을 눌러야 반영된다. 연결 시험은 아직 저장하지 않은 입력값을 사용하지 않는다.
실험실의 ‘제목 만들어 보기’와 ‘이야기 나눠 보기’는 같은 store의 현재 선택 제공자를 계속 사용한다. 제공자 표시도 Cloudflare로 바꾸었다.

토큰 저장은 기존 방식과 같은 Android Keystore AES-256/GCM 및 저장마다 새 IV를 사용한다.

- 새 Keystore alias: `talkrec.summary.cloudflare-token.v1`
- 새 AAD: `talkrec_summary/cloudflare_token/v1`
- SharedPreferences: 기존 `talkrec_summary`, 필드 `cf_account_id`, `cf_token_ciphertext`, `cf_token_iv`, `model_CLOUDFLARE`
- 토큰 원문 getter와 원문 preference는 없다. 기존 백업/기기 이전 제외 규칙은 같은 preference 파일에 계속 적용된다.
- 공개 설정 API: `setCloudflareAccountId`, `setCloudflareToken`, `cloudflareTokenLast4`. Gemini용 `setApiKey`, `apiKeyLast4` API는 제거했다.

## 설정 마이그레이션

store를 열 때 기존 `api_ciphertext`, `api_iv`, `model_GEMINI`를 복호화 없이 원자적으로 삭제한다. `GEMINI` 제공자는 `CLOUDFLARE`로 저장한다. 알 수 없는 제공자도 Cloudflare로 정규화한다.
새 Cloudflare 자격 증명/편집 모델과 다른 설정은 보존하며 반복 실행해도 결과가 같다. 기존 AES wrapping key alias도 Android Keystore에 접근 가능하면 삭제한다. 이 삭제에 실패해도 이미 제거한 Gemini 암호문을 재사용하지 않는다.

Cloudflare 인증 정보를 새로 입력하기 전에는 `KEY_UNAVAILABLE`로 전송을 막는다. 요약 worker의 기존 키 없음 처리와 제목 fallback은 유지했다. 마이그레이션이 API를 호출하거나 기존 Gemini 키를 Cloudflare 토큰으로 옮기는 일은 없다.
기존 debug의 저장된 `AGY`/`FAKE` 선택은 보존한다. release에서 읽은 `AGY` 선택은 Cloudflare로 정규화한다.

## release와 debug 분리

- `talkrec/src/debug/.../summary/AgyBridgeSummaryProvider.kt`: relay provider, HTTP 경로/요청 매핑, relay URL 검증, 기본 URL/모델, 기존 360초 읽기 timeout을 이곳으로 이동했다.
- `talkrec/src/debug/.../summary/SummaryBuildProviders.kt`: debug enum에만 `AGY`가 있다. relay 생성과 `relayBaseUrl`/`setRelayBaseUrl` 설정 확장 API도 여기만 존재한다.
- `talkrec/src/release/.../summary/SummaryBuildProviders.kt`: enum은 **CLOUDFLARE / FAKE** 두 개뿐이다. relay 생성 구현이나 설정 API가 없다.
- 공용 `SummarySettings`에서 relay 주소 필드를 제거했다. main 소스에는 relay 클래스·주소·기본 모델·HTTP 경로가 없다.
- 설정 화면과 실험실은 기존처럼 `app/src/debug`에만 있다. relay cleartext XML/manifest도 기존 `talkrec/src/debug`에만 있다. main/release manifest나 네트워크 허용 범위는 확장하지 않았다.
- relay 전용 테스트를 `testDebug`로 옮겼다. `testRelease`는 제공자 목록, 저장된 AGY 선택 정규화, relay/Gemini 클래스와 debug 설정 확장 클래스의 부재를 검사한다.

## 실행한 검증

기존 `.talkrec-notes/verify-*.ps1`와 같은 캐시 Kotlin 2.2.10/Compose compiler 방식이다. Android/WorkManager API는 기존 host stand-in을 사용했다.

| 검증 | 결과 |
| --- | --- |
| `powershell -NoProfile -ExecutionPolicy Bypass -File .talkrec-notes/verify-i12-summary.ps1 -Variant debug` | **60개 통과** |
| 같은 스크립트 `-Variant release` | **54개 통과** |
| `.talkrec-notes/verify-i12-backend.ps1` | 서비스·worker·설정·repository 등 타입 검사 및 기존 저장 회귀 **11개 통과** |
| `.talkrec-notes/verify-i12-ui.ps1` | 실제 캐시 Compose/lifecycle API로 설정 화면·실험실·catalog 타입 검사 통과 |
| release 프로덕션 클래스 문자열 검사 | **73개 클래스**에서 relay 클래스/기본 URL/설정 키 및 Gemini API URL/인증 헤더 참조 없음 |
| `git diff --check` | 통과 |

fake HTTP 테스트는 두 작업의 요청/응답, 정상/코드 펜스/think prefix, JSON 모드 fallback과 프로세스 기억, fallback과 형식 재요청의 독립적인 상한, 401/403/429/5xx/timeout, 잘린 응답, 기존 분류·키워드 validator를 검사한다. 설정 테스트는 실제 store를 메모리 SharedPreferences 인터페이스에 연결해 마이그레이션, 토큰 삭제, 모델 분리 보존, 인증 정보 누락, Fake 선택을 검사한다.
debug/release 테스트에는 공통 테스트가 겹친다. 두 숫자를 고유 테스트 수로 합산하지 않는다.

프롬프트 SHA-256(변경 없음):

```text
summary.md     63E3F3AFFB0712B0B41C9E224EB2890BBDEF8E52C30D21249EEC52143780E7CC
story_split.md 97C1026DE023E5F4DBF29BF2D149EA4F79DF96A4B4D28FD60020F68CA34CD567
```

공식 참고: [OpenAI 호환 경로](https://developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/), [JSON Mode](https://developers.cloudflare.com/workers-ai/features/json-mode/). 모델/요청 계약은 이번 작업에서 사용자가 지정한 2026-11 사양을 적용했다.

## 열린 항목 / Claude 인계

- 실제 Cloudflare 토큰은 사용하지 않았다. Claude 환경에서 Gradle debug/release 전체 빌드, 실제 APK/dex 검사, 설치 및 AVD 검증이 남았다. 위 73개 검사 결과는 **release 소스로 독립 컴파일한 JVM 클래스 검사**이며 APK/dex 검사 결과가 아니다.
- AVD에서 Gemini 설정이 남은 기존 설치의 업그레이드 후 키 삭제·Cloudflare 선택·인증 정보 누락 처리를 확인한다. 새 토큰 저장/끝 4자리/프로세스 재시작 후 복호화/삭제도 실제 Android Keystore로 확인한다.
- 실제 모델로 연결 시험, 단일 제목 시험, 여러 클립 이야기 시험을 확인한다. Gemma의 JSON 모드 미지원 4xx가 실제 어떤 오류 문구로 오는지와 reasoning 출력·`finish_reason`도 확인한다. reasoning 제어용 비공개 인자는 보내지 않는다.
- reasoning이 출력 예산 1024를 소진하면 `length` 응답과 형식 오류 재요청 후 실패할 수 있다. 실제 응답을 보고 예산 조정이 필요한지 판단한다.
- 실제 서버의 에러 본문이나 모델 출력은 로그로 수집하지 않는다. UI의 안전한 오류 코드와 테스트 결과만 기록한다.
- 설정 UI의 기기상 크기/스크롤, 주행 중 비활성화와 입력 토큰 삭제, 실험실 제공자 전환도 AVD에서 확인한다.
- `.talkrec-notes`는 기존 ignore 대상이므로 이 문서와 새 검증 스크립트는 아래 git status에 표시되지 않는다.

## git status --short

```text
 M app/src/debug/java/com/dongbantest/app/debug/SummarySettingsActivity.kt
 M app/src/debug/java/com/dongbantest/app/debug/TalkRecLabActivity.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryHttpClient.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryJson.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryProviders.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummaryResult.kt
 M talkrec/src/main/java/com/drivediary/talkrec/summary/SummarySettingsStore.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/StorySplitTest.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryProvidersTest.kt
 M talkrec/src/test/java/com/drivediary/talkrec/summary/SummaryTestData.kt
?? talkrec/src/debug/java/com/drivediary/talkrec/summary/
?? talkrec/src/main/java/com/drivediary/talkrec/summary/CloudflareSummaryProvider.kt
?? talkrec/src/main/java/com/drivediary/talkrec/summary/SummarySettingsMigration.kt
?? talkrec/src/release/
?? talkrec/src/test/java/com/drivediary/talkrec/summary/SummarySettingsStoreTest.kt
?? talkrec/src/testDebug/java/com/drivediary/talkrec/summary/
?? talkrec/src/testRelease/
```

## Claude 후속 수정과 실기 확인 (2026-11-13)

- **스키마 단순화**: Workers AI(xgrammar)는 한 문자열에 `pattern`과 `minLength/maxLength`가 함께 있으면 400으로 거부한다(T63 실측). 그래서 `summarySchema`와 `storySchema`에서 pattern·길이·minimum/maximum·minItems를 뺐다. 이 규칙들은 검증기가 그대로 검사한다. JSON 모드 폴백 판별어에 `grammar`도 추가했다.
- **thinking 끔**: 요청에 `chat_template_kwargs={"enable_thinking": false}`를 넣었다. 끄면 13초→1.5초다. thinking을 켜면 이야기 묶기가 1024토큰 안에 끝나지 않아 실패한다.
- 테스트: Cloudflare 요청 스키마에 위 키워드가 없고 thinking이 꺼져 있는지 확인하는 단언을 추가했다. `:talkrec:testDebugUnitTest`와 `:app:assembleDebug`가 통과했다.
- AVD 확인: 설정 화면의 연결 시험이 성공했다(1.4초). real1 음성으로 여행을 진행해 클립 3개가 이야기 2개로 묶였고, 요약과 묶기 모두 Gemma로 처리됐다. 주행 화면 카드도 정상이다.
- 남은 확인: 여행 종료 후 다이어리 화면과 숨긴 대화(keep=false) 표시.
