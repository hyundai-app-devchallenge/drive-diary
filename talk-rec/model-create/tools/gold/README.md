# T07: 한국어 gold 라벨링

`model-create`에서 실행합니다. 추가 설치나 네트워크 연결은 필요하지 않습니다.

```powershell
.\.venv\Scripts\python.exe -B -m tools.gold.serve --name 민수 --split dev --sample 100 --seed 7
```

콘솔의 `http://127.0.0.1:8765`를 브라우저에서 엽니다. 종료는 Ctrl+C입니다.
`--split train dev test`, `--source yt:VIDEO_ID yt:OTHER_ID`로 제한할 수 있습니다.
두 옵션을 생략하면 모든 split/source를 사용합니다. 포트 변경은 `--port 8766`입니다.
서버는 항상 127.0.0.1에만 바인딩합니다. 한 라벨러 이름당 서버 하나만 실행하세요.

## 화면과 키

클립의 전체 발화를 시간순으로 보여 줍니다. 맨 앞에는 이전 클립의 직전 최대
3턴도 표시하여 경계를 넘는 문맥을 보존합니다. 현재는 노란색, 정확한 모델
문맥은 파란색, 그 밖의 이전 발화와 미래 발화는 회색입니다. 20초 초과 간격을
넘은 과거는 회색으로 표시하며 판정 근거로 사용할 수 없습니다. 간격은 인접한
발화의 시작과 이전 발화의 끝 사이의 초 단위 값입니다. 겹치는 발화는 음수입니다.
현재 턴의 YouTube 링크를 누르면 그 시각부터 새 탭에서 듣습니다. 페이지는
외부 리소스·CDN을 요청하지 않습니다. 기준 문서는 `rubric.md`이며 화면에 요약이 있습니다.

| 키 | 동작 |
| --- | --- |
| 0–3 | 점수 지정, 저장 후 다음 턴 |
| R / M / F / W / A | recall / moment / feeling / promise / family_fact 전환 |
| C / E / D / O / G | command / external / sensitive / optout / garbled 전환 |
| S | skip 전환 (켜면 점수 0, kind/exclude 비움) |
| P | 바로 이전 발화의 저장된 라벨과 메모 복사, 저장 후 다음 턴 |
| ← / → | 이전 / 다음 턴 (클립 경계 포함) |

메모·입력창·클립 선택에 포커스가 있을 때 단축키는 작동하지 않습니다.
2·3점에 종류가 아직 없으면 한국어 안내와 함께 저장·이동을 보류합니다.
종류를 고르면 저장 후 다음 턴으로 갑니다. 0·1점으로 낮추면 허용되지 않는 종류를
비웁니다. 나머지 유효한 변경은 즉시 저장합니다. 실패 시 화면의 저장 재시도 버튼을
사용하세요. 미저장 상태로 닫으면 브라우저가 경고합니다.

## 저장과 공통 표본

`data/gold/<name>.jsonl`에는 공통 스키마의 id, story, kind, exclude, skip,
labeler(`human:<name>`), note만 저장합니다. 전사 본문을 메모에 인용·붙여넣지 마세요.
각 변경은 임시 파일의 flush/fsync 후 원자적 교체로 저장합니다.
`data/gold/<name>.position.json`에는 마지막으로 선택한 id만 저장하므로 재실행하면
그 턴으로 돌아갑니다. 위치 파일이 없으면 첫 미라벨 턴부터 시작합니다.
다른 조건으로 실행해도 기존 JSONL의 다른 id 라벨을 보존합니다.

`--sample N`은 **최소 N개 창**을 목표로 전체 클립을 뽑습니다. 정확히 N개가 되도록
클립을 자르지 않으므로 마지막 클립만큼 초과할 수 있습니다. N이 전체보다 크면
모두 선택합니다. source별 클립을 seed로 섞은 뒤 source를 돌아가며 하나씩 뽑아
source 편중을 줄입니다. source의 창 개수 비례 배분은 아닙니다.
같은 입력·필터·N·seed는 같은 id 집합을 만듭니다. 선택된 id는
`data/gold/sample-<seed>.txt`에 한 줄씩 저장합니다. 여러 사람은 이름만 바꾸고 같은
옵션을 사용하세요. LLM 라벨러에도 이 파일의 id 목록을 사용하면 됩니다.
동일 seed 파일이 다른 선택을 담고 있으면 덮어쓰지 않고 오류를 냅니다.

## 일치도

```powershell
.\.venv\Scripts\python.exe -B -m tools.gold.agreement data/gold/민수.jsonl data/gold/지수.jsonl
.\.venv\Scripts\python.exe -B -m tools.gold.agreement data/gold/민수.jsonl .local/labels/llm.jsonl --json
```

입력 2개 이상을 주면 모든 쌍의 공통 id를 비교합니다. 중복 id나 스키마 오류는
거부합니다. 사람/LLM 파일 모두 공통 스키마를 사용하며 추가 메타데이터도 허용합니다.
한쪽이라도 skip이면 이야기 지표에서 제외하고 별도로 셉니다.
binary story>=2의 정확 일치율·Cohen κ, story 0–3의 정확 일치율·이차 가중 κ를
계산합니다. 모든 kind/exclude 값의 유무와 skip의 일치율·κ는 공통 id 전체를
사용합니다. 일치율은 0–1 비율입니다. 공통 id가 없거나 기대 불일치가 0인 κ는
Markdown에서 `n/a`, JSON에서는 `null`입니다. 한쪽만 상수이고 다른 쪽이 변하면
기대 불일치가 존재하므로 κ를 정상 계산합니다.

## 검증

```powershell
New-Item -ItemType Directory -Force -Path .local/gold | Out-Null
.\.venv\Scripts\python.exe -B -m pytest --basetemp .local/gold/pytest -p no:cacheprovider
```

HTTP 경로: `GET /`, `GET /api/state`, `GET /api/clip?index=0`,
`POST /api/label` (공통 라벨 객체; labeler 생략 가능), `POST /api/position` (`{"id":"..."}`).
JSON POST만 허용하며 잘못된 라벨은 HTTP 400, 잘못된 Host/Origin은 403입니다.
테스트는 실제 HTTP 서버를 켜고 종료하며, 이 환경에서는 시각적 UI 검증을 하지 않았습니다.
