# T60 — 앱 변경 전 이야기 이어 붙이기 오프라인 측정

## 목적과 범위

대화를 90초마다 자르면 하나의 이야기가 여러 블록으로 나뉜다. 블록별 독립 판정은 이어지는 대화를 버리거나 종류를 달리 붙일 수 있고, 독립 요약은 비슷한 제목을 반복해서 만들 수 있다. 앱을 변경하기 전에 연속 블록의 저장 기준을 완화할 때의 회수율·오저장 비용과 run 단위 이야기 분할 가능성을 측정한다.

이 작업은 앱 동작이나 배포 모델을 변경하지 않는다. Part A는 LLM 없이 배포 int8 ONNX를 사용하고, Part B는 선택한 정책으로 저장된 블록만 로컬 릴레이에 보낸다. 실제 agy 실험은 Claude 세션에서 실행한다. fake 결과는 배선 검증용이며 분할 품질의 근거로 사용하지 않는다.

## 정의

### 평가 자료

`eval.real.load_labels(root, real.run_directory(root, 'v1'), annotators=['vizatae'], split='dev')` 게이트만 이용한다. `open_test`는 전달하지 않는다. `cast == unknown`은 제외한다. 알려진 cast의 dev 블록은 라벨 유무와 관계없이 추론하고 run을 구성한다. 라벨이 없는 블록도 저장 결정에는 영향을 주지만 정답 기반 지표의 분모에서는 빠진다. `unsure` 라벨은 별도 제외하지 않는다.

입력 문자열은 `demo.trial.data.input_text`로 finalized `lines`를 줄별 strip 후 LF로 연결한다. 배포 번들의 토크나이저만 로컬에서 읽는다. `train.v3.export.session` 및 `token_batch(..., max_length=256, truncation='both')`를 재사용한다. 첫 부분과 끝 부분을 보존하고, 오른쪽 패딩하며, 블록마다 배치 크기 1로 추론한다. 모델 가중치는 ONNX만 읽으며 원격 코드나 네트워크 다운로드는 사용하지 않는다.

### Run

같은 source의 시간순 블록 중 앞 블록의 `closing`이 `length`이고 다음 블록의 시작이 앞 블록 끝에서 0~2초 뒤이면 이어 붙인다. 가능한 한 길게 묶는다. source 변경, 2초 초과 간격, 겹친 시간, `range_index` 변경, `length` 이외 종료 사유는 경계가 된다. 따라서 silence, range end, manual, end 다음에는 새 run을 시작한다.

run 길이는 첫 블록 시작부터 마지막 블록 끝까지의 시간이다. continuation은 run의 두 번째 이후 블록이다. 첫 블록과 continuation으로 나누는 모든 집계는 **run 내 위치** 기준이며, 처음 저장된 블록의 위치를 뜻하지 않는다.

### Hysteresis

첫 저장 또는 바로 앞 블록이 탈락한 뒤의 저장은 `p >= 0.957`이어야 한다. 같은 run에서 **바로 앞 블록이 저장된 경우에만** `p >= T_cont`로 완화한다. 하나라도 탈락하면 완화 상태가 초기화된다. 다음 run으로 상태가 이어지지 않는다. 확률 경계는 포함한다.

`T_cont ∈ {0.957, 0.9, 0.8, 0.7, 0.5, 0.3}`을 측정한다. 모든 블록에 0.957을 쓰는 현재 규칙은 `cont0.957_min0`이다. 정책 이름은 `cont<T_cont>_min<N>`이며 위 threshold와 아래 글자 수의 모든 조합을 Part B에서 선택할 수 있다.

정책 차단 신호는 실제 배포에서 반드시 저장을 막아야 한다. 오프라인에는 인간 라벨의 `policy`만 있으므로, 요청한 grid에서는 이를 차단 신호로 사용하지 않고 비어 있지 않은 policy 라벨의 **블록 수**만 별도로 기록한다. 이 grid는 정책 차단 후의 성능을 나타내지 않는다. 순수 함수의 `enforce_policy=True` 동작은 차단 및 상태 초기화를 테스트한다.

### 최소 내용 gate

finalized `lines`의 모델 입력 문자열에서 Unicode 공백(`isspace`)을 제외한 코드포인트 수가 `N` 이상이어야 저장할 수 있다. `N ∈ {0, 20, 40, 80, 120}`이며 `N=0`은 gate를 끈다. gate 탈락도 hysteresis 상태를 초기화한다.

현재 규칙 및 `T_cont=0.5` 각각에 대해 `N=0` 대비 제거된 human-none / human-keep / 무라벨 블록 수를 기록한다. `removed_vs_min0`은 상태 초기화의 여파까지 포함한 최종 감소량이고, `directly_below_minimum`은 기존에 저장됐으나 글자 수가 직접 부족한 블록 수다.

### Story split

run이 끝나면 저장된 블록을 순서대로 `[1]`, `[2]` 등의 번호와 함께 한 번 요청한다. LLM은 입력 전체를 순서대로 정확히 한 번 덮는 연속 그룹으로 나누고, 그룹마다 `segments`, `title`, `category`, `keywords`, `kind`를 반환한다. 탈락한 블록은 전송되지 않으므로 여기서 말하는 연속 그룹은 **전송된 블록 순서** 기준이다.

`story`는 화제를 받아 이어 간 대화, `moment`는 한마디·웃음·노래·감탄 자체가 남길 만한 장면이다. 실제 대화가 없는 잡음·TV/라디오·내비게이션 음성만의 그룹은 `none`이다. `none`은 title/category를 빈 문자열, keywords를 빈 배열로 둘 수 있다. 비어 있지 않은 값에는 동일한 검증 규칙을 적용한다.

제목은 한 줄 25자 이하이며, 분류 13개와 키워드 0~2개의 문법 규칙은 기존 summary 검증과 공유한다. 키워드는 trim 후 1~6자, 한글 완성형·영문·숫자·가운데점만 허용하고 숫자 전용·분류명·대소문자 무시 중복을 거부한다. 사람 이름이나 근거 없는 내용 제외 등 의미 규칙은 프롬프트에 명시하며 문법 검증만으로 보장되지는 않는다.

입력이 12개를 넘으면 저장된 블록을 연속된 최대 12개씩의 창으로 나눠 각 창을 한 번 요청한다. 창 경계를 넘는 이야기 병합은 수행하지 않는다. `window_count`, `window`, `segment_offset` 및 `windowed_runs`로 이 제한을 확인한다. 저장 블록이 없는 run은 요청하지 않는다.

## 실행 방법

아래 명령의 작업 디렉터리는 `talk-rec/model-create`다. 가상환경을 활성화하거나 `python` 대신 `.venv/Scripts/python.exe`를 사용한다.

### Part A — LLM 없는 측정

```powershell
python -X utf8 -B -m eval.stitch.measure
```

`.local/export/m1/model.int8.onnx`와 해당 디렉터리의 토크나이저가 필요하다. `predictions.json` 중 dataset이 dev이고 id가 겹치는 int8 확률을 비교하여 overlap과 max abs diff를 보고한다. 차이가 `1e-6`을 넘거나 overlap이 없으면 집계 파일을 남기되 종료 코드는 2다. 정상 종료 코드는 0이다. stdout에는 블록·run·policy 수, 확률 재현 차이와 짧은 threshold 표만 나온다.

### Part B — 릴레이와 이야기 분할

먼저 별도 터미널에서 fake 릴레이를 실행할 수 있다.

```powershell
python -X utf8 -B -m tools.summary_relay --fake --port 8771
```

실제 실행은 Claude 세션에서 `--fake`를 빼고 릴레이를 시작한다. 클라이언트 명령은 같다.

```powershell
python -X utf8 -B -m eval.stitch.split --relay http://127.0.0.1:8771 --policy cont0.5_min40
```

모델 선택 예:

```powershell
python -X utf8 -B -m eval.stitch.split --relay http://127.0.0.1:8771 --policy cont0.957_min0 --model gemini-3.8-flash-medium
```

클라이언트는 Part A의 `runs.jsonl` 결정을 읽고, 동일한 dev 게이트를 다시 거쳐 입력을 메모리에서 복원한다. run별 id 순서, 라벨, 선택 정책의 재계산 결정이 다르면 중단한다. 자료·전사·모델이 바뀌었으면 Part A부터 다시 실행한다. 같은 글자 수의 전사 수정까지 검출하는 스냅샷은 아니므로 다른 세션이 자료를 수정하는 동안 A/B를 실행하지 않는다.

HTTP는 숫자로 지정한 loopback 주소만 허용하며 proxy나 redirect를 사용하지 않는다. `/story-split`은 기존 agy backend 생성·직렬화 lock·출력 오류 재시도 경로와 Windows loopback half-close 처리를 공유한다. LLM 출력이 잘못되면 릴레이가 한 번 다시 시도한다. 클라이언트는 연결 reset/조기 종료에 한해 **총 최대 3회 시도**한다. timeout이나 HTTP 오류는 반복하지 않는다. reset이 응답 수신 중 발생하면 서버가 이미 처리했을 수도 있어, 재시도는 LLM 중복 실행 가능성이 있다.

요청 본문은 `/story-split`에서 최대 256 KiB, 기존 `/summarize`에서는 기존 32 KiB 제한을 유지한다. agy의 기존 명령 길이 제한에 걸리는 입력은 실패로 남으며 자동으로 내용을 자르지 않는다. 각 HTTP 응답 대기는 180초다. 성공 run은 모든 창이 성공한 run이다. 일부 창만 성공하면 해당 run은 failed이며, 성공한 창의 결과는 파일에 남긴다. 프로세스가 끝날 때 파일을 원자적으로 교체하므로 같은 정책·모델을 재실행하면 이전 결과를 덮어쓴다.

stdout은 요청한 run 수, 성공/실패 수, 생략된 run 수, 창 수, 창 분할 run 수, 성공 run의 이야기 수 히스토그램, 성공 창에서 나온 `none` 그룹 수, 성공 요청의 중앙 지연 시간만 출력한다. 이야기 수에는 `none`도 포함한다. 성공 코드는 0, 요청 실패 또는 입력 오류는 2다.

## 산출물과 공개 범위

| 경로 | 내용 | 취급 |
|---|---|---|
| `.local/eval/stitch/measure.json` | run 통계, grid, min-content 비교, kind 일관성, int8 재현 차이 | 집계 숫자만 포함 |
| `.local/eval/stitch/runs.jsonl` | run별 id, 확률, model kind, 최소 인간 라벨(keep/kind/policy 유무), 30개 정책 결정 | 비공개, 전사·제목·코멘트 없음 |
| `.local/eval/stitch/split-<policy>-<model>.jsonl` | 창별 id, 그룹, 제목, 분류·키워드·tags, kind, 시도 횟수·지연, 실패 상태 | **생성 제목을 포함하므로 비공개** |
| `prompts/story_split.md` | 한국어 규칙 및 지어낸 예시 | 저장소 포함 가능 |

실효 모델명에는 effort 접미사가 붙는다. JSONL의 `segments`는 창 안의 1부터 시작하는 번호, `segment_offset`은 run의 저장 블록 목록에서 창 앞에 있는 블록 수, `ids`는 실제 블록 id다. 실패 행에는 원본 응답·예외 메시지를 기록하지 않는다. 전사는 어느 산출물에도 저장하지 않으며 클라이언트와 릴레이 stdout/log에도 출력하지 않는다. 실제 제목·전사·id별 결과를 문서나 저장소에 복사하지 않는다.

## 확인할 지표와 해석

- run 수와 블록 수 히스토그램, run 길이 median/p90/max, continuation 비율로 시간 제한이 만드는 분절 규모를 확인한다.
- 현재 규칙의 human-keep 탈락을 첫 블록/continuation으로 나눠 문맥 단절의 손실이 어디에 집중되는지 본다.
- hole은 **인접한 세 블록**이 저장-탈락-저장이며 가운데 인간 라벨이 keep인 경우다. 둘 이상 연속 탈락한 간격은 이 지표에 포함하지 않는다.
- recall은 라벨 있는 human-keep 중 저장된 블록 비율이다. false save는 인간 none인데 저장된 블록 수다. 첫 블록/continuation으로 분리하며 saved seconds는 저장된 각 블록의 `end-start` 합이다. run 내부 공백은 더하지 않는다.
- threshold 완화에서 recall 회복과 continuation false save·saved seconds 증가를 함께 본다. hole 감소만으로 정책을 고르지 않는다.
- kind 비교의 분모는 같은 run 안에서 인접하며 둘 다 human-keep인 쌍이다. 인간 kind 변경, model `kind_logits` argmax 변경, 둘 다 변경한 쌍의 수를 기록한다. model kind에는 `none`도 포함한다. 저장 여부와 독립적으로 비교하며 실제 화제 전환도 종류 변경의 원인이 될 수 있다.
- 최소 글자 수별 human-none 제거 이득과 human-keep 손실을 본다. 직접 gate 손실과 hysteresis 초기화의 추가 손실을 구분한다.
- Part B의 성공률, 이야기 수 분포, `none` 수, 지연 시간과 12개 창 분할 비율을 확인한다. private 결과에서 연속 화제의 중복 제목, 과도한 병합·분할을 검토하되 문서에는 집계만 옮긴다. 인간 이야기 경계 정답은 없으므로 A/B 집계만으로 분할 정확도를 주장하지 않는다.

## 검증 방법

지어낸 자료만 사용한 순수 함수·게이트·int8 parity 비교·coverage/order·공유 필드 검증·fake route·클라이언트 창 분할·재시도·비공개 출력 검증이 있다. 기존 `/summarize` 테스트도 함께 유지한다.

```powershell
New-Item -ItemType Directory -Force .local/eval/stitch | Out-Null
python -X utf8 -B -m pytest -q -p no:cacheprovider --basetemp=.local/eval/stitch/pytest-check
```

현재 샌드박스에서는 기존 가상환경의 Pillow/torchvision 파일을 읽지 못하는 권한 오류가 있었다. 환경 파일이나 권한은 변경하지 않고, Part A 실행 프로세스에서 읽을 수 있는 Codex 런타임의 Pillow를 먼저 import한 뒤 기존 모듈 경로를 복원했다. 전체 테스트에서는 같은 방법에 더해 text 모델에서 쓰지 않는 선택 의존성 torchvision의 가용 플래그만 해당 프로세스에서 껐다. 일반 환경의 표준 명령 성공과 이 임시 실행 환경의 결과는 구분해서 기록해야 한다.

또한 기존 `test_label.py`의 fixture는 `prompts/labeler.md` 안의 두 개 text 코드 블록을 기대한다. 현재 해당 문서는 `judge.md` 참조문이므로 그 fixture 오류가 독립 실행에서도 발생한다. 이 두 파일은 T60 변경 허용 범위 밖이라 수정하지 않았다. Windows multiprocessing 테스트를 실행할 때는 stdin 실행기 대신 표준 `-m pytest` 또는 `-c` 실행기를 사용한다.

## 결과 — 실행 후 집계만 기입

| 항목 | 결과 |
|---|---|
| 실행 일시 / 번들 식별 | 2026-11-13 / `.local/export/m1/model.int8.onnx` (AVD 탑재본) |
| dev 블록 / 라벨 있는 블록 / unknown 제외 / policy 라벨 블록 | 77 / 77 / 1 / 1 |
| int8 overlap / max abs diff / 재현 통과 | 77 / 0.0 / 통과 |
| run 수 / 블록 수 히스토그램 / continuation 비율 | 21 / 1개 6, 2개 3, 3개 5, 4개 2, 7개 2, 8개 1, 10개 2 / 73% (56/77) |
| run seconds median / p90 / max | 240초 / 717초 / 890초 |
| 현재 규칙 keep 탈락 first / continuation / holes | 사람 keep 33개 중 1 / 2 / 0 (recall 0.909) |
| threshold별 recall / false saves first·continuation / holes / saved seconds | 0.957: 0.909, 7·19, 0, 4762초 · 0.9: 0.909, 7·20 · 0.8~0.5: 0.909, 7·22 · 0.3: 0.909, 7·23, 4982초. 낮춰도 recall은 그대로이고 잘못 저장만 는다 |
| 인접 human-keep 쌍 / human kind 변경 / model kind 변경 / 둘 다 변경 | 19 / 4 (21%) / 6 (32%) / 2 |
| 최소 글자 수별 none 제거 / keep 손실 / 직접 손실과 추가 손실 | 0.957 기준: 20·40자 0/0, 80자 1/1, 120자 4/2. 0.5 기준: 40자 1/0, 120자 6/2. 잡음 거르기 효과가 거의 없다 |
| Part B 정책 / 모델 / fake 여부 | cont0.957_min0 / gemini-3.8-flash-medium, gemini-4-hydrogen-medium / 실제 호출 |
| Part B runs sent / ok / failed / 창 분할 run 수 | Flash 18 / 11 / 7 (클라이언트 180초 시간 초과, agy가 3~6분 걸림) / 0. Hydrogen 18 / 18 / 0 / 0 (시간 초과를 480초로 올림) |
| 이야기 수 히스토그램 / none 수 / 중앙 지연 시간 | Flash(11 run): 1개 10, 2개 1 / 0 / 36.7초. Hydrogen(18 run, 59블록 → 29이야기): 1개 12, 2개 3, 3개 2, 4개 1 / 0 / 17.7초 |
| 전체 테스트 결과 | T60 관련 193개 통과. 전체는 기존 `test_label.py` fixture 불일치 15건(T60 범위 밖) |
| 앱 변경 전 판단과 후속 실험 | 아래 |

### 판단 (Claude가 전사와 하나씩 대조, 전사는 기록하지 않음)
- 90초 뒤 이어지는 조각이 판정에서 떨어지는 일은 드물다(2/33). 연속 조각 기준을 낮추는 hysteresis는 효과가 없어 쓰지 않는다.
- 조각마다 story/moment가 자주 바뀐다(모델 32%). 종류는 이야기 단위로 LLM이 정한다.
- LLM 이야기 나누기는 쓸 만하다. Hydrogen은 화제가 바뀌는 곳에서 잘 나누고(9조각 → 4이야기, 5조각 → 3이야기), 같은 화제는 하나로 묶는다. Flash는 덜 나누는 편이다(두 화제를 한 이야기로 묶은 경우 1~2건). Hydrogen 추천.
- 최소 글자 수 gate는 효과가 없다. 사람이 "저장 안 함"으로 단 조각은 잡음이 아니라 어른끼리의 평범한 대화(운전·주차·출퇴근 이야기)가 대부분이다.
- 진짜 문제는 판정 모델이 너무 많이 저장하는 것이다(사람 none 44개 중 26개 저장). LLM도 "내용 없음"을 한 번도 돌려주지 않았다.
- 후속: (1) 앱에 이야기 묶기 반영, (2) 판정 모델 재학습(평범한 대화 = 저장 안 함 예시 보강)과 LLM 이야기 단위 가치 판단을 둘 다 시험한다.
