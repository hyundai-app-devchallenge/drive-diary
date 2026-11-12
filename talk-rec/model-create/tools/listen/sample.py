"""Deterministic demo-only sampling; see README.md for quota precedence."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import random

from .storage import ROOT, ID, atomic_write, audio_path, encode_lines, read_jsonl

RULES = {
    "approved": "all .local/blocks/*.jsonl with audio; exempt from per-video cap and n",
    "prescreened_cap": 4,
    "random": "ceil(remaining/2), reserve min(5, n) single-turn total first, seeded shuffle",
    "dense": "floor(remaining/2); intersection of top ceil(M/2) speech_share and bottom ceil(M/2) median_gap; seeded shuffle",
    "fallback": "if dense pool exhausted, fill from all remaining capped prescreened; report shortfall",
    "keep_existing": "preserve complete existing records/order; append only unseen approved IDs; n/cap exempt",
    "duration": "retain original block boundaries including short/single-turn blocks; no 30s filter",
}


def candidates(root, directory, origin):
    result = {}
    for path in sorted((root / ".local" / directory).glob("*.jsonl")):
        for row in read_jsonl(path):
            match = ID.fullmatch(row["id"])
            if not match or row["source"] != f"yt:{match[1]}":
                raise ValueError("묶음 id/source 형식 오류")
            if not all(isinstance(row[k], (int, float)) and math.isfinite(row[k]) for k in ("start", "end")) or not 0 <= row["start"] < row["end"]:
                raise ValueError("묶음 시간 형식 오류")
            record = dict(row, audio=dict(path=f".local/audio/{match[1]}.webm", start=row["start"], end=row["end"]), origin=origin)
            if audio_path(root, record).is_file():
                if row["id"] in result:
                    raise ValueError("중복 묶음 id")
                result[row["id"]] = record
    return result


def summary(rows):
    lengths = sorted(r["end"] - r["start"] for r in rows)
    def quantile(p):
        if not lengths:
            return None
        i = (len(lengths) - 1) * p
        lo = int(i)
        return round(lengths[lo] + (lengths[min(lo + 1, len(lengths) - 1)] - lengths[lo]) * (i - lo), 3)
    return dict(n=len(rows), origins=dict(sorted(Counter(r["origin"] for r in rows).items())),
                videos=dict(sorted(Counter(r["source"] for r in rows).items())),
                single_turn=sum(len(r["turns"]) == 1 for r in rows),
                seconds=dict(min=quantile(0), p25=quantile(.25), median=quantile(.5), p75=quantile(.75), p90=quantile(.9), max=quantile(1)),
                duration_bands=dict(under30=sum(s < 30 for s in lengths), from30to90=sum(30 <= s <= 90 for s in lengths), over90=sum(s > 90 for s in lengths)))


def generate(root=ROOT, *, seed=1, n=60, out=None, keep_existing=False):
    root = Path(root)
    out = Path(out) if out is not None else root / ".local/demo/sample.jsonl"
    if n < 5:
        raise ValueError("n은 5 이상이어야 합니다.")
    approved = candidates(root, "blocks", "approved")
    counts = Counter()
    dense_shortfall = 0
    if keep_existing and out.exists():
        selected = read_jsonl(out)
        seen = {r["id"] for r in selected}
        if len(seen) != len(selected) or any(not audio_path(root, r).is_file() for r in selected):
            raise ValueError("기존 표본 중복 또는 오디오 누락: 기존 표본을 먼저 확인하세요.")
        selected += [approved[k] for k in sorted(approved) if k not in seen]
    else:
        selected = [approved[k] for k in sorted(approved)]
        counts.update(r["source"] for r in selected)
        pool = [r for k, r in sorted(candidates(root, "blocks-pre", "prescreened").items()) if k not in approved and counts[r["source"]] < 4]
        rng = random.Random(seed)
        remaining = max(0, n - len(selected))
        random_quota = (remaining + 1) // 2
        dense_quota = remaining // 2
        m = (len(pool) + 1) // 2
        high = {r["id"] for r in sorted(pool, key=lambda r: (-r["features"]["speech_share"], r["id"]))[:m]}
        low = {r["id"] for r in sorted(pool, key=lambda r: (r["features"]["median_gap"], r["id"]))[:m]}
        used = {r["id"] for r in selected}
        def take(rows, quota):
            rows = list(rows)
            rng.shuffle(rows)
            added = 0
            for row in rows:
                if added >= quota:
                    break
                if row["id"] not in used and counts[row["source"]] < 4:
                    selected.append(row)
                    used.add(row["id"])
                    counts[row["source"]] += 1
                    added += 1
            return added
        needed = max(0, 5 - sum(len(r["turns"]) == 1 for r in selected))
        if needed > random_quota or take([r for r in pool if len(r["turns"]) == 1], needed) < needed:
            raise ValueError("한 턴 최소 5개를 채울 수 없습니다. n 또는 입력 표본을 늘리세요.")
        take(pool, random_quota - needed)
        dense_added = take([r for r in pool if r["id"] in high & low], dense_quota)
        dense_shortfall = dense_quota - dense_added
        take(pool, max(0, n - len(selected)))
    if sum(len(r["turns"]) == 1 for r in selected) < 5:
        raise ValueError("한 턴 최소 5개가 없는 기존 표본입니다.")
    report = summary(selected)
    payload = encode_lines(selected)
    unchanged = out.exists() and out.read_bytes() == payload
    atomic_write(out, payload)
    meta_path = out.with_suffix(".meta.json")
    if not unchanged or not meta_path.exists():
        meta = dict(seed=seed, n=n, actual_n=len(selected), rules=RULES, demo_only=True,
                    generated_at=datetime.now(timezone.utc).isoformat(), keep_existing=keep_existing,
                    dense_shortfall=dense_shortfall, summary=report)
        atomic_write(meta_path, (json.dumps(meta, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--n", type=int, default=60)
    parser.add_argument("--out", type=Path, default=ROOT / ".local/demo/sample.jsonl")
    parser.add_argument("--keep-existing", action="store_true")
    parser.add_argument("--check", action="store_true", help="기존 표본 통계만 출력")
    args = parser.parse_args(argv)
    try:
        report = summary(read_jsonl(args.out)) if args.check else generate(seed=args.seed, n=args.n, out=args.out, keep_existing=args.keep_existing)
    except (ValueError, OSError, KeyError) as exc:
        parser.error(str(exc))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
