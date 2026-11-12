"""Sequential, resumable multi-seed experiments: python -m train.sweep."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

from .data import current_snapshot, digest_json, write_json
from .run import load_config, local_output, seed_directory, validate_config


def main(argv=None):
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--list", type=Path, help="JSON config paths relative to list file")
    cli.add_argument("--configs", type=Path, nargs="+")
    cli.add_argument("--seeds", type=int, nargs="+", help="overrides config seeds (fallback: seed)")
    cli.add_argument("--snapshot", type=Path, help="prefix for immutable live-data snapshots")
    cli.add_argument("--root", default=".local/train/ms", type=Path)
    cli.add_argument("--out", default=None, help="report prefix; default ROOT/report")
    cli.add_argument("--epochs", type=int)
    cli.add_argument("--max-train-rows", type=int, help="small smoke run only")
    args = cli.parse_args(argv)
    paths = list(args.configs or [])
    if args.list:
        paths += [args.list.parent / p for p in json.loads(args.list.read_text(encoding="utf-8-sig"))]
    if not paths:
        cli.error("provide --list or --configs")
    root = local_output(args.root)
    snapshots, runs, seen = {}, [], set()
    configs = [(p, load_config(p)) for p in paths]
    for _, config in configs:
        config["seeds"] = args.seeds if args.seeds is not None else config.get("seeds", [config["seed"]])
        validate_config(config)
        for seed in config["seeds"]:
            key = (config["name"], seed)
            if key in seen:
                raise ValueError("duplicate experiment/seed directory")
            seen.add(key)
    for path, config in configs:
        data = config["data"]
        prefix = local_output(args.snapshot or data["snapshot"])
        key = digest_json(dict(prefix=str(prefix), data=data))
        if key not in snapshots:
            snapshots[key] = current_snapshot(prefix, data)
        for seed in config["seeds"]:
            command = [sys.executable, "-B", "-m", "train.run", "--config", str(path),
                       "--snapshot", str(snapshots[key]), "--seed", str(seed),
                       "--output-root", str(root), "--resume"]
            for flag, value in (("--epochs", args.epochs), ("--max-train-rows", args.max_train_rows)):
                if value is not None:
                    command += [flag, str(value)]
            env = {**os.environ, "PYTHONHASHSEED": str(seed),
                   "CUBLAS_WORKSPACE_CONFIG": ":4096:8", "PYTHONUTF8": "1"}
            completed = subprocess.run(command, env=env)
            if completed.returncode:
                return completed.returncode
            runs.append(str(seed_directory(root, config["name"], seed).relative_to(root)))
    root.mkdir(parents=True, exist_ok=True)
    write_json(root / "sweep.json", dict(format_version=1, runs=runs))
    from .report import main as report_main
    return report_main(["--root", str(root), "--out", args.out or str(root / "report")])


if __name__ == "__main__":
    sys.exit(main())
