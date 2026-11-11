# T05b — 다운로드 상태 필터와 채널 분할 고정

## 변경 내용

- `pipeline/windows.py`: 매니페스트의 `status=downloaded`인 소스만 창을 생성한다. 거절·후보·실패 소스의 발화 파일이 남아 있어도 창을 만들지 않는다. 발화가 존재하지만 제외된 소스는 `stats.json.skipped_sources`에 기록하며, 상태에 따른 제외 사유는 `status:<status>`다. 매니페스트가 없으면 `no manifest row`다. `--check`도 같은 기준으로 완전성을 검사하고, 이전 상태에서 생성된 부적격 창이 남아 있으면 오류로 처리한다.
- `pipeline/collect.py`: 채널 ID의 UTF-8 SHA-256 앞 8바이트를 big endian 정수로 읽고 100으로 나눈 나머지로 분할한다. 0~69는 train, 70~84는 dev, 85~99는 test다. 다른 채널의 존재나 순서에 의존하지 않는다. 채널 ID가 없으면 분할은 빈 문자열이다.
- 채널 내 어느 행이든 `lowdensity=1`이면 채널 전체가 test다. 이 규칙은 해시보다 우선한다.
- `data/split-overrides.tsv`를 헤더(`channel_id`, `split`, `reason`)만 있는 상태로 추가했다. 사람이 gold를 만들기 전에 채널의 현재 분할과 사유를 기록하면 이후 해시 규칙이 바뀌어도 해당 채널의 기존·새 영상이 그 분할을 사용한다. 중복 채널(동일 split 중복 포함), 잘못된 split, 빈 채널·사유, 잘못된 TSV는 오류다. lowdensity 채널을 train/dev로 고정하는 것도 오류다.
- 수집기의 `migrate-splits` 명령은 승인 판정 적용이나 음성 이동 없이 모든 상태의 매니페스트 행에 분할을 다시 계산한다. 재실행 시 바뀐 내용이 없으면 파일 바이트와 수정 시각을 유지한다. 수집기 `--check`는 override와 저장된 split의 일치를 확인한다. 윈도 생성기는 저장된 split을 읽고 override와 다르면 실패하므로 먼저 마이그레이션해야 한다.
- `stats.json.splits`는 실제 생성된 창의 windows/sources/channels/clips다. `downloaded_splits`는 발화 파일 유무와 관계없이 전체 downloaded 매니페스트의 sources/channels다. 두 통계 모두 rejected/candidate/failed를 제외한다.
- `warnings`는 실제 창이 있는 dev/test 채널 수가 `--min-channels`보다 작을 때 채널 수와 기준을 기록한다. 기본값은 10, 0이면 경고를 끈다. 경고는 종료 코드를 실패로 바꾸지 않는다.
- `--out-dir`를 기존 `--output`의 별칭으로 추가했다. 토크나이저는 `.local/hf`의 캐시만 읽도록 `local_files_only=True`를 지정했다.
- 변경 파일: `pipeline/collect.py`, `pipeline/collect.md`, `pipeline/windows.py`, `pipeline/windows.md`, `data/sources.tsv`, `data/split-overrides.tsv`, `tests/test_collect.py`, `tests/test_windows.py`, 이 작업 기록.

## 실행 방법과 실행한 명령

작업 디렉터리는 `C:\github\drive-diary\talk-rec\model-create`다. 기존 가상환경만 사용했으며 의존성 변경, 외부 네트워크, agy/codex CLI 실행, 커밋은 없었다.

```powershell
# 관련 fixture 테스트
.\.venv\Scripts\python.exe -X utf8 -B -m pytest tests/test_windows.py tests/test_collect.py tests/test_collect_curated.py --basetemp .local/windows/pytest5 -p no:cacheprovider

# 실제 매니페스트 마이그레이션, 재실행으로 멱등성 확인
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py migrate-splits
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py migrate-splits
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py --check

# 라벨링 중인 기존 창과 분리된 실제 데이터 검증
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.windows --out-dir .local/windows-t05b/
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.windows --out-dir .local/windows-t05b/ --check

# 전체 테스트
.\.venv\Scripts\python.exe -X utf8 -B -m pytest --basetemp .local/windows/pytest5 -p no:cacheprovider
```

다른 pin 파일은 수집기와 윈도 생성기 모두 `--split-overrides PATH`로 지정한다. 수집기의 기본 경로는 프로젝트 `data/split-overrides.tsv`, 윈도 생성기의 기본 경로는 사용한 매니페스트 옆 `split-overrides.tsv`다. 경고 기준 변경 예시는 `--min-channels 5`다.

## 검증 결과

- 관련 테스트: **173개 통과**.
- 전체 pytest: **796개 통과**, 실패·건너뜀 없음(21.61초).
- 10,000개 합성 채널(`channel-0`~`channel-9999`): train **7,020개(70.20%)**, dev **1,488개(14.88%)**, test **1,492개(14.92%)**. 모두 목표 비율 ±2%p 이내다. 고정 입력 벡터, 매니페스트 구성·추가 순서와의 독립성도 검사했다.
- rejected/candidate/failed별 창 미생성, 상태 변경 후 남은 창 검출, override 유지·검증·해시 변경 후 고정, lowdensity 충돌 거부, 모든 상태 행의 마이그레이션과 파일 멱등성, 최소 채널 경고의 경계·비활성화를 fixture로 검증했다.
- 실제 매니페스트: **182행 중 23행의 split 변경**, 두 번째 실행 **0행 변경**. split 외 모든 셀과 행 순서는 이전과 같음을 별도로 확인했다.
- 수집기 `--check`: **오류 0건**, 매니페스트 182행.
- 실제 출력: `.local/windows-t05b/{train,dev,test}.jsonl`, `stats.json`.
- 생성 후 내장 검사와 별도 `--check` 모두 **4,651창 검사, 오류 0건, 분할 간 채널 중복 0개**.

| split | windows | sources | channels | clips |
| --- | ---: | ---: | ---: | ---: |
| train | 3,859 | 30 | 28 | 255 |
| dev | 545 | 5 | 5 | 48 |
| test | 247 | 2 | 2 | 15 |
| 합계 | 4,651 | 37 | 35 | 318 |

이번 데이터에서는 전체 downloaded 매니페스트 수(`downloaded_splits`)와 실제 창을 가진 소스·채널 수가 일치했다. 기본 기준 10채널에 대해 dev **5채널**, test **2채널** 경고가 기록됐다. 강제로 채널을 재배치하지 않았다.

발화가 있는 소스는 총 43개이며, 제외된 6개의 ID와 사유는 다음과 같다.

| source | reason |
| --- | --- |
| `yt:-V1BsWCC8Jk` | `status:rejected` |
| `yt:U6Mf2CyODVI` | `status:rejected` |
| `yt:hcn_hz3E1f0` | `no manifest row` |
| `yt:hjjSwsbF-fw` | `status:rejected` |
| `yt:rfD2mZDPDFk` | `status:rejected` |
| `yt:uJ7N0wzfPWE` | `status:rejected` |

## 알아 둘 점과 남은 문제

- 실행 중인 라벨링의 `.local/windows` 창·통계, `.local/labels`, `.local/utterances`, `.local/audio`를 다시 생성하거나 수정하지 않았다. 사용자가 지정한 pytest 임시 경로 `.local/windows/pytest5`만 테스트용으로 사용했다. 그 경로를 제외한 windows 및 utterances/audio 파일의 크기·수정 시각이 검증 전후 같았다. 실행 중인 labels는 검증 대상으로 열지 않았다. 별도 검증 요약은 `.local/windows-t05b/t05b-verification.json`에 있다.
- 기존 `.local/windows`는 과거 분할 및 거절 영상을 포함할 수 있는 이전 산출물이다. 매니페스트만 새 규칙으로 변경했으므로 기존 창에 새 `--check`를 적용하면 불일치가 날 수 있다. 진행 중인 라벨링을 마친 뒤 창 교체 시점을 조율하고, 라벨 ID의 소스가 현재 어떤 split인지 다시 확인해야 한다.
- 이 작업은 요청대로 override를 빈 헤더 상태로 남겼다. gold 파일을 자동 탐지하거나 소급 pin하지 않는다. gold를 생성하는 채널은 사람이 현재 분할을 기록하고 이후 마이그레이션해야 한다. override 파일이 없으면 레거시 입력·격리 fixture 호환을 위해 pin이 없는 것으로 취급하므로 gold와 함께 이 파일을 보관해야 한다.
- lowdensity는 채널 전체 test 강제 규칙이다. 이미 train/dev에 고정된 채널에 lowdensity를 붙이면 충돌로 실패한다. 판정을 조정할 때 기존 gold 분할도 함께 검토해야 한다.
- 70/15/15는 해시 구간 비율이며 작은 실제 채널 집합의 비율을 보장하지 않는다. dev/test의 부족한 채널은 수집 확대가 필요하다. 최소 채널 수 경고는 발화·창이 없는 다운로드만으로 평가 표본이 충분해 보이지 않도록 실제 창 기준이다.
- 매니페스트가 없는 `yt:hcn_hz3E1f0`는 계속 제외했다. 자동으로 행을 추가하거나 내용을 조사하지 않았다.
- `TASKS.md`의 공통 규약에는 이전 80/10/10이 남아 있다. 지정된 수정 범위에 따라 그 파일은 고치지 않았으며, 이 작업과 `pipeline/collect.md`에 명시된 T05b의 70/15/15가 이번 구현의 기준이다.
- 토크나이저 캐시가 없는 환경에서는 빌드가 오류로 끝난다. `--check`는 Transformers나 캐시 없이 실행 가능하다.
- 완료 조건 중 미충족 항목은 없다. 경고로 보고된 평가 채널 부족과 기존 창 교체는 후속 운영 사항이다. 이 기록에는 전사 본문을 포함하지 않았다.
