"""Pure seeded ASR corruption and block export; see PROBABILITIES for units."""
import argparse
from collections import Counter
import json
from copy import deepcopy
import math
from pathlib import Path
import random
import re
from statistics import median

from common.schema import validate_block
from demo.pleos_profile import distribution
from synth.generate import validate_cards, validate_scenes
from synth.io import digest, output_lock, read_jsonl, write_json, write_jsonl
from synth.cards import quotas

# Each tuple is level 0/1/2. Child syllable/particle/word probabilities are x2.
PROBABILITIES = {
    'punctuation': (0, .9, 1),       # per punctuation character
    'substitute': (0, .008, .025),   # per Hangul syllable
    'syllable_drop': (0, .004, .015),
    'particle_drop': (0, .04, .12),  # per word ending with a listed particle
    'word_drop': (0, .02, .07),      # per word
    'name_variant': (0, .5, .85),    # per name occurrence, independently per line
    'hallucinate': (0, .003, .015),  # per retained line, repeats one word 8..20 times
    'overlap_drop': (0, .25, .55),  # per overlap-marked line
    'nav_mix': (0, .06, .15),       # per scene, one additional navigation line
    'merge': (1, 1, 1),             # deterministic reconstruction if gap < 1s
    'split': (1, 1, 1),             # deterministic reconstruction if span > 12s
}
NAME_VARIANTS = {'지우': ('지우', '지유', '지오'), '하린': ('하린', '하림', '아린'),
                 '도윤': ('도윤', '도연', '도운')}
PARTICLES = ('에게', '에서', '으로', '까지', '부터', '은', '는', '이', '가', '을', '를', '에', '도', '만')
SUBSTITUTIONS = {'가': '까', '나': '라', '다': '따', '바': '파', '사': '자', '어': '아', '오': '우', '고': '구'}


def corrupt_text(text, child, rng, level, enabled, probabilities, names):
    def hit(rule, multiplier=1):
        return enabled.get(rule, True) and rng.random() < min(1, probabilities[rule][level] * multiplier)
    for name, variants in names.items():
        text = re.sub(re.escape(name), lambda m: rng.choice(variants) if hit('name_variant') else m.group(), text)
    text = ''.join('' if hit('punctuation') and not ch.isalnum() and not ch.isspace() else ch
                   for ch in text)
    factor = 2 if child else 1
    words = []
    for word in text.split():
        if hit('word_drop', factor):
            continue
        if hit('particle_drop', factor):
            for particle in PARTICLES:
                if word.endswith(particle) and len(word) > len(particle):
                    word = word[:-len(particle)]
                    break
        syllables = []
        for ch in word:
            if '\uac00' <= ch <= '\ud7a3':
                if hit('syllable_drop', factor):
                    continue
                if hit('substitute', factor):
                    ch = SUBSTITUTIONS.get(ch, chr(0xac00 + (ord(ch) - 0xac00 + 1) % 11172))
            syllables.append(ch)
        if syllables:
            words.append(''.join(syllables))
    if not words:
        words = ['음']
    if hit('hallucinate'):
        words.extend([rng.choice(words)] * rng.randint(8, 20))
    return ' '.join(words)


def reconstruct(lines, merge=True, split=True):
    """Word time is proportional to character count; never invent speaker turns.

    The gap test uses the current merged segment's maximum end. An indivisible
    word estimated at >12s keeps its text and at most 12s; the remainder is silence.
    """
    merged = []
    for line in lines:
        row = dict(t=line['t'], dur=line['dur'], text=line['text'])
        if merge and merged and row['t'] - (merged[-1]['t'] + merged[-1]['dur']) < 1:
            last = merged[-1]
            end = max(last['t'] + last['dur'], row['t'] + row['dur'])
            last['text'] += ' ' + row['text']
            last['dur'] = end - last['t']
        else:
            merged.append(row)
    result = []
    for row in merged:
        if not split or row['dur'] <= 12:
            result.append(row)
            continue
        words = row['text'].split()
        weights = [len(w) for w in words]
        total = sum(weights)
        if any(row['dur'] * weight / total > 12 + 1e-9 for weight in weights):
            # With no audio, a 20s single-word timestamp is unresolvable. Retain
            # whole words and shorten estimated speech instead of inventing words
            # or stopping the entire export. Preserve subsequent word start times.
            offset = 0
            for word, weight in zip(words, weights):
                result.append(dict(t=row['t'] + row['dur'] * offset / total,
                                   dur=min(12, row['dur'] * weight / total), text=word))
                offset += weight
            continue
        offset, group, group_weight, group_start = 0, [], 0, row['t']
        for word, weight in zip(words, weights):
            if group and row['dur'] * (group_weight + weight) / total > 12 + 1e-9:
                result.append(dict(t=group_start, dur=row['dur'] * group_weight / total, text=' '.join(group)))
                group_start = row['t'] + row['dur'] * offset / total
                group, group_weight = [], 0
            group.append(word)
            group_weight += weight
            offset += weight
        if group:
            result.append(dict(t=group_start, dur=row['t'] + row['dur'] - group_start, text=' '.join(group)))
    return result


def whisper_degrade(lines, level=1, seed=43, enabled=None, probabilities=None, names=None):
    """Returns speaker-free timed turns, with no mutation or global randomness."""
    if level not in (0, 1, 2):
        raise ValueError('level must be 0,1,2')
    enabled = dict(enabled or {})
    if set(enabled) - set(PROBABILITIES):
        raise ValueError('unknown degradation rule')
    probabilities = {**PROBABILITIES, **(probabilities or {})}
    if set(probabilities) != set(PROBABILITIES) or any(
        len(p) != 3 or any(not math.isfinite(v) or not 0 <= v <= 1 for v in p) for p in probabilities.values()
    ):
        raise ValueError('invalid probability table')
    rng = random.Random(seed)
    names = NAME_VARIANTS if names is None else names
    rows = []
    for line in deepcopy(lines):
        if line['overlap'] and enabled.get('overlap_drop', True) and rng.random() < probabilities['overlap_drop'][level]:
            continue
        text = corrupt_text(line['text'], line['spk'].startswith('아이'), rng, level, enabled, probabilities, names)
        rows.append(dict(t=line['t'], dur=line['dur'], text=text))
    if not rows:
        # All-overlap scene: keep one line so every card still has a valid block.
        line = lines[0]
        rows = [dict(t=line['t'], dur=line['dur'], text=corrupt_text(
            line['text'], line['spk'].startswith('아이'), rng, level, enabled, probabilities, names))]
    if enabled.get('nav_mix', True) and rng.random() < probabilities['nav_mix'][level]:
        end = max(r['t'] + r['dur'] for r in rows)
        rows.append(dict(t=rng.uniform(rows[0]['t'], max(rows[0]['t'], end-1)), dur=min(1, end-rows[0]['t']),
                         text=rng.choice(['잠시 후 우회전입니다', '앞에 단속 구간입니다', '이어서 직진입니다'])))
    rows.sort(key=lambda r: r['t'])
    return reconstruct(rows, enabled.get('merge', True), enabled.get('split', True))


DEFAULT_PROFILE = {
    'line_count': 483, 'reference_line_count': 177, 'line_count_ratio': 483/177,
    'chars_per_line': dict(n=483, min=1, median=13, p90=35, max=85),
    # Snapshot strengths are heuristics, not independent acoustic probabilities.
    'short_reply_drop_rate': 2/3,
    'char_alignment': dict(substitution_rate=.178, deletion_rate=.328),
}


def load_profile(path='.local/demo/pleos/profile.json'):
    path = Path(path)
    if not path.exists():
        return deepcopy(DEFAULT_PROFILE)
    profile = json.loads(path.read_text(encoding='utf-8'))['summary']
    lengths = profile['chars_per_line']
    if not all(type(lengths[k]) in (int, float) and math.isfinite(lengths[k]) and lengths[k] > 0
               for k in ('min', 'median', 'p90', 'max')):
        raise ValueError('invalid profile lengths')
    if not lengths['min'] <= lengths['median'] <= lengths['p90'] <= lengths['max']:
        raise ValueError('invalid profile quantiles')
    for rate in (profile.get('short_reply_drop_rate'),
                 *[profile['char_alignment'].get(k) for k in ('substitution_rate', 'deletion_rate')]):
        if rate is not None and (type(rate) not in (float, int) or not math.isfinite(rate) or rate < 0):
            raise ValueError('invalid profile rate')
    if profile.get('short_reply_drop_rate') is not None and profile['short_reply_drop_rate'] > 1:
        raise ValueError('invalid short reply probability')
    histogram = profile.get('line_length_histogram', [])
    if any(type(r.get('chars')) is not int or r['chars'] < 1 or
           type(r.get('count')) is not int or r['count'] < 1 for r in histogram):
        raise ValueError('invalid profile histogram')
    return profile


def split_pleos(row, rng, profile):
    """Sample measured lengths; prefer nearby spaces, retain character timing."""
    text, offset = row['text'], 0
    lengths = profile['chars_per_line']
    histogram = profile.get('line_length_histogram')
    if not histogram:
        # Approximate a numeric histogram from the fallback quantiles; apply the
        # same finite-utterance correction as for a measured histogram.
        knots = [(0, lengths['min']), (.5, lengths['median']), (.9, lengths['p90']), (1, lengths['max'])]
        histogram = []
        for i in range(1, 101):
            q = i/100
            (a, lo), (b, hi) = next((a, b) for a, b in zip(knots, knots[1:]) if a[0] <= q <= b[0])
            histogram.append(dict(chars=max(1, round(lo+(hi-lo)*(q-a)/(b-a))), count=1))
    parts = []
    while offset < len(text):
        # Finite utterances leave a short residual segment. Length-biased
        # proposals compensate for that censoring instead of oversplitting.
        target = rng.choices([r['chars'] for r in histogram],
                             [r['count'] * r['chars'] for r in histogram])[0]
        end = min(len(text), offset + max(1, target))
        if end < len(text):
            spaces = [i for i in range(max(offset+1, end-3), min(len(text), end+4)) if text[i].isspace()]
            if spaces:
                end = min(spaces, key=lambda i: abs(i-end))
        piece = text[offset:end].strip()
        if piece:
            parts.append(dict(t=row['t'] + row['dur'] * offset/len(text),
                              dur=row['dur'] * (end-offset)/len(text), text=piece))
        offset = end
        while offset < len(text) and text[offset].isspace():
            offset += 1
    # Preserve the original <=12s block timing convention for long silences.
    return reconstruct(parts, merge=False, split=True)


def pleos_degrade(lines, level, seed, profile, family=(), stats=None):
    rng = random.Random(seed)
    stats = stats if stats is not None else {}
    for key in ('input_line_count', 'drop_count', 'short_reply_count', 'short_reply_dropped',
                'overlap_dropped', 'fallback_count'):
        stats.setdefault(key, 0)
    young = {m['spk'] for m in family if any(a in m['age'] for a in ('아기', '유아', '초등', 'toddler', 'elementary'))}
    factor = (0, 1, 1.6)[level]
    drop_rate = profile.get('short_reply_drop_rate')
    drop_rate = 2/3 if drop_rate is None else drop_rate
    alignment = profile.get('char_alignment', {})
    # Time alignment/reference errors are not independent acoustic error rates.
    # Bound them before using as corruption strength; children receive x3.
    sub_rate = alignment.get('substitution_rate')
    delete_rate = alignment.get('deletion_rate')
    sub_rate = min(.2, .08 if sub_rate is None else sub_rate) / 3
    delete_rate = min(.15, .04 if delete_rate is None else delete_rate) / 3
    rows = []
    dropped_reasons = []
    for line in lines:
        stats['input_line_count'] += 1
        if line['spk'] == '내비':
            rows.append(dict(t=line['t'], dur=line['dur'], text=line['text']))
            continue
        text = ' '.join(''.join(c for c in line['text'] if c.isalnum() or c.isspace()).split())
        short = 0 < len(text.replace(' ', '')) <= 2
        stats['short_reply_count'] += int(short)
        if short and rng.random() < min(1, drop_rate * factor):
            stats['drop_count'] += 1
            stats['short_reply_dropped'] += 1
            dropped_reasons.append('short')
            continue
        scramble = False
        if line['overlap'] and level:
            if rng.random() < (.35 if level == 1 else .6):
                stats['drop_count'] += 1
                stats['overlap_dropped'] += 1
                dropped_reasons.append('overlap')
                continue
            scramble = True
        child = line['spk'] in young
        words = []
        for word in text.split():
            multiplier = (3 if child else 1) * factor * (1.5 if scramble else 1)
            if rng.random() < delete_rate * multiplier:
                continue
            if rng.random() < sub_rate * multiplier:
                # A whole-word recognition substitution, from an invented inventory.
                word = rng.choice(('거기', '그러면', '이거', '갔어', '다시', '그런데'))
            elif child and rng.random() < sub_rate * factor:
                word = ''.join(SUBSTITUTIONS.get(c, c) for c in word)
            words.append(word)
        if not words:
            stats['drop_count'] += 1
            dropped_reasons.append('words')
            continue
        if scramble and words == text.split():
            words[0] = '거기' if words[0] != '거기' else '이거'
        rows.append(dict(t=line['t'], dur=line['dur'], text=' '.join(words)))
    if not rows:
        # Keep one original observation, never invent a replacement utterance.
        line = lines[0]
        text = ' '.join(''.join(c for c in line['text'] if c.isalnum() or c.isspace()).split())
        if not text:
            raise ValueError('scene contains no recognizable characters')
        rows = [dict(t=line['t'], dur=line['dur'], text=text)]
        stats['drop_count'] -= 1
        if dropped_reasons[0] == 'short':
            stats['short_reply_dropped'] -= 1
        elif dropped_reasons[0] == 'overlap':
            stats['overlap_dropped'] -= 1
        stats['fallback_count'] += 1
    # Speaker changes are unavailable to STT. Reconstruct short-gap speech runs
    # before resegmenting; navigation remains an exact, isolated observation.
    result, run = [], []
    nav = {(l['t'], l['text']) for l in lines if l['spk'] == '내비'}
    def flush():
        for row in reconstruct(run, merge=bool(level), split=False):
            result.extend(split_pleos(row, rng, profile))
        run.clear()
    for row in rows:
        if (row['t'], row['text']) in nav:
            flush()
            result.append(row)
        else:
            run.append(row)
    flush()
    return sorted(result, key=lambda r: r['t'])


def degrade(lines, level=1, seed=43, enabled=None, probabilities=None, names=None,
            style='pleos', profile=None, family=(), stats=None):
    """Seeded speaker-free output. Pleos never adds repeated hallucinations."""
    if level not in (0, 1, 2) or style not in ('pleos', 'whisper') or not lines:
        raise ValueError('invalid level/style/lines')
    if style == 'whisper':
        return whisper_degrade(lines, level, seed, enabled, probabilities, names)
    if enabled or probabilities or names:
        raise ValueError('rule overrides are for whisper style')
    return pleos_degrade(lines, level, seed, profile or DEFAULT_PROFILE, family, stats)


def output_stats(blocks, stats):
    return dict(stats, line_count=sum(len(b['turns']) for b in blocks),
                chars_per_line=distribution([len(t) for b in blocks for t in b['turns']]))


def to_block(number, turns, level):
    start = turns[0]['t']
    end = max(t['t'] + t['dur'] for t in turns)
    span = end - start
    gaps = [b['t'] - (a['t'] + a['dur']) for a, b in zip(turns, turns[1:])]
    source = f'synth:{number}'
    row = dict(id=source + '@0', source=source, split='test', block=0, start=start, end=end,
               gap_before=None, utts=list(range(len(turns))), turns=[t['text'] for t in turns], prefix=[],
               features=dict(n_turns=len(turns), seconds=span,
                             speech_share=sum(t['dur'] for t in turns) / span if span else 0,
                             median_gap=median(gaps) if gaps else 0, gap_before=600),
               timing=[dict(start=t['t'], end=t['t'] + t['dur']) for t in turns], level=level, demo_only=True)
    validate_block(row)
    return row


def level_assignment(cards, level='mixed', seed=43, level_mix=None):
    if level_mix is None:
        if str(level) not in ('0', '1', '2', 'mixed'):
            raise ValueError('invalid level')
        return {c['card']: (c['card'] - 1) % 3 if level == 'mixed' else int(level) for c in cards}
    if str(level) != 'mixed' or len(level_mix) != 3:
        raise ValueError('level-mix requires mixed level and three weights')
    weights = dict(enumerate(level_mix))
    result = {}
    for label in sorted({c['label'] for c in cards}):
        selected = sorted((c for c in cards if c['label'] == label), key=lambda c: c['card'])
        levels = [key for key, count in quotas(len(selected), weights).items() for _ in range(count)]
        random.Random(f'{seed}:levels:{label}').shuffle(levels)
        result.update({c['card']: value for c, value in zip(selected, levels)})
    return result


def export(cards, scenes, out_dir, level=1, seed=43, enabled=None, style='pleos', profile=None,
           level_mix=None, duplicate_threshold=.85):
    validate_cards(cards)
    by_id = {c['card']: c for c in cards}
    if not scenes or len({s['card'] for s in scenes}) != len(scenes) or any(s['card'] not in by_id for s in scenes):
        raise ValueError('invalid scene card identities')
    selected = [by_id[s['card']] for s in scenes]
    validate_scenes(scenes, selected, threshold=duplicate_threshold)
    levels = level_assignment(cards, level, seed, level_mix)
    profile = (profile if profile is not None else load_profile()) if style == 'pleos' else None
    stats = {}
    blocks, clean, labels = [], [], []
    for card, scene in zip(selected, scenes):
        actual_level = levels[card['card']]
        turns = degrade(scene['lines'], actual_level, seed + card['card'], enabled,
                        style=style, profile=profile, family=card['family'], stats=stats)
        block = to_block(card['card'], turns, actual_level)
        blocks.append(block)
        clean.append(dict(id=block['id'], card=card, lines=scene['lines'], why=scene['why']))
        labels.append(dict(id=block['id'], verdict=card['label'], pattern=card['pattern'],
                           boundary=card['boundary'], level=actual_level,
                           negative=card.get('negative', False), pair_id=card.get('pair_id')))
    directory = Path(out_dir)
    with output_lock(directory / 'blocks.jsonl'):
        write_json(directory / 'meta.json', dict(demo_only=True, complete=False))
        write_jsonl(directory / 'clean.jsonl', clean)
        write_jsonl(directory / 'labels.jsonl', labels)
        write_jsonl(directory / 'blocks.jsonl', blocks)
        write_json(directory / 'meta.json', dict(demo_only=True, complete=True, count=len(blocks),
                   level=level, level_mix=level_mix, duplicate_threshold=duplicate_threshold,
                   seed=seed, disabled=[k for k, v in (enabled or {}).items() if not v],
                   timing_policy=('profile_character_segments; short_gap_runs; speech_capped_at_12s; navigation_unchanged'
                                  if style == 'pleos' else
                                  'character_weighted_words; indivisible_word_speech_capped_at_12s'),
                   style=style, profile=profile if style == 'pleos' else None,
                   stats=output_stats(blocks, stats),
                   label_counts=dict(Counter(c['label'] for c in selected)),
                   negative_count=sum(c.get('negative', False) for c in selected),
                   pair_count=len({c['pair_id'] for c in selected if c.get('pair_id')}),
                   level_counts=dict(Counter(b['level'] for b in blocks)),
                   probabilities=PROBABILITIES if style == 'whisper' else None,
                   cards_hash=digest(cards), scenes_hash=digest(scenes)))
    return blocks


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cards', default='.local/synth/demo/cards.jsonl')
    p.add_argument('--scenes', default='.local/synth/demo/scenes.jsonl')
    p.add_argument('--out-dir', default='.local/synth/demo')
    p.add_argument('--level', choices=['0', '1', '2', 'mixed'], default='mixed')
    p.add_argument('--level-mix', help='level 0,1,2 weights, e.g. 0.2,0.5,0.3; requires --level mixed')
    p.add_argument('--duplicate-threshold', type=float, default=.85)
    p.add_argument('--seed', type=int, default=43)
    p.add_argument('--style', choices=['whisper', 'pleos'], default='pleos')
    p.add_argument('--profile', default='.local/demo/pleos/profile.json')
    p.add_argument('--disable', action='append', choices=list(PROBABILITIES), default=[])
    args = p.parse_args(argv)
    try:
        scene_path = Path(args.scenes)
        meta = scene_path.with_suffix('.meta.json')
        if meta.exists():
            if json.loads(meta.read_text(encoding='utf-8')).get('backend') == 'fake' and 'demo' in [x.casefold() for x in Path(args.out_dir).resolve().parts]:
                p.error('가짜 장면은 demo 경로로 내보낼 수 없습니다.')
        rows = export(read_jsonl(args.cards), read_jsonl(args.scenes), args.out_dir,
                      args.level, args.seed, {k: False for k in args.disable}, args.style,
                      load_profile(args.profile) if args.style == 'pleos' else None,
                      [float(v) for v in args.level_mix.split(',')] if args.level_mix else None,
                      args.duplicate_threshold)
    except (OSError, ValueError, TypeError, KeyError):
        p.exit(2, '전사 변환 실패: 카드·장면·출력 경로를 확인하세요.\n')
    print(f'합성 묶음 {len(rows)}개 저장 (demo_only)')
    print(json.dumps(json.loads((Path(args.out_dir) / 'meta.json').read_text(encoding='utf-8'))['stats']))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
