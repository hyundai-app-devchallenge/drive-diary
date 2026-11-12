"""Shared text input and deterministic scene/pair-stratified learning curves."""
from collections import Counter, defaultdict
from dataclasses import dataclass, field
import json
from pathlib import Path

import numpy as np

from demo.trial.data import LABELS, input_text, load_synth, split_scenes
from eval import real


@dataclass(repr=False)
class RealExample:
    id: str
    source: str
    text: str = field(repr=False)
    label: int
    seconds: float
    cast: str
    unsure: bool


def scene_groups(examples):
    parent, pairs = {}, {}
    def root(k):
        parent.setdefault(k, k)
        if parent[k] != k:
            parent[k] = root(parent[k])
        return parent[k]
    for row in examples:
        root(row.scene)
        if row.pair_id:
            other = pairs.setdefault(row.pair_id, row.scene)
            parent[root(row.scene)] = root(other)
    groups = defaultdict(list)
    for row in examples:
        groups[root(row.scene)].append(row)
    return [groups[k] for k in sorted(groups)]


def stratified_subset(examples, size, seed=55):
    """Proportional allocation by label composition and paired membership.

    Select whole scene/pair components; variants of a scene stay together.
    Fixed per-stratum shuffles give stable samples independent of train seeds.
    The allocation error is bounded by whole-group granularity.
    """
    total = len({r.scene for r in examples})
    if not 0 < size <= total:
        raise ValueError('invalid synthetic size')
    if size == total:
        return list(examples)
    strata = defaultdict(list)
    for group in scene_groups(examples):
        labels = {r.scene: r.label for r in group}
        key = tuple(sorted(labels.values())), any(r.pair_id for r in group)
        strata[key].append(group)
    rng = np.random.default_rng(seed)
    selected, allocations, targets = [], {}, {}
    for key in sorted(strata):
        units = strata[key]
        rng.shuffle(units)
        width = len({r.scene for r in units[0]})
        targets[key] = len(units) * size / total
        count = int(targets[key])
        allocations[key] = count
        selected.extend(units[:count])
    current = sum(len({r.scene for r in g}) for g in selected)
    while current < size:
        eligible = [k for k, units in strata.items() if allocations[k] < len(units)
                    and len({r.scene for r in units[allocations[k]]}) <= size - current]
        if not eligible:
            raise ValueError('requested size incompatible with whole pairs')
        key = max(sorted(eligible), key=lambda k: targets[k] - allocations[k])
        group = strata[key][allocations[key]]
        selected.append(group)
        allocations[key] += 1
        current += len({r.scene for r in group})
    ids = {r.id for g in selected for r in g}
    return [r for r in examples if r.id in ids]


def counts(rows):
    return dict(blocks=len(rows), scenes=len({r.scene for r in rows}),
                labels=dict(Counter(LABELS[r.label] for r in rows)),
                paired_scenes=len({r.scene for r in rows if r.pair_id}))


def load_dev(root, run='v1', *, split='dev', open_test=False):
    """Human labels enter only through the mandatory eval.real gate."""
    directory = real.run_directory(root, run)
    blocks, labels = real.load_labels(root, directory, annotators=['vizatae'],
                                     split=split, open_test=open_test)
    humans = labels['vizatae']
    rows, unknown, unknown_sources = [], 0, set()
    for block in blocks:
        if block['cast'] == 'unknown':
            unknown += 1
            unknown_sources.add(block['source'])
            continue
        if block['id'] not in humans:
            continue
        human = humans[block['id']]
        label = LABELS.index(human['kind']) if human['keep'] == 'keep' else 0
        rows.append(RealExample(block['id'], block['source'], input_text(block['lines']),
                                label, float(block['seconds']), block['cast'], human['unsure']))
    sources = json.loads((directory / 'sources.json').read_text(encoding='utf-8'))
    usable = {'yt:' + s['id']: float(s['usable_seconds']) for s in sources}
    if any(not np.isfinite(usable.get(r.source, 0)) or usable.get(r.source, 0) <= 0
           or not np.isfinite(r.seconds) or r.seconds < 0 for r in rows):
        raise ValueError('invalid source duration')
    return rows, usable, dict(unknown_cast_blocks=unknown, unknown_cast_sources=len(unknown_sources),
        labeled_blocks=len(rows), sources=len({r.source for r in rows}),
        by_cast={cast: dict(blocks=sum(r.cast == cast for r in rows),
                           sources=len({r.source for r in rows if r.cast == cast}),
                           labels=dict(Counter(LABELS[r.label] for r in rows if r.cast == cast)),
                           unsure=sum(r.unsure for r in rows if r.cast == cast),
                           listened_false=sum(not humans[r.id]['listened'] for r in rows if r.cast == cast))
                 for cast in sorted({r.cast for r in rows})})
