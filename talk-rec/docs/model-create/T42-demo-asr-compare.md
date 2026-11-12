# T42 — 전사 설정 비교와 눈가림 선택 화면

작성일: 2026-11-11. 구현과 가짜 백엔드 검증 완료. 다운로드·실제 모델 전사·청취 평가는 실행하지 않았다. 커밋하지 않았다.

## 만든 것과 변경 파일

작업 기준 경로: `C:\github\drive-diary\talk-rec\model-create`.

| 파일 | 역할 |
|---|---|
| `demo/asr/__init__.py`, `configs.py` | 비교 패키지, 네 설정 표, 추가 로컬 CT2 후보 등록 |
| `demo/asr/fetch.py` | large-v3의 허용된 모델 파일만 명시적으로 다운로드 |
| `demo/asr/data.py` | approved 표본·클립 경로·결과 검증, 완전한 설정별 결과 읽기 |
| `demo/asr/run.py` | 클립 전사, 기존 기준선 재사용, long 줄 나누기, JSONL·stats 저장 |
| `demo/asr/gpu.py`, `metrics.py` | GPU 메모리 관측, 반복·글자 수 집계 |
| `demo/asr/report.py` | 설정별·영상별 최종 선택 수/비율, 기계 지표, 시간·메모리 보고서 |
| `tools/asrpick/__init__.py`, `store.py`, `serve.py`, `index.html` | 눈가림 셔플·동일 본문 통합·로컬 화면·원자적 선택 journal |
| `tests/test_asr_core.py`, `test_asr_http.py`, `test_asr_report.py` | 가짜 디코더·합성 토큰·합성 파일·실제 loopback HTTP 테스트 |
| `../docs/model-create/T42-demo-asr-compare.md` | 이 실행 보고서 |

`pipeline/`, `tools/listen/`, `tools/review/`, 기존 `demo/*.py`, `common/`은 수정하지 않았다. 의존성·lock 파일도 변경하지 않았다. 기존 함수는 import해서 사용한다. 실제 `.local/demo/` 아래에는 테스트 디렉터리나 결과·선택 기록을 만들지 않았다. 테스트 임시 파일은 `.local/asrt/pt` 안에만 둔다.

## 밖에서 실행할 명령: 순서대로

PowerShell에서 실행한다. 환경의 faster-whisper 1.2.1, CTranslate2, Hugging Face Hub, PyAV 및 CUDA DLL을 사용한다. CPU 대체 실행이나 run 중 모델 다운로드는 없다. T40의 sample과 클립이 준비되어 있어야 한다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create

# 1. 네트워크가 되는 밖에서 모델 파일 준비
.\.venv\Scripts\python.exe -m demo.asr.fetch

# 2. 동일한 approved 클립 전체를 설정별로 처리
.\.venv\Scripts\python.exe -m demo.asr.run --config turbo-base --out .local\demo\asr\turbo-base.jsonl
.\.venv\Scripts\python.exe -m demo.asr.run --config large-v3-base --out .local\demo\asr\large-v3-base.jsonl
.\.venv\Scripts\python.exe -m demo.asr.run --config large-v3-long --out .local\demo\asr\large-v3-long.jsonl
.\.venv\Scripts\python.exe -m demo.asr.run --config large-v3-long-prompt --out .local\demo\asr\large-v3-long-prompt.jsonl

# 3. 사람의 눈가림 비교 (my-name은 영문/한글/숫자/밑줄/하이픈 이름)
.\.venv\Scripts\python.exe -m tools.asrpick.serve --name my-name --port 8768 --limit 12
# 브라우저에서 http://127.0.0.1:8768 열기. 이번 묶음 종료 후 Ctrl+C.
# 같은 serve 명령을 다시 실행하면 다음 미선택 묶음으로 진행한다.
# 기본 limit 12이므로 25개 전체 평가에는 보통 세 번 실행한다.

# 4. 마지막 선택 기록으로 보고서 생성 (서버 종료 후 실행 가능)
.\.venv\Scripts\python.exe -m demo.asr.report --picks .local\demo\asr\picks\my-name.jsonl --out .local\demo\asr\report.md
```

선택된 항목을 포함하여 다시 검토하려면 다음 명령을 사용한다.

```powershell
.\.venv\Scripts\python.exe -m tools.asrpick.serve --name my-name --limit 25 --review
```

## 내려받는 모델과 크기

| 모델 | 위치 | 대략 크기 |
|---|---|---|
| `Systran/faster-whisper-large-v3` (CTranslate2) | `.local/hf/models--Systran--faster-whisper-large-v3/snapshots/<revision>/` | FP16 가중치 약 3.1 GB(약 2.9 GiB), 토크나이저 등 수 MB 추가 |
| 기존 turbo | `pipeline.segment.MODEL`이 가리키는 `../dev-tools/.local/models/whisper-turbo` | 기존 파일 재사용, 추가 다운로드 없음 |

large-v3 크기는 추정치이며 실제 다운로드 바이트 수는 미확인이다. 현재 캐시에 config·tokenizer 파일은 있지만 `model.bin`은 확인되지 않아 fetch가 필요하다. Hugging Face 캐시의 Windows 링크/복사 방식과 중간 다운로드 파일 때문에 디스크 사용량은 더 커질 수 있다. fetch는 `model.bin`, `config.json`, `tokenizer.json`, `vocabulary.*`, `preprocessor_config.json`만 허용한다. 저장소의 Python 파일을 받거나 실행하지 않으며 `trust_remote_code`를 사용하는 로더도 없다.

추가 한국어 미세조정 후보는 CT2 변환이 끝난 로컬 디렉터리를 준비한 뒤 `demo/asr/configs.py`의 `CONFIGS`에 한 줄을 추가한다.

```python
"ko-long": Config(Path(r"C:\models\korean-ct2"), "long"),
```

해당 경로에 `model.bin`, `config.json`, `tokenizer.json`이 있어야 한다. PyTorch 체크포인트 디렉터리는 직접 사용할 수 없다. 추가 후보도 `run --config ko-long --out .local/demo/asr/ko-long.jsonl`로 전사하면 화면과 보고서가 포함한다. 원격 코드가 필요한 모델은 사용할 수 없다.

## 설정 표

모두 CUDA GPU 0, float16, `language="ko"`, `task="transcribe"`, beam 5, word timestamps 사용.

| 이름 | 모델 | VAD | 이전 본문 조건 | 온도 | 줄 나누기 | initial_prompt |
|---|---|---|---|---|---|---|
| turbo-base | 기존 turbo | 기존 VAD | False | 0 | 기존 필터 후 1초 쉼 또는 12초 상한 | 없음 |
| large-v3-base | large-v3 | 기존 VAD | False | 0 | 기준선과 동일 | 없음 |
| large-v3-long | large-v3 | 끔 | True | `[0, 0.2, 0.4]` 폴백 | 0.8초 이상 쉼에서만 | 없음 |
| large-v3-long-prompt | large-v3 | 끔 | True | `[0, 0.2, 0.4]` 폴백 | 0.8초 이상 쉼에서만 | 차 안에서 가족이 나누는 대화. 아이와 부모가 말한다. |

기준선은 `pipeline.segment.transcribe`와 `build_records`를 그대로 호출한다. VAD threshold 0.5, 최소 발화 0ms, 최소 무음 1000ms, padding 300ms이며, 기존 무음·낮은 확률·반복·긴 단어 등의 제거 규칙도 적용한다. 원본 영상에서 얻었던 예전 전사를 복사하는 것이 아니라 **같은 padding 포함 WAV에 재실행**한다. 12초 제한은 기존 파이프라인처럼 디코드 뒤 발화 묶기의 상한이다.

long은 일반 `WhisperModel.transcribe`의 `chunk_length=30`으로 전체 WAV를 연속 디코드하며 BatchedInferencePipeline이나 외부 VAD 분할을 사용하지 않는다. 이전 본문 조건은 같은 클립의 창 사이에만 적용되고 클립 사이로 전사를 넘기지 않는다. 기존 후처리 제거·12초 줄 상한을 적용하지 않으므로 한 줄이 길거나 반복이 남을 수 있다. 쉼은 이전 줄 단어들의 최대 end와 다음 start 차이로 계산한다. 단어 시간이 없는 비어 있지 않은 세그먼트는 조용히 누락하지 않고 실행 실패로 처리한다.

기타 공통 디코드 값은 기존 `DECODING`을 재사용한다: `no_speech_threshold=None`, `log_prob_threshold=-1.0`, `compression_ratio_threshold=2.4`, `hallucination_silence_threshold=None`. long의 온도 폴백은 이런 디코드 임계에 따라 이루어진다.

## 결과와 눈가림 선택 동작

- `origin == "approved"`만 읽는다. 확인한 입력은 총 65개 중 approved 25개이며, 해당 WAV 25개가 존재한다. prescreened는 전사·화면·보고서 대상에서 제외한다.
- 파일명은 `tools.listen.storage.clip_name`을 재사용한다. 예: id `yt:IOlxH67P1JM@0` → `yt_IOlxH67P1JM@0.wav`. 이미 앞 2초·뒤 1초 padding이 있으므로 재절단하지 않는다. 출력 줄 시각은 **padding 포함 WAV 시작을 0초로 한 상대 시각**이다.
- 출력 한 줄에는 `id`, `config`, `lines[{start,end,text}]`, `seconds`, `avg_logprob`, `no_speech_prob`만 저장한다. seconds는 WAV 디코드·지연 생성기 소진을 포함한 처리 시간이며 모델 로드는 제외한다. 확률은 원시 디코드 세그먼트 길이로 가중 평균하고 세그먼트가 없으면 null이다. 기준선 필터로 모든 줄이 사라져도 원시 확률은 남는다.
- `<이름>.stats.json`은 출력 JSONL의 확장자를 바꾼 형제로 저장한다. 총 시간·모델 로드·전사 시간, 실제 모델 경로, 디코드 옵션, 반복 구간/해당 클립 수, 빈 클립 수, 줄 수, 줄당·클립당 글자 수의 min/median/p90/max/total을 기록한다. 글자 수는 공백·문장부호를 포함하고 줄 구분 개행은 제외한다. 반복은 같은 단어·음절이 8회 이상 이어지는 겹치지 않는 구간 수이며, 붙여 쓴 반복도 감지한다. 웃음 등 정상 표현도 탐지될 수 있다.
- GPU 메모리는 torch 할당량 대신 `nvidia-smi`로 **GPU 0 장치 전체 사용량**을 약 0.2초 간격으로 관측한다. 다른 프로세스의 사용량도 포함하고 짧은 피크는 놓칠 수 있다. CUDA 장치 재매핑 없이 GPU 0에서 실행하고 공정한 비교를 위해 다른 GPU 작업을 멈추는 것이 좋다. 명령 실행/측정 실패 시 peak는 null이며 보고서에는 미측정으로 나온다. 다운로드 크기와 VRAM 요구량은 다른 수치다.
- 화면은 `.local/demo/asr/*.jsonl`을 읽어 결과 안의 config로 묶는다. 설정별로 승인 표본 전체가 있어야 하며 누락·중복·혼합 결과는 거부한다. 최소 두 설정이 필요하다. 같은 config의 예전 복사본을 이 디렉터리에 함께 두지 않는다.
- 영상 `yt:IOlxH67P1JM`, `yt:zn4tDPT4RLQ`를 먼저 배치하고 영상 내에서는 시간순이다. limit 기본 12는 미선택 표본에 적용한다. 모두 선택했거나 `--review`이면 처음부터 재검토할 수 있다.
- 설정 이름은 HTTP 응답에도 넣지 않는다. 서버가 이름/id의 SHA-256 시드로 클립별 셔플을 만들고 후보 슬롯→설정 목록을 기억한다. 재시작해도 입력과 이름이 같으면 순서가 같다. 시각은 비교에서 제외하며, 줄 경계를 공백으로 연결한 본문이 같으면 후보 하나로 합친다. 내부 공백·문장부호의 차이는 유지한다. 선택하면 해당 설정 모두에 1표씩 준다.
- Space 재생/일시정지, 1~4 후보 선택, 0 전부 엉망, S 비슷해서 못 고르겠음, C 코멘트, J 다음/K 이전. 추가 후보는 5~9도 지원하며 모든 후보에 클릭 버튼이 있다. 코멘트 입력 중에는 단축키를 막고 Esc로 입력을 마치면 선택 단축키를 다시 쓴다. 코멘트는 다음 선택 저장에 포함된다. 고르면 저장 성공 후 다음 클립으로 이동한다. J/K로만 이동하면 선택을 저장하지 않는다.
- `.local/demo/asr/picks/<이름>.jsonl`에 `id`, `picked`(설정 목록 또는 `all_bad`/`similar`), `comment`, UTC `decided_at`을 추가한다. 같은 id의 마지막 기록이 유효하다. 스레드 잠금 안에서 이전 journal과 새 행을 합쳐 형제 임시 **파일**에 fsync한 뒤 원자적으로 교체하며, 임시 파일은 정상·예외 종료 시 정리한다. 임시 디렉터리나 lock 파일은 만들지 않는다. 강제 프로세스 종료/전원 차단까지 임시 파일 정리를 보장하지는 않는다.
- 같은 이름의 서버는 한 프로세스만 실행한다. 선택 중에는 sample·클립·전사 결과를 바꾸지 않는다. 후보나 설정을 바꿔 비교하려면 새 이름의 선택 기록을 쓴다. run은 이어받기 없이 전체 설정을 다시 처리하고 완료 후 결과를 원자적으로 교체한다. JSONL과 stats 두 파일의 교체가 하나의 트랜잭션은 아니며, 보고서는 불일치한 stats를 거부한다.
- 127.0.0.1에만 바인딩한다. 외부 Host/Origin·cross-site 요청과 임의 파일 경로를 거부하며, 서버가 선택한 WAV만 Range 요청으로 제공한다. 외부 JS/CSS/폰트/CDN은 없다. 본문을 textContent로 표시한다. 로그에는 id·시간·상태만 출력하고, 예외 원문이나 전사 본문은 출력하지 않는다.

## 보고서 읽기

보고서는 전사 본문·코멘트를 포함하지 않는다. 설정별 선택 횟수와 비율, 영상별 같은 수치 및 전부 엉망/비슷함 수, 전체 approved 결과의 기계 지표, 총 시간·로드 시간·전사 시간·GPU 최대 관측량을 보여 준다. 비율 분모는 해당 범위에서 최종 선택을 남긴 클립 수이며 전부 엉망/비슷함도 포함한다. 동일 후보가 여러 설정에 표를 주므로 설정별 비율 합이 100%를 넘을 수 있다. 미선택 클립은 선택 비율의 분모에 넣지 않는다. 기계 지표는 미선택을 포함한 전체 승인 클립 기준이다.

## 검증과 확인 못 한 것

테스트 실행 명령:

```powershell
New-Item -ItemType Directory -Path .local\asrt -Force | Out-Null
.\.venv\Scripts\python.exe -m pytest tests/test_asr_core.py tests/test_asr_http.py tests/test_asr_report.py --basetemp .local/asrt/pt -p no:cacheprovider -q
```

최종 테스트 결과는 아래 기록을 따른다. 설정 표·금지 원격 모델·fetch 허용 파일·가짜 Whisper 어댑터 옵션·기준선 필터 재사용·long 쉼/상한 없음·가중 확률·반복 탐지·가짜 실행 실패 시 기존 결과 보존·GPU 측정 대체·셔플 결정성·통합 후보·우선순위/limit·서버 저장/재선택/재시작·동시 저장·경로 탈출·Host/Origin·Range/HEAD·loopback 바인딩·보고서 수치를 검증했다. 서버 테스트는 finally에서 shutdown/server_close/join하고 종료를 확인한다.

현재 확인: **74 passed, 1 skipped**. skip은 Windows에서 심볼릭 링크 생성 권한이 없는 경우의 파일 링크 탈출 검사다. 일반 요청·세션명 경로 탈출 검사는 통과했다. 기존 `tests/test_label.py`는 실행하거나 수정하지 않았다.

네 CLI의 `--help`와 추출한 화면 JavaScript의 `node --check`도 성공했다. 실제 `.local/demo/` 아래 `.tmp`·`.lock` 잔여 파일은 없음을 확인했다.

미검증: 실제 Hugging Face 다운로드와 정확한 파일 크기, 실제 faster-whisper/CUDA 실행·속도·VRAM 피크·OOM 여부, 실제 아이 발화 품질 및 사람 선택 결과, 브라우저 렌더링·키보드 단축키·오디오 재생. 합성 데이터 UI 서버를 준비했지만 Chrome 제어의 자동 승인 검토가 `Computer Use was not approved to use Google Chrome` 사유로 거부했다. 해당 임시 서버는 종료했다. 합성 HTTP/가짜 모델 테스트만으로 실제 품질 개선을 주장할 수 없다.
