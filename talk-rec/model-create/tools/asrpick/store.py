import hashlib
import random
import re
import threading
from datetime import datetime, timezone
from pathlib import Path

from demo.asr.data import approved, clip_path, load_runs
from tools.listen.storage import ROOT, atomic_write, encode_lines, read_jsonl

PRIORITY = {"yt:IOlxH67P1JM", "yt:zn4tDPT4RLQ"}


def blind_options(name, block_id, results):
    """Compare content with line boundaries joined by spaces; ignore timestamps."""
    groups = {}
    for config, result in sorted(results.items()):
        content = " ".join(line["text"] for line in result["lines"])
        if content not in groups:
            groups[content] = dict(text="\n".join(line["text"] for line in result["lines"]), configs=[])
        groups[content]["configs"].append(config)
    options = list(groups.values())
    seed = int.from_bytes(hashlib.sha256(f"{name}\0{block_id}".encode()).digest(), "big")
    random.Random(seed).shuffle(options)
    return options


def validate_name(name):
    if (not isinstance(name, str) or not re.fullmatch(r"[\w-]{1,80}", name)
            or name.upper() in {"CON", "PRN", "AUX", "NUL", *[f"COM{i}" for i in range(10)], *[f"LPT{i}" for i in range(10)]}):
        raise ValueError("이름은 한글·영문·숫자·밑줄·하이픈 1~80자로 입력하세요.")
    return name


def read_picks(path):
    latest = {}
    for row in read_jsonl(path):
        if (not isinstance(row, dict) or set(row) != {"id", "picked", "comment", "decided_at"}
                or not isinstance(row["id"], str) or not isinstance(row["comment"], str)
                or not isinstance(row["decided_at"], str) or not row["decided_at"]):
            raise ValueError("선택 기록 형식 오류")
        pick = row["picked"]
        if not ((isinstance(pick, str) and pick in {"all_bad", "similar"})
                or (isinstance(pick, list) and pick and all(isinstance(x, str) for x in pick) and len(set(pick)) == len(pick))):
            raise ValueError("선택 기록의 후보 형식 오류")
        latest[row["id"]] = row
    return latest


class PickStore:
    def __init__(self, name, *, root=ROOT, limit=12, review=False):
        validate_name(name)
        if type(limit) is not int or limit <= 0:
            raise ValueError("limit은 양의 정수여야 합니다.")
        self.name, self.root = name, Path(root)
        directory = (self.root / ".local/demo/asr").resolve()
        self.path = directory / "picks" / f"{name}.jsonl"
        if not self.path.resolve().is_relative_to(directory):
            raise ValueError("선택 저장 경로를 벗어날 수 없습니다.")
        rows = approved(self.root)
        rows.sort(key=lambda r: (r["source"] not in PRIORITY, r["source"], r["start"], r["id"]))
        self.runs, _ = load_runs(directory, [r["id"] for r in rows])
        if len(self.runs) < 2:
            raise ValueError("두 설정 이상의 전체 결과가 필요합니다.")
        self.all_ids = {r["id"] for r in rows}
        answers = read_picks(self.path)
        if set(answers) - self.all_ids:
            raise ValueError("선택 기록과 현재 승인 표본이 다릅니다.")
        pending = [r for r in rows if r["id"] not in answers]
        self.rows = (rows if review or not pending else pending)[:limit]
        self.by_id = {r["id"]: r for r in self.rows}
        self.options = {r["id"]: blind_options(name, r["id"], {c: records[r["id"]] for c, records in self.runs.items()}) for r in self.rows}
        for row in self.rows:
            clip_path(self.root, row["id"])
        self.lock = threading.RLock()

    def media(self, block_id):
        if block_id not in self.by_id:
            raise KeyError(block_id)
        return clip_path(self.root, block_id)

    def state(self):
        with self.lock:
            answers = read_picks(self.path)
            rows = []
            for row in self.rows:
                block_id = row["id"]
                options = self.options[block_id]
                answer = answers.get(block_id)
                public_answer = None
                if answer:
                    choice = answer["picked"]
                    if isinstance(choice, list):
                        choice = next((i + 1 for i, o in enumerate(options) if set(o["configs"]) == set(choice)), None)
                    public_answer = dict(choice=choice, comment=answer["comment"])
                rows.append(dict(id=block_id, options=[dict(slot=i + 1, text=o["text"]) for i, o in enumerate(options)], answer=public_answer))
            return dict(rows=rows, answered=sum(r["answer"] is not None for r in rows), total=len(rows),
                        remaining_total=len(self.all_ids - set(answers)))

    def save(self, payload):
        if not isinstance(payload, dict) or set(payload) != {"id", "choice", "comment"}:
            raise ValueError("선택 요청 형식 오류")
        block_id, choice, comment = payload["id"], payload["choice"], payload["comment"]
        if not isinstance(block_id, str) or block_id not in self.by_id or not isinstance(comment, str) or len(comment) > 2000:
            raise ValueError("표본 또는 코멘트 오류")
        if type(choice) is int and 1 <= choice <= len(self.options[block_id]):
            picked = self.options[block_id][choice - 1]["configs"]
        elif isinstance(choice, str) and choice in {"all_bad", "similar"}:
            picked = choice
        else:
            raise ValueError("유효한 후보를 고르세요.")
        row = dict(id=block_id, picked=picked, comment=comment, decided_at=datetime.now(timezone.utc).isoformat())
        with self.lock:
            old = self.path.read_bytes() if self.path.exists() else b""
            if old and not old.endswith(b"\n"):
                old += b"\n"
            atomic_write(self.path, old + encode_lines([row]))
        # Configuration names never leave the server, even in save responses.
        return dict(saved=True)
