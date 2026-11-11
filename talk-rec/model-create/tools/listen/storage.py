"""Small atomic file helpers shared by the listening tools."""
import json
import os
from pathlib import Path
import re
import tempfile

ROOT = Path(__file__).resolve().parents[2]
ID = re.compile(r"yt:([A-Za-z0-9_-]{11})@([0-9]+)")
VERDICTS = ("story", "moment", "none", "unsure")


def read_jsonl(path):
    if not Path(path).exists():
        return []
    try:
        return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
    except (ValueError, UnicodeError):
        raise ValueError("JSONL 파일 형식을 확인하세요.") from None


def atomic_write(path, payload):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() == payload:
        return
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def encode_lines(rows):
    return "".join(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n" for row in rows).encode("utf-8")


def validate_answer(row):
    if (not isinstance(row, dict)
            or set(row) != {"id", "verdict", "comment", "decided_at", "listened", "opened_text"}
            or not isinstance(row["id"], str) or not ID.fullmatch(row["id"])
            or row["verdict"] not in VERDICTS or not isinstance(row["comment"], str)
            or not isinstance(row["decided_at"], str) or not row["decided_at"]
            or type(row["listened"]) is not bool or type(row["opened_text"]) is not bool):
        raise ValueError("판정 레코드 형식을 확인하세요.")
    return row


def read_answers(path):
    """Append journal: last record for an ID wins, including across restarts."""
    return {row["id"]: row for row in map(validate_answer, read_jsonl(path))}


def clip_name(block_id):
    match = ID.fullmatch(block_id)
    if not match:
        raise ValueError("묶음 id 형식을 확인하세요.")
    # ':' cannot occur in Windows filenames. Keep a reversible ID mapping.
    return f"yt_{match[1]}@{match[2]}.wav"


def audio_path(root, row):
    match = ID.fullmatch(row["id"])
    if (not match or row["source"] != f"yt:{match[1]}"
            or row["audio"]["path"] != f".local/audio/{match[1]}.webm"):
        raise ValueError("오디오 경로 형식을 확인하세요.")
    path = (root / row["audio"]["path"]).resolve()
    if not path.is_relative_to((root / ".local/audio").resolve()):
        raise ValueError("오디오 경로를 벗어날 수 없습니다.")
    return path
