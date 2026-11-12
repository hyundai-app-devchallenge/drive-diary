"""Shared input validation, without logging transcript text."""
import json
import math
from pathlib import Path
import re

from tools.listen.storage import ROOT, audio_path, atomic_write, clip_name, encode_lines, read_jsonl


def approved(root=ROOT):
    rows = [r for r in read_jsonl(Path(root) / ".local/demo/sample.jsonl") if r.get("origin") == "approved"]
    if not rows or len({r["id"] for r in rows}) != len(rows):
        raise ValueError("승인 표본이 없거나 id가 중복되었습니다.")
    for row in rows:
        audio_path(Path(root), row)
        a = row["audio"]
        if not all(isinstance(a[k], (float, int)) and math.isfinite(a[k]) for k in ("start", "end")) or not 0 <= a["start"] < a["end"]:
            raise ValueError("표본 시간 범위를 확인하세요.")
        if a["start"] != row["start"] or a["end"] != row["end"]:
            raise ValueError("묶음과 오디오 시간 범위가 다릅니다.")
    return rows


def clip_path(root, block_id):
    directory = (Path(root) / ".local/demo/clips").resolve()
    path = (directory / clip_name(block_id)).resolve()
    if not path.is_relative_to(directory) or not path.is_file():
        raise ValueError("클립 파일이 없거나 허용된 경로를 벗어났습니다.")
    return path


def write_json(path, value):
    atomic_write(path, (json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n").encode("utf-8"))


def validate_result(row):
    if not isinstance(row, dict) or set(row) != {"id", "config", "lines", "seconds", "avg_logprob", "no_speech_prob"}:
        raise ValueError("ASR 결과 형식 오류")
    clip_name(row["id"])
    if (not isinstance(row["config"], str) or not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,79}", row["config"])
            or not isinstance(row["lines"], list)):
        raise ValueError("ASR 결과 설정/줄 형식 오류")
    for field in ("seconds", "avg_logprob", "no_speech_prob"):
        value = row[field]
        if value is None and field != "seconds":
            continue
        if type(value) not in (float, int) or not math.isfinite(value) or (field == "seconds" and value < 0):
            raise ValueError("ASR 결과 숫자 오류")
    for line in row["lines"]:
        if (set(line) != {"start", "end", "text"} or not isinstance(line["text"], str)
                or not all(type(line[k]) in (float, int) and math.isfinite(line[k]) for k in ("start", "end"))
                or not 0 <= line["start"] <= line["end"]):
            raise ValueError("ASR 결과 줄 형식 오류")
    return row


def load_runs(directory, ids):
    runs, paths = {}, {}
    for path in sorted(Path(directory).glob("*.jsonl")):
        rows = [validate_result(r) for r in read_jsonl(path)]
        if not rows:
            continue
        name = rows[0]["config"]
        if (name in runs or any(r["config"] != name for r in rows)
                or len({r["id"] for r in rows}) != len(rows) or {r["id"] for r in rows} != set(ids)):
            raise ValueError("설정별 승인 표본 전체 결과가 필요합니다. 중복/누락/혼합 결과를 확인하세요.")
        runs[name] = {r["id"]: r for r in rows}
        paths[name] = path
    if not runs:
        raise ValueError("ASR 결과가 없습니다. run을 먼저 실행하세요.")
    return runs, paths
