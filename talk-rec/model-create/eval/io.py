"""Validated JSONL/TSV joins without importing any model or pipeline code."""

import csv
import json
import math
from pathlib import Path

from common.schema import EXCLUDES, validate_label, validate_window
from .metrics import probability


def read_jsonl(path, key="id", validator=None):
    records = {}
    with Path(path).open(encoding="utf-8-sig") as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
                if not isinstance(record, dict):
                    raise ValueError("expected a JSON object")
                value = record.get(key)
                if type(value) is not str or not value.strip():
                    raise ValueError(f"{key}: expected a nonempty string")
                if value in records:
                    raise ValueError(f"duplicate {key}: {value}")
                if validator:
                    validator(record)
                records[value] = record
            except (ValueError, TypeError) as error:
                raise ValueError(f"{path}:{number}: {error}") from error
    return records


def label_validator(record):
    # The CLI contract permits labels without the optional annotation note.
    validate_label({"note": "", **record})


def window_validator(record):
    validate_window({"context": [], "current": "", **record})
    if "boundary_before" in record and type(record["boundary_before"]) is not bool:
        raise ValueError("boundary_before must be boolean")


def score_validator(record):
    probability(record.get("story"), "story score")
    flags = record.get("exclude", {})
    if not isinstance(flags, dict) or flags.keys() - EXCLUDES:
        raise ValueError("exclude scores must be an object with known flags")
    for flag, value in flags.items():
        probability(value, f"exclude.{flag}")


def pair_validator(record):
    for key in ("positive", "negative"):
        if type(record.get(key)) is not str or not record[key].strip():
            raise ValueError(f"{key}: expected a nonempty id")


def read_metadata(path):
    if path is None:
        return {}, []
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        fields = reader.fieldnames
        if not fields or "id" not in fields or len(set(fields)) != len(fields) or "" in fields:
            raise ValueError(f"{path}: metadata needs unique headers including id")
        result = {}
        for number, row in enumerate(reader, 2):
            if None in row or any(v is None for v in row.values()) or not row["id"].strip():
                raise ValueError(f"{path}:{number}: malformed metadata row")
            if row["id"] in result:
                raise ValueError(f"{path}:{number}: duplicate id: {row['id']}")
            result[row["id"]] = {k: v for k, v in row.items() if k != "id"}
    return result, [f for f in fields if f != "id"]


def read_timings(directory, sources):
    """Prefer explicit source/utt fields; minimal records use safe filename/row."""
    directory = Path(directory)
    if not directory.is_dir():
        raise ValueError(f"utterances directory does not exist: {directory}")
    aliases = {}
    for source in sources:
        for name in {source, source.replace(":", "_")}:
            aliases.setdefault(name, set()).add(source)
    records = {}
    for path in sorted(directory.rglob("*.jsonl")):
        index = 0
        with path.open(encoding="utf-8-sig") as stream:
            for number, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                try:
                    record = json.loads(line)
                    if not isinstance(record, dict):
                        raise ValueError("expected a JSON object")
                    candidates = aliases.get(path.stem, set())
                    source = record.get("source")
                    if source is None:
                        if len(candidates) != 1:
                            raise ValueError("minimal timing record needs an unambiguous source filename")
                        source = next(iter(candidates))
                    utt = record.get("utt", index)
                    index += 1
                    if type(source) is not str or not source.strip():
                        raise ValueError("source: expected a nonempty string")
                    if source not in sources:
                        continue
                    if type(utt) is not int or utt < 0:
                        raise ValueError("utt: expected a nonnegative integer")
                    start, end = record.get("start"), record.get("end")
                    if any(type(v) not in (int, float) or not math.isfinite(v) for v in (start, end)):
                        raise ValueError("start/end must be finite numbers")
                    if not 0 <= start < end:
                        raise ValueError("timing must satisfy 0 <= start < end")
                    ident = f"{source}#{utt}"
                    if ident in records:
                        raise ValueError(f"duplicate timing id: {ident}")
                    records[ident] = dict(id=ident, source=source, start=start, end=end)
                except (ValueError, TypeError) as error:
                    raise ValueError(f"{path}:{number}: {error}") from error
    return records


def missing_ids(required, available, name):
    missing = set(required) - set(available)
    if missing:
        raise ValueError(f"{len(missing)} ids missing from {name}: " + ", ".join(sorted(missing)))


def interval_union_length(intervals):
    end = float("-inf")
    total = 0.0
    for start, stop in sorted(intervals):
        total += max(0.0, stop - max(start, end))
        end = max(end, stop)
    return total


def load_inputs(labels_path, scores_path, windows_path, utterances_dir, meta_path=None,
                pairs_path=None, duration_mode="source"):
    labels = read_jsonl(labels_path, validator=label_validator)
    scores = read_jsonl(scores_path, validator=score_validator)
    windows = read_jsonl(windows_path, validator=window_validator)
    metadata, columns = read_metadata(meta_path)
    pairs = list(read_jsonl(pairs_path, key="pair", validator=pair_validator).values()) if pairs_path else []
    skipped = {ident for ident, row in labels.items() if row["skip"]}
    active = {ident: row for ident, row in labels.items() if not row["skip"]}
    missing_ids(active, scores, "scores")
    missing_ids(active, windows, "windows")
    sources = {windows[ident]["source"] for ident in active}
    timings = read_timings(utterances_dir, sources)
    missing_ids(active, timings, "utterance timings")
    rows = []
    # Unknown gold turns are boundaries, not implicit negatives. Skipped turns
    # disappear before this check and therefore do not create a boundary.
    unknown_since_last = {}
    boundaries = {}
    prior = {}
    contexts = {}
    for timing in sorted(timings.values(), key=lambda r: (r["source"], r["start"], r["end"], r["id"])):
        ident, source = timing["id"], timing["source"]
        history = prior.setdefault(source, [])
        if ident in active and "context" in windows[ident]:
            count = len(windows[ident]["context"])
            contexts[ident] = history[-count:] if count else []
        history.append(ident)
        prior[source] = history[-3:]
        if ident in skipped:
            continue
        if ident in active:
            boundaries[ident] = unknown_since_last.get(source, False)
            unknown_since_last[source] = False
        else:
            unknown_since_last[source] = True
    for ident, label in active.items():
        window, timing, score = windows[ident], timings[ident], scores[ident]
        row = dict(label, **timing, split=window["split"], clip=window["clip"],
                   score=score["story"], exclude_scores=score.get("exclude", {}),
                   meta=metadata.get(ident, {}),
                   boundary_before=window.get("boundary_before", False),
                   unlabeled_before=boundaries[ident])
        if "context" in window:
            row["context_count"] = len(window["context"])
            row["context_ids"] = contexts[ident]
        rows.append(row)
    duration = 0.0
    if duration_mode not in {"source", "evaluated_span"}:
        raise ValueError("unknown duration_mode")
    for source in sources:
        source_times = [r for r in timings.values() if r["source"] == source and r["id"] not in skipped]
        selected = [r for r in rows if r["source"] == source]
        start = 0.0 if duration_mode == "source" else min(r["start"] for r in selected)
        end = max(r["end"] for r in (source_times if duration_mode == "source" else selected))
        removed = [(max(start, t["start"]), min(end, t["end"])) for ident, t in timings.items()
                   if ident in skipped and t["source"] == source and t["end"] > start and t["start"] < end]
        duration += end - start - interval_union_length(removed)
    return dict(rows=rows, pairs=pairs, skipped_ids=skipped, metadata_columns=columns,
                duration_seconds=duration,
                counts=dict(labels_n=len(labels), evaluated_n=len(rows), skipped_n=len(skipped),
                            sources_n=len(sources), timing_n=len(timings)))
