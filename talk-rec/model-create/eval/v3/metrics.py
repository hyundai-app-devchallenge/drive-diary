"""Thresholds, source-held-out predictions and paired bootstrap intervals."""
import numpy as np

KEYS = ('keep_recall', 'none_specificity', 'keep_precision', 'accuracy', 'auroc',
        'minutes_per_hour', 'accuracy3')


def select_threshold(gold, probability, target=.85):
    y, p = np.asarray(gold) != 0, np.asarray(probability, dtype=float)
    if not len(y) or p.shape != y.shape or not np.isfinite(p).all() or np.any((p < 0) | (p > 1)):
        raise ValueError('invalid threshold arrays')
    candidates = sorted(set([0., .5, 1., float(np.nextafter(1., 2.)), *p.tolist()]))
    def score(t):
        pred = p >= t
        recall = float(pred[y].mean()) if y.any() else 1.
        specificity = float((~pred[~y]).mean()) if (~y).any() else 0.
        # Feasible first, maximize specificity; fallback maximizes recall.
        # Ties: recall, closest to .5, lower threshold (fully deterministic).
        return (recall >= target, specificity if recall >= target else recall,
                recall if recall >= target else specificity, -abs(t - .5), -t)
    return float(max(candidates, key=score))


def held_out(rows, probability):
    p = np.asarray(probability)
    gold = np.asarray([r.label for r in rows])
    sources = np.asarray([r.source for r in rows])
    pred = np.zeros(len(rows), dtype=bool)
    thresholds = {}
    if len(set(sources)) < 2:
        raise ValueError('LOSO needs multiple sources')
    for source in sorted(set(sources)):
        fit, test = sources != source, sources == source
        threshold = select_threshold(gold[fit], p[fit])
        thresholds[source] = threshold
        pred[test] = p[test] >= threshold
    return pred.tolist(), thresholds


def values(gold, probability, prediction, pred3, seconds, exposure, hard=False):
    gold = np.asarray(gold)
    y, pred, p = gold != 0, np.asarray(prediction, dtype=bool), np.asarray(probability)
    def ratio(n, d):
        return float(n / d) if d else None
    positive, negative = p[y], p[~y]
    auc = float(np.mean((positive[:, None] > negative) + .5 * (positive[:, None] == negative))) if (
        len(positive) and len(negative) and not hard) else None
    return dict(keep_recall=ratio(np.sum(y & pred), np.sum(y)),
                none_specificity=ratio(np.sum(~y & ~pred), np.sum(~y)),
                keep_precision=ratio(np.sum(y & pred), np.sum(pred)),
                accuracy=ratio(np.sum(y == pred), len(y)), auroc=auc,
                minutes_per_hour=ratio(np.sum(np.asarray(seconds)[pred]) * 60, exposure),
                accuracy3=float(np.mean(np.asarray(pred3) == gold)) if pred3 is not None and len(y) else None)


def stats(values):
    values = [v for v in values if v is not None]
    return dict(mean=float(np.mean(values)) if values else None,
                std=float(np.std(values, ddof=1)) if len(values) > 1 else (0. if values else None))


def bootstrap_indices(rows, count, seed=55, cluster=True):
    rng = np.random.default_rng(seed)
    sources = sorted({r.source for r in rows})
    groups = {s: np.asarray([i for i, r in enumerate(rows) if r.source == s]) for s in sources}
    for _ in range(count):
        if cluster:
            draw = rng.choice(sources, len(sources)).tolist()
            yield np.concatenate([groups[s] for s in draw]), draw
        else:
            yield rng.integers(0, len(rows), len(rows)), sources


def aggregate(rows, runs, usable, bootstrap=2000, hard=False):
    """Seed mean±SD, percentile CI of seed mean; paired across methods.

    Intervals condition on fitted models and original dev/LOSO thresholds.
    Source draws repeat both block rows and source exposure. Block draws keep
    original source exposure fixed (secondary sampling-unit sensitivity).
    """
    if not rows:
        return dict(n=0, sources=0, metrics={k: dict(mean=None, std=None) for k in KEYS})
    gold = np.asarray([r.label for r in rows])
    seconds = np.asarray([r.seconds for r in rows])
    exposure = sum(usable[s] for s in {r.source for r in rows})
    arrays = [dict(probability=np.asarray(r['probability']), prediction=np.asarray(r['prediction']),
                   pred3=np.asarray(r['pred3']) if r.get('pred3') is not None else None) for r in runs]
    def scores(ix, denominator, with_std=False):
        per_seed = [values(gold[ix], a['probability'][ix], a['prediction'][ix],
                           a['pred3'][ix] if a['pred3'] is not None else None,
                           seconds[ix], denominator, hard) for a in arrays]
        if with_std:
            return {k: stats([s[k] for s in per_seed]) for k in KEYS}
        result = {}
        for k in KEYS:
            defined = [s[k] for s in per_seed if s[k] is not None]
            result[k] = sum(defined) / len(defined) if defined else None
        return result
    metrics = scores(np.arange(len(rows)), exposure, with_std=True)
    for cluster, name in ((True, 'cluster_ci95'), (False, 'block_ci95')):
        samples = {k: [] for k in KEYS}
        for ix, draw in bootstrap_indices(rows, bootstrap, cluster=cluster):
            result = scores(ix, sum(usable[s] for s in draw))
            for k in KEYS:
                if result[k] is not None:
                    samples[k].append(result[k])
        for k, sample in samples.items():
            low, high = np.quantile(sample, [.025, .975]) if sample else (None, None)
            metrics[k][name] = dict(low=float(low) if low is not None else None,
                                   high=float(high) if high is not None else None, defined=len(sample))
    return dict(n=len(rows), sources=len({r.source for r in rows}),
                keep=int(np.sum(gold != 0)), unsure=sum(r.unsure for r in rows),
                usable_seconds=exposure, metrics=metrics)


def evaluate(rows, runs, usable, bootstrap=2000, hard=False):
    scopes = dict(child_family={'child_family'}, all_family={'child_family', 'adult_family', 'couple'},
                  friends={'friends'})
    output = {}
    for scope, casts in scopes.items():
        output[scope] = {}
        for unsure in ('included', 'excluded'):
            ix = [i for i, r in enumerate(rows) if r.cast in casts and (unsure == 'included' or not r.unsure)]
            chosen = [rows[i] for i in ix]
            sampled = [{k: [r[k][i] for i in ix] if r.get(k) is not None else None
                        for k in ('probability', 'prediction', 'pred3')} for r in runs]
            output[scope][unsure] = aggregate(chosen, sampled, usable, bootstrap, hard)
    return output


def choose_model(m0, m1):
    """DESIGN 6.1: primary recall CI must clear M0's upper endpoint."""
    a = m0['keep_recall']['cluster_ci95']
    b = m1['keep_recall']['cluster_ci95']
    return 'M1' if a['high'] is not None and b['low'] is not None and b['low'] > a['high'] else 'M0'


def selection_rule(m0, m1):
    choice = choose_model(m0, m1)
    a, b = (m['keep_recall']['cluster_ci95'] for m in (m0, m1))
    def interval(ci):
        return f"[{ci['low']:.3f}, {ci['high']:.3f}]" if ci['low'] is not None else '정의되지 않음'
    reason = 'M1 하단이 M0 상단보다 높아' if choice == 'M1' else 'M1 하단이 M0 상단을 넘지 않아'
    return (f"DESIGN §6.1 적용: LOSO keep 재현율 출처 bootstrap 95% 구간 "
            f"M0 {interval(a)}, M1 {interval(b)}에서 {reason} {choice}를 선택한다.")
