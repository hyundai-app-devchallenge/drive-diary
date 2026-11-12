# T47: 합성 대화의 길이 지름길 제거

T46에서 내용 없이 줄 모양만 보는 shape 모델도 합성 val 정확도 90.6%를 기록했다. 실제 Pleos에서는 none과 story의 길이가 비슷한데 기존 합성은 none을 짧게, story를 길게 만드는 경향이 있었다. 따라서 길이로 keep을 추측하는 경로부터 줄인다. 실제 전사 글·ID는 이 문서나 검사 로그에 기록하지 않는다. 커밋·네트워크·agy 호출은 하지 않았다.

## 카드와 예시 변경

`synth/cards.py`는 각 라벨 안에서 아래 비율을 먼저 배정한다. seconds와 n_turns를 같은 구간에서 함께 뽑는다. 라벨별 길이 제한을 없애고 카드 검증 상한을 26줄로 늘렸다.

| 구간 | 라벨별 비율 | 목표 초 | 원본 발화 줄 수 |
|---|---:|---:|---:|
| 짧음 | 25% | 15~30 | 5~8 |
| 중간 | 20% | 31~59 | 9~17 |
| 김 | 55% | 60~90 | 18~26 |

기존 fragments의 한두 줄과 침묵은 작은 예외로 남긴다. fragments/greeting/short_answers/unanswered는 기본 패턴의 1/4 가중치로 고르고 긴 구간에는 배정하지 않는다. 새 none 패턴은 처음 가는 곳 길 찾기·주차(`route_parking`), 어른끼리 일정·장보기·돈 의논(`adult_planning`), 업무 스피커폰 통화(`phone_work`), 형제 실랑이와 말리기(`sibling_squabble`), 주유·세차·계산 절차(`service_steps`)다. `phone_work`에서만 가족 밖 `통화상대` 화자를 추가 허용한다. 패턴에 맞게 어른 둘 또는 형제 둘을 확보한다.

모든 라벨에서 아이 없는 장면 25%, 가족 수 2~5명의 균등 배정, 세 energy의 균등 배정을 독립적으로 섞는다. story에도 어른 둘뿐인 장면, none에도 아이가 떠들고 정신없는 장면이 나온다. `moment_in_errands`는 60초 이상 용무 흐름 중 한마디만 남고 다시 용무로 돌아가는 패턴이다.

`synth/generate.py`는 `prompts/synth-examples/*.json`을 파일 이름순으로 전부 읽는다. 현재 28개이며 agy3의 8개는 긴 none 5개, 짧은 story 2개, 긴 흐름 속 moment 1개다. agy3의 패턴만 새 사전에 맞췄다. 배치 라벨을 모두 포괄하고 라벨마다 가장 많이 등장하는 길이 구간과 일치하는 예시를 우선한다. 해당 길이 예시가 없으면 같은 라벨을 고른다. 남은 자리는 아직 포괄하지 못한 라벨·길이 조합부터 채운다. 기존 2~3개 예시 및 Windows 명령 길이 제한을 유지한다. 모든 예시와 선택 시드는 재개 서명에 반영된다.

## 프롬프트와 시간 보정

`prompts/synth-scene.md`에 “none은 길고 말이 많아도 된다. 지금 할 일·길·용무·실랑이뿐이면 none이며 억지로 짧게 쓰지 않는다”, “story·moment는 아주 짧을 수 있다”를 명시했다. 어른 이야기의 긴 발화도 허용한다. 24줄·80초 목표를 48초로 압축하지 않도록 줄 사이 기다림·운전 집중·생각하는 침묵을 t에 반영하라고 지시한다.

그래도 생성 응답이 짧으면 `generate`가 카드별 검증 전에 `stretch_duration()`으로 t와 dur 모두에 `목표 초 / 실제 끝 시각`을 곱한다. 끝 시각을 목표에 맞추며 시작 0, 발화 순서, 겹침과 침묵의 비율, 텍스트를 유지한다. 비정상 시각·잘못된 순서·줄 수·화자 등은 기존처럼 거부한다. 목표보다 긴 장면은 줄이지 않는다. 짧아서 탈락하기 전에 보정한 장면을 저장하므로 degrade는 보정된 시각을 받는다.

보정은 기본 켬이며 생성 명령에 `--no-stretch-short`를 붙이면 끈다. 옵션은 재개 metadata에 기록되어 중간 변경을 거부한다. 기존 부분 성공 보존·실패 카드만 재개·숫자 오류 집계 기능을 유지했다. 프롬프트와 예시가 바뀌었으므로 기존 demo2/3 생성 결과를 새 설정으로 이어쓰지 않고 demo4를 사용한다.

## 지름길 검사

`python -m synth.shortcut`는 다음 두 입력 중 하나를 받는다.

- `--cards cards.jsonl`: seconds·n_turns·가족 수 3개만 사용한다.
- `--synth-dir DIR` 반복: `demo.trial.data.load_synth()`와 `demo.trial.models.shape()`를 재사용한다. 줄 수·총 글자·줄 길이 중앙값/평균/표준편차/최소/최대 7개를 사용하며 내용·단어·시각·화자·패턴은 분류기에 넣지 않는다.

`demo.trial.data.split_scenes()`를 시드 43/44/45/46/47로 반복하는 층화 80/20 교차검증이다. 5-fold의 서로 겹치지 않는 검증 집합이 아니라 **5회 반복 홀드아웃**이다. 같은 카드의 여러 export/level은 항상 같은 분할에 들어가고, 카드 번호의 라벨이 충돌하면 거부한다. 각 분할에서 StandardScaler와 로지스틱 회귀를 train에만 fit하고 문턱 0.5로 평가한다. 검증 관측 수로 가중 평균하며 문턱을 val 또는 실제 자료에 맞추지 않는다. 같은 관측이 여러 번 평가될 수 있다.

CV 정확도가 입력 전체의 다수 클래스 비율 + 10%p를 **초과하면 FAIL, 종료 코드 3**이다. 같거나 낮으면 PASS, 코드 0이다. 입력 오류는 전사·예외 원문 없이 고정 안내와 코드 2로 처리한다. 실제 자료는 `--real-judge`와 `--real-asr`를 함께 주면 분포표에만 사용한다. Pleos 중 judge.ids에 있는 항목을 사람 라벨로 집계하며 예측 라벨은 요구하지 않는다. 실제 자료로 fit하거나 PASS 문턱을 조정하지 않는다.

분포표는 라벨별 줄 수·총 글자·초의 min/중앙값/p90/max를 합성과 실제에 나란히 표시한다. 카드는 총 글자가 없어 가족 수를 표시한다. 총 글자는 trial과 동일한 양끝 공백 정리 후 줄바꿈을 제외한 합계다. 합성 길이는 block.end-start, 실제 길이는 lines의 마지막 end-첫 start이며 ASR 처리 소요시간 seconds를 쓰지 않는다. 출력에는 숫자와 고정 라벨·항목명만 있으며 발화나 ID는 없다.

## 로컬 실행 결과

400장, seed 91의 `.local/synth/demo4/cards.jsonl`을 생성하고 실제 CLI로 검사했다. story/moment/none은 160/80/160장이다. 아이 없는 비율은 각각 40/20/40장, 가족 수는 라벨마다 2/3/4/5명에 균등하게 배정됐다. 긴 none은 **88/160장(55%)**, 짧은 story는 **40/160장(25%)**, 짧은 moment는 **20/80장(25%)**이다. 긴 용무 속 moment 패턴은 13장, 조용한 기존 none 4종은 합쳐 18장이다.

| 대상 | 장면 수 | 5회 정확도 (%) | CV 정확도 | 다수 비율 | FAIL 기준 | 판정·종료 코드 |
|---|---:|---|---:|---:|---:|---|
| 새 카드 seed 91 | 400 | 60 / 60 / 62.5 / 60 / 60 | 60.50% | 60.00% | 70.00% 초과 | PASS · 0 |
| 기존 demo2 묶음 | 156 | 90.625 / 81.25 / 87.5 / 78.125 / 81.25 | 83.75% | 62.18% | 72.18% 초과 | FAIL · 3 |

각 실행의 검증 관측 수 합계는 카드 400, demo2 160이다. demo2는 none/story/moment 59/66/31묶음이며 다수 클래스는 keep 97/156이다. 분류기 입력에는 아래 초를 포함하지 않는데도 FAIL이 재현됐다. 이 검사는 demo2를 읽기만 했으며 원본을 덮어쓰지 않았다.

다음은 demo2 및 실제 사람 판정 20묶음(none/story/moment 8/7/5)의 CLI 분포 숫자다. 각 셀 순서는 min / 중앙값 / p90 / max다.

| 라벨 | 특징 | demo2 | 실제 Pleos |
|---|---|---|---|
| none | 줄 수 | 1 / 6 / 11 / 13 | 4 / 19 / 23.30 / 24 |
| none | 총 글자 | 17 / 89 / 156.40 / 198 | 76 / 327.50 / 425.40 / 431 |
| none | 초 | 11.50 / 32.40 / 55.34 / 83.80 | 17.10 / 84.95 / 91.66 / 91.80 |
| story | 줄 수 | 3 / 10 / 16.50 / 24 | 17 / 19 / 23.80 / 25 |
| story | 총 글자 | 88 / 222.50 / 286.50 / 465 | 275 / 380 / 474.20 / 476 |
| story | 초 | 14.40 / 41.10 / 68.90 / 77.50 | 56.57 / 71.10 / 87.42 / 87.59 |
| moment | 줄 수 | 4 / 7 / 13 / 16 | 12 / 14 / 19.80 / 21 |
| moment | 총 글자 | 61 / 144 / 186 / 276 | 120 / 249 / 288.60 / 307 |
| moment | 초 | 13.70 / 34.80 / 65 / 71.20 | 31.90 / 47.50 / 68.12 / 78.40 |

## 실행 순서 — Windows PowerShell

아래 생성부터는 사용자가 agy가 있는 환경에서 실행한다. 카드 생성과 카드 검사는 이미 수행했지만 동일 명령으로 재현할 수 있다. 검사 종료 코드가 0인지 확인한 뒤 다음 단계로 진행한다. 생성 종료 코드 4는 실패 카드가 남은 부분 성공이므로 같은 명령으로 재개한다. 부분 성공 export도 허용되므로 생성 완료 장수를 확인해야 한다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create

# 1. 카드 400장
.\.venv\Scripts\python.exe -X utf8 -B -m synth.cards --n 400 --seed 91 --out .local/synth/demo4/cards.jsonl

# 2. 카드 지름길 검사: PASS 확인
.\.venv\Scripts\python.exe -X utf8 -B -m synth.shortcut --cards .local/synth/demo4/cards.jsonl

# 3. 생성 — 사용자가 실행; Gemini 3.8 Flash medium, concurrency 3
.\.venv\Scripts\python.exe -X utf8 -B -m synth.generate --cards .local/synth/demo4/cards.jsonl --backend agy --model gemini-3.8-flash-medium --out .local/synth/demo4/scenes.jsonl --seed 91 --concurrency 3 --wait-on-limit 10

# 4. Pleos level 1 열화
.\.venv\Scripts\python.exe -X utf8 -B -m synth.degrade --cards .local/synth/demo4/cards.jsonl --scenes .local/synth/demo4/scenes.jsonl --out-dir .local/synth/demo4 --style pleos --profile .local/demo/pleos/profile.json --level 1 --seed 91

# 5. 묶음 지름길 검사: FAIL이면 trial 전에 생성 결과 편향 재검토
.\.venv\Scripts\python.exe -X utf8 -B -m synth.shortcut --synth-dir .local/synth/demo4 --real-judge .local/demo/pleos/judge.json --real-asr .local/demo/asr/pleos.jsonl

# 6. 먼저 가벼운 두 기준선으로 시험 학습
.\.venv\Scripts\python.exe -X utf8 -B -m demo.trial.run --models ngram shape --synth-dir .local/synth/demo4 --seeds 43 44 45 --split-seed 43 --run-name t47-demo4-baselines
```

세 encoder도 비교할 때는 T46의 로컬 HF 캐시를 준비한 뒤 다음 명령을 실행한다. 다운로드 명령은 포함하지 않는다. trial의 기존 출력 이름은 덮어쓰지 않으므로 반복 실행 시 새 run-name을 쓴다. 서로 독립적인 demo2/3/4는 같은 카드 번호에 다른 정답이 붙을 수 있어 이 명령에서는 demo4만 사용한다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m demo.trial.run --models monologg/koelectra-small-v3-discriminator klue/roberta-small alphaedge-ai/mmBERT-small-kor-32768 --synth-dir .local/synth/demo4 --cache .local/hf --device cuda --epochs 3 --learning-rate 3e-5 --batch-size 8 --max-length 256 --truncation both --seeds 43 44 45 --split-seed 43 --run-name t47-demo4-encoders
```

demo2 FAIL 재현 명령은 다음과 같다. 기대 종료 코드는 3이다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m synth.shortcut --synth-dir .local/synth/demo2 --real-judge .local/demo/pleos/judge.json --real-asr .local/demo/asr/pleos.jsonl
```

## 테스트와 한계

```powershell
$t47Tests = Get-ChildItem tests/test_synth*.py, tests/test_demo*.py, tests/test_trial.py, tests/test_asr*.py, tests/test_skim*.py, tests/test_schema*.py | ForEach-Object { $_.FullName }
.\.venv\Scripts\python.exe -X utf8 -B -m pytest @t47Tests --basetemp .local/pytest-t47 -p no:cacheprovider -q --tb=short
```

새 테스트는 창작 입력으로 길이·구성 비율, 동적 예시 로딩과 길이 선택, 압축 시각 보정·끄기·겹침·비정상 시각, 통화상대 화자, 카드 PASS·묶음 FAIL, 장면별 분할 누수 방지, 실제 사람 라벨·시간 계산, 오류 원문 비출력을 확인한다. `tests/test_label.py`의 기존 무관한 실패 9개는 이 실행에 포함하지 않는다.

위 명령의 최종 결과는 **944 passed, 2 skipped, 14 warnings, 60.19초**다. 경고는 설치된 torch의 `torch.jit.script_method` deprecation이다. `git diff --check`도 통과했다.

카드 PASS는 생성 결과의 총 글자·줄 길이 분포를 보장하지 않는다. agy가 none 대사를 짧게 쓰거나 열화가 라벨마다 다르게 분절하면 묶음 검사에서 다시 FAIL할 수 있다. 이번에는 네트워크·agy를 호출하지 않아 demo4 생성·열화·실제 성능 개선을 아직 확인하지 않았다. 실제 20묶음은 작은 비교 표본이며 독립 평가 자료를 추가해야 한다.

시간 보정은 실제 말 속도나 무음의 정답을 복원하지 못하고 dur도 함께 늘린다. 생성 단계에서 자연스러운 침묵을 우선해야 한다. 가족 수·아이 유무·energy의 주변 분포를 맞춰도 세부 화자 구성과 패턴의 모든 결합이 독립이라는 보장은 없다. 현재 검사는 선형 모양 분류기 하나이므로 비선형 지름길이나 어휘·내용 편향을 놓칠 수 있다. PASS는 이 검사에서 큰 모양 지름길을 발견하지 못했다는 뜻이다.
