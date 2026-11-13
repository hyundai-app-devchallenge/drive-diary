# T61 — 이야기별 보존 가치 판정과 사람 라벨 비교

## 목적과 범위

T60에서는 사람이 `none`으로 표시한 44블록 중 26블록을 on-device judge가 저장했다. 대부분 잡음이 아니라 운전·주차·일정 등 평범한 용무 대화였다. 기존 이야기 분할의 `kind=none`은 실제 대화가 없는 경우만 뜻하므로, 이야기마다 별도의 `keep`과 `reason`을 추가해 사람의 보존 기준과 비교한다.

앱과 배포 모델은 변경하지 않는다. 기준은 [rubric.md v3](../../model-create/rubric.md) §2~§6이며, 평가 자료와 judge 정책은 [T60](T60-story-stitch-measure.md)을 따른다. 실제 agy 호출과 결과 기입은 Claude 세션에서 수행한다. 이번 구현 검증에는 지어낸 자료와 fake 응답만 사용했으며, 실제 판정 품질 수치는 아직 없다.

## 프롬프트와 응답 계약

`prompts/story_split.md`에 다음 순서를 추가했다. `kind`의 기존 의미와 분류 13개, 제목·키워드 규칙, 입력 전체를 순서대로 한 번 덮는 분할 규칙은 유지한다.

| 순서 | 판단 | 숨김 이유 |
|---|---|---|
| 1 | 차 안 탑승자의 대화인가 | `not_in_car` |
| 2 | 가족의 대화인가. 부부·커플·어른 가족도 포함하며 친구·동료끼리는 내용과 무관하게 제외 | `not_family` |
| 3 | 현재 분위기가 다툼·혼내기·울음·험담·무거운 나쁜 소식 중심인가 | `negative` |
| 4 | 남길 화제나 한마디가 있는가. 길 안내·주차·차선·결제·심부름·일정 등 용무뿐이면 숨김 | `procedure` |
| 4 | 단답이나 맥락 없는 조각 등으로 남길 내용이 없는가 | `no_content` |
| 5~6 | 종류를 고르고, 경계에서는 일 년 뒤 다시 듣고 싶은지 판단 | 남기면 빈 문자열 `""` |

소재보다 지금의 분위기를 본다. 장난스러운 놀림과 지나간 일을 따뜻하게 받아 주는 이야기는 남길 수 있다. 길이·줄 수·주고받은 횟수는 보존 가치의 기준이 아니다. 용무 사이에도 남길 이야기나 순간이 있으면 남긴다. 관계나 STT에 없는 감정을 지어내지 않는다. 예시는 전부 지어냈으며 절차와 비가족 대화를 숨기는 예를 포함한다.

각 이야기의 필수 필드는 `segments`, `title`, `category`, `keywords`, `kind`, `keep`, `reason`이다. 릴레이가 만드는 `tags`도 기존처럼 유지한다.

- `keep`은 JSON 불리언이다. 숫자나 문자열은 거부한다.
- `keep=true`이면 `reason=""`, `keep=false`이면 위 다섯 가지 비어 있지 않은 이유 중 하나여야 한다.
- `kind=none`이면 반드시 `keep=false`, `reason="no_content"`다. 실제 말이 없는 그룹에는 이 규칙을 먼저 적용한다.
- 실제 대화는 `keep=false`여도 제목·분류·키워드를 채운다. 숨긴다는 이유로 `kind`를 `none`으로 바꾸지 않는다.

`/story-split` 검증과 fake 응답, 클라이언트 저장 형식에 같은 계약을 적용했다. 기존 `/summarize`의 계약과 동작은 유지한다. split stdout에는 기존 집계와 함께 이야기 단위 `keep`, `hide`, 숨김 사유별 `reasons`를 출력한다. 이 수치는 성공한 창 전체 기준으로, 일부 창이 실패한 run의 성공 창도 포함한다. 전체 파이프라인 평가는 아래 value에서 완전한 결과만 허용한다.

## 실행 방법

작업 디렉터리는 `talk-rec/model-create`다. 가상환경을 활성화하거나 아래 `python`을 `.venv/Scripts/python.exe`로 바꾼다.

기존 `runs.jsonl`에 cast가 없으면 Part A를 다시 실행한다. `measure.load_dev`가 cast를 가져오고 snapshot이 블록마다 cast를 저장한다. 추가되는 것은 정해진 cast 라벨뿐이며 전사·코멘트는 저장하지 않는다. 새 snapshot과 맞는 split도 다시 생성한다.

```powershell
python -X utf8 -B -m eval.stitch.measure
```

별도 터미널에서 릴레이를 실행한다. 다음 명령은 fake 배선 확인용이다. Claude의 실제 실험에서는 `--fake`를 뺀다.

```powershell
python -X utf8 -B -m tools.summary_relay --fake --port 8771
```

선택한 judge 정책과 모델로 분할한 뒤 평가한다. 다음은 T60의 현재 judge 기준을 사용하는 예다.

```powershell
python -X utf8 -B -m eval.stitch.split --relay http://127.0.0.1:8771 --policy cont0.957_min0 --model gemini-4-hydrogen-medium
python -X utf8 -B -m eval.stitch.value .local/eval/stitch/split-cont0.957_min0-gemini-4-hydrogen-medium.jsonl
```

value는 STT나 원본 라벨 파일을 다시 열지 않고 `.local/eval/stitch/runs.jsonl`의 id·사람 라벨·선택 정책 결정과 split의 이야기 결정을 결합한다. run, 창, 창 내부 segment 번호, id 순서와 전체 coverage가 맞아야 한다. judge가 저장하지 않은 블록은 LLM에 보내지 않으며 결합 파이프라인에서도 저장하지 않는다.

정상 종료 코드는 0이다. 실패·누락·중복된 창, 순서·id 불일치, 잘못된 keep/reason은 고정된 설명과 종료 코드 2로 거부하며 새 평가 JSON을 쓰지 않는다. 실패 창은 숨김이나 저장으로 임의 처리하지 않는다. judge가 아무 블록도 저장하지 않았다면 정책을 알 수 있는 기존 형식의 파일명과 빈 split 파일로 평가할 수 있다.

## 산출물과 지표

입력이 `split-<name>.jsonl`이면 `.local/eval/stitch/value-<name>.json`에 집계 JSON을 원자적으로 저장한다. stdout에도 같은 집계만 출력한다. split에는 생성 제목이 있으므로 계속 비공개로 취급한다. 평가 JSON과 stdout에는 블록 id·전사·제목·키워드·코멘트·원본 오류를 넣지 않는다. 실제 내용을 저장소·문서·로그로 옮기지 않는다.

| 필드 | 의미 |
|---|---|
| `coverage` | run·저장 run·창 수, cast가 없는 블록 수, 기존 형식으로 읽은 이야기 수 |
| `stories.keep/hide/reasons` | 이야기 단위 보존·숨김 수와 숨김 이유 히스토그램 |
| `overall.judge_saved` | judge가 저장한 블록 중 사람 keep/none/무라벨 각각의 LLM kept·hidden 수 |
| `overall.reason_by_human` | 같은 저장 블록에 대한 이유 × 사람 라벨 히스토그램. 남김은 `""`이며 블록 수로 가중 |
| `overall.judge_alone` | judge만 적용한 저장 블록 수·사람 keep 저장 수·recall·false-save 수 |
| `overall.judge_and_llm` | judge AND LLM keep을 적용한 같은 지표 |
| `overall.change` | 추가로 숨긴 사람 keep, 제거한 false save, 결합 recall에서 judge recall을 뺀 값 |
| `by_cast` | cast별로 위 블록 지표 전체를 반복 |

이야기에 속한 각 블록은 같은 keep/reason을 상속한다. 따라서 한 이야기 안의 사람 라벨이 섞여 있으면 보존 이득과 손실이 각각 집계된다. recall의 분모는 **runs.jsonl 전체의 사람 keep 블록 수**다. judge에서 이미 탈락한 사람 keep도 포함하며, 무라벨 블록은 recall·false-save에서 제외한다. 사람 keep이 없으면 recall은 JSON `null`이다. false save는 사람 `none`인데 최종 저장한 블록 수다. `unsure`와 사람 policy 플래그의 취급은 T60과 같고, policy 차단 성능을 측정하지 않는다.

cast가 있으면 `child_family`, `adult_family`, `couple`, `friends`, `unknown`별 결과를 낸다. 모든 블록에서 cast가 빠진 기존 snapshot은 전체 결과만 내며 `by_cast={}`로 둔다. 일부만 빠졌으면 그 블록은 unknown에 포함하고 `cast_missing_blocks`로 드러낸다.

기존 T60 split에서 `keep`과 `reason`이 **둘 다 없으면** `keep=true`, `reason=""`로 읽는다. 옛 `kind=none`도 같은 방식이며, 이는 기존의 가치 판정 없는 파이프라인을 재현하기 위한 호환 처리다. `legacy_stories_assumed_keep`에 수를 표시한다. 두 필드 중 하나만 없거나 새 계약을 어긴 파일은 거부한다. 옛 결과로 새 프롬프트의 품질을 주장하지 않는다.

## 검증

지어낸 자료로 계약 검증, fake 릴레이, 숨김 메타데이터 유지, split 집계·저장, 여러 창과 탈락 블록의 결합, 전체 recall 분모, cast별 집계, 사람 라벨별 이유, 기존 파일 호환, 불완전 결과 거부, stdout·JSON의 비공개 내용 배제를 확인했다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m pytest -q -p no:cacheprovider --basetemp=.local/eval/stitch/pytest-t61 tests/test_stitch_split.py tests/test_stitch_measure.py tests/test_stitch_value.py tests/test_summary_relay.py
```

2026-11-13: 위 관련 테스트 **249개 통과**. 별도 환경 우회 없이 기존 가상환경에서 실행했다. value 모듈의 `--help` 진입도 확인했다. 실제 agy 호출이나 실자료 재측정은 실행하지 않았다.

## 결과 — Claude 실제 실행 후 집계만 기입

| 항목 | 결과 |
|---|---|
2026-11-13, 정책 cont0.957_min0, gemini-4-hydrogen-medium, 실제 호출. 두 번 돌렸다. v1은 Codex 프롬프트 그대로, v2는 가족 판단에 "동료·친구임이 분명할 때만 not_family, 확실하지 않으면 가족으로 본다"를 더한 것이다(앱에서는 탑승자 대부분이 가족이라 가족을 잘못 숨기는 쪽이 더 나쁘다).

| 항목 | v1 | v2 (채택) |
|---|---|---|
| 전체 블록 / 사람 keep / 사람 none | 77 / 33 / 44 | 같음 |
| 저장 run / 성공 / 실패 | 18 / 18 / 0 | 18 / 18 / 0 |
| 이야기 keep / hide | 22 / 8 | 24 / 6 |
| 숨김 이유 | negative 2, no_content 1, not_family 3, procedure 2 | not_family 4, procedure 2 |
| judge 단독 recall / false saves | 0.909 / 26 | 0.909 / 26 |
| judge AND LLM recall / false saves | 0.848 / 13 | 0.879 / 16 |
| 중앙 지연 시간 | 22.5초 | 22.0초 |

judge가 저장한 블록 (v2):

| 범위 | 사람 keep 유지 | 사람 keep 숨김 | 사람 none 유지 | 사람 none 숨김 |
|---|---|---|---|---|
| 전체 | 29 | 1 | 16 | 10 |
| child_family | 24 | 0 | 5 | 0 |
| adult_family | 3 | 0 | 6 | 4 |
| couple | 2 | 1 | 1 | 1 |
| friends | 0 | 0 | 4 | 5 |

결론:
- LLM 가치 판단은 잘못 저장을 40~50% 줄인다(26 → 13~16). 주로 친구 대화(9 → 4)와 어른 가족의 용무 대화(10 → 6)를 숨긴다.
- 사람 keep 손실은 1~2블록이다. 부부 대화 하나를 두 번 모두 not_family로 봤다. 숨김이지 삭제가 아니므로 받아들일 만하다.
- 실행마다 결과가 꽤 흔들린다. v1은 아이 있는 가족의 부정적 대화 3블록을 negative로 숨겼고 v2는 하나도 숨기지 않았다. 표본이 작아(77블록) v1과 v2의 차이는 프롬프트 효과와 구분되지 않는다.
- 판정 모델 재학습(T62, 다른 세션)과 함께 쓴다. 앱은 v2 프롬프트를 쓴다(I11).
