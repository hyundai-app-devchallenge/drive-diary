# T43 — 차 안 가족 대화 합성 생성기와 훑어보기

작성일: 2026-11-11. 작업 폴더는 `C:\github\drive-diary\talk-rec\model-create`.
커밋하지 않았다. 실제 영상 전사 문장은 새 프롬프트·예시·테스트에 넣지 않았다.
`pipeline/`, `common/`, `demo/`, `tools/listen/`, `tools/review/` 및 기존 라벨 프롬프트는 수정하지 않았다.

이 작업은 **사람이 자연스러움을 먼저 확인하는 데모**다. 유튜브 등 실제 묶음은 흐름 참고·평가·분포 비교에만 사용하며, 합성 데이터와 학습 입력으로 합치지 않는다.
모든 내보낸 합성 묶음과 메타데이터에 `demo_only: true`를 넣었다. 현재 실제 생성과 사람 검토는 아직 하지 않았다.

## 만든 것

| 파일 | 역할 |
|---|---|
| `synth/patterns.py` | 그대로 유지한 라벨 정의, 패턴 20개, 화제 84개, 이동 상황 14개 |
| `synth/cards.py` | 고정 시드·정수 할당·조합 반복 제한 카드 표본기 |
| `synth/generate.py` | 4장 단위 생성, 형식 검증, 중복 탐지, 재요청·재개·동시 호출 |
| `synth/degrade.py` | 순수 함수 전사 열화·턴 재구성·기존 묶음 스키마 내보내기 |
| `synth/io.py` | UTF-8 JSONL, 원자적 파일 교체, 데모 밖의 OS 잠금 |
| `synth/report.py` | 자연스러움 비율, 일치율·혼동 행렬, 어색함 코멘트, 실제 분포 비교 |
| `prompts/synth-scene.md` | 한국어 생성 지시, 라벨·필드·패턴 설명, 새로 지은 말투 예시 3개 |
| `tools/skim/serve.py`, `index.html`, `app.js`, `style.css` | 외부 자원이 없는 한국어 로컬 화면 |
| `tests/test_synth_cards.py`, `test_synth_generate.py`, `test_synth_degrade.py`, `test_synth_report.py` | 표본·프롬프트·검증·재요청·재개·열화·스키마·보고서 검사 |
| `tests/test_skim_http.py`, `test_skim_browserlogic.py` | 가림·저장·보안 경로·HTTP와 실제 JS 상호작용 검사 |

각 새 패키지에는 `__init__.py`도 추가했다. 의존성·락 파일은 변경하지 않았다.

## 사용자가 밖에서 실행할 순서

아래 명령은 PowerShell에서 순서대로 실행한다. 실제 `agy` 호출은 이 문서 작성 중 실행하지 않았다.
모델 식별자를 인자로 전달하고, 기존 `pipeline.label.AgyBackend`가 호출 형식을 처리한다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create

# 1. 라벨을 먼저 정한 카드 200장
.\.venv\Scripts\python.exe -X utf8 -B -m synth.cards --n 200 --seed 43 --ratios 0.4,0.2,0.4 --boundary-rate 0.15 --max-repeat 2 --out .local/synth/demo/cards.jsonl

# 2. 카드 4장마다 실제 LLM 생성 (처음에는 동시 호출 1 권장)
.\.venv\Scripts\python.exe -X utf8 -B -m synth.generate --cards .local/synth/demo/cards.jsonl --backend agy --model gemini-3.8-flash-medium --out .local/synth/demo/scenes.jsonl --concurrency 1 --wait-on-limit 10

# 3. 깨끗한 원본을 보존하고 열화 강도 0/1/2를 섞어 내보내기
.\.venv\Scripts\python.exe -X utf8 -B -m synth.degrade --cards .local/synth/demo/cards.jsonl --scenes .local/synth/demo/scenes.jsonl --out-dir .local/synth/demo --level mixed --seed 43

# 4. 사람 검토 (이름을 본인 이름으로 바꿀 것)
.\.venv\Scripts\python.exe -X utf8 -B -m tools.skim.serve --name 검토자 --limit 60 --seed 43 --port 8769
```

브라우저에서 `http://127.0.0.1:8769`를 연다. 검토를 마치면 서버를 실행한 창에서 Ctrl+C로 종료한다.
그 다음 같은 PowerShell에서 보고서를 만든다.

```powershell
# 5. 자연스러움·일치율·실제 분포 비교 보고서
.\.venv\Scripts\python.exe -X utf8 -B -m synth.report --skim .local/synth/demo/skim/검토자.jsonl --out .local/synth/demo/report.md
```

출력은 `cards.jsonl`, `scenes.jsonl`, `scenes.meta.json`, `clean.jsonl`, `blocks.jsonl`,
`labels.jsonl`, `meta.json`, `skim/<이름>.jsonl`, `report.md`다.
`clean.jsonl`에는 화자·겹침·시각을 포함한 원본, 카드 전체, why를 보존한다.
`labels.jsonl`은 정확히 `id, verdict, pattern, boundary, level` 필드다.

### 중단·제한·설정 변경

- 생성 검증이 실패하면 **그 4장 호출만 최대 2회 다시 요청**한다(최초 포함 3회). 카드가 4의 배수가 아니면 마지막만 1~3장이다.
- 이미 저장한 4장 호출은 다시 부르지 않는다. 중단·실패 후 **동일 명령**을 실행하면 재개한다. 출력은 완성된 호출 단위로 원자적으로 교체한다.
- 사용량 제한 때 `--wait-on-limit 10`은 10분 후 같은 호출을 다시 시도한다. 제한 재시도에는 횟수 상한이 없으며 Ctrl+C로 중단할 수 있다. 대기는 내부적으로 60초 이하로 나눈다.
- 기본 `--wait-on-limit 0`이면 제한 시 종료 코드 3, 검증·백엔드·입출력 오류는 2, Ctrl+C는 130이다. 오류 로그에는 생성 전사를 출력하지 않는다.
- `--concurrency 2` 등으로 올릴 수 있다. 작은 묶음의 병렬 호출을 카드 순서로 저장한다. 중단 시 완료됐어도 저장 전인 호출은 재개 시 다시 호출될 수 있다. 이미 실행 중인 호출은 백엔드 제한 시간(기본 600초)까지 기다릴 수 있다.
- 카드 내용·프롬프트·모델·백엔드·command·agy 경로가 바뀌면 같은 출력으로 재개하지 못한다. 새 출력 파일/별도 데이터 폴더를 사용한다. 동시 호출 수와 대기 시간은 바꿔도 된다.
- 자동 탐색이 안 되면 생성 명령에 `--agy-path '실제 agy.exe 경로'`를 추가한다.
- `--backend command --command '<실행 파일과 인자>'`도 지원한다. 기존 `CommandBackend`를 그대로 사용하므로 stdin으로 프롬프트를 받고 stdout으로 JSON 배열을 내보내는 명령이어야 한다. `{model}`, `{effort}` 치환을 지원한다.
- `FakeBackend`는 합성용 하위 클래스로 재사용했다. 가짜 데이터는 형식 검사 전용이며 자연스러움 평가에 사용할 수 없다. CLI는 가짜 장면을 `demo` 경로에 쓰거나 내보내는 것을 차단한다.
- 생성과 열화가 끝난 뒤 화면/보고서를 실행한다. 열화의 여러 출력 파일 전체가 하나의 트랜잭션은 아니다. `meta.json.complete`가 false인 데이터는 화면이 거부한다.
- OS 잠금은 `.local/syntht/locks/`, 원자적 교체용 임시 파일은 `.local/syntht/tmp/`에만 만든다. 임시 파일은 정리한다. **demo 아래에는 잠금·임시 디렉터리를 만들지 않는다.** 프로세스가 죽으면 OS 잠금은 해제된다. 잠금 파일 자체는 데모 밖에 남을 수 있다.

## 카드·패턴·화제

라벨 정의는 다음 그대로다.

- `story`: 누군가 화제를 꺼내고 다른 사람이 받아서 두세 번 이상 오간 흐름. 완결될 필요 없음.
- `moment`: 이어지지 않아도 남길 만한 한마디·웃음·함께 부르는 노래·감탄·장난.
- `none`: 그 외.

기본 200장/시드 43 결과: story 80, moment 40, none 80, 경계 30장.
원본 줄 수 분포는 아래와 같고 중앙값 9, 한 줄짜리 14장(7%)이다.

| 원본 줄 수 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 카드 수 | 14 | 5 | 5 | 6 | 8 | 12 | 16 | 20 | 44 | 30 | 20 | 20 |

표본기는 작은 그룹의 할당에는 최대 나머지 방식으로 반올림한다. 기본 설정에서 20개 패턴·84개 화제가 모두 등장한다.
라벨별 적게 뽑힌 패턴, 적게 쓴 pattern×topic 및 화제를 우선한다. 조합 반복 상한 기본 2(1로 낮출 수 있음).
많은 N/극단적인 비율로 조합 용량을 넘기면 반복 제한을 무시하지 않고 오류를 낸다.
라벨 비율을 극단적으로 바꿔 짧은 줄 수 할당이 패턴과 충돌하면 패턴에 맞는 길이를 우선하므로 7%·중앙값 목표가 달라질 수 있다.

story는 최소 6줄, none 뒤 이야기 시작 및 긴 운전 지시 경계는 최소 8줄,
조각 패턴은 최대 2줄, 영상 인사는 최대 4줄, 답 없는 평범한 질문은 최대 5줄로 배정한다.
길이는 15~90초이며 원본 줄 수가 적으면 `12 + 10 × n_turns`초로 상한을 낮춘다.
에너지는 차분/보통/정신없음, 끼어듦은 운전 지시/내비 음성/용무/아이 딴소리/침묵 중 0~2개다.

| 라벨 | 패턴 ID | 한 줄 방향 |
|---|---|---|
| story | parent_interview | 부모 질문과 아이의 긴 두서없는 답·말 고치기 |
| story | kid_recount | 아이 경험담에 가족이 반응하거나 형제가 정정 |
| story | shared_memory | 함께 겪은 기억을 서로 보태기 |
| story | choice_game | 둘 중 하나 선택하고 이유 주고받기 |
| story | interrupted_story | 용무·운전 주의가 끼고 이어지거나 끊기기 |
| story | grandparent_recall | 조부모 옛일과 아이의 다른 경험 |
| story | after_none | 용무·내비 구간 뒤 짧은 이야기 시작(경계) |
| moment | adult_imitation | 어른·설교·영어 말투 흉내와 짧은 웃음 |
| moment | tease_reply | 놀림과 한 번의 엉뚱한 받아치기 |
| moment | chant | 구호 같은 짧은 말 반복 |
| moment | song | 기존 가사 없는 즉석 노래 |
| moment | novice_reaction | 초보 운전에 대한 인상적인 감상 |
| moment | unanswered_moment | 답은 없어도 질문·옆말 자체가 남는 순간(경계) |
| none | driving_only | 방향·속도·주차 지시만 긴 주고받기(경계에도 사용) |
| none | navigation | 안내 음성과 짧은 확인 |
| none | errand | 물건 찾기·닦기·건네기 |
| none | fragments | 맥락 없는 조각과 침묵 |
| none | greeting | 영상 시작·끝 인사와 준비 말 |
| none | short_answers | 서로 무관한 단답 나열 |
| none | unanswered | 질문이 답 없이 용무로 끊기기(경계) |

화제 84개는 패턴 그룹에 맞춰 선택한다. 전체 목록은 `synth/patterns.py`의 `TOPICS`가 기준이다.

| 그룹 | 개수 | 목록 요약 |
|---|---:|---|
| life | 20 | 학교 모둠·자리, 친구, 급식, 학원 숙제, 게임 길 찾기, 만화 동물, 반려동물, 간식, 장래 희망, 형제 다툼, 준비물, 체육, 발표, 종이접기, 도서관, 낮잠, 운동화, 지우개, 별명, 자전거 |
| memory | 12 | 여행 비, 모래성, 산책 강아지, 도시락, 휴게소 국물, 조부모 통학·난방, 주먹밥, 길 잃은 나들이, 시장, 사촌 놀이, 별 |
| choice | 10 | 버스/택시, 피자/햄버거, 전기차/휘발유차, 바다/산, 비/눈, 날기/투명, 강아지/고양이, 아침밥/늦잠, 기차/배, 그림/만들기 |
| play | 12 | 전화·잔소리 흉내, 엉터리 영어, 출발 구호, 졸음 놀림, 모자 별명, 배고픔 선언, 구름, 양말, 우산, 아는 척, 칭찬 |
| song | 4 | 나무·빗방울·졸음·간식 즉석 노래 |
| view | 4 | 느린 주차, 출발 긴장, 커브, 브레이크 |
| driving | 10 | 우회전, 깜빡이, 직진 반복, 후진, 차선, 감속, 차간 거리, 내비 거리, 단속, 출구 |
| errand | 12 | 휴지, 물병, 창문, 쓰레기, 안전띠, 가방, 신발, 충전선, 영상 시작·끝 인사, 온도, 문 |

가족은 2~5명이며 운전자 1명과 동승자다. 아빠/엄마/할아버지/할머니/이모/삼촌 및
아이1~3 조합을 사용한다. 어른은 청년·중년·노년, 아이는 유아 3~5세/초등 저학년/초등 고학년/중고생이다.
각 카드에는 최소 한 명의 아이가 있고 조부모 회상 패턴에는 조부모가 포함된다.
이동 상황은 등원·등교, 하원·하교, 주말 나들이, 귀가, 장거리 고속도로, 마트, 병원, 운전 연습,
명절, 비 오는 날 정체, 출근 동승, 야간, 주차장 진입, 조부모 댁 방문의 14개다.

## 생성 검증과 한계

장면별 `card, lines, why`, 발화별 `t, dur, spk, text, overlap`만 허용한다.
카드 번호·순서, family(+내비) 화자, 유한한 시각·양수 길이, t 단조 증가와 시작 0,
목표 줄 수·마지막 종료 시각 ±30%, 빈 대사·why, 작은 실존 인물·상표 금칙어 목록을 검사한다.
장면 내 전체 텍스트의 공백을 제거한 문자 4-gram 집합으로 Jaccard 유사도를 계산하고
0.85 이상이면 같은 호출 또는 이미 저장된 장면과 중복으로 본다. 병렬 호출 사이도 저장 전에 다시 검사한다.
순서대로 저장하는 시점에만 앞선 출력이 확정되므로 응답 도착 순서가 결과 순서를 바꾸지 않는다.

금칙어는 포괄적인 실존명 탐지기가 아니며 일부 일반 문자열의 부분 일치로도 거절될 수 있다.
라벨의 의미·나이에 맞는 말투·자연스러움·정치/종교 부재를 코드만으로 보증하지 못한다.
이것이 사람이 먼저 원본을 가리고 읽어야 하는 이유다. 가짜 생성기는 임의 단어를 배열하는 형식용이라 자연스러움의 증거가 아니다.

## 전사 열화 규칙과 확률

`degrade(lines, level, seed, enabled, probabilities, names)`는 입력을 수정하지 않는 순수 함수다.
`enabled={규칙: False}`로 각 규칙을 끌 수 있다. CLI에서는 `--disable 규칙`을 반복한다.
예: `--disable hallucinate --disable nav_mix`. 확률표를 바꾸려면 함수의 `probabilities`에
`{규칙: (level0, level1, level2)}`를 전달한다. 재구성 merge/split은 확률 추첨 없이 on/off로 적용한다.

| 규칙 ID | 적용 단위 | 깨끗 0 | 보통 1 | 심함 2 |
|---|---|---:|---:|---:|
| punctuation | 문장부호 문자 하나 제거 | 0% | 90% | 100% |
| substitute | 한글 음절 하나 치환 | 0% | 0.8% | 2.5% |
| syllable_drop | 한글 음절 하나 탈락 | 0% | 0.4% | 1.5% |
| particle_drop | 단어 끝의 목록 내 조사 제거 | 0% | 4% | 12% |
| word_drop | 단어 하나 누락 | 0% | 2% | 7% |
| name_variant | 알려진 이름의 각 출현에서 표기 선택 | 0% | 50% | 85% |
| hallucinate | 남은 한 줄 뒤 단어 하나를 8~20번 덧붙임 | 0% | 0.3% | 1.5% |
| overlap_drop | overlap=true인 한 줄 누락 | 0% | 25% | 55% |
| nav_mix | 장면 안에 내비 한 줄 추가 | 0% | 6% | 15% |
| merge | 쉼 <1.0초면 화자 관계없이 합침 | 항상 | 항상 | 항상 |
| split | 12초 초과 턴을 단어 경계에서 자름 | 항상 | 항상 | 항상 |

아이 화자는 `substitute`, `syllable_drop`, `particle_drop`, `word_drop` 확률이 표의 2배다(최대 100%).
이름 표기는 기본 지우→지우/지유/지오, 하린→하린/하림/아린, 도윤→도윤/도연/도운을
출현마다 독립적으로 선택한다. 다른 이름은 `names` 매핑으로 지정할 수 있다. 원래 표기가 다시 선택될 수도 있다.
내비 원본 발화도 전사에 남고, nav_mix는 시각을 정해 별도의 합성 내비 발화를 추가한다.
겹침 줄이 전부 누락되면 유효한 묶음을 유지하도록 원본 한 줄은 남긴다. 단어가 모두 지워지면 `음`으로 남긴다.

화자 필드는 모든 강도에서 제거한다. level 0도 턴 재구성은 하므로 원본 줄 수와 다를 수 있다.
쉼은 현재 합친 구간의 최대 끝 시각으로 계산하고, 정확히 1.0초면 합치지 않는다.
분할은 단어의 글자 수에 비례해 원본 구간의 시간을 배분한다. 예를 들어 같은 길이 단어 6개/30초는
단어 2개씩 0~10, 10~20, 20~30초가 된다. 문장 끝과 관계없이 나눈다.
단어 하나만으로 추정 길이가 12초를 넘는 예외에서는 텍스트를 온전히 유지하고 그 단어의 음성 구간을
최대 12초로 추정한다. 남는 시간은 쉼으로 취급하고 다음 단어의 추정 시작은 보존한다.
예를 들어 한 단어/20초는 그 단어/12초로 남으므로 묶음 끝 시각이 줄 수도 있다.
음성이 없는 합성 시각의 한계이며 메타 파일의 timing_policy에도 이 방식을 기록한다.

`--level mixed`는 카드 번호 기준 `(번호-1)%3`으로 0/1/2를 번갈아 배정한다. 이번 200개는 67/67/66개다.
기본 시드는 43이고 카드별로 `seed + card`를 사용한다. 원하는 강도를 하나만 보려면 `--level 0`, `1`, `2`를 쓴다.

### 묶음 스키마

모든 결과는 `common.schema.validate_block`을 통과한다. 통과하지 못하는 필드는 없다.
id는 `synth:<번호>@0`, source는 `synth:<번호>`, block은 0, split은 `test`로 두었다.
`split=test`는 기존 허용값에 맞춘 데모 표기이며 실제 평가셋으로 자동 승인한 의미가 아니다.
`prefix=[]`, `utts`는 재구성 후 순번, `turns`는 화자 없는 문자열 배열이다.
추가 메타데이터 `timing`, `level`, `demo_only`는 스키마에서 허용한다.

- `n_turns`: 열화·재구성 후 턴 수.
- `seconds`: 최대 끝 시각 − 첫 시작 시각.
- `speech_share`: 재구성 후 각 턴 길이의 합 / seconds(0초면 0).
- `median_gap`: 인접 턴 다음 시작 − 이전 끝의 중앙값(한 턴이면 0).
- `gap_before`: 원본 필드는 null, features는 첫 묶음 규칙대로 600.

원본 음성이 없어 시간은 생성·재구성의 추정치다. merge된 작은 쉼도 턴 구간 안에 들어가므로
speech_share가 원본의 실제 음성 점유율을 뜻하지 않는다. 겹침을 끄지 않으면 원본 방식처럼 음수 gap이나 1 초과 speech_share도 보존할 수 있다.
외부 실제 전사에서만 해석되는 `pipeline.blocks --check`의 원본 발화 매칭을 합성 자료에 적용하지 않는다.

## 훑어보기와 보고서 해석

기본 60개는 고정 시드로 라벨을 번갈아 뽑고, 각 라벨 안에서는 적게 뽑힌 패턴을 우선한다.
이는 데모 비율 40/20/40과 다른 **검토용 층화 표본**이다. 따라서 보고서의 전체 실제 같음 비율은
사람이 본 표본의 비율이며 200개 전체로 가중 보정한 수치가 아니다.
어떤 층이 부족하면 남은 층이 빈 자리를 채운다. `--limit 200`으로 전부 볼 수 있다.

화면 위에는 모델 입력 전사만 표시한다. 원본·의도 라벨·패턴·카드·why는 기본 API 응답에도 없다.

| 키 | 동작 |
|---|---|
| Y/N/M | 실제 같음 / 어색함 / 애매 |
| 1/2/3 | 이야기 / 한 줄 순간 / 없음 |
| C | 코멘트 입력으로 이동(두 판정 선택 전에 입력) |
| Esc | 코멘트 입력에서 빠져나오기 |
| O | 화자와 시각이 있는 원본 열기/닫기 |
| L | 의도 라벨·패턴 설명·장면 카드 열기/가리기 |
| J/K | 다음/이전 |

두 판정을 고르면 저장 후 다음 묶음으로 간다. 마지막에서도 미판정 개수를 안내한다.
코멘트를 쓰는 동안 다른 단축키는 작동하지 않는다. 이동만 하면 미완료 입력은 저장하지 않는다.
같은 이름으로 재시작하면 저장한 판정을 다시 볼 수 있고, 판정 버튼을 다시 고르면 수정한다.
O/L을 열었다 닫아도 `opened_clean`, `revealed_label`은 그 판정에 true로 남는다.
저장 시각은 UTC ISO 8601이다. 이름은 한글/영문/숫자/밑줄/붙임표만 허용한다.

서버는 127.0.0.1에만 바인딩하고, 허용 Host·Origin, 세션 토큰, 고정 정적 경로,
저장 경로 이탈·심볼릭 링크, 요청 크기, 판정 필드를 검사한다. 표시에는 textContent를 사용한다.
외부 스크립트·폰트·이미지·요청이 없고 전사나 코멘트를 HTTP 로그에 남기지 않는다.
이름별 저장은 `.local/synth/demo/skim/<이름>.jsonl`이며 같은 id를 수정하면 한 행으로 교체한다.

보고서는 전체·의도 라벨·패턴·level별로 **yes / (yes+no+maybe)**를 계산한다.
행=의도 라벨, 열=사람 판정으로 혼동 행렬을 만든다. 어색함 목록에는 id·pattern·코멘트만 넣으며 전사 본문은 쓰지 않는다.
원본/라벨을 열었던 판정 수를 함께 표시한다. 여러 사람은 `--skim 파일1 파일2`로 합칠 수 있고
각 사람의 판정을 별도 관측으로 센다. 서로 다른 생성 실행의 같은 synth id는 합치면 안 된다.

실제 비교 입력 기본값은 `.local/blocks/*.jsonl`이다. 합성은 내보낸 전체 묶음, 실제는 해당 파일의
턴 수·초·턴당 글자 수(공백 포함)·speech_share·median_gap의 평균/중앙값/p10/p90을 비교한다.
실제 경로에서 demo_only나 synth source는 제외한다. 실제 파일이 없으면 표를 —로 둔다.
비교 입력을 바꾸려면 `--real-glob '다른경로/*.jsonl'`, 합성 입력은 `--labels`, `--blocks`로 지정한다.
현재 로컬 실제 묶음은 25개라 비교 기준 자체도 작다.

## 가짜 전체 흐름과 테스트

최종 코드로 다음을 실행해 `.local/synth/fake/`에 카드 200장, 장면 200개, clean/labels/blocks 각 200개를 만들었다.
`demo/` 경로는 만들거나 쓰지 않았다. 가짜 결과는 자연스러움 평가 자료가 아니다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m synth.cards --n 200 --seed 43 --out .local/synth/fake/cards.jsonl
.\.venv\Scripts\python.exe -X utf8 -B -m synth.generate --cards .local/synth/fake/cards.jsonl --backend fake --model gemini-3.8-flash-medium --out .local/synth/fake/scenes.jsonl --concurrency 4
.\.venv\Scripts\python.exe -X utf8 -B -m synth.degrade --cards .local/synth/fake/cards.jsonl --scenes .local/synth/fake/scenes.jsonl --out-dir .local/synth/fake --level mixed --seed 43
```

검사는 아래 명령으로 수행한다. 서버 테스트는 자신이 띄운 서버를 finally에서 shutdown/close/join하며,
별도로 띄운 화면 확인용 서버(18769)도 종료했다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m pytest tests/test_synth_cards.py tests/test_synth_generate.py tests/test_synth_degrade.py tests/test_synth_report.py tests/test_skim_http.py tests/test_skim_browserlogic.py tests/test_schema.py tests/test_schema_v2.py --basetemp .local/syntht/pt -p no:cacheprovider -q
node --check tools/skim/app.js
```

확인 범위: 라벨/경계/턴 분포, 시드 결정성, 조합 반복 제한, 카드와 프롬프트 필드,
4장 프롬프트의 Windows agy 명령 길이(<32000 UTF-16 단위, 실행 없음), 생성 형식 오류,
같은/다른 호출 중복, 2회 재요청, 완료 호출 보존·재개·입력 변경 거부, 가짜 동시 호출 결과,
사용량 제한의 분 단위 대기, 모든 열화 규칙의 독립 on/off·시드 스냅샷·아이 오류 증가,
턴 합치기/자르기와 features 손계산, 200개 스키마 검사,
화면 가림·두 판정 저장·이름/경로/Origin/Host/세션 차단, 보고서 비율·혼동 행렬·실제 입력 집계.
실제 `app.js`는 Node의 모의 DOM + 실제 로컬 HTTP 서버에 연결하여 O/L/C/Esc/Y·N·M/1·2·3/J/K,
자동 저장·다음·이전·원본 가림 초기화·미판정 안내를 검사한다. Node는 서비스 실행에 필요 없고 이 추가 테스트에만 쓴다.

최종 실행 결과: **694개 통과, 1개 건너뜀**. 이 중 새 합성/화면 검사는 **90개 통과, 1개 건너뜀**이고,
기존 공통 스키마 검사는 604개 통과했다. `node --check`도 통과했다.

## 확인 못 한 것

- 샌드박스에서 `agy` 실행 및 네트워크 호출을 하지 않았다. 실제 모델의 출력 품질·토큰 비용·처리 시간·제한 후 실서비스 복귀는 미검증이다.
- 브라우저 UI 연결 도구가 `The browser access host is unavailable`을 반환했다. 실제 브라우저에서의 시각적 배치·모바일·키보드 포커스는 직접 보지 못했다. HTTP·정적 가림·실제 JS 로직은 별도 검사했다.
- Windows 심볼릭 링크 생성 권한이 없어 링크를 실제 만드는 테스트 1개는 건너뛰었다. 이름 경로/HTTP 경로/Host·Origin 차단 테스트는 수행했다.
- 기존 `tests/test_label.py`의 알려진 9개 실패는 손대지 않았으며 이번 검사 범위에 포함하지 않았다. 전체 저장소 테스트를 모두 통과했다고 주장하지 않는다.
- 사람의 자연스러움 판정과 라벨 일치율은 아직 없다. 밖에서 생성→가림 검토→보고서를 만든 후 방향을 결정해야 한다.

## 실행 후 수정

실제 합성은 200개 중 12개 장면을 저장한 뒤 `BackendError('agy denied actions')`로 종료 코드 2를 반환했다.
모델이 도구 사용을 시도해 해당 호출의 샌드박스에서 거부된 응답이며, 기존 코드는 검증 실패만 재요청하고
이 백엔드 오류는 전체 실행까지 전파했다.

- `UsageLimit`을 제외한 `BackendError`도 같은 카드 묶음을 재요청한다. 새 옵션 없이 기존 검증 재요청과 예산을 공유하며, 묶음당 최초 1회 + 재요청 2회, 총 3회다. 병렬 호출 사이 중복 검증에서 재요청해도 이 예산을 넘지 않는다.
- 예산을 소진하면 해당 묶음만 건너뛰고 다음 묶음을 계속 저장한다. 끝에 `실패한 카드 번호: [...]`를 출력하고 `scenes.meta.json`의 `failed_cards`에 기록한다. 다음 실행에서는 저장된 성공 카드를 제외하고 실패·미완료 카드만 요청한다. 실패 묶음이 나중에 성공하면 실패 목록에서 제거한다.
- 진행 중 `검증 실패 n, 백엔드 오류 n`을 누적 출력한다. `failure_counts.validation_errors`와 `failure_counts.backend_errors`에 이번 실행의 횟수를 기록한다. 예외 메시지와 장면 본문은 로그에 넣지 않는다.
- 종료 코드는 전체 성공 `0`, 입력·재개 설정 오류 등 `2`, 기존 사용량 제한 `3`, 재요청 소진으로 일부 또는 전부 생성 실패 `4`다. 사용량 제한은 일반 백엔드 재요청 예산에 포함하지 않으며 기존 `--wait-on-limit 분` 동작을 유지한다. 새 CLI 옵션은 없다.
- 프롬프트 첫 줄에 도구·파일을 쓰지 말고 JSON 배열만 답하도록 추가했다. 기존 실제 12개에서 재개할 수 있도록 이 한 줄 추가 전의 정확한 프롬프트 해시만 호환한다. 카드·모델·백엔드 등 다른 재개 설정의 일치 검사는 유지한다. 실제 데이터의 카드/이전 프롬프트 해시 일치를 읽기 전용으로 확인했으며 `scenes.jsonl`, `cards.jsonl`, 실제 메타데이터는 변경하지 않았다.
- `pipeline/label.py`는 수정하지 않았다. `demo/run_arm.py`도 확인만 했다. 이 파일의 `BackendError` 처리에서는 `backend_error` 기록 후 즉시 종료 코드 1로 전체 실행을 끝내므로 같은 종류의 중단 가능성이 남아 있다. 요청에 따라 수정하지 않았다.

가짜 백엔드로 첫 오류 후 성공, 끝까지 실패 후 다음 묶음 진행, 직렬·병렬 실행에서 실패 카드만 재개,
전부 실패해 장면 파일이 없는 경우의 재개, 사용량 제한 구분, 검증/백엔드 공유 예산과 이전 프롬프트 호환을 검증했다.
두 필수 테스트 파일은 **83개 통과**했다. adb·agy는 실행하지 않았다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
New-Item -ItemType Directory -Force .local/fixt | Out-Null
.\.venv\Scripts\python.exe -X utf8 -B -m pytest tests/test_demo_pleos.py tests/test_synth_generate.py --basetemp .local/fixt/pt -p no:cacheprovider -q
```

테스트 임시 경로는 `.local/fixt/pt`, 합성의 기존 락·원자적 저장 작업 경로는 `.local/syntht/`를 사용한다.
`.local/demo/`와 `.local/synth/demo/`에는 락 파일·임시 디렉터리를 남기지 않는다.

관련 demo·synth·skim·공통 스키마 회귀 검사는 **765개 통과, 1개 건너뜀**이었다.
회귀 검사 명령:

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m pytest tests/test_demo_pleos.py tests/test_synth_generate.py tests/test_demo_run_arm.py tests/test_demo_cli.py tests/test_listen_clips.py tests/test_synth_cards.py tests/test_synth_degrade.py tests/test_synth_report.py tests/test_skim_http.py tests/test_skim_browserlogic.py tests/test_schema.py tests/test_schema_v2.py --basetemp .local/fixt/pt -p no:cacheprovider -q
```
