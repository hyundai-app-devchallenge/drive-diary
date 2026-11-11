"""Build causal judgment windows; see windows.md for CLI and training helpers."""

from __future__ import annotations

import argparse
from bisect import bisect_right
from collections import Counter, defaultdict
import csv
from dataclasses import dataclass
import json
import math
import os
from pathlib import Path
import sys
from typing import Sequence

from common.normalize import normalize
from common.schema import validate_utterance, validate_window
from pipeline.collect import read_split_overrides, split_override_errors

ROOT = Path(__file__).resolve().parents[1]
SPLITS = ("train", "dev", "test")
TOKENIZER = "monologg/koelectra-small-v3-discriminator"
MAX_GAP = 20.0
TOKEN_LIMIT = 160


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


def skip_reason(source: str, manifest: dict[str, dict]) -> str | None:
    row = source_row(source, manifest)
    if row is None:
        return "no manifest row"
    if row["status"] != "downloaded":
        return f"status:{row['status']}"
    return None


def manifest_errors(sources: Sequence[str], manifest: dict[str, dict],
                    overrides: dict[str, str] | None = None) -> list[str]:
    overrides = overrides or {}
    errors = split_override_errors(manifest.values(), overrides)
    errors.extend(f"manifest split disagrees with override: {video}"
                  for video, row in manifest.items()
                  if row["channel_id"] in overrides and row["split"] != overrides[row["channel_id"]])
    channels = defaultdict(set)
    lowdensity_channels = {row["channel_id"] for row in manifest.values()
                           if row["lowdensity"] == "1"}
    for source in sources:
        row = source_row(source, manifest)
        if skip_reason(source, manifest) is not None:
            continue
        if row["split"] not in SPLITS or not row["channel_id"]:
            errors.append(f"invalid manifest split/channel: {source}")
        if (row["lowdensity"] == "1" or row["channel_id"] in lowdensity_channels
                ) and row["split"] != "test":
            errors.append(f"lowdensity channel outside test: {source}")
        channels[row["channel_id"]].add(row["split"])
    errors.extend(f"channel in multiple splits: {channel}"
                  for channel, splits in sorted(channels.items()) if len(splits) > 1)
    return errors


def context_indices(utterances: Sequence[dict], index: int) -> list[int]:
    """Walk adjacent gaps backwards; equality at 20 seconds is allowed."""
    previous = []
    for position in range(index - 1, max(-1, index - 4), -1):
        if utterances[position + 1]["start"] - utterances[position]["end"] > MAX_GAP:
            break
        previous.append(position)
    return previous[::-1]


def assign_clips(utterances: Sequence[dict]) -> list[int]:
    """Partition >20s-delimited runs, maximizing silence at legal 180..300s cuts.

    Span is last (maximum) speech end minus first speech start. Equal gaps
    prefer a span nearest 240s, then the earlier boundary. Only a run's final
    clip may be short. T03's <=12s utterances guarantee legal cut candidates.
    """
    clips = [0] * len(utterances)
    boundaries = [i for i in range(1, len(utterances))
                  if utterances[i]["start"] - utterances[i - 1]["end"] > MAX_GAP]
    boundaries.append(len(utterances))
    first = 0
    clip = 0
    for stop in boundaries:
        # Prefix maxima also handle overlapping utterances without understating span.
        ends = []
        maximum = 0.0
        for item in utterances[first:stop]:
            maximum = max(maximum, item["end"])
            ends.append(maximum)
        run_first = first
        while first < stop:
            origin = utterances[first]["start"]
            if ends[-1] - origin <= 300.0:
                cut = stop
            else:
                last = min(stop - 1, run_first + bisect_right(ends, origin + 300.0))
                candidates = []
                for cut_at in range(first + 1, last + 1):
                    span = ends[cut_at - 1 - run_first] - origin
                    if 180.0 <= span <= 300.0:
                        gap = utterances[cut_at]["start"] - utterances[cut_at - 1]["end"]
                        candidates.append((gap, -abs(span - 240.0), -cut_at, cut_at))
                if not candidates:
                    raise ValueError("cannot form 180..300 second clip; check utterance durations")
                cut = max(candidates)[-1]
            for index in range(first, cut):
                clips[index] = clip
            clip += 1
            first = cut
    return clips


def build_source(utterances: Sequence[dict], split: str) -> list[dict]:
    clips = assign_clips(utterances)
    windows = []
    for index, utterance in enumerate(utterances):
        window = {
            "id": f"{utterance['source']}#{utterance['utt']}",
            "source": utterance["source"], "split": split, "clip": clips[index],
            "context": [utterances[i]["text"] for i in context_indices(utterances, index)],
            "current": utterance["text"],
        }
        validate_window(window)
        windows.append(window)
    return windows


@dataclass(frozen=True)
class TruncatedTokens:
    context: tuple[tuple[int, ...], ...]
    current: tuple[int, ...]
    dropped_context: int
    current_truncated: bool


def total_tokens(context: Sequence[Sequence[int]], current: Sequence[int]) -> int:
    """Count CLS, one SEP per retained turn, and all content tokens."""
    return 2 + len(current) + sum(len(turn) + 1 for turn in context)


def truncate_tokens(context: Sequence[Sequence[int]], current: Sequence[int],
                    max_tokens: int = TOKEN_LIMIT) -> TruncatedTokens:
    """Pure training helper: drop whole oldest turns, then left-truncate current.

    Inputs contain no special tokens. The budget reserves CLS and final SEP.
    Neither input is mutated. Returned tokens are still free of special tokens.
    """
    if type(max_tokens) is not int or max_tokens < 2:
        raise ValueError("max_tokens must be an integer >= 2")
    retained = tuple(tuple(turn) for turn in context)
    current = tuple(current)
    dropped = 0
    while retained and total_tokens(retained, current) > max_tokens:
        retained = retained[1:]
        dropped += 1
    truncated = len(current) + 2 > max_tokens
    if truncated:
        capacity = max_tokens - 2
        current = current[-capacity:] if capacity else ()
    return TruncatedTokens(retained, current, dropped, truncated)


def encode_turns(context: Sequence[str], current: str, tokenizer) -> tuple[list, list]:
    """Normalize each turn independently, without automatic special tokens."""
    turns = [tokenizer.encode(normalize(text), add_special_tokens=False)
             for text in [*context, current]]
    return turns[:-1], turns[-1]


def model_input(context: Sequence[str], current: str, tokenizer,
                max_tokens: int = TOKEN_LIMIT) -> dict[str, list[int]]:
    """Training-ready bounded input, with segment id 1 only on current + its SEP."""
    context_ids, current_ids = encode_turns(context, current, tokenizer)
    bounded = truncate_tokens(context_ids, current_ids, max_tokens)
    ids = [tokenizer.cls_token_id]
    for turn in bounded.context:
        ids.extend((*turn, tokenizer.sep_token_id))
    types = [0] * len(ids)
    ids.extend((*bounded.current, tokenizer.sep_token_id))
    types.extend([1] * (len(bounded.current) + 1))
    return {"input_ids": ids, "token_type_ids": types, "attention_mask": [1] * len(ids)}


def percentile(values: Sequence[int], fraction: float) -> float | None:
    if not values:
        return None
    position = (len(values) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def make_stats(windows: Sequence[dict], grouped: dict[str, list[dict]],
               manifest: dict[str, dict], tokenizer, min_channels: int = 10) -> dict:
    if type(min_channels) is not int or min_channels < 0:
        raise ValueError("min_channels must be a nonnegative integer")
    by_split = {}
    downloaded = {}
    for split in SPLITS:
        records = [w for w in windows if w["split"] == split]
        sources = {w["source"] for w in records}
        by_split[split] = {
            "windows": len(records), "sources": len(sources),
            "channels": len({source_row(source, manifest)["channel_id"] for source in sources}),
            "clips": len({(w["source"], w["clip"]) for w in records}),
        }
        rows = [row for row in manifest.values()
                if row["status"] == "downloaded" and row["split"] == split]
        downloaded[split] = {"sources": len(rows),
                             "channels": len({row["channel_id"] for row in rows})}
    lengths = []
    drops = Counter()
    truncated_current = 0
    for window in windows:
        context, current = encode_turns(window["context"], window["current"], tokenizer)
        lengths.append(total_tokens(context, current))
        bounded = truncate_tokens(context, current)
        drops[bounded.dropped_context] += 1
        truncated_current += bounded.current_truncated
    lengths.sort()
    over = sum(length > TOKEN_LIMIT for length in lengths)
    histogram = Counter(len(w["context"]) for w in windows)
    return {
        "splits": by_split,
        "downloaded_splits": downloaded,
        "min_channels": min_channels,
        "warnings": [{"split": split, "reason": "channels_below_minimum",
                      "channels": by_split[split]["channels"], "minimum": min_channels}
                     for split in ("dev", "test") if by_split[split]["channels"] < min_channels],
        "utterance_sources_present": len(grouped),
        "skipped_sources": [{"source": source, "utterances": len(grouped[source]),
                             "reason": skip_reason(source, manifest)}
                            for source in sorted(grouped) if skip_reason(source, manifest) is not None],
        "context_length_histogram": {str(i): histogram[i] for i in range(4)},
        "tokens": {"tokenizer": TOKENIZER, "limit": TOKEN_LIMIT, "count": len(lengths),
                   "median": percentile(lengths, .5), "p90": percentile(lengths, .9),
                   "p99": percentile(lengths, .99), "max": max(lengths, default=None),
                   "over_limit": over, "over_limit_share": over / len(lengths) if lengths else 0.0,
                   "context_turns_dropped": {str(i): drops[i] for i in range(4)},
                   "current_truncated": truncated_current},
    }


def check_windows(directory: Path, grouped: dict[str, list[dict]],
                  manifest: dict[str, dict], overrides: dict[str, str] | None = None) -> dict:
    """Offline: validate schemas, identities, full text, context, clips and splits."""
    errors = manifest_errors(list(grouped), manifest, overrides)
    expected = {}
    for source, utterances in grouped.items():
        row = source_row(source, manifest)
        if skip_reason(source, manifest) is None and row["split"] in SPLITS:
            expected.update((w["id"], w) for w in build_source(utterances, row["split"]))
    seen = set()
    channels = defaultdict(set)
    checked = 0
    for split in SPLITS:
        path = directory / f"{split}.jsonl"
        if not path.exists():
            errors.append(f"missing window file: {path.name}")
            continue
        try:
            records = read_jsonl(path)
        except ValueError as error:
            errors.append(str(error))
            continue
        for window in records:
            checked += 1
            try:
                validate_window(window)
            except ValueError as error:
                errors.append(f"{path.name}: invalid window: {error}")
                continue
            identity = window["id"]
            if identity in seen:
                errors.append(f"duplicate window id: {identity}")
            seen.add(identity)
            if window["split"] != split:
                errors.append(f"file/split mismatch: {identity}")
            row = source_row(window["source"], manifest)
            reason = skip_reason(window["source"], manifest)
            if reason is not None:
                errors.append(f"ineligible window source ({reason}): {identity}")
            if row is not None:
                channels[row["channel_id"]].add(split)
            target = expected.get(identity)
            if target is None:
                errors.append(f"unresolved or unmanifested window id: {identity}")
            else:
                for field in ("source", "split", "clip", "context", "current"):
                    if window[field] != target[field]:
                        errors.append(f"{field} mismatch: {identity}")
    missing = set(expected) - seen
    if missing:
        errors.append(f"missing windows: {len(missing)}")
    overlap = sum(len(splits) > 1 for splits in channels.values())
    if overlap:
        errors.append(f"channels in multiple window splits: {overlap}")
    return {"windows_checked": checked, "channel_overlap": overlap,
            "error_count": len(errors), "errors": errors}


def write_json(path: Path, value: object) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="offline consistency check only")
    parser.add_argument("--utterances", type=Path, default=ROOT / ".local/utterances")
    parser.add_argument("--manifest", type=Path, default=ROOT / "data/sources.tsv")
    parser.add_argument("--output", "--out-dir", dest="output", type=Path, default=ROOT / ".local/windows")
    parser.add_argument("--split-overrides", type=Path,
                        help="defaults to split-overrides.tsv beside the manifest")
    parser.add_argument("--min-channels", type=int, default=10,
                        help="warn when dev/test window channels are below this minimum")
    args = parser.parse_args(argv)
    try:
        if args.min_channels < 0:
            raise ValueError("min-channels must be nonnegative")
        grouped = read_utterances(args.utterances)
        manifest = read_manifest(args.manifest)
        overrides = read_split_overrides(args.split_overrides if args.split_overrides is not None
                                         else args.manifest.with_name("split-overrides.tsv"))
        if args.check:
            report = check_windows(args.output, grouped, manifest, overrides)
            print(json.dumps(report, ensure_ascii=True))
            return int(bool(report["error_count"]))
        errors = manifest_errors(list(grouped), manifest, overrides)
        if errors:
            raise ValueError("; ".join(errors))
        # Lazy import keeps --check and synthetic tests entirely offline.
        from transformers import AutoTokenizer

        tokenizer = AutoTokenizer.from_pretrained(TOKENIZER, cache_dir=str(ROOT / ".local/hf"),
                                                   local_files_only=True)
        windows = []
        for source in sorted(grouped):
            row = source_row(source, manifest)
            if skip_reason(source, manifest) is None:
                windows.extend(build_source(grouped[source], row["split"]))
        stats = make_stats(windows, grouped, manifest, tokenizer, args.min_channels)
        args.output.mkdir(parents=True, exist_ok=True)
        for split in SPLITS:
            path = args.output / f"{split}.jsonl"
            temporary = path.with_suffix(".jsonl.tmp")
            with temporary.open("w", encoding="utf-8", newline="\n") as stream:
                for window in windows:
                    if window["split"] == split:
                        validate_window(window)
                        stream.write(json.dumps(window, ensure_ascii=False) + "\n")
            os.replace(temporary, path)
        report = check_windows(args.output, grouped, manifest, overrides)
        stats["check"] = report
        write_json(args.output / "stats.json", stats)
        print(json.dumps(stats, ensure_ascii=True, indent=2))
        return int(bool(report["error_count"]))
    except (OSError, ValueError, ImportError) as error:
        print(f"windows: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
