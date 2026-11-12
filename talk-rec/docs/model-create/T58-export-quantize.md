# T58 내보내기와 양자화

2026-11-12. DESIGN §6.1의 2026-11-12 결정에 따라 **M1/2978/seed43만** 내보냈다. M0 산출물은 만들지 않았다. ONNX fp32는 PC torch 원본과 수치상 매우 가깝지만, int8은 확률까지 같지는 않다. 원래 문턱에서는 실제 dev 판정이 모두 같고 합성 val 7개의 판정이 바뀌었다. 같은 규칙으로 int8 문턱을 다시 정했으며, 이 문턱에서는 dev 판정 12개가 PC 원본과 달라진다. 이 차이까지 T59에 전달한다. 커밋하지 않았다.

## 고정 후보와 환경

- 가중치: `model-create/.local/train/v3/t55-v3-20261112/M1/2978/s43/weights/model.safetensors`.
- 학습 규모 2,978장, 학습 시드 **43**은 사전 고정이다. 다른 시드 가중치를 읽거나 dev로 시드를 고르지 않았다.
- `results.json`의 `models.M1.2978.per_seed.43.threshold`: **0.9573729634284973**. 저장된 `numbers.json`, 합성 val 정답, `preds.jsonl`의 id 순서와 복원 결과도 확인했다.
- 최종 int8 문턱: **0.9783868789672852**, 판정은 `keep_probability >= threshold`다.
- 합성: T55와 같은 `.local/synth/t53`, 부분표본 시드 55, 장면·pair 분할 시드 43. train 2,382 / val **596**. 실제 평가는 dev **77**개만, 주 집단 child_family **37**개(keep 25 / none 12), unsure 포함이다. test를 열지 않았다.
- Python 3.12, torch **2.10.0+cu128**, transformers **4.57.3**, tokenizers **0.22.1**, onnx **1.19.1**, onnxruntime **1.23.2**, numpy **2.2.6**, safetensors **0.8.0**. 비교·속도 측정은 CPU에서 수행했다.
- 기존 설치 패키지를 사용했다. 추가 설치와 venv 변경은 없다. T55가 별도 설치한 `.local/train/v3-runtime`의 Pillow 13.0.0 / torchvision 0.25.0 보정을 그대로 사용했다.
- HF 오프라인, `local_files_only=True`, `trust_remote_code=False`. 저장 가중치는 safetensors만 읽었으며 pickle·bin·pt 로드는 하지 않았다. 원본 가중치 해시는 `.local/export/m1/results.json`에 기록한다.

## 실행 명령

작업 디렉터리는 `C:\github\drive-diary\talk-rec\model-create`다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
$env:PYTHONPATH = (Resolve-Path '.local/train/v3-runtime').Path

.\.venv\Scripts\python.exe -X utf8 -B -m pytest tests/test_train_v3_export.py --basetemp C:/Users/ip936/AppData/Local/Temp/drive-diary-t58-c -p no:cacheprovider -q --tb=short

New-Item -ItemType Directory -Force .local/export/m1 | Out-Null
# cmd의 바이트 리다이렉션으로 Python UTF-8 stdout/stderr를 그대로 저장한다.
cmd /d /c ".\.venv\Scripts\python.exe -X utf8 -B -m train.v3.export > .local\export\m1\run.log 2>&1"

# 문서와 닫힌 실행 로그까지 포함해 마지막으로 검사한다.
.\.venv\Scripts\python.exe -X utf8 -B -m train.v3.export --audit-only
```

`train/v3/export.py`가 내보내기, 두 양자화 변형, 전 데이터 비교, 문턱 보정, 골든 파일, 별도 프로세스 자원 측정, 비노출 검사를 수행한다. 기본 출력은 `.local/export/m1/`다. 같은 명령을 다시 실행하면 해당 내보내기 산출물을 다시 만든다.

## 내보내기와 양자화

opset **17**, 입력 `input_ids`, `attention_mask`는 **int64 [batch, 256]**다. 시퀀스 축은 고정이고 배치 축만 동적이다. 배치 1로 실제 비교·측정했으며 단위 테스트에서 배치 2도 확인했다. `token_type_ids` 입력은 없으며 인코더가 전부 0인 단일 segment를 사용한다.

출력 `keep_probability`는 **float32 [batch]**, 독립 이진 head의 sigmoid다. `kind_logits`는 **float32 [batch, 3]**, 순서는 **none / story / moment**다. 종류의 softmax나 `1 - P(none)`으로 keep 확률을 대신하면 안 된다. 추론은 eval 모드, encoder 마지막 은닉 상태의 CLS 위치를 두 head에 넣는다.

추가 exporter 의존성 없이 설치된 torch의 `dynamo=False` 경로로 내보냈다. 이 경로는 deprecation 경고를 내지만 ONNX checker와 현재 ORT CPU 실행을 통과한다. `onnxruntime.quantization.quantize_dynamic`, `weight_type=QInt8`, `per_channel=True`, `MatMulConstBOnly=True`를 사용했다.

탑재 모델 `model.int8.onnx`는 상수 가중치를 갖는 MatMul/Gemm과 Gather 임베딩을 양자화한다. ORT 1.23.2의 Gather는 `QInt8` 지정에도 임베딩을 uint8로 저장하므로, **임베딩 값과 영점을 함께 128씩 빼 signed int8로 재표현**한다. `(u-z) = ((u-128)-(z-128))`로 복원값과 scale은 보존되며, 초소형 모델에서 전환 전후 확률이 완전히 같은지도 검사했다. 동적 activation은 ORT CPU 커널의 uint8을 유지한다. bias, LayerNorm, 비선형 연산, 상수 가중치가 없는 attention MatMul 등은 fp32다.

비교 변형 `model.int8-fp32-embeddings.onnx`는 Gather 임베딩을 fp32로 남기고 MatMul/Gemm만 양자화한다. 탑재 변형은 임베딩까지 양자화한 모델로 고정했으며, fp32 임베딩 변형은 크기가 크고 이번 비교의 일치도도 개선하지 못했다.

## 확률과 판정 일치

차이는 배치 1의 **PC torch CPU 원본** 대비다. 판정 문턱은 이 표에서 모두 원래 T55 문턱 `0.9573729634284973`이다. 평균은 묶음별 절대차의 산술평균이다.

| 자료 | ONNX 변형 | 최대 절대차 | 평균 절대차 | keep 판정 일치 | 뒤집힘 |
|---|---|---:|---:|---:|---:|
| 합성 val 596 | fp32 | 0.000007421 | 0.00000009715 | 100% | 0 |
| 합성 val 596 | int8 | 0.445029646 | 0.008956201 | 98.8255% | 7 |
| 합성 val 596 | int8, 임베딩 fp32 | 0.616802484 | 0.010587325 | 98.8255% | 7 |
| 실제 dev 77 | fp32 | 0.000005186 | 0.0000002240 | 100% | 0 |
| 실제 dev 77 | int8 | 0.347477436 | 0.020242257 | 100% | 0 |
| 실제 dev 77 | int8, 임베딩 fp32 | 0.414656132 | 0.024372624 | 96.1039% | 3 |

ONNX fp32↔int8 직접 비교의 최대/평균 절대차는 val **0.445034057 / 0.008956241**, dev **0.347477496 / 0.020242210**이다. 판정 일치와 뒤집힘 수는 위 torch↔int8과 같다.

T55에 저장된 GPU 예측↔이번 torch CPU의 최대/평균 차이는 val **0.000008896 / 0.00000007208**, dev **0.000006258 / 0.0000002451**, 원래 문턱의 판정 일치율은 둘 다 **100%**다. 환경·배치 차이에 따른 작은 부동소수점 차이가 있으며, int8의 큰 차이와는 규모가 다르다.

원래 문턱에서 뒤집힌 id만 적는다.

- val, int8: `s0/synth:598@0`, `s0/synth:870@0`, `s0/synth:1417@0`, `s0/synth:2188@0`, `s0/synth:2212@0`, `s0/synth:2581@0`, `s0/synth:2597@0`.
- val, 임베딩 fp32: `s0/synth:598@0`, `s0/synth:870@0`, `s0/synth:1071@0`, `s0/synth:2188@0`, `s0/synth:2212@0`, `s0/synth:2581@0`, `s0/synth:2597@0`.
- dev, 임베딩 fp32: `yt:0rqGBKrEao4#2`, `yt:35nWrWFsa2M#5`, `yt:pkGAxu0g0vQ#0`.
- fp32는 val/dev 모두 없고, 탑재 int8의 dev 뒤집힘도 없다.

### child_family dev와 int8 문턱

T55와 동일한 `eval.v3.metrics.select_threshold`를 int8 확률에 적용했다. keep 재현율 ≥0.85를 먼저 만족시키고 none 걸러 내기를 최대화한다. 동률이면 재현율 최대, 0.5와의 거리 최소, 낮은 문턱 순이다. 만족할 후보가 없을 때만 재현율 최대·걸러 내기 최대 순으로 처리한다. unsure를 포함한 37묶음으로 보정하며, 참고 집단에도 이 문턱을 그대로 쓴다.

| 모델 / 문턱 | keep 재현율 | none 걸러 내기 | 정확도 | AUROC |
|---|---:|---:|---:|---:|
| torch / 원래 문턱 | 0.960000 | 0.583333 | 0.837838 | 0.813333 |
| ONNX fp32 / 원래 문턱 | 0.960000 | 0.583333 | 0.837838 | 0.813333 |
| ONNX int8 / 원래 문턱 | 0.960000 | 0.583333 | 0.837838 | 0.793333 |
| int8, 임베딩 fp32 / 원래 문턱 | 0.920000 | 0.583333 | 0.810811 | 0.780000 |
| **탑재 int8 / 보정 문턱 0.9783868789672852** | **0.880000** | **0.666667** | **0.810811** | **0.793333** |

원래 문턱의 int8은 재현율·걸러 내기·정확도 변화가 0이며 AUROC가 **−0.020000**이다. 임베딩 fp32 변형은 각각 **−0.040000 / 0 / −0.027027 / −0.033333**이다. 탑재 문턱까지 적용한 int8은 torch 대비 **−0.080000 / +0.083333 / −0.027027 / −0.020000**이다. 보정 후 keep 22/25, none 8/12, 정답 30/37이다. 재현율 0.85 제약을 지키며 걸러 내기를 최대화한 결과이며 정확도를 최대화한 문턱은 아니다. 여기의 단일 seed 수치를 T55의 시드 3개 평균과 혼동하지 않는다.

보정 문턱의 int8↔원래 문턱 torch 일치율은 **val 89.7651%(61개 뒤집힘), dev 84.4156%(12개 뒤집힘)**이다. 양자화 확률 오차와 문턱 변경 효과를 구별해야 한다. 두 문턱에서의 전체 뒤집힘 id는 `.local/export/m1/results.json`의 `datasets.*.int8_recalibrated_vs_torch.flipped_ids`에 있으며 전사 글은 없다. dev 12개 id는 다음과 같다.

`yt:0rqGBKrEao4#2`, `yt:0rqGBKrEao4#3`, `yt:35nWrWFsa2M#5`, `yt:CzItgxWOjhM#5`, `yt:IOlxH67P1JM#1`, `yt:P5vMsReRNKo#1`, `yt:eicKfWnR3cA#1`, `yt:eicKfWnR3cA#2`, `yt:eicKfWnR3cA#6`, `yt:eicKfWnR3cA#9`, `yt:oXOzAbZjFPY#0`, `yt:pkGAxu0g0vQ#0`.

## 파일 크기·CPU 속도·RSS

MB는 10^6 bytes이며, ONNX 파일 자체 크기다. 각 모델마다 새 Python 프로세스를 사용한다. ORT CPUExecutionProvider, intra/inter-op 1, sequential, 배치 1, 고정 256 토큰이다. T55와 같은 창작 입력 `오늘 바람이 시원해\n다음에 같이 산책 가자\n`을 40번 반복했다. 각 측정은 준비 3회 후 15회 중앙값이고 샘플도 `results.json`에 보존한다.

| 변형 | ONNX MB | 토큰화 제외 ms | 토큰화 포함 ms | 로드 직후 RSS 증가 MB | 준비·추론 뒤 RSS 증가 MB |
|---|---:|---:|---:|---:|---:|
| fp32 | 56.452379 | 55.843 | 57.495 | 76.022 | 82.522 |
| int8 | 14.731423 | 24.588 | 25.212 | 31.576 | 38.412 |
| int8, 임베딩 fp32 | 28.367229 | 24.066 | 25.604 | 45.851 | 52.748 |

토큰화 포함 측정은 입력 글→토큰화→both 절단→패딩→NumPy 배열→ORT까지다. 제외 측정은 배열을 미리 만들고 ORT 호출만 잰다. 입력 글 조립, STT, 파일 I/O는 속도에 포함하지 않는다. RSS 기준점은 torch/토크나이저를 준비한 뒤, ORT import와 첫 세션 생성 전이다. 로드 증분에는 ORT 초기화와 모델 세션이 들어가며, 준비 뒤 증분에는 추론용 버퍼도 들어간다. 전체 Python 프로세스 RSS를 기기 모델 메모리로 해석하면 안 된다.

## T59 기기 이식 자료

모두 `C:\github\drive-diary\talk-rec\model-create\.local\export\m1\`에 있다.

| 파일 | 형식과 용도 |
|---|---|
| `model.int8.onnx` | 탑재 모델, 위 입력·출력 계약, opset 17 |
| `vocab.txt` | UTF-8, 한 줄 한 토큰, 줄 번호 0부터 token id |
| `tokenizer.json` | 학습 때 사용한 fast tokenizer의 정규화·분절·WordPiece·후처리 정의 |
| `tokenizer_config.json`, `special_tokens_map.json` | 원본 토크나이저 설정과 특수 토큰 |
| `tokenizer_settings.json` | 실제 입력 조립, 정규화, both 절단, 패딩, 자료형·shape를 명시한 이식 계약 |
| `threshold.json` | 탑재 int8 문턱, 원래 문턱, 비교 연산, 보정 집단과 선택 규칙 |
| `labels.json` | 종류 logit 순서 `none, story, moment`, 독립 keep head 설명 |
| `golden.json` | schema_version 1, 직접 만든 한국어 가족 대화 20개, 각 `id`, `lines`, `input_text`, 길이 256의 `input_ids`·`attention_mask`, int8 `keep_probability`, 길이 3의 `kind_logits` |
| `manifest.json` | 탑재 모델과 위 이식 자료의 SHA-256, 후보·opset 식별 |
| `model.fp32.onnx`, `model.int8-fp32-embeddings.onnx` | PC 검증·비교용 모델 |
| `results.json` | 버전, 후보 가중치 해시, 일치·지표·속도·RSS·비노출 검사 결과 |
| `predictions.json` | 자료 구분, id, torch/fp32/두 int8 확률만 기록한 673개 숫자 행 |
| `run.log` | 명령 진행 수치와 패키지 경고, 전사 글 없음 |

입력 글은 **실제 `demo.trial.data.input_text`의 동작**에 맞춘다. 원래 순서의 줄 목록을 받아 문자열이면 그대로, 객체면 `text`만 취한다. 각 줄의 앞뒤 Unicode 공백을 `strip()`한 뒤 **U+000A LF 한 개**로 연결한다. 빈 줄도 유지하고 끝에 별도 LF를 붙이지 않는다. 줄 안의 글·문장부호·대소문자는 보존한다. 화자·시각·정답·pattern·level·clean 전사 등은 추가하지 않는다.

M1 입력에 `common.normalize.normalize`를 추가 적용하면 안 된다. 현재 T55 M1 경로에는 그 함수의 NFKC·소문자화·기호 제거가 없고, 저장된 **BertNormalizer**를 사용한다. `clean_text=true`, `handle_chinese_chars=true`, `lowercase=false`, `strip_accents=null`이다. 이 설정에서는 대소문자와 악센트를 유지하고 NFKC를 적용하지 않는다. Bert clean_text는 제어 문자를 제거하고 공백을 정리하며, BertPreTokenizer가 공백과 문장부호를 분절한다. 정확한 Unicode 처리는 `tokenizer.json`을 기준으로 구현한다. WordPiece는 `##` 연속 접두사, 단어 최대 100글자, 실패 시 `[UNK]`다. LF도 토크나이저에서 공백으로 처리되며 별도의 줄 토큰을 만들지 않는다.

**both의 정확한 정의**: 먼저 절단 없이 특수 토큰을 포함해 전체 토큰화하고 special_tokens_mask를 받는다. 후처리가 추가한 특수 토큰 위치는 모두 유지한다. 본문 예산은 `256 - 특수 토큰 수`다. 본문이 예산을 넘으면 앞 `ceil(예산/2)`개와 뒤 `floor(예산/2)`개 본문 토큰만 원래 순서로 남긴다. 일반 입력에서는 `[CLS]=2`와 `[SEP]=3`이므로 앞 **127** + 뒤 **127**, 합계 256이다. 짧으면 오른쪽을 `[PAD]=0`으로 채운다. 실제 토큰의 attention_mask는 1, PAD는 0이며 모든 입력은 int64다. `[UNK]=1`, `[MASK]=4`다. 단순 head-only 절단이나 글자 수 절단으로 대체하지 않는다.

골든 20개는 모두 이번에 직접 쓴 창작 대화다. 대소문자·전각 문자·악센트가 포함된 예와 256토큰을 넘는 긴 창작 예도 넣었다. 실전사에서 문장을 가져오지 않았다. T59는 먼저 token id와 mask의 완전 일치를 확인하고, 모델·토크나이저·문턱 해시를 맞춘 다음 확률을 비교한다. JSON의 권장 절대오차 `1e-4`는 초기 점검 기준이며, 다른 아키텍처·ORT 버전의 동등성을 보증하지 않는다.

## 검증과 개인정보

`tests/test_train_v3_export.py`의 **4개 테스트 통과**. 초소형 랜덤 Electra를 safetensors로 저장·복원하고, fp32·두 int8 내보내기, ONNX checker, 두 출력과 동적 배치, signed 임베딩의 복원값 보존, both 앞뒤 토큰·LF·대소문자, 20개 골든 재실행, 문턱 반전·보정, 반환형 오류와 예외 비노출을 검증했다. pytest 임시 폴더는 저장소 밖이며 cacheprovider는 껐다.

실제 자료는 `eval.real.load_labels` 호출 결과를 **출력 전에 코드로 검사**한다. `(selected_blocks, {annotator: {id: label}})` 튜플과 구성원 자료형을 확인한 뒤에만 사용한다. 경고 출력은 격리하고, 예외도 형식명과 코드 위치만 남긴다. 전사문은 메모리에서만 토큰화·비교에 쓰며 저장하지 않는다. 실제 결과 직렬화는 id와 수치만 허용한다.

최종 비노출 검사에서는 원본 묶음 글 및 20글자 이상 전사 줄 **426개 조각**과 대조하여 저장소의 텍스트 파일, 생성 JSON·vocab·로그·이 문서 등 **261개 파일에서 일치 0건**을 확인했다. UTF-8/UTF-16과 JSON Unicode 이스케이프도 검사한다. 짧은 흔한 발화는 우연히 창작 예·코드와 겹칠 수 있어 이 문자열 감사의 대상으로 삼지 않는다. 대신 실제 입력 필드를 산출물에 넣지 않는 구조와 가짜 비밀문장 단위 테스트로 보호한다. `.local` 전체를 공개 파일로 스캔하지 않으며, 원본 사유 자료는 그 안에 계속 둔다. 검사 파일 수와 비교 조각 수는 `results.json.privacy_audit`에 기록한다.

## 한계와 T59로 넘길 판단

int8의 최대 확률 오차는 val 약 **0.445**, dev 약 **0.347**로 커서 확률 수치가 같은 모델이라고 말할 수 없다. 원래 문턱에서 dev 판정 일치 100%만으로 확률 보존을 주장하면 안 된다. 보정 문턱은 같은 dev에서 none 걸러 내기를 높이는 대신 keep 재현율과 원본 판정 일치를 낮춘다. 실제 dev가 작고, 같은 dev로 문턱을 보정하므로 낙관 편향이 남는다. 이번 수치는 고정 seed 하나이며 신뢰구간·재학습 불확실성을 추가 추정하지 않았다.

속도와 RSS는 **Windows PC CPU 측정**이다. Android/ARM에서의 연산 지원, 토큰화·확률 일치, PSS·p95 지연·CPU와 DESIGN §7 예산 통과 여부는 **T59**에서 별도로 확인해야 한다. 일반 Python RSS 증분을 Android PSS와 직접 비교하지 않는다. 통합·soak는 T60, 최종 test 평가는 별도 절차로 남긴다.
