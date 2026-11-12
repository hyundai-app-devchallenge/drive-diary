# T41 — 로컬 음향 태그와 판정 방식 비교 데모

작성일: 2026-11-11. 구현과 합성 입력 검증까지 완료했다. 실제 음원·모델·agy 품질 실험 결과는 아직 없다. 커밋하지 않았다.

## 만든 것

작업 기준 디렉터리는 `C:\github\drive-diary\talk-rec\model-create`다.

| 파일 | 역할 |
| --- | --- |
| `demo/data.py` | sample/답 JSONL 읽기, 원래 utterance 번호로 발화 시각 연결, 원문을 출력하지 않는 오류 처리 |
| `demo/acoustic.py` | PyAV 디코드, AST 사건 점수, ECAPA 임베딩, 영상별 군집, RMS/f0/시각 겹침, 처리 통계 |
| `demo/fetch_models.py` | 공개 모델을 `.local/hf`에 미리 받는 전용 명령 |
| `demo/render.py` | 텍스트만 / 음향 표시 포함 입력을 만드는 순수 함수와 공통 표시 규칙 |
| `demo/run_arm.py` | 기존 `pipeline.label`의 agy/command/fake 백엔드를 import하여 사용, 검증·재시도·재개·한도 대기 |
| `demo/listen.py` | C의 `ListenBackend.judge(audio_path, start, end, prompt)` 프로토콜과 fake 구현 |
| `demo/compare.py` | 일치율·κ·혼동 행렬·재현율/정밀도·시간·불일치 id·A/B 맥니마·bootstrap·사람 간 κ |
| `demo/__init__.py` | 모듈 실행용 패키지 |
| `prompts/demo-story.md` | A/B 공통 한국어 프롬프트. 요청한 정의 및 지어낸 짧은 예 4개 |
| `tests/test_demo_render.py`, `test_demo_acoustic.py`, `test_demo_run_arm.py`, `test_demo_compare.py`, `test_demo_cli.py` | 다운로드 없는 합성 입력 테스트 |
| `pyproject.toml` | `demo` 의존성 그룹, torchaudio CUDA 인덱스 설정 |

`pipeline/label.py`, `prompts/labeler.md`, `pipeline/blocks*`, `common/schema.py`, `tools/`는 수정하지 않았다. T40의 실제 sample/answers에도 쓰지 않았다. 테스트 파일에는 실제 전사나 전사 예문을 넣지 않고 빈 문자열과 시각·번호만 사용했다.

## 밖에서 실행할 명령: 순서대로

PowerShell에서 아래 순서를 따른다. 모델 다운로드·패키지 설치·agy는 이 작업에서 실행하지 않았다. `my-name.jsonl`은 T40에서 만든 실제 사람 답 파일명으로 바꾼다.

### 1. 의존성 설치

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
uv sync --group pipeline --group train --group demo
```

새 모델 의존성은 `speechbrain==1.0.3`, `torchaudio==2.10.0+cu128`다. demo 그룹에는 기존 버전과 맞춘 `numpy==2.2.6`, `av==16.1.0`, `transformers==4.57.3`도 명시했다. torchaudio는 torch와 같은 `pytorch-cu128` 인덱스를 사용한다. `hyperpyyaml`과 `huggingface_hub`는 이 그룹의 SpeechBrain/Transformers 의존성으로 들어온다. 기존 train/pipeline 그룹도 함께 지정하여 해당 환경을 유지한다. 여기서는 설치와 lock 갱신을 하지 않았으며, 밖의 `uv sync`가 lock을 갱신한다.

### 2. 모델 미리 받기

```powershell
.\.venv\Scripts\python.exe -m demo.fetch_models --cache .local\hf
```

| 모델 | 다운로드 대략 크기 | 사용 |
| --- | --- | --- |
| `MIT/ast-finetuned-audioset-10-10-0.4593` | 약 350 MB | AudioSet 사건 분류 |
| `speechbrain/spkrec-ecapa-voxceleb` | 약 90 MB | 턴별 화자 임베딩 |

크기는 추정치이며 이 환경에서 실제 다운로드 크기를 확인하지 않았다. AST는 safetensors가 있으면 그것만, 없으면 `pytorch_model.bin`만 받는다. ECAPA는 `hyperparams.yaml`과 체크포인트만 받는다. 캐시 부가 파일과 Windows의 링크/복사 방식에 따라 디스크 사용량은 늘어날 수 있다. 두 모델 모두 게이트 없는 공개 모델을 전제로 하며 토큰·약관 동의나 `trust_remote_code=True`를 사용하지 않는다. 원격 `custom.py`도 받거나 실행하지 않는다.

### 3. 음향 계산

T40의 sample과 기존 `.local/utterances/*.jsonl`, sample이 가리키는 오디오가 준비된 뒤 실행한다.

```powershell
.\.venv\Scripts\python.exe -m demo.acoustic --sample .local\demo\sample.jsonl --out .local\demo\acoustic.jsonl --offline
```

기본 장치는 CUDA다. `--device cpu`로 CPU 실행도 요청할 수 있다. 기본 사건 임계는 `--threshold 0.3`, 화자 코사인 거리 임계는 `--speaker-threshold 0.35`다. `--offline`은 Transformers와 Hugging Face snapshot 조회에 `local_files_only=True`를 전달한다. ECAPA는 해당 로컬 snapshot에서만 체크포인트를 읽고 SpeechBrain의 별도 수집/다운로드 경로를 호출하지 않는다.

출력은 `acoustic.jsonl`, 통계는 `.local/demo/acoustic.stats.json`이다. 통계에는 실제 `id2label`에서 찾은 이름/인덱스 매핑, 없는 보조 라벨, 설정값, 모델 로딩 시간, 묶음별 처리 시간, 군집 시간, PyTorch GPU 최대 allocated 메모리(byte)가 들어간다. 묶음 시간에는 디코드·사건 추론·임베딩·숫자 계산 및 균등 배분한 전체 군집 시간이 포함된다. 모델 로딩과 파일 쓰기는 제외한다. GPU 메모리는 다른 프로세스까지 포함하는 전체 VRAM 사용량이 아니다.

### 4. A: 텍스트만 판정

```powershell
.\.venv\Scripts\python.exe -m demo.run_arm --arm text --backend agy --model gemini-3.8-flash-low --sample .local\demo\sample.jsonl --out .local\demo\arms\text.jsonl --wait-on-limit 10
```

### 5. B: 음향 표시를 붙여 판정

```powershell
.\.venv\Scripts\python.exe -m demo.run_arm --arm acoustic --backend agy --model gemini-3.8-flash-low --sample .local\demo\sample.jsonl --acoustic .local\demo\acoustic.jsonl --out .local\demo\arms\acoustic.jsonl --wait-on-limit 10
```

agy가 자동 탐색되지 않으면 두 명령에 `--agy-path '실제 agy.exe 절대경로'`를 추가한다. A/B의 시스템 프롬프트와 모델은 같다. 같은 영상의 묶음을 시간순으로, 한 호출에 최대 6개씩 넣는다. 영상이 달라지면 호출을 나눈다. 각 영상의 마지막 배치는 6개 미만일 수 있다. 재개는 완료 id를 건너뛰므로 재시작 시 미완료 묶음의 배치 구성이 달라질 수 있다.

같은 명령을 다시 실행하면 재개한다. 출력 옆 `.text.run/`, `.acoustic.run/`의 `config.json`과 `call-*.json`을 함께 보존한다. 호출 journal에는 id, 상태, 소요 시간, 토큰 사용량(백엔드가 주는 경우), 대기 예정 시간과 검증된 판정만 남긴다. 입력 본문·원시 응답·오류 원문은 남기지 않는다. 출력 materialize 중 중단되어도 journal로 복원한다. 이전 실패 호출의 비용도 재개 후 묶음 시간에 반영한다. 한 출력에 실행기 하나만 사용한다.

입력/발화 시각/프롬프트/모델/백엔드/command 설정이 바뀌면 같은 출력으로 이어 쓸 수 없다. 이때는 새 출력 경로를 사용한다. 한 호출의 검증 실패는 최초 호출 후 최대 2회만 재요청한다. 번호·개수·필드·허용 판정·note를 검사한다. 프롬프트는 인용을 금지하고 4자 이상 발화 전체가 note에 그대로 포함되면 재요청한다. 부분적인 재인용까지 완전히 탐지하는 것은 아니므로 note를 외부 로그로 복사하지 않는다.

한도에 걸려 `--wait-on-limit 10`을 주면 10분 뒤 같은 호출을 다시 시도한다. 옵션이 없으면 종료 코드 3으로 중단한다. 일반 백엔드 오류/검증 소진은 1, 성공은 0이다. 한도 대기 시간은 추론 시간에서 제외한다. agy의 `denied_actions` 없는 정상 응답 처리는 기존 백엔드 구현을 그대로 사용한다.

### 6. 사람 답과 비교

```powershell
.\.venv\Scripts\python.exe -m demo.compare --answers .local\demo\answers\my-name.jsonl --arms '.local/demo/arms/*.jsonl' --acoustic .local\demo\acoustic.jsonl --out .local\demo\report.md
```

PowerShell에서 따옴표로 넘긴 wildcard를 실행기가 직접 확장한다. 비교할 방식마다 파일 하나만 사용하며 같은 arm의 여러 실행 파일을 함께 주면 오류로 막는다. fake 출력은 별도 폴더에 둔다.

사람 답이 둘 이상이면 다음처럼 넘긴다. **첫 답 파일을 기준 정답**으로 삼고 사람 간 κ를 별도로 계산한다. 합의 정답을 임의로 만들지 않는다.

```powershell
.\.venv\Scripts\python.exe -m demo.compare --answers .local\demo\answers\my-name.jsonl .local\demo\answers\second-name.jsonl --arms '.local/demo/arms/*.jsonl' --out .local\demo\report.md
```

답은 같은 id의 마지막 줄이 유효하다. `unsure`는 제외 개수만 보고한다. 방식마다 정답 중 미판정/정답 밖 출력 개수를 표시한다. 직접 A/B 비교는 양쪽이 모두 판정한 동일 id만 사용한다. 음향 레코드가 없는 id는 사건 감지 여부가 미상이라고 표시한다.

주요 결과는 3분류 일치율, Cohen κ, 혼동 행렬, 남길 것(story 또는 moment)과 story 각각의 재현율/정밀도, 전사·note·comment 없는 불일치 id 목록, 묶음당 시간이다. B에는 `.stats.json`이 있으면 음향 전처리+판정 시간도 따로 합산한다. A/B는 맥니마 2×2 표·정확 양측 p, B−A 차이, 정답 moment/웃음·노래 감지/둘의 합집합 부분집합 일치율을 표시한다.

95% 구간은 기본 묶음 단위 percentile bootstrap 2,000회(seed 41)다. A/B 차이는 같은 묶음을 함께 뽑는 paired bootstrap이다. 분모가 0인 precision/recall과 단일 클래스의 정의 불가능한 κ는 N/A이며, bootstrap의 유효 반복 수도 보고한다. 표본 약 60개의 탐색적 결과이고, 같은 영상 내 상관을 반영하지 않아 구간이 좁을 수 있다. 소규모 부분집합의 높은 일치율만으로 라벨러를 확정하지 않는다.

## 음향 라벨 매핑과 표시 규칙

아래 이름은 AudioSet의 config 표시 이름으로 찾는다. 숫자 class id를 고정하지 않는다. 각 행의 첫 이름이 config에 없으면 실패하고, 나머지 보조 이름은 존재하는 것만 사용한다. 실제 해석된 매핑 및 빠진 보조 이름은 `.stats.json`으로 확인한다. 실제 모델 config는 이번 샌드박스에서 내려받아 확인하지 않았다.

| 내부 event | 정확한 config 라벨 후보 | 한국어 표시 |
| --- | --- | --- |
| `laughter` | `Laughter`; `Baby laughter`; `Giggle`; `Snicker`; `Belly laugh`; `Chuckle, chortle` | `(웃음)` |
| `singing` | `Singing`; `Choir`; `Yodeling`; `Chant`; `Mantra`; `Child singing`; `Synthetic singing`; `Rapping`; `Humming` | `(노래)` |
| `music` | `Music` | 묶음 머리 `music` 점수 |
| `child_speech` | `Child speech, kid speaking` | `(아이 목소리)` |
| `baby_cry` | `Baby cry, infant cry` | `(아기 울음)` |
| `shout` | `Shout`; `Bellow`; `Whoop`; `Yell`; `Children shouting`; `Screaming` | `(큰 소리)` |
| `applause` | `Applause`; `Cheering` | `(박수·환호)` |
| `vehicle` | `Vehicle`; `Engine`; `Engine starting`; `Idling`; `Accelerating, revving, vroom` | JSON 점수만 저장 |
| `speech` | `Speech` | JSON 점수만 저장 |

AST logits에 sigmoid를 적용하고, 같은 event에 대응하는 라벨 중 최대값을 창 점수로 쓴다. 5초 창·2.5초 간격이며 끝 창과 5초보다 짧은 묶음은 5초까지 무음 패딩한다. 마지막 창이 실제 끝을 덮으면 추가 창을 만들지 않는다. 각 창의 실제 시작/끝과 점수를 JSONL의 `windows`에 남긴다.

묶음/턴의 `events`는 겹치는 창 점수의 최대, `events_mean`은 해당 창들의 단순 산술평균이다. 턴 시각과 조금이라도 교차하는 창을 사용하므로 짧은 턴에는 주변 소리도 묻는다. 정확한 사건 경계나 해당 화자만의 사건을 의미하지 않는다. 평균은 교차 시간으로 가중하지 않는다.

한국어 표시는 `demo/render.py`의 `MARKERS` 한 표에서 관리하고, 모든 표시의 기준은 **턴 최대 점수 ≥ acoustic 레코드의 threshold(기본 0.3)**다. 저장한 점수 자체는 임계 아래 값도 버리지 않는다. 음악·노래·웃음 최대 점수와 추정 화자 수, 겹침 비율을 묶음 머리 속성으로 준다. 음향이 비어 있으면 텍스트만 렌더링하며, id만 있는 빈 결과는 0개 화자·0점과 `[화자?]`로 표현한다. CLI의 B는 묶음별 음향 레코드가 빠지면 실패한다.

## 수치·화자 해석과 입력 계약

- sample의 `turns`는 원문 문자열 배열이다. `source`와 `utts`로 `.local/utterances/`에서 시각을 찾고 문자열 일치를 확인한다. 발화 시각이 없거나 맞지 않으면 추정해 진행하지 않는다. 렌더러를 직접 호출할 때는 같은 순서의 `turn_times` 목록을 전달한다. 첫 턴의 간격은 +0, 이후에는 직전 턴 끝과의 실제 간격(겹치면 음수), 묶음 사이 간격은 `gap_before` 속성이다. ASR 본문의 XML 특수문자는 escape한다.
- `audio.path`는 실행 기준 디렉터리에서 해석한다. `audio.start/end`는 해당 파일 안의 실제 구간이고 그 길이는 `block.end-start`와 1 sample 이내로 같아야 한다. 원본 webm을 가리키면 일반적으로 블록과 같은 시각이다. 잘라둔 파일이면 0부터 시작하는 audio 구간도 가능하다. 오디오와 발화 시각은 블록 시작을 기준으로 연결한다.
- PyAV가 presentation timestamp로 seek/정렬한 뒤 채널을 산술평균하여 모노 16 kHz float PCM을 만든다. 파일 경계에 없는 구간은 실패한다. 내부 timestamp 공백은 무음으로 둔다.
- RMS는 `20 log10(sqrt(mean(x²)))` dBFS, 무음/빈 턴의 바닥값은 −120 dBFS다. `rms_std`는 턴 RMS 값들의 모집단 표준편차(ddof=0), `rms_peak_jump`는 연속 턴의 최대 양의 증가량이며 증가가 없으면 0이다.
- f0는 numpy 정규화 자기상관: 40 ms 프레임, 20 ms hop, 65–600 Hz, −50 dBFS 이상 및 상관 ≥0.6인 첫 국소 봉우리, 포물선 보간이다. 완전한 프레임만 계산한다. 중앙값과 max−min 범위, 전체 프레임 중 유성 비율을 기록한다. 유성 프레임이 없으면 f0 두 값은 null이다. 음악·잡음·아동 음성에서 배음/옥타브 오류가 날 수 있다. librosa를 추가하지 않았다.
- `overlap_share`는 2개 이상 턴 시각이 겹친 시간의 합집합 / 묶음 길이다. 실제 음성 중첩을 검출하는 모델 결과가 아니다. 삼중 겹침도 한 번만 센다. 화자 미표시 ASR의 시각만으로는 놓치는 겹침이 많다.
- 0.6초 미만 턴은 임베딩을 만들지 않고 `?`다. 나머지는 같은 source의 표본 턴을 모아 average-link 응집 군집을 한다. 코사인 거리 기본 0.35, 유효 턴이 둘 이상이면 최소 2개·최대 6개 군집이다. 유효 턴이 하나면 1개, 없으면 0개다. 실제 한 사람뿐이어도 최소 2개 제약 때문에 갈라질 수 있다.
- A/B/C 문자는 source 내 첫 출현 순서이고 다른 영상과 동일 인물이란 뜻은 아니다. 성별·가족 관계를 뜻하지도 않는다. `n_speakers`는 해당 묶음에 나온 알려진 화자 수다. `speaker_turn_changes`는 인접 턴이 둘 다 알려져 있고 문자가 바뀐 경우만 센다. `?`를 건너뛰어 연결하지 않는다.
- SpeechBrain은 늦게 import한다. 1.0.3의 import 시 torchaudio 구버전 API 탐색을 위해, 없는 `list_audio_backends`에는 빈 목록을 반환하는 작은 호환 처리를 둔다. 파일 디코딩은 torchaudio가 아닌 PyAV가 담당한다. 실제 이 조합의 설치·동작은 밖에서 확인해야 한다.

## 가짜 실행과 테스트

실제 sample 경로를 사용하지 않는 합성 CLI 통합 테스트도 포함했다. 순서는 음향 fake(실제 PyAV 합성 WAV 디코드) → A fake → B fake → C fake → 재개 → compare다. Hugging Face 전송과 AST 어댑터는 모듈 가짜로 캐시/offline 전달을 검사했다. 모델 파일은 읽지 않았다.

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_demo_render.py tests/test_demo_acoustic.py tests/test_demo_run_arm.py tests/test_demo_compare.py tests/test_demo_cli.py --basetemp .local/demo/pt -p no:cacheprovider -q
```

결과: **53 passed (5.26초)**. 사인파의 f0/RMS 및 진폭비에 따른 음량 급등, 무음, 스테레오 다운믹스·16 kHz 리샘플·구간 seek, 삼중 겹침, 사건 최대/평균, 합성 임베딩 군집, 표시 임계, 입력 시각 연결, AST/ECAPA 가짜 어댑터의 offline/로컬 로딩 계약, 6개 배치·순서, 검증 재시도/재개/저널 복구/한도 대기/오류 비공개, 손계산 지표와 McNemar, bootstrap 재현성, 마지막 답 우선 및 사람 간 비교를 검증했다. 알려진 기존 `tests/test_label.py` 실패 9개는 이번 범위와 관계없이 남아 있으며 해당 전체 스위트는 다시 실행하지 않았다.

C의 자리만 시험하려면 별도 출력 경로로 실행한다.

```powershell
.\.venv\Scripts\python.exe -m demo.run_arm --arm listen --backend fake --out .local\demo\fake-arms\listen.jsonl
```

C의 fake는 소리를 열거나 이해하지 않고 항상 none을 반환한다. listen에 agy/command를 지정하면 거부한다. 실제 음성 LLM 어댑터는 만들지 않았다. A/B도 `--backend fake`가 가능하며 음향 명령의 `--backend fake`는 사건을 0점으로 반환한다. 이 결과들은 품질 비교 자료가 아니다.

## 확인하지 못한 것

- 실제 sample/답 연결 및 원본 webm의 모든 codec/timestamp 경우. T40 완료 후 외부 명령으로 확인해야 한다.
- HF 실제 config의 전체 보조 라벨 일치와 다운로드 크기, 실제 모델 다운로드·오프라인 캐시 완전성.
- SpeechBrain/torchaudio 설치 및 실제 ECAPA 체크포인트 로딩, RTX 5060 Ti에서의 AST/ECAPA 추론·최대 메모리·처리 시간.
- agy 실제 응답·한도 처리·토큰 사용량, 6개 긴 묶음의 Windows 명령 길이 제한. 기존 백엔드는 길이 초과를 잘라내지 않고 오류로 반환하므로 필요하면 표본의 길이를 재검토한다.
- 실제 음향 사건·화자 군집 품질과 사람이 고른 정답에 대한 A/B 성능. 차량 음악, 방송 음성, 아이 음성, 가족 간 비슷한 음색과 턴 안 화자 교체는 오판 원인이 된다.
- C의 실제 청취 성능. 다음 작업에서 프로토콜에 맞는 모델을 연결해야 한다.

따라서 현재 보고서는 구현·검증 기록이며 A/B/C 중 어느 방식이 더 낫다는 실험 결론을 포함하지 않는다.
