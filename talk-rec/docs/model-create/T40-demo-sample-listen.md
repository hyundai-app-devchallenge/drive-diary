# T40 — 데모 표본과 소리 듣고 판정 화면

작성: 2026-11-11. 작업 위치: `C:\github\drive-diary\talk-rec\model-create`.
커밋·외부 네트워크·브라우저 실행·의존성 설치 없이 구현했다. 이 표본과 판정은
데모 전용이며 학습 데이터로 사용하지 않는다. 보고서에는 전사 본문을 싣지 않았다.

## 만든 것과 변경 파일

- `pipeline/collect.py`, `pipeline/collect.md`: 사람이 승인한 영상의 길이 하한만
  180초로 완화했다. 명시적 --min-duration이 더 작으면 기존 값을 유지한다.
  빈 decided_by·claude 사전 선별에는 기존 하한을 적용한다. 다른 필터·예산·수집 기준은
  유지했다. discovery/refilter/download가 같은 길이 예외를 사용한다.
- `data/sources.tsv`: `zn4tDPT4RLQ`(427초)의 status를 rejected → candidate,
  note를 duration_too_short → 빈 값으로 바꿨다. 전후 전체 매니페스트를 비교하여
  이 두 셀만 변경됐음을 확인했다. 실제 다운로드나 전사는 실행하지 않았다.
- `tools/listen/__init__.py`, `storage.py`, `sample.py`, `serve.py`, `clips.py`,
  `index.html`, `README.md`: 표본 추출·통계, 원자적 파일 저장, 청취 화면,
  WebM Range 서빙, PyAV WAV 클립 생성, 사용 문서.
- `tests/test_listen_sample.py`, `test_listen_http.py`, `test_listen_clips.py`,
  `test_listen_collect.py`: 표본·답 파일·HTTP·클립·수집기 회귀 테스트.
- 이 보고서: `talk-rec/docs/model-create/T40-demo-sample-listen.md`.

`pipeline/blocks*`, `common/schema.py`, `demo/` 및 기존 라벨러는 수정하지 않았다.
동시에 진행되는 다른 작업의 파일 변경은 이 작업에 포함하지 않는다.

## 실제 산출물과 숫자

- `.local/demo/sample.jsonl`: **60개**, 영상 **33개**. 원래 묶음의 모든 필드에
  audio(path/start/end), origin만 추가했다. 경계는 원본 그대로다.
- `.local/demo/sample.meta.json`: seed=1, n=60, actual_n=60,
  demo_only=true, 규칙, 생성 UTC 시각, 통계. 최초 생성 시각은
  `2026-11-11T01:48:50.021879+00:00`이다.
- `.local/demo/clips/yt_<video_id>@<block>.wav`: **60개**, 합계
  **107,406,368 bytes**. 16kHz mono PCM16. Windows 파일명에서 `:`를 쓸 수 없어
  `yt:ID@N`을 `yt_ID@N.wav`로 매핑했다.
- 답 파일은 사용자가 판정할 때 `.local/demo/answers/<이름>.jsonl`에 생긴다.
  실제 데이터 점검에서는 답 파일을 생성하지 않았다.

입력 승인 묶음 20개(2개 영상), 사전 선별 묶음 786개를 확인했다. 현재 입력에는
모두 대응 WebM이 있었다. 표본은 승인 **20개 전부** + 사전 선별 **40개**이며,
사전 선별 몫은 무작위 **20개** + 정신없는 후보 **20개**다. 정신없는 후보 부족량은 0이다.
한 턴 묶음은 **12개**다. 승인 전부 포함을 우선하므로 승인 영상의 12개·8개는
4개 제한의 예외이며, 사전 선별 영상은 최대 4개다.

| 원래 묶음 길이 | 초 |
| --- | ---: |
| 최소 | 0.700 |
| 25백분위 | 20.777 |
| 중앙값 | 64.280 |
| 75백분위 | 81.813 |
| 90백분위 | 87.282 |
| 최대 | 89.030 |

30초 미만 17개, 30~90초 43개, 90초 초과 0개다. 한 턴·한 줄 순간 대조 표본을
위해 30초 하한으로 거르지 않았다. 이 분포는 앞뒤 재생 여유를 제외한 원래 묶음 기준이다.

| 영상 id | origin | 표본 수 |
| --- | --- | ---: |
| IOlxH67P1JM | approved | 12 |
| pkGAxu0g0vQ | approved | 8 |
| 0_qX1N2IXTU | prescreened | 1 |
| 3xvfnBp21_Y | prescreened | 3 |
| 4i4dDBv4hzA | prescreened | 1 |
| 5vSwUj9l7iY | prescreened | 1 |
| 6p0zHS5mEqo | prescreened | 1 |
| BEIYkRxq7X4 | prescreened | 1 |
| F6IAfLSuDR4 | prescreened | 1 |
| FJkJnUbJm88 | prescreened | 1 |
| GqlRV76skqE | prescreened | 1 |
| H5vQyHx07eU | prescreened | 1 |
| JuOU4RnymqI | prescreened | 1 |
| Jy1Oruo4fZk | prescreened | 1 |
| KQ7N15C1iCc | prescreened | 2 |
| Mi3KKPZH720 | prescreened | 1 |
| STlUZf798-w | prescreened | 4 |
| UBb2T5huI6k | prescreened | 1 |
| VfzjQnKeizE | prescreened | 1 |
| W8D9ZhiOVoA | prescreened | 1 |
| WdM5sso70Z4 | prescreened | 1 |
| X00WlhdkzFw | prescreened | 1 |
| YSSc0aUNHyc | prescreened | 1 |
| YWrYUrqg0is | prescreened | 1 |
| Ylx8I0qEMpg | prescreened | 1 |
| b_TKOQzBNIE | prescreened | 3 |
| hBh7eAfsTlI | prescreened | 1 |
| ljdZIUyCgiM | prescreened | 1 |
| oCCzZJ1dy7U | prescreened | 1 |
| wtF_ALqbkks | prescreened | 1 |
| yZRvfnRf6XE | prescreened | 1 |
| zeFFCX_oku8 | prescreened | 1 |
| zifM3PJTBKQ | prescreened | 2 |

## 정확한 추출 규칙과 T41 연동

파일과 id를 정렬해서 읽고 WebM이 없는 레코드는 제외한다. `.local/blocks`를 승인
묶음의 기준으로 삼아 전부 먼저 넣는다. 승인 표본에 n·영상별 상한을 적용하지 않는다.
남은 후보는 `.local/blocks-pre`에서 승인 id를 빼며 승인 표본까지 합한 영상별 수가
4 미만일 때만 추가한다. 이 우선순위로 “승인 전부”와 “나머지 영상당 최대 4개”를 적용한다.

남은 자리 R의 ceil(R/2)를 무작위 몫으로, floor(R/2)를 정신없는 몫으로 잡는다.
무작위 몫 안에서 한 턴 표본을 먼저 뽑아 전체 한 턴 수를 최소 5개로 맞추고,
그 몫의 남은 자리를 전체 후보로 채운다. `random.Random(seed)` 하나를 사용해
각 정렬 후보 목록을 섞으며 중복과 영상 상한을 건너뛴다.

정신없는 후보는 최초 후보 M개에서 speech_share 내림차순 상위 ceil(M/2)와
median_gap 오름차순 하위 ceil(M/2)의 **교집합**이다. 동점은 id 사전순으로 끊는다.
무작위 몫 선택 이후 교집합의 남은 후보를 같은 RNG로 섞어 선택한다. 교집합이 부족하면
전체 후보로 보충하고 dense_shortfall에 부족량을 남긴다. 현재는 정확히 20개를 채웠다.
한 턴의 median_gap=0, speech_share가 높은 특성상 한 턴도 이 후보에 들어갈 수 있다.
이 수치 기준은 실제 겹말·소음의 정답이 아니며 청취로 확인해야 한다.

각 줄의 audio.start/end는 원래 start/end와 같고, audio.path는 프로젝트 기준 상대 경로
`.local/audio/<video_id>.webm`이다. ±여유 구간은 표본 레코드에 섞지 않는다.
T41은 이 sample.jsonl을 그대로 읽을 수 있다. 이 데모는 학습 입력으로 연결하지 않았다.

동일 입력 재실행 시 표본과 meta가 바이트 단위로 유지되는 것을 확인했다.
`--keep-existing`은 기존 레코드·순서를 보존하고 아직 없는 새 승인 id만 뒤에 추가한다.
기존 사전 선별 표본이 승인돼도 추출 당시 origin을 유지한다. n을 넘을 수 있으며
새 사전 선별 표본을 보충하지 않는다. 동일 id의 재생성된 텍스트·경계로 기존 레코드를
덮어쓰지 않으므로 기존 답과의 연결도 보존한다. 기존 오디오가 사라지면 오류로 멈춘다.
입력 묶음 재생성 작업이 모두 끝난 뒤 표본 도구를 실행한다.

## 실행 방법

아래 명령은 모두 다음 위치에서 실행한다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
```

현재 만들어진 60개를 바로 판정하려면 다음만 실행한다. 클립은 이미 60개 있다.
사용자가 브라우저에서 표시된 주소를 열고, 종료할 때 Ctrl+C를 누른다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.serve --name 민수 --port 8767
# http://127.0.0.1:8767
```

샌드박스 밖에서 새 영상을 다운로드 → 분할·전사 → 묶음 재생성 → 표본 확장하려면
다음 순서대로 실행한다. 다운로드 한도에는 기존 다운로드 완료 51개·약 17.812시간도
포함되므로 기본 10개/5시간으로는 새 영상에 도달하지 않는다. 현재 승인 전체는
52개·약 17.931시간이므로 아래 60개/24시간 한도를 사용한다. 이 명령은 승인 목록
전체를 대상으로 하며 현재 미다운로드 승인 후보는 zn4tDPT4RLQ 하나다.

```powershell
# 1. 실제 다운로드 (외부 네트워크 필요)
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.collect download --max-videos 60 --max-hours 24

# 2. 새 파일만 기존 로컬 CUDA/Whisper 환경으로 분할·전사
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.segment .local/audio/zn4tDPT4RLQ.webm --source yt:zn4tDPT4RLQ

# 3. 승인 묶음과 사전 선별 분석 묶음을 차례로 재생성 (기존 로컬 토크나이저)
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.blocks
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.blocks --check
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.blocks --allow-prescreened --out-dir .local/blocks-pre
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.blocks --allow-prescreened --out-dir .local/blocks-pre --check

# 4. 기존 표본을 보존하고 새 승인 묶음만 추가
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.sample --seed 1 --n 60 --out .local/demo/sample.jsonl --keep-existing
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.sample --check --out .local/demo/sample.jsonl

# 5. 클립 재생성 후 청취 서버 시작
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.clips
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.serve --name 민수 --port 8767
```

행 복구 명령은 다음과 같으며 **이미 실행했다**. 현재 candidate 행에 다시 실행하면
안전 조건에 맞지 않아 오류가 난다. status=rejected와 정확한 note=duration_too_short,
사람 승인·180~10800초인 명시적 id만 복구하며 다른 거절 사유를 지우지 않는다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.collect retry-short-approved zn4tDPT4RLQ
```

처음부터 같은 표본을 재추출하거나 요약만 확인할 때:

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.sample --seed 1 --n 60 --out .local/demo/sample.jsonl
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.sample --check --out .local/demo/sample.jsonl
```

## 화면·단축키·답 파일

상단에 세 판정의 정의가 고정되고, 한 화면에 묶음 하나만 표시된다. 영상 제목은
보여주지 않고 id만 작게 표시한다. 전사와 직전 문맥은 함께 접힌 상태로 시작한다.
앞 2초·뒤 1초를 포함해 재생하고 끝에서 자동 정지한다. 다시 듣기 버튼도 있다.
클립이 있으면 WAV를 우선하며 없으면 WebM을 Range로 서빙한다.

| 키 | 동작 |
| --- | --- |
| 1 | 이야기 있음 → 저장 후 다음 |
| 2 | 한 줄 순간 → 저장 후 다음 |
| 3 | 없음 → 저장 후 다음 |
| 0 | 판정 불가 → 저장 후 다음 |
| Space | 재생·일시 정지 |
| J / K | 다음 / 이전 |
| T | 전사·직전 문맥 펼침·접힘 |
| C | 코멘트 입력으로 이동 |

입력 중 단축키는 작동하지 않는다. 코멘트 입력 후 판정 버튼을 누르면 함께 저장된다.
재실행하면 첫 미판정 표본부터 시작하며, 이전 판정은 J/K로 돌아가 바꿀 수 있다.
저장 성공 후에만 다음으로 이동한다. 마지막 표본은 전체 완료를 표시하며 화면에 남는다.

답은 `.local/demo/answers/<이름>.jsonl`에 id/verdict/comment/decided_at/listened/
opened_text로 기록한다. verdict는 story/moment/none/unsure다. 서버가 UTC 시각을
붙이며 같은 id 재판정은 기록을 추가한다. `tools.listen.storage.read_answers(path)`는
마지막 판정을 사용한다. 쓰기는 프로세스 내 잠금과 flush/fsync 후 원자적 교체로 처리한다.
같은 이름의 서버를 동시에 여러 개 실행하지 않는다. 깨진 JSONL은 무시하지 않고 오류로 멈춘다.

listened는 재생 중 시간 진행을 관측한 표시로, 완청 여부나 실제 청취·주의 집중을
증명하지 않는다. opened_text는 전사·문맥을 한 번이라도 펼쳤는지 표시한다.
재판정 때 기존 true 플래그를 보존한다. 표본이나 클립 갱신 후에는 서버를 재시작하고
브라우저도 새로 고친다. 클립 도구는 현재 표본 클립을 매번 덮어쓴다.

## 검증 결과와 한계

최종 관련 테스트는 **204개 통과**했다. 신규 T40 41개와 기존 수집기·검토 콘솔 회귀
163개다. 표본 결정성·승인 전부·상한·오디오 누락·한 턴·keep-existing, 답 파일 마지막 값,
저장·재판정·원자 교체 실패, 127.0.0.1 바인딩, Host/Origin, 경로 탈출 차단,
Range(일반/열린 끝/접미/잘못된 범위)·HEAD·클립 우선, PyAV 절단·EOF를 검사했다.
모든 테스트 서버는 finally에서 shutdown/server_close/join으로 종료했다.

```powershell
New-Item -ItemType Directory -Force .local/listen | Out-Null
.\.venv\Scripts\python.exe -X utf8 -B -m pytest tests/test_listen_sample.py tests/test_listen_http.py tests/test_listen_clips.py tests/test_listen_collect.py tests/test_collect.py tests/test_collect_approvals.py tests/test_collect_curated.py tests/test_review.py --basetemp .local/listen/pt -p no:cacheprovider -q --tb=short --junitxml=.local/listen/results.xml
```

`.local/listen/results.xml`에 결과가 있다. 첫 테스트 시도는 basetemp의 부모 디렉터리가
없어 setup 오류가 났고, 부모 생성 후 위 최종 명령으로 통과했다. 개발 중 발견한
합성 수집기 옵션 호환성 문제도 수정한 후 기존 수집기 테스트 전체를 통과했다.
전체 pytest는 실행하지 않았으며 알려진 `tests/test_label.py` 9개 실패는 수정하지 않았다.

실제 데이터 추가 확인:

- 실제 60개 WebM 구간의 WAV 생성 성공, 60개 모두 WAV 헤더·길이 확인.
- 실제 sample.jsonl로 임시 loopback 서버를 띄워 목록 60개·클립 우선 선택 확인,
  WAV 44바이트 Range가 206과 RIFF/WAVE 헤더를 반환하는지 확인 후 서버 종료.
  점검용 답 파일은 생성하지 않았다.
- 재추출 및 no-op keep-existing에서 sample·meta 바이트 보존 확인.
- HTML 내 JavaScript를 `.local/listen/client.js`로 추출해 `node --check` 통과.

3개 원본 시작 구간은 코덱/시작 타임스탬프로 약 20.5ms 짧고,
IOlxH67P1JM 마지막 묶음 클립은 원본 EOF 때문에 기대 여유보다 약 0.912초 짧다.
그 외 클립은 요청 구간과 0.01초 이내로 일치했다. 원본에 없는 앞뒤 여유를
인위적 무음으로 채우지 않으며 실제 끝까지 재생된다.

미검증: 브라우저를 열지 않아 실제 UI 레이아웃, 브라우저별 WebM 탐색·자동 정지·단축키,
스피커 출력과 실제 청취 감각은 확인하지 않았다. 다운로드·GPU 전사·새 영상으로 묶음
재생성은 외부 실행 항목이다. 아직 사람이 청취한 정답이나 T41 판정 비교 결과는 없다.
학습·배포·의존성 변경·커밋은 하지 않았다.
