# T63 — Cloudflare Workers AI 릴레이

2026-11-13. `@cf/google/gemma-4-26b-a4b-it`를 agy와 같은 프롬프트·검증기·stitch 평가로 측정할 수 있도록 로컬 릴레이에 Workers AI 백엔드를 추가했다. 이번 작업에서는 실제 API 호출이나 실제 dev 평가를 실행하지 않았다. JSON 모드 지원 여부와 reasoning 비용·지연에 대한 측정 결과는 아직 없다.

## 추가한 동작

- `tools/cloudflare_ai.py`: 외부 패키지 없이 `urllib`로 계정별 `/ai/v1/chat/completions`에 POST한다. system/user 메시지를 그대로 보내며 기본값은 `max_completion_tokens=1024`, `temperature=0.2`, 요청당 `timeout=120`초다. 생성자에서 모델·세 옵션·`json_schema`·`chat_template_kwargs`를 바꿀 수 있다. 릴레이는 모델 이외의 옵션에 기본값을 사용한다.
- `tools/summary_relay.py`: 모델명이 `@cf/`로 시작하면 `/summarize`, `/story-split` 모두 Cloudflare로 보낸다. 다른 모델은 기존 agy 경로를 사용한다. 기본 모델은 기존 `gemini-3.8-flash-medium`이며 새 `--model`로 바꿀 수 있다. 요청의 `model`이 서버 기본값보다 우선한다. `/health`도 설정된 기본 모델을 반환한다.
- summary의 `title/category/keywords/kind`, story split의 `stories[].segments/title/category/keywords/kind/keep/reason`에 대한 JSON 스키마를 만들었다. 분류는 각 기존 프롬프트의 `분류` 목록에서 읽으며 길이·키워드·kind·reason 상수는 검증기와 공유한다. 분할 순서·전체 coverage, 키워드 중복·숫자·분류명 제외 등의 최종 판정은 기존 검증기가 한다.
- Cloudflare 응답의 코드 펜스, `<think>`, `<thinking>`, `<analysis>`, `<reasoning>` 블록과 JSON 앞의 설명을 제거한다. 처음으로 디코딩 가능한 `{...}` 객체를 추출한 뒤 기존 검증기에 전달한다. 이 완화 처리는 Cloudflare에만 적용한다. agy 출력 파싱은 그대로다.
- 기존 직렬화 lock, 출력 검증 실패 시 한 번 재호출, `tags` 구성, fake 모드, loopback 바인딩, Windows half-close/drain 처리는 유지했다. Cloudflare 모델명에는 agy effort 접미사를 붙이지 않는다.
- `eval/stitch/split.py`: Cloudflare 모델을 허용하며 요청·결과 행의 정확한 모델명은 보존한다. 파일명의 `@cf/`는 `cf-`, 이후 `/`는 `--`로 바꾼다. agy 파일명은 그대로다. 새 파일은 기존 `eval.stitch.value`에서도 읽을 수 있다.

## 환경 변수와 오류

필수 환경 변수는 `CLOUDFLARE_ACCOUNT_ID`, `CLOUDFLARE_API_TOKEN`이다. 토큰은 해당 계정의 Workers AI 호출 권한이 있어야 한다. 계정 ID와 토큰을 받는 CLI 인자는 없으며 환경에서만 읽는다. `.env` 파일도 자동으로 읽지 않는다.

PowerShell 7에서는 다음처럼 값을 화면이나 명령 기록에 직접 적지 않고 입력할 수 있다. 릴레이를 시작하는 터미널에서 설정한다. 프로브를 다른 터미널에서 실행하면 그 터미널에도 환경 변수가 필요하다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
$env:CLOUDFLARE_ACCOUNT_ID = Read-Host 'Cloudflare account ID'
$env:CLOUDFLARE_API_TOKEN = Read-Host 'Cloudflare API token' -MaskInput
```

클라이언트·릴레이는 요청/응답 본문이나 upstream 예외 메시지를 로그로 출력하지 않는다. 클라이언트 결과의 `repr`에도 생성 내용을 넣지 않는다. HTTP redirect는 따라가지 않는다. 릴레이 로그는 기존 메서드·허용 경로·상태·길이·시간 집계만 남긴다.

| 상황 | 릴레이 HTTP / JSON `error` | `retryable` |
|---|---|---|
| 필수 환경 변수 누락·빈 값 | 503 / `cloudflare_not_configured` | false |
| 잘못된 계정 ID 형식 | 503 / `cloudflare_invalid_configuration` | false |
| upstream 401 또는 403 | 401 / `authentication_error` | false |
| upstream 429 | 429 / 기존 `usage_limit` | true |
| upstream 5xx·연결 실패·시간 초과 | 502 / 기존 `backend_error` (upstream 503은 503) | true |
| 그 밖의 upstream 오류 | 502 / `backend_error` | false |
| 두 번의 출력 검증 실패 | 기존 502 / `invalid_output` | 기존 응답 유지 |

`success:false, errors:[...]` 형태의 HTTP 200 오류 래퍼도 메시지/코드에서 인증·rate limit·서버 오류를 분류한다. 이 경우 프로브의 `http_status`는 분류한 오류 상태다. 오류 원문은 출력하지 않는다. `retryable`은 호출자에게 주는 분류이며, 릴레이가 429/5xx를 자동 재호출하지는 않는다. stitch 클라이언트의 기존 연결 reset 재시도 정책도 그대로다.

## 릴레이와 프로브 실행

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m tools.summary_relay --port 8771 --model @cf/google/gemma-4-26b-a4b-it
```

기존 호출자도 요청의 `model`을 `@cf/google/gemma-4-26b-a4b-it`로 지정하면 된다. `--fake`를 함께 쓰면 자격 증명 없이 결정적인 응답을 얻으며 API를 호출하지 않는다.

다음 프로브는 코드 안에 지어낸 짧은 한국어 대화 하나만 사용해 story-split 프롬프트로 한 번의 논리 호출을 실행한다. JSON 모드 거절 때만 HTTP 요청이 두 번 나갈 수 있다. 릴레이를 거치지 않고 같은 클라이언트·프롬프트·스키마·검증기를 직접 사용한다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m tools.cloudflare_probe --model @cf/google/gemma-4-26b-a4b-it --variant default
.\.venv\Scripts\python.exe -X utf8 -B -m tools.cloudflare_probe --model @cf/google/gemma-4-26b-a4b-it --variant no-thinking
```

`default`는 `chat_template_kwargs`를 생략한다. `no-thinking`은 실험적으로 `{"enable_thinking": false}`를 보낸다. 각각 새 프로세스에서 실행하므로 JSON 모드 폴백 캐시도 각각 새로 시작한다. 비교 시 폴백 여부와 요청 수의 차이도 고려해야 한다.

기본 stdout은 JSON 한 줄이며 다음 항목만 포함한다.

- `http_status`, `response_format_accepted`, `finish_reason`, `reasoning_tokens`, `latency_ms`
- `has_think_or_prefix`: `<think>` 또는 첫 `{` 앞의 공백 이외 텍스트 유무. 코드 펜스도 앞 텍스트에 포함한다.
- `content_length`: 원래 content의 문자 수
- `validation`: `pass`/`fail`, `error_code`: 성공 시 null, 실패 시 정제된 오류 코드

`latency_ms`는 JSON 모드 폴백을 포함한 논리 호출 전체의 시간이다. 응답에서 reasoning 토큰 수를 제공하지 않으면 `reasoning_tokens=null`이다. 알 수 없는 `finish_reason` 문자열은 `unknown`으로 정제한다. 성공 종료 코드는 0, 실패는 2다. `--show`를 명시할 때만 지어낸 입력에 대한 원래 생성 내용을 추가 출력한다. 실제 전사 입력 옵션은 없다.

## agy와 같은 평가 실행

기존 Part A의 `.local/eval/stitch/runs.jsonl`과 같은 자료·정책을 사용한다. 아래는 기존 T60 비교 정책 예다. 자료나 판정 모델이 바뀌었다면 Part A를 먼저 다시 실행한다. 실행법은 `T60-story-stitch-measure.md`를 참고한다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m eval.stitch.split --relay http://127.0.0.1:8771 --policy cont0.957_min0 --model @cf/google/gemma-4-26b-a4b-it
.\.venv\Scripts\python.exe -X utf8 -B -m eval.stitch.value .local/eval/stitch/split-cont0.957_min0-cf-google--gemma-4-26b-a4b-it.jsonl
```

같은 릴레이에서 agy 비교군도 기존 명령으로 실행할 수 있다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m eval.stitch.split --relay http://127.0.0.1:8771 --policy cont0.957_min0 --model gemini-4-hydrogen-medium
```

Cloudflare 결과 파일은 `.local/eval/stitch/split-<policy>-cf-google--gemma-4-26b-a4b-it.jsonl`이다. 결과 행의 `model`은 원래 `@cf/google/gemma-4-26b-a4b-it`다. 생성 제목을 포함하므로 기존 평가 결과와 동일하게 비공개로 취급한다. 이번에 reasoning 옵션을 바꾸는 경로는 프로브에만 추가했으며 릴레이 평가의 기본 설정은 두 백엔드의 같은 출력 계약을 비교하는 용도다.

## 실측 결과 (2026-11-13, Claude)

실제 API로 확인했다. 아래 "미확인 사항"은 작성 당시 기록이며, 여기서 확인된 내용이 우선한다.

- **JSON 모드**: 동작한다. 다만 xgrammar는 한 문자열에 `pattern`과 `minLength/maxLength`가 함께 있으면 400으로 거부한다. 그래서 스키마는 타입·enum·required·maxItems만 쓰고 나머지는 검증기에 맡겼다. 폴백 판별 정규식에 grammar/schema/xgrammar도 추가했다.
- **thinking**: 기본으로 켜져 있다. 추론은 `reasoning_content`로 나오고 `content`에는 JSON만 남는다. `chat_template_kwargs={"enable_thinking": false}`이면 프로브 기준 13.3초에서 1.5초로 줄고 출력은 똑같이 검증을 통과한다. 이야기 묶기에서는 thinking을 켜면 1024토큰 안에 끝나지 않아(`finish_reason=length`, content 비어 있음) 실패했다. 그래서 릴레이 기본값은 thinking 끔이다(`--cf-thinking`으로 켬).
- **요약(블록 77개)**: 74개 성공, 3개는 Cloudflare 502다. 중앙값 1.3초, p90 2.0초이며 Flash(agy)는 중앙값 19초다. Flash와 겹치는 37개 중 kind는 37개 모두 같고 분류는 25개가 같다. 갈린 것은 대부분 '아이의 하루'와 '가족 이야기'의 경계이며, Gemma 쪽이 '가족 이야기'를 더 자주 고른다. 제목은 쓸 만하지만 "~에 관한 이야기/대화"처럼 조금 더 밋밋하다.
- **이야기 묶기(저장된 run 18개)**: 중앙값 2.3초. 매번 1~2개 run이 실패했다. 원인은 같은 조각을 두 이야기에 넣는 잘못된 분할이고, 검증기가 정상적으로 거부한 것이다. 재요청하면 통과하기도 한다.
- **가치 판단(T61 기준; 실패한 run은 앱 폴백처럼 전부 저장으로 계산)**: 잘못 저장된 것이 26개에서 13개로 줄었다(Hydrogen v2는 16개). 하지만 사람이 저장으로 본 것 중 숨겨진 것이 3개다(Hydrogen 1개). 출연진별 잘못 저장은 아래와 같다. Gemma는 친구 대화를 거의 걸러 내지 못하고, 아이 대화를 일부 숨긴다.

  | 출연진 | Gemma | Hydrogen v2 |
  |---|---|---|
  | child_family | 5→1 | 5→5 |
  | adult_family | 10→2 | 10→6 |
  | friends | 9→8 | 9→4 |
  | couple | 2→2 | 2→1 |

- **결론**: 속도와 비용 면에서 실제 앱 기본값으로 충분하다. 품질은 Flash/Hydrogen보다 조금 낮다(분류 경계, 친구 대화 필터, 분할 실패 약 6%). 앱 I12에도 같은 스키마 단순화와 thinking 끔을 반영했다.

## 미확인 사항

1. **JSON 모드**: 제공된 2026-11 API 정보에서 Gemma 4 26B는 지원 목록에 없다. 스키마가 주어지면 첫 요청에 `response_format`을 보낸다. JSON mode/response_format을 언급하는 4xx 오류면 같은 입력에서 그 필드만 제거해 한 번 폴백하고 프로세스 전체에 모델별로 기억한다. HTTP 200 오류 래퍼도 같은 방식으로 분류한다. 인증·rate limit·5xx나 다른 4xx에는 이 폴백을 하지 않는다. 프로세스를 재시작하면 다시 시도한다.
2. **accepted의 의미**: `response_format_accepted=true`는 해당 필드를 보낸 요청이 성공했다는 뜻이다. API가 실제 스키마를 강제했다는 보장은 없다. 폴백 또는 캐시로 생략하면 false, 스키마를 요청하지 않았거나 오류로 판단할 수 없으면 null이다. 최종 출력 검증은 항상 수행한다.
3. **reasoning 끄기**: `chat_template_kwargs` 내용이 문서화되지 않았으므로 `enable_thinking=false`가 지원되는지, 무시되는지, 오류가 나는지 미확인이다. 기본 프로브와 reasoning_tokens·latency를 비교해야 한다. 클라이언트는 이 옵션 오류를 자동으로 제거해 재시도하지 않는다.
4. **reasoning 텍스트와 토큰 예산**: 추론 텍스트가 어디에 나타나는지도 미확인이다. 알려진 태그가 content에 있으면 제거하되 별도 reasoning 필드는 출력하지 않는다. 기본 1024토큰에서 추론 때문에 `finish_reason=length` 또는 검증 실패가 생기는지 확인해야 한다. 토큰 수가 없다는 사실만으로 추론이 꺼졌다고 판단하지 않는다.

## 검증

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m pytest -q -p no:cacheprovider tests/test_cloudflare_ai.py tests/test_summary_relay.py tests/test_stitch_split.py tests/test_stitch_measure.py tests/test_stitch_value.py --basetemp=.local/t63-pytest-check
```

**320개 통과**. 실제 네트워크를 차단한 fake opener/메모리 HTTP 테스트로 정상 응답·메타데이터·옵션, 펜스·추론·설명 접두사, JSON 모드 폴백·프로세스 캐시, 401/403·429·5xx·오류 래퍼, 누락 환경 변수, 토큰 비노출, prefix 라우팅과 기존 프롬프트·검증·재호출, fake 모드, Cloudflare 파일명으로 stitch split→value 호환성을 확인했다. 기존 summary/stitch 테스트를 포함한 결과다. 실제 API 지원 여부·성능을 검증한 결과는 아니다.

T62 작업 파일은 수정하지 않았다. 커밋·push·브랜치 전환은 수행하지 않았다.
