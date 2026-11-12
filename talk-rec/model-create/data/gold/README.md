# Gold labels

`python -m tools.gold.serve --name <이름>`이 `<이름>.jsonl`과
마지막 선택 id만 담은 `<이름>.position.json`을 생성합니다.
`--sample N --seed S`를 사용하면 공통 표본 id 목록 `sample-S.txt`도 생성합니다.

JSONL에는 공통 라벨 스키마의 id와 라벨만 저장합니다. 전사 본문을 추가하지 마세요.
note는 판정 근거를 자신의 말로 작성하며 원문을 인용하지 않습니다.
전사·창은 `.local/`에만 둡니다. 자세한 사용법은 `tools/gold/README.md`를 보세요.
