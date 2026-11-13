"""Evaluate synthetic-only JSONL; persist counts/IDs, never input text."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import platform
from pathlib import Path
import statistics
import time

from .gate import PolicyGate


def load_cases(path):
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("synthetic") is not True or row.get("category") not in {
            "optout", "command", "external", "mixed", "normal_challenge"
        }:
            raise ValueError("synthetic policy cases required")
        rows.append(row)
    if not rows or len({r["id"] for r in rows}) != len(rows):
        raise ValueError("empty or duplicate policy cases")
    return rows


def load_t53(path):
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        block = json.loads(line)
        # Require T53 synthetic identity/provenance, and never consult real ASR.
        if not str(block.get("source", "")).startswith("synth:") or block.get("demo_only") is not True:
            raise ValueError("synthetic blocks required")
        rows.append(dict(id="t53:" + block["id"], base_id="t53:" + block["id"],
                         split="t53", category="normal_t53", variant="t53",
                         level=block["level"], lines=block["turns"],
                         expected=dict(block="allow", drop_lines=[], retro_delete=False)))
    if not rows or len({r["id"] for r in rows}) != len(rows):
        raise ValueError("empty or duplicate T53 blocks")
    return rows


def summarize(observations):
    n = len(observations)
    blocked = sum(r["actual"]["block"] == "block" for r in observations)
    expected_block = observations[0]["expected"]["block"] == "block"
    drop_exact = sum(r["actual"]["drop_lines"] == r["expected"]["drop_lines"] for r in observations)
    tp = fp = fn = 0
    for row in observations:
        want, got = set(row["expected"]["drop_lines"]), set(row["actual"]["drop_lines"])
        tp += len(want & got)
        fp += len(got - want)
        fn += len(want - got)
    return dict(n=n, blocked=blocked, block_rate=blocked / n,
                block_recall=blocked / n if expected_block else None,
                false_block_rate=None if expected_block else blocked / n,
                drop_exact=drop_exact, drop_exact_rate=drop_exact / n,
                drop_tp=tp, drop_fp=fp, drop_fn=fn,
                drop_precision=tp / (tp + fp) if tp + fp else None,
                drop_recall=tp / (tp + fn) if tp + fn else None,
                retro_delete=sum(r["actual"]["retro_delete"] for r in observations),
                correct=sum(r["actual"]["block"] == r["expected"]["block"]
                            and r["actual"]["retro_delete"] == r["expected"]["retro_delete"]
                            and r["actual"]["drop_lines"] == r["expected"]["drop_lines"] for r in observations),
                affected_allow_blocks=sum(r["actual"]["block"] == "allow" and bool(r["actual"]["drop_lines"])
                                          for r in observations))


def evaluate(rows, engine, repeats=3):
    groups, detail, timings = defaultdict(list), [], []
    # Warmup outside the timed region. Loading/JSON IO are excluded.
    for row in rows[:min(30, len(rows))]:
        engine.gate(row["lines"])
    for row in rows:
        start = time.perf_counter_ns()
        for _ in range(repeats):
            actual = engine.gate(row["lines"])
        elapsed = (time.perf_counter_ns() - start) / repeats / 1_000_000
        timings.append(elapsed)
        # Persist no text, including the original T53 synthetic text.
        entry = {k: row[k] for k in ("id", "base_id", "split", "category", "variant", "level", "expected")}
        entry.update(actual=actual, ms=elapsed)
        detail.append(entry)
        groups[(row["split"], row["category"])].append(entry)
    grouped = {f"{split}/{category}": summarize(items) for (split, category), items in sorted(groups.items())}
    policy = {split: summarize([r for r in detail if r["split"] == split and
                               r["category"] in ("optout", "command", "external")])
              for split in ("dev", "held_out") if any(r["split"] == split for r in detail)}
    thresholds = {split: {"recall": m["block_recall"], "target": .95,
                          "pass": m["block_recall"] >= .95} for split, m in policy.items()}
    by_level = {str(level): summarize([r for r in detail if r["level"] == level and
                                      r["category"] in ("optout", "command", "external")])
                for level in sorted({r["level"] for r in detail})
                if any(r["level"] == level and r["category"] in ("optout", "command", "external") for r in detail)}
    policy_by_variant = {f"{split}/{variant}": summarize([r for r in detail if r["split"] == split and
                            r["variant"] == variant and r["category"] in ("optout", "command", "external")])
                         for split in ("dev", "held_out") for variant in sorted({r["variant"] for r in detail
                             if r["split"] == split and r["category"] in ("optout", "command", "external")})}
    count = Counter(r["category"] for r in engine.rules["patterns"])
    count["optout_literal"] = len(engine.literals)
    count["wake_word"] = len(engine.wake_words)
    ordered = sorted(timings)
    return dict(schema_version=1, synthetic_only=True, rules_sha256=engine.rules_sha256,
                python=platform.python_version(), platform=platform.platform(),
                counts=dict(total=len(rows), by_category=dict(Counter(r["category"] for r in rows))),
                base_expressions={f"{s}/{c}": len({r["base_id"] for r in rows if r["split"] == s
                                                    and r["category"] == c and r["variant"] != "pair"})
                                  for s, c in groups},
                rules=dict(count), rule_count=sum(count.values()),
                grouped=grouped, policy=policy, policy_by_level=by_level,
                policy_by_variant=policy_by_variant, criteria=thresholds,
                timing=dict(repeats=repeats, n=len(timings), mean_ms=statistics.mean(timings),
                            median_ms=statistics.median(timings), p95_ms=ordered[int(.95 * (len(ordered) - 1))],
                            max_ms=max(timings))), detail


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=Path(".local/policy/cases.jsonl"))
    parser.add_argument("--t53", type=Path, default=Path(".local/synth/t53/blocks.jsonl"))
    parser.add_argument("--out-dir", type=Path, default=Path(".local/policy"))
    parser.add_argument("--split", choices=("dev", "held_out", "all"), default="all")
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args(argv)
    if args.repeats < 1:
        parser.error("repeats must be positive")
    try:
        rows = load_cases(args.cases)
        if args.split != "all":
            rows = [r for r in rows if r["split"] in (args.split, "challenge")]
        rows += load_t53(args.t53)
        result, detail = evaluate(rows, PolicyGate(), args.repeats)
        result["inputs"] = {key: hashlib.sha256(path.read_bytes()).hexdigest()
                            for key, path in (("cases_sha256", args.cases), ("t53_sha256", args.t53))}
        args.out_dir.mkdir(parents=True, exist_ok=True)
        (args.out_dir / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (args.out_dir / "predictions.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in detail), encoding="utf-8")
    except (OSError, ValueError, KeyError, TypeError):
        print("policy evaluation failed: invalid or unavailable synthetic inputs")
        return 2
    print(json.dumps(dict(total=result["counts"]["total"], criteria=result["criteria"], timing=result["timing"])))
    return 0 if all(c["pass"] for c in result["criteria"].values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
