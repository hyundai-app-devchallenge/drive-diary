"""Model-independent windows, event groups, mixing and evaluation."""
from __future__ import annotations

from abc import ABC, abstractmethod
from itertools import combinations

import numpy as np
from sklearn.metrics import roc_auc_score

RATE = 16000
TARGETS = ('laugh', 'shout', 'cry')
GROUPS = {
    'laugh': ('Laughter', 'Baby laughter', 'Giggle', 'Snicker', 'Belly laugh', 'Chuckle, chortle'),
    'shout': ('Shout', 'Yell', 'Screaming', 'Children shouting', 'Bellow', 'Whoop', 'Battle cry'),
    'cry': ('Crying, sobbing', 'Baby cry, infant cry', 'Whimper', 'Wail, moan'),
    'sing': ('Singing', 'Child singing', 'Humming', 'Chant'),
    'speech': ('Speech', 'Child speech, kid speaking', 'Conversation'),
}
CLASS_GROUP = {'laugh': 'laugh', 'screaming': 'shout', 'crying': 'cry'}


def window_ranges(count, rate=RATE):
    """10 s windows at 5 s hops; a final partial window covers the tail."""
    if rate <= 0 or count <= 0:
        raise ValueError('audio and sample rate must be positive')
    size, hop = round(10 * rate), round(5 * rate)
    starts = [0] if count <= size else range(0, count - size + hop, hop)
    return [(start, min(count, start + size)) for start in starts]


def resolve_groups(labels):
    available = set(labels)
    return {group: [name for name in names if name in available] for group, names in GROUPS.items()}


class SoundModel(ABC):
    """Replace only score_window for another model; all other policy is shared.

    score_windows returns {AudioSet label: max probability across windows}.
    summarize additionally preserves group means over the same windows.
    """
    labels: tuple[str, ...]

    @abstractmethod
    def score_window(self, waveform, rate) -> dict[str, float]:
        """Return label probabilities for one window, including short audio."""

    def iter_window_scores(self, waveform, rate):
        waveform = np.asarray(waveform, dtype=np.float32)
        if waveform.ndim != 1 or not np.isfinite(waveform).all():
            raise ValueError('expected finite mono waveform')
        for start, end in window_ranges(len(waveform), rate):
            row = self.score_window(waveform[start:end], rate)
            if set(row) != set(self.labels) or any(not np.isfinite(v) or not 0 <= v <= 1 for v in row.values()):
                raise ValueError('invalid model probabilities')
            yield row

    def score_windows(self, waveform, rate) -> dict[str, float]:
        rows = list(self.iter_window_scores(waveform, rate))
        return {name: max(row[name] for row in rows) for name in self.labels}

    def summarize(self, waveform, rate=RATE):
        mapping = resolve_groups(self.labels)
        rows = [{group: max((row[name] for name in names), default=0.0)
                 for group, names in mapping.items()}
                for row in self.iter_window_scores(waveform, rate)]
        return {group: {'max': max(row[group] for row in rows),
                        'mean': float(np.mean([row[group] for row in rows]))}
                for group in GROUPS}


def mix_snr(event, background, snr_db):
    """Scale event power relative to background; common peak scaling keeps SNR.

    Returns (mixture, scaled_event, scaled_background), enabling ratio auditing.
    Silent components cannot define an SNR and are rejected.
    """
    event, background = (np.asarray(x, dtype=np.float64) for x in (event, background))
    if event.ndim != 1 or event.shape != background.shape or not len(event):
        raise ValueError('mix requires equal nonempty mono inputs')
    ep, bp = float(np.mean(event ** 2)), float(np.mean(background ** 2))
    if not np.isfinite([ep, bp, snr_db]).all() or min(ep, bp) <= 1e-16:
        raise ValueError('SNR undefined for silent or invalid input')
    scaled = event * np.sqrt(bp * 10 ** (snr_db / 10) / ep)
    divisor = max(1.0, float(np.max(np.abs(scaled + background))))
    scaled, background = scaled / divisor, background / divisor
    return tuple(x.astype(np.float32) for x in (scaled + background, scaled, background))


def calibrate_thresholds(scores, fpr=.05):
    """Order-statistic threshold, strict >, empirical calibration FPR <= target.

    Ties stay negative. With small n the attainable FPR is discrete (often 0).
    """
    if not scores or not 0 <= fpr < 1:
        raise ValueError('need calibration rows and valid false-positive rate')
    index = len(scores) - int(np.floor(fpr * len(scores))) - 1
    return {group: float(sorted(row[group] for row in scores)[index]) for group in GROUPS}


def rates(scores, thresholds):
    return {group: float(np.mean([row[group] > thresholds[group] for row in scores])) if scores else None
            for group in thresholds}


def auc(truth, scores):
    return float(roc_auc_score(truth, scores)) if len(set(truth)) == 2 else None


def classification_metrics(rows, thresholds):
    """Rows: {class, scores}; matrices have true rows and predicted columns."""
    targets = [r for r in rows if r['class'] in CLASS_GROUP]
    confusion = np.zeros((3, 3), dtype=int)
    gated = np.zeros((3, 4), dtype=int)
    detected = {g: [] for g in TARGETS}
    for row in targets:
        actual = CLASS_GROUP[row['class']]
        predicted = max(TARGETS, key=lambda g: row['scores'][g])
        i = TARGETS.index(actual)
        confusion[i, TARGETS.index(predicted)] += 1
        eligible = [g for g in TARGETS if row['scores'][g] > thresholds[g]]
        chosen = max(eligible, key=lambda g: row['scores'][g]) if eligible else None
        gated[i, TARGETS.index(chosen) if chosen else 3] += 1
        if chosen:
            detected[actual].append(chosen)
    pairwise = {}
    for a, b in combinations(TARGETS, 2):
        pair = [r for r in targets if CLASS_GROUP[r['class']] in (a, b)]
        pairwise[f'{a}_vs_{b}'] = auc(
            [CLASS_GROUP[r['class']] == a for r in pair],
            [r['scores'][a] - r['scores'][b] for r in pair])
    detection = {}
    for group in TARGETS:
        subset = [r['scores'] for r in targets if CLASS_GROUP[r['class']] == group]
        found = detected[group]
        detection[group] = {
            'n': len(subset), 'n_detected_any': len(found),
            'own_threshold_recall': rates(subset, thresholds)[group],
            'any_target_recall': len(found) / len(subset) if subset else None,
            'wrong_label_among_detected': sum(g != group for g in found) / len(found) if found else None,
            'predicted_among_detected': {g: found.count(g) / len(found) if found else None for g in TARGETS},
        }
    other = [r['scores'] for r in rows if r['class'] not in CLASS_GROUP]
    return {
        'n': len(rows), 'n_target': len(targets), 'label_order': list(TARGETS),
        'confusion': confusion.tolist(),
        'accuracy': float(np.trace(confusion) / len(targets)) if targets else None,
        'pairwise_auroc_score_difference': pairwise,
        'gated_confusion_columns': [*TARGETS, 'reject'], 'gated_confusion': gated.tolist(),
        'detection': detection, 'n_other': len(other), 'other_false_alarm': rates(other, thresholds),
        'other_false_alarm_by_class': {
            name: {'n': sum(r['class'] == name for r in rows),
                   'rates': rates([r['scores'] for r in rows if r['class'] == name], thresholds)}
            for name in sorted({r['class'] for r in rows} - set(CLASS_GROUP))},
        'other_any_target_false_alarm': float(np.mean([
            any(r[g] > thresholds[g] for g in TARGETS) for r in other])) if other else None,
    }


def real_metrics(rows):
    summaries = {}
    for verdict in sorted({r['verdict'] for r in rows}):
        subset = [r for r in rows if r['verdict'] == verdict]
        summaries[verdict] = {'n': len(subset), 'groups': {
            group: {stat: {'median': float(np.median([r['groups'][group][stat] for r in subset])),
                           'max': max(r['groups'][group][stat] for r in subset)}
                    for stat in ('max', 'mean')} for group in GROUPS}}
    labeled = [r for r in rows if r['verdict'] != 'unsure']
    tasks = {}
    for name, positive in [('moment_vs_rest', {'moment'}), ('keep_vs_none', {'story', 'moment'})]:
        truth = [r['verdict'] in positive for r in labeled]
        tasks[name] = {'n': len(labeled), 'n_positive': sum(truth), 'auroc': {}}
        for feature in ('laugh', 'sing', 'laugh_or_sing'):
            scores = [max(r['groups'][g]['max'] for g in ('laugh', 'sing')) if feature == 'laugh_or_sing'
                      else r['groups'][feature]['max'] for r in labeled]
            tasks[name]['auroc'][feature] = auc(truth, scores)
    return {'by_verdict': summaries, 'binary_tasks_excluding_unsure': tasks}
