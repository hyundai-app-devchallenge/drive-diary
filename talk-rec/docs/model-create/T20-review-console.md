# T20 영상 사람 검토 콘솔

작성일: 2026-11-11

## 만든 것

비개발자가 수집된 영상을 듣고 승인·거절·보류와 짧은 코멘트만 남기는 한국어 로컬 웹 콘솔을 만들었다. 점수나 평점 입력은 없다. Python 표준 라이브러리 HTTP 서버를 `127.0.0.1`에만 열며, HTML 한 페이지와 vanilla JavaScript를 사용한다. 페이지에 외부 스크립트·폰트·이미지·유튜브 임베드를 넣지 않았다. 유튜브는 새 탭 링크로만 연다.

- 매니페스트 상태가 `downloaded` 또는 `candidate`인 영상마다 카드를 표시한다. 미검토·승인·거절·보류·전체 필터와 받은 영상만 필터, 진행률을 제공한다. 사람 미검토가 먼저이고 그 안에서 받은 영상이 먼저다. Claude 판정은 사람 검토 완료로 세지 않는다.
- 제목, 채널, 길이, 업로드 날짜, 처음 찾은 검색어, 분할을 표시한다. 전사가 있으면 말 비중·발화 수·분당 발화·한글 비율·발화별 `mean_word_probability`의 산술평균을 표시한다. 통계 파일의 말 비중은 `(audio_duration - vad_removed_seconds) / audio_duration`이다. 통계가 없고 전사만 있으면 겹침을 합친 발화 구간 길이로 계산한다.
- silver 라벨이 있으면 실행 디렉터리별로 `skip` 비율과 표본 수를 표시한다. v1 발화 ID(`#`)와 v2 묶음 ID(`@`)를 구분하고 실행 간 비율을 섞지 않는다. `run.jsonl`, `unclear.jsonl`, `.chunks`는 집계하지 않는다. 라벨이 없어도 사용할 수 있다. `--labels`로 특정 실행 디렉터리를 지정할 수 있다.
- 듣기 링크는 발화 순서의 10·30·50·70·90% 위치를 사용한다. 인덱스는 `floor((발화 수 - 1) × 분위수)`이고 재생 초는 발화 시작 시각을 내림한다. 각 위치부터 최대 3개 전사 줄을 보여 준다. 발화가 적으면 링크가 중복되거나 남은 줄이 3개보다 적을 수 있다. 전사가 없으면 영상 길이의 같은 비율을 사용한다.
- `claude`의 현재 판정·이유는 별도 색상의 “사전 의견”으로 표시한다. 사람 판정과 구별한다.
- 판정은 서버에서 검증하고 `approved.tsv`를 fsync 후 원자적으로 교체한다. 같은 영상의 기존 행을 덮어쓰므로 사전 의견을 사람 판정으로 바꾸거나 나중에 결정을 바꿀 수 있다. 저장 실패 시 기존 파일을 보존한다.
- 사람 판정별 전체 건수와 매니페스트 split 기준 사람 승인 고유 채널 수를 제공한다. dev·test 각각 10개 채널에 도달하면 표시한다. `/api/state`, `/api/summary`, `POST /api/decision`을 제공한다.
- Gold 서버의 Host·Origin 검사, 제한된 요청 본문 처리, Windows 응답 유실을 막는 연결 종료 전 제한된 peer drain 패턴을 재사용했다. 서버 요청 로그와 오류 traceback에 전사 본문을 남기지 않는다.

## 실행

PowerShell에서 `C:\github\drive-diary\talk-rec\model-create`를 현재 디렉터리로 두고 다음 한 명령을 실행한다. `user`는 실제 검토자 이름으로 바꿀 수 있다.

```powershell
.\.venv\Scripts\python.exe -m tools.review.serve --name user
```

브라우저에서 `http://127.0.0.1:8766`을 연다. 종료는 서버 터미널에서 Ctrl+C다. 포트가 사용 중이면 `--port`로 바꾼다. 검토자 이름은 비어 있거나 `claude`일 수 없다.

## 조작

| 키 또는 버튼 | 동작 |
| --- | --- |
| A / 승인 | 현재 영상 승인과 코멘트를 즉시 저장 |
| R / 거절 | 현재 영상 거절과 코멘트를 즉시 저장 |
| H / 보류 | 현재 영상 보류와 코멘트를 즉시 저장 |
| J / ↓ / → | 다음 카드 |
| K / ↑ / ← | 이전 카드 |
| C | 현재 카드의 코멘트 칸으로 이동 |
| Enter / 코멘트 저장 | 현재 사람 판정을 유지하고 코멘트 저장 |

코멘트 입력 중에는 A/R/H/J/K/C가 글자로 입력된다. Enter는 저장하며 한글 조합 중 Enter는 가로채지 않는다. 최초 판정이 없는 카드에서는 승인·거절·보류 중 하나를 선택해야 저장된다. 코멘트만 입력하고 이동하면 아직 저장된 것이 아니다. 필터를 바꾸거나 다른 판정을 저장하는 동안의 입력 초안은 현재 페이지 메모리에 유지하지만, 새로고침·종료하면 저장하지 않은 초안은 사라진다.

## 승인 파일과 학습까지의 흐름

`data/approved.tsv`는 `video_id`, `decision`, `reason`, `decided_by`, `comment`의 5열이다. 예전 4열도 읽으며 코멘트를 빈 문자열로 취급한다. 명시적 `migrate_approvals(path)`와 콘솔 시작 시 마이그레이션은 한 번만 파일을 바꾸고, 같은 내용으로 재실행하면 바이트·수정 시각을 유지한다. 읽기와 `--check`는 파일을 고치지 않는다. 모든 승인 저장은 5열이며 코멘트의 탭·개행·제어 문자를 공백으로 정리하고 최대 500자로 자른다.

`decided_by=claude`는 사전 선별 의견이다. 그 외 비어 있지 않은 이름은 사람이다. `pipeline.collect.human_decision(row)`와 `is_human_approved(video_id, approvals)`를 추가했다. 콘솔 저장은 `decided_by=<검토자>`, `reason=human review`다. 영상별로 한 행만 보관하므로 사람이 저장하면 이전 Claude 의견을 포함한 과거 행은 남지 않는다. 판정 이력 보관 기능은 없다.

흐름은 메타데이터 수집 → 사전 선별 → 음성 내려받기·전사 → 콘솔 사람 검토 → 사람 승인 여부를 확인하는 후속 묶음 생성·라벨링·학습이다. **T20은 승인 형식과 조회 함수만 준비했다. 수집기의 다운로드 관문과 `--check` 판정 규칙은 변경하지 않았다. 실제 학습 진입 제한은 T21 및 후속 파이프라인 작업에서 적용해야 한다.**

콘솔 거절은 판정만 기록한다. 매니페스트나 음성을 옮기지 않는다. 받은 영상에 아직 적용하지 않은 사람 거절이 있으면 `pipeline/collect.py apply-decisions` 실행 안내를 보여 준다. 실제 보관 위치 변경은 수집기의 기존 책임이다.

기준 영상 `IOlxH67P1JM`, `pkGAxu0g0vQ`, `zn4tDPT4RLQ`를 `decided_by=user`, `decision=approve`, `comment=기준 영상: 무편집 차 안 가족 대화`로 기록했다. 기존 `3eKrFeTGupY`의 사람 거절·이유는 유지했다. `zn4tDPT4RLQ`는 현재 길이 자동 제외 상태라 카드에 나타나지 않는다. T21에서 최소 길이와 수집 상태를 재평가하기 전에는 승인 행만으로 자동 제외를 우회하지 않는다.

## 검증 결과와 알아 둘 점

- 관련 테스트: `tests/test_review.py`, `tests/test_collect_approvals.py`, 기존 수집기 테스트를 합쳐 **163개 통과**. 4→5열 마이그레이션과 멱등성, HTTP 세 판정·코멘트 저장, Claude 행 교체, 잘못된 입력의 무기록, 저장 실패 보존, 재시작 복원, 필터·정렬·지표·요약·채널 중복 제거, 손계산 듣기 링크, 서버 로그 비노출을 검증했다.
- 실행 명령: `.\.venv\Scripts\python.exe -X utf8 -B -m pytest --basetemp .local/review/pt -p no:cacheprovider -q --tb=short`. **전체 1,262개 중 1,253개 통과, 9개 실패**. 전체 통과 완료 조건은 충족하지 못했다.
- 실패 9개는 모두 `tests/test_label.py`다. 기존 v1 `pipeline/label.py::render_prompt`는 `{{CLIP}}`을 치환하지만, 현재 v2 `prompts/labeler.md`는 `{{BLOCKS}}`를 사용한다. 프롬프트 전개·fake 실행·재시작·동시성 테스트가 그 불일치로 실패한다. T20 수정 금지 범위여서 라벨러·프롬프트·그 테스트를 변경하지 않았다.
- `.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py --check`: **182행, 오류 0건**.
- 실제 데이터로 `/`, `/api/state`, `/api/summary` HTTP 200을 확인했고 승인 파일이 바뀌지 않는 것을 확인했다. 검토 카드 62개, 사람 판정 승인 3·거절 1·보류 0, 미검토 전체 178개다. 표시 대상 진행률은 2/62이고, 승인 채널은 train 1·dev 2·test 0이다. 전체 요약에는 자동 제외된 기준 영상도 포함되므로 카드 진행률과 분모가 다르다.
- Node로 JavaScript 구문을 확인했다. 브라우저는 열지 않았다. **UI 배치·시각적 구분·실제 브라우저 단축키·유튜브 재생은 검증하지 않았으며 디자이너 확인이 필요하다.** 외부 네트워크는 사용하지 않았다.
- 콘솔과 수집기·수동 편집 등 승인 파일 작성자를 동시에 실행하지 않는다. 매니페스트·전사·라벨은 시작 시 읽으므로 수집 결과가 새로 생기면 서버를 재시작한다. 저장한 사람 판정은 즉시 읽어 복원된다.
- `.local/utterances`, `.local/labels`, `.local/audio`, `pipeline/label*`, `pipeline/windows*`, `pipeline/blocks*`, `common/schema.py`, `tools/gold/`는 수정하지 않았다. 의존성 변경·커밋은 없고 검증용 서버는 모두 종료했다.

변경 파일: `tools/review/__init__.py`, `tools/review/serve.py`, `tools/review/index.html`, `tests/test_review.py`, `tests/test_collect_approvals.py`, `pipeline/collect.py`, `pipeline/collect.md`, `data/approved.tsv`, 이 작업 기록.
