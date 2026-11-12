# T50 — 실제 평가 묶음 파이프라인

2026-11-12. Python 3.12와 기존 `.venv`로 구현했다. 외부 네트워크, 패키지 설치,
adb, 에뮬레이터, 커밋은 사용하지 않았다. 실제 전사나 판정은 실행하지 않았다.
아래 실험 문장은 모두 지어낸 것이며 실제 전사 본문은 기록하지 않았다.

## 만든 것

- `model-create/eval/real.py`: sources, blocks, grid, split, agree, export, stats, open-test.
- `demo/pleos.py`: 전체 출처의 PCM 변환, 조각 전송, 원래 시각 복원, 완료 출처·조각 재사용.
- `pipeline/blocks.py`: 사람 승인 규칙을 `approval_reason`으로 공유했다.
  `Settings(max_turns=None)`이면 턴 수로 닫지 않는다. 기존 기본값 12는 유지했다.
- `tools/listen/real.py`, `real.html`, `serve.py`: v3 청취 화면과 판정자별 저장.
  기존 `index.html`, `.local/demo`, `--name` 모드, 기존 답 파일은 유지했다.
- `data/real-split.tsv`: 출처 id와 dev/test만 보관한다. 기존 행의 값은 바꾸지 않는다.
- `tests/test_real.py`, `tests/test_demo_pleos.py`: 합성 PCM, 지어낸 결과, 가짜 adb로 검증한다.

승인 규칙은 `decision=approve`, 비어 있지 않은 `decided_by`, 대소문자를 무시한
`claude` 제외다. 기존 `pipeline.blocks.skip_reason`과 같은 함수를 쓴다.
음성이 없거나 읽을 수 없으면 sources에 `skipped`와 고정 이유 코드를 남긴다.
승인 목록은 실행 때마다 다시 읽는다.

## 실제 자료에서 확인한 범위

요청 당시 3개였던 사람 승인 출처가 작업 중 추가됐다. PCM 준비 시점에는
**6개 출처, 4,076.652초, 49조각, 130,452,854바이트**, 조각당 최대 90초였다.
음성 존재 여부·길이와 PCM 준비만 확인했다. 파일은 전부 `.local/real/v1/` 안에 있다.
이때의 분할은 **dev 5개, test 1개**다. 추가 승인분은 다음 sources/prepare/split에서 반영한다.
`approved.tsv`는 이 작업에서 수정하지 않았다.

실제 Pleos 전사, 실제 grid 수치, 사람 판정, 실제 κ는 아직 없다.
이 값은 아래 순서로 기기 전사를 마친 뒤 구한다. 합성 결과를 실제 평가 수치로 쓰지 않는다.

## 결함과 수정

### push 크기 확인

기존 코드는 `adb shell → run-as → sh -c 'wc -c < 파일'`의 stdout이 숫자 하나라고
가정하고 `isdigit()`으로 검사했다. 전송과 달리 크기 확인에는 원격 셸·리다이렉션과
wc 출력 해석이 끼어 있었다. 이 경로에서 확인이 실패하면 정상 전송 뒤에도 첫 파일에서 멈춘다.

확인은 `exec-out run-as <패키지> --user <id> stat -c %s <파일>`로 바꿨다.
셸과 리다이렉션 없이 크기만 받아 로컬 `fstat` 값과 비교한다. 줄 끝 공백과 CRLF는
제거하고, 숫자가 아니거나 크기가 다르면 계속 실패시킨다. 전송 자체는 기존 파일 stdin 방식을 유지한다.
가짜 adb의 실제 바이트 저장·stat 응답으로 전체 흐름과 크기 불일치 거부를 검증했다.
기기에서 발생한 원래 stdout은 샌드박스에서 관측할 수 없으므로, 실기에서의 최종 확인은 Claude가 한다.

### collect 형식

기존 collect는 `callbacks`, `errors`를 넣고 신뢰도 필드를 빼며, 위치 없는 줄에는
null 시각을 넣어 `validate_result`와 달랐다. 이제 공개 결과는 정확히
`id/config/lines/seconds/avg_logprob/no_speech_prob` 여섯 필드다.
신뢰도 두 값은 `null`, 실제 출처의 config는 `pleos`다. 저장 전 `validate_result`를 통과시킨다.
데모의 가속 실험 config인 `pleos-200` 등은 유지한다.
콜백·오류 수는 내부 `results()`와 기존 데모 stats에서만 다룬다.

실제 모드에서 깨진 TSV·SDK 오류·위치 없는 final은 실패 조각으로 취급한다.
정상 조각은 보관하고, 그 출처의 모든 조각이 모여야 출처 결과를 쓴다.
위치가 없는 데모 final도 유효하지 않은 ASR 줄로 내보내지 않고 오류를 낸다.

### 기기 길이 상한과 재시작

현재 `BatchSttService.java:175`가 **95초 초과 PCM을 거부**한다. Java는 바꾸지 않았다.
T50b부터 기본 90초, `--chunk-seconds`는 0보다 크고 90 이하만 허용한다.
10분 조각은 이 APK에서 쓸 수 없다. 전체 음성은 프레임 단위로 16kHz 모노 s16le로
변환한 뒤 샘플 단위로 겹침 없이 나눈다. 출처 전체를 메모리에 올리지 않는다.

조각 offset을 더해 원래 출처 시각으로 돌린다. 성공 조각은 `pleos/parts/`에 보관한다.
prepare는 끝난 출처와 성공 조각을 제외해 새 manifest를 만든다. 남은 조각이 없으면
push/run/pull은 adb를 부르지 않는다. 원본의 크기·수정 시각 또는 조각 길이가 바뀌면
새 실행 이름을 요구한다. 묶음 판정이 생긴 뒤 기존 묶음이 바뀌는 재생성도 거부한다.
새 출처 묶음의 추가는 허용한다.

## Claude가 실행할 명령 — PowerShell

T44의 debug APK가 설치된 AVD를 먼저 켜고, 실제 serial을 `$serial`에 넣는다.
APK 설치가 필요하면 [T44 기록](T44-pleos-batch-stt.md)의 빌드·설치 절차를 먼저 따른다.
기존 다른 STT 실험은 종료한다. push는 기기 batch 파일을 지우므로 보관할 이전 결과는 먼저 pull한다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
$t50python = (Resolve-Path .\.venv\Scripts\python.exe).Path
function Invoke-T50 {
    & $t50python -X utf8 @args
    if ($LASTEXITCODE -ne 0) { throw 'T50 명령 실패. 다음 단계로 진행하지 마세요.' }
}
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = Read-Host '사용할 AVD의 serial을 입력하세요'
$androidUser = (& $adb -s $serial shell am get-current-user).Trim()
if ($LASTEXITCODE -ne 0 -or $androidUser -notmatch '^\d+$') { throw 'Android 사용자 확인 실패' }
& $adb -s $serial shell pm grant --user $androidUser com.drivediary.sttprobe android.permission.POST_NOTIFICATIONS
if ($LASTEXITCODE -ne 0) { throw '알림 권한 설정 실패' }

# 1. 승인 목록·real-ranges.tsv 구간 확인과 분할. 새 출처만 분할표에 추가한다.
Invoke-T50 -m eval.real sources --run v1
Invoke-T50 -m eval.real split --run v1

# 2. 사용 구간 안에서만 PCM 조각 준비. 완료 출처·조각은 건너뛴다.
Invoke-T50 -m demo.pleos prepare --real v1 --chunk-seconds 90

# 3. 기기 전사. 100% 속도로 실행한다. 길어진 전체 출처에 맞춰 제한을 늘린다.
Invoke-T50 -m demo.pleos push --real v1 --serial $serial --user $androidUser
Invoke-T50 -m demo.pleos run --real v1 --serial $serial --user $androidUser --speed-pct 100 --padding-ms 0 --start-timeout 15 --timeout 10800
Invoke-T50 -m demo.pleos pull --real v1 --serial $serial --user $androidUser
Invoke-T50 -m demo.pleos collect --real v1

# 4. 숫자 표와 기본 묶음 생성. 중간에 늘어난 승인 출처도 다시 분할한다.
Invoke-T50 -m eval.real grid --run v1 --cast child_family
Invoke-T50 -m eval.real blocks --run v1
Invoke-T50 -m eval.real split --run v1

# 5. 첫 판정자. 브라우저에서 http://127.0.0.1:8767 을 연다.
Invoke-T50 -m tools.listen.serve --real v1 --annotator 민수 --suggest claude --port 8767
# Ctrl+C로 종료한 뒤 둘째 판정자를 연다. 서로의 파일은 보이지 않는다.
Invoke-T50 -m tools.listen.serve --real v1 --annotator 지수 --suggest claude --port 8767

# 6. 기본은 dev만 읽는다. 실제 평가의 주 수치는 아이+가족 묶음에서 낸다.
Invoke-T50 -m eval.real agree 민수 지수 --run v1 --cast child_family
Invoke-T50 -m eval.real stats --run v1 --cast child_family
Invoke-T50 -m eval.real export --run v1 --cast child_family
```

실패한 전사를 재개할 때는 보관할 기기 결과를 먼저 pull/collect한 뒤
prepare → push → run → pull → collect를 반복한다. 오류 조각은 다시 전사하고
앞서 성공한 조각을 합친다. 완료된 출처는 바꾸지 않는다.
run/pull 전에 prepare를 다시 실행하면 manifest가 바뀔 수 있으므로 위 순서를 지킨다.
동시에 같은 run을 수정하는 두 프로세스는 실행하지 않는다.

test를 실제로 열 때만 아래 중 필요한 명령을 **별도로** 실행한다.

```powershell
# 이 명령들은 test만 읽는다. 매번 접근 로그를 먼저 쓴다.
Invoke-T50 -m eval.real open-test agree 민수 지수 --run v1 --cast child_family
Invoke-T50 -m eval.real open-test stats --run v1 --cast child_family
Invoke-T50 -m eval.real open-test export --run v1 --cast child_family
# 플래그 방식: --open-test만 쓰면 dev+test, --split test를 더하면 test만.
Invoke-T50 -m eval.real stats --run v1 --split test --open-test
```

## 파일 형식과 T55 연결

저장소에는 코드·문서·출처 분할 id만 둔다. 아래 자료는 모두 `.local/real/<run>/` 안에 둔다.

| 경로 | 내용 |
| --- | --- |
| `sources.json` | 배열. `id`, `cast`, `channel_id`, `seconds`, `status`, `reason`, `ranges`, `usable_seconds`, `range_adjustments` |
| `sources-summary.json` | 출처 수·전체 초·사용 초·무시한 미승인 구간 행 수, `by_cast`별 출처 수·사용 초 |
| `pleos/plan.json` | 출처별 전체 길이·원본 확인 값·사용 구간·조각 offset와 range_index |
| `pleos/manifest.json`, `pcm/`, `raw/`, `parts/` | 전송 대상, PCM, 원본 TSV, 성공 조각 |
| `asr.jsonl` | 출처당 ASR 결과. id는 `yt:<영상 id>@0`, config는 `pleos` |
| `blocks.jsonl` | `id/source/start/end/seconds/n_lines/closing/lines/range_index` |
| `judgments/<이름>.jsonl` | 판정 이력. 같은 id의 마지막 판정을 사용 |
| `clips/` | 사용 구간 안으로 제한한 앞뒤 최대 2초 여유 WAV. 최초 재생 요청 때 생성 |
| `test-access.log` | UTC 시각, 명령, 읽은 split. 잠금 해제 상태를 저장하지 않음 |
| `export/<dev 또는 test 또는 all>/manifest.json` | T55가 읽을 현재 파일 목록 |
| `export/<split>/annotators/<이름>.jsonl` | 판정자별 모델 입력과 판정 |
| `export/<split>/consensus.jsonl` | 여러 판정자가 일치한 묶음 |

위 표의 `pcm/raw/parts`는 `pleos/` 아래에 있다. 출처 결과의 `@0`은 기존 ASR id
검증 규약에 맞춘 전체 출처 식별자이고, 묶음 id는 별개인 `yt:<영상 id>#<0부터 순번>`이다.
줄은 `start/end/text`, 모든 시각은 출처 시작 기준 초다.

판정은 `id/keep/kind/none_reason/unsure/policy/comment/listened/labeler/saved_at`이다.
`kind`는 none일 때 빈 문자열이다. `none_reason`은 빈 문자열·`not_car`·`negative` 중 하나다.
서버가 `human:<이름>`과 UTC 저장 시각을 붙인다. 클라이언트가 다른 판정자 이름을 보낼 수 없다.
기존 v2 답 파일은 이 형식으로 옮기지 않는다.

내보낸 행은 `id/source/split/cast/text/keep/kind/none_reason/unsure/policy/listened/labeler`다.
text는 합성과 똑같이 **`demo.trial.data.input_text`**로 만든다. 코멘트는 내보내지 않는다.
consensus는 모든 판정자의 공통 id 중 keep·kind·none_reason·정책 집합이 같고,
모두 청취 표시가 있으며 unsure가 없는 것만 포함한다. 판정자가 한 명이면 비어 있다.
T55는 manifest에 적힌 파일을 읽고, 과거 내보내기에 남은 파일을 임의로 훑지 않는다.

평가·학습 코드의 판정 읽기는 **`eval.real.load_labels` 한 곳**으로 모았다.
기본 `split=dev`이고 분할되지 않은 출처가 있으면 읽기를 거부한다.
test/all은 `open_test=True`가 필요하고, 라벨 파일을 읽기 전에 접근 로그를 쓴다.
한 번 열었다고 다음 실행이 열리지 않는다. 청취 화면의 예외 경로
`read_own_judgments`는 지정 판정자 파일 하나만 읽어 모든 split을 판정하게 한다.
이 잠금은 코드 경로의 규약이며 로컬 파일을 소유한 사람의 직접 접근을 막는 암호화는 아니다.

## 묶음·집계에서 알아 둘 점

- 기본 닫힘은 침묵 20초, 다음 줄을 넣으면 길이 90초 초과, 사용 구간 끝이다.
  기존 `closing_reason`의 우선순위를 유지한다. 턴 수 조건은 없다.
  한 줄 자체가 90초를 넘으면 줄을 쪼개지 않는다.
- grid는 침묵 10/20/30 × 길이 60/90/120의 9행이다. 길이와 줄 수의 p10/p50/p90,
  길이·침묵·range_end 닫힘 비율(0~1)을 표시한다. 글은 표시하지 않는다.
- Pleos TSV의 시각은 실제 발화 경계가 아니라 **오디오 제출 위치의 근사**다.
  줄 시작은 직전 final 끝으로 정한다. 따라서 침묵을 정확히 재는 값으로 해석하면 안 된다.
  조각 경계에서 인식 문맥이 끊길 수 있다. 겹침은 추가하지 않았다.
- agree의 κ는 unsure 포함/제외를 각각 낸다. 둘 다 keep인 묶음에서 kind 일치율을 낸다.
  keep·kind 불일치 id와 정책 불일치 id는 별도 목록이다. 글과 코멘트는 없다.
  공통 판정이 없거나 한 종류로만 일치해 κ가 정의되지 않으면 null이다.
  한 판정자만 있으면 판정 분포와 2명 필요 안내만 낸다.
- stats의 전체 출처·묶음 수와 split별 출처·묶음 수는 라벨을 열지 않아도 센다.
  판정 분포는 허용된 split만 세고 잠긴 split의 판정 집계는 null로 둔다.
- 청취 화면은 rubric §2 순서, 선택적 없음 이유, 복수 정책, 불확실, 메모를 제공한다.
  실제로 재생한 서로 다른 구간이 80% 이상일 때 청취 표시가 켜진다.
  표시가 사람의 집중까지 보증하지는 않는다. 전사는 기본으로 접혀 있다.

## 검증 결과

최종 관련 테스트는 **358 통과, 1 건너뜀, 실패 없음**이었다(28.40초).
새 `test_real.py`는 45개가 포함된다. 모든 새 테스트는 지어낸 문장과 합성 PCM만 쓴다.

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_real.py tests/test_demo_pleos.py tests/test_demo_pleos_profile.py tests/test_demo_cli.py tests/test_demo_run_arm.py tests/test_demo_render.py tests/test_demo_compare.py tests/test_demo_acoustic.py tests/test_listen_sample.py tests/test_listen_http.py tests/test_listen_clips.py tests/test_listen_collect.py tests/test_blocks.py tests/test_asr_core.py tests/test_asr_report.py tests/test_asr_http.py tests/test_synth_pleos.py --basetemp .local/pytest-t50 -p no:cacheprovider -q
```

가짜 adb로 prepare → push → run → pull → collect, CRLF 크기 응답, 잘못된 크기 거부,
ASR 형식 검증, 원래 시각 복원, 실패 조각만 재개, 190.25초 합성 출처의 무겹침 분할을 검사했다.
sources/blocks/grid/split/serve/agree/export/stats CLI와 서버, test 잠금과 접근 로그,
깨진 다른 판정자 파일을 열지 않는 성질, 다른 판정자 API의 404도 검사했다.
Node로 실제 클라이언트 코드를 실행해 청취 비율·탐색·반복 재생·저장·이동을 검사했다.
JavaScript `node --check`도 통과했다. 브라우저의 실제 배치와 스피커 출력은 확인하지 않았다.

별도로 `test_trial.py`까지 넓힌 실행은 **361 통과, 1 건너뜀, 2 실패**였다.
실패는 `test_encoder_random_tiny_one_step`, `test_generic_fast_v5_tokenizer_and_modernbert_cpu_step`의
설치된 `PIL/__init__.py` 읽기 `PermissionError`다. 패키지나 권한은 바꾸지 않았다.
원래 알려진 `tests/test_label.py` 9개 실패는 실행하거나 수정하지 않았다.

## T50b — 사용 구간 제한

2026-11-12. `data/real-ranges.tsv`를 읽어 승인 영상의 사용 구간을 제한했다.
영상에 행이 없으면 전체 길이를 한 구간으로 사용한다. 여러 행은 시각순으로 정렬하며
`range_index`는 그 순서의 0부터 시작하는 번호다. `m:ss`, `h:mm:ss`, 소수 초와
끝 시각의 `end`를 지원한다. 잘못된 형식·역전·영상 밖 시작·겹침은
`real-ranges.tsv:<줄 번호>`와 고정 이유로 실패한다. 끝 시각 초과는 길이로 자르고
`sources.json`의 `range_adjustments`에 행 번호·요청 끝·적용 끝을 기록한다.
미승인 영상의 행은 무시하며 `sources-summary.json`과 sources 출력의
`ignored_range_rows`로 센다. sources 콘솔에는 출처 수·전체 길이·사용 길이 합계만
숫자로 출력하고, 승인 메모는 로컬 sources에 저장한다.

prepare는 각 구간 안에서 최대 90초씩 PCM을 만들며 경계를 넘지 않는다.
샘플 경계는 시작을 올림하고 끝을 내림하므로 구간 밖 샘플을 포함하지 않는다.
조각 offset과 collect 줄 시각은 원래 영상 기준이다. 구간 시각이 바뀌면 run 전체에서
성공 조각·ASR 결과·판정이 없는 경우에만 계획을 다시 만들고 이전 PCM·raw·클립과
아직 판정하지 않은 묶음을 정리한다. collect 전의 정상 raw TSV도 보호한다.
결과나 판정이 있으면 새 run 이름이 필요하다. 원본 파일 또는 조각 길이 변경은
기존처럼 새 run 이름이 필요하다. 기존 T50 전체 영상 계획도 구간 계획으로 전환된다.

묶음은 구간별로 생성한다. 모든 구간의 마지막 묶음은 **`range_end`**로 닫는다.
마지막 구간이나 전체 영상 기본 구간에도 같은 규칙을 적용하며 `end_of_source`를 쓰지 않는다.
구간을 넘거나 사용 구간 밖에 있는 ASR 줄은 글을 임의로 자르지 않고 실패시킨다.
grid에는 길이·침묵·range_end의 닫힘 비율을 표시한다. stats의 `by_source`에는
출처별 `usable_seconds`와 묶음 수만 추가했다.

청취 화면에는 해당 구간의 note를 **승인 메모 (전사 아님)**로 작게 표시한다.
클립은 원래 영상 시각에서 앞뒤 최대 2초를 붙이되 해당 구간 안으로 제한한다.
구간 경계가 바뀌면 클립 캐시 이름도 바뀐다. `serve.py`의 기존 실제 모드 연결을
확인했으며 별도 수정은 필요하지 않았다. `--serial` 인자가 adb 호출에 전달되는 것을
가짜 adb 테스트로 확인했고, 위 명령 예시는 모두 `$serial`을 사용하도록 고쳤다.

### 실제 v1 준비 확인

아래 세 명령을 순서대로 실제 실행했다. 네트워크·패키지 설치·adb·에뮬레이터·커밋은
사용하지 않았다. 실제 Pleos 전사와 사람 판정도 실행하지 않았다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -m eval.real sources --run v1
.\.venv\Scripts\python.exe -X utf8 -m eval.real split --run v1
.\.venv\Scripts\python.exe -X utf8 -m demo.pleos prepare --real v1 --chunk-seconds 90
```

| 항목 | 결과 |
| --- | ---: |
| 출처 수 | 13 |
| 전체 길이 합계 (초) | 12,828.953 |
| 사용 길이 합계 (초) | 6,367.448 |
| PCM 조각 수 | 76 |
| 최대 조각 길이 (초) | 90 |
| PCM 실제 샘플 길이 합계 (초) | 6,367.3078125 |
| PCM 바이트 합계 | 203,753,850 |
| dev / test 출처 수 | 9 / 4 |
| 무시한 미승인 구간 행 / 끝 시각 보정 수 | 0 / 0 |

사용 길이는 컨테이너의 영상 길이 기준이며, PCM 길이는 디코딩된 실제 샘플 수 기준이다.
76조각 전부 해당 구간 안에 있고 PCM 파일 크기가 계획과 일치함을 확인했다.
`data/real-ranges.tsv`, `data/approved.tsv`는 수정하지 않았다.
`data/real-split.tsv`는 기존 11행의 바이트를 보존하고 새 출처 2행만 덧붙였다.
실제 v1의 ASR 파일·성공 조각·판정 파일은 모두 0개다.

### T50b 검증

T50 관련 테스트 묶음 전체: **383 통과, 1 건너뜀, 실패 없음**(22.84초).
새 검증 25개는 형식·겹침·end·끝 시각 보정·미승인 행 무시, 구간별 PCM 바이트와
시각 복원, 구간별 묶음·grid·stats, 결과가 없을 때의 기존 계획 전환과 준비물 정리,
성공 조각·ASR·판정·정상 raw가 있을 때 재생성 거부, 클립 여유·캐시와 화면 note,
serial 전달과 split 원문 보존을 검증한다. 음성·문장은 모두 합성하거나 지어냈다.
Pillow 권한 문제가 알려진 `tests/test_trial.py`는 이번 묶음에 포함하지 않았다.

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_real.py tests/test_demo_pleos.py tests/test_demo_pleos_profile.py tests/test_demo_cli.py tests/test_demo_run_arm.py tests/test_demo_render.py tests/test_demo_compare.py tests/test_demo_acoustic.py tests/test_listen_sample.py tests/test_listen_http.py tests/test_listen_clips.py tests/test_listen_collect.py tests/test_blocks.py tests/test_asr_core.py tests/test_asr_report.py tests/test_asr_http.py tests/test_synth_pleos.py --basetemp .local/pytest-t50b -p no:cacheprovider -q
```

## T50c — push 크기 확인 재시도

2026-11-12. Claude가 실제 Pleos AVD에서 push의 `push-verify (PCM size mismatch)`를
확인했다. 첫 시도는 첫 파일, 재시도는 둘째 파일에서 실패했지만 직후 직접 stat을
읽으면 로컬과 같은 2,880,000바이트였다. 원인은 `exec-in` 반환 뒤에도 기기의
`cat >` 쓰기가 끝나지 않은 순간에 크기를 읽는 경쟁 상태이며, 직접 stat 방식은 유지한다.

기대보다 작은 크기만 0.2초 간격으로 재확인한다. 파일별 기본 제한은 10초이며
`push(..., verify_timeout=...)` 또는 CLI `--push-verify-timeout`으로 조정한다.
stat 호출에도 남은 시간을 전달한다. 크기가 같아지면 통과하고, 초과·비숫자 응답은
즉시, 제한 시간 만료는 `TransferError('push-verify', 'PCM size mismatch')`로 실패한다.
기존 clean 후 전체 재전송 동작을 유지하며, `now`·`sleep`을 주입할 수 있다.

가짜 adb와 가상 시계로 작은 크기 후 일치(데모·실제 workspace 각각 두 파일),
계속 작은 크기의 시간 만료, 초과·비숫자 즉시 실패, stat의 남은 시간 제한,
CLI 제한 시간 조정·잘못된 값 거부, 같은 크기 파일도 clean 후 재전송함을 검증했다.
아래 지정 테스트는 **154 통과, 실패 없음**(9.47초)이었다.
adb·에뮬레이터·네트워크·설치·커밋은 사용하지 않았으며 데이터 TSV와 `.local/` 실물은
수정하지 않았다. pytest 임시 파일은 지정한 `.local/pytest-t50c`에만 생성했다.
실제 전사는 실행하거나 기록하지 않았다.

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_demo_pleos.py tests/test_real.py --basetemp .local/pytest-t50c -p no:cacheprovider -q
```

## T50d — dev 제안 확인 화면

2026-11-12. 76개 묶음(dev 55, test 21)을 나누어 판정한다. test는 점수의 기준이므로
사람이 직접 듣고 판단하며 Claude 제안을 보지 않는다. dev는 Claude가 전사 글로 먼저
제안하고 사람이 확인하거나 고친다. 이 분담은 이번 작업에서 합의한 것으로,
dev는 듣지 않고도 저장할 수 있다. `listened`는 기존처럼 실제로 들은 구간이 80% 이상일 때만 켜진다.

```powershell
.\.venv\Scripts\python.exe -m tools.listen.serve --real v1 --annotator vizatae --suggest claude --port 8790
.\.venv\Scripts\python.exe -m eval.real agree vizatae claude --run v1
.\.venv\Scripts\python.exe -m eval.real stats --run v1
.\.venv\Scripts\python.exe -m eval.real export --run v1
```

서버를 종료한 뒤 agree/stats/export를 실행한다. 위 명령은 사용 예시이며 실물 자료로 실행하지 않았다.
현재 사용 중인 8770 서버는 건드리지 않았다. `--suggest`를 빼면 제안을 읽거나 붙이지 않는다.
사람 판정자로 `claude`는 쓸 수 없다(대소문자 구분 없이 거부).

제안 읽기는 `eval.real.load_suggestions` 한 곳이다. 고정 파일
`.local/real/<run>/judgments/claude.jsonl`에서 현재 묶음에 속한 dev만 돌려준다.
test·미분할 출처·없는 묶음은 버리며 같은 id는 마지막 줄을 쓴다.
API는 각 묶음에 `split`을 붙이고 dev 제안에만 `suggestion` 여섯 필드
(`keep/kind/none_reason/unsure/policy/comment`)를 붙인다. test에는 그 키 자체가 없다.
다른 사람 판정 파일을 읽는 API는 추가하지 않았다.

화면의 `test만 / dev만 / 전체` 선택은 localStorage에 기억하며 저장소가 막혀 있어도 동작한다.
기본 선택은 전체다. 진행률은 선택한 묶음과 전체를 함께 보여 주고 이전·다음·J는 선택 안에서 움직인다.
dev 제안은 별도 상자에 표시하고 판정 칸을 미리 채운다. 이미 저장한 사람 판정이 있으면 먼저 채운다.
`제안 맞음 (A)`은 제안 값을 그대로 저장하고 다음으로 간다. test는 제안 표시나 미리 채우기를 하지 않는다.
상단의 판정 순서는 유지했다.

dev에 제안이 있을 때만 사람 판정에 아래 두 키를 더한다.

```json
{"suggested_by":"claude","review":"accepted"}
```

서버가 제안과 저장값 여섯 필드를 비교한다. 같으면 `accepted`, 하나라도 다르면 `changed`다.
정책의 나열 순서는 비교에서 무시한다. test·제안 없음·제안 모드를 끈 경우에는 두 키를 넣지 않는다.
기존 판정 형식과 `labeler: human:<이름>`은 유지한다.

agree에서 이름을 명시하면 사람 대 Claude를 비교할 수 있다. 기본은 dev이며 test 잠금과
접근 로그 규칙은 유지한다. Claude 파일에 test id가 있으면 id나 내용을 출력하지 않고 제외 개수만 경고한다.
stats의 `splits.dev.reviews.<이름>`에 `accepted/changed` 개수만 더했다.
기본 `load_labels`와 stats/export는 Claude 파일을 읽지 않는다. export와 consensus에는 사람 판정만 쓴다.

검증 결과는 **413 통과, 1 건너뜀, 실패 없음**(22.89초)이다.
지정된 real·Pleos 검사와 listen 검사 네 파일, 기존 T50 관련 검사 전체를 함께 실행했다.
합성 PCM·지어낸 문장·임시 판정 파일만 사용했다. test 제안 배제, 미분할 배제, 마지막 제안 우선,
옵션 없음, 예약 이름 거부, accepted/changed 저장과 재읽기, 사람 대 Claude 일치도와 잠금,
사람만 export하는 동작을 확인했다. Node로 실제 화면 JS를 실행해 필터·진행률·이동·제안 미리 채우기,
사람 판정 우선·A 확인·저장 실패·localStorage 예외를 확인했다. 실제 브라우저 배치와 음성 출력은 확인하지 않았다.

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_real.py tests/test_demo_pleos.py tests/test_demo_pleos_profile.py tests/test_demo_cli.py tests/test_demo_run_arm.py tests/test_demo_render.py tests/test_demo_compare.py tests/test_demo_acoustic.py tests/test_listen_sample.py tests/test_listen_http.py tests/test_listen_clips.py tests/test_listen_collect.py tests/test_blocks.py tests/test_asr_core.py tests/test_asr_report.py tests/test_asr_http.py tests/test_synth_pleos.py --basetemp .local/pytest-t50d -p no:cacheprovider -q
```

실물 `vizatae.jsonl`·`claude.jsonl`과 `data/*.tsv`는 읽거나 수정하지 않았다.
실물 제안 파일 생성·수정·삭제, adb·에뮬레이터·외부 네트워크·설치·커밋도 하지 않았다.
HTTP 검사는 임시 폴더의 가짜 자료를 쓰는 로컬 테스트 서버만 사용했다.

## T50e — 탑승 구성과 채널 단위 분할

2026-11-12. DESIGN §1·§8의 결정에 따라 실제 평가의 주 수치를 아이+가족 묶음으로
제한한다. 어른 가족·부부·친구는 참고로 남긴다. 같은 가족의 영상을 추가 수집하므로
새 영상은 채널 단위로 분할해 dev와 test 사이에 같은 가족이 섞이는 것을 막는다.

### 파일 형식과 읽기

`data/real-cast.tsv`는 다음 네 열을 순서대로 갖는다. 아래 행은 지어낸 예시다.

```tsv
video_id	cast	by	note
abcdefghijk	child_family	user	지어낸 구성 메모
```

`cast`는 `child_family | adult_family | couple | friends | unknown` 중 하나다.
`eval.real.read_cast` 한 곳에서 읽고, 누락된 표·출처는 `unknown`으로 처리한다.
잘못된 열·id·cast·중복 id는 `real-cast.tsv:<줄 번호>`와 고정 이유로 실패한다.
`by/note`와 오류가 난 값은 보고서·예외에 출력하지 않는다.
`channel_id`는 `data/sources.tsv`에서 읽고, 출처나 채널 정보가 없으면 빈 문자열이다.

sources는 각 출처에 `cast/channel_id`를 저장한다. 요약의 `by_cast.<cast>`에는
`sources/usable_seconds`를 숫자로 넣는다. 음성이 빠진 출처도 출처 수에는 포함하고
사용 길이는 0으로 센다. 기존 sources 결과를 다시 만들지 않아도 보고 명령은 현재
cast 표를 읽으므로 기존 v1에도 필터가 적용된다.

### 채널 단위 분할

기존 분할표 전체를 기준으로 같은 채널의 split을 상속한다. 처음 보는 채널은
기존 SHA-256 규칙의 입력을 channel_id로 바꾸고, 채널 정보가 없으면 video_id를 쓴다.
최소 dev/test 보정은 서로 다른 분할 단위가 2개 이상일 때 새 채널 전체에만 적용한다.
상속받는 채널이나 기존 행은 보정하지 않으며, 보정할 새 단위가 없으면 그대로 둔다.
기존 행의 바이트·순서·줄 끝을 보존하고 새 영상만 덧붙인다.

기존 행에 같은 채널의 dev/test가 섞여 있으면 실패하지 않고 stderr에 채널 id와
영상 id만 경고한다. 그 채널의 새 영상은 분할표에서 먼저 기록된 행의 split을 따른다.
기존 혼재를 자동 수정하지 않으므로 경고는 추가 행이 없는 실행에서도 나온다.

### 명령과 잠금

`stats/agree/export/grid`에 `--cast`를 여러 번 지정하면 그 구성들의 합집합을 선택한다.
생략하면 전체다. 다음은 dev 평가 예시다. 이번 작업에서는 아래 첫 번째
`stats --cast child_family`만 실물 자료로 실행했다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -m eval.real stats --run v1 --cast child_family
.\.venv\Scripts\python.exe -X utf8 -m eval.real agree vizatae claude --run v1 --cast child_family
.\.venv\Scripts\python.exe -X utf8 -m eval.real export --run v1 --cast child_family
.\.venv\Scripts\python.exe -X utf8 -m eval.real grid --run v1 --cast child_family
# 참고 구성 둘을 함께 선택
.\.venv\Scripts\python.exe -X utf8 -m eval.real stats --run v1 --cast adult_family --cast couple
```

stats의 `selected_blocks/annotators`와 split별 판정·제안 확인 수는 필터를 적용한다.
`by_cast`는 필터와 관계없이 구성별 전체 묶음 수(`blocks`), 허용된 split의 묶음 수
(`selected_blocks`), 그 split의 판정 분포(`annotators`)를 숫자로 낸다.
전체 출처·묶음 수와 split별 출처·묶음 수는 기존처럼 전체 메타데이터 기준이다.
cast별 판정 분포를 내기 위해 stats는 허용된 split의 모든 cast 판정을 검증한다.
기본 dev 실행에서는 test 판정을 검증하거나 집계하지 않으며 test 판정 집계는 null이다.
grid는 기존처럼 판정 파일을 읽지 않고 ASR을 묶은 숫자 표에 cast 필터를 적용한다.

test/all 판정 접근은 cast 필터보다 먼저 잠금을 검사한다. 선택 결과가 0개여도
`--open-test`가 필요하고, 열면 매번 라벨 읽기 전에 접근 로그를 쓴다.
export의 판정자별 행과 consensus 행에는 `cast`가 들어가며 코멘트는 들어가지 않는다.
기존 `export/<split>/` 경로를 사용하고 manifest의 `cast`에 선택 목록(전체는 null)을 남긴다.

### 검증과 실제 dev 통계

합성 PCM·지어낸 표·판정으로 cast 값·중복·형식과 줄 번호, 누락 시 unknown,
출처별 채널과 사용 길이 요약, 채널 해시·분할 상속·기존 행 보존·혼재 경고,
반복/복수/빈 선택 필터, stats의 전체 cast 집계, test 잠금·접근 로그,
export의 판정자별·consensus cast 필드를 검사했다.

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_real.py tests/test_demo_pleos.py --basetemp .local/pytest-t50e -p no:cacheprovider -q
```

지정 테스트 결과는 **216 통과, 실패 없음**(15.99초)이다.
기존 sources 요약 전체를 비교하던 검사는 새 `by_cast` 필드까지 검증하도록 갱신했다.

요청한 실제 명령을 실행했고 종료 코드는 0이었다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -m eval.real stats --run v1 --cast child_family
```

| 항목 | 수치 |
| --- | ---: |
| 전체 출처 / 묶음 | 13 / 76 |
| dev 출처 / 묶음 | 9 / 55 |
| test 출처 / 묶음 (메타데이터만) | 4 / 21 |
| 선택된 child_family dev 묶음 / 판정 | 23 / 23 |
| child_family keep / none | 17 / 6 |
| child_family story / moment | 16 / 1 |
| child_family unsure / 비율 | 9 / 0.391304347826087 |
| child_family dev 제안 accepted / changed | 22 / 0 |

필터와 관계없이 함께 출력된 cast별 숫자다. 판정 분포는 dev만 센다.

| cast | 전체 묶음 | dev 묶음 | dev 판정 | keep | none | story | moment | unsure |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| child_family | 23 | 23 | 23 | 17 | 6 | 16 | 1 | 9 |
| adult_family | 19 | 19 | 19 | 3 | 16 | 1 | 2 | 8 |
| couple | 5 | 5 | 5 | 3 | 2 | 2 | 1 | 3 |
| friends | 7 | 7 | 7 | 5 | 2 | 4 | 1 | 0 |
| unknown | 22 | 1 | 1 | 1 | 0 | 0 | 1 | 1 |

test의 판정 집계와 제안 확인 집계는 null로 남았다. 실제 전사·판정 코멘트는
기록하지 않았다. 실제 데이터 TSV와 `.local/real/v1/judgments/*`는 수정하지 않았다.
실제 split 명령과 adb·에뮬레이터·외부 네트워크·설치·커밋은 실행하지 않았고
8766~8770 서버는 건드리지 않았다. 테스트는 임시 로컬 포트만 사용했다.
