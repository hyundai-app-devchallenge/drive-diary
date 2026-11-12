"""CLI: python -m eval.run ... or python -m eval.run compare result.json ..."""

import argparse
from dataclasses import asdict
import json
from pathlib import Path
import sys

from .episodes import AssemblyConfig, evaluate_episodes
from .io import load_inputs
from .metrics import minimal_pair_metrics, slice_metrics, utterance_metrics


ASSUMPTIONS = [
    "DESIGN.md §3 gives no numeric score thresholds: tau_open=0.5, tau_keep=0.3; both configurable.",
    "DESIGN.md §3 does not say whether closing low turns belong to the episode: buffer low turns, "
    "include them if a keep turn follows, otherwise trim them (include_trailing_low can change this).",
    "DESIGN.md §3/§5: pre-roll uses up to context_turns=3 eligible preceding evaluated turns, "
    "limited by window context length and inferred immediately preceding timing IDs when supplied; "
    "no unlabeled or skipped turn is added or replaced with an older turn. "
    "Missing context uses the configurable context_turns limit. Earlier episodes can overlap pre-roll.",
    "DESIGN.md §3/§8 does not define partial annotation boundaries: clip changes and intervening "
    "unlabeled turns reset assembly by default (break_on_clip and bridge_unlabeled configure this). "
    "Explicit boundary_before on windows represents route change, microphone stop, or session reset.",
    "DESIGN.md §10 does not define episode matching: a single predicted episode must overlap "
    "at least overlap_fraction=0.5 of the gold episode duration; any positive overlap prevents a false episode. "
    "No one-to-one matching or pooling of multiple overlaps.",
    "DESIGN.md §8/§10 provides no exact audio duration field: source duration is [0, maximum timing end] "
    "per evaluated source, including silence and unlabeled audio; evaluated_span is an alternative. "
    "Skip interval unions are subtracted, and skipped turns do not set source endpoints. "
    "Trailing audio after the final transcript cannot be inferred.",
    "DESIGN.md §10 does not specify a sweep: default tau_open=0..1 in steps of 0.05 plus operating "
    "tau_open; tau_keep=min(configured tau_keep, swept tau_open). Gold grouping stays fixed. "
    "Budget recall is the best observed feasible point, without interpolation; ties use fewer false "
    "episodes then higher tau_open. No feasible point gives null.",
    "DESIGN.md §10 does not define ranking ties, empty cases, or slice reliability: AP groups equal "
    "scores, ROC ties get half credit, classification zero denominators give zero, undefined "
    "ranking/episode/pair rates give null, and min_positives=5 marks reliability. "
    "Best-F1 threshold ties choose the highest observed score; pair ties count as wrong.",
    "DESIGN.md §4/§5/§10: exclusion safety measures temporal inclusion BEFORE storage filtering "
    "or optout rollback. Gold story episodes retain excluded story-positive turns. "
    "This harness cannot certify the 0.95 command/optout policy criterion without rule-gate outputs. "
    "Partial exclude score coverage is evaluated on available scores and explicitly counted.",
    "DESIGN.md §10: results are provisional until D recordings are evaluated; dataset provenance "
    "is supplied by the caller. Synthetic fixtures establish arithmetic only. Baseline, ASR and "
    "quantization deltas require separate comparable runs; no significance claim is inferred.",
]


def evaluate(inputs, config=AssemblyConfig(), *, threshold=0.5, positive_story=2,
             min_positives=5, overlap_fraction=0.5, sweep=None,
             false_per_hour_budget=3.0, recall_target=0.75, duration_mode="source"):
    if type(positive_story) is not int or not 1 <= positive_story <= 3:
        raise ValueError("positive_story must be 1, 2, or 3")
    rows = inputs["rows"]
    options = dict(threshold=threshold, positive_story=positive_story, min_positives=min_positives)
    return dict(format_version=1, provisional=True, assumptions=ASSUMPTIONS,
                config=dict(assembly=asdict(config), **options, overlap_fraction=overlap_fraction,
                            false_per_hour_budget=false_per_hour_budget, recall_target=recall_target,
                            duration_mode=duration_mode), counts=inputs["counts"],
                utterance=utterance_metrics(rows, **options),
                episodes=evaluate_episodes(rows, inputs["duration_seconds"], config,
                                          positive_story=positive_story, overlap_fraction=overlap_fraction,
                                          sweep=sweep, false_per_hour_budget=false_per_hour_budget,
                                          recall_target=recall_target),
                minimal_pairs=minimal_pair_metrics(inputs["pairs"], rows, inputs["skipped_ids"]),
                slices=slice_metrics(rows, inputs["metadata_columns"], **options))


def cell(value):
    if value is None:
        return "null"
    if isinstance(value, float):
        return f"{value:.6g}"
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def table(headers, rows):
    return "\n".join(["| " + " | ".join(map(cell, headers)) + " |",
                      "| " + " | ".join("---" for _ in headers) + " |"] +
                     ["| " + " | ".join(map(cell, row)) + " |" for row in rows])


METRIC_COLUMNS = ["n", "positives", "negatives", "reliable", "pr_auc", "roc_auc", "threshold",
                  "tp", "fp", "fn", "tn", "precision", "recall", "f1", "best_f1", "best_f1_threshold"]


def metric_rows(name, metrics):
    result = [[name] + [metrics[k] for k in METRIC_COLUMNS]]
    for flag, values in metrics["exclude"].items():
        result.append([f"{name}/exclude:{flag} (coverage={values['coverage_n']}, missing={values['missing_n']})"]
                      + [values[k] for k in METRIC_COLUMNS])
    return result


def markdown_summary(result):
    episode, pairs = result["episodes"], result["minimal_pairs"]
    lines = ["# Evaluation (provisional; DESIGN.md §10)", "",
             table(["Count", "Value"], result["counts"].items()), "",
             "## Utterances", "", table(["Group"] + METRIC_COLUMNS,
                                           metric_rows("all", result["utterance"])), "",
             "## Episodes", "", table(["Metric", "Value"],
                 [(k, episode[k]) for k in ("gold_n", "predicted_n", "found_n", "false_n", "recall",
                                            "false_per_hour", "duration_seconds")]), "",
             table(["Budget metric", "Value"], episode["budget"].items()), "",
             "## Exclusion safety (before storage policy)", "",
             table(["Flag", "n", "inside_n", "inside_share"],
                   [[flag, values["n"], values["inside_n"], values["inside_share"]]
                    for flag, values in episode["exclusion_safety"].items()]), "",
             "## Minimal pairs", "", table(["Metric", "Value"], pairs.items()), "",
             "## Threshold sweep", "", table(["tau_open", "tau_keep", "recall", "false_per_hour"],
                 [[p[k] for k in ("tau_open", "tau_keep", "recall", "false_per_hour")]
                  for p in episode["curve"]]), "", "## Slices", ""]
    sliced = []
    for dimension, groups in result["slices"].items():
        for value, metrics in groups.items():
            sliced.extend(metric_rows(f"{dimension}={value}", metrics))
    lines += [table(["Group"] + METRIC_COLUMNS, sliced), "", "## Configuration", "",
              "```json", json.dumps(result["config"], ensure_ascii=False, indent=2), "```", "",
              "## Assumptions", ""] + [f"- {a}" for a in result["assumptions"]]
    return "\n".join(lines) + "\n"


def compare_results(paths):
    results = []
    for path in paths:
        with Path(path).open(encoding="utf-8-sig") as stream:
            result = json.load(stream)
        if not isinstance(result, dict) or result.get("format_version") != 1:
            raise ValueError(f"{path}: unsupported evaluation result format")
        results.append(result)
    # Flatten scalar metrics so comparison includes slices, flag coverage, the
    # selected operating point, counts and all other reported scalar metrics.
    def flatten(value, prefix=""):
        found = {}
        for key, item in value.items():
            name = f"{prefix}.{key}" if prefix else key
            if isinstance(item, dict):
                found.update(flatten(item, name))
            elif not isinstance(item, list):
                found[name] = item
        return found
    flat = [flatten(r) for r in results]
    keys = sorted(set().union(*(r.keys() for r in flat)))
    return table(["Metric"] + [str(p) for p in paths],
                 [[key] + [r.get(key) for r in flat] for key in keys]) + "\n"


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    for option in ("labels", "scores", "windows", "utterances-dir", "out"):
        cli.add_argument(f"--{option}", required=True, type=Path)
    cli.add_argument("--pairs", type=Path)
    cli.add_argument("--meta", type=Path)
    cli.add_argument("--threshold", type=float, default=0.5)
    cli.add_argument("--positive-story", type=int, choices=(1, 2, 3), default=2)
    cli.add_argument("--tau-open", type=float, default=0.5)
    cli.add_argument("--tau-keep", type=float, default=0.3)
    cli.add_argument("--low-turns", type=int, default=2)
    cli.add_argument("--silence-seconds", type=float, default=20)
    cli.add_argument("--context-turns", type=int, default=3)
    cli.add_argument("--break-on-clip", action=argparse.BooleanOptionalAction, default=True)
    cli.add_argument("--bridge-unlabeled", action="store_true")
    cli.add_argument("--include-trailing-low", action="store_true")
    cli.add_argument("--duration-mode", choices=("source", "evaluated_span"), default="source")
    cli.add_argument("--overlap-fraction", type=float, default=0.5)
    cli.add_argument("--min-positives", type=int, default=5)
    cli.add_argument("--false-per-hour-budget", type=float, default=3.0)
    cli.add_argument("--recall-target", type=float, default=0.75)
    cli.add_argument("--sweep", type=float, nargs="+", help="tau_open values (operating tau_open is also included)")
    return cli


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv and argv[0] == "compare":
        cli = argparse.ArgumentParser(description="Compare evaluation JSON results side by side")
        cli.add_argument("results", nargs="+", type=Path)
        args = cli.parse_args(argv[1:])
        try:
            print(compare_results(args.results), end="")
        except (ValueError, OSError, KeyError, TypeError) as error:
            cli.error(str(error))
        return
    cli = parser()
    args = cli.parse_args(argv)
    try:
        config = AssemblyConfig(**{key: getattr(args, key) for key in asdict(AssemblyConfig())})
        inputs = load_inputs(args.labels, args.scores, args.windows, args.utterances_dir,
                             args.meta, args.pairs, args.duration_mode)
        result = evaluate(inputs, config, **{key: getattr(args, key) for key in (
            "threshold", "positive_story", "min_positives", "overlap_fraction", "sweep",
            "false_per_hour_budget", "recall_target", "duration_mode")})
        result["inputs"] = {key: str(getattr(args, key)) if getattr(args, key) else None
                            for key in ("labels", "scores", "windows", "utterances_dir", "pairs", "meta")}
        output = args.out
        if output.suffix.lower() != ".json":
            output = output.with_suffix(".json")
        output.parent.mkdir(parents=True, exist_ok=True)
        summary = markdown_summary(result)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
                          encoding="utf-8")
        output.with_suffix(".md").write_text(summary, encoding="utf-8")
        print(f"JSON: {output}\nMarkdown: {output.with_suffix('.md')}")
    except (ValueError, OSError) as error:
        cli.error(str(error))


if __name__ == "__main__":
    main()
