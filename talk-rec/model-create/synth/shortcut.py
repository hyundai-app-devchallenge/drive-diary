"""Numeric-only shape shortcut audit; no speech or input IDs are printed."""
import argparse
import json
from pathlib import Path
import sys

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from demo.trial.data import Example, LABELS, input_text, load_synth, read_rows, split_scenes
from demo.trial.models import shape
from synth.generate import validate_cards


# Repeated stratified, scene-grouped 80/20 validation, identical to trial splits.
# Freeze these seeds independently of the card generation seed.
SPLIT_SEEDS = (43, 44, 45, 46, 47)


def cross_validate(examples, features):
    """Scale/fit on train only. Fixed .5 decision; no tuning on held-out data."""
    values = np.asarray(features, dtype=float)
    if values.ndim != 2 or len(values) != len(examples) or not np.isfinite(values).all():
        raise ValueError('invalid shape features')
    by_id = {r.id: i for i, r in enumerate(examples)}
    if len(by_id) != len(examples):
        raise ValueError('duplicate observation identities')
    scores, counts = [], []
    for seed in SPLIT_SEEDS:
        train, val = split_scenes(examples, seed)
        a, b = [by_id[r.id] for r in train], [by_id[r.id] for r in val]
        model = make_pipeline(StandardScaler(), LogisticRegression(max_iter=2000, random_state=seed))
        model.fit(values[a], [r.label != 0 for r in train])
        scores.append(float(np.mean(model.predict(values[b]) == [r.label != 0 for r in val])))
        counts.append(len(val))
    majority = max(sum(r.label == 0 for r in examples), sum(r.label != 0 for r in examples)) / len(examples)
    accuracy = float(np.average(scores, weights=counts))
    return dict(n=len(examples), cv_accuracy=accuracy, majority=majority, limit=majority + .10,
                fail=accuracy > majority + .10 + 1e-12, fold_accuracy=scores,
                validation_observations=sum(counts))


def card_shapes(path):
    cards = read_rows(path)
    validate_cards(cards)
    examples = [Example(str(c['card']), c['card'], '', LABELS.index(c['label'])) for c in cards]
    values = [[c['seconds'], c['n_turns'], len(c['family'])] for c in cards]
    stats = [dict(label=c['label'], seconds=c['seconds'], n_turns=c['n_turns'], family=len(c['family'])) for c in cards]
    return examples, values, stats


def synth_shapes(directories):
    examples, skipped = load_synth(directories)
    blocks = {}
    wanted = {r.id for r in examples}
    for index, directory in enumerate(directories):
        path = Path(directory) / 'blocks.jsonl'
        if path.exists():
            for b in read_rows(path):
                identity = f"s{index}/{b['id']}"
                if identity in wanted:
                    blocks[identity] = b
    values = shape([r.text for r in examples])
    stats = [dict(label=LABELS[r.label], n_turns=int(v[0]), chars=int(v[1]),
                  seconds=blocks[r.id]['end'] - blocks[r.id]['start']) for r, v in zip(examples, values)]
    return examples, values, stats, skipped


def real_shapes(judge_path, asr_path):
    # Human labels only. Predictions are neither required nor consulted.
    judge = json.loads(Path(judge_path).read_text(encoding='utf-8'))
    ids = judge['ids']
    if not ids or len(set(ids)) != len(ids):
        raise ValueError('invalid judge identities')
    selected = [r for r in read_rows(asr_path) if r['config'] == 'pleos' and r['id'] in set(ids)]
    by_id = {r['id']: r for r in selected}
    if len(by_id) != len(selected) or set(by_id) != set(ids):
        raise ValueError('missing or duplicate judged ASR observations')
    stats = []
    for identity in ids:
        row = by_id[identity]
        values = shape([input_text(row['lines'])])[0]
        if not row['lines'] or judge['human'][identity] not in LABELS:
            raise ValueError('invalid real shape input')
        stats.append(dict(label=judge['human'][identity], n_turns=int(values[0]), chars=int(values[1]),
                          seconds=max(l['end'] for l in row['lines']) - min(l['start'] for l in row['lines'])))
    return stats


def distribution(rows, label, field):
    values = [r[field] for r in rows if r['label'] == label]
    if not values:
        return '—'
    if not np.isfinite(values).all() or min(values) < 0:
        raise ValueError('invalid shape statistics')
    return ' / '.join(f'{float(v):.2f}' for v in np.quantile(values, [0, .5, .9, 1]))


def print_table(synthetic, real=None, cards=False):
    fields = ('n_turns', 'seconds', 'family') if cards else ('n_turns', 'chars', 'seconds')
    print('각 셀: min / 중앙값 / p90 / max; 실제는 사람 라벨')
    for label in LABELS:
        print(f"n[{label}]={sum(r['label'] == label for r in synthetic)}" +
              (f" real_n={sum(r['label'] == label for r in real)}" if real is not None else ''))
    print('| 라벨 | 특징 | 카드' if cards else '| 라벨 | 특징 | 합성', end='')
    print(' | 실제 |' if real is not None else ' |')
    print('|---|---|---|' + ('---|' if real is not None else ''))
    for label in LABELS:
        for field in fields:
            print(f'| {label} | {field} | {distribution(synthetic, label, field)} |' +
                  (f' {distribution(real, label, field)} |' if real is not None else ''))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cards')
    parser.add_argument('--synth-dir', action='append')
    parser.add_argument('--real-judge')
    parser.add_argument('--real-asr')
    args = parser.parse_args(argv)
    if bool(args.cards) == bool(args.synth_dir) or bool(args.real_judge) != bool(args.real_asr) or (args.cards and args.real_judge):
        parser.error('choose --cards OR --synth-dir; real inputs require both options with --synth-dir')
    try:
        if args.cards:
            examples, values, stats = card_shapes(args.cards)
            skipped = 0
        else:
            examples, values, stats, skipped = synth_shapes(args.synth_dir)
        real = real_shapes(args.real_judge, args.real_asr) if args.real_judge else None
        result = cross_validate(examples, values)
        print_table(stats, real, cards=bool(args.cards))
        print(f"n={result['n']} skipped_dirs={skipped} splits={len(SPLIT_SEEDS)} "
              f"validation_observations={result['validation_observations']}")
        print('split_accuracy=' + ','.join(f'{x:.4f}' for x in result['fold_accuracy']))
        print(f"CV accuracy={result['cv_accuracy']:.4f} majority={result['majority']:.4f} "
              f"limit={result['limit']:.4f}: {'FAIL' if result['fail'] else 'PASS'}")
        return 3 if result['fail'] else 0
    except (OSError, ValueError, TypeError, KeyError, IndexError):
        # Never interpolate exception messages: JSON errors can contain input text.
        print('shortcut 검사 실패: 입력 완전성·라벨별 장면 수·숫자 형식을 확인하세요.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
