# T53 합성 대량 생성과 다양화

2026-11-12. 카드 3,000장 생성과 카드 단계 지름길 검사를 실제로 실행해 통과했다. agy·네트워크·실제 대화 생성은 실행하지 않았다. 테스트 대사는 모두 창작 입력이다. 커밋하지 않았으며 T52 영역인 `eval/reference*`, `prompts/judge.md`, `prompts/labeler.md`는 수정하지 않았다.

## 만든 것과 변경 파일

경로는 별도 표기가 없으면 `talk-rec/model-create/` 기준이다.

| 파일 | 변경 |
|---|---|
| `synth/patterns.py` | 일반 소재 60개 × 관점 6개 = 공유 화제 360개, 화제 그룹 6개, 이동 상황 24개, 말투 12개. 부정 패턴 5종과 따뜻한 회상 추가 |
| `synth/cards.py` | 기존 라벨·길이·구성 할당 유지, 대립쌍 배정, `--pair-rate` 기본 0.3, `--childless-rate` 기본 0.15, 아기 연령·연령 가중치, `topic_group/style/negative/pair_id` |
| `synth/generate.py` | 정규화 중복 검사와 역색인, `--duplicate-threshold`, 재개 서명에 문턱 보존, 고정 실패 사유 집계표, 부정 예시 우선 선택 |
| `synth/degrade.py` | `--level-mix`, 라벨별 수준 비율 할당, 부정·쌍 메타데이터 전달과 수치 집계, 아기 오류 가중치 |
| `synth/shortcut.py` | 기존 모양 FAIL 유지, 합성 내부 글자 n-gram 정확도·라벨별 상위 n-gram·단독 출현 경고, 화제 분포와 `--topic-report` |
| `synth/curate.py` (신규) | `dedup` 중복 제거, `sample` 라벨·패턴·부정·쌍 층화 훑어보기 export |
| `synth/report.py` | 검토 결과의 부정·대립쌍별 실제 같음 집계 |
| `prompts/synth-scene.md` | 루브릭 v3의 가족·부정 우선 판정, 최소 대립쌍, 아기 화자 지시 |
| `prompts/synth-examples/negative.json` (신규) | 혼내기·떼쓰기·걱정스러운 소식의 짧은 창작 본보기 3개. Claude 검토·수정 가능 |
| `prompts/synth-examples/agy3.json` | 기존 외부 직장 상사와의 통화를 가족인 형과 서류·방문 용무를 확인하는 본보기로 수정 |
| `demo/trial/data.py` (허용 예외) | `Example`에 선택적 `pair_id`, `load_synth`에서 전달, `split_scenes`에서 카드와 쌍 연결을 합쳐 분할 |
| `tests/test_synth_cards.py` | 아이 없는 기본 비율 15%와 늘어난 패턴의 작은 표본 조건 반영 |
| `tests/test_synth_pleos.py` | 본보기 31개 반영 |
| `tests/test_synth_shortcut.py` | 합성 n-gram 표시 허용과 실제 역할 입력의 비출력 검사 구분 |
| `tests/test_synth_scale.py` (신규) | 3,000장, 공유 화제, 대립쌍, 분할 누수, 부정 표시, 구성 옵션, n-gram, 정규화 중복 제거, 표본, 수준 혼합, 대량 재개 검사 |
| `talk-rec/docs/model-create/T53-synth-scale.md` (신규) | 이 기록 |

모든 장면의 탑승자는 가족이다. 통화상대 예외도 가족으로 지정한다. 친구·동료끼리인 장면을 없음의 학습 자료로 만들지 않는다. 기존 패턴의 `group` 이름은 호환용 화제 목록 별칭이며 모두 같은 360개 목록을 가리킨다. 카드의 실제 소재 묶음은 라벨과 공유하는 `topic_group`이다.

최소 대립쌍 비율은 **전체 카드 중 쌍에 속한 카드 비율**이다. 기본 3,000장의 30%는 900장, 즉 450쌍이다. 라벨 할당을 먼저 정하고 길이 구간·아이 유무·가족 수·energy가 같은 슬롯을 짝짓는다. 한 쌍의 가족·화제·상황·목표 초·목표 줄 수·말투·끼어드는 요소를 같게 한다. 내용의 받아 주는 흐름이나 현재 분위기로 라벨을 달리한다. 작은 N·극단적인 라벨 비율에서는 가능한 쌍 수가 요청 비율보다 작을 수 있어 실제 쌍 수를 검사 출력에서 확인한다.

부정 패턴은 `argument`, `scolding`, `tantrum`, `complaint`, `worrying_news`다. 모두 여러 차례 응답하는 `none`, `negative: true`이며, 밝은 회상·따뜻한 이야기·장난스러운 놀림과 짝지을 수 있다. 소재가 속상한 일인 것과 현재 차 안 분위기가 부정적인 것을 구분한다. 아기는 옹알이·낱말 수준으로 쓰고 이야기의 연결은 다른 가족이 맡는다. 아이 연령 추첨 가중치는 아기/유아/초등 저학년/초등 고학년/중고생 = 3/4/5/3/1이다.

## 실제 카드 실행 결과

작업 폴더는 `C:\github\drive-diary\talk-rec\model-create`이고 다음 두 명령을 실행했다. 마지막 검사 종료 코드는 **0**이다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m synth.cards --n 3000 --seed 53 --pair-rate 0.3 --childless-rate 0.15 --out .local/synth/t53/cards.jsonl
.\.venv\Scripts\python.exe -X utf8 -B -m synth.shortcut --cards .local/synth/t53/cards.jsonl --topic-report .local/synth/t53/topic-counts.json
```

| 항목 | 결과 |
|---|---:|
| 카드 수 | 3,000 |
| story / moment / none | 1,200 / 600 / 1,200 |
| 같은 패턴 × 화제의 최대 반복 | 2 |
| 경계 카드 | 450 |
| 대립쌍 / 쌍 소속 카드 | 450 / 900 |
| 부정 카드 | 484 (none의 40.33%) |
| 밝은 판과 짝지어진 부정 카드 | 254 (부정 5종 모두 포함) |
| 아이 없는 카드 | 450 (15%); none/story/moment = 180/180/90 |
| 세 라벨 모두에서 등장한 화제 | 360 / 360 |
| 모양 검사 각 반복 정확도 | 60.00 / 60.00 / 60.00 / 60.00 / 60.00% |
| 모양 검사 가중 정확도 / 다수 클래스 | 60.00% / 60.00% |
| FAIL 문턱 | 70.00% 초과 |
| 검사 결과 / 검증 관측 합계 | PASS / 3,000 |

고정 분할 시드는 43/44/45/46/47이다. 쌍과 같은 카드의 여러 export/열화 수준이 같은 분할에 들어간다. 5회 반복 80/20 홀드아웃으로, 서로 겹치지 않는 5-fold가 아니다. 반복 사이 같은 관측이 다시 평가될 수 있다.

| 화제 묶음 | none | story | moment |
|---|---:|---:|---:|
| 관계 | 194 | 193 | 99 |
| 나들이 | 200 | 203 | 98 |
| 돌봄 | 206 | 200 | 99 |
| 배움 | 199 | 203 | 99 |
| 생활 | 193 | 193 | 102 |
| 이동 | 208 | 208 | 103 |

화제별 360행의 라벨 건수는 `.local/synth/t53/topic-counts.json`에 저장했다. 화제·묶음은 라벨별로 별도 목록을 쓰지 않는다. 정확히 같은 건수를 강제하지는 않으므로 작은 표본의 우연한 분포 차이는 남는다.

부정 패턴 건수는 다툼 97, 혼내기 97, 떼쓰기·울음 96, 험담·불평 97, 걱정스러운 소식 97이다. 카드 안의 아이 등장 횟수는 아기 599, 유아 916, 초등 저학년 1,261, 초등 고학년 670, 중고생 204다. 가족을 공유하는 쌍의 중복 등장도 포함한 수치다. 추가로 seed 43/44/91에서도 각 3,000장, 쌍 소속 900장, 패턴×화제 최대 반복 2를 확인했다.

## 생성·검사·검토 실행 순서

아래 생성부터는 agy가 있는 환경에서 실행한다. 새 프롬프트와 예시는 기존 실행의 재개 서명과 다르므로 새 `t53` 경로를 쓴다. 기존 장면 파일을 새 설정으로 덮어 이어 쓰지 않는다.

```powershell
# 1. 위 카드 생성과 카드 검사를 먼저 실행한다.
# 2. 실제 생성: 이 작업에서는 실행하지 않았다. 같은 명령으로 재개한다.
.\.venv\Scripts\python.exe -X utf8 -B -m synth.generate --cards .local/synth/t53/cards.jsonl --backend agy --model gemini-3.8-flash-medium --out .local/synth/t53/scenes.jsonl --concurrency 4 --seed 53 --duplicate-threshold 0.85 --wait-on-limit 10

# 3. 수준 0/1/2를 20/50/30% 비율로 섞어 내보낸다.
.\.venv\Scripts\python.exe -X utf8 -B -m synth.degrade --cards .local/synth/t53/cards.jsonl --scenes .local/synth/t53/scenes.jsonl --out-dir .local/synth/t53 --level mixed --level-mix 0.2,0.5,0.3 --seed 53 --duplicate-threshold 0.85

# 4. 생성된 글의 모양 관문과 내용 참고 진단을 함께 본다.
.\.venv\Scripts\python.exe -X utf8 -B -m synth.shortcut --synth-dir .local/synth/t53 --top-ngrams 12 --exclusive-min-support 5 --exclusive-rate 0.02

# 5. 쌍을 통째로 포함하는 검토용 표본을 별도 디렉터리로 만든다.
.\.venv\Scripts\python.exe -X utf8 -B -m synth.curate sample --synth-dir .local/synth/t53 --out-dir .local/synth/t53-sample --limit 60 --seed 53
.\.venv\Scripts\python.exe -X utf8 -B -m tools.skim.serve --data-dir .local/synth/t53-sample --name t53-review --limit 1000
```

`tools/skim`은 표본 디렉터리의 `blocks.jsonl`, `clean.jsonl`, `labels.jsonl`, 완료된 `meta.json`을 그대로 읽는다. `--limit 1000`으로 표본 전체를 연다. 표본 추출은 라벨별 건수를 우선 균형화하고 패턴·부정·쌍 유무의 덜 뽑힌 층을 우선한다. 선택한 쌍과 같은 장면의 여러 수준은 모두 포함하므로 요청 limit을 마지막 그룹 크기만큼 넘길 수 있다. 쌍이 2장뿐인 경우 60 또는 61장이 된다. 표본은 `review_only`이며 학습 입력에는 전체 원본 export 디렉터리를 지정한다.

기존 skim UI 내부의 표본 함수는 수정하지 않았다. 그 함수는 라벨·패턴만 층화한다. T53의 부정·쌍 층화를 적용하려면 위 `synth.curate sample`을 먼저 실행해야 한다. 사람 검토 후 `synth.report`의 부정·대립쌍별 표를 확인한다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m synth.report --skim .local/synth/t53-sample/skim/t53-review.jsonl --labels .local/synth/t53-sample/labels.jsonl --blocks .local/synth/t53-sample/blocks.jsonl --out .local/synth/t53-sample/report.md
```

생성은 통과 장면을 보존하고 실패·미완료 카드만 다시 요청한다. 출력 순서는 카드 순서다. 종료 코드는 성공 0, 입력·재개 오류 2, 사용량 제한 3, 일부 카드 실패 4다. `.meta.json`의 `failed_cards`, `failure_counts`, `failure_reasons`와 마지막 실패 사유 표를 확인한다. 사유 카운터는 **이번 실행** 기준이고, 같은 카드의 반복 실패가 여러 번 집계될 수 있다. 예외 원문이나 응답 본문은 사유 표에 넣지 않는다.

3,000장 재개 검사는 창작 장면 2,996장이 있는 서명된 체크포인트에서 남은 4장만 가짜 백엔드 1회로 채웠다. 같은 명령을 다시 실행했을 때 추가 호출은 0회였다. 이는 재개·검증의 규모 검사이며 실제 모델의 자연스러움이나 agy 처리 속도 측정이 아니다.

중복은 공통 `normalize`(NFKC·소문자·문자/숫자 이외 공백화)를 거친 글에서 공백을 없애 문자 4-gram 집합의 Jaccard 유사도로 판정한다. 기본 0.85 **이상**을 거부한다. 역색인과 드문 n-gram 접두 집합으로 후보를 줄이며 근사 검색은 쓰지 않는다. 창작 무작위 입력으로 전수 비교와 동일한 결과를 확인했다. 생성 시 예시·기존 출력·동일 응답·동시 호출 사이 중복을 확인하고, 문턱 변경은 재개 시 거부한다. 열화 시에도 같은 문턱을 지정한다.

이미 있는 합성 장면을 별도로 정리하려면 다음을 쓴다. 먼저 나온 장면을 남기고 중복 장면을 제외하며, 원본 생성 파일은 보존한다. 제외 카드 번호·건수는 `.dedup.json`에 남는다. 중복으로 한쪽이 빠진 쌍을 억지로 다시 채우지 않으므로 검토 시 불완전 쌍 여부를 살핀다. `scenes-unique.jsonl`은 생성 재개 출력으로 쓰지 않는다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m synth.curate dedup --scenes .local/synth/t53/scenes.jsonl --out .local/synth/t53/scenes-unique.jsonl --threshold 0.85
```

`--level-mix`는 0/1/2의 음수가 아닌 유한 가중치 세 개다. 합계는 양수여야 하며 합계 1로 정규화한다. 라벨별 최대 잔여법으로 건수를 정한 뒤 시드로 섞으므로 3,000장에서는 600/1,500/900장이다. 한 장면당 수준 하나를 배정한다. 전체 카드 목록으로 배정해 부분 export나 장면 입력 순서가 달라도 같은 카드의 수준은 바뀌지 않는다. 다른 디렉터리로 수준 0·1·2를 각각 내보내도 카드 번호와 `pair_id` 연결을 함께 합쳐 train/val 누수를 막는다. 기존 `--level mixed`만 쓰는 순환 0/1/2 동작도 유지한다.

## n-gram 수치와 경고 해석

합성 글에만 공통 정규화 후 문자 2~5-gram TF-IDF(최대 50,000개)와 로지스틱 회귀를 fit한다. 이진 keep/none 정확도는 모양 관문과 **같은 쌍 단위 분할·같은 5개 시드**로 측정한다. 어휘 추출·회귀 모두 각 train에만 fit한다. 정확도는 내용 학습의 결과이므로 FAIL 조건에 넣지 않는다. 기존 모양 관문은 다수 클래스 비율 +10%p **초과**일 때 FAIL/종료 코드 3인 규칙을 유지한다.

상위 목록은 전체 합성 입력으로 별도 fit한 라벨별 one-vs-rest 회귀의 계수 상위 12개다. n-gram, 계수, none/story/moment별 **고유 원본 장면 출현 수**를 표시한다. 한 장면의 여러 수준·반복 export는 출현 수를 늘리지 않는다. 전체 fit 목록은 원인 점검용이며 검증 정확도 계산에 재사용하지 않는다. 실제 자료는 기존처럼 숫자 분포표에만 쓰고 그 글에서 어휘를 추출하거나 출력하지 않는다.

상위 n-gram이 다른 두 라벨에는 한 장면도 없고, 해당 라벨에서 `max(5장, 해당 라벨 고유 장면 수의 2% 올림)` 이상 등장하면 `WARN exclusive`를 표시한다. 5장은 우연한 한두 장면의 경고를 줄이고, 2%는 규모가 커질 때 희귀 고유명·개별 소재보다 반복되는 작성 습관을 우선 보려는 운영 기준이다. 통계적 유의성이나 누설 확정 기준은 아니다. 실제로 중요한 내용 단어도 경고될 수 있어 사람이 같은 소재의 반대 라벨 장면과 본보기를 검토한다. 문턱은 CLI로 바꿀 수 있다.

창작 입력에 일부러 라벨별 다른 표현을 넣은 테스트에서 내부 정확도 1.0과 단독 출현 경고를 확인했다. 실제 합성 본문 3,000장은 만들지 않았으므로 새 대규모 자료의 n-gram 정확도·상위 표현·생성 후 모양 PASS는 아직 없다.

## 예상 소요 시간

[T45](T45-pleos-style-synth.md)와 [T47](T47-shortcut-fix.md)를 확인했으나 두 기록에는 **장면당 agy 생성 처리 시간 실측이 없다**. 장면의 15~90초 목표 길이와 pytest 60.19초는 생성 처리 시간으로 사용할 수 없다. 따라서 해당 기록의 장면당 실측으로 계산했다는 주장은 하지 않는다.

운영 계획용 민감도 추정은 다음과 같다. 장면당 처리 시간에 호출 시작·응답·검증 비용이 포함된다고 가정하고 `3,000 × 장면당 초 / 동시성`으로 계산했다. T45·T47과 같은 최대 4장 배치·동시성 4를 사용하는 실행을 가정하며, Windows 명령 길이로 배치가 줄거나 사용량 제한·재요청이 있으면 길어진다.

| 가정한 장면당 처리 시간 (실측 아님) | 직렬 | 동시성 4의 이상적 시간 |
|---|---:|---:|
| 10초 | 8.33시간 | 2.08시간 |
| 20초 | 16.67시간 | 4.17시간 |
| 30초 | 25.00시간 | 6.25시간 |

우선 20~40장을 생성해 실제 벽시계 시간과 완료 장수로 장면당 시간을 구한 뒤 남은 장수에 적용한다. 위 범위는 처리량을 보장하지 않으며 실패 재요청·사용량 대기·사람 검토 시간을 포함하지 않는다.

## 검증과 남은 제약

최종 합성 전용 검사: **129 passed, 45.65초**. 다음 명령으로 실행했다.

```powershell
$t53Tests = Get-ChildItem tests/test_synth*.py | ForEach-Object { $_.FullName }
.\.venv\Scripts\python.exe -X utf8 -B -m pytest @t53Tests --basetemp .local/pytest-t53 -p no:cacheprovider -q --tb=short
```

trial·skim·공통 스키마·Pleos 프로필까지 확장한 검사에서는 처음 **764 passed, 1 skipped, 3 failed**를 확인했다. 이 중 두 인코더 검사는 `.venv/Lib/site-packages/PIL/__init__.py` 읽기 권한 오류로 transformers import가 실패했다. 환경 파일이나 테스트를 수정하지 않았다. 나머지 `tests/test_skim_http.py::test_sample_balanced_seeded`는 40장 fixture에서 라벨별 30장 표본에 모든 패턴이 있어야 한다는 기존 가정이다. none에 새 패턴을 더한 뒤 서로 다른 none 패턴 수가 그 표본의 none 11장보다 많아 조건을 만족할 수 없다. 허용 영역 밖인 해당 테스트와 기존 UI 표본 함수는 수정하지 않았다. 새 표본 명령은 별도로 부정·쌍·다수 패턴 포함 및 SkimStore 호환성을 검증했다.

위 세 검사를 명시적으로 제외한 확장 회귀 결과는 **764 passed, 1 skipped, 3 deselected, 70.49초**다. 건너뜀 1건은 Windows 심볼릭 링크 생성 권한 때문이다.

```powershell
$t53Regression = Get-ChildItem tests/test_synth*.py, tests/test_trial.py, tests/test_skim*.py, tests/test_schema*.py, tests/test_demo_pleos_profile.py | ForEach-Object { $_.FullName }
.\.venv\Scripts\python.exe -X utf8 -B -m pytest @t53Regression --basetemp .local/pytest-t53 -p no:cacheprovider -q --tb=short -k 'not test_encoder_random_tiny_one_step and not test_generic_fast_v5_tokenizer_and_modernbert_cpu_step and not test_sample_balanced_seeded'
```

변경 파일의 `git diff --check`는 통과했다. 저장소 전체 테스트가 모두 통과했다는 뜻은 아니다. 실제 전사 글은 출력·문서·로그로 가져오지 않았다.

카드 PASS는 생성된 본문의 자연스러움·라벨 의미·아기 말투·음성 분위기·열화 후 모양을 보증하지 않는다. 특히 부정/밝은 판의 차이가 글만으로 어려운 경우가 있어 사람 검토가 필요하다. 대립쌍은 독립 호출에 나뉠 수 있어 같은 문장을 편집한 엄밀한 최소쌍을 보장하지 않으며 카드의 공통 조건을 유지하도록 지시한다. 유사도 검사는 의미가 같은 바꿔쓰기를 모두 찾지 못한다. 기존 pair_id 없는 export에서 서로 다른 카드의 쌍을 자동 복원하지는 않는다. 카드 번호가 같은 여러 디렉터리는 같은 원본 장면이라고 간주하므로 서로 다른 생성 실행을 임의로 섞지 않는다.
