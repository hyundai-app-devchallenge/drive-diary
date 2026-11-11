"""Utterance metrics with explicit empty-set and score-tie conventions."""

import math

from sklearn.metrics import average_precision_score, roc_auc_score

from common.schema import KINDS, SPLITS


def probability(value, name="score"):
    if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 1:
        raise ValueError(f"{name}: expected a finite number in [0, 1]")
    return float(value)


def at_threshold(gold, scores, threshold):
    predicted = [score >= threshold for score in scores]
    tp = sum(y and p for y, p in zip(gold, predicted))
    fp = sum(not y and p for y, p in zip(gold, predicted))
    fn = sum(y and not p for y, p in zip(gold, predicted))
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return dict(tp=tp, fp=fp, fn=fn, tn=len(gold) - tp - fp - fn,
                precision=precision, recall=recall,
                f1=2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else 0.0)


def binary_metrics(gold, scores, threshold=0.5, min_positives=5):
    """AP uses grouped ties; undefined ranking metrics are None (JSON null)."""
    gold, scores = list(gold), list(scores)
    if len(gold) != len(scores):
        raise ValueError("gold and scores must have equal length")
    if any(type(y) not in (bool, int) or y not in (0, 1) for y in gold):
        raise ValueError("gold must be binary")
    probability(threshold, "threshold")
    scores = [probability(s) for s in scores]
    if type(min_positives) is not int or min_positives < 1:
        raise ValueError("min_positives must be a positive integer")
    positives = sum(gold)
    # Evaluate each distinct threshold once, in O(n log n). A tied maximum
    # chooses the highest threshold. The no-prediction point cannot improve F1.
    tp = fp = 0
    best_f1, best_threshold = 0.0, None
    ordered = sorted(zip(scores, gold), reverse=True)
    index = 0
    while index < len(ordered):
        score = ordered[index][0]
        while index < len(ordered) and ordered[index][0] == score:
            tp += ordered[index][1]
            fp += 1 - ordered[index][1]
            index += 1
        f1 = 2 * tp / (positives + tp + fp) if positives + tp + fp else 0.0
        if best_threshold is None or f1 > best_f1:
            best_f1, best_threshold = f1, score
    return dict(n=len(gold), positives=positives, negatives=len(gold) - positives,
                reliable=positives >= min_positives,
                pr_auc=float(average_precision_score(gold, scores)) if positives else None,
                roc_auc=float(roc_auc_score(gold, scores)) if 0 < positives < len(gold) else None,
                **at_threshold(gold, scores, threshold),
                threshold=threshold, best_f1=best_f1, best_f1_threshold=best_threshold)


def utterance_metrics(rows, threshold=0.5, min_positives=5, positive_story=2):
    """Rows are joined label/score records; skip filtering also works for API use."""
    rows = [row for row in rows if not row.get("skip", False)]
    result = binary_metrics([r["story"] >= positive_story for r in rows],
                            [r["score"] for r in rows], threshold, min_positives)
    flags = sorted({flag for r in rows for flag in r.get("exclude_scores", {})})
    result["exclude"] = {}
    for flag in flags:
        available = [r for r in rows if flag in r.get("exclude_scores", {})]
        value = binary_metrics([flag in r["exclude"] for r in available],
                               [r["exclude_scores"][flag] for r in available],
                               threshold, min_positives)
        value["coverage_n"] = len(available)
        value["missing_n"] = len(rows) - len(available)
        result["exclude"][flag] = value
    return result


def slice_metrics(rows, metadata_columns=(), **options):
    rows = [r for r in rows if not r.get("skip", False)]
    groups = {"kind": {k: [] for k in sorted(KINDS) + ["(none)"]},
              "labeler_type": {"human": [], "llm": []},
              "split": {s: [] for s in sorted(SPLITS)}}
    for column in metadata_columns:
        groups[f"meta:{column}"] = {}
    for row in rows:
        for kind in row["kind"] or ["(none)"]:
            groups["kind"][kind].append(row)
        groups["labeler_type"][row["labeler"].split(":", 1)[0]].append(row)
        groups["split"][row["split"]].append(row)
        for column in metadata_columns:
            value = row.get("meta", {}).get(column) or "(missing)"
            groups[f"meta:{column}"].setdefault(value, []).append(row)
    return {dimension: {value: utterance_metrics(members, **options)
                        for value, members in values.items()}
            for dimension, values in groups.items()}


def minimal_pair_metrics(pairs, rows, skipped_ids=()):
    scores = {r["id"]: r["score"] for r in rows if not r.get("skip", False)}
    skipped_ids = set(skipped_ids) | {r["id"] for r in rows if r.get("skip", False)}
    retained = [p for p in pairs if not {p["positive"], p["negative"]} & skipped_ids]
    missing = {p[key] for p in retained for key in ("positive", "negative")
               if p[key] not in scores}
    if missing:
        raise ValueError(f"minimal pairs: {len(missing)} ids missing from evaluated scores: "
                         + ", ".join(sorted(missing)))
    correct = sum(scores[p["positive"]] > scores[p["negative"]] for p in retained)
    return dict(n=len(retained), correct=correct, skipped=len(pairs) - len(retained),
                accuracy=correct / len(retained) if retained else None)
