# 소리 듣고 판정 (T40, 데모 전용)

`model-create`에서 기존 `.venv`를 사용한다. 표본·판정·클립은 모두 `.local/demo/`에
있으며 학습에 사용하지 않는다. 원래 묶음과 전사 파일은 변경하지 않는다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.sample --seed 1 --n 60 --out .local/demo/sample.jsonl
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.sample --check --out .local/demo/sample.jsonl
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.clips
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.serve --name 민수 --port 8767
```

사용자가 브라우저에서 `http://127.0.0.1:8767`을 연다. 종료는 Ctrl+C.
같은 이름으로 서버를 동시에 두 개 실행하지 않는다. 서버는 시작 시 표본을 읽으므로
표본이나 클립을 갱신한 뒤 서버를 재시작하고 페이지를 새로 고친다.

## 표본 규칙

1. 파일 경로·id를 정렬하여 읽고, `.local/audio/<video_id>.webm`이 실제로 있는
   `.local/blocks/*.jsonl` 레코드를 모두 먼저 넣는다. 승인 표본에는 영상당 4개
   상한과 목표 n을 적용하지 않는다. 승인 전부 포함이 우선이다. 원본 묶음 레코드
   전체를 보존하고 audio(path/start/end), origin만 추가한다. audio 시간은 원래
   묶음 경계이며 ±여유 시간은 재생·클립 단계에서만 적용한다.
2. 나머지는 `.local/blocks-pre/*.jsonl`에서 승인 id를 제외하고 채운다. 승인 표본까지
   합한 영상별 개수가 4 미만일 때만 사전 선별 표본을 추가한다. 따라서 이미 승인
   묶음이 4개 이상인 영상에서는 사전 선별 묶음을 추가하지 않는다.
3. 남은 수 R 중 ceil(R/2)는 무작위, floor(R/2)는 정신없는 구간 몫이다.
   무작위 몫에서 먼저 전체 한 턴 수가 5개가 될 때까지 한 턴 후보를 무작위로
   뽑고, 남은 무작위 몫을 전체 후보에서 뽑는다. 한 턴은 `len(turns)==1`이다.
   한 턴 최소 5개를 채울 수 없거나 무작위 몫이 모자라면 쓰기 전에 오류로 중단한다.
4. 정신없는 후보는 최초 사전 선별 후보 M개에서 speech_share 내림차순 상위
   ceil(M/2)와 median_gap 오름차순 하위 ceil(M/2)의 **교집합**이다. 동점은 id
   사전순으로 끊는다. 무작위 선정 뒤 이 교집합에서 같은 RNG로 무작위 추출한다.
   각 추출은 정렬된 후보를 `random.Random(seed).shuffle`로 섞고 중복·영상 상한을
   건너뛴다. 정신없는 후보가 부족하면 전체 잔여 후보로 채우고 meta의
   `dense_shortfall`에 부족량을 남긴다. 전체 후보도 부족하면 실제 n이 목표보다
   작을 수 있으며 meta의 actual_n 및 통계에서 확인한다.
5. 원본의 짧은 묶음과 한 턴을 유지하므로 30초 하한으로 거르지 않는다.
   30~90초를 중심으로 보되 한 줄 순간·없음의 대조 표본을 포함하기 위함이다.
   길이는 원래 경계로 계산하며 묶음을 임의로 늘리거나 합치지 않는다.
6. 동일 입력·seed·n은 순서와 JSONL 바이트까지 같다. 내용이 같으면 sample과
   meta를 다시 쓰지 않아 생성 시각도 보존한다. 입력 묶음 재생성 중에는 실행하지 않는다.

묶음 재생성으로 승인 표본이 늘었을 때:

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.sample --seed 1 --n 60 --out .local/demo/sample.jsonl --keep-existing
.\.venv\Scripts\python.exe -X utf8 -B -m tools.listen.clips
```

`--keep-existing`은 기존 레코드·id·순서를 바꾸지 않고 아직 없는 승인 id만 정렬하여
추가한다. 기존 사전 선별 표본이 승인되더라도 origin은 추출 당시 값을 보존한다.
n을 넘을 수 있고, 새 사전 선별 표본으로 빈자리를 보충하지 않는다. 기존 오디오가
없어지면 기존 id 보존과 오디오 누락 제외를 동시에 만족할 수 없으므로 오류로 멈춘다.
기존 id의 경계가 재생성 후 바뀌어도 기존 표본·기존 판정 연결을 유지한다. 의도적으로
새 표본을 만들려면 다른 --out을 쓰되 서버의 기본 입력은 sample.jsonl임에 유의한다.

meta는 seed, 목표 n, 실제 n, 생성 UTC 시각, 규칙, 요약, `demo_only: true`를 기록한다.
`--check`는 저장된 표본의 출처별·영상별 수, 한 턴 수, 길이 분포를 읽기 전용 출력한다.

## 재생·판정

전사와 직전 문맥은 매 묶음마다 접힌 상태로 시작한다. 제목은 표시하지 않는다.
Space 재생/정지, J 다음, K 이전, T 전사·문맥, C 코멘트, 1 이야기 있음,
2 한 줄 순간, 3 없음, 0 판정 불가. 코멘트 입력 중 단축키는 작동하지 않는다.
코멘트를 쓴 뒤 판정 버튼을 누르면 함께 저장한다. 판정 저장 성공 후 다음으로 이동하고,
실패하면 같은 화면에 남는다. 마지막 표본은 완료 표시와 함께 유지한다.

원본 WebM은 `/audio?id=<묶음 id>`로 전송하며 단일 byte Range/HEAD를 지원한다.
앞 max(0,start-2)부터 end+1까지 재생하고 끝에서 멈춘다. `tools.listen.clips`는 설치된
PyAV로 같은 구간의 16kHz mono 16bit WAV를 원자적으로 생성한다. 외부 ffmpeg 실행이나
추가 설치는 필요 없다. Windows에서 `:`가 파일명으로 금지되므로 id
`yt:VIDEO_ID@N`의 클립 파일명은 **`yt_VIDEO_ID@N.wav`**이다. 화면은 이 파일을 우선한다.
원본 앞부분의 코덱 지연이나 원본 EOF에서는 여유 구간이 짧을 수 있다. clips는 매번
표본 클립을 덮어쓰므로 표본 경계를 바꾼 뒤 다시 실행해야 한다. 오디오 내용을 실제로
듣거나 브라우저 탐색 호환성을 확인하는 일은 사람이 한다.

답은 `.local/demo/answers/<이름>.jsonl`에 id, verdict(story/moment/none/unsure),
comment, decided_at(UTC), listened, opened_text만 기록한다. 동일 id 재판정은 과거 줄을
보존해 추가하며 `tools.listen.storage.read_answers(path)`가 마지막 것을 반환한다.
쓰기 잠금 아래 기존 바이트와 새 줄을 임시 파일에 flush/fsync한 뒤 원자적으로 교체한다.
부분 JSONL은 조용히 무시하지 않고 오류를 낸다. listened는 플레이어 시각이 실제 재생 중
앞으로 움직인 관측값이며 완청·주의 집중을 보증하지 않는다. opened_text는 해당 표본에서
전사 패널을 한 번이라도 열었는지 기록한다. 재판정 시 기존 true 플래그를 보존한다.

HTTP: `GET /`, `GET /api/state`, `GET|HEAD /audio?id=...`,
`POST /api/decision` JSON `{id,verdict,comment,listened,opened_text}`.
decided_at은 서버에서 붙인다. 서버는 127.0.0.1만 바인딩하고 Host/Origin을 확인하며,
사용자 경로를 받지 않고 표본에 등록된 파일만 전송한다. 외부 CDN·폰트를 사용하지 않는다.

## 테스트

```powershell
New-Item -ItemType Directory -Force .local/listen | Out-Null
.\.venv\Scripts\python.exe -X utf8 -B -m pytest tests/test_listen_sample.py tests/test_listen_http.py tests/test_listen_clips.py tests/test_listen_collect.py --basetemp .local/listen/pt -p no:cacheprovider -q --tb=short
```

합성 로컬 fixture와 loopback HTTP만 사용하며 모든 테스트 서버는 finally에서 종료한다.
