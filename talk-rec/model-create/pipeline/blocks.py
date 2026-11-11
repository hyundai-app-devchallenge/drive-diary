"""Build v2 conversation blocks; see blocks.md for CLI and token policy."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
from dataclasses import asdict, dataclass
import itertools
import json
import math
import os
from pathlib import Path
import statistics
import sys
import time

from common.normalize import normalize
from common.schema import validate_block, validate_utterance

ROOT = Path(__file__).resolve().parents[1]
SPLITS = ("train", "dev", "test")
TOKENIZER = "monologg/koelectra-small-v3-discriminator"
TOKEN_LIMIT = 320
DEFAULT_OUTPUT = ROOT / ".local/blocks"
SPLIT_OVERRIDE_COLUMNS = ["channel_id", "split", "reason"]

# Small, independent v1 readers: intentionally no import of collect or windows.
def read_jsonl(path: Path) -> list[dict]:
    records = []
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                raise ValueError(f"{path.name}:{line_number}: invalid JSON") from None
            if not isinstance(record, dict):
                raise ValueError(f"{path.name}:{line_number}: expected object")
            records.append(record)
    return records


def read_utterances(directory: Path) -> dict[str, list[dict]]:
    """Snapshot every JSONL, grouping by source fields rather than filenames."""
    grouped = defaultdict(list)
    seen = set()
    for path in sorted(directory.glob("*.jsonl")):
        for record in read_jsonl(path):
            validate_utterance(record)
            key = (record["source"], record["utt"])
            if key in seen:
                raise ValueError(f"duplicate utterance id: {key[0]}#{key[1]}")
            seen.add(key)
            grouped[record["source"]].append(record)
    for records in grouped.values():
        records.sort(key=lambda u: (u["start"], u["utt"]))
    return dict(grouped)


def read_manifest(path: Path) -> dict[str, dict]:
    rows = {}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        required = {"video_id", "channel_id", "split", "lowdensity", "status"}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError("manifest missing required columns")
        for row in reader:
            if None in row or any(value is None for value in row.values()):
                raise ValueError("malformed manifest row")
            video_id = row["video_id"]
            if not video_id or video_id in rows:
                raise ValueError("empty or duplicate manifest video_id")
            if row["lowdensity"] not in {"0", "1"}:
                raise ValueError(f"invalid lowdensity: {video_id}")
            rows[video_id] = row
    return rows


def source_row(source: str, manifest: dict[str, dict]) -> dict | None:
    return manifest.get(source[3:]) if source.startswith("yt:") else None


def read_split_overrides(path: Path) -> dict[str, str]:
    """Read channel pins; absent files mean no pins for legacy/isolated inputs."""
    if not path.exists():
        return {}
    overrides = {}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames != SPLIT_OVERRIDE_COLUMNS:
            raise ValueError("Unexpected split override header")
        for row in reader:
            if None in row or any(value is None for value in row.values()):
                raise ValueError("Malformed split override row")
            channel = row["channel_id"]
            if not channel.strip() or channel != channel.strip() or channel in overrides:
                raise ValueError(f"Empty or duplicate split override channel: {channel}")
            if row["split"] not in SPLITS or not row["reason"].strip():
                raise ValueError(f"Invalid split override split/reason: {channel}")
            overrides[channel] = row["split"]
    return overrides


def lowdensity_channels(rows) -> set[str]:
    return {row["channel_id"] for row in rows if row["lowdensity"] == "1" and row["channel_id"]}


def split_override_errors(rows, overrides: dict[str, str]) -> list[str]:
    return [f"lowdensity channel override outside test: {channel}"
            for channel in sorted(lowdensity_channels(rows))
            if channel in overrides and overrides[channel] != "test"]




def read_approvals(path: Path) -> dict[str, dict]:
    """Snapshot by header; retry once on a concurrent replacement/partial write."""
    for attempt in range(2):
        try:
            before = path.stat()
            with path.open(encoding="utf-8-sig", newline="") as stream:
                reader = csv.DictReader(stream, delimiter="\t")
                required = {"video_id", "decision", "reason", "decided_by"}
                if not required.issubset(reader.fieldnames or []):
                    raise ValueError("approval file missing required columns")
                rows = {}
                for row in reader:
                    if None in row or any(v is None for v in row.values()):
                        raise ValueError("malformed approval row")
                    video = row["video_id"]
                    if not video or video in rows or row["decision"] not in {"approve", "reject", "hold"}:
                        raise ValueError("invalid/duplicate approval row")
                    rows[video] = row
            after = path.stat()
            if (before.st_mtime_ns, before.st_size) != (after.st_mtime_ns, after.st_size):
                raise ValueError("approval file changed during read")
            return rows
        except (OSError, ValueError):
            if attempt:
                raise
            time.sleep(.05)
    raise AssertionError("unreachable")


def skip_reason(source, manifest, approvals, allow_prescreened=False):
    row = source_row(source, manifest)
    if row is None:
        return "no manifest row"
    if row["status"] != "downloaded":
        return f"status:{row['status']}"
    approval = approvals.get(row["video_id"])
    if approval is None:
        return "no approval row"
    if approval["decision"] != "approve":
        return f"decision:{approval['decision']}"
    decider = approval["decided_by"].strip()
    if not decider:
        return "empty decided_by"
    if decider.casefold() == "claude" and not allow_prescreened:
        return "prescreened only"
    return None


def manifest_errors(sources, manifest, overrides=None):
    overrides = overrides or {}
    errors = split_override_errors(manifest.values(), overrides)
    errors.extend(f"manifest split disagrees with override: {video}"
                  for video, row in manifest.items()
                  if row["channel_id"] in overrides and row["split"] != overrides[row["channel_id"]])
    channels = defaultdict(set)
    lowdensity = lowdensity_channels(manifest.values())
    for source in sources:
        row = source_row(source, manifest)
        if row is None or row["status"] != "downloaded":
            continue
        if row["split"] not in SPLITS or not row["channel_id"]:
            errors.append(f"invalid manifest split/channel: {source}")
        if row["channel_id"] in lowdensity and row["split"] != "test":
            errors.append(f"lowdensity channel outside test: {source}")
        channels[row["channel_id"]].add(row["split"])
    errors.extend(f"channel in multiple splits: {channel}"
                  for channel, splits in sorted(channels.items()) if len(splits) > 1)
    return errors


@dataclass(frozen=True)
class Settings:
    silence: float = 20.0
    max_seconds: float = 90.0
    max_turns: int = 12
    prefix_gap: float = 60.0

    def __post_init__(self):
        for name in ("silence", "max_seconds", "prefix_gap"):
            value = getattr(self, name)
            if not math.isfinite(value) or value < 0 or (name != "prefix_gap" and value == 0):
                raise ValueError(f"{name} must be finite and positive (prefix_gap may be zero)")
        if type(self.max_turns) is not int or self.max_turns < 1:
            raise ValueError("max_turns must be a positive integer")


def closing_reason(turns, following, settings):
    """Exclusive attribution: silence, length, turns, then end of source."""
    if following is None:
        return "end_of_source"
    if following["start"] - turns[-1]["end"] >= settings.silence:
        return "silence"
    if max(following["end"], *(u["end"] for u in turns)) - turns[0]["start"] > settings.max_seconds:
        return "length"
    if len(turns) >= settings.max_turns:
        return "turns"
    return None


def build_source(utterances, split, settings=Settings()):
    """Partition chronologically ordered utterances; preserve full original text."""
    blocks = []
    first = 0
    for i, utterance in enumerate(utterances):
        turns = utterances[first:i + 1]
        following = utterances[i + 1] if i + 1 < len(utterances) else None
        reason = closing_reason(turns, following, settings)
        if reason is None:
            continue
        previous = blocks[-1] if blocks else None
        gap = turns[0]["start"] - utterances[first - 1]["end"] if previous else None
        start, end = turns[0]["start"], max(u["end"] for u in turns)
        seconds = end - start
        block = {
            "id": f"{utterance['source']}@{len(blocks)}", "source": utterance["source"],
            "split": split, "block": len(blocks), "start": start, "end": end,
            "gap_before": gap, "utts": [u["utt"] for u in turns],
            "turns": [u["text"] for u in turns],
            "prefix": previous["turns"][-2:] if previous and gap <= settings.prefix_gap else [],
            "features": {
                "n_turns": len(turns), "seconds": seconds,
                "speech_share": sum(u["end"] - u["start"] for u in turns) / seconds if seconds else 0.0,
                "median_gap": statistics.median([b["start"] - a["end"] for a, b in zip(turns, turns[1:])]) if len(turns) > 1 else 0.0,
                "gap_before": min(gap, 600) if gap is not None else 600,
            }, "closed_by": reason,
        }
        validate_block(block)
        blocks.append(block)
        first = i + 1
    return blocks


def total_tokens(prefix, turns):
    return 1 + sum(len(t) + 1 for t in (*prefix, *turns))


@dataclass(frozen=True)
class FittedTokens:
    prefix: tuple[tuple[int, ...], ...]
    turns: tuple[tuple[int, ...], ...]
    prefix_indices: tuple[int, ...]
    turn_indices: tuple[int, ...]
    dropped_prefix: int
    dropped_middle: int
    dropped_earliest: bool
    last_truncated: bool


def fit_tokens(prefix_token_lists, turn_token_lists, limit=TOKEN_LIMIT):
    """Drop oldest prefix, centre-out interior, earliest endpoint, then left tokens.

    Centre ties remove the left turn first (6 turns: 2,3,1,4); the final
    endpoint fallback retains the latest turn. Inputs exclude special tokens.
    """
    if type(limit) is not int or limit < 2:
        raise ValueError("limit must be an integer >= 2")
    prefix = tuple(tuple(t) for t in prefix_token_lists)
    turns = tuple(tuple(t) for t in turn_token_lists)
    if not turns:
        raise ValueError("at least one block turn required")
    pi, ti = list(range(len(prefix))), list(range(len(turns)))
    def size():
        return total_tokens([prefix[i] for i in pi], [turns[i] for i in ti])
    while pi and size() > limit:
        pi.pop(0)
    middle = sorted(range(1, len(turns) - 1), key=lambda i: (abs(i - (len(turns) - 1) / 2), i))
    removed = 0
    for i in middle:
        if size() <= limit:
            break
        ti.remove(i)
        removed += 1
    earliest = len(ti) > 1 and size() > limit
    if earliest:
        ti.pop(0)
    truncated = size() > limit
    retained = tuple(turns[i] for i in ti)
    if truncated:
        capacity = limit - 2
        retained = (retained[0][-capacity:] if capacity else (),)
    return FittedTokens(tuple(prefix[i] for i in pi), retained, tuple(pi), tuple(ti),
                        len(prefix) - len(pi), removed, earliest, truncated)


def encode_turns(prefix, turns, tokenizer):
    encoded = [tokenizer.encode(normalize(t), add_special_tokens=False) for t in [*prefix, *turns]]
    return encoded[:len(prefix)], encoded[len(prefix):]


def model_input(prefix, turns, tokenizer, limit=TOKEN_LIMIT):
    fitted = fit_tokens(*encode_turns(prefix, turns, tokenizer), limit)
    ids, types = [tokenizer.cls_token_id], [0]
    for segment, group in enumerate((fitted.prefix, fitted.turns)):
        for turn in group:
            ids.extend((*turn, tokenizer.sep_token_id))
            types.extend([segment] * (len(turn) + 1))
    return {"input_ids": ids, "token_type_ids": types, "attention_mask": [1] * len(ids)}


def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    position = (len(values) - 1) * fraction
    lo, hi = math.floor(position), math.ceil(position)
    return values[lo] + (values[hi] - values[lo]) * (position - lo)


def summary(values):
    return {"median": percentile(values, .5), "p90": percentile(values, .9),
            "p99": percentile(values, .99), "max": max(values, default=None)}


def token_stats(blocks, tokenizer, limit=TOKEN_LIMIT, cache=None):
    cache = {} if cache is None else cache
    lengths, branches = [], Counter(dict(unchanged=0, drop_prefix=0, drop_middle=0,
                                        drop_earliest=0, truncate_last=0))
    dropped_prefix, dropped_middle = 0, 0
    for block in blocks:
        def encode(text):
            if text not in cache:
                cache[text] = tokenizer.encode(normalize(text), add_special_tokens=False)
            return cache[text]
        prefix = [encode(t) for t in block["prefix"]]
        turns = [encode(t) for t in block["turns"]]
        length = total_tokens(prefix, turns)
        lengths.append(length)
        fitted = fit_tokens(prefix, turns, limit)
        branches["unchanged"] += length <= limit
        branches["drop_prefix"] += bool(fitted.dropped_prefix)
        branches["drop_middle"] += bool(fitted.dropped_middle)
        branches["drop_earliest"] += fitted.dropped_earliest
        branches["truncate_last"] += fitted.last_truncated
        dropped_prefix += fitted.dropped_prefix
        dropped_middle += fitted.dropped_middle
    return {"tokenizer": TOKENIZER, "limit": limit, "count": len(lengths), **summary(lengths),
            "over_320": sum(n > 320 for n in lengths),
            "over_320_share": sum(n > 320 for n in lengths) / len(lengths) if lengths else 0.0,
            "over_limit": sum(n > limit for n in lengths),
            "over_limit_share": sum(n > limit for n in lengths) / len(lengths) if lengths else 0.0,
            "branches": dict(branches), "prefix_turns_dropped": dropped_prefix,
            "middle_turns_dropped": dropped_middle}


def build_all(grouped, manifest, approvals, settings, allow_prescreened=False):
    return [block for source in sorted(grouped)
            if skip_reason(source, manifest, approvals, allow_prescreened) is None
            for block in build_source(grouped[source], source_row(source, manifest)["split"], settings)]


def make_stats(blocks, grouped, manifest, approvals, tokenizer, settings, allow_prescreened=False, limit=320, cache=None):
    splits = {}
    for split in SPLITS:
        records = [b for b in blocks if b["split"] == split]
        sources = {b["source"] for b in records}
        splits[split] = {"blocks": len(records), "sources": len(sources),
                         "channels": len({source_row(s, manifest)["channel_id"] for s in sources})}
    closed = Counter(b["closed_by"] for b in blocks)
    return {"settings": asdict(settings), "prescreened_included": allow_prescreened,
            "splits": splits, "utterance_sources_present": len(grouped),
            "skipped_sources": [{"source": s, "utterances": len(grouped[s]), "reason": reason}
                                for s in sorted(grouped)
                                if (reason := skip_reason(s, manifest, approvals, allow_prescreened)) is not None],
            "closing_conditions": {k: {"count": closed[k], "share": closed[k] / len(blocks) if blocks else 0.0}
                                   for k in ("silence", "length", "turns", "end_of_source")},
            "features": {k: summary([b["features"][k] for b in blocks]) for k in
                         ("n_turns", "seconds", "speech_share", "median_gap", "gap_before")},
            "tokens": token_stats(blocks, tokenizer, limit, cache),
            "warnings": [{"split": s, "reason": "channels_below_minimum", "channels": splits[s]["channels"], "minimum": 10}
                         for s in ("dev", "test") if splits[s]["channels"] < 10]}


def check_blocks(directory, grouped, manifest, approvals, settings=Settings(), allow_prescreened=False, overrides=None):
    """Reconstruct expected partition offline; compare every contract field."""
    errors = manifest_errors(grouped, manifest, overrides)
    expected = {b["id"]: b for b in build_all(grouped, manifest, approvals, settings, allow_prescreened)} if not errors else {}
    seen, channels, coverage = set(), defaultdict(set), Counter()
    checked = 0
    for split in SPLITS:
        try:
            records = read_jsonl(directory / f"{split}.jsonl")
        except (OSError, ValueError):
            errors.append(f"missing or malformed block file: {split}.jsonl")
            continue
        for block in records:
            checked += 1
            try:
                validate_block(block)
            except ValueError as error:
                errors.append(f"{split}.jsonl: invalid block: {error}")
                continue
            identity, source = block["id"], block["source"]
            if identity in seen:
                errors.append(f"duplicate block id: {identity}")
            seen.add(identity)
            if block["split"] != split:
                errors.append(f"file/split mismatch: {identity}")
            reason = skip_reason(source, manifest, approvals, allow_prescreened)
            if reason is not None:
                errors.append(f"ineligible source ({reason}): {identity}")
            row = source_row(source, manifest)
            if row is not None:
                channels[row["channel_id"]].add(split)
            coverage.update((source, i) for i in block["utts"])
            target = expected.get(identity)
            if target is None:
                errors.append(f"unresolved block: {identity}")
            else:
                for field in target:
                    if block.get(field) != target[field]:
                        errors.append(f"{field} mismatch: {identity}")
    wanted = Counter((b["source"], i) for b in expected.values() for i in b["utts"])
    if coverage != wanted:
        errors.append("utterance partition has unresolved indices, overlap or loss")
    if set(expected) - seen:
        errors.append(f"missing blocks: {len(set(expected) - seen)}")
    overlap = sum(len(s) > 1 for s in channels.values())
    if overlap:
        errors.append(f"channels in multiple splits: {overlap}")
    return {"blocks_checked": checked, "channel_overlap": overlap, "error_count": len(errors), "errors": errors}


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def sweep(grouped, manifest, approvals, tokenizer, allow_prescreened=False, limit=320, prefix_gap=60):
    sources = [s for s in grouped if skip_reason(s, manifest, approvals, allow_prescreened) is None]
    # Audio duration includes silence; manifest duration preferred, timestamp extent fallback.
    seconds = sum(float(source_row(s, manifest).get("duration_s") or max(u["end"] for u in grouped[s])) for s in sources)
    rows, cache = [], {}
    for silence, maximum, turns in itertools.product((10, 20, 30), (60, 90, 120), (8, 12, 16)):
        settings = Settings(silence, maximum, turns, prefix_gap)
        blocks = build_all(grouped, manifest, approvals, settings, allow_prescreened)
        stats = make_stats(blocks, grouped, manifest, approvals, tokenizer, settings, allow_prescreened, limit, cache)
        rows.append({"silence": silence, "max_seconds": maximum, "max_turns": turns,
                     "blocks": len(blocks), "blocks_per_hour": len(blocks) * 3600 / seconds if seconds else 0.0,
                     "seconds_median": stats["features"]["seconds"]["median"],
                     "seconds_p90": stats["features"]["seconds"]["p90"],
                     "turns_median": stats["features"]["n_turns"]["median"],
                     "single_turn_share": sum(len(b["utts"]) == 1 for b in blocks) / len(blocks) if blocks else 0.0,
                     **{f"closed_{k}_share": v["share"] for k, v in stats["closing_conditions"].items()},
                     "over_320_share": stats["tokens"]["over_320_share"]})
    return {"prescreened_included": allow_prescreened, "sources": len(sources), "audio_hours": seconds / 3600, "rows": rows}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs="?", choices=("sweep",))
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--utterances", type=Path, default=ROOT / ".local/utterances")
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/sources.tsv")
    parser.add_argument("--approvals", type=Path, default=ROOT / "data/approved.tsv")
    parser.add_argument("--split-overrides", type=Path)
    parser.add_argument("--output", "--out-dir", dest="output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--allow-prescreened", action="store_true")
    parser.add_argument("--silence", type=float, default=20)
    parser.add_argument("--max-seconds", type=float, default=90)
    parser.add_argument("--max-turns", type=int, default=12)
    parser.add_argument("--prefix-gap", type=float, default=60)
    parser.add_argument("--token-limit", type=int, default=320)
    parser.add_argument("--json", type=Path, help="optional sweep JSON path")
    args = parser.parse_args(argv)
    try:
        if args.allow_prescreened and args.output.resolve() == DEFAULT_OUTPUT.resolve():
            raise ValueError("--allow-prescreened requires a different --out-dir; analysis only, never for training")
        if args.token_limit < 2:
            raise ValueError("token-limit must be >= 2")
        if args.check and args.command:
            raise ValueError("--check cannot be combined with sweep")
        settings = Settings(args.silence, args.max_seconds, args.max_turns, args.prefix_gap)
        grouped, manifest, approvals = read_utterances(args.utterances), read_manifest(args.manifest), read_approvals(args.approvals)
        overrides = read_split_overrides(args.split_overrides or args.manifest.with_name("split-overrides.tsv"))
        if args.check:
            # Reuse the persisted closing settings unless explicitly building a new snapshot.
            stats_path = args.output / "stats.json"
            if stats_path.exists():
                saved = json.loads(stats_path.read_text(encoding="utf-8"))
                settings = Settings(**saved["settings"])
                if bool(saved["prescreened_included"]) != args.allow_prescreened:
                    raise ValueError("check approval mode must match saved stats")
            report = check_blocks(args.output, grouped, manifest, approvals, settings, args.allow_prescreened, overrides)
            print(json.dumps(report, ensure_ascii=True))
            return int(bool(report["error_count"]))
        errors = manifest_errors(grouped, manifest, overrides)
        if errors:
            raise ValueError("; ".join(errors))
        from transformers import AutoTokenizer
        tokenizer = AutoTokenizer.from_pretrained(TOKENIZER, cache_dir=str(ROOT / ".local/hf"), local_files_only=True)
        if args.command == "sweep":
            report = sweep(grouped, manifest, approvals, tokenizer, args.allow_prescreened, args.token_limit, args.prefix_gap)
            keys = list(report["rows"][0])
            print(" | ".join(keys))
            for row in report["rows"]:
                print(" | ".join(f"{v:.4f}" if isinstance(v, float) else str(v) for v in row.values()))
            if args.json:
                if args.allow_prescreened and (args.json.resolve() == DEFAULT_OUTPUT.resolve() or DEFAULT_OUTPUT.resolve() in args.json.resolve().parents):
                    raise ValueError("prescreened sweep JSON must be outside default block directory")
                write_json(args.json, report)
            return 0
        blocks = build_all(grouped, manifest, approvals, settings, args.allow_prescreened)
        stats = make_stats(blocks, grouped, manifest, approvals, tokenizer, settings, args.allow_prescreened, args.token_limit)
        args.output.mkdir(parents=True, exist_ok=True)
        for split in SPLITS:
            path = args.output / f"{split}.jsonl"
            temporary = path.with_suffix(".jsonl.tmp")
            with temporary.open("w", encoding="utf-8", newline="\n") as stream:
                for block in blocks:
                    if block["split"] == split:
                        stream.write(json.dumps(block, ensure_ascii=False) + "\n")
            os.replace(temporary, path)
        stats["check"] = check_blocks(args.output, grouped, manifest, approvals, settings, args.allow_prescreened, overrides)
        write_json(args.output / "stats.json", stats)
        print(json.dumps(stats, ensure_ascii=True, indent=2))
        return int(bool(stats["check"]["error_count"]))
    except (OSError, ValueError, ImportError, KeyError) as error:
        print(f"blocks: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
