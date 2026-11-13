"""Shape gate and synthetic-only content diagnostics. Never print real speech."""
import argparse
import json
from pathlib import Path
import sys

import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.feature_extraction.text import TfidfVectorizer
from common.normalize import normalize
from collections import Counter

from demo.trial.data import Example, LABELS, input_text, load_synth, read_rows, split_scenes
from demo.trial.models import shape
from synth.generate import validate_cards
from synth.io import write_json


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
    examples = [Example(str(c['card']), c['card'], '', LABELS.index(c['label']), c.get('pair_id')) for c in cards]
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


def topic_report(cards, out=None):
    """Counts only: never emit even invented topic text in the distribution."""
    print('| topic_group | none | story | moment |')
    print('|---|---:|---:|---:|')
    for group in sorted({c.get('topic_group', 'legacy') for c in cards}):
        counts = Counter(c['label'] for c in cards if c.get('topic_group', 'legacy') == group)
        print(f'| {group} | ' + ' | '.join(str(counts[k]) for k in LABELS) + ' |')
    topics = {k: {c['topic'] for c in cards if c['label'] == k} for k in LABELS}
    print('shared_keep_none_topics=' + str(len(topics['none'] & (topics['story'] | topics['moment']))))
    print(f"negative={sum(c.get('negative', False) for c in cards)} "
          f"paired_cards={sum(bool(c.get('pair_id')) for c in cards)} "
          f"pairs={len({c['pair_id'] for c in cards if c.get('pair_id')})}")
    if out:
        counts = Counter((c['topic'], c['label']) for c in cards)
        write_json(out, dict(synthetic_only=True, labels=list(LABELS), topics=[
            dict(topic=topic, counts={label: counts[topic, label] for label in LABELS})
            for topic in sorted(set.union(*topics.values()))]))


def ngram_audit(examples, top=12, min_support=5, exclusive_rate=.02):
    """Synthetic text only; same five group splits as the shape gate.

    Coefficients are one-vs-rest (3 labels). Exclusive warnings use unique
    scene support, avoiding inflated counts from repeated degradation levels.
    """
    if top < 0 or min_support < 1 or not 0 <= exclusive_rate <= 1:
        raise ValueError('invalid ngram settings')
    texts = {r.id: normalize(r.text) for r in examples}
    scores, counts = [], []
    def vectorizer():
        return TfidfVectorizer(analyzer='char', ngram_range=(2, 5), min_df=1, max_features=50000)
    for seed in SPLIT_SEEDS:
        train, val = split_scenes(examples, seed)
        model = make_pipeline(vectorizer(), LogisticRegression(max_iter=2000, random_state=seed))
        model.fit([texts[r.id] for r in train], [r.label != 0 for r in train])
        scores.append(float(np.mean(model.predict([texts[r.id] for r in val]) == [r.label != 0 for r in val])))
        counts.append(len(val))
    x = vectorizer()
    matrix = x.fit_transform([texts[r.id] for r in examples])
    features = x.get_feature_names_out()
    # Support is counted by original scene, never by turn/level/export count.
    scene_sets = [{r.scene for r in examples if r.label == k} for k in range(3)]
    tops = []
    sparse = matrix.tocsc()
    for label in range(3):
        model = LogisticRegression(max_iter=2000, random_state=43)
        model.fit(matrix, [r.label == label for r in examples])
        rows = []
        for j in np.argsort(model.coef_[0])[::-1][:top]:
            indices = sparse.indices[sparse.indptr[j]:sparse.indptr[j+1]]
            support = [{examples[i].scene for i in indices if examples[i].label == k} for k in range(3)]
            own = len(support[label])
            exclusive = own >= max(min_support, int(np.ceil(exclusive_rate * len(scene_sets[label])))) and all(
                not values for k, values in enumerate(support) if k != label)
            rows.append(dict(ngram=str(features[j]), coefficient=float(model.coef_[0][j]),
                             support=[len(s) for s in support], warning=exclusive))
        tops.append(dict(label=LABELS[label], rows=rows))
    return dict(cv_accuracy=float(np.average(scores, weights=counts)), fold_accuracy=scores,
                validation_observations=sum(counts), tops=tops)


def print_ngram(result):
    print(f"ngram CV accuracy={result['cv_accuracy']:.4f} (참고; FAIL 기준 아님)")
    print('ngram split_accuracy=' + ','.join(f'{x:.4f}' for x in result['fold_accuracy']))
    print('| label | n-gram | coefficient | support(none/story/moment) | warning |')
    print('|---|---|---:|---|---|')
    for group in result['tops']:
        for row in group['rows']:
            print(f"| {group['label']} | {json.dumps(row['ngram'], ensure_ascii=False)} | "
                  f"{row['coefficient']:.6f} | {row['support']} | "
                  f"{'WARN exclusive' if row['warning'] else ''} |")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cards')
    parser.add_argument('--synth-dir', action='append')
    parser.add_argument('--real-judge')
    parser.add_argument('--real-asr')
    parser.add_argument('--top-ngrams', type=int, default=12)
    parser.add_argument('--exclusive-min-support', type=int, default=5)
    parser.add_argument('--exclusive-rate', type=float, default=.02)
    parser.add_argument('--topic-report', help='optional per-topic label counts JSON; --cards only')
    args = parser.parse_args(argv)
    if bool(args.cards) == bool(args.synth_dir) or bool(args.real_judge) != bool(args.real_asr) or (args.cards and args.real_judge):
        parser.error('choose --cards OR --synth-dir; real inputs require both options with --synth-dir')
    if args.topic_report and not args.cards:
        parser.error('--topic-report requires --cards')
    try:
        if args.cards:
            examples, values, stats = card_shapes(args.cards)
            skipped = 0
        else:
            examples, values, stats, skipped = synth_shapes(args.synth_dir)
        real = real_shapes(args.real_judge, args.real_asr) if args.real_judge else None
        result = cross_validate(examples, values)
        print_table(stats, real, cards=bool(args.cards))
        if args.cards:
            topic_report(read_rows(args.cards), args.topic_report)
        print(f"n={result['n']} skipped_dirs={skipped} splits={len(SPLIT_SEEDS)} "
              f"validation_observations={result['validation_observations']}")
        print('split_accuracy=' + ','.join(f'{x:.4f}' for x in result['fold_accuracy']))
        print(f"CV accuracy={result['cv_accuracy']:.4f} majority={result['majority']:.4f} "
              f"limit={result['limit']:.4f}: {'FAIL' if result['fail'] else 'PASS'}")
        if not args.cards:
            print_ngram(ngram_audit(examples, args.top_ngrams, args.exclusive_min_support, args.exclusive_rate))
        return 3 if result['fail'] else 0
    except (OSError, ValueError, TypeError, KeyError, IndexError):
        # Never interpolate exception messages: JSON errors can contain input text.
        print('shortcut 검사 실패: 입력 완전성·라벨별 장면 수·숫자 형식을 확인하세요.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
