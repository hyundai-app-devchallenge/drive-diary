"""Pairwise gold/LLM agreement, implemented without pipeline label tools."""

import argparse
from collections import Counter
from itertools import combinations
import json
from pathlib import Path

from common.schema import EXCLUDES, KINDS, validate_label
from tools.gold.serve import read_jsonl


def agreement(a, b, *, quadratic=False):
    """Exact agreement and Cohen kappa; undefined quantities are None.

    For quadratic weighting, disagreement is (a-b)^2 over fixed scores 0..3.
    Dividing all weights by 9 would cancel in observed/expected disagreement.
    """
    if len(a) != len(b):
        raise ValueError("Paired arrays must have the same length")
    n = len(a)
    if not n:
        return {"n": 0, "exact": None, "kappa": None}
    counts_a, counts_b = Counter(a), Counter(b)
    distance = (lambda x, y: (x - y) ** 2) if quadratic else (lambda x, y: int(x != y))
    observed = sum(distance(x, y) for x, y in zip(a, b))
    expected = sum(distance(x, y) * nx * ny for x, nx in counts_a.items() for y, ny in counts_b.items())
    return {"n": n, "exact": sum(x == y for x, y in zip(a, b)) / n,
            "kappa": 1 - observed * n / expected if expected else None}


def load_labels(path):
    labels = {}
    for row in read_jsonl(Path(path)):
        validate_label(row)
        if row["id"] in labels:
            raise ValueError(f"{path}: duplicate id {row['id']}")
        labels[row["id"]] = row
    return labels


def compare(a, b):
    shared = sorted(a.keys() & b.keys())
    story_ids = [i for i in shared if not (a[i]["skip"] or b[i]["skip"])]
    metrics = {
        "story>=2": agreement([a[i]["story"] >= 2 for i in story_ids], [b[i]["story"] >= 2 for i in story_ids]),
        "story:0-3 (quadratic)": agreement([a[i]["story"] for i in story_ids], [b[i]["story"] for i in story_ids], quadratic=True),
        "skip": agreement([a[i]["skip"] for i in shared], [b[i]["skip"] for i in shared]),
    }
    for field, values in (("kind", KINDS), ("exclude", EXCLUDES)):
        for value in sorted(values):
            metrics[f"{field}:{value}"] = agreement([value in a[i][field] for i in shared], [value in b[i][field] for i in shared])
    return {"shared_n": len(shared), "story_n": len(story_ids),
            "skipped_either": len(shared) - len(story_ids),
            "skipped_both": sum(a[i]["skip"] and b[i]["skip"] for i in shared),
            "skipped_only_a": sum(a[i]["skip"] and not b[i]["skip"] for i in shared),
            "skipped_only_b": sum(b[i]["skip"] and not a[i]["skip"] for i in shared),
            "metrics": metrics}


def markdown(pairs):
    def escape(value):
        return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", " ")

    def number(value):
        return "n/a" if value is None else f"{value:.6f}"

    lines = ["| A | B | shared n | story n | skip either | skip both | skip only A | skip only B |",
             "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for pair in pairs:
        lines.append("| " + " | ".join(escape(pair[k]) for k in ("a", "b", "shared_n", "story_n", "skipped_either", "skipped_both", "skipped_only_a", "skipped_only_b")) + " |")
    lines += ["", "| A | B | metric | n | exact agreement | kappa |", "| --- | --- | --- | ---: | ---: | ---: |"]
    for pair in pairs:
        for metric, values in pair["metrics"].items():
            lines.append(f"| {escape(pair['a'])} | {escape(pair['b'])} | {metric} | {values['n']} | {number(values['exact'])} | {number(values['kappa'])} |")
    lines += ["", "Story metrics exclude rows skipped by either labeler. Flag/skip metrics use all shared IDs.",
              "Exact agreement is a fraction (0–1). Kappa is n/a when expected disagreement is zero; empty metrics are n/a."]
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Pairwise human/LLM agreement on shared IDs")
    parser.add_argument("files", nargs="+", type=Path)
    parser.add_argument("--json", action="store_true", help="Output JSON instead of Markdown (undefined = null)")
    args = parser.parse_args(argv)
    if len(args.files) < 2:
        parser.error("Provide at least two JSONL label files")
    try:
        loaded = [(str(path), load_labels(path)) for path in args.files]
        pairs = [{"a": pa, "b": pb, **compare(a, b)} for (pa, a), (pb, b) in combinations(loaded, 2)]
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(json.dumps({"pairs": pairs}, ensure_ascii=False, indent=2, allow_nan=False) if args.json else markdown(pairs))


if __name__ == "__main__":
    main()
