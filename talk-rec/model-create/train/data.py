"""Validated, immutable experiment snapshots (DESIGN.md §3, §4, §8, §9).

Multiple silver annotators contribute weighted training examples. Evaluation uses
the highest-weight annotator (configuration order breaks ties); human gold
replaces every silver annotation for its ID, including human skip decisions.
"""

from __future__ import annotations

import csv
import hashlib
import importlib
import json
import math
from pathlib import Path
import random
import time

from common.normalize import normalize
from common.schema import validate_label, validate_window

SPLITS = ("train", "dev", "test")
KINDS = ("recall", "moment", "feeling", "promise", "family_fact")
EXCLUDES = ("command", "external", "sensitive", "optout", "garbled")


def digest_json(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def data_fingerprint(splits):
    """Hash actual ID/label/weight/channel membership, with no transcript text."""
    records = {}
    for split, rows in splits.items():
        records[split] = sorted((dict(id=r["window"]["id"], channel=r["channel"],
                                      label={k: v for k, v in r["label"].items() if k != "note"},
                                      weight=r["weight"]) for r in rows), key=lambda r: digest_json(r))
    return digest_json(records)


def live_data_digest(config):
    """Include new/deleted files as well as edits; never expose their contents."""
    paths = [Path(config["manifest"])]
    paths += [Path(config["windows_dir"]) / f"{s}.jsonl" for s in SPLITS]
    paths += list(Path(config["utterances_dir"]).glob("*.jsonl"))
    paths += label_files(config["gold_dir"], gold=True)
    for item in config["silver"]:
        paths += label_files(item["dir"])
    return digest_json(dict(config={k: v for k, v in config.items() if k != "snapshot"},
                            files={str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                                   for p in sorted(set(paths))}))


def current_snapshot(prefix, config):
    """A sweep freezes live data once in a short content-addressed sibling."""
    prefix = Path(prefix)
    digest = live_data_digest(config)
    path = prefix.with_name(prefix.name[:12] + "-" + digest[:12])
    prepare_snapshot(path, config)
    if live_data_digest(config) != digest:
        raise ValueError("live input changed while snapshotting; rerun the command")
    return path


def read_rows(path):
    """Read one file generation; writers replace rather than append label views."""
    path = Path(path)
    try:
        lines = path.read_text(encoding="utf-8-sig").splitlines()
    except OSError:
        raise ValueError(f"cannot read input: {path}") from None
    rows = []
    for n, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError()
        except (ValueError, TypeError):
            raise ValueError(f"{path}:{n}: invalid JSON object (content withheld)") from None
        rows.append(row)
    return rows


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2,
                                    allow_nan=False) + "\n", encoding="utf-8")


def write_rows(path, rows):
    with Path(path).open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n")


def positive_weight(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
        raise ValueError("sample weights must be finite and positive")
    return float(value)


def label_files(directory, gold=False):
    directory = Path(directory)
    if not directory.is_dir():
        raise ValueError(f"missing label directory: {directory}")
    # run.jsonl and unclear.jsonl are NOT label files. Do not inspect .chunks.
    return sorted(p for p in directory.glob("*.jsonl")
                  if gold or p.name not in {"run.jsonl", "unclear.jsonl"})


def read_labels(files, human=False):
    found = {}
    for path in files:
        for row in read_rows(path):
            row = {**row, "note": ""}  # no label notes in training/evaluation artifacts
            validate_label(row)
            if human and not row["labeler"].startswith("human:"):
                raise ValueError("gold files must contain human labels")
            if row["id"] in found:
                raise ValueError(f"duplicate label ID in label directory: {row['id']}")
            found[row["id"]] = row
    return found


def join_labels(windows, silvers, gold, gold_weight):
    """Return weighted training rows, canonical evaluation labels, and coverage."""
    gold_weight = positive_weight(gold_weight)
    joined, canonical = [], []
    coverage = dict(total=len(windows), labeled=0, skipped=0, retained=0,
                    missing=0, positives=0, gold=0, training_samples=0)
    for window in windows:
        key = window["id"]
        annotations = ([(gold[key], gold_weight)] if key in gold else
                       [(labels[key], positive_weight(weight))
                        for labels, weight in silvers if key in labels])
        if not annotations:
            coverage["missing"] += 1
            continue
        coverage["labeled"] += 1
        coverage["gold"] += int(key in gold)
        selected = max(annotations, key=lambda item: item[1])[0]
        canonical.append(selected)
        if selected["skip"]:
            coverage["skipped"] += 1
        else:
            coverage["retained"] += 1
            coverage["positives"] += int(selected["story"] >= 2)
        for label, weight in annotations:
            if not label["skip"]:
                joined.append(dict(window=window, label=label, weight=weight))
    coverage["training_samples"] = len(joined)
    return joined, canonical, coverage


def subsample_channels(rows, fraction, seed):
    """Nested hash-ranked channel subsets; dev/test are never subsampled (§8)."""
    if not 0 < fraction <= 1:
        raise ValueError("channel_fraction must be in (0, 1]")
    channels = sorted({r["channel"] for r in rows},
                      key=lambda c: (hashlib.sha256(f"{seed}:{c}".encode()).hexdigest(), c))
    keep = set(channels[:math.ceil(len(channels) * fraction)])
    return [r for r in rows if r["channel"] in keep]


def window_helpers():
    # Another task may be atomically editing the module. Retry import only;
    # missing APIs remain errors and are never replaced by a local truncator.
    for attempt in range(3):
        try:
            return importlib.import_module("pipeline.windows")
        except (ImportError, SyntaxError):
            if attempt == 2:
                raise
            importlib.invalidate_caches()
            time.sleep(0.2)


def build_input(context, current, tokenizer, max_length):
    """Use the authoritative truncator, including its separator accounting (§3)."""
    context_ids = [tokenizer.encode(normalize(t), add_special_tokens=False) for t in context]
    current_ids = tokenizer.encode(normalize(current), add_special_tokens=False)
    bounded = window_helpers().truncate_tokens(context_ids, current_ids, max_length)
    ids = [tokenizer.cls_token_id]
    for turn in bounded.context:
        ids.extend((*turn, tokenizer.sep_token_id))
    types = [0] * len(ids)
    ids.extend((*bounded.current, tokenizer.sep_token_id))
    types.extend([1] * (len(bounded.current) + 1))
    return dict(input_ids=ids, token_type_ids=types, attention_mask=[1] * len(ids))


def drop_context(context, seed, epoch, index, maximum=3):
    rng = random.Random(f"{seed}:{epoch}:{index}")
    removed = set(rng.sample(range(len(context)), min(rng.randint(0, maximum), len(context))))
    return [turn for i, turn in enumerate(context) if i not in removed]


def prepare_snapshot(path, config):
    """Freeze live inputs once. Reusing a snapshot never rereads live labels."""
    path = Path(path)
    marker = path / "snapshot.json"
    signature = {k: v for k, v in config.items() if k != "snapshot"}
    if marker.exists():
        existing = json.loads(marker.read_text(encoding="utf-8"))
        if existing["data_config"] != signature:
            raise ValueError("snapshot data configuration changed; choose a new --snapshot path")
        for name, digest in existing["files"].items():
            if hashlib.sha256((path / name).read_bytes()).hexdigest() != digest:
                raise ValueError(f"snapshot file changed: {name}")
        return existing
    if path.exists():
        raise ValueError("incomplete snapshot exists; choose a new snapshot path")
    # Read before creating the directory so invalid live inputs leave no snapshot.
    provenance = {}

    def track(p):
        p = Path(p)
        raw = p.read_bytes()
        provenance[str(p)] = hashlib.sha256(raw).hexdigest()
        return raw

    manifest_path = Path(config["manifest"])
    import io
    manifest = list(csv.DictReader(io.StringIO(track(manifest_path).decode("utf-8-sig")), delimiter="\t"))
    channel_map = {"yt:" + r["video_id"]: r["channel_id"] for r in manifest}
    silver = []
    for item in config["silver"]:
        files = label_files(item["dir"])
        for f in files:
            track(f)
        silver.append((read_labels(files), positive_weight(item["weight"])))
    files = label_files(config["gold_dir"], gold=True)
    for f in files:
        track(f)
    gold = read_labels(files, human=True)
    windows, samples, labels, coverage = {}, {}, {}, {}
    seen_ids, split_channels = set(), {}
    for split in SPLITS:
        p = Path(config["windows_dir"]) / f"{split}.jsonl"
        track(p)
        windows[split] = read_rows(p)
        for w in windows[split]:
            validate_window(w)
            if w["split"] != split or w["id"] in seen_ids:
                raise ValueError("window split mismatch or duplicate ID")
            seen_ids.add(w["id"])
            # Non-YouTube corpora must supply a speaker/channel on the window.
            channel = w.get("channel_id") or channel_map.get(w["source"])
            if not channel:
                raise ValueError(f"missing channel metadata: {w['source']}")
            if channel in split_channels and split_channels[channel] != split:
                raise ValueError("channel leakage across splits; rebuild windows after split changes")
            split_channels[channel] = split
        samples[split], labels[split], coverage[split] = join_labels(
            windows[split], silver, gold, config["gold_weight"])
        for r in samples[split]:
            r["channel"] = r["window"].get("channel_id") or channel_map[r["window"]["source"]]
        coverage[split]["channels_total"] = len({w.get("channel_id") or channel_map[w["source"]] for w in windows[split]})
        coverage[split]["channels_retained"] = len({r["channel"] for r in samples[split]})
    selected_sources = {w["source"] for ws in windows.values() for w in ws}
    timing = []
    for f in sorted(Path(config["utterances_dir"]).glob("*.jsonl")):
        # No transcript is copied into timing snapshots or logs.
        raw_rows = read_rows(f)
        retained = [{k: r[k] for k in ("source", "utt", "start", "end")}
                    for r in raw_rows if r.get("source") in selected_sources]
        if retained:
            track(f)
            timing.extend(retained)
    # Detect a live generation change while reading (do not silently mix splits).
    for name, digest in provenance.items():
        if hashlib.sha256(Path(name).read_bytes()).hexdigest() != digest:
            raise ValueError("live input changed while snapshotting; rerun the command")
    path.mkdir(parents=True)
    (path / "timing").mkdir()
    write_rows(path / "timing" / "all.jsonl", timing)
    for split in SPLITS:
        write_rows(path / f"{split}.windows.jsonl", windows[split])
        write_rows(path / f"{split}.labels.jsonl", labels[split])
        write_rows(path / f"{split}.samples.jsonl", samples[split])
    files = {str(f.relative_to(path)): hashlib.sha256(f.read_bytes()).hexdigest()
             for f in path.rglob("*.jsonl")}
    info = dict(format_version=1, data_config=signature, coverage=coverage,
                provenance=provenance, files=files,
                digest=hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest())
    write_json(marker, info)
    return info
