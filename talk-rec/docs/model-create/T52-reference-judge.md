# T52 — 기준선 판정기 v3

2026-11-12. Python은 `talk-rec/model-create/.venv/Scripts/python.exe`를 사용했다.
실제 agy·command 판정, 외부 네트워크, 실제 자료 평가, 커밋은 실행하지 않았다.
테스트 입력과 사람 판정은 모두 지어낸 자료이며 지정한 pytest 임시 폴더에만 만들었다.

## 만든 것

- `eval/reference.py`: judge/compare 명령, 출처별 배치, 응답 검증, 재시도와 재개.
- `eval/reference_report.py`: 다수결, 이진 지표, 반복 일치도, 출처 단위 부트스트랩, JSON·Markdown 보고서.
- `prompts/labeler.md`: v2 내용을 지우고 `prompts/judge.md(v3)` 안내로 바꿨다.
- `tests/test_reference.py`, `tests/test_reference_report.py`: 새 테스트 68개.

`prompts/judge.md`는 수정하지 않았다. 시스템 지시문은 이 파일 전체를 그대로 읽는다.
`pipeline.label`의 AgyBackend, CommandBackend, FakeBackend, UsageLimit, BackendError,
parse_answer와 원자적 파일 쓰기를 재사용했다. 기존 `demo/run_arm.py`의 배치·호출 기록
방식을 참고했고, 기존 파일 자체는 수정하지 않았다. `tests/test_label.py`도 수정하지 않았다.
작업 중 보이던 다른 작업의 변경은 수정하거나 되돌리지 않았다.

## Claude가 실행할 명령 — PowerShell

실제 실행 전에 v1의 `blocks.jsonl`, `data/real-split.tsv`와 사람 판정을 준비한다.
아래 명령은 이번 작업에서 실물 자료로 실행하지 않았다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create

# dev 실행: 출처별 최대 7묶음, 독립적인 3회 반복.
# --retries 2는 최초 호출 외에 최대 두 번 추가 호출한다는 뜻이다.
.\.venv\Scripts\python.exe -X utf8 -m eval.reference judge --run v1 --split dev --repeat 3 --backend agy --model gemini-3.8-flash-low --out-name reference-v3-dev --retries 2

# 중단·한도 도달·실패 배치가 생긴 뒤 재개: 같은 명령을 그대로 실행한다.
.\.venv\Scripts\python.exe -X utf8 -m eval.reference judge --run v1 --split dev --repeat 3 --backend agy --model gemini-3.8-flash-low --out-name reference-v3-dev --retries 2

# dev 비교: 주 수치는 child_family. 기본 사람 판정자는 vizatae다.
.\.venv\Scripts\python.exe -X utf8 -m eval.reference compare --run v1 --out-name reference-v3-dev --split dev --annotator vizatae --cast child_family

# 한도에 걸리면 10분 기다렸다 같은 배치를 재시도하는 실행/재개 방식.
.\.venv\Scripts\python.exe -X utf8 -m eval.reference judge --run v1 --split dev --repeat 3 --backend agy --model gemini-3.8-flash-low --out-name reference-v3-dev --retries 2 --wait-on-limit 10
```

`--agy-path`로 실행 파일을 지정할 수 있다. command 백엔드는 `--backend command`와
`--command`를 함께 쓰며, 셸 연산자 없이 argv를 구성하고 지시문·입력을 stdin으로 보낸다.
명령 템플릿에는 `{model}`, `{effort}`를 쓸 수 있다. fake 백엔드는 항상 `none`으로
응답하는 전송·저장 점검용이며 실제 기준선 성능을 나타내지 않는다.

기본 judge 분할은 dev다. test를 판정할 때는 별도 출력 이름을 쓴다.
judge는 사람 판정을 전혀 읽지 않는다. test 사람 판정은 비교 때 명시적으로 연다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -m eval.reference judge --run v1 --split test --repeat 3 --backend agy --model gemini-3.8-flash-low --out-name reference-v3-test --retries 2
.\.venv\Scripts\python.exe -X utf8 -m eval.reference compare --run v1 --out-name reference-v3-test --split test --open-test --annotator vizatae --cast child_family
```

compare의 사람 판정 읽기는 반드시 `eval.real.load_labels`를 통한다. `--split test`만
주면 잠기며, cast 선택이 비어도 잠금은 유지된다. `--open-test`만 주고 split을 생략하면
eval.real처럼 dev+test 접근을 요청한다. 단, 비교표는 해당 출력 이름으로 판정한 분할만 낸다.
test 접근은 매번 기존 `test-access.log`에 `library` 명령으로 먼저 기록한다.
이 필수 접근 로그 외의 새 결과는 해당 reference 출력 폴더 안에만 만든다.

## 저장과 재개

입력은 `.local/real/<run>/blocks.jsonl`이며 `data/real-split.tsv`로 분할을 고른다.
같은 출처의 묶음만 최대 7개씩 보낸다. 배치마다 번호를 0부터 붙이고,
각 묶음을 `<block id="N">`과 `</block>`으로 감싼다. 입력 줄과 빈 줄을 그대로 보존한다.
완료 여부와 무관하게 배치 경계를 고정하므로 재개 때도 문맥·번호가 바뀌지 않는다.

출력 위치는 `.local/real/<run>/reference/<out-name>/`다.

| 파일 | 내용 |
| --- | --- |
| `config.json` | 선택 입력·지시문·모델·분할·반복 수·배치 크기·백엔드 설정의 서명 |
| `r1/`, `r2/`, `r3/`의 `calls/*.json` | 호출별 판정, 묶음 id, 상태, 재시도 소진 여부, 초, 토큰 수, 예정 대기 초 |
| 각 반복의 `predictions.jsonl` | 성공 판정의 id·verdict·none_reason·unsure |
| `comparison.json` | 지표·구간·흔들림·운영 통계·불일치 id 목록 |
| `comparison.md` | 전사나 묶음 id 목록이 없는 사람이 읽는 표 |

검증은 개수·순서·정확한 키·타입·허용 값을 확인한다. `none_reason`은
빈 값/not_car/not_family/negative만 가능하고, none이 아닌 판정에서는 반드시 빈 값이다.
note는 공백만으로 이뤄지거나 500자를 넘거나 줄바꿈·여러 문장의 종결 부호를 담으면 거부한다.
어느 입력 줄이든 공백을 뺀 연속 8글자(`QUOTE_CHARS`)를 그대로 담은 note는 비우고 판정은 받는다. note는 어차피 저장하지 않는다.
처음에는 연속 4글자에서 배치 전체를 거부했으나, 실제 dev 실행에서 "는 대화" 같은 흔한 말 조각이 걸려 배치 151개 중 145개가 거부됐다. 8글자로 늘린 뒤에도 화제 이름을 옮겨 적는 한 배치가 계속 거부돼, 거부 대신 note를 비우도록 바꿨다(2026-11-12, Claude 수정).
원시 응답·프롬프트·예외 본문은 파일이나 콘솔에 남기지 않는다. CLI 예외는 종류와 코드 위치만 낸다.

검증 오류와 BackendError는 모두 해당 배치에서만 재시도한다. 소진되면 실패 기록을 남기고
다음 배치와 다음 반복으로 계속한다. 실행의 종료 코드는 정상 0, 배치 소진 있음 1,
입출력·서명·설정 오류 2, 대기 옵션 없는 한도 도달 3, Ctrl+C 130이다.
UsageLimit은 일반 재시도 횟수를 소비하지 않는다. 대기 옵션이 있으면 30초 이하 조각으로
기다리며, 없으면 성공 기록을 보존하고 멈춘다.

재개는 각 반복의 성공 배치만 건너뛴다. 실패·한도·중단 배치는 다시 시도하며,
일반 재시도 예산은 실행할 때마다 새로 시작한다. 원자적으로 저장한 호출 JSON이 기준이므로
판정 JSONL이 유실돼도 호출 기록에서 복원한다. 같은 출력 이름의 동시 실행은 파일 잠금으로 막는다.
선택 입력·지시문·모델·분할·반복 수·배치 크기·백엔드 설정이 바뀌면 이어 쓰기를 거부한다.
입력이나 지시문이 바뀐 뒤 기존 결과를 비교하는 것도 거부한다. 이런 경우 새 out-name을 쓴다.
시간 제한·대기 시간·재시도 횟수는 재개 때 조정할 수 있다.

## 비교표의 계산 기준

- story와 moment를 합친 keep 대 none을 먼저 다수결로 정한다. 동률은 keep이다.
  keep 안의 종류가 동률이면 story, none 이유가 동률이면 빈 값/not_car/not_family/negative 순이다.
  이진 동률 수와 종류 동률 수를 JSON에 기록한다.
- keep과 none 각각의 재현율·정밀도·F1, 정확도, Cohen κ, 남길 비율을 낸다.
  사람 unsure 포함·제외를 각각 계산한다. 제외 기준은 사람의 unsure다.
- 전부 남김도 동일한 사람·기준선 공통 묶음에서 한 행으로 계산한다.
  `selected`는 --cast의 합집합이며, 구성별 표는 판정한 분할 전체를 보여 준다.
  child_family를 주 수치로 명시한다. 구성 표가 없으면 unknown이다.
- 일부 반복만 성공했으면 성공한 투표만으로 판정하되 부분 투표·누락·전회 완료 수를 함께 낸다.
  완전히 누락된 묶음은 두 방법의 성능 분모에서 제외된다. JSON에는 사람 판정 수와
  전체 투표 대상의 남길 비율도 따로 있어 분모 차이를 확인할 수 있다.
- 모든 반복이 완료된 묶음에서 keep/none 전회 일치율, verdict 전회 일치율,
  반복 쌍별 κ 평균을 낸다. JSON에는 각 쌍 자체의 공통 묶음 수·κ도 따로 둔다.
- 95% 구간은 출처를 복원 추출하고 그 출처의 대상 묶음 전체를 함께 넣는 percentile
  부트스트랩이다. 기본 2,000회, 시드 52이며 `--bootstrap`으로 횟수를 바꿀 수 있다.
  구성·사람 unsure 필터를 적용한 뒤 계산하고 흔들림에도 구간을 붙인다.
- 0으로 나누거나 κ의 우연 일치율이 1인 경우 JSON은 null, 표는 —다.
  구간의 `defined_samples`는 지표가 정의된 재표집 횟수다.
- 현재 실패 배치 수는 마지막 호출이 재시도 소진인 반복별 배치 수다.
  재개 후 성공하면 현재 실패 수에서는 빠지며 과거 소진 횟수는 따로 남는다.
- 시간은 재시도·실패 호출을 포함하고 한도 대기·파일 쓰기를 제외한다.
  `mean_seconds_per_block`은 전체 반복 호출 초를 한 번이라도 시도한 고유 묶음 수로 나눈다.
  `mean_seconds_per_block_repetition`은 시도한 (반복, 묶음) 수로 나눈다.
  호출이 공유한 시간은 배치 묶음 수로 나누어 배분한다. 토큰 수는 백엔드가 알려 준
  숫자 필드만 기록하며, 미제공 시 빈 객체다. 호출 기록의 대기 초는 예정 값이다.

## 검증 결과

새 테스트 **68개를 포함해 268개 통과, 실패 0개**였다. 최종 실행 시간은 **17.87초**다.
새 테스트만 실행한 중간 단계는 63개 통과였고, 이후 note 끝 줄바꿈·붙은 문장,
성공 호출 토큰·실패 다음 반복 진행·동시 쓰기 잠금 검증을 추가해 68개가 됐다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
.\.venv\Scripts\python.exe -m pytest tests/test_reference.py tests/test_reference_report.py tests/test_real.py tests/test_demo_run_arm.py tests/test_label_agy.py --basetemp .local/pytest-t52 -p no:cacheprovider -q
```

배치 경계·번호·빈 줄·시스템 지시문 전달, 인용과 잘못된 none_reason 거부,
배치 재시도·실패 후 진행·재개·서명 불일치, 독립 반복·한도 대기·중단 복구,
다수결·동률·지표 손계산·출처 재표집 재현성, unsure·cast·전부 남김,
test 잠금과 읽기 전 접근 로그·로그 쓰기 실패 차단, 원문 비노출을 확인했다.
CLI judge/compare의 도움말도 확인했다.

`tests/test_label.py`는 이번 실행 대상에서 제외했다. 알려진 기존 실패 9개는 T62에 남긴다.
전체 저장소 pytest 통과나 실제 모델 성능을 주장하지 않는다.

## 알아 둘 점과 한계

실제 성능·지연·토큰 수는 아직 측정하지 않았다. 실제 agy 실행은 Claude가 한다.
기존 agy 전송부의 Windows 명령 길이 제한을 유지하므로 긴 배치는 BackendError로
처리될 수 있다. 배치 크기를 줄일 때는 서명이 바뀌므로 새 출력 이름을 사용한다.

note의 한 문장 검사는 종결 부호를 이용하는 형식 검사이며 자연어 문장 의미를 검증하지 않는다.
출처가 적거나 구성별 표본이 작으면 신뢰구간이 불안정하고, 출처 하나면 구간이 한 값이 된다.
부분 투표 결과는 최종 성능으로 확정하기 전에 실패 배치를 재개해 완료 수를 확인해야 한다.

현재 `eval.real.validate_judgment`의 사람 none_reason 허용 값은 빈 값/not_car/negative다.
새 모델 응답의 not_family는 이 판정기에서 지원하지만, 사람 파일에도 그 값을 직접 넣으면
기존 load_labels 검증에서 거부된다. 허용 파일 범위 밖인 기존 사람 판정 스키마는 수정하지 않았다.
dev 사람 판정은 기존 Claude 제안을 확인한 기록을 포함할 수 있으며,
`listened=False` 판정도 load_labels가 돌려주는 그대로 평가한다.
본문만 받는 기준선이므로 음성·말투에서 얻는 사람의 정보와 차이가 있다.
