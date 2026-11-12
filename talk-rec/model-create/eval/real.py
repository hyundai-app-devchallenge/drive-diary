"""실제 평가 묶음. 글과 판정은 .local 안에만 저장한다."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import csv
import hashlib
import json
import math
from pathlib import Path
import random
import re
import sys

from pipeline.blocks import Settings, approval_reason, closing_reason, percentile, read_approvals
from tools.listen.storage import ROOT, atomic_write, encode_lines

VIDEO = re.compile(r'[A-Za-z0-9_-]{11}')
BLOCK = re.compile(r'yt:([A-Za-z0-9_-]{11})#([0-9]+)')
CASTS = ('child_family', 'adult_family', 'couple', 'friends', 'unknown')


class CastError(ValueError):
    """Safe TSV locations and fixed reasons, never values from the table."""

    def __init__(self, line, reason):
        super().__init__(f'real-cast.tsv:{line}: {reason}')


def read_cast(root):
    path = Path(root) / 'data/real-cast.tsv'
    result = {}
    if not path.exists():
        return result
    try:
        with path.open(encoding='utf-8-sig', newline='') as stream:
            reader = csv.DictReader(stream, delimiter='\t')
            if reader.fieldnames != ['video_id', 'cast', 'by', 'note']:
                raise CastError(1, 'invalid columns')
            for row in reader:
                number = reader.line_num
                if None in row or any(v is None for v in row.values()):
                    raise CastError(number, 'invalid columns')
                if not VIDEO.fullmatch(row['video_id']):
                    raise CastError(number, 'invalid video id')
                if row['video_id'] in result:
                    raise CastError(number, 'duplicate video id')
                if row['cast'] not in CASTS:
                    raise CastError(number, 'invalid cast')
                result[row['video_id']] = row['cast']
    except UnicodeError:
        raise CastError(1, 'invalid encoding') from None
    return result


def read_channels(root):
    """Read only identity columns; missing channel metadata means no channel pin."""
    path = Path(root) / 'data/sources.tsv'
    if not path.exists():
        return {}
    result = {}
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream, delimiter='\t')
        if 'video_id' not in (reader.fieldnames or []):
            raise ValueError('출처 형식 오류 sources.tsv:1')
        for row in reader:
            video = row.get('video_id')
            if not video or not VIDEO.fullmatch(video) or video in result:
                raise ValueError(f'출처 오류 sources.tsv:{reader.line_num}')
            result[video] = row.get('channel_id') or ''
    return result


def select_cast(rows, casts, cast=None):
    allowed = set(CASTS if cast is None else cast)
    if not allowed <= set(CASTS):
        raise ValueError('탑승 구성 필터 오류')
    result = []
    for row in rows:
        value = casts.get(source_video(row.get('source', row['id'])), 'unknown')
        if value in allowed:
            result.append(dict(row, cast=value))
    return result


def select_labels(labels, blocks):
    ids = {b['id'] for b in blocks}
    return {name: {i: r for i, r in rows.items() if i in ids} for name, rows in labels.items()}


class RangeError(ValueError):
    """Only fixed reasons and TSV locations may be printed by the CLI."""

    def __init__(self, line, reason):
        super().__init__(f'real-ranges.tsv:{line}: {reason}')


def range_time(value, number, *, allow_end=False):
    value = value.strip()
    if allow_end and value == 'end':
        return None
    if re.fullmatch(r'(?:\d+(?:\.\d*)?|\.\d+)', value):
        seconds = float(value)
    elif re.fullmatch(r'\d+:\d{2}(?::\d{2})?', value):
        parts = [int(p) for p in value.split(':')]
        if any(p >= 60 for p in parts[1:]):
            raise RangeError(number, 'invalid time')
        seconds = sum(p * 60**i for i, p in enumerate(reversed(parts)))
    else:
        raise RangeError(number, 'invalid time')
    if not math.isfinite(seconds):
        raise RangeError(number, 'invalid time')
    return seconds


def read_ranges(root, approved):
    path = Path(root) / 'data/real-ranges.tsv'
    ranges, ignored = {}, 0
    if not path.exists():
        return ranges, ignored
    try:
        with path.open(encoding='utf-8-sig', newline='') as stream:
            reader = csv.DictReader(stream, delimiter='\t')
            if reader.fieldnames != ['video_id', 'start', 'end', 'note']:
                raise RangeError(1, 'invalid columns')
            for row in reader:
                number = reader.line_num
                if row.get('video_id') not in approved:
                    ignored += 1
                    continue
                if None in row or any(v is None for v in row.values()):
                    raise RangeError(number, 'invalid columns')
                start = range_time(row['start'], number)
                end = range_time(row['end'], number, allow_end=True)
                if end is not None and start >= end:
                    raise RangeError(number, 'start must precede end')
                ranges.setdefault(row['video_id'], []).append(dict(
                    start=start, end=end, note=row['note'], line=number))
    except UnicodeError:
        raise RangeError(1, 'invalid encoding') from None
    for rows in ranges.values():
        rows.sort(key=lambda r: r['start'])
        for previous, row in zip(rows, rows[1:]):
            if previous['end'] is None or row['start'] < previous['end']:
                raise RangeError(row['line'], 'overlapping ranges')
    return ranges, ignored


def resolve_ranges(rows, seconds):
    if rows is None:
        return [dict(start=0., end=seconds, note='')], []
    result, adjustments = [], []
    for row in rows:
        if row['start'] >= seconds:
            raise RangeError(row['line'], 'start outside source')
        end = seconds if row['end'] is None else min(row['end'], seconds)
        if row['end'] is not None and row['end'] > seconds:
            adjustments.append(dict(line=row['line'], requested_end=row['end'], end=end))
        result.append(dict(start=row['start'], end=end, note=row['note']))
    return result, adjustments


def source_summary(rows, ignored=0):
    return dict(sources=len(rows), seconds=sum(r['seconds'] or 0 for r in rows),
                usable_seconds=sum(r['usable_seconds'] for r in rows),
                ignored_range_rows=ignored,
                by_cast={c: dict(sources=sum(r['cast'] == c for r in rows),
                                 usable_seconds=sum(r['usable_seconds'] for r in rows if r['cast'] == c))
                         for c in CASTS})


def safe_name(name):
    if (not isinstance(name, str) or not name or name != name.strip() or len(name) > 80
            or name in {'.', '..'} or re.search(r'[<>:"/\\|?*\x00-\x1f]', name)
            or name.endswith('.') or name.split('.')[0].upper() in
            {'CON', 'PRN', 'AUX', 'NUL', *('COM'+str(i) for i in range(10)), *('LPT'+str(i) for i in range(10))}):
        raise ValueError('이름 형식 오류')
    return name


def local_path(root, path):
    path = Path(path).resolve()
    if not path.is_relative_to((Path(root) / '.local').resolve()):
        raise ValueError('로컬 경로 오류')
    return path


def run_directory(root=ROOT, run='v1'):
    return local_path(root, Path(root) / '.local/real' / safe_name(run))


def read_objects(path):
    path = Path(path)
    if not path.exists():
        return []
    rows = []
    try:
        with path.open(encoding='utf-8-sig') as stream:
            for number, line in enumerate(stream, 1):
                if not line.strip():
                    continue
                try:
                    row = json.loads(line)
                    if not isinstance(row, dict):
                        raise ValueError()
                except (ValueError, TypeError):
                    raise ValueError(f'JSON 오류 {path.name}:{number}') from None
                rows.append(row)
    except UnicodeError:
        raise ValueError(f'인코딩 오류 {path.name}:1') from None
    return rows


def write_objects(path, rows):
    atomic_write(path, encode_lines(rows))


def duration(path):
    import av
    try:
        with av.open(str(path)) as container:
            if container.duration is not None:
                return float(container.duration / av.time_base)
            return sum(frame.samples / frame.sample_rate for frame in container.decode(audio=0))
    except Exception:
        raise ValueError('오디오 길이 오류') from None


def discover_sources(root, directory):
    rows = []
    casts, channels = read_cast(root), read_channels(root)
    approved = {v: a for v, a in read_approvals(Path(root) / 'data/approved.tsv').items()
                if approval_reason(a) is None}
    ranges, ignored = read_ranges(root, approved)
    for video in sorted(approved):
        if not VIDEO.fullmatch(video):
            raise ValueError('출처 id 오류 approved.tsv:1')
        audio = local_path(root, Path(root) / '.local/audio' / (video + '.webm'))
        row = dict(id=video, seconds=None, status='ready', reason='', ranges=[], usable_seconds=0,
                   range_adjustments=[], cast=casts.get(video, 'unknown'), channel_id=channels.get(video, ''))
        if not audio.is_file():
            row.update(status='skipped', reason='audio_missing')
        else:
            try:
                row['seconds'] = duration(audio)
                if not math.isfinite(row['seconds']) or row['seconds'] <= 0:
                    raise ValueError()
            except ValueError:
                row.update(status='skipped', reason='audio_invalid', seconds=None)
        if row['status'] == 'ready':
            row['ranges'], row['range_adjustments'] = resolve_ranges(ranges.get(video), row['seconds'])
            row['usable_seconds'] = sum(r['end']-r['start'] for r in row['ranges'])
        rows.append(row)
    atomic_write(Path(directory) / 'sources.json', (json.dumps(rows, indent=2) + '\n').encode())
    atomic_write(Path(directory) / 'sources-summary.json',
                 (json.dumps(source_summary(rows, ignored), indent=2) + '\n').encode())
    return rows


def source_video(identity):
    match = re.fullmatch(r'yt:([A-Za-z0-9_-]{11})(?:@0)?', identity)
    if match is None:
        raise ValueError('출처 id 오류')
    return match[1]


def transcriptions(root, directory):
    from demo.asr.data import validate_result
    approvals = read_approvals(Path(root) / 'data/approved.tsv')
    ranges, _ = read_ranges(root, {v for v, a in approvals.items() if approval_reason(a) is None})
    rows = read_objects(Path(directory) / 'asr.jsonl')
    if not rows:
        raise ValueError('ASR 결과 없음 asr.jsonl:1')
    seen = set()
    for number, row in enumerate(rows, 1):
        try:
            validate_result(row)
            video = source_video(row['id'])
            if (video in seen or row['config'] != 'pleos'
                    or approval_reason(approvals.get(video)) is not None):
                raise ValueError()
            previous = -1
            for line in row['lines']:
                if line['start'] < previous or line['end'] > row['seconds']:
                    raise ValueError()
                previous = line['start']
            seen.add(video)
        except (ValueError, KeyError, TypeError):
            raise ValueError(f'ASR 오류 asr.jsonl:{number}') from None
        row['ranges'], _ = resolve_ranges(ranges.get(video), row['seconds'])
    return rows


def make_blocks(rows, silence=20, length=90):
    settings = Settings(silence=silence, max_seconds=length, max_turns=None)
    blocks = []
    for row in sorted(rows, key=lambda r: r['id']):
        source = 'yt:' + source_video(row['id'])
        ranges = row.get('ranges', [dict(start=0, end=row['seconds'], note='')])
        grouped = [[] for _ in ranges]
        for line in row['lines']:
            matches = [i for i, r in enumerate(ranges)
                       if r['start'] <= line['start'] < r['end'] and line['end'] <= r['end']]
            if len(matches) != 1:
                raise ValueError('ASR range boundary error asr.jsonl')
            grouped[matches[0]].append(line)
        ordinal = 0
        for range_index, lines in enumerate(grouped):
            first = 0
            for index in range(len(lines)):
                turns = lines[first:index + 1]
                following = lines[index + 1] if index + 1 < len(lines) else None
                reason = 'range_end' if following is None else closing_reason(turns, following, settings)
                if reason is None:
                    continue
                start, end = turns[0]['start'], max(t['end'] for t in turns)
                blocks.append(dict(id=f'{source}#{ordinal}', source=source, start=start, end=end,
                                   seconds=end-start, n_lines=len(turns), closing=reason, lines=turns,
                                   range_index=range_index))
                first, ordinal = index + 1, ordinal + 1
    return blocks


def build_blocks(root, directory, silence=20, length=90):
    blocks = make_blocks(transcriptions(root, directory), silence, length)
    path = Path(directory) / 'blocks.jsonl'
    # Changing IDs after listening silently invalidates judgments. New runs are cheap.
    if any((Path(directory) / 'judgments').glob('*.jsonl')):
        old = {b['id']: b for b in read_objects(path)}
        new = {b['id']: b for b in blocks}
        if any(new.get(k) != v for k, v in old.items()):
            raise ValueError('기존 묶음 변경: 새 실행 이름 필요')
    write_objects(path, blocks)
    return blocks


def grid(rows):
    output = ['| 침묵 초 | 길이 초 | 묶음 수 | 길이 p10 | p50 | p90 | 줄 수 p10 | p50 | p90 | 길이 닫힘 비율 | 침묵 닫힘 비율 | range_end 비율 |',
              '|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for silence in (10, 20, 30):
        for length in (60, 90, 120):
            blocks = make_blocks(rows, silence, length)
            values = [silence, length, len(blocks)]
            values += [percentile([b[key] for b in blocks], p) or 0
                       for key in ('seconds', 'n_lines') for p in (.1, .5, .9)]
            values += [sum(b['closing'] == reason for b in blocks) / len(blocks) if blocks else 0
                       for reason in ('length', 'silence', 'range_end')]
            output.append('| ' + ' | '.join(f'{v:.3f}' if isinstance(v, float) else str(v) for v in values) + ' |')
    return '\n'.join(output)


def read_split(root):
    path = Path(root) / 'data/real-split.tsv'
    if not path.exists():
        return {}
    result = {}
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream, delimiter='\t')
        if reader.fieldnames != ['video_id', 'split']:
            raise ValueError('분할 형식 오류 real-split.tsv:1')
        for number, row in enumerate(reader, 2):
            video, split = row.get('video_id', ''), row.get('split')
            if not VIDEO.fullmatch(video) or video in result or split not in {'dev', 'test'} or None in row:
                raise ValueError(f'분할 오류 real-split.tsv:{number}')
            result[video] = split
    return result


def assign_split(root, videos, test_ratio=.4):
    if not math.isfinite(test_ratio) or not 0 < test_ratio < 1:
        raise ValueError('분할 비율 오류')
    videos = sorted(set(videos))
    if any(not VIDEO.fullmatch(v) for v in videos):
        raise ValueError('출처 id 오류')
    result = read_split(root)
    channels = read_channels(root)
    def unit(video):
        channel = channels.get(video, '')
        return ('channel', channel) if channel else ('video', video)
    existing, members = {}, {}
    for video, split in result.items():
        key = unit(video)
        existing.setdefault(key, split)  # First recorded row anchors an already mixed channel.
        members.setdefault(key, []).append(video)
    for key, group in sorted(members.items()):
        if key[0] == 'channel' and len({result[v] for v in group}) > 1:
            print(f'경고: 채널 분할 혼재 {key[1]} ' + ' '.join(sorted(group)), file=sys.stderr)
    fresh = [v for v in videos if v not in result]
    def score(key):
        return int(hashlib.sha256(key[1].encode()).hexdigest(), 16) / 2**256
    groups = {}
    for video in fresh:
        key = unit(video)
        groups.setdefault(key, []).append(video)
        result[video] = existing.get(key, 'test' if score(key) < test_ratio else 'dev')
    movable = [key for key in groups if key not in existing]
    if len({unit(v) for v in videos}) >= 2:
        for needed, ordering in (('test', False), ('dev', True)):
            if needed not in {result[v] for v in videos} and movable:
                chosen = sorted(movable, key=lambda key: (score(key), key), reverse=ordering)[0]
                for video in groups[chosen]:
                    result[video] = needed
    if fresh:
        # Preserve existing bytes, including row order and line endings.
        path = Path(root) / 'data/real-split.tsv'
        old = path.read_bytes() if path.exists() else b'video_id\tsplit\n'
        addition = ''.join(f'{v}\t{result[v]}\n' for v in fresh).encode()
        atomic_write(path, old + (b'\n' if old and not old.endswith(b'\n') else b'') + addition)
    return result


JUDGMENT_KEYS = {'id', 'keep', 'kind', 'none_reason', 'unsure', 'policy', 'comment', 'listened', 'labeler', 'saved_at'}
REVIEW_KEYS = {'suggested_by', 'review'}
SUGGESTION_FIELDS = ('keep', 'kind', 'none_reason', 'unsure', 'policy', 'comment')


def validate_judgment(row, annotator):
    if (not isinstance(row, dict) or set(row) not in (JUDGMENT_KEYS, JUDGMENT_KEYS | REVIEW_KEYS)
            or (REVIEW_KEYS <= set(row) and
                (row['suggested_by'] != 'claude' or row['review'] not in ('accepted', 'changed')))
            or not isinstance(row['id'], str) or not BLOCK.fullmatch(row['id'])
            or row['keep'] not in ('keep', 'none')
            or (row['keep'] == 'keep' and (row['kind'] not in ('story', 'moment') or row['none_reason'] != ''))
            or (row['keep'] == 'none' and (row['kind'] != '' or row['none_reason'] not in ('', 'not_car', 'negative')))
            or type(row['unsure']) is not bool or type(row['listened']) is not bool
            or not isinstance(row['policy'], list)
            or any(p not in ('optout', 'command', 'external') for p in row['policy'])
            or len(set(row['policy'])) != len(row['policy'])
            or not isinstance(row['comment'], str) or len(row['comment']) > 2000
            or row['labeler'] != 'human:' + annotator
            or not isinstance(row['saved_at'], str) or not row['saved_at']):
        raise ValueError('판정 형식 오류')
    return row


def judgment_path(directory, annotator):
    path = Path(directory) / 'judgments' / (safe_name(annotator) + '.jsonl')
    if not path.resolve().is_relative_to(Path(directory).resolve()):
        raise ValueError('판정 경로 오류')
    return path


def _read_judgments(directory, annotator, allowed_ids):
    """Only common I/O boundary. Callers must supply an explicit allowed ID set."""
    result = {}
    path = judgment_path(directory, annotator)
    for number, row in enumerate(read_objects(path), 1):
        if row.get('id') not in allowed_ids:
            continue
        try:
            validate_judgment(row, annotator)
        except (ValueError, TypeError, KeyError):
            raise ValueError(f'판정 오류 {path.name}:{number}') from None
        result[row['id']] = row
    return result


def read_own_judgments(directory, annotator, ids):
    """Annotation-only view: exactly one fixed annotator, including test blocks."""
    human_name(annotator)
    return _read_judgments(directory, annotator, set(ids))


def human_name(name):
    name = safe_name(name)
    if name.casefold() == 'claude':
        raise ValueError('claude는 제안 전용 이름입니다.')
    return name


def load_suggestions(root, directory, *, warn_test=False):
    """Fixed Claude file only; discard test and unassigned sources before validation."""
    mapping = read_split(root)
    blocks = read_objects(Path(directory) / 'blocks.jsonl')
    allowed = {b['id'] for b in blocks if BLOCK.fullmatch(b['id'])
               and b['source'] == 'yt:' + BLOCK.fullmatch(b['id'])[1]
               and mapping.get(b['source'][3:]) == 'dev'}
    result, test_ids = {}, set()
    path = judgment_path(directory, 'claude')
    for number, row in enumerate(read_objects(path), 1):
        identity = row.get('id')
        match = BLOCK.fullmatch(identity) if isinstance(identity, str) else None
        if match and mapping.get(match[1]) == 'test':
            test_ids.add(identity)
        if not match or identity not in allowed:
            continue
        try:
            if (set(row) != (JUDGMENT_KEYS - {'listened'}) | {'basis'}
                    or row['labeler'] != 'claude' or row['basis'] != 'transcript'):
                raise ValueError()
            validate_judgment(dict({k: v for k, v in row.items() if k != 'basis'},
                                   labeler='human:claude', listened=False), 'claude')
        except (ValueError, TypeError, KeyError):
            raise ValueError(f'제안 오류 claude.jsonl:{number}') from None
        result[identity] = row
    if warn_test and test_ids:
        print(f'경고: Claude test 제안 {len(test_ids)}개 제외', file=sys.stderr)
    return result


def review_result(answer, suggestion):
    return 'accepted' if all(
        set(answer[k]) == set(suggestion[k]) if k == 'policy' else answer[k] == suggestion[k]
        for k in SUGGESTION_FIELDS) else 'changed'


def load_labels(root, directory, *, annotators=None, split='dev', open_test=False, command='library', cast=None):
    """Mandatory evaluation/training gate. No persistent unlock state exists."""
    if split not in {'dev', 'test', 'all'} or (split != 'dev' and not open_test):
        raise ValueError('test 잠김: --open-test 필요')
    mapping = read_split(root)
    blocks = read_objects(Path(directory) / 'blocks.jsonl')
    if any(b['source'][3:] not in mapping for b in blocks):
        raise ValueError('출처 분할 없음: split 먼저 실행')
    if split != 'dev':
        # Log before touching any label. A failed log write denies access.
        if command not in {'stats', 'agree', 'export', 'library'}:
            raise ValueError('접근 명령 오류')
        path = Path(directory) / 'test-access.log'
        old = path.read_bytes() if path.exists() else b''
        atomic_write(path, old + f'{datetime.now(timezone.utc).isoformat()}\t{command}\tsplit={split}\n'.encode())
    selected = [b for b in blocks if split == 'all' or mapping[b['source'][3:]] == split]
    selected = select_cast(selected, read_cast(root), cast)
    ids = {b['id'] for b in selected}
    if annotators is None:
        annotators = sorted(p.stem for p in (Path(directory) / 'judgments').glob('*.jsonl')
                            if p.stem.casefold() != 'claude')
    labels = {}
    for name in annotators:
        if name == 'claude' and command == 'agree':
            labels[name] = {i: r for i, r in load_suggestions(root, directory, warn_test=True).items() if i in ids}
        else:
            labels[name] = _read_judgments(directory, human_name(name), ids)
    return selected, labels


def distribution(rows):
    rows = list(rows)
    return dict(judgments=len(rows), keep=dict(Counter(r['keep'] for r in rows)),
                kind=dict(Counter(r['kind'] for r in rows if r['kind'])),
                unsure=sum(r['unsure'] for r in rows),
                unsure_ratio=sum(r['unsure'] for r in rows) / len(rows) if rows else 0)


def kappa(pairs):
    n = len(pairs)
    if not n:
        return dict(n=0, kappa=None)
    observed = sum(a['keep'] == b['keep'] for a, b in pairs) / n
    chance = sum(sum(a['keep'] == value for a, _ in pairs) / n *
                 sum(b['keep'] == value for _, b in pairs) / n for value in ('keep', 'none'))
    return dict(n=n, kappa=(observed-chance)/(1-chance) if chance < 1 else None)


def agreement(labels):
    active = {name: rows for name, rows in labels.items() if rows}
    report = dict(annotators=len(active), distributions={n: distribution(r.values()) for n, r in labels.items()})
    if len(active) < 2:
        report['status'] = '판정자 2명 필요'
        return report
    if len(active) != 2:
        raise ValueError('판정자 두 이름 필요')
    a, b = active.values()
    common = sorted(set(a) & set(b))
    pairs = [(a[i], b[i]) for i in common]
    kinds = [(x, y) for x, y in pairs if x['keep'] == y['keep'] == 'keep']
    report.update(including_unsure=kappa(pairs),
                  excluding_unsure=kappa([(x, y) for x, y in pairs if not x['unsure'] and not y['unsure']]),
                  kind_count=len(kinds), kind_agreement=sum(x['kind'] == y['kind'] for x, y in kinds)/len(kinds) if kinds else None,
                  disagreements=[i for i in common if a[i]['keep'] != b[i]['keep'] or a[i]['kind'] != b[i]['kind']],
                  policy_disagreements=[i for i in common if set(a[i]['policy']) != set(b[i]['policy'])])
    return report


def statistics_report(root, directory, *, cast=None, **access):
    # Read every cast in the authorized split once for the unconditional cast summary.
    all_selected, all_labels = load_labels(root, directory, command='stats', **access)
    casts = read_cast(root)
    selected = select_cast(all_selected, casts, cast)
    labels = select_labels(all_labels, selected)
    blocks = select_cast(read_objects(Path(directory) / 'blocks.jsonl'), casts)
    mapping = read_split(root)
    source_path = Path(directory) / 'sources.json'
    try:
        sources = json.loads(source_path.read_text(encoding='utf-8')) if source_path.exists() else []
    except (ValueError, UnicodeError):
        raise ValueError('출처 오류 sources.json:1') from None
    return dict(sources=len(sources) if sources else len({b['source'] for b in blocks}),
                by_source={r['id']: dict(usable_seconds=r.get('usable_seconds', r['seconds']) or 0,
                                         blocks=sum(b['source'] == 'yt:' + r['id'] for b in blocks))
                           for r in sources},
                block_sources=len({b['source'] for b in blocks}), blocks=len(blocks), selected_blocks=len(selected),
                annotators={name: distribution(rows.values()) for name, rows in labels.items()},
                by_cast={c: dict(blocks=sum(b['cast'] == c for b in blocks),
                                 selected_blocks=sum(b['cast'] == c for b in all_selected),
                                 annotators={name: distribution(rows.values()) for name, rows in
                                             select_labels(all_labels, [b for b in all_selected if b['cast'] == c]).items()})
                         for c in CASTS},
                splits={s: dict(sources=len({v for v, value in mapping.items() if value == s}),
                                blocks=sum(mapping[b['source'][3:]] == s for b in blocks),
                                reviews={name: {value: sum(r.get('review') == value for i, r in rows.items()
                                                          if mapping[BLOCK.fullmatch(i)[1]] == 'dev')
                                                for value in ('accepted', 'changed')}
                                         for name, rows in labels.items()}
                                        if s == 'dev' and access.get('split', 'dev') in ('dev', 'all') else None,
                                judgments={name: distribution(r for i, r in rows.items()
                                              if mapping[BLOCK.fullmatch(i)[1]] == s)
                                           for name, rows in labels.items()}
                                if access.get('split', 'dev') in (s, 'all') else None)
                        for s in ('dev', 'test')})


def export(root, directory, **access):
    from demo.trial.data import input_text
    blocks, labels = load_labels(root, directory, command='export', **access)
    by_id = {b['id']: b for b in blocks}
    folder = Path(directory) / 'export' / access.get('split', 'dev')
    mapping = read_split(root)
    def example(identity, judgment):
        b = by_id[identity]
        # Comments never enter model inputs/exports.
        return dict(id=identity, source=b['source'], split=mapping[b['source'][3:]], text=input_text(b['lines']),
                    cast=b['cast'],
                    keep=judgment['keep'], kind=judgment['kind'], none_reason=judgment['none_reason'],
                    unsure=judgment['unsure'], policy=judgment['policy'], listened=judgment['listened'],
                    labeler=judgment['labeler'])
    counts = {}
    for name, rows in labels.items():
        values = [example(i, row) for i, row in sorted(rows.items())]
        write_objects(folder / 'annotators' / (safe_name(name) + '.jsonl'), values)
        counts[name] = len(values)
    consensus = []
    if len(labels) >= 2:
        common = set.intersection(*(set(r) for r in labels.values()))
        for identity in sorted(common):
            rows = [r[identity] for r in labels.values()]
            fields = ('keep', 'kind', 'none_reason')
            if (all(not r['unsure'] and r['listened'] for r in rows)
                    and all(all(r[k] == rows[0][k] for k in fields)
                            and set(r['policy']) == set(rows[0]['policy']) for r in rows)):
                consensus.append(example(identity, dict(rows[0], labeler='consensus')))
    write_objects(folder / 'consensus.jsonl', consensus)
    atomic_write(folder / 'manifest.json', (json.dumps(dict(version=1, split=access.get('split', 'dev'),
                 cast=sorted(set(access['cast'])) if access.get('cast') is not None else None,
                 annotators={name: f'annotators/{name}.jsonl' for name in labels},
                 consensus='consensus.jsonl'), ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    return dict(annotators=counts, consensus=len(consensus))


def listening_order(blocks, seed=50):
    rows = sorted(blocks, key=lambda b: b['id'])
    random.Random(seed).shuffle(rows)
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['sources', 'blocks', 'grid', 'agree', 'split', 'export', 'stats', 'open-test'])
    parser.add_argument('names', nargs='*', help='agree: 판정자 두 이름 / open-test: 명령과 이름')
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--run', default='v1')
    parser.add_argument('--silence', type=float, default=20)
    parser.add_argument('--length', type=float, default=90)
    parser.add_argument('--test-ratio', type=float, default=.4)
    parser.add_argument('--split', choices=['dev', 'test', 'all'])
    parser.add_argument('--open-test', action='store_true')
    parser.add_argument('--cast', choices=CASTS, action='append', help='탑승 구성 (여러 번 지정 가능, 기본 전체)')
    args = parser.parse_args(argv)
    args.split = args.split or ('all' if args.open_test else 'dev')
    try:
        directory = run_directory(args.root, args.run)
        if args.command == 'open-test':
            if not args.names or args.names[0] not in {'agree', 'export', 'stats'}:
                raise ValueError('open-test 뒤에 agree/export/stats 필요')
            args.command, args.names = args.names[0], args.names[1:]
            args.split, args.open_test = 'test', True
        if args.names and args.command != 'agree':
            raise ValueError('이름 인수 오류')
        if args.cast and args.command not in {'stats', 'agree', 'export', 'grid'}:
            raise ValueError('탑승 구성 필터 명령 오류')
        access = dict(split=args.split, open_test=args.open_test, cast=args.cast)
        if args.command == 'sources':
            discover_sources(args.root, directory)
            report = json.loads((directory / 'sources-summary.json').read_text(encoding='utf-8'))
        elif args.command == 'blocks':
            report = dict(blocks=len(build_blocks(args.root, directory, args.silence, args.length)))
        elif args.command == 'grid':
            print(grid(select_cast(transcriptions(args.root, directory), read_cast(args.root), args.cast)))
            return 0
        elif args.command == 'split':
            sources = discover_sources(args.root, directory)
            mapping = assign_split(args.root, [r['id'] for r in sources if r['status'] == 'ready'], args.test_ratio)
            report = dict(Counter(mapping.values()))
        elif args.command == 'agree':
            if args.names and len(args.names) != 2:
                raise ValueError('판정자 두 이름 필요')
            _, labels = load_labels(args.root, directory, annotators=args.names or None, command='agree', **access)
            report = agreement(labels)
        elif args.command == 'export':
            report = export(args.root, directory, **access)
        else:
            report = statistics_report(args.root, directory, **access)
        print(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False))
    except (ValueError, KeyError, TypeError, OSError) as error:
        if isinstance(error, (RangeError, CastError)):
            print(str(error), file=sys.stderr)
            return 1
        # Never print exception values, which can come from local speech/comments.
        trace = error.__traceback__
        while trace.tb_next:
            trace = trace.tb_next
        print(f'{type(error).__name__} {Path(trace.tb_frame.f_code.co_filename).name}:{trace.tb_lineno}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
