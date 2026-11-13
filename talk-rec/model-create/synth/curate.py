"""Synthetic-only deduplication and pair-aware stratified skim exports."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import random
import re
from copy import deepcopy

from synth.generate import SceneIndex
from synth.io import read_jsonl, write_json, write_jsonl, output_lock


SOUND_TOKEN = re.compile(r'^(?:푸|으)?(?:하|허|크|히|헤|깔|낄|키득|앙|ㅋ|ㅎ)+$')
SOUND_PUNCTUATION = '!~.,?…()[]{}'


def strip_sound_text(text):
    """Remove whole whitespace-delimited sound tokens, never word substrings."""
    def sound(token):
        core = token.strip(SOUND_PUNCTUATION)
        return len(core) >= 2 and SOUND_TOKEN.fullmatch(core) is not None
    return ' '.join(token for token in text.split() if not sound(token))


def strip_sounds(scenes):
    result = deepcopy(scenes)
    for scene in result:
        kept = []
        for line in scene['lines']:
            line['text'] = strip_sound_text(line['text'])
            if line['text']:
                kept.append(line)
        scene['lines'] = kept
    return result


def deduplicate(scenes, threshold=.85):
    import math
    if not math.isfinite(threshold) or not 0 < threshold <= 1:
        raise ValueError('invalid duplicate threshold')
    index, kept, dropped = SceneIndex(), [], []
    for scene in scenes:
        if index.duplicate(scene, threshold):
            dropped.append(scene['card'])
        else:
            kept.append(scene)
            index.add(scene)
    return kept, dropped


def sample_ids(labels, limit=60, seed=43):
    """Soft scene cap: keep every pair and every level of a selected scene.

    Balance label, then pattern/negative/paired strata. Every choice consumes
    its full union group, including partial pairs that exist in the input.
    """
    if limit < 1 or not labels or len({r['id'] for r in labels}) != len(labels):
        raise ValueError('invalid sample settings/identities')
    parent, pairs = {}, {}
    def scene(row):
        return row['id'].split('@')[0]
    def root(key):
        parent.setdefault(key, key)
        if parent[key] != key:
            parent[key] = root(parent[key])
        return parent[key]
    for row in labels:
        key = scene(row)
        root(key)
        if row.get('pair_id'):
            other = pairs.setdefault(row['pair_id'], key)
            parent[root(key)] = root(other)
    units = defaultdict(list)
    for row in labels:
        units[root(scene(row))].append(row)
    units = list(units.values())
    random.Random(seed).shuffle(units)
    counts, strata = Counter(), Counter()
    selected = []
    def stratum(r):
        return r['verdict'], r['pattern'], bool(r.get('negative')), bool(r.get('pair_id'))
    while units and len(selected) < limit:
        unit = min(units, key=lambda u: (
            sum(counts[r['verdict']] for r in u) / len(u),
            sum(strata[stratum(r)] for r in u) / len(u)))
        units.remove(unit)
        selected.extend(r['id'] for r in unit)
        counts.update(r['verdict'] for r in unit)
        strata.update(stratum(r) for r in unit)
    return selected


def export_sample(directory, out_dir, limit=60, seed=43):
    directory, out_dir = Path(directory), Path(out_dir)
    if directory.resolve() == out_dir.resolve():
        raise ValueError('sample output must differ from input')
    meta = json.loads((directory / 'meta.json').read_text(encoding='utf-8'))
    if not meta.get('demo_only') or not meta.get('complete'):
        raise ValueError('complete synthetic export required')
    rows = {k: read_jsonl(directory / f'{k}.jsonl') for k in ('blocks', 'clean', 'labels')}
    identities = set(r['id'] for r in rows['labels'])
    if any(len({r['id'] for r in values}) != len(values) or {r['id'] for r in values} != identities
           for values in rows.values()):
        raise ValueError('sample input identities mismatch')
    ids = sample_ids(rows['labels'], limit, seed)
    with output_lock(out_dir / 'blocks.jsonl'):
        write_json(out_dir / 'meta.json', dict(demo_only=True, complete=False))
        for key, values in rows.items():
            by_id = {r['id']: r for r in values}
            write_jsonl(out_dir / f'{key}.jsonl', [by_id[i] for i in ids])
        write_json(out_dir / 'meta.json', dict(demo_only=True, complete=True, count=len(ids),
                   review_only=True, sample_seed=seed, requested_limit=limit,
                   strata=['verdict', 'pattern', 'negative', 'paired'], whole_pair_units=True))
    return len(ids)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    sounds = commands.add_parser('strip-sounds')
    sounds.add_argument('--scenes', required=True)
    sounds.add_argument('--out', required=True)
    dedup = commands.add_parser('dedup')
    dedup.add_argument('--scenes', required=True)
    dedup.add_argument('--out', required=True)
    dedup.add_argument('--threshold', type=float, default=.85)
    sample = commands.add_parser('sample')
    sample.add_argument('--synth-dir', required=True)
    sample.add_argument('--out-dir', required=True)
    sample.add_argument('--limit', type=int, default=60)
    sample.add_argument('--seed', type=int, default=43)
    args = parser.parse_args(argv)
    try:
        if args.command == 'strip-sounds':
            if Path(args.scenes).resolve() == Path(args.out).resolve():
                raise ValueError('sound output must differ from input')
            rows = strip_sounds(read_jsonl(args.scenes))
            with output_lock(args.out):
                write_jsonl(args.out, rows)
            print(f'소리 토큰 제거: {len(rows)}장 저장')
        elif args.command == 'dedup':
            if Path(args.scenes).resolve() == Path(args.out).resolve():
                raise ValueError('dedup output must differ from input')
            kept, dropped = deduplicate(read_jsonl(args.scenes), args.threshold)
            with output_lock(args.out):
                write_jsonl(args.out, kept)
                write_json(Path(args.out).with_suffix('.dedup.json'), dict(
                    kept=len(kept), dropped=len(dropped), dropped_cards=dropped, threshold=args.threshold))
            print(f'중복 제거: 유지 {len(kept)}, 제외 {len(dropped)}')
        else:
            count = export_sample(args.synth_dir, args.out_dir, args.limit, args.seed)
            print(f'훑어보기 표본 {count}개 저장 (쌍 전체 포함; review_only)')
        return 0
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        parser.exit(2, '합성 정리 실패: 입력·출력 경로·옵션을 확인하세요.\n')


if __name__ == '__main__':
    raise SystemExit(main())
