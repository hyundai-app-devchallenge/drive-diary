# T11 학생 모델 학습(S0·S1) 작업 기록

2026-11-10, `talk-rec/model-create`에서 구현·실험했다. 요청된 실험 1–4와 동일 시드 재실행을 모두 완료했다. **아래 수치는 단일 LLM이 만든 silver 라벨에 대한 잠정치다.** human gold, Pleos 전사, 실제 주행 D 데이터의 성능을 뜻하지 않는다. DESIGN.md §8·§10에 따라 품질 통과나 배포 임계값을 확정하지 않았다.

## 만든 파일과 계약 적용

- `train/__init__.py`, `train/data.py`: 스키마 검증, ID 조인, 여러 silver 디렉터리의 표본 가중치, gold 우선, skip·미라벨 집계, 채널 단위 부분집합, 입력 구성, 라이브 입력 스냅샷.
- `train/model.py`, `train/engine.py`: KoELECTRA 전체 미세조정, 4개 헤드, 가중 손실, AdamW, warmup/decay, fp16 AMP, gradient clipping, dev AP 조기 종료, 최적 체크포인트, 온도 보정, 재로딩 도우미.
- `train/s0.py`: 기존 문자 2–4그램 로지스틱 회귀의 특징 체계 재현 및 같은 라벨 목표 학습.
- `train/run.py`, `train/sweep.py`: 단일 설정 실행, 순차 실험 목록, T14 CLI 평가·비교 호출, 실행 메타데이터.
- `train/configs/base.json`, `all.json`, `s0c0.json`, `s0c3.json`, `s1c3.json`, `s1c0.json`, `s1c1.json`, `s1l256.json`, `s1p25.json`, `s1p50.json`, `s1repeat.json`.
- `train/README.md`, `tests/test_train_data.py`, `tests/test_train_model.py`, `tests/test_train_eval.py`, 이 작업 기록.

DESIGN.md §3의 판정 단위와 입력 순서를 따른다. 각 턴에 공통 `normalize()`를 적용한 뒤, **`pipeline.windows.truncate_tokens()`를 직접 호출**한다. 오래된 문맥 턴부터 통째로 버리고, 현재 턴만으로도 초과할 때 현재 턴의 왼쪽을 자른다. CLS·문맥·문맥 SEP는 segment 0, 현재 턴과 마지막 SEP는 segment 1이다. 길이는 특수 토큰까지 포함한다. 해당 모듈이 편집 중일 때 import 오류만 제한적으로 재시도하며 자체 truncator로 대체하지 않는다.

DESIGN.md §4와 이번 작업 지시의 `story>=2`를 주 이진 목표로 쓴다. §9.2의 `story/3` 연속 목표 BCE는 **별도 서열 보조 헤드**로 유지한다. 이 헤드의 해석은 `3*sigmoid(logit)`인 0–3 점수이며, 4범주 softmax나 3개 누적 경계 헤드는 아니다. kind·exclude는 각각 5개 BCE 출력이다. exclude가 켜져도 story 목표를 바꾸거나 마스킹하지 않는다(§4). 추론 후 저장 정책·거부 소급 처리는 모델 밖 범위다(§3·§5).

## 실행 방법과 실행한 명령

PowerShell 작업 위치는 `C:\github\drive-diary\talk-rec\model-create`다. 패키지·`pyproject.toml`·`uv.lock`은 변경하지 않았다. 첫 점검에서 HF 캐시에 tokenizer/config만 있고 인코더 가중치는 없어 Python `huggingface_hub.snapshot_download()`로 아래 고정 revision의 가중치를 `.local/hf`에 받았다. curl이나 패키지 추가 설치는 사용하지 않았다.

```powershell
New-Item -ItemType Directory -Force .local/train | Out-Null
.\.venv\Scripts\python.exe -B -m pytest tests/test_train_data.py tests/test_train_model.py tests/test_train_eval.py --basetemp .local/train/pytest -p no:cacheprovider -q --tb=short
.\.venv\Scripts\python.exe -B -m train.sweep --list train/configs/all.json
.\.venv\Scripts\python.exe -B -m pytest --basetemp .local/train/pytest -p no:cacheprovider -q --tb=short
```

각 실험은 다음 단일 명령의 config 경로만 바꿔 실행한다.

```powershell
.\.venv\Scripts\python.exe -B -m train.run --config train/configs/s1c3.json
```

| 실험 | 설정 파일 |
| --- | --- |
| 1. S0 현재/문맥 3턴 vs S1 문맥 3턴·160 | `s0c0.json`, `s0c3.json`, `s1c3.json` |
| 2. S1 문맥 0·1·3턴 | `s1c0.json`, `s1c1.json`, `s1c3.json` |
| 3. S1 최대 길이 160·256 | `s1c3.json`, `s1l256.json` |
| 4. S1 학습 채널 25·50·100% | `s1p25.json`, `s1p50.json`, `s1c3.json` |
| 재현성 | `s1c3.json`, `s1repeat.json` — 출력 이름 외 설정 동일 |

이미 존재하는 run 디렉터리는 덮어쓰지 않는다. 이 기록의 실험은 모두 완료됐으므로 다시 실행할 때 `--name s1again`처럼 새 이름을 준다. sweep은 `--configs FILE FILE ...`도 지원하며 실패나 CUDA OOM에서 즉시 멈춘다. 자동 배치 축소·반복 재시도는 하지 않는다.

각 run은 다음 형태의 **실제 T14 명령**을 dev와 test에 실행했다. 정확한 argv는 각 `run.json`의 `eval_commands`에 있다. 학습 중 best-epoch 선정 AP도 `eval.metrics.binary_metrics()`를 사용했다. 지표를 별도로 구현하지 않았다.

```powershell
.\.venv\Scripts\python.exe -B -m eval.run --labels .local/train/ds1/dev.labels.jsonl --scores .local/train/s1c3/dev.scores.jsonl --windows .local/train/ds1/dev.windows.jsonl --utterances-dir .local/train/ds1/timing --out .local/train/s1c3/dev.eval.json
.\.venv\Scripts\python.exe -B -m eval.run compare .local/train/s0c0/dev.eval.json .local/train/s0c3/dev.eval.json .local/train/s1c3/dev.eval.json .local/train/s1c0/dev.eval.json .local/train/s1c1/dev.eval.json .local/train/s1l256/dev.eval.json .local/train/s1p25/dev.eval.json .local/train/s1p50/dev.eval.json .local/train/s1repeat/dev.eval.json
```

## 고정한 데이터와 커버리지

실행 시작 시점의 live 입력을 `.local/train/ds1/`에 한 번 고정하고 9개 run에서 공유했다. 진행 중인 라벨링·분할 변경을 실험 사이에 섞지 않았다. 입력 파일 해시, 스냅샷 파일 해시, 데이터 설정은 `snapshot.json`에 있다. 재사용 시 해시를 검증하며, 생성 도중 읽던 입력이 바뀌면 실패하고 재실행을 요청한다.

스냅샷 digest: `aff4dd0fc3b8ba577e490076d4e432de93600d95d632f3ae9e3fd7a52dff5d7b`.

라벨 디렉터리는 `.local/labels/agy-gemini-3.8-flash-low-first50`, 라벨러는 전부 `llm:agy:gemini-3.8-flash:low`였다. `data/gold/*.jsonl`은 0개였다. 파일명 `yt%3A<id>.jsonl`을 그대로 읽고 ID로 조인했다. `run.jsonl`, `unclear.jsonl`, `.chunks`는 라벨 입력에서 제외했다.

| split | 전체 창 | 라벨 있음 / 전체 | 커버리지 | skip | 사용 가능 | 미라벨 제외 | story>=2 | 전체/라벨 후 채널 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| train | 5,081 | 2,338 / 5,081 | 46.01% | 184 | 2,154 | 2,743 | 712 | 34 / 18 |
| dev | 491 | 491 / 491 | 100% | 59 | 432 | 0 | 133 | 5 / 5 |
| test | 96 | 96 / 96 | 100% | 1 | 95 | 0 | 16 | 1 / 1 |

최초 환경 확인 때보다 스냅샷 시점의 라벨이 늘었다. 결과의 기준은 위 스냅샷이다. 요청 당시 test가 거의 비어 있다는 설명과 달리 실행 시점에는 1채널 96창에 라벨이 있어 **9개 run 모두 test 점수와 T14 결과도 생성했다.** test를 학습·조기 종료·보정에 사용하지 않았다.

dev 양성 133개는 T14의 최소 양성 지원 수 5 이상이지만, 채널은 5개뿐이다. test는 양성 16개·1채널로 매우 작다. DESIGN.md §8의 dev/test 각각 10채널 이상 조건에 못 미친다. dev gold 에피소드는 40개, 측정 노출은 4,289.35초이고, test는 7개·1,187.87초다. 각 split의 타이밍 행 수는 창 수와 같았다. 시간당 오탐은 T14의 source 노출·skip 구간 제거 규약을 그대로 따른다.

희귀 라벨 지원 수도 제한적이다. train의 kind는 recall 355, moment 210, feeling 152, promise 20, family_fact 69이고 exclude는 garbled 120, external 13, sensitive 16이다. **train과 dev의 command·optout 양성은 0개**다. dev의 promise는 3개, feeling 10개, sensitive 6개이며 external도 0개다. 이 데이터로 해당 정책·희귀 플래그의 검출 성능을 검증할 수 없다. T14는 각 플래그 지원 수와 정의되지 않는 PR-AUC를 별도로 기록한다.

학습 곡선은 **라벨이 있는 train 18채널**을 대상으로 hash 순위의 중첩 부분집합을 골랐다(§8). dev는 동일하다.

| 설정 | 선택 채널 / 18 | 학습 행 | 양성 행 |
| --- | ---: | ---: | ---: |
| 25% | 5 | 348 | 58 |
| 50% | 9 | 1,059 | 327 |
| 100% | 18 | 2,154 | 712 |

채널 수는 올림한다. 비율은 전체 34채널이 아니라 라벨이 있는 18채널 기준이다. 라벨 도착 순서가 무작위 표집이 아니므로 부분 라벨의 편향과 채널 구성 효과가 섞여 있다.

## 잠정 결과

아래 지표는 모두 T14 결과 JSON에서 가져왔다. **dev n=432, 양성=133은 모든 행에서 동일하다.** AP는 T14의 average precision이다. best-F1과 에피소드 예산 재현율은 dev 점수에 대한 기술적 sweep 결과이며 채택한 운영 임계값이 아니다. T14의 기본 utterance 0.5, episode open/keep 0.5/0.3, pre-roll 3턴, 예산 3건/시간을 모든 run에서 유지했다. 입력 문맥 ablation에서도 에피소드 규칙은 동일하게 두어 분류기 입력만 바꿨다.

| run | 문맥 | 토큰 길이 | 학습 채널 | dev PR-AUC | dev best-F1 | 에피소드 재현율 @ ≤3 오탐/h | 학습 초 | best/실행 epoch |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| S0 `s0c0` | 0 | 문자 제한 | 18 | 0.606054 | 0.586301 | 0.425 | 4.97 | — |
| S0 `s0c3` | 3 | 문자 제한 | 18 | 0.513493 | 0.539326 | 0.200 | 4.46 | — |
| S1 `s1c3` (100%) | 3 | 160 | 18 | 0.578738 | 0.569921 | 0.125 | 97.74 | 2 / 4 |
| S1 `s1c0` | 0 | 160 | 18 | 0.680735 | 0.626263 | 0.450 | 150.10 | 4 / 6 |
| S1 `s1c1` | 1 | 160 | 18 | 0.601780 | 0.619048 | 0.200 | 70.77 | 1 / 3 |
| S1 `s1l256` | 3 | 256 | 18 | 0.571874 | 0.607955 | 0.525 | 72.41 | 1 / 3 |
| S1 `s1p25` | 3 | 160 | 5 | 0.540538 | 0.606860 | 0.325 | 21.60 | 2 / 4 |
| S1 `s1p50` | 3 | 160 | 9 | 0.548968 | 0.551253 | 0.475 | 43.24 | 2 / 4 |
| S1 `s1repeat` | 3 | 160 | 18 | 0.578738 | 0.569921 | 0.125 | 68.46 | 2 / 4 |

S1은 전 run에서 **14,059,276 파라미터 전부 학습**, `model.pt`는 **56,308,855 bytes = 53.7003 MiB**다. fp32 학습 체크포인트 크기이며 DESIGN.md §6의 int8 파일 추정이나 §7의 기기 PSS 측정값과 비교하면 안 된다. tokenizer/config/온도 파일은 별도로 있다. 9개 run의 학습 시간 합은 533.75초다. S1 시간은 epoch별 dev 검증·체크포인트 저장을 포함하고, 모델 로드·보정·최종 T14 호출은 제외한다. S0는 import를 포함한다. GPU를 다른 작업과 공유할 수 있고 실험 순서·캐시도 다르므로 이 시간으로 속도 우열을 단정하지 않는다.

| run | test PR-AUC | test best-F1 | test 에피소드 재현율 @ ≤3 오탐/h |
| --- | ---: | ---: | ---: |
| `s0c0` | 0.237863 | 0.313725 | 0.142857 |
| `s0c3` | 0.119447 | 0.288288 | 0.000000 |
| `s1c3` | 0.350985 | 0.400000 | 0.428571 |
| `s1c0` | 0.560562 | 0.714286 | 0.571429 |
| `s1c1` | 0.415921 | 0.545455 | 0.428571 |
| `s1l256` | 0.385820 | 0.535714 | 0.571429 |
| `s1p25` | 0.444531 | 0.560000 | 0.428571 |
| `s1p50` | 0.314647 | 0.450704 | 0.000000 |
| `s1repeat` | 0.350985 | 0.400000 | 0.428571 |

test는 n=95, 양성=16, 1채널이다. 이 표 역시 독립적인 최종 성능이나 통계적 유의성을 주장하지 않는다. 이번 silver dev에서는 문맥 0턴 S1의 AP가 가장 높았지만, 문맥 도움을 입증하지 못한 이유를 데이터·라벨·모델 효과로 분리할 수 없다. 학습량에 따른 AP는 증가했지만 한 번의 채널 부분집합과 같은 작은 dev만으로 확대 효과를 확정하지 않는다. DESIGN.md §10의 통과 기준에 맞추는 임계값 탐색이나 모델 재튜닝은 하지 않았다.

## 재현성 확인과 검증

`s1c3`와 `s1repeat`는 seed 42, 이름 외 resolved 설정, 코드 해시, 스냅샷 digest가 모두 같았다. 두 체크포인트를 CPU로 읽어 텐서를 직접 비교했고, dev score JSONL과 T14 AP도 비교했다. 기록은 `.local/train/repro.json`이다.

| 항목 | 결과 |
| --- | --- |
| 두 dev PR-AUC | 0.5787379943547626 / 0.5787379943547626 |
| 확인 기준 | AP 절대 차이 ≤ 1e-6 |
| 실제 AP 절대 차이 | **0.0** |
| primary score 최대 절대 차이 | **0.0** |
| 파라미터 최대 절대 차이 | **0.0** |
| 전체 dev story/exclude 점수 | 동일 |
| 온도·보정 진단 | 동일 |
| 기본 S1 온도 | 2.4860753682189367 |
| 기본 S1 dev NLL 보정 전 → 후 | 0.7218810467 → 0.6508281062 |

Python/NumPy/Torch/CUDA 시드를 고정하고, deterministic algorithms, cuDNN deterministic, eager attention, TF32 off, CPU thread 1, DataLoader worker 0을 사용했다. sweep은 자식 인터프리터 시작 전에 `PYTHONHASHSEED`도 설정한다. 이 결과는 같은 RTX 5060 Ti·같은 소프트웨어·같은 스냅샷 범위의 재현성이다. 다른 GPU나 버전 간 bitwise 일치는 측정하지 않았다.

실행 환경은 Python 3.12, torch 2.10.0+cu128, transformers 4.57.3, tokenizers 0.22.1, NumPy 2.2.6, scikit-learn 1.7.2, RTX 5060 Ti 16GB였다. 모델 revision은 `7488f8db0f208beff4a1f3f9bb3ed04650a89ed7`로 고정했다.

신규 CPU 단위 테스트는 **21개 통과**했다. 입력 순서·정규화·segment ID·실제 truncator 호출·현재 턴 왼쪽 자르기, skip/미라벨/gold 우선/가중치/여러 라벨러 조인, percent-encoded 파일과 sidecar 제외, 채널 부분집합 결정성, 문맥 dropout, 수계산 S0 특징, 가중 다중 손실·서열 목표·희귀 플래그 cap, toy 온도 보정, tiny random-init 인코더의 역전파·체크포인트 재로딩, 실제 eval CLI의 score 수용·compare를 확인했다. 테스트는 CPU만 쓰고 다운로드하지 않는다.

최종 전체 pytest는 **817 passed in 45.04s**였다. 9개 run 모두 dev/test score JSONL 및 T14 JSON/Markdown이 존재함을 확인했다. 비교표는 실제 `python -m eval.run compare`의 출력이다.

## 설계에 명시되지 않은 선택과 함정

- **DESIGN.md §9 손실**: 주 이진 BCE 1.0, 서열 soft BCE 0.2, kind BCE 0.1, exclude BCE 0.2로 주 헤드가 보조 합보다 크다. 각 헤드는 먼저 출력 차원 평균, 이후 배치 내 표본 가중 평균을 쓴다. gold 기본 가중치는 3, silver는 1이다. 모두 config로 변경한다.
- **§9 불균형**: S1은 primary 두 클래스를 역빈도 복원 표집하고, 표본 가중치는 손실에서 적용한다. kind/exclude의 훈련 neg/pos 비율은 최소 1·최대 20으로 제한한다. 양성 0개인 플래그는 weight 1로 두며 없는 양성을 만들어내지 않는다. 가중치는 해당 채널 부분집합의 train에서만 구한다.
- **§9 최적화**: AdamW lr 5e-5, weight decay 0.01(편향·LayerNorm 제외), 계획된 전체 step의 10% warmup 후 선형 decay, batch/eval batch 8, 최대 8 epoch, AP patience 2, gradient norm 1, head dropout 0.1이다. pooled CLS에 4개 선형 헤드를 붙인다. full fine-tuning이다.
- **§9 문맥 탈락**: 예제·epoch·seed로 결정한 k를 0–3에서 뽑고, 보유 턴 수로 제한해 임의 위치를 제거한다. 남은 턴 순서는 보존한다. 턴이 1개면 k≥1일 때 그 턴이 제거되는 규약이다. 원본 창 파일을 수정하지 않는다. skip은 목표 행에서 제외하고, 다른 유효 행의 입력 문맥은 제공된 창을 그대로 쓴다.
- **§9 보정/빈 dev**: primary logit만 dev의 비가중 NLL로 온도 보정한다. T 범위는 0.05–20, 단일 클래스 dev는 T=1, dev가 비면 실행 오류다. dev 양성이 0이면 AP가 정의되지 않으므로 명시적으로 dev NLL로 best epoch를 정하고 메타데이터에 남긴다. 이번 실행에는 이 대체 규칙이 필요 없었다. 온도만 저장하며 운영 임계값을 학습하지 않는다.
- **§8·§9 라벨러 충돌**: 여러 silver 디렉터리는 각각 가중된 훈련 행을 만든다. 평가는 가중치가 가장 큰 silver를 택하고 동률이면 설정상 앞 디렉터리를 택한다. human gold가 있으면 그 ID의 silver를 전부 대체하며 gold skip도 우선한다. 평가 지표는 표본 가중치로 가중하지 않는다.
- **§6 S0**: 원래 `test/stt/tools/train-story-probe.py`, `benchmark-story-models.py`, `TinyStoryModel.java`를 읽어 특징을 재현했다. 원문 512 codepoint 제한 → 공통 정규화 → 공백·경계 `_` → 중복 없는 2–4그램 → train 문서 빈도 ≥2 → 알려진 특징 수의 제곱근 정규화다. 최적화는 fixture용 수동 SGD 대신 고정 seed의 L2 liblinear 로지스틱 회귀(C=1)다. ordinal은 soft BCE에 해당하는 양/음 복제 표본 가중치로 적합한다. 지원 클래스가 하나인 출력은 상수 확률이다. 기존 STT 파일은 수정하지 않았다.
- **S0 입력 길이**: 토큰 길이 160/256은 S1 설정이다. S0는 기존 512 원문 문자 제한을 유지한다. 문맥을 앞에 붙이는 S0c3에서는 긴 문맥이 현재 턴을 제한 밖으로 밀 수 있다. S0 파일에는 모든 보조 헤드·어휘를 JSON으로 저장하므로 예전 배포용 단일 story 84kB TSV와 크기를 직접 비교하지 않는다.
- **§8 분할과 노출**: 기존 창의 split을 사용하고 매니페스트는 channel ID를 공급한다. 채널 누수는 오류로 처리하지만 분할을 재생성하거나 매니페스트와 창의 split을 강제로 맞추지 않는다. 생성 중인 다른 작업의 결과는 새 스냅샷에 반영해야 한다. T14의 미라벨 노출·clip/무라벨 경계·고정 sweep·최소 지원 수·에피소드 매칭 규약을 그대로 사용한다.
- **§9·§10 범위**: Pleos 오류 통계 증강·일관성 항은 T10, ONNX/int8 및 보정 후 양자화 차이는 T15, 기기 PSS·지연·soak는 T16/T17에서 확인할 항목이다. 이 T11 결과로 이를 주장하지 않는다.

초기 단위 테스트에서 `.local/train` 부모가 없어 pytest 임시 경로 생성이 실패해 부모 생성 명령을 문서에 넣었다. 첫 S0 실행의 메타데이터 상대 경로 처리 오류도 수정했다. 그 미완료 출력은 `.local/train/s0c0-incomplete`에 보존하고 정식 S0를 다시 실행했으며 비교에는 완료 run만 포함했다. 실제 학습 중 CUDA OOM은 없었다. 커밋·브랜치·푸시는 하지 않았고 `pipeline/`, `eval/`, `common/`, live labels/windows/utterances를 수정하지 않았다.

## 산출물과 다음 재실행

- `.local/train/ds1/snapshot.json`: 커버리지·live 입력 provenance·동결 파일 해시. 텍스트가 있는 창은 이 `.local` 안에만 있다. timing 스냅샷은 source/utt/start/end만 보관한다.
- `.local/train/<run>/dev.scores.jsonl`, `test.scores.jsonl`: T14 형식의 `id`, 보정된 `story`, 5개 `exclude` 확률.
- 각 run의 `dev.eval.json/.md`, `test.eval.json/.md`, `*.eval.log`: T14 결과와 실행 출력.
- `resolved.json`, `run.json`, S1의 `history.json`: 설정·시간·지원 수·코드/데이터 해시·best epoch·버전·평가 argv.
- S1의 `model.pt`, `config.json`, tokenizer 파일, `student.json`, `temperature.json`: `train.engine.load_student(path)`로 재로딩하는 T15 입력.
- `.local/train/comparison.md`: T14가 만든 9개 모델의 병렬 비교표. `comparison.json`은 T14 결과의 주요 필드를 복사한 요약이며 지표를 다시 계산하지 않는다.
- `.local/train/repro.json`: 동일 시드 재현성 검사.

라벨링 완료, gold 추가, split 비율·채널 배치 변경, 창 재생성 후에는 **기존 ds1을 고치지 않고** 새 스냅샷과 새 run 이름을 쓴다. 기본 single run 예:

```powershell
.\.venv\Scripts\python.exe -B -m train.run --config train/configs/s1c3.json --snapshot .local/train/ds2 --name s1new
.\.venv\Scripts\python.exe -B -m train.run --config train/configs/s1c3.json --snapshot .local/train/ds3 --name s1multi --silver .local/labels/LABELER_A 1 --silver .local/labels/LABELER_B 0.5 --gold-weight 3
```

전 실험을 다시 비교하려면 run 이름을 새로 정한 설정 목록에 `train.sweep --list ... --snapshot .local/train/ds2`를 실행한다. 동일한 새 데이터로 S0 두 변형, S1 문맥·길이·채널 곡선, 동일 시드 반복을 함께 다시 계산한다. 특히 부분 라벨인 train의 학습 곡선은 라벨 완료 후 재검증해야 한다. human gold와 dev/test 채널 확대, Pleos/D 평가가 확보되기 전에는 DESIGN.md §10 통과를 판정하지 않는다.

요청된 T11 구현·실험·재현성·테스트·작업 기록 완료 조건에서 남은 항목은 없다. 데이터의 gold 부재·작은 dev/test·희귀 정책 플래그 부재는 위 결과의 한계로 남는다.
