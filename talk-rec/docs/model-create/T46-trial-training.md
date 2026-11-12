# T46: 합성 대화로 묶음 분류 시험 학습

`model-create/demo/trial/`은 합성 대화로 학습하고, 합성 val 및 사람이 판정한 실제 Pleos STT 묶음으로 평가하는 오프라인 데모다. 주 과제는 **keep(story 또는 moment) vs none**이다. story와 moment를 구분하는 3분류 정확도는 보조 지표다. 실제 전사 글은 이 문서나 보고서에 기록하지 않는다. 커밋하지 않았다.

## 구현

- `ngram`: 문자 1~3 gram TF-IDF(최대 특징 100,000개, sublinear TF) + 로지스틱 회귀. 사전 학습 모델이나 다운로드 불필요. 특징 추출기와 회귀는 합성 train에만 fit한다.
- `shape`: 줄 수, 총 글자 수, 줄 길이 중앙값·평균·표준편차·최솟값·최댓값만 사용한다. StandardScaler + 로지스틱 회귀. 단어 의미·시간·화자·pattern·boundary·level은 사용하지 않는다.
- 인코더: `monologg/koelectra-small-v3-discriminator`, `klue/roberta-small`, `alphaedge-ai/mmBERT-small-kor-32768` 또는 임의의 Hugging Face 이름. `AutoTokenizer`/`AutoModel`에 `.local/hf`, `local_files_only=True`, `trust_remote_code=False`를 명시하고 offline 환경 변수도 켠다. 원격 Python 코드는 실행하지 않는다. 설치된 transformers가 지원하는 아키텍처와 로컬 자산이 필요하다.
- 현재 mmBERT 캐시는 transformers 5 형식이다. 4.57에서 모르는 `TokenizersBackend` 이름에 한해 내장 `PreTrainedTokenizerFast`로 동일 tokenizer.json을 읽는다. special token은 직렬화된 postprocessor의 mask로 보존한다. ModernBERT의 새 `rope_parameters`는 4.57이 사용하는 global/local RoPE theta로 옮겨 기본값으로 잘못 읽히는 것을 방지한다. 기본 RoPE와 주기적인 full/sliding attention 배치만 호환하며 다른 설정은 중단한다. ModernBERT는 SDPA와 `reference_compile=False`로 외부 FlashAttention/컴파일 없이 실행한다.
- 인코더 전체를 미세조정한다. 첫 토큰 표현에 binary head와 보조 3분류 head를 붙인다. 손실은 binary BCE + 0.2 × 3분류 cross entropy, AdamW(weight decay 0.01), gradient clip 1.0, 기본 3 epoch/학습률 3e-5/batch 8이다. 고정 epoch의 마지막 가중치를 평가한다. T11의 발화·문맥·다중 라벨 엔진은 목적과 입력이 달라 재사용하지 않았다.
- 선형 모델도 이진/보조 회귀를 별도로 학습한다. 보조 예측은 이진 문턱과 독립적이므로 서로 다를 수 있다.
- `data.input_text()` 한 함수가 합성 `turns`와 실제 `lines[].text`에 동일하게 적용된다. 각 줄 양끝 공백을 없애고 줄바꿈으로 잇는다. 깨끗한 원문·화자·정답 필드는 모델 입력에 섞이지 않는다.
- 합성 `source=synth:<카드 번호>`로 장면을 묶고, 원래 3라벨로 층화해 train/val 80/20을 나눈다. 기본 분할 시드는 43, 학습 시드는 43/44/45이다. 분할은 모든 모델과 학습 시드에 동일하다. 디렉터리를 넘어 같은 카드 번호는 같은 분할에 둔다. 동일 번호의 정답이 충돌하면 중단한다. 서로 독립적인 카드 집합이라면 먼저 번호 체계를 정리해야 한다.
- 없는 합성 디렉터리, blocks/labels가 아직 없는 디렉터리, `meta.json`의 `complete=false`는 건너뛴다. 이미 있는 blocks/labels의 ID 불일치·중복은 오류로 처리한다. 생성과 export가 끝난 뒤 실행하는 편이 재현에 적합하다.
- 실제 데이터는 `judge.ids`에 있는 항목만 순서대로 쓰며, 기본 `config=pleos`만 선택한다. 평가 대상 ID의 중복·누락은 오류다. 실제 데이터는 fit, 문턱 선택, epoch 선택에 쓰지 않는다.

## 그대로 실행할 PowerShell 명령

모든 명령은 아래 작업 폴더에서 실행한다. 출력 이름이 이미 존재하면 덮어쓰지 않고 중단한다. 다시 실행할 때에는 새 이름을 쓴다. 두 실험을 비교할 때에는 합성 생성/export가 끝난 동일한 파일을 사용한다. 실행 사이 demo3가 완성되면 데이터가 달라지므로 기준선도 새 이름으로 다시 실행한다. `--models`에 다섯 후보를 한꺼번에 넣으면 한 실행의 동일한 메모리 내 입력·분할로 비교한다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create

# 먼저 기준선 두 가지: 다운로드/GPU 불필요, 기본 학습 시드 3개
.\.venv\Scripts\python.exe -m demo.trial.run `
  --models ngram shape `
  --synth-dir .local/synth/demo2 `
  --synth-dir .local/synth/demo3 `
  --run-name t46-baselines-local

# 다음 인코더 3종: 로컬 HF 캐시에 필요한 자산이 있어야 함
.\.venv\Scripts\python.exe -m demo.trial.run `
  --models monologg/koelectra-small-v3-discriminator klue/roberta-small alphaedge-ai/mmBERT-small-kor-32768 `
  --synth-dir .local/synth/demo2 `
  --synth-dir .local/synth/demo3 `
  --cache .local/hf `
  --device cuda `
  --epochs 3 --learning-rate 3e-5 --batch-size 8 `
  --max-length 256 --truncation both `
  --seeds 43 44 45 --split-seed 43 `
  --run-name t46-encoders-local
```

`--models` 뒤에 임의의 HF 이름도 넣을 수 있다. GPU 메모리가 부족하면 batch 크기를 줄인다. CPU 학습은 `--device cpu`, 자동 선택은 `--device auto`다. **이번 작업에서는 실제 인코더 3종의 학습을 실행하지 않았다.** 사용자가 위 명령을 실행해야 한다. 현재 로컬 캐시의 Electra/Roberta config·tokenizer 읽기를 확인했고, mmBERT는 호환 처리 후 캐시의 실제 가중치 로드·창작 입력 1개 CPU 추론 성공까지 확인했다(학습 없음).

HF 캐시가 비었다면, 네트워크가 되는 사용자 환경에서 다음 준비 명령을 **별도로** 한 번 실행한다. 학습 CLI 자체는 다운로드하지 않는다. 저장소 접근 권한·존재 여부와 설치된 transformers의 아키텍처 지원 여부도 이 단계에서 확인한다.

```powershell
@'
from huggingface_hub import snapshot_download
from transformers import AutoConfig
from demo.trial.models import load_tokenizer
names = [
    'monologg/koelectra-small-v3-discriminator',
    'klue/roberta-small',
    'alphaedge-ai/mmBERT-small-kor-32768',
]
for name in names:
    snapshot_download(name, cache_dir='.local/hf', allow_patterns=[
        '*.json', '*.txt', '*.model', '*.safetensors', 'pytorch_model.bin'
    ])
    AutoConfig.from_pretrained(name, cache_dir='.local/hf', local_files_only=True, trust_remote_code=False)
    load_tokenizer(name, '.local/hf')
    print(name, 'cache ready')
'@ | .\.venv\Scripts\python.exe -
```

최대 토큰 수는 special token을 포함해 256 이하로 제한한다. 기본 `--truncation both`는 내용 토큰의 앞·뒤 절반씩 남기며, `head`는 앞, `tail`은 뒤를 남긴다. 패딩은 선택한 최대 길이까지 한다. 이 설정을 바꾸면 실험 이름도 바꾸어 비교한다.

가중치가 필요하면 명령 끝에 `--save-weights`를 추가한다. 기본은 저장하지 않는다. 켜면 `weights/m<모델 순서>/s<시드>/` 아래 선형 모델은 `model.pkl`, 인코더는 `model.pt`와 config/tokenizer를 저장하고, `trial.json`에 모델 이름·문턱·입력 설정·라벨 순서를 함께 남긴다. pickle은 직접 만든 신뢰할 수 있는 파일만 불러와야 한다. 결과는 모델·시드마다 처리하므로 인코더 여러 개를 동시에 GPU에 올리지 않는다.

## 출력과 지표

`.local/demo/trial/<run-name>/` 아래에 다음을 저장한다.

- `results.json`: 모든 값은 숫자 또는 null(키는 모델명/지표명). 설정의 truncation 코드는 both=0/head=1/tail=2. 개수·시드별 결과·평균·표준편차·실제 bootstrap 구간·측정 수치를 담는다.
- `report.md`: 합성 val/실제 평가 지표, 혼동 행렬, 크기·속도 표. 실제 표에 작은 표본과 넓은 구간을 표시한다.
- `preds.jsonl`: model, seed, split(train/val/real), id, probability(keep 확률), prediction/gold(keep/none), prediction3/gold3. 글은 없다. 합성 ID의 `s0/`, `s1/`은 `--synth-dir` 순서를 뜻한다. train 예측은 점검용이며 보고서의 성능 표에는 포함하지 않는다. 중간 실패 시 완료된 시드의 예측 파일만 남을 수 있고, 완성된 실행에는 세 출력 파일이 모두 있다.

정확도=(TP+TN)/전체, keep 재현율=TP/(TP+FN), keep 정밀도=TP/(TP+FP). keep 예측이 없으면 정밀도는 0이다. AUROC는 keep 확률 순위로 계산하며 동점은 0.5승으로 센다. 정답에 두 클래스가 없으면 null이다. 혼동 행렬은 행 none/keep, 열 none/keep 순서로 `[[TN, FP], [FN, TP]]`다.

각 학습 시드의 **합성 val에서 balanced accuracy=(keep 재현율+none 재현율)/2를 최대화하는 문턱**을 고른다. 후보는 관측 확률과 0/0.5/1 및 1보다 아주 조금 큰 값(전부 none 선택)이다. 동률이면 0.5에 가까운 값, 그다음 낮은 값을 택한다. 비교는 probability≥threshold이다. 이 문턱을 실제 평가에 그대로 적용한다. 합성 val의 문턱 적용 정확도에는 같은 val에서 문턱을 고른 낙관 편향이 있다.

학습 시드 3개의 평균 ± 표본 표준편차(ddof=1)를 표시한다. 선형 모델은 최적화가 사실상 결정적이므로 시드 표준편차가 0일 수 있다. split seed를 바꾸어 생기는 불확실성을 나타내는 수치는 아니다.

실제 평가는 묶음 20개를 복원 추출하는 비모수 bootstrap 2,000회, 시드 1046의 percentile 95% 구간을 붙인다. 각 재표본에서 학습 시드별 지표의 평균을 계산하고, 모든 모델에 같은 추출 인덱스를 적용한다. 구간은 **시험 표본의 불확실성**이며 모델 재학습이나 문턱 재선택을 하지 않는다. 단일 클래스 표본의 AUROC 및 keep 정답이 없는 표본의 재현율은 제외하고 유효 횟수를 `ci_valid`에 기록한다. 전부 keep 기준선의 재현율 구간처럼 퇴화한 구간도 있어, 작은 표본에서 일반화가 확실하다는 뜻으로 읽으면 안 된다.

기준선은 전부 keep과 `judge.pred['pleos']`의 Gemini 판정이다. Gemini에는 확률이 없으므로 keep=1/none=0의 hard score로 AUROC를 계산한다. 확률 모델의 순위 분해능과 직접 같지는 않다. 전부 keep은 story/moment를 구분하지 않으므로 보조 3분류 정확도는 null이다.

## 크기·속도 해석

각 모델·시드는 별도 프로세스에서 측정한다. CPU 단일 스레드, batch 1, 준비 3회 후 기본 15회 추론의 중앙값(ms)을 기록한다. 토큰화/특징 변환도 시간에 포함한다. 인코더는 창작 글을 최대 길이(기본 256토큰)로 패딩하며 ngram/shape에는 토큰 예산이 없어 같은 창작 글 전체를 사용한다. 모델 로드는 시간에 포함하지 않는다.

파라미터 수에는 두 head를 포함한다. 선형 모델은 두 회귀의 계수·절편 수이며, TF-IDF 어휘/IDF나 scaler 통계는 파라미터 수에서 빠지지만 저장 크기에 포함한다. MB는 10^6 bytes다. 저장 크기는 선형 모델의 pickle 직렬화 크기, 인코더의 torch state archive 크기이며 tokenizer/config 파일은 인코더 크기에서 제외한다. 기본 설정에서도 메모리 내 byte counter로 크기를 재며 인코더 가중치를 디스크에 임시 저장하지 않는다.

RSS는 Windows PeakWorkingSetSize(다른 OS에서는 ru_maxrss)의 대략값이다. Python·라이브러리·학습·CPU 추론 전체의 최대치이고 GPU VRAM은 포함하지 않는다. 배포할 추론 런타임의 상주 메모리와 다르다. **앱 전체 400MB와 CPU 약 3% 예산 충족 여부는 이 PC 측정으로 판정할 수 없다.** 대상 기기에서 전체 앱 RSS, 토큰화, 호출 주기와 백그라운드 부하를 함께 측정해야 한다. 단순 CPU 점유 추산도 ms/호출 × 초당 호출 수에 의존한다.

## 이번 실행의 숫자

2026-11-11 `t46-baseline-20261111` 실행은 성공했다. demo2 156장, train 124/val 32, 실제 20묶음(keep 12/none 8)을 사용했다. demo3는 blocks/labels가 없어 1개 디렉터리를 건너뛰었다. 아래는 학습 시드 43/44/45 평균이며 모든 성능 지표의 시드 표준편차는 0이었다. 실제 구간은 위 bootstrap 정의를 따른다.

| 모델 | 합성 val 정확도 | 실제 정확도 [95% 구간] | 실제 keep 재현율 | 실제 keep 정밀도 | 실제 AUROC | 실제 보조 3분류 정확도 | 실제 TN/FP/FN/TP |
|---|---:|---:|---:|---:|---:|---:|---|
| ngram | 1.000 | 0.750 [0.550, 0.900] | 0.917 | 0.733 | 0.771 | 0.450 | 4 / 4 / 1 / 11 |
| shape | 0.906 | 0.600 [0.400, 0.800] | 0.917 | 0.611 | 0.448 | 0.400 | 1 / 7 / 1 / 11 |
| 전부 keep | 0.625 | 0.600 [0.400, 0.800] | 1.000 | 0.600 | 0.500 | — | 0 / 8 / 0 / 12 |
| Gemini Pleos | — | 0.850 [0.700, 1.000] | 0.917 | 0.846 | 0.833 | 0.650 | 6 / 2 / 1 / 11 |

ngram 문턱은 0.649, shape는 0.787이었다. 상세 재현율·정밀도·AUROC·보조 정확도의 구간은 실행 report/results에 있다.

| 모델 | 파라미터 수 | 저장 MB | CPU ms/묶음 평균 ± SD | peak RSS MB 평균 ± SD |
|---|---:|---:|---:|---:|
| ngram | 65,856 | 1.131 | 1.162 ± 0.330 | 138.919 ± 1.037 |
| shape | 32 | 0.002 | 0.200 ± 0.048 | 120.405 ± 0.480 |

합성 val이 쉬워 보여도 실제 성능이 따라오지 않을 수 있다. shape의 합성 val 정확도와 실제 AUROC 차이도 길이·줄 구조 같은 합성 편향을 점검할 이유다. 실제 20개는 “아예 안 되는가”를 보는 자료이며, 모델 순위나 차량 배포 결정을 뒷받침하기에는 작다. story/moment의 경계는 사람 판정에도 주관성이 있다. 실제 20개를 보면서 모델·입력·문턱을 계속 고르면 평가 자료에 맞춘 선택이 되므로 별도의 실제 묶음 확보가 필요하다.

## 합성 4장 단위 탈락 조사와 수정

기존 `synth.generate.request_batch()`는 `validate_scenes()`가 첫 오류에서 예외를 내면 **응답 전체를 재시도**했다. 3회 예산 소진 시 `BatchFailure`, `generate()`가 그 배치의 4장 모두를 failed_cards에 넣었다. duration/line count 허용 폭만 늘려도 speaker/fields/duplicate/JSON 같은 다른 오류가 계속되면 탈락률은 그대로일 수 있다.

읽기 전용으로 확인한 demo2 metadata에는 failed_cards 44장, validation_errors 50회, backend_errors 0회가 있었다. **기존 metadata에는 검사별 이유나 실패 응답이 없어 44장의 정확한 실패 사유는 소급 집계할 수 없다.** 보존된 정상 장면만 다시 검사해서 실패 응답의 이유를 추정하지 않았다. 원본 demo2/demo3 파일을 수정하거나 생성을 재실행하지 않았다.

수정 후에는 응답에서 카드별로 검증하고 통과한 장면을 재시도 사이에 보존한다. 예산 소진 시에도 정상 장면은 저장하고 실패한 카드만 failed_cards에 남긴다. 다음 실행은 저장되지 않은 카드만 요청한다. 동시 생성 후 기존 장면과 중복 검사에서 충돌해도 통과한 카드는 보존한다. 구조가 깨진 응답·전체 backend 실패로 정상 장면을 확보하지 못한 경우에는 배치 전체가 실패할 수 있다.

`scenes.meta.json`의 기존 `failure_counts`는 유지하고 `failure_reasons`를 추가했다. JSON 파싱, 응답 장수·ID, 줄 수, 길이, 화자, 시간, 내용 형식, 금지 이름, 중복 등 고정된 종류별 숫자만 기록한다. 각 카드의 첫 실패 검사와 응답 외곽 오류를 센다. 따라서 이유 합계는 실패 응답 수보다 클 수 있다. 이미 통과해 보존된 카드는 이후 같은 응답에서 재검사하지 않는다. 카운터는 기존 동작처럼 **현재 실행 단위**이며, 같은 카드가 재시도마다 실패하면 여러 번 집계된다. backend 오류도 별도 종류로 센다. 생성된 말이나 예외 원문은 metadata/log에 기록하지 않는다. 기존 resume 서명은 유지하며 부분 저장·구버전 metadata로 재개할 수 있다.

## 테스트

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
$t46Tests = Get-ChildItem tests/test_trial.py, tests/test_synth*.py, tests/test_demo*.py |
  ForEach-Object { $_.FullName }
.\.venv\Scripts\python.exe -m pytest @t46Tests --basetemp .local/pytest-t46 -p no:cacheprovider -q
```

새 테스트는 창작 입력만 사용한다. ngram/shape의 학습·3시드·평가·문턱 적용·출력 전체와 장면 분할 누수 방지, 구간·지표·오류 시 전사 비출력, 무작위 초기화 초소형 BERT의 실제 1 optimizer step·예측·직렬화·앞/뒤 토큰 절단을 확인한다. 범용 fast tokenizer의 special token 보존과 초소형 ModernBERT의 설정 호환·CPU 1 step도 확인한다. encoder 테스트는 다운로드/GPU 없이 CPU에서 실행한다. 생성기 테스트에는 한 장만 실패할 때 정상 세 장 보존과 남은 한 장만 재개하는 경우, 여러 검사 사유 집계를 추가했다. `tests/test_label.py`의 기존 무관한 실패는 대상에서 제외했다.

최종 위 명령 결과: **229 passed, 14 warnings (44.81초)**. 경고는 설치된 torch의 `torch.jit.script_method` deprecation 경고다. 실제 전사 글은 테스트 입력·출력에 사용하지 않았다.
