"""Pure run construction, policies and aggregate metrics. Never log inputs."""
from collections import Counter
import math

FIRST_THRESHOLD = .957
CONT_THRESHOLDS = (.957, .9, .8, .7, .5, .3)
MIN_CHARS = (0, 20, 40, 80, 120)
KINDS = ('none', 'story', 'moment')


def build_runs(blocks):
    """Maximal chronological chains; a non-length close always ends a run."""
    ordered = sorted(blocks, key=lambda b: (b['source'], b['start'], b['end'], b['id']))
    if len({b['id'] for b in ordered}) != len(ordered):
        raise ValueError('duplicate block identities')
    runs = []
    for block in ordered:
        if (not all(math.isfinite(block[k]) for k in ('start', 'end'))
                or block['end'] < block['start']):
            raise ValueError('invalid block times')
        previous = runs[-1][-1] if runs else None
        if (previous is not None and previous['source'] == block['source']
                and previous['closing'] == 'length'
                and previous.get('range_index') == block.get('range_index')
                and 0 <= block['start'] - previous['end'] <= 2):
            runs[-1].append(block)
        else:
            runs.append([block])
    return runs


def content_chars(text):
    return sum(not char.isspace() for char in text)


def policy_name(threshold, minimum):
    return f'cont{threshold:g}_min{minimum}'


def parse_policy(name):
    for threshold in CONT_THRESHOLDS:
        for minimum in MIN_CHARS:
            if name == policy_name(threshold, minimum):
                return threshold, minimum
    raise ValueError('unknown stitch policy')


def decide(run, threshold=FIRST_THRESHOLD, minimum=0, *, enforce_policy=False):
    """Only an immediately saved predecessor lowers the threshold.

    Offline grids intentionally ignore human policy flags. Runtime policy blocking
    can be exercised explicitly; a blocked/gated/dropped block resets hysteresis.
    """
    if not 0 <= threshold <= 1 or type(minimum) is not int or minimum < 0:
        raise ValueError('invalid policy settings')
    saved = []
    for block in run:
        limit = threshold if saved and saved[-1] else FIRST_THRESHOLD
        blocked = enforce_policy and bool((block.get('human') or {}).get('policy'))
        saved.append(bool(not blocked and block['chars'] >= minimum and block['probability'] >= limit))
    return saved


def quantile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    index = (len(values) - 1) * fraction
    lo, hi = math.floor(index), math.ceil(index)
    return values[lo] + (values[hi] - values[lo]) * (index - lo)


def histogram(values):
    return dict(sorted(Counter(values).items()))


def human_keep(block):
    return (block.get('human') or {}).get('keep') == 'keep'


def metrics(runs, threshold, minimum=0):
    keep_total = keep_saved = holes = saved_blocks = 0
    dropped_keep = dict(first=0, continuation=0)
    false_saves = dict(first=0, continuation=0)
    saved_seconds = 0.
    for run in runs:
        saved = decide(run, threshold, minimum)
        for index, (block, save) in enumerate(zip(run, saved)):
            position = 'first' if index == 0 else 'continuation'
            human = block.get('human') or {}
            if human_keep(block):
                keep_total += 1
                keep_saved += save
                dropped_keep[position] += not save
            elif human.get('keep') == 'none' and save:
                false_saves[position] += 1
            if save:
                saved_blocks += 1
                saved_seconds += block['end'] - block['start']
            if (0 < index < len(run) - 1 and saved[index - 1] and not save
                    and saved[index + 1] and human_keep(block)):
                holes += 1
    return dict(t_cont=threshold, min_chars=minimum, human_keep=keep_total,
                keep_saved=keep_saved, keep_recall=keep_saved / keep_total if keep_total else None,
                human_keep_dropped=dropped_keep, false_saves=false_saves,
                holes=holes, saved_blocks=saved_blocks, saved_seconds=saved_seconds)


def aggregate(runs):
    blocks = [block for run in runs for block in run]
    spans = [run[-1]['end'] - run[0]['start'] for run in runs]
    pairs = [(a, b) for run in runs for a, b in zip(run, run[1:])
             if human_keep(a) and human_keep(b)]
    human_changed = sum(a['human']['kind'] != b['human']['kind'] for a, b in pairs)
    model_changed = sum(a['model_kind'] != b['model_kind'] for a, b in pairs)
    both_changed = sum(a['human']['kind'] != b['human']['kind'] and
                       a['model_kind'] != b['model_kind'] for a, b in pairs)
    gates = []
    for threshold in (FIRST_THRESHOLD, .5):
        for minimum in MIN_CHARS:
            removed = dict(human_none=0, human_keep=0, unlabeled=0)
            direct = dict(removed)
            for run in runs:
                before, after = decide(run, threshold), decide(run, threshold, minimum)
                for block, old, new in zip(run, before, after):
                    label = (block.get('human') or {}).get('keep')
                    key = 'human_keep' if label == 'keep' else 'human_none' if label == 'none' else 'unlabeled'
                    if old and not new:
                        removed[key] += 1
                    if old and block['chars'] < minimum:
                        direct[key] += 1
            gates.append(dict(metrics(runs, threshold, minimum), removed_vs_min0=removed,
                              directly_below_minimum=direct))
    return dict(
        counts=dict(blocks=len(blocks), labeled=sum(b.get('human') is not None for b in blocks),
                    unlabeled=sum(b.get('human') is None for b in blocks),
                    policy_labeled=sum(bool((b.get('human') or {}).get('policy')) for b in blocks)),
        runs=dict(count=len(runs), blocks_per_run=histogram(map(len, runs)),
                  seconds=dict(median=quantile(spans, .5), p90=quantile(spans, .9), max=max(spans, default=None)),
                  continuation_blocks=len(blocks) - len(runs),
                  continuation_share=(len(blocks) - len(runs)) / len(blocks) if blocks else None),
        current=metrics(runs, FIRST_THRESHOLD),
        hysteresis=[metrics(runs, threshold) for threshold in CONT_THRESHOLDS],
        kind_consistency=dict(adjacent_human_keep_pairs=len(pairs), human_different=human_changed,
                              model_different=model_changed, both_different=both_changed,
                              human_different_rate=human_changed / len(pairs) if pairs else None,
                              model_different_rate=model_changed / len(pairs) if pairs else None),
        min_content=gates)


def snapshot(runs):
    """Explicit allowlist: no transcript, titles, free-form labels or comments."""
    rows = []
    for index, run in enumerate(runs):
        policies = {policy_name(t, n): decide(run, t, n) for t in CONT_THRESHOLDS for n in MIN_CHARS}
        rows.append(dict(run=index, blocks=[dict(
            id=b['id'], probability=b['probability'], model_kind=b['model_kind'], human=b['human'],
            decisions={name: saved[i] for name, saved in policies.items()}) for i, b in enumerate(run)]))
    return rows
