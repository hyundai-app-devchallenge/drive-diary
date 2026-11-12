# T02d — 짧은 영상을 검토 후보로 복구

- 이유: 실제 평가는 사람이 승인한 차 안 영상의 사용 구간만 쓴다. 아이+가족 영상에는 1~7분짜리가 많아, 학습 밀도를 위한 8분 하한 때문에 검토 기회까지 사라졌다.
- 새 명령: `retry-short`는 사람 승인 없이 `rejected` → `candidate`만 바꾼다. 거부 사유가 `duration_too_short` 하나이고 길이가 기본 60~10800초일 때만 복구하며, 그 사유만 note에서 지운다. 출처 등 다른 메모는 보존한다.
- 보호: 모든 ID를 먼저 검사한다. 다른 거부 사유·사람 reject·잘못된 상태/길이·없는 ID가 하나라도 있으면 전체 무변경이며, 실패 ID와 이유 및 요약 JSON을 출력한다. 승인 기록·음성은 건드리지 않는다.
- 길이 규칙: 사람 승인 다운로드는 `min(60, --min-duration)`초 이상이다. Claude 승인은 기존 하한을 유지하고, 다른 자동 거부·3시간 상한·다운로드 개수/시간 제한도 유지한다. 시간 예산은 실제 영상 길이로 검사해 90초 영상도 작은 예산 안에서 받을 수 있다. 기존 `retry-short-approved`는 종전 조건을 유지한다.
- 검토 화면: candidate 목록을 시작할 때 읽으므로 복구 후 검토 서버를 다시 켠다. 화면 코드는 변경하지 않았다. 미승인 후보를 다시 필터링할 때도 `refilter --min-duration 60`을 쓴다.

실행 위치: `talk-rec/model-create`. 아래 수집 명령은 사용 예시이며 이번 작업에서는 실행하지 않았다.

```powershell
.\.venv\Scripts\python.exe -m pipeline.collect retry-short VIDEO_ID_01 VIDEO_ID_02
.\.venv\Scripts\python.exe -m pipeline.collect retry-short VIDEO_ID_01 --min-duration 30
# 평가용 아이+가족 신규 수집: 기본값 480초는 유지하고 명시적으로 60초 지정
.\.venv\Scripts\python.exe -m pipeline.collect discover --queries data/queries-kids.txt --min-duration 60
.\.venv\Scripts\python.exe -m pipeline.collect expand --channel-query "드라이브" --min-duration 60
```

가짜 manifest·메타데이터·음성 파일로 검증했다. 수집기 테스트 전체 **204개 통과**, 기존 T40 관련 테스트 **3개 통과**. 단독 짧은 거부 복구, 여러 ID 중 실패 시 파일 바이트/수정 시각 보존, 사람 reject 차단, 60초/3시간 경계, 사용자 지정 하한, 사람 승인 90초 다운로드 성공·Claude 승인 거부, 승인 없는 다운로드 차단을 확인했다.

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_collect.py tests/test_collect_approvals.py tests/test_collect_curated.py tests/test_collect_short.py --basetemp .local/pytest-t02d -p no:cacheprovider -q
.\.venv\Scripts\python.exe -m pytest tests/test_listen_collect.py --basetemp .local/pytest-t02d/listen -p no:cacheprovider -q
```

네트워크·설치·커밋·실제 수집 없이 작업했다. 기존 `data/*.tsv`와 `.local/` 실물은 수정하지 않았고 테스트 임시 파일은 `.local/pytest-t02d` 아래에만 만들었다.
