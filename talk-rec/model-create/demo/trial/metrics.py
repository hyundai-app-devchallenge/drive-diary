"""Numeric-only metrics; all thresholds are selected on synthetic validation."""
import numpy as np

METRICS = ("accuracy", "keep_recall", "keep_precision", "auroc", "accuracy3", "tn", "fp", "fn", "tp")


def metrics(gold, probability, threshold=.5, pred3=None):
    labels = np.asarray(gold)
    y = labels != 0
    p = np.asarray(probability, dtype=float)
    if len(y) == 0 or p.shape != y.shape or not np.isfinite(p).all() or np.any((p < 0) | (p > 1)):
        raise ValueError("invalid metric arrays")
    pred = p >= threshold
    tn, fp = int(np.sum(~y & ~pred)), int(np.sum(~y & pred))
    fn, tp = int(np.sum(y & ~pred)), int(np.sum(y & pred))
    # Pairwise definition handles ties and avoids undefined single-class ROC calls.
    positive, negative = p[y], p[~y]
    auc = (float(np.mean((positive[:, None] > negative) + .5 * (positive[:, None] == negative)))
           if len(positive) and len(negative) else None)
    return dict(accuracy=(tn + tp) / len(y), keep_recall=tp / (tp + fn) if tp + fn else None,
                keep_precision=tp / (tp + fp) if tp + fp else 0., auroc=auc,
                accuracy3=float(np.mean(np.asarray(pred3) == labels)) if pred3 is not None else None,
                tn=tn, fp=fp, fn=fn, tp=tp)


def select_threshold(gold, probability):
    """Maximize balanced accuracy; tie: closest to .5, then lower threshold."""
    y = np.asarray(gold) != 0
    p = np.asarray(probability)
    candidates = sorted(set([0., .5, 1., float(np.nextafter(1., 2.))] + p.tolist()))
    def score(t):
        pred = p >= t
        balanced = (float(np.mean(pred[y])) + float(np.mean(~pred[~y]))) / 2
        return balanced, -abs(t - .5), -t
    return float(max(candidates, key=score))


def aggregate(gold, runs, bootstrap=2000, seed=1046):
    """Seed mean±sample SD; paired block bootstrap CI for the seed mean.

    Undefined AUROC resamples are omitted and counted, not replaced by zero.
    Bootstrap is over test blocks, not seeds, and does not refit/tune models.
    """
    scores = [metrics(gold, r["probability"], r["threshold"], r.get("pred3")) for r in runs]
    output = {}
    for name in METRICS:
        values = [s[name] for s in scores if s[name] is not None]
        output[name] = dict(mean=float(np.mean(values)) if values else None,
                            std=float(np.std(values, ddof=1)) if len(values) > 1 else (0. if values else None))
    if bootstrap:
        rng = np.random.default_rng(seed)
        samples = {k: [] for k in METRICS}
        gold = np.asarray(gold)
        for _ in range(bootstrap):
            ix = rng.integers(0, len(gold), len(gold))
            rows = [metrics(gold[ix], np.asarray(r["probability"])[ix], r["threshold"],
                            np.asarray(r["pred3"])[ix] if r.get("pred3") is not None else None) for r in runs]
            for name in METRICS:
                values = [r[name] for r in rows if r[name] is not None]
                if values:
                    samples[name].append(float(np.mean(values)))
        for name, values in samples.items():
            low, high = np.quantile(values, [.025, .975]) if values else (None, None)
            output[name].update(ci_low=float(low) if low is not None else None,
                                ci_high=float(high) if high is not None else None, ci_valid=len(values))
    return output
