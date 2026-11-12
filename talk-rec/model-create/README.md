# 추억 후보 모델 생성

설계는 [DESIGN.md](DESIGN.md), 작업 순서와 데이터 규약은 [TASKS.md](TASKS.md),
라벨 기준은 [rubric.md](rubric.md)를 따른다. 작업별 기록은 [docs/model-create](../docs/model-create/)에 있다.

Windows x64와 RTX 5060 Ti에서 PowerShell로 다음 명령을 실행한다.
설치 스크립트가 uv와 Python 3.12를 준비하고 잠금 파일에 따라 모든 의존성 그룹을
`.venv/`에 설치한 뒤 의존성과 GPU 연산을 검사한다. 가상 환경 활성화는 필요 없다.

```powershell
cd C:\github\drive-diary\talk-rec\model-create
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\setup.ps1
.\.venv\Scripts\python.exe -W error .\tools\check_env.py
.\.venv\Scripts\python.exe -m pytest
```

`base`는 pytest, `pipeline`은 전사·수집 및 CUDA 12 cuBLAS/cuDNN,
`train`은 CUDA PyTorch·학습·ONNX 의존성을 담는다. 기존 `dev-tools/`의 uv 실행 파일이
있으면 재사용하고, 없으면 `.local/uv/`에 받는다. Python과 설치 캐시는 `.local/`에 둔다.
`dev-tools/`의 가상 환경과 파일은 변경하지 않는다.

| 경로 | 용도 |
| --- | --- |
| `common/` | 정규화 함수·Kotlin 이식 검증용 벡터·JSONL 레코드 검증기다. |
| `tests/` | 정규화와 데이터 규약의 pytest 테스트다. |
| `tools/` | GPU 환경 확인, 영상 검토 화면(`review/`), 듣고 판정하는 화면(`listen/`) 등 보조 도구다. |
| `data/` | 출처 매니페스트·승인 목록·실제 영상 사용 구간(`real-ranges.tsv`)·dev/test 분할(`real-split.tsv`)·탑승 구성(`real-cast.tsv`)이다. 본문은 넣지 않는다. |
| `synth/` | 합성 대화 생성기다. |
| `demo/` | Pleos 에뮬레이터 일괄 전사(`pleos.py`)와 데모 비교 도구다. |
| `pipeline/` | 수집·분할·전사·창 생성·라벨링 코드용이다. |
| `train/` | 학습·증류·내보내기 코드용이다. |
| `eval/` | 평가 지표·슬라이스·실제 영상 평가 묶음(`real.py`)이다. |
| `prompts/` | 라벨러 프롬프트다. |
| `.local/` | 원본·전사·창·silver 라벨·체크포인트와 로컬 도구·캐시를 보관하며 Git에서 제외한다. |
| `.venv/` | 이 프로젝트 전용 Python 환경이며 Git에서 제외한다. |

창 레코드에는 시각이 없으므로 `common/schema.py`는 문맥이 최대 3턴인지만 검사한다.
턴 간 20초 제한은 T05에서 발화 시각을 사용해 검사한다.
