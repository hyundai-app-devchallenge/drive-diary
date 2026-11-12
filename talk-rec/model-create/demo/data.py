"""Read demo contracts without printing input contents."""
from __future__ import annotations

import json
import math
from pathlib import Path

from pipeline.label import atomic_text, digest, jsonl

VERDICTS = ('story', 'moment', 'none')


def read_jsonl(path):
    rows = []
    with Path(path).open(encoding='utf-8-sig') as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError()
            except ValueError:
                raise ValueError(f'invalid JSONL object at line {number}') from None
            rows.append(row)
    return rows


def write_json(path, value):
    atomic_text(Path(path), json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n')


def latest(path, allowed=VERDICTS):
    result = {}
    for row in read_jsonl(path):
        if not isinstance(row.get('id'), str) or row.get('verdict') not in allowed:
            raise ValueError('invalid judgment record')
        result[row['id']] = row
    return result


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def load_blocks(path, utterances='.local/utterances'):
    """Resolve original utterance indices; do not change or log transcript strings."""
    rows = read_jsonl(path)
    sources = {b.get('source') for b in rows}
    lookup = {}
    for file in sorted(Path(utterances).glob('*.jsonl')):
        for row in read_jsonl(file):
            if row.get('source') in sources:
                key = (row['source'], row['utt'])
                if key in lookup:
                    raise ValueError('duplicate utterance timing')
                lookup[key] = row
    seen = set()
    result = []
    for block in rows:
        if not isinstance(block.get('id'), str) or block['id'] in seen:
            raise ValueError('missing/duplicate block id')
        seen.add(block['id'])
        if type(block.get('block')) is not int or not isinstance(block.get('source'), str):
            raise ValueError('invalid block identity')
        if block['block'] < 0 or block['id'] != f"{block['source']}@{block['block']}":
            raise ValueError('id/source/block mismatch')
        if not all(finite(block.get(k)) for k in ('start', 'end')) or not 0 <= block['start'] < block['end']:
            raise ValueError('invalid block interval')
        if not block.get('utts') or len(block['utts']) != len(block.get('turns', [])):
            raise ValueError('invalid turns/utts')
        if len(set(block['utts'])) != len(block['utts']):
            raise ValueError('duplicate utt in block')
        times = []
        for utt, text in zip(block['utts'], block['turns']):
            row = lookup.get((block['source'], utt))
            if row is None or row.get('text') != text:
                raise ValueError('block does not resolve to matching utterance')
            if not all(finite(row.get(k)) for k in ('start', 'end')) or not block['start'] <= row['start'] <= row['end'] <= block['end']:
                raise ValueError('utterance interval outside block')
            if times and row['start'] < times[-1]['start']:
                raise ValueError('utterances must be in chronological order')
            times.append({'utt': utt, 'start': row['start'], 'end': row['end']})
        result.append(dict(block, turn_times=times))
    return sorted(result, key=lambda b: (b['source'], b['start'], b['block']))
