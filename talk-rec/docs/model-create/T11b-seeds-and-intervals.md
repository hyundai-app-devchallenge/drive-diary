# T11b: 다중 시드와 채널 구간

2026-11-10, 작업 위치 `C:\github\drive-diary\talk-rec\model-create`.
T11의 단일 시드 표에서 문맥 길이·학습량 차이가 잡음인지 판단하기 어려웠던 문제를 다뤘다.
다중 시드 실행, 요약 보고서, 채널 bootstrap과 대응 비교를 구현했다.
**전체 다중 시드 실험은 실행하지 않았다. 아래 실제 데이터 수치는 모두 smoke 검증이다.**

## 변경한 파일과 동작

| 파일 | 변경 |
| --- | --- |
| `train/data.py` | 실제 사용 ID·라벨·가중치·채널·split의 SHA-256 fingerprint, 현재 입력 파일 집합의 해시, 내용별 동결 스냅샷 선택 |
| `train/run.py` | 짧은 시드 디렉터리, 설정·데이터·완료 산출물 검사, skip/재실행, 시작 시 fingerprint와 커버리지 출력, 텍스트 없는 `dev.targets.jsonl` |
| `train/sweep.py` | `--seeds`, config의 시드 목록, 순차 자식 프로세스 실행, 현재 데이터 세대 고정, 정확한 실행 목록 `sweep.json`, 자동 보고서 |
| `train/report.py` | 평가 JSON 집계, PR-AUC 채널 bootstrap, 기준 대비 대응 차이·승리 비율, 채널별 표, 25/50/100% 학습곡선, Markdown/JSON CLI |
| `train/configs/base.json` | `seeds: [1, 2, 3]` 추가; 기존 단일 실행용 `seed: 42` 유지 |
| `train/README.md` | 전체 실행·smoke·보고서 명령, 재사용 규칙과 통계 해석 문서화 |
| `tests/test_train_sweep.py` | 시드 경로, skip/재실행, 설정·라벨·split·입력 파일 변화 감지 검증 |
| `tests/test_train_report.py` | 수계산 평균/표본 SD, 기존 평가값 사용, bootstrap 정답·결정성, 작은 채널 경고와 혼합 데이터 거부 검증 |
| 이 문서 | 한국어 작업 기록과 다음 실행 명령 |

`eval/`, `pipeline/`, `common/`은 수정하지 않았다. 기존 `.venv`를 사용했고 의존성 변경과 커밋은 하지 않았다.
새 보고서·점수 동반 파일·로그에는 전사문, 제목, 원래 채널 ID를 추가하지 않는다.
기존 학습용 창 스냅샷과 S0 어휘/체크포인트는 종전처럼 `.local/` 안의 학습 산출물이다.

## 설계자가 실행할 전체 sweep과 보고서

PowerShell에서 다음 명령을 그대로 사용한다. **smoke 옵션 `--epochs 1`, `--max-train-rows`는 넣지 않는다.**

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
New-Item -ItemType Directory -Force .local/train | Out-Null
.\.venv\Scripts\python.exe -B -m train.sweep --list train/configs/all.json --seeds 1 2 3 --snapshot .local/train/dsb --root .local/train/ms
.\.venv\Scripts\python.exe -B -m train.report --root .local/train/ms --baseline s1c3 --channel-run s1c3 --out .local/train/ms/report
```

`all.json`의 9개 설정 × 3개 시드 = **27개 실행**이다. 기존 재현성 대조 `s1repeat`도 포함한다.
예를 들어 `.local/train/ms/s1c3/s1`, `s2`, `s3`에 저장한다.
CLI `--seeds`가 config의 `seeds`보다 우선한다. 목록이 없는 config는 단일 `seed`를 사용한다.
여러 설정을 직접 고를 때는 `--configs train/configs/설정.json ...`을 쓴다.
다른 기준 또는 채널별 분석 대상을 원하면 `train.report`의 `--baseline`, `--channel-run`에 짧은 실험 이름을 준다.

sweep은 마지막에 보고서도 생성한다. 두 번째 명령은 학습 없이 다시 집계하는 명령이다.
산출물은 `.local/train/ms/report.md`, `report.json`, 실행 목록은 `sweep.json`이다.
이전 명령에서 남은 다른 실험·시드는 실행 목록을 통해 보고서에서 제외한다.

## 재실행과 데이터 변경

각 실행은 학습 전, skip 전에도 `data_fingerprint`, `snapshot_digest`, 실제 학습 행/양성/채널 수와
train/dev/test의 labeled/total, skip, missing, retained, positive, 채널 수를 출력한다.
사용 행 fingerprint는 채널 부분집합과 smoke 행 제한 적용 후의 **실제 사용 ID와 라벨**을 해시한다.
학습에 여러 silver 표본이 있으면 가중치와 중복 표본도 반영한다. 라벨 note와 전사문 자체는 이 해시에 넣지 않는다.
창 내용·타이밍 등 전체 동결 입력의 변화는 별도 스냅샷 digest로 추적한다.

sweep의 `--snapshot`은 경로 접두어다. 현재 매니페스트·창·라벨·타이밍 파일의 내용과 파일 집합을 해시해
`dsb-<12자리 해시>` 같은 새 형제 디렉터리를 고른다. 라벨 파일 추가/삭제도 감지한다.
한 sweep 안에서는 같은 데이터 설정에 대해 한 번 정한 세대를 공유한다. 실행 중 새로 생긴 라벨은 다음 sweep에 반영한다.
스냅샷을 읽는 동안 입력이 바뀌면 실패하므로 같은 명령을 다시 실행한다.
기존 스냅샷은 보존하며 변조된 스냅샷은 오류다.

완료 표시 `run.json`, 기록된 산출물 전부, resolved config, 실제 사용 fingerprint,
스냅샷 digest가 일치할 때만 `SKIP`한다. 설정/데이터가 달라졌거나 파일이 빠졌으면
그 시드 디렉터리만 교체하고 `RERUN`한다. 잘못된 결과를 이어 붙이지 않는다.
실제 사용하지 않은 라벨만 추가됐다면 사용 행 fingerprint는 같을 수 있지만,
스냅샷 digest와 커버리지가 달라지고 보수적으로 재실행한다.
OOM이나 자식 프로세스 오류에서는 중단하며 자동 배치 축소나 재시도는 하지 않는다.

기존 단일 `train.run`의 `--snapshot`은 정확한 동결 경로이며 live 라벨을 다시 읽지 않는다.
단일 실행의 기존 덮어쓰기 금지도 유지하고, `--resume`을 지정할 때만 위 재사용/교체 규칙을 쓴다.
학습 코드만 바꾼 경우에는 코드 해시만으로 skip을 무효화하지 않으므로 새 `--root`를 사용한다.
smoke와 전체 실험도 서로 다른 root를 사용한다.

## 표를 읽는 법

- 행은 `s1c3` 같은 짧은 실험 이름이다. 대표 PR-AUC, best-F1, 예산 내 episode recall은
  **`dev.eval.json`의 값을 읽어 집계**한다. 해당 대표 지표를 점수 파일로 다시 계산하지 않는다.
- `평균 ± SD`는 시드 사이 평균과 **표본 표준편차(ddof=1)**다. SD는 평균의 신뢰구간이 아니다.
  정의되지 않은 값은 0으로 채우지 않는다. JSON의 `defined_n/total_n`을 확인한다.
  한 시드뿐이면 SD는 `NA`다. 학습 시간도 시드별 평균/SD다.
- `Dev rows / + / ch`는 평가에 남은 행·양성·채널 수다. label coverage는 skip을 포함한
  labeled/total 창 비율이다. 지표에서는 skip/missing을 제외한다. 전체 split별 커버리지는 JSON에 있다.
- PR-AUC는 T14 규약의 average precision이다. best-F1과 시간당 오탐 예산 3 이내의 최대 episode recall은
  dev에서 관찰한 기술 통계이며 운영 임계값 채택이나 독립 평가 결과를 뜻하지 않는다.
- `AP 95%`는 채널을 복원 추출한 **1,000회**, 고정 난수 seed **1729**의 2.5/97.5 백분위 구간이다.
  한 채널이 두 번 뽑히면 그 채널의 모든 행을 두 번 넣는다. 행을 독립 표본으로 뽑지 않는다.
  각 시드의 AP를 구한 후 평균하며, 여러 시드 점수를 합쳐 하나의 분류기처럼 평가하지 않는다.
- `Paired Δ [95%]`의 점추정치는 평가 JSON의 시드 평균 AP 차이이고,
  구간은 모든 실험·시드에 **같은 채널 추출**을 적용한 run−baseline 차이의 백분위다.
  기본 baseline은 S1/context 3/length 160인 `s1c3`이다. 시드 집합이 다르면 대응 비교를 생략하고 경고한다.
- `Win share`는 정의된 추출 중 차이가 **엄격히 양수**인 비율이다. 동률은 승리가 아니므로 기준 자신은 0이다.
  p-value 또는 유의성 선언으로 읽으면 안 된다. 양성이 없는 추출은 AP가 정의되지 않아 제외하며 `valid_draws`를 기록한다.
- **dev 채널이 10개 미만이면 `UNRELIABLE INTERVAL` 경고를 항상 출력한다.** 지금처럼 5채널뿐이면
  구간도 불안정하다. 이 구간은 학습된 모델에 조건부인 채널 변동을 나타내며 시드/학습 불확실성을 합친 구간이 아니다.
  여러 실험 비교에 대한 다중 비교 보정도 하지 않는다.
- 채널별 표는 `--channel-run` 실험의 시드별 AP 평균/SD, 양성 비율, 행 수를 보여 준다.
  채널 ID는 SHA-256의 앞 12자리만 표시한다. 양성이 없는 채널의 AP는 `NA`다.
- 학습곡선은 `s1p25`, `s1p50`, **`s1c3` = 100%**를 명시한다. 실제 사용 학습 행 수도 평균/SD로 제시한다.
  부분집합은 각 시드 안에서 중첩되지만 시드마다 구성 채널이 달라진다. 불완전 라벨과 채널 구성 효과도 섞인다.
  빠진 지점은 `not run`이며 보간하지 않는다.

서로 다른 스냅샷·dev fingerprint·평가 설정을 합치거나 한 실험 이름에 서로 다른 하이퍼파라미터를 섞으면
보고서를 거부한다. 현재 silver 라벨, 적은 dev/test 채널, 희귀 정책 플래그 부족은 이 도구로 해결되지 않는다.
human gold와 더 많은 채널, Pleos/D 평가 전에는 문맥 우열·단조 학습곡선·DESIGN.md 통과를 주장하지 않는다.

## 실제 smoke 검증 결과

실행 명령은 다음과 같다. **전체 실험 결과가 아니다.** S0 하나와 S1 하나, 시드 1/2, 학습 최대 32행,
S1 1 epoch만 사용했다. dev/test는 줄이지 않고 실제 T14 CLI를 호출했다.

```powershell
.\.venv\Scripts\python.exe -B -m train.sweep --configs train/configs/s0c0.json train/configs/s1c3.json --seeds 1 2 --epochs 1 --max-train-rows 32 --snapshot .local/train/bds --root .local/train/bs
.\.venv\Scripts\python.exe -B -m train.report --root .local/train/bs --baseline s1c3 --channel-run s1c3 --out .local/train/bs/report
```

최종 동결 세대는 `.local/train/bds-d5fbc4bd9bf4`다.

- 스냅샷 digest: `56709500fc0bf0649b9fa8e51f4e13788ca792b912504c04bc0bc3138f1ee6c7`
- 실제 사용 fingerprint: `a61e5a03e08e5a513076149a096147c17e1d90e63ea072b06072694ddc4ec1ab`
- dev fingerprint: `5218eaa637a04d4bf323ab79c74e9cc24a28015af139cf4155ef1e7c4f807cac`
- 전체 train 라벨 커버리지 3,184/3,859, skip 239, 사용 가능 2,945행, 양성 823, 라벨 채널 23개.
  smoke에서 **실제로 쓴 train은 각 실행 32행·양성 1개·1채널**뿐이다.
- dev 545/545 라벨, skip 98, 평가 **447행·양성 131개·5채널**. test 평가 218행·양성 52개·2채널.
  과거 T11의 dev 432행·양성 133개와 다른 데이터다.

| Smoke 실험 | Dev PR-AUC | Best-F1 | Episode recall @ ≤3/h | 학습 초 | AP 채널 95% |
| --- | --- | --- | --- | --- | --- |
| s0c0, 시드 1/2 | 0.2628 ± 0.0000 | 0.4549 ± 0.0000 | 0.0000 ± 0.0000 | 2.6786 ± 0.0545 | [0.1995, 0.3703] |
| s1c3, 시드 1/2 | 0.3294 ± 0.1255 | 0.4804 ± 0.0327 | 0.0213 ± 0.0301 | 1.4938 ± 0.0421 | [0.2427, 0.4525] |

**두 구간 모두 5채널이라 신뢰하기 어렵다.** 학습 32행의 smoke 점수로 모델 성능이나 순위를 결론내리지 않는다.
25/50% 설정은 요청 범위에 따라 실제 학습하지 않았으며 보고서에는 `not run`이다.
100% 설정의 실제 학습 행은 제한 옵션 때문에 32 ± 0으로 표시한다.
`.local/train/bs/report.md`와 `report.json`에 대응 차이, 승리 비율, 익명 채널 5개 표까지 생성됐다.

첫 보고서의 콘솔 출력에서 Windows cp949가 유니코드 대시를 인코딩하지 못해 UTF-8 출력으로 수정했다.
재실행 시 live 라벨이 3,004개에서 3,184개로 늘어난 것을 감지해 네 실행이 모두 새 스냅샷으로 재학습됐다.
따라서 작은 smoke 학습을 두 차례 수행했으며, 전체 실험을 실행한 것은 아니다.
최종 고정 스냅샷에 대해 아래 단일 실행을 S0/S1, 시드 1/2로 각각 호출했을 때 네 번 모두 `SKIP`을 확인했다.

```powershell
.\.venv\Scripts\python.exe -B -m train.run --config train/configs/s1c3.json --snapshot C:\github\drive-diary\talk-rec\model-create\.local\train\bds-d5fbc4bd9bf4 --seed 1 --epochs 1 --max-train-rows 32 --output-root .local/train/bs --resume
```

## 테스트와 완료 상태

```powershell
.\.venv\Scripts\python.exe -B -m pytest --basetemp .local/train/pt -p no:cacheprovider -q --tb=short
```

최종 **838 passed in 44.96s**. 기존 817개에 T11b 검증 21개가 추가됐다.
평균 `[1,2,3] → 2`, 표본 SD `1`, 완전 순위/역순위의 bootstrap AP `1/0.5`와 차이 `0.5`,
복수 시드 AP 평균, 채널 중복 추출, 양성 없는 추출, 난수 결정성, 10채널 경계 경고를 검증했다.
실제 `run()` 경로에서 완료 결과 skip, 설정/라벨 변화와 산출물 누락 후 재학습도 검증했다.
대표 지표를 임의의 평가값으로 주고 점수 파일의 AP와 달라도 평가 JSON 값을 그대로 집계하는 테스트가 있다.

요청된 구현·단위 테스트·실제 작은 smoke·보고서·한국어 기록에서 미충족 항목은 없다.
전체 3시드 실험과 실제 25/50% 곡선 측정은 요청대로 설계자의 다음 실행에 남겼다.
