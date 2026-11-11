"""One reproducible T11 experiment: python -m train.run --config FILE."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

from .data import (EXCLUDES, data_fingerprint, digest_json, prepare_snapshot, read_rows,
                   subsample_channels, write_json, write_rows)

ROOT = Path(__file__).resolve().parents[1]


def seed_directory(root, name, seed):
    if type(seed) is not int or not 0 <= seed < 2**32:
        raise ValueError("seeds must be integers in [0, 2**32)")
    return Path(root) / name / f"s{seed}"


def reusable(output, config, fingerprint, snapshot_digest):
    """run.json is the completion marker, written only after T14 succeeds."""
    try:
        info = json.loads((output / "run.json").read_text(encoding="utf-8"))
        resolved = json.loads((output / "resolved.json").read_text(encoding="utf-8"))
        return (resolved == config and info["data_fingerprint"] == fingerprint
                and info["snapshot_digest"] == snapshot_digest
                and bool(info["outputs"])
                and all((output / p).is_file() for p in info["outputs"]))
    except (OSError, ValueError, KeyError):
        return False


def used_data(snapshot, config):
    train = subsample_channels(read_rows(snapshot / "train.samples.jsonl"),
                               config["channel_fraction"], config["seed"])
    if config.get("max_train_rows"):
        train = train[:config["max_train_rows"]]
    splits = {}
    for split in ("dev", "test"):
        labels = {r["id"]: r for r in read_rows(snapshot / f"{split}.labels.jsonl") if not r["skip"]}
        channels = {r["window"]["id"]: r["channel"] for r in read_rows(snapshot / f"{split}.samples.jsonl")}
        splits[split] = [dict(window=w, label=labels[w["id"]], weight=1.0, channel=channels[w["id"]])
                         for w in read_rows(snapshot / f"{split}.windows.jsonl") if w["id"] in labels]
    return train, splits


def load_config(path):
    path = Path(path).resolve()
    config = json.loads(path.read_text(encoding="utf-8-sig"))
    if "extends" in config:
        parent = load_config(path.parent / config.pop("extends"))
        for key, value in config.items():
            if isinstance(value, dict) and isinstance(parent.get(key), dict):
                parent[key] = {**parent[key], **value}
            else:
                parent[key] = value
        return parent
    return config


def local_output(path):
    resolved = Path(path).resolve()
    if not resolved.is_relative_to(ROOT / ".local") or resolved == ROOT / ".local":
        raise ValueError("data snapshots/checkpoints must be under model-create/.local/")
    return resolved


def validate_config(config):
    if config["model"] not in {"s0", "s1"}:
        raise ValueError("model must be s0 or s1")
    if not config["name"] or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789_-" for c in config["name"]):
        raise ValueError("run name must be short lowercase letters/digits/underscore/hyphen")
    if len(config["name"]) > 32:
        raise ValueError("run name exceeds 32 characters")
    seed_directory(Path("."), config["name"], config["seed"])
    if "seeds" in config:
        if not isinstance(config["seeds"], list) or not config["seeds"] or len(set(config["seeds"])) != len(config["seeds"]):
            raise ValueError("seeds must be a nonempty list without duplicates")
        for seed in config["seeds"]:
            seed_directory(Path("."), config["name"], seed)
    if config.get("max_train_rows") is not None and (type(config["max_train_rows"]) is not int or config["max_train_rows"] < 1):
        raise ValueError("max_train_rows must be a positive integer")
    for key in ("seed", "context_turns", "max_length", "batch_size", "eval_batch_size", "epochs", "patience", "cpu_threads"):
        if type(config[key]) is not int:
            raise ValueError(f"{key} must be an integer")
    if not 0 <= config["context_turns"] <= 3 or config["max_length"] < 2:
        raise ValueError("context_turns must be 0..3 and max_length >= 2")
    if any(config[k] < 1 for k in ("batch_size", "eval_batch_size", "epochs", "patience", "cpu_threads")):
        raise ValueError("batch sizes, epochs, patience and threads must be positive")
    for key in ("channel_fraction", "learning_rate", "gradient_clip", "pos_weight_cap", "s0_c"):
        if not isinstance(config[key], (int, float)) or not math.isfinite(config[key]) or config[key] <= 0:
            raise ValueError(f"{key} must be finite and positive")
    if config["channel_fraction"] > 1 or config["pos_weight_cap"] < 1:
        raise ValueError("channel_fraction <= 1 and pos_weight_cap >= 1 required")
    for key in ("head_dropout", "warmup_ratio"):
        if not 0 <= config[key] < 1:
            raise ValueError(f"{key} must be in [0, 1)")
    if set(config["loss_weights"]) != {"binary", "ordinal", "kind", "exclude"}:
        raise ValueError("all four loss weights required")
    if any(not math.isfinite(v) or v < 0 for v in config["loss_weights"].values()):
        raise ValueError("loss weights must be finite and nonnegative")
    if config["loss_weights"]["binary"] <= sum(v for k, v in config["loss_weights"].items() if k != "binary"):
        raise ValueError("primary loss weight must dominate auxiliary weights")


def score_rows(examples, predictions):
    """Strict eval/README.md schema: one ID, calibrated story and exclusions."""
    for i, example in enumerate(examples):
        binary = predictions["binary"][i]
        exclude = ({flag: float(predictions[f"exclude:{flag}"][i]) for flag in EXCLUDES}
                   if "exclude" not in predictions else
                   {flag: float(predictions["exclude"][i, j]) for j, flag in enumerate(EXCLUDES)})
        yield {"id": example["window"]["id"], "story": float(binary.item()), "exclude": exclude}


def evaluate_split(snapshot, output, split):
    command = [sys.executable, "-B", "-m", "eval.run", "--labels", str(snapshot / f"{split}.labels.jsonl"),
               "--scores", str(output / f"{split}.scores.jsonl"), "--windows", str(snapshot / f"{split}.windows.jsonl"),
               "--utterances-dir", str(snapshot / "timing"), "--out", str(output / f"{split}.eval.json")]
    # T14 owns every metric, threshold default and episode convention (§10).
    completed = subprocess.run(command, check=True, capture_output=True, text=True, encoding="utf-8")
    (output / f"{split}.eval.log").write_text(completed.stdout, encoding="utf-8")
    return command


def run(config_path, *, snapshot_override=None, silver_override=None, gold_weight=None, name=None,
        prepare_only=False, seed=None, epochs=None, max_train_rows=None, output_root=None, resume=False):
    if Path.cwd().resolve() != ROOT:
        raise ValueError("run from talk-rec/model-create")
    config = load_config(config_path)
    if snapshot_override:
        config["data"]["snapshot"] = str(snapshot_override)
    if silver_override:
        config["data"]["silver"] = [{"dir": p, "weight": float(w)} for p, w in silver_override]
    if gold_weight is not None:
        config["data"]["gold_weight"] = gold_weight
    if name:
        config["name"] = name
    for key, value in (("seed", seed), ("epochs", epochs), ("max_train_rows", max_train_rows)):
        if value is not None:
            config[key] = value
    # The sweep's seed list is orchestration, not a single-run hyperparameter.
    config.pop("seeds", None)
    validate_config(config)
    snapshot = local_output(config["data"]["snapshot"])
    snapshot_info = prepare_snapshot(snapshot, config["data"])
    train, splits = used_data(snapshot, config)
    fingerprint = data_fingerprint(dict(train=train, **splits))
    print(json.dumps(dict(run=config["name"], seed=config["seed"], data_fingerprint=fingerprint,
                         snapshot_digest=snapshot_info["digest"], train_samples=len(train),
                         train_positives=sum(r["label"]["story"] >= 2 for r in train),
                         train_channels=len({r["channel"] for r in train}),
                         coverage=snapshot_info["coverage"])), flush=True)
    if prepare_only:
        return snapshot_info
    output = local_output(seed_directory(output_root, config["name"], config["seed"]) if output_root
                          else Path(".local/train") / config["name"])
    if resume and reusable(output, config, fingerprint, snapshot_info["digest"]):
        print(f"SKIP {config['name']}/s{config['seed']}: config + data fingerprint match", flush=True)
        return json.loads((output / "run.json").read_text(encoding="utf-8"))
    if output.exists():
        if not resume:
            raise ValueError("run directory already exists; choose --name to retain prior results")
        print(f"RERUN {config['name']}/s{config['seed']}: incomplete outputs or config/data changed", flush=True)
        # local_output has verified the absolute deletion target is inside .local.
        if output.is_symlink() or output.is_junction():
            raise ValueError("run directory must not be a link")
        shutil.rmtree(output)
    if not train or not splits["dev"]:
        raise ValueError("training and non-skipped labeled dev rows are required")
    output.mkdir(parents=True)
    write_json(output / "resolved.json", config)
    os.environ["HF_HOME"] = str(local_output(config["hf_cache"]))
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    os.environ["CUBLAS_WORKSPACE_CONFIG"] = ":4096:8"
    os.environ["PYTHONUTF8"] = "1"
    start = time.perf_counter()
    if config["model"] == "s1":
        from .engine import train_s1
        predictions, info = train_s1(train, splits["dev"], splits, config, output)
    else:
        from .s0 import fit, predict
        model = fit(train, config["context_turns"], config["seed"], config["s0_c"])
        training_seconds = time.perf_counter() - start
        write_json(output / "s0.json", model)
        predictions = {split: predict(model, [r["window"] for r in examples]) for split, examples in splits.items()}
        info = dict(training_seconds=training_seconds,
                    parameters=sum(len(head["coef"]) + 1 if isinstance(head, dict) else 1 for head in model["heads"].values()),
                    checkpoint_bytes=(output / "s0.json").stat().st_size,
                    vocabulary_size=len(model["vocabulary"]), feature_scheme=model["feature_scheme"])
    info.update(name=config["name"], model=config["model"], provisional=True,
                data_fingerprint=fingerprint, config_fingerprint=digest_json(config),
                dev_fingerprint=data_fingerprint(dict(dev=splits["dev"])),
                context_turns=config["context_turns"], max_length=config["max_length"],
                channel_fraction=config["channel_fraction"], seed=config["seed"],
                train_samples=len(train), train_unique_ids=len({r["window"]["id"] for r in train}),
                train_positives=sum(r["label"]["story"] >= 2 for r in train),
                train_channels=sorted({r["channel"] for r in train}), coverage=snapshot_info["coverage"],
                snapshot=str(snapshot), snapshot_digest=snapshot_info["digest"], eval_commands={})
    for split, examples in splits.items():
        if not examples:
            continue
        write_rows(output / f"{split}.scores.jsonl", score_rows(examples, predictions[split]))
        info["eval_commands"][split] = evaluate_split(snapshot, output, split)
    from importlib.metadata import version
    info["versions"] = {m: version(m) for m in ("torch", "transformers", "tokenizers", "numpy", "scikit-learn")}
    info["python"] = sys.version
    info["code_hashes"] = {str(p.resolve().relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                           for p in [*Path("train").glob("*.py"), Path("pipeline/windows.py"),
                                     Path("common/normalize.py"), Path("common/schema.py")]}
    info["total_seconds"] = time.perf_counter() - start
    write_rows(output / "dev.targets.jsonl", (dict(id=r["window"]["id"], story=r["label"]["story"],
               channel=hashlib.sha256(r["channel"].encode()).hexdigest()[:12]) for r in splits["dev"]))
    info["outputs"] = [str(p.relative_to(output)) for p in output.rglob("*") if p.is_file()]
    write_json(output / "run.json", info)
    print(json.dumps(dict(run=config["name"], training_seconds=info["training_seconds"], output=str(output))), flush=True)
    return info


def main(argv=None):
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--config", required=True, type=Path)
    cli.add_argument("--snapshot", type=Path, help="new path to freeze currently available live data")
    cli.add_argument("--silver", nargs=2, action="append", metavar=("DIRECTORY", "WEIGHT"))
    cli.add_argument("--gold-weight", type=float)
    cli.add_argument("--name")
    cli.add_argument("--prepare-only", action="store_true")
    cli.add_argument("--seed", type=int)
    cli.add_argument("--epochs", type=int)
    cli.add_argument("--max-train-rows", type=int, help="smoke only: cap training rows after channel selection")
    cli.add_argument("--output-root", type=Path)
    cli.add_argument("--resume", action="store_true", help="skip matching complete runs; replace stale/incomplete runs")
    args = cli.parse_args(argv)
    try:
        run(args.config, snapshot_override=args.snapshot, silver_override=args.silver,
            gold_weight=args.gold_weight, name=args.name, prepare_only=args.prepare_only,
            seed=args.seed, epochs=args.epochs, max_train_rows=args.max_train_rows,
            output_root=args.output_root, resume=args.resume)
    except Exception as error:
        import torch
        if isinstance(error, torch.cuda.OutOfMemoryError):
            print("CUDA OOM: run stopped; GPU may be shared. No automatic retry. Choose a new run name and smaller batch.", file=sys.stderr)
        elif isinstance(error, subprocess.CalledProcessError):
            print("T14 evaluation failed; check snapshot timing/labels. " + error.stderr, file=sys.stderr)
        else:
            # Exceptions from tokenizers/data libraries can include input text.
            safe = str(error) if isinstance(error, (ValueError, ImportError)) else type(error).__name__
            print(f"Training failed: {safe}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
