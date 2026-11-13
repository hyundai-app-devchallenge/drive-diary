"""T61: text-free block-level value evaluation against the T60 run snapshot."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

from .core import parse_policy
from .measure import ROOT
from tools.summary_relay import MAX_SEGMENTS, validate_story_value

CASTS = ('child_family', 'adult_family', 'couple', 'friends', 'unknown')


class ValueInputError(ValueError):
    """Only fixed, public diagnostics may reach the CLI."""


def story_value(story):
    kind = story.get('kind')
    if kind not in ('story', 'moment', 'none'):
        raise ValueInputError('invalid story kind')
    if 'keep' not in story and 'reason' not in story:
        # T60 never made a value decision, even for kind=none.
        return True, '', True
    try:
        value = validate_story_value(story['keep'], story['reason'], kind)
    except (KeyError, ValueError):
        raise ValueInputError('invalid or missing story keep/reason') from None
    return value['keep'], value['reason'], False


def summarize(blocks):
    """All denominators include judge-dropped blocks; the table only uses saved ones."""
    saved = [b for b in blocks if b['judge']]
    table, reasons = {}, {}
    for label, key in (('keep', 'human_keep'), ('none', 'human_none'), (None, 'unlabeled')):
        subset = [b for b in saved if b['label'] == label]
        table[key] = dict(kept=sum(b['keep'] for b in subset), hidden=sum(not b['keep'] for b in subset))
        # Block-weighted, including the empty reason for kept/legacy stories.
        reasons[key] = dict(sorted(Counter(b['reason'] for b in subset).items()))
    keep_total = sum(b['label'] == 'keep' for b in blocks)
    def pipeline(selected):
        keep_saved = sum(b['label'] == 'keep' for b in selected)
        return dict(saved_blocks=len(selected), human_keep_saved=keep_saved,
                    keep_recall=keep_saved / keep_total if keep_total else None,
                    false_saves=sum(b['label'] == 'none' for b in selected))
    judge = pipeline(saved)
    combined = pipeline([b for b in saved if b['keep']])
    return dict(blocks=len(blocks), human_keep=keep_total,
                human_none=sum(b['label'] == 'none' for b in blocks),
                unlabeled=sum(b['label'] is None for b in blocks),
                judge_saved=table, reason_by_human=reasons,
                judge_alone=judge, judge_and_llm=combined,
                change=dict(human_keep_hidden=table['human_keep']['hidden'],
                            false_saves_removed=judge['false_saves'] - combined['false_saves'],
                            keep_recall_delta=(combined['keep_recall'] - judge['keep_recall']
                                               if keep_total else None)))


def evaluate(records, rows, policy):
    """Join by run/window/segment/id; require exactly the judge's saved partition.

    Only ids, decisions and minimal labels from runs.jsonl are used. Titles,
    keywords and any unexpected text fields never enter the aggregate report.
    """
    try:
        parse_policy(policy)
        blocks, expected, seen_runs = {}, {}, set()
        cast_missing = 0
        for record in records:
            run = record['run']
            if type(run) is not int or run < 0 or run in seen_runs or not record['blocks']:
                raise ValueInputError('invalid run snapshot')
            seen_runs.add(run)
            saved = []
            for block in record['blocks']:
                identity, judge = block['id'], block['decisions'][policy]
                human = block['human']
                label = human['keep'] if human is not None else None
                cast = block.get('cast', 'unknown')
                if (not isinstance(identity, str) or not identity or identity in blocks
                        or type(judge) is not bool or label not in ('keep', 'none', None)
                        or (human is not None and label is None) or cast not in CASTS):
                    raise ValueInputError('invalid run block/label/cast/decision')
                cast_missing += 'cast' not in block
                blocks[identity] = dict(label=label, cast=cast, judge=judge)
                if judge:
                    saved.append(identity)
            chunks = [saved[start:start + MAX_SEGMENTS] for start in range(0, len(saved), MAX_SEGMENTS)]
            for index, ids in enumerate(chunks):
                expected[run, index + 1] = dict(ids=ids, window_count=len(chunks),
                                                segment_offset=index * MAX_SEGMENTS)
        seen, story_counts, reason_counts = set(), Counter(), Counter()
        legacy = 0
        for row in rows:
            if row['policy'] != policy:
                raise ValueInputError('mixed split policies')
            if row['status'] != 'ok':
                raise ValueInputError('failed split windows; rerun split before value')
            if type(row['run']) is not int or type(row['window']) is not int:
                raise ValueInputError('invalid split window')
            key = row['run'], row['window']
            if key not in expected or key in seen:
                raise ValueInputError('duplicate or unexpected split window')
            seen.add(key)
            window = expected[key]
            if (row['ids'] != window['ids']
                    or any(type(row[k]) is not int or row[k] != window[k]
                           for k in ('window_count', 'segment_offset'))):
                raise ValueInputError('split/run snapshot mismatch; rerun split')
            if not isinstance(row['stories'], list) or not row['stories']:
                raise ValueInputError('invalid story partition')
            covered = []
            for story in row['stories']:
                segments = story['segments']
                if (not isinstance(segments, list) or not segments
                        or any(type(n) is not int or not 1 <= n <= len(window['ids']) for n in segments)):
                    raise ValueInputError('invalid story segments')
                ids = [window['ids'][n - 1] for n in segments]
                if story['ids'] != ids:
                    raise ValueInputError('story id/segment mismatch')
                keep, reason, old = story_value(story)
                legacy += old
                story_counts['keep' if keep else 'hide'] += 1
                if not keep:
                    reason_counts[reason] += 1
                covered.extend(segments)
                for identity in ids:
                    blocks[identity].update(keep=keep, reason=reason)
            if covered != list(range(1, len(window['ids']) + 1)):
                raise ValueInputError('invalid story coverage/order')
        if seen != set(expected):
            raise ValueInputError('missing split windows; rerun split before value')
        values = list(blocks.values())
        return dict(schema_version=1, policy=policy, policy_flags_ignored=True,
                    coverage=dict(runs=len(seen_runs), saved_runs=len({key[0] for key in expected}),
                                  windows=len(seen), cast_missing_blocks=cast_missing,
                                  legacy_stories_assumed_keep=legacy),
                    stories=dict(keep=story_counts['keep'], hide=story_counts['hide'],
                                 reasons=dict(sorted(reason_counts.items()))),
                    overall=summarize(values),
                    by_cast={cast: summarize([b for b in values if b['cast'] == cast])
                             for cast in CASTS if any(b['cast'] == cast for b in values)}
                    if cast_missing < len(values) else {})
    except ValueInputError:
        raise
    except (KeyError, TypeError, ValueError, IndexError, AttributeError, RecursionError):
        raise ValueInputError('invalid split/run snapshot format') from None


def read_rows(path):
    try:
        return [json.loads(line) for line in Path(path).read_text(encoding='utf-8').splitlines() if line.strip()]
    except (ValueError, UnicodeError, RecursionError):
        raise ValueInputError('invalid UTF-8 JSONL') from None


def execute(split_jsonl, *, root=ROOT):
    from tools.listen.storage import atomic_write
    path = Path(split_jsonl)
    name = path.stem.removeprefix('split-')
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,199}', name):
        raise ValueInputError('invalid split filename')
    rows = read_rows(path)
    # An empty split is valid only when the snapshot also has no saved blocks.
    match = re.fullmatch(r'(cont[0-9.]+_min[0-9]+)-.+', name)
    policy = rows[0].get('policy') if rows and isinstance(rows[0], dict) else match[1] if match else None
    directory = Path(root) / '.local/eval/stitch'
    report = evaluate(read_rows(directory / 'runs.jsonl'), rows, policy)
    atomic_write(directory / f'value-{name}.json',
                 (json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8'))
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('split_jsonl')
    args = parser.parse_args(argv)
    try:
        report = execute(args.split_jsonl)
        print(json.dumps(report, ensure_ascii=False, allow_nan=False))
        return 0
    except ValueInputError as error:
        print(f'stitch_value_failed: {error}', file=sys.stderr)
        return 2
    except Exception:
        print('stitch_value_failed', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
