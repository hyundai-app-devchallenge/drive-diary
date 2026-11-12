# T02c — 수집기 비영상 항목 건너뛰기

- 증상: `expand --channel-query` 결과에 재생목록 ID(`PL9DWZNtmXG04MIbaAmyZB79JjTBproz5_`)가 섞이면 `Invalid YouTube video id`로 전체 중단(exit 2).
- 원인: 검색·채널 목록 결과를 영상으로 간주하고, 후보 저장 시점의 엄격한 ID 검사까지 그대로 전달했다.
- 수정: `discover`와 `expand`의 공통 후보 처리에서 `[A-Za-z0-9_-]{11}` 형식이 아닌 결과를 저장·상세 메타데이터 조회 전에 건너뛴다. 요약 JSON에 `skipped_non_video`를 추가하고 채널·검색어별 개수를 합산한다. 캐시 결과에도 적용하며, 직접 지정한 잘못된 `video:` 입력은 기존처럼 exit 2로 실패한다.
- 테스트: 가짜 yt-dlp 결과의 영상 2개와 재생목록 1개에서 영상만 후보가 되는지 확인했다. 검색·채널 목록·채널 내 검색, 캐시 재사용, 채널/쇼츠 묶음/빈 ID/누락 ID/잘못된 형식, 여러 채널·검색어의 합계, 잘못된 직접 입력을 검증했다. 아래 수집기 관련 테스트 **166개 통과**.

실행 위치: `talk-rec/model-create`.

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_collect.py tests/test_collect_approvals.py tests/test_collect_curated.py tests/test_listen_collect.py --basetemp .local/pytest-t02c -p no:cacheprovider -q
```

네트워크·설치·커밋·실제 수집 없이 검증했다. 기존 `data/*.tsv`와 `.local/` 실물은 수정하지 않았으며, 테스트 임시 파일은 지정된 `.local/pytest-t02c` 아래에서만 생성했다.
