"""Compact seed summaries and paired channel bootstrap: python -m train.report."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import mean, stdev
import sys

import numpy as np

from eval.metrics import average_precision_score
from .data import digest_json, read_rows, write_json
from .run import local_output

BOOTSTRAP_SEED = 1729
BOOTSTRAP_DRAWS = 1000


def summarize(values):
    values = list(values)
    defined = [float(v) for v in values if v is not None]
    return dict(mean=mean(defined) if defined else None,
                sd=stdev(defined) if len(defined) > 1 else None,
                defined_n=len(defined), total_n=len(values))


def interval(values):
    values = [v for v in values if v is not None]
    return dict(low=float(np.quantile(values, .025)) if values else None,
                high=float(np.quantile(values, .975)) if values else None,
                valid_draws=len(values))


def ap(rows):
    gold = [r["positive"] for r in rows]
    return float(average_precision_score(gold, [r["score"] for r in rows])) if any(gold) else None


def channel_bootstrap(runs, baseline="s1c3", draws=BOOTSTRAP_DRAWS, seed=BOOTSTRAP_SEED):
    """runs[name][seed] = aligned rows. Resample clusters, then mean seed APs.

    The identical channel draw is used for every seed and run. Paired deltas
    require exactly matching training seed sets. Zero-positive draws are undefined.
    """
    if not runs or draws < 1:
        raise ValueError("bootstrap needs runs and positive draws")
    first = next(iter(next(iter(runs.values())).values()))
    signature = [(r["id"], r["channel"], r["positive"]) for r in first]
    channels = sorted({r["channel"] for r in first})
    if not channels:
        raise ValueError("bootstrap needs dev channels")
    for seeds in runs.values():
        for rows in seeds.values():
            if [(r["id"], r["channel"], r["positive"]) for r in rows] != signature:
                raise ValueError("dev IDs, labels or channels differ; paired reporting is invalid")
    indices = [np.array([i for i, r in enumerate(first) if r["channel"] == c]) for c in channels]
    gold = np.array([r["positive"] for r in first])
    scores = {name: [np.array([r["score"] for r in rows]) for _, rows in sorted(seeds.items())]
              for name, seeds in runs.items()}
    samples = {name: [] for name in runs}
    rng = np.random.default_rng(seed)
    for _ in range(draws):
        selected = np.concatenate([indices[c] for c in rng.integers(0, len(channels), size=len(channels))])
        y = gold[selected]
        for name, arrays in scores.items():
            value = mean(float(average_precision_score(y, s[selected])) for s in arrays) if y.any() else None
            samples[name].append(value)
    result = {}
    for name, values in samples.items():
        paired = baseline in runs and set(runs[name]) == set(runs[baseline])
        differences = ([a - b for a, b in zip(values, samples[baseline]) if a is not None and b is not None]
                       if paired else [])
        result[name] = dict(pr_auc_95=interval(values), difference_95=interval(differences),
                            probability_beats_baseline=mean(v > 0 for v in differences) if differences else None,
                            paired=paired, channels=len(channels), unreliable=len(channels) < 10)
    return result


def load_run(path):
    info = json.loads((path / "run.json").read_text(encoding="utf-8"))
    config = json.loads((path / "resolved.json").read_text(encoding="utf-8"))
    if info["config_fingerprint"] != digest_json(config):
        raise ValueError("resolved config changed after training")
    if not all((path / p).is_file() for p in info["outputs"]):
        raise ValueError("incomplete run outputs")
    result = json.loads((path / "dev.eval.json").read_text(encoding="utf-8"))
    targets = read_rows(path / "dev.targets.jsonl")
    scores = read_rows(path / "dev.scores.jsonl")
    if len({r["id"] for r in targets}) != len(targets) or len({r["id"] for r in scores}) != len(scores):
        raise ValueError("duplicate saved dev IDs")
    score_map = {r["id"]: r["story"] for r in scores}
    if set(score_map) != {r["id"] for r in targets}:
        raise ValueError("saved dev scores/targets do not match")
    rows = [dict(id=r["id"], channel=r["channel"], positive=int(r["story"] >= result["config"]["positive_story"]),
                 score=score_map[r["id"]]) for r in sorted(targets, key=lambda r: r["id"])]
    if len(rows) != result["utterance"]["n"] or sum(r["positive"] for r in rows) != result["utterance"]["positives"]:
        raise ValueError("saved dev targets differ from harness counts")
    return dict(info=info, config=config, result=result, scores=rows)


def build_report(records, baseline="s1c3", channel_run=None):
    if not records:
        raise ValueError("no completed runs found")
    groups = {}
    # Mixing data generations or eval conventions would give misleading means.
    if len({r["info"]["snapshot_digest"] for r in records}) != 1:
        raise ValueError("snapshot generations differ; report each dataset separately")
    if len({r["info"]["dev_fingerprint"] for r in records}) != 1:
        raise ValueError("dev fingerprints differ")
    if len({digest_json(r["result"]["config"]) for r in records}) != 1:
        raise ValueError("eval harness conventions differ")
    for record in records:
        name = record["info"]["name"]
        groups.setdefault(name, []).append(record)
    for name, members in groups.items():
        configs = [{k: v for k, v in r["config"].items() if k != "seed"} for r in members]
        if any(c != configs[0] for c in configs[1:]):
            raise ValueError("hyperparameters differ within one experiment")
        if len({r["info"]["seed"] for r in members}) != len(members):
            raise ValueError("duplicate experiment seed")
    boot = channel_bootstrap({n: {r["info"]["seed"]: r["scores"] for r in rs} for n, rs in groups.items()}, baseline)
    warnings = []
    channel_n = next(iter(boot.values()))["channels"]
    if channel_n < 10:
        warnings.append(f"UNRELIABLE INTERVAL: dev has {channel_n} channels (<10); channel bootstrap intervals are unreliable.")
    if baseline not in groups:
        warnings.append(f"Baseline {baseline} is absent; paired differences are unavailable.")
    rows = []
    for name, members in groups.items():
        info = members[0]["info"]
        u = members[0]["result"]["utterance"]
        if len(members) < 2:
            warnings.append(f"{name}: one seed; sample SD is undefined.")
        if baseline in groups and not boot[name]["paired"]:
            warnings.append(f"{name}: seed sets differ from baseline; paired differences are unavailable.")
        if boot[name]["pr_auc_95"]["valid_draws"] < BOOTSTRAP_DRAWS:
            warnings.append(f"{name}: zero-positive bootstrap draws omitted; see valid_draws.")
        rows.append(dict(run=name, seeds=sorted(r["info"]["seed"] for r in members),
                         pr_auc=summarize(r["result"]["utterance"]["pr_auc"] for r in members),
                         best_f1=summarize(r["result"]["utterance"]["best_f1"] for r in members),
                         episode_recall_at_budget=summarize(r["result"]["episodes"]["budget"]["recall"] for r in members),
                         dev_rows=u["n"], dev_positives=u["positives"], dev_channels=channel_n,
                         label_coverage=info["coverage"],
                         training_rows=summarize(r["info"]["train_samples"] for r in members),
                         training_seconds=summarize(r["info"]["training_seconds"] for r in members),
                         fingerprints={str(r["info"]["seed"]): r["info"]["data_fingerprint"] for r in members},
                         bootstrap=boot[name]))
    channel_run = channel_run or (baseline if baseline in groups else next(iter(groups)))
    if channel_run not in groups:
        raise ValueError("chosen channel run is absent")
    channels = []
    chosen = groups[channel_run]
    for channel in sorted({r["channel"] for r in chosen[0]["scores"]}):
        per_seed = [[r for r in member["scores"] if r["channel"] == channel] for member in chosen]
        first = per_seed[0]
        positives = sum(r["positive"] for r in first)
        channels.append(dict(channel=channel, rows=len(first), positives=positives,
                             positive_rate=positives / len(first), pr_auc=summarize(ap(rs) for rs in per_seed)))
    learning = []
    by_name = {r["run"]: r for r in rows}
    for row in rows:
        base_mean = by_name[baseline]["pr_auc"]["mean"] if baseline in by_name else None
        run_mean = row["pr_auc"]["mean"]
        row["bootstrap"]["difference_mean"] = (run_mean - base_mean
            if row["bootstrap"]["paired"] and run_mean is not None and base_mean is not None else None)
    for fraction, name in ((.25, "s1p25"), (.5, "s1p50"), (1., "s1c3")):
        match = by_name.get(name)
        config = groups[name][0]["config"] if match else {}
        if match and (config["model"], config["context_turns"], config["max_length"], config["channel_fraction"]) != ("s1", 3, 160, fraction):
            raise ValueError("learning-curve run does not have its advertised settings")
        learning.append(dict(fraction=fraction, run=name, available=match is not None,
                             **({k: match[k] for k in ("training_rows", "pr_auc", "best_f1", "episode_recall_at_budget")} if match else {})))
    return dict(format_version=1, provisional=True, baseline=baseline, warnings=warnings,
                snapshot_digest=records[0]["info"]["snapshot_digest"],
                dev_fingerprint=records[0]["info"]["dev_fingerprint"],
                false_per_hour_budget=records[0]["result"]["config"]["false_per_hour_budget"],
                smoke=any(r["config"].get("max_train_rows") for r in records),
                bootstrap=dict(draws=BOOTSTRAP_DRAWS, seed=BOOTSTRAP_SEED, unit="dev channel",
                               statistic="mean of seed APs; same channel draw for every seed/run",
                               interval="2.5/97.5 percentile; undefined zero-positive draws omitted"),
                rows=rows, channel_run=channel_run, channels=channels, learning_curve=learning)


def fmt(value):
    return "NA" if value is None else f"{value:.4f}"


def fmt_stat(value):
    text = f"{fmt(value['mean'])} ± {fmt(value['sd'])}"
    if value["defined_n"] != value["total_n"]:
        text += f" ({value['defined_n']}/{value['total_n']} defined)"
    return text


def fmt_interval(value):
    return f"[{fmt(value['low'])}, {fmt(value['high'])}]"


def markdown(report):
    lines = ["# T11 seed report" + (" — SMOKE ONLY" if report["smoke"] else ""), "",
             "Provisional. Mean ± sample SD (ddof=1); NA is undefined, never zero-filled.",
             f"Episode recall uses harness budget ≤ {report['false_per_hour_budget']:g} false/hour; best-F1 and budget recall are descriptive dev sweeps.",
             "Bootstrap: 1,000 channel draws, fixed seed 1729; percentile 95% interval of mean seed AP.",
             f"Paired Δ = run − {report['baseline']}; win share uses strict Δ>0, and is not a p-value.",
             "Intervals describe channel sampling conditional on fitted models; they do not combine seed/training uncertainty or correct multiple comparisons.",
             "", f"Snapshot: `{report['snapshot_digest']}`", f"Dev fingerprint: `{report['dev_fingerprint']}`", ""]
    lines += [f"> WARNING: {w}" for w in report["warnings"]]
    lines += ["", "| Run | Seeds | Dev PR-AUC | Best-F1 | Episode recall @ budget | Dev rows / + / ch | Labels train; dev | Train sec | AP 95% | Paired Δ [95%] | Win share |",
              "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
    for row in report["rows"]:
        coverage = row["label_coverage"]
        cov = "; ".join(f"{coverage[s]['labeled']}/{coverage[s]['total']}" for s in ("train", "dev"))
        b = row["bootstrap"]
        lines.append(f"| {row['run']} | {','.join(map(str, row['seeds']))} | {fmt_stat(row['pr_auc'])} | {fmt_stat(row['best_f1'])} | {fmt_stat(row['episode_recall_at_budget'])} | {row['dev_rows']} / {row['dev_positives']} / {row['dev_channels']} | {cov} | {fmt_stat(row['training_seconds'])} | {fmt_interval(b['pr_auc_95'])} | {fmt(b['difference_mean'])} {fmt_interval(b['difference_95'])} | {fmt(b['probability_beats_baseline'])} |")
    lines += ["", "Labels = labeled/total windows, including skip; metric rows exclude skip/missing. Full split coverage and per-seed fingerprints are in JSON.",
              "", "## Learning curve: S1 context 3, length 160", "",
              "| Training channels | Run | Training rows actually used | Dev PR-AUC | Best-F1 | Episode recall @ budget |",
              "| --- | --- | --- | --- | --- | --- |"]
    for row in report["learning_curve"]:
        cells = [fmt_stat(row[k]) if row["available"] else "not run" for k in ("training_rows", "pr_auc", "best_f1", "episode_recall_at_budget")]
        lines.append(f"| {row['fraction']:.0%} | {row['run']} | " + " | ".join(cells) + " |")
    lines += ["", "Channel subsets are seed-dependent and nested within each seed; fraction effects also reflect channel composition and incomplete labels.",
              "", f"## Per-channel dev: {report['channel_run']}", "",
              "| Channel hash | Rows | Positives | Positive rate | PR-AUC mean ± SD |", "| --- | --- | --- | --- | --- |"]
    for row in report["channels"]:
        lines.append(f"| {row['channel']} | {row['rows']} | {row['positives']} | {fmt(row['positive_rate'])} | {fmt_stat(row['pr_auc'])} |")
    return "\n".join(lines) + "\n"


def main(argv=None):
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--root", type=Path, default=Path(".local/train/ms"))
    cli.add_argument("--out", help="output prefix; default ROOT/report")
    cli.add_argument("--baseline", default="s1c3")
    cli.add_argument("--channel-run", help="experiment to break down; default baseline")
    args = cli.parse_args(argv)
    root = local_output(args.root)
    manifest = root / "sweep.json"
    paths = ([root / p for p in json.loads(manifest.read_text(encoding="utf-8"))["runs"]] if manifest.exists()
             else sorted(p.parent for p in root.glob("*/s*/run.json")))
    records = [load_run(p) for p in paths]
    report = build_report(records, args.baseline, args.channel_run)
    out = local_output(args.out or root / "report")
    out.parent.mkdir(parents=True, exist_ok=True)
    write_json(out.with_suffix(".json"), report)
    rendered = markdown(report)
    out.with_suffix(".md").write_text(rendered, encoding="utf-8")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    print(rendered, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
