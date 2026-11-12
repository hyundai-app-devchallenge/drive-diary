"""Numeric-only Pleos/reference comparison. The reference is NOT ground truth.

Character edits exclude whitespace/punctuation. Time-connected components align
segments without counting a reference line once for every chopped Pleos line.
No transcript text or source identifiers are serialized, including on errors.
"""
import argparse
from collections import Counter
from difflib import SequenceMatcher
import json
import re

from demo.asr.data import validate_result
from synth.io import read_jsonl, write_json


def normalized(text):
    return ''.join(c for c in text if c.isalnum())


def distribution(values):
    values = sorted(values)
    if not values:
        return dict(n=0, min=None, median=None, p90=None, max=None)
    def q(p):
        x = (len(values) - 1) * p
        i = int(x)
        return values[i] + (values[min(i+1, len(values)-1)] - values[i]) * (x-i)
    return dict(n=len(values), min=values[0], median=q(.5), p90=q(.9), max=values[-1])


def overlaps(a, b):
    return min(a['end'], b['end']) > max(a['start'], b['start'])


def repeated_segments(lines):
    """Consecutive identical >=4-char lines or >=3 repeats of a word/phrase.

    Counts affected segments, not ordinary two-word self-corrections.
    """
    previous, count = '', 0
    for line in lines:
        text = normalized(line['text'])
        phrase_run = bool(re.search(r'(.{2,30}?)\1{2,}', text))
        count += int(phrase_run or (len(text) >= 4 and text == previous))
        previous = text
    return count


def edits(reference, hypothesis):
    counts = dict(reference_chars=len(reference), pleos_chars=len(hypothesis),
                  substitution=0, deletion=0, insertion=0)
    for tag, a, b, c, d in SequenceMatcher(None, reference, hypothesis, autojunk=False).get_opcodes():
        if tag == 'replace':
            counts['substitution'] += min(b-a, d-c)
            counts['deletion'] += max(0, b-a-(d-c))
            counts['insertion'] += max(0, d-c-(b-a))
        elif tag == 'delete':
            counts['deletion'] += b-a
        elif tag == 'insert':
            counts['insertion'] += d-c
    return counts


def rates(counts):
    denominator = counts['reference_chars']
    return dict(counts, **{k + '_rate': counts[k] / denominator if denominator else None
                          for k in ('substitution', 'deletion', 'insertion')},
                mismatch_rate=sum(counts[k] for k in ('substitution', 'deletion', 'insertion')) / denominator
                if denominator else None)


def aligned_segments(pleos, reference):
    events = sorted([(l['start'], side, i, l) for side, lines in enumerate((reference, pleos))
                     for i, l in enumerate(lines)], key=lambda e: (e[0], e[1], e[2]))
    groups, current, end = [], [], -1
    for event in events:
        if current and event[0] >= end:
            groups.append(current)
            current = []
        current.append(event)
        end = max(end, event[3]['end']) if len(current) > 1 else event[3]['end']
    if current:
        groups.append(current)
    result = []
    for group in groups:
        texts = [''.join(normalized(e[3]['text']) for e in group if e[1] == side) for side in (0, 1)]
        result.append(dict(start=min(e[0] for e in group), end=max(e[3]['end'] for e in group),
                           **rates(edits(*texts))))
    return result


def summarize(pairs):
    plines = [line for p, _ in pairs for line in p['lines']]
    rlines = [line for _, r in pairs for line in r['lines']]
    lengths = [len(l['text']) for l in plines]
    pauses = [b['start'] - a['end'] for p, _ in pairs
              for a, b in zip(sorted(p['lines'], key=lambda l: l['start']),
                              sorted(p['lines'], key=lambda l: l['start'])[1:])]
    segments = [s for p, r in pairs for s in aligned_segments(p['lines'], r['lines'])]
    counts = {k: sum(s[k] for s in segments)
              for k in ('reference_chars', 'pleos_chars', 'substitution', 'deletion', 'insertion')}
    short = dropped = 0
    for p, r in pairs:
        for line in r['lines']:
            compact = re.sub(r'\s+', '', line['text'])
            target = normalized(line['text'])
            if 0 < len(compact) <= 2 and target:
                short += 1
                # Overlap alone is not correspondence: the reply must occur there.
                candidates = ''.join(normalized(l['text']) for l in p['lines'] if overlaps(l, line))
                dropped += int(target not in candidates)
    return dict(clips=len(pairs), line_count=len(plines), reference_line_count=len(rlines),
                line_count_ratio=len(plines)/len(rlines) if rlines else None,
                chars_per_line=distribution(lengths),
                line_length_histogram=[dict(chars=k, count=v) for k, v in sorted(Counter(lengths).items())],
                pause_seconds=distribution(pauses), short_reply_count=short, short_reply_dropped=dropped,
                short_reply_drop_rate=dropped/short if short else None,
                char_alignment=rates(counts),
                total_char_ratio=counts['pleos_chars']/counts['reference_chars'] if counts['reference_chars'] else None,
                repeated_segments=sum(repeated_segments(p['lines']) for p, _ in pairs),
                reference_repeated_segments=sum(repeated_segments(r['lines']) for _, r in pairs))


def build_profile(pleos, reference):
    def index(rows):
        result = {}
        for row in rows:
            validate_result(row)
            if row['id'] in result:
                raise ValueError('duplicate clip')
            result[row['id']] = row
        return result
    p, r = index(pleos), index(reference)
    ids = sorted(p.keys() & r.keys())
    if not ids:
        raise ValueError('no paired clips')
    pairs = [(p[k], r[k]) for k in ids]
    return dict(version=1, pleos_unpaired=len(p.keys()-r.keys()), reference_unpaired=len(r.keys()-p.keys()),
                summary=summarize(pairs),
                clips=[dict(clip_index=i+1, **summarize([pair]),
                            segments=aligned_segments(pair[0]['lines'], pair[1]['lines']))
                       for i, pair in enumerate(pairs)])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pleos', default='.local/demo/asr/pleos.jsonl')
    parser.add_argument('--ref', default='.local/demo/asr/large-v3-long-prompt.jsonl')
    parser.add_argument('--out', default='.local/demo/pleos/profile.json')
    args = parser.parse_args(argv)
    try:
        profile = build_profile(read_jsonl(args.pleos), read_jsonl(args.ref))
        write_json(args.out, profile)
    except (OSError, ValueError, TypeError, KeyError):
        parser.exit(2, '프로필 생성 실패: 입력 형식과 경로를 확인하세요.\n')
    print(json.dumps(profile['summary'], ensure_ascii=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
