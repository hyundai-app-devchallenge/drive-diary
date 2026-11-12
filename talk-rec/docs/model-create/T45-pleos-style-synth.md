# T45 — Pleos STT 말투를 반영한 합성 대화

PC 전사 비교에서 large-v3-long-prompt가 25개 중 9개, Pleos가 2개로 선택됐지만 앱 입력은 Pleos다. 따라서 합성 입력의 기본 열화를 `whisper`에서 `pleos`로 바꿨다. 실제 전사 원문은 문서·로그·테스트에 복사하지 않았다. 커밋하지 않았다.

## 구현

| 파일 | 변경 |
|---|---|
| `model-create/demo/pleos_profile.py` | 같은 id의 Pleos·참조 결과를 연결하고 클립별·전체 수치만 저장 |
| `model-create/synth/degrade.py` | 기본 `--style pleos`, 프로필 기반 분절·단답 삭제·연령별 오류·겹침 처리, 기존 `whisper` 선택 지원 |
| `model-create/synth/generate.py` | 시드로 배치 라벨을 포괄하는 예시 2~3개 선택, Windows 명령 길이 검사, 예시 복제 거부 |
| `model-create/prompts/synth-scene.md` | 짧은 어른 반응, 아이의 말 고치기·지시어, 끼어듦·화제 이탈·미완결 끝을 규칙으로 지정 |
| `model-create/prompts/synth-examples/{claude,agy}.json` | 12개 창작 예시의 패턴 이름을 기존 사전에 맞춤 |
| `model-create/synth/cards.py` | story 카드 약 30%에 화제 이탈 또는 미해결 종료 필드 추가 |
| `model-create/synth/report.py` | Pleos 프로필과 합성 출력 수치를 나란히 표시, 사람 검토 전에도 보고서 생성 |
| `model-create/tests/test_demo_pleos_profile.py`, `test_synth_pleos.py`, `test_synth_degrade.py` | 창작 입력으로 프로필·열화·예시·길이 제한·부분 성공 회귀 검사, 기존 Whisper 검사는 스타일 명시 |

`degrade`는 전체 카드 수와 성공 장면 수가 같을 필요가 없다. 각 장면의 카드 번호로 원래 카드를 찾아 라벨·패턴·경계 여부를 그대로 보존한다. 카드 누락·중복 번호는 거부한다.

Pleos 스타일은 화자 표기를 내보내지 않고 문장부호를 제거한다. 내비 발화는 텍스트와 시각을 그대로 통과시킨다. 레벨 0은 문장부호 정리·분절만 적용한다. 레벨 1은 프로필 강도, 2는 더 큰 단답·겹침 누락률과 단어 오류를 사용한다. 유아·초등 나이는 성인보다 높은 단어 치환·발음 뭉개기·단어 삭제 확률을 받으며 중고생을 자동으로 유아 취급하지 않는다. 겹친 발화는 한쪽을 삭제하거나 남은 쪽을 치환한다. 반복 환각이나 새 내비 문장을 추가하지 않는다.

레벨 1·2에서는 화자가 사라진 STT 흐름을 만들기 위해 1초 미만 간격의 발화들을 묶은 뒤 분절한다. 내비는 합치지 않는다. 측정한 줄 길이 히스토그램을 사용하되, 발화 끝의 짧은 잔여 줄이 과도하게 늘지 않도록 긴 목표 길이에 가중치를 준다. 실제 어절 경계 근처를 우선하고 시각은 글자 비율로 추정한다. 일반 발화의 기존 12초 상한을 유지한다. `--disable`과 세부 확률 직접 지정은 기존 Whisper 규칙용이다.

프로필 파일이 없으면 내장 길이 기본값 1/13/35/85자를 사용한다. 단답 삭제와 문자 오류의 내장 강도는 측정 스냅샷을 바탕으로 한 휴리스틱이다. 프로필의 문자 불일치율을 그대로 독립 오류 확률로 쓰지 않고 상한·축소를 적용한다. 프로필과 출력 통계는 `meta.json`에 함께 보존된다.

## 실제 프로필 수치

입력은 `.local/demo/asr/pleos.jsonl`과 `.local/demo/asr/large-v3-long-prompt.jsonl`이다. 실제 CLI 실행으로 25쌍을 처리했고 양쪽의 미대응 클립은 0개다. 출력 `.local/demo/pleos/profile.json`은 숫자와 null만 값으로 가지며 전사·클립 id를 포함하지 않는다. 클립은 공통 id 정렬 순서의 숫자 인덱스로 저장한다.

| 지표 | Pleos 대 참조 |
|---|---:|
| 클립 수 | 25 |
| Pleos / 참조 줄 수 | 483 / 177 |
| 줄 수 비율 | 2.7288 |
| 공백 포함 줄 글자 수 최소 / 중앙값 / p90 / 최대 | 1 / 13 / 35 / 85 |
| 줄 사이 간격 최소 / 중앙값 / p90 / 최대, 초 | 0 / 0 / 0 / 10.3 |
| 참조 짧은 줄 / 대응하지 않은 줄 | 3 / 2 |
| 짧은 답변 누락률 | 66.67% |
| 정규화 참조 / Pleos 글자 수 | 5,561 / 6,103 |
| 총 글자 수 비율 | 1.0975 |
| 시간 정렬 치환 / 삭제 / 삽입 글자 수 | 991 / 1,822 / 2,364 |
| 치환 / 삭제 / 삽입 비율, 참조 글자 수 분모 | 17.82% / 32.76% / 42.51% |
| 합산 문자 불일치율 | 93.09% |
| 반복 구간 수 Pleos / 참조 | 0 / 22 |

문자 비교는 공백·문장부호를 제외한다. 시간상 연결된 구간들을 묶어서 양쪽 텍스트를 각각 한 번만 이어 붙인 뒤 `difflib.SequenceMatcher(autojunk=False)`로 비교한다. 치환의 길이가 다르면 공통 길이를 치환, 남는 길이를 삭제·삽입으로 센다. 참조와 시간상 겹치지 않는 구간도 누락·삽입으로 포함한다. 클립별·정렬 구간별 계수도 JSON에 저장한다.

짧은 답변은 참조 줄의 공백 제외 길이가 1~2자인 경우다. 겹치는 Pleos 줄들에 정규화한 답변이 포함됐을 때만 대응으로 센다. 시간 겹침만으로는 검출 성공으로 세지 않는다. 반복 구간은 정규화한 4자 이상 줄의 연속 동일 출력 또는 2~30자 구절의 3회 이상 연속 반복이 있는 줄 수다. 모든 반복을 환각으로 판정하는 지표는 아니다.

## 실행 순서 — Windows PowerShell

작업 위치는 `C:\github\drive-diary\talk-rec\model-create`다. 아래 명령은 실제 전사를 화면에 출력하지 않는다. 생성 단계의 agy 호출은 사용자가 네트워크와 agy가 있는 환경에서 실행한다.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create

# 1. 실제 두 전사에서 수치 프로필 생성
.\.venv\Scripts\python.exe -X utf8 -B -m demo.pleos_profile --pleos .local/demo/asr/pleos.jsonl --ref .local/demo/asr/large-v3-long-prompt.jsonl --out .local/demo/pleos/profile.json

# 2. 200장 새 카드: story/moment/none = 80/40/80
.\.venv\Scripts\python.exe -X utf8 -B -m synth.cards --n 200 --seed 43 --out .local/synth/demo2/cards.jsonl

# 3. 사용자가 실행할 정확한 생성 명령: Gemini 3.8 Flash, medium
.\.venv\Scripts\python.exe -X utf8 -B -m synth.generate --cards .local/synth/demo2/cards.jsonl --backend agy --model gemini-3.8-flash-medium --out .local/synth/demo2/scenes.jsonl --seed 43 --concurrency 1 --wait-on-limit 10

# 4. 프로필 수준 Pleos 열화
.\.venv\Scripts\python.exe -X utf8 -B -m synth.degrade --cards .local/synth/demo2/cards.jsonl --scenes .local/synth/demo2/scenes.jsonl --out-dir .local/synth/demo2 --style pleos --profile .local/demo/pleos/profile.json --level 1 --seed 43

# 5. 사람 검토 전 수치 보고서
.\.venv\Scripts\python.exe -X utf8 -B -m synth.report --blocks .local/synth/demo2/blocks.jsonl --labels .local/synth/demo2/labels.jsonl --profile .local/demo/pleos/profile.json --real-glob .local/blocks/*.jsonl --out .local/synth/demo2/report.md

# 6. 훑어보기: 브라우저에서 http://127.0.0.1:8769
.\.venv\Scripts\python.exe -X utf8 -B -m tools.skim.serve --data-dir .local/synth/demo2 --name tester --limit 60 --seed 43 --port 8769
```

검토 후에는 서버를 종료하고 아래 명령으로 사람 판정도 합친다. 실제 전사 원문을 검토 코멘트에 붙여 넣지 않는다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m synth.report --skim .local/synth/demo2/skim/tester.jsonl --profile .local/demo/pleos/profile.json --out .local/synth/demo2/report.md
```

생성은 같은 명령으로 재개한다. 바뀐 프롬프트·예시·시드로 이전 생성 결과를 이어 쓰지 않는다. 별도 `demo2` 경로를 쓰는 이유다. 형식 검증은 줄 수 ±40%, 전체 길이(초) ±50%로 완화했다. 12개 예시 전체와 기존 결과에 대한 문자 4-gram 유사도 0.85 이상을 거부한다. 예시의 note는 출력에 포함하지 않는다.

기본 배치는 최대 4장이다. 배치별 선택 예시는 모든 카드 라벨을 포괄한다. UTF-16 Windows 명령 길이를 따옴표·종결 NUL까지 포함해 계산하며 `pipeline/label.py`의 `AGY_COMMAND_LIMIT=32000` 이내에서 예시를 3개에서 2개로 줄이거나 배치를 줄인다. 라벨이 3종이면 예시 3개를 유지하고 배치를 줄인다. 이번 200장 카드의 50배치 명령 길이는 `agy.exe` 경로 기준 최소 9,482, 최대 11,417 단위였다. 실제 실행 파일 경로 길이도 호출 전에 검사한다.

## 실행 검증

기존 200장 중 성공한 144장 파일을 그대로 읽어 아래 명령을 실행했다. 기존 demo 출력은 보존하고 검증 출력만 별도 디렉터리에 저장했다.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m synth.degrade --cards .local/synth/demo/cards.jsonl --scenes .local/synth/demo/scenes.jsonl --out-dir .local/synth/t45-check --style pleos --profile .local/demo/pleos/profile.json --level 1 --seed 43
.\.venv\Scripts\python.exe -X utf8 -B -m synth.report --blocks .local/synth/t45-check/blocks.jsonl --labels .local/synth/t45-check/labels.jsonl --profile .local/demo/pleos/profile.json --real-glob .local/t45-no-real/*.jsonl --out .local/synth/t45-check/report.md
```

| 지표 | 실제 Pleos | 기존 144장 변환 |
|---|---:|---:|
| 클립·장면 수 | 25 | 144 |
| 줄 수 | 483 | 1,225 |
| 최소 / 중앙값 / p90 / 최대 글자 수 | 1 / 13 / 35 / 85 | 1 / 15 / 32 / 85 |
| 참조·깨끗한 원본 줄 수 | 177 | 1,211 |
| 짧은 답변 수 / 삭제 수 | 3 / 2 | 4 / 4 |
| 전체 발화 삭제 수 | — | 17 |
| 겹침 발화 삭제 수 | — | 11 |
| 전부 삭제 시 한 줄 복구 | — | 0 |

전체 줄 수 비율은 입력의 성격이 달라 직접 맞출 목표가 아니다. 실제 비교의 분모는 길게 묶인 참조 STT 줄이고 합성의 분모는 깨끗한 화자별 발화다. 이번 기존 장면에는 짧은 답변이 거의 없으므로 프롬프트를 바꿔 새로 생성한 결과를 사람이 다시 검토해야 한다.

관련 synth·demo·ASR·skim·공통 스키마 회귀 검사는 아래 명령으로 실행해 **920개 통과, 2개 건너뜀**을 확인했다. 테스트는 창작·임시 입력을 사용한다. `tests/test_label.py`의 기존 무관 실패 9개는 수정하지 않았다.

```powershell
$t45Tests = Get-ChildItem tests/test_*.py | Where-Object { $_.Name -match '^test_(synth_|demo_|asr_|skim_|schema)' } | ForEach-Object { $_.FullName }
.\.venv\Scripts\python.exe -X utf8 -B -m pytest @t45Tests --basetemp .local/pytest-t45 -p no:cacheprovider -q --tb=short
```

## 해석의 한계

large-v3-long-prompt는 사람이 확정한 정답 전사가 아니다. 93.09%는 정확한 음성 인식 오류율(CER)로 해석할 수 없으며 참조 오류와 양쪽 시각 어긋남·발화 범위 차이를 포함한다. 독립적인 청취 정답 없이는 어떤 치환이 Pleos의 잘못인지 판단할 수 없다.

짧은 답변 누락률의 분모는 3개뿐이다. 참조도 단답을 놓칠 수 있고 긴 참조 줄 안에 붙은 단답은 이 지표에 포함되지 않는다. 겹치는 줄의 부분 문자열로 확인하므로 우연한 포함도 가능하다. 표본에 연령별 정답 주석이 없어 유아·초등 가중치와 겹침 강도는 휴리스틱이다.

Pleos 줄 사이 간격 중앙값·p90이 0초인 것은 출력 타임스탬프의 특성도 반영한다. 실제 침묵 길이가 0이라는 뜻이 아니다. 합성의 시간은 오디오 강제 정렬이 아니라 글자 비례 추정이다. 짧은 발화·긴 침묵·내비의 분포까지 한 번에 정확히 재현하지는 않는다.

라벨은 새로 추론하거나 변경하지 않는다. 심한 열화로 읽는 사람에게 라벨 근거가 덜 보일 수 있으므로 `--level 1` 결과를 우선 보고, `mixed` 또는 레벨 2는 별도로 검토한다. 이 작업에서는 네트워크·adb·agy 실행을 하지 않았으며 새 200장 생성은 사용자 실행 단계다.
