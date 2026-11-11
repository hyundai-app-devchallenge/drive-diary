"""Seeded, quota-balanced scene cards: python -m synth.cards."""
import argparse
from collections import Counter
import math
import random

from synth.io import write_jsonl
from synth.patterns import (BOUNDARY_PATTERNS, BY_ID, CHILD_PATTERNS, INTERRUPTIONS,
                            LABELS, QUIET_NONE, SITUATIONS, TOPICS)

# Use the same joint time/turn mixture for every label, including none.
LENGTH_WEIGHTS = {'short': .25, 'medium': .20, 'long': .55}
LENGTH_BOUNDS = {'short': (15, 30, 5, 8), 'medium': (31, 59, 9, 17),
                 'long': (60, 90, 18, 26)}


def quotas(n, weights):
    total = sum(weights.values())
    if n < 0 or total <= 0 or any(not math.isfinite(v) or v < 0 for v in weights.values()):
        raise ValueError('invalid weights/count')
    raw = {k: n * v / total for k, v in weights.items()}
    result = {k: math.floor(v) for k, v in raw.items()}
    for key in sorted(weights, key=lambda k: raw[k] - result[k], reverse=True)[:n-sum(result.values())]:
        result[key] += 1
    return result


def family(rng, size, pattern, children=True):
    adults = ['아빠', '엄마', '할아버지', '할머니', '이모', '삼촌']
    driver = rng.choice(adults)
    if pattern == 'grandparent_recall' and size == 2:
        driver = rng.choice(['할아버지', '할머니'])
    members = [dict(spk=driver, role='운전자', age=rng.choice(['청년', '중년'])
                    if driver not in ('할아버지', '할머니') else '노년')]
    if pattern == 'grandparent_recall' and driver not in ('할아버지', '할머니'):
        members.append(dict(spk=rng.choice(['할아버지', '할머니']), role='동승자', age='노년'))
    if pattern == 'adult_planning':
        members.append(dict(spk=rng.choice([a for a in adults if a != driver]), role='동승자', age='중년'))
    if children:
        members.append(dict(spk='아이1', role='동승자', age=rng.choice(
            ['유아 3~5세', '초등 저학년', '초등 고학년', '중고생'])))
    if pattern == 'sibling_squabble':
        members.append(dict(spk='아이2', role='동승자', age='초등 저학년'))
    size = max(size, len(members))
    while len(members) < size:
        used = {m['spk'] for m in members}
        options = [a for a in adults if a not in used]
        if children:
            options += [f'아이{i}' for i in range(2, 4) if f'아이{i}' not in used]
        spk = rng.choice(options)
        age = rng.choice(['유아 3~5세', '초등 저학년', '초등 고학년', '중고생']) if spk.startswith('아이') else (
            '노년' if spk in ('할아버지', '할머니') else '중년')
        members.append(dict(spk=spk, role='동승자', age=age))
    return members


def sample_cards(n=200, seed=43, ratios=None, boundary_rate=.15, max_repeat=2):
    if n < 1 or not 0 <= boundary_rate <= 1 or max_repeat < 1:
        raise ValueError('invalid sampling settings')
    rng = random.Random(seed)
    ratios = ratios or dict(story=.4, moment=.2, none=.4)
    if set(ratios) != set(LABELS):
        raise ValueError('ratios must contain story,moment,none')
    labels = [label for label, count in quotas(n, ratios).items() for _ in range(count)]
    rng.shuffle(labels)
    boundaries = set(rng.sample(range(n), round(n * boundary_rate)))
    # Quotas per label prevent accidental composition/energy/size skew.
    targets = {}
    for label in LABELS:
        indices = [i for i, value in enumerate(labels) if value == label]
        lengths = [key for key, count in quotas(len(indices), LENGTH_WEIGHTS).items() for _ in range(count)]
        children = [j >= round(len(indices) * .25) for j in range(len(indices))]
        sizes = [2 + j % 4 for j in range(len(indices))]
        energies = [['차분', '보통', '정신없음'][j % 3] for j in range(len(indices))]
        for values in (lengths, children, sizes, energies):
            rng.shuffle(values)
        for i, length, child, size, energy in zip(indices, lengths, children, sizes, energies):
            lo, hi, a, b = LENGTH_BOUNDS[length]
            targets[i] = dict(seconds=rng.randint(lo, hi), n_turns=rng.randint(a, b),
                              children=child, size=size, energy=energy, length=length)
    counts, pair_counts = Counter(), Counter()
    cards = []
    for i, label in enumerate(labels):
        boundary = i in boundaries
        target = targets[i]
        candidates = [p for p, item in BY_ID.items() if item['label'] == label
                      and (not boundary or p in BOUNDARY_PATTERNS[label])
                      and (target['children'] or p not in CHILD_PATTERNS)
                      and (p != 'adult_planning' or not target['children'] or target['size'] >= 3)
                      and (p != 'sibling_squabble' or target['size'] >= 3)
                      and (p != 'moment_in_errands' or target['length'] == 'long')
                      and (p not in QUIET_NONE or target['length'] != 'long')
                      and any(pair_counts[p, t] < max_repeat for t in TOPICS[item['group']])]
        if not candidates:
            raise ValueError('pattern×topic capacity exhausted; reduce N or raise --max-repeat')
        rng.shuffle(candidates)
        pattern = min(candidates, key=lambda p: counts[p] / (.25 if p in QUIET_NONE else 1))
        topics = [t for t in TOPICS[BY_ID[pattern]['group']] if pair_counts[pattern, t] < max_repeat]
        rng.shuffle(topics)
        topic = min(topics, key=lambda t: (pair_counts[pattern, t], sum(v for (p, x), v in pair_counts.items() if x == t)))
        counts[pattern] += 1
        pair_counts[pattern, topic] += 1
        cards.append(dict(card=i + 1, label=label, pattern=pattern, boundary=boundary,
                          family=family(rng, target['size'], pattern, target['children']), situation=SITUATIONS[i % len(SITUATIONS)],
                          topic=topic, interruptions=rng.sample(INTERRUPTIONS, rng.randrange(3)),
                          seconds=target['seconds'], n_turns=target['n_turns'], energy=target['energy']))
        if pattern == 'fragments':
            # Keep quiet fragments as a small exception, never the default none.
            cards[-1]['n_turns'] = rng.randint(1, 2)
    flow_rng = random.Random(f'{seed}:story-flow')
    stories = [c for c in cards if c['label'] == 'story']
    for card in flow_rng.sample(stories, round(len(stories) * .3)):
        card['flow_variant'] = flow_rng.choice(('첫 질문과 다른 화제로 샌다', '해결 없이 말하다 끊긴다'))
    return cards


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--n', type=int, default=200)
    parser.add_argument('--seed', type=int, default=43)
    parser.add_argument('--ratios', default='0.4,0.2,0.4', help='story,moment,none')
    parser.add_argument('--boundary-rate', type=float, default=.15)
    parser.add_argument('--max-repeat', type=int, default=2)
    parser.add_argument('--out', default='.local/synth/demo/cards.jsonl')
    args = parser.parse_args(argv)
    try:
        values = [float(v) for v in args.ratios.split(',')]
        if len(values) != 3:
            raise ValueError('three ratios required')
        cards = sample_cards(args.n, args.seed, dict(zip(LABELS, values)), args.boundary_rate, args.max_repeat)
        write_jsonl(args.out, cards)
    except (ValueError, OSError) as exc:
        parser.exit(2, f'카드 생성 실패: {exc}\n')
    print(f'카드 {len(cards)}장 저장')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
