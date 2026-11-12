# I5a: agy 요약 HTTP 중계

작성일: 2026-11-13

## 구현 내용

Android 에뮬레이터의 debug용 `AgyBridgeSummaryProvider`가 API 키 없이 제목·태그·종류를 받아 볼 수 있는 로컬 HTTP 중계다. Python 표준 라이브러리만 사용하며 기존 `pipeline/label.py`의 `AgyBackend`를 호출한다. agy 설치 및 사용 가능한 로그인 세션은 별도로 필요하다.

생성 파일:

- `talk-rec/model-create/tools/summary_relay.py`
- `talk-rec/model-create/tests/test_summary_relay.py`
- 이 보고서 `talk-rec/docs/app-integration/I5a-summary-relay.md`

시작할 때 `model-create/prompts/summary.md` 전체를 시스템 프롬프트로 읽는다. 허용 태그는 같은 파일의 `## 태그` 절에서 쉼표로 구분된 목록을 파싱한다. 태그 목록을 코드에 복제하지 않았으며, 목록을 읽을 수 없으면 시작에 실패한다. 프롬프트 변경은 서버를 재시작해야 반영된다.

기본 모델은 `gemini-3.8-flash-medium`이다. 요청의 모델 이름에 `-low`, `-medium`, `-high`가 있으면 기존 `agy_model`로 분리해 해당 effort를 쓴다. 접미사가 없으면 `medium`을 적용하고, 응답의 `model`에는 실제 CLI에 전달하는 접미사 포함 이름을 돌려준다.

LLM 출력의 코드 펜스를 제거한 뒤 JSON 객체인지, 제목이 공백뿐이지 않은 1~25자 한 줄인지, 태그가 허용 목록의 문자열 1~3개인지, 종류가 `story` 또는 `moment`인지 검증한다. 형식이 잘못되면 같은 시스템 프롬프트와 전사로 한 번 더 요청한다. 두 번째도 실패하면 `502 invalid_output`이다. 백엔드 오류나 사용량 제한은 중계 내부에서 재시도하지 않는다.

## 실행 방법

PowerShell에서 다음과 같이 실행한다.

```powershell
cd C:\github\drive-diary\talk-rec\model-create
.\.venv\Scripts\Activate.ps1
python -m tools.summary_relay --port 8771
```

가상환경을 활성화하지 않을 때는 다음 명령도 같다.

```powershell
.\.venv\Scripts\python.exe -m tools.summary_relay --port 8771
```

PC 주소는 `http://127.0.0.1:8771`, Android 에뮬레이터의 중계 주소는 `http://10.0.2.2:8771`이다. `--port` 생략 시 8771을 쓰며, 허용 범위는 1~65535다. 호스트 변경 옵션은 없고 `127.0.0.1`에만 바인딩한다. 종료는 Ctrl+C다.

agy를 호출하지 않는 앱 연결 시험:

```powershell
python -m tools.summary_relay --port 8771 --fake
```

fake 모드는 전사 내용과 무관하게 `테스트 대화`, 프롬프트 목록의 첫 태그, `story`를 반환한다. 모델 필드는 요청 모델 설정을 따르고 `elapsed_ms`는 실제 처리 시간이다. 따라서 내용은 결정적이지만 시간은 실행마다 달라질 수 있다.

## HTTP 형식

### 상태 확인

`GET /health`

```json
{"ok": true, "model": "gemini-3.8-flash-medium"}
```

중계 프로세스가 응답하는지 확인하는 용도다. agy 실행이나 로그인·사용량 상태까지 확인하지는 않는다.

### 요약 요청

`POST /summarize`, `Content-Type: application/json`, `Content-Length` 필요. 본문은 UTF-8 JSON이며 최대 32,768바이트다. chunked 요청은 지원하지 않는다.

```json
{
  "transcript": "예전에 비 올 때 같이 만두 샀잖아\n맞아 그때 신발 다 젖었지",
  "model": "gemini-3.8-flash-medium"
}
```

`transcript`는 공백만으로 이루어지지 않은 문자열이어야 한다. `model`은 생략 가능하며, 영문·숫자로 시작하는 최대 128자의 영문·숫자·점·밑줄·하이픈 이름을 허용한다. 모델의 실제 지원 여부는 agy가 판단한다. 음성 파일이나 위치 정보는 받지 않는다.

성공 응답 예시(`200`, 시간은 예시):

```json
{
  "title": "비 오던 날 만두 이야기",
  "tags": ["추억"],
  "kind": "story",
  "model": "gemini-3.8-flash-medium",
  "elapsed_ms": 1234
}
```

`elapsed_ms`는 중계의 요약 처리 시작부터 완료까지의 정수 밀리초다. 잠금 대기, 백엔드 생성, 호출 및 출력 재시도를 포함하며 HTTP 본문 수신 시간은 포함하지 않는다. 모든 응답에 `Cache-Control: no-store`를 붙인다.

### 오류 코드

오류 본문은 짧은 코드만 제공한다. 예: `{"error":"invalid_output"}`. CLI 출력·예외 상세·전사·제목을 오류에 포함하지 않는다.

| HTTP | error | 의미 |
| --- | --- | --- |
| 400 | `invalid_request` | 잘못된 JSON·UTF-8·본문 필드·모델 형식·Content-Length, 중복 길이 헤더 또는 Transfer-Encoding |
| 404 | `not_found` | 지원하지 않는 경로 또는 GET/POST와 경로 조합. 쿼리 문자열도 받지 않음 |
| 405 | `method_not_allowed` | GET/POST 외의 지원하지 않는 메서드 |
| 408 | `request_timeout` | 본문 읽기 시간 초과(소켓 제한 10초) |
| 413 | `request_too_large` | 선언된 본문 크기가 32 KB 초과 |
| 415 | `unsupported_media_type` | Content-Type이 application/json이 아님 |
| 429 | `usage_limit` | 기존 백엔드의 `UsageLimit`: 구독·할당량·호출 빈도 제한 |
| 502 | `invalid_output` | 최초 응답 및 한 번의 재시도 모두 출력 검증 실패 |
| 502 | `backend_error` | CLI 실행·시간 초과·명령줄 길이 제한·모델 오류 등 기타 백엔드 실패 |

표준 HTTP 파서가 거부하는 과도한 요청 줄·헤더 등은 해당 HTTP 상태와 `invalid_request`로 반환한다.

## 테스트 결과

2026-11-13, Windows / Python 3.12.16 / pytest 8.4.2에서 실행했다.

```powershell
cd C:\github\drive-diary\talk-rec\model-create
.\.venv\Scripts\python.exe -m pytest tests/test_summary_relay.py tests/test_label_agy.py --basetemp C:\github\drive-diary\talk-rec\model-create\.local\tmp-relay-tests -p no:cacheprovider
```

결과: **144 passed in 0.44s**. 새 릴레이 테스트 89개와 기존 agy 백엔드 테스트 55개가 통과했다. 테스트는 fake 백엔드·메모리 HTTP 스트림·바인딩 인자 가로채기를 사용했다. 새 테스트에서는 소켓 생성과 실제 `AgyBackend.complete`를 실패 처리하여 네트워크와 agy 호출이 없음을 보장했다.

검증 범위는 태그 절 파싱, 출력 스키마와 제목 길이 경계, 코드 펜스, 재시도 상한, 오류 매핑, 모델/effort 전달, 요청 크기 경계, 잘못된 헤더·본문·타임아웃, health/fake/경로 처리, 기본 포트와 localhost 주소, 두 요청과 재시도 사이의 직렬화, 전사·제목·백엔드 상세의 로그 미포함이다. 기존 agy 테스트에서는 프롬프트 구분, CLI 인자, 제한 감지와 길이 보호 등 재사용 동작도 확인했다.

## 보안 및 사용 범위

- **localhost 전용·debug 데모 전용**이다. `0.0.0.0`이나 LAN 인터페이스를 열지 않는다. 에뮬레이터는 호스트 루프백 별칭 `10.0.2.2`로 접근한다.
- 인증·TLS를 제공하지 않는다. 로컬 프로세스와 에뮬레이터가 호출할 수 있으므로 외부 포워딩이나 배포 서비스 용도로 사용하지 않는다. CORS 허용 헤더도 제공하지 않는다.
- 요청 로그에는 정해진 메서드·경로, HTTP 상태, 본문 바이트 수·전사 문자 수·응답 바이트 수, 처리 시간만 남긴다. 쿼리 문자열과 알 수 없는 경로·메서드는 원문을 기록하지 않는다. 기본 HTTP 접근 로그와 서버 예외 traceback도 억제한다.
- 중계는 전사·제목을 파일로 저장하지 않는다. 기존 agy 백엔드의 sandbox 호출과 임시 작업 폴더 동작을 그대로 이용한다. agy는 전사를 외부 모델에 전달하므로 앱의 별도 외부 AI 전송 동의와 debug 설정 정책은 앱에서 적용해야 한다.
- 하나의 중계 인스턴스는 잠금으로 한 번에 한 agy 호출만 실행한다. 최초 호출과 출력 재시도 사이에도 잠금을 유지한다. 여러 중계 프로세스 사이의 직렬화는 제공하지 않는다.

## 남은 과제

- 실제 agy 로그인 세션으로 하는 호출 및 Android 에뮬레이터의 `10.0.2.2` 왕복 시험은 수행하지 않았다. 앱 제공자·debug 설정·cleartext HTTP 허용·연결 시험 화면 연동은 후속 앱 작업이다.
- JSON 형식 검증으로는 제목의 사실성, 사람 이름 배제, 태그 적합성을 보장할 수 없다. 실제 전사에 대한 품질 확인이 필요하다.
- HTTP 본문이 32 KB 이하여도 시스템 프롬프트·인자 인용·UTF-16 길이 때문에 기존 agy의 32,000 코드 단위 명령줄 제한에 걸릴 수 있다. 이 경우 전사를 자르지 않고 `502 backend_error`를 반환한다.
- agy 호출은 기존 기본 시간 제한 600초를 따른다. 대기열 상한·호출 취소·장애 후 백오프는 구현하지 않았으며, 앱 측 시간 제한과 WorkManager 재시도 정책을 맞춰야 한다. `health` 성공 후에도 agy 호출은 실패할 수 있다.

기존 수정 파일은 변경하지 않았고 커밋·푸시·브랜치 전환을 하지 않았다.

## 실제 agy 확인 (Claude, 2026-11-13)

- pytest `tests/test_summary_relay.py`: 89개 통과.
- 중계 서버를 127.0.0.1:8771로 띄우고 지어낸 대화 하나를 보냄: 200 응답, 제목 13자, 태그 2개(목록 안), kind story, 15.4초.
- 서버 로그에는 method·path·status·바이트 수·글자 수·시간만 남고 전사 글과 제목은 없음을 확인.
