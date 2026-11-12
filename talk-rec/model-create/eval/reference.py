"""T52: resumable v3 reference judgments; no prompts or response text in outputs."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from itertools import groupby
import json
import math
import os
from pathlib import Path
import re
import sys
import time
import uuid

from pipeline.label import (AgyBackend, BackendError, CommandBackend, FakeBackend,
                            UsageLimit, atomic_text, digest, jsonl, parse_answer)
from . import real

ROOT = Path(__file__).resolve().parents[1]
VERDICTS = ('story', 'moment', 'none')
REASONS = ('', 'not_car', 'not_family', 'negative')
FIELDS = {'block', 'verdict', 'none_reason', 'unsure', 'note'}


def write_json(path, value):
    atomic_text(Path(path), json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n')


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def load_blocks(root, directory, split):
    """Read inputs only; judging never opens human judgments."""
    if split not in ('dev', 'test'):
        raise ValueError('split: configuration')
    path = Path(directory) / 'blocks.jsonl'
    if not path.is_file():
        raise ValueError('missing input: blocks.jsonl')
    mapping, seen, selected = real.read_split(root), set(), []
    for number, b in enumerate(real.read_objects(path), 1):
        identity = b.get('id')
        match = real.BLOCK.fullmatch(identity) if isinstance(identity, str) else None
        if (not match or identity in seen or b.get('source') != 'yt:' + match[1]
                or match[1] not in mapping):
            raise ValueError(f'block identity/split: blocks.jsonl:{number}')
        seen.add(identity)
        if mapping[match[1]] != split:
            continue
        if (not isinstance(b.get('lines'), list)
                or not all(isinstance(line, dict) and isinstance(line.get('text'), str)
                           and not any(c in line['text'] for c in '\r\n') for line in b['lines'])
                or type(b.get('start')) not in (int, float) or not math.isfinite(b['start'])):
            raise ValueError(f'block schema: blocks.jsonl:{number}')
        selected.append(b)
    return sorted(selected, key=lambda b: (b['source'], b['start'], int(b['id'].rsplit('#', 1)[1])))


def batches(blocks, size=7):
    # Boundaries stay fixed on resume; never rebuild from pending IDs.
    for _, group in groupby(blocks, key=lambda b: b['source']):
        rows = list(group)
        for offset in range(0, len(rows), size):
            yield rows[offset:offset + size]


def render(batch):
    return '\n\n'.join(f'<block id="{i}">\n' + '\n'.join(line['text'] for line in b['lines'])
                       + '\n</block>' for i, b in enumerate(batch))


QUOTE_CHARS = 8


def validate(items, batch):
    if not isinstance(items, list) or len(items) != len(batch):
        raise ValueError('answer count: response')
    # A quote is QUOTE_CHARS non-space characters in a row copied from a line. Shorter runs
    # (4 chars) matched ordinary Korean phrasing in almost every note.
    texts = [re.sub(r'\s+', '', line['text']) for b in batch for line in b['lines']]
    fragments = {t[i:i + QUOTE_CHARS] for t in texts for i in range(len(t) - QUOTE_CHARS + 1)}
    for i, item in enumerate(items):
        if not isinstance(item, dict) or set(item) != FIELDS:
            raise ValueError(f'answer schema: response:{i}')
        if type(item['block']) is not int or item['block'] != i:
            raise ValueError(f'answer order: response:{i}')
        note = item['note']
        if (item['verdict'] not in VERDICTS or item['none_reason'] not in REASONS
                or (item['verdict'] != 'none' and item['none_reason'] != '')
                or type(item['unsure']) is not bool):
            raise ValueError(f'answer values: response:{i}')
        if (not isinstance(note, str) or not note.strip() or len(note) > 500
                or any(c in note for c in '\r\n\v\f\x1c\x1d\x1e\x85\u2028\u2029')
                or len(re.findall(r'[.!?。！？]+(?=\s|$|[가-힣A-Za-z])', note.strip())) > 1):
            raise ValueError(f'answer note: response:{i}')
        # Notes are never stored, so a quoted note is dropped rather than failing the batch.
        compact = re.sub(r'\s+', '', note)
        if any(compact[j:j + QUOTE_CHARS] in fragments for j in range(len(compact) - QUOTE_CHARS + 1)):
            item['note'] = ''
    return items


@contextmanager
def locked(directory):
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / '.lock').open('a+b') as stream:
        if os.name == 'nt':
            import msvcrt
            if stream.seek(0, 2) == 0:
                stream.write(b'0')
                stream.flush()
            stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            if os.name == 'nt':
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)


class Store:
    """Immutable atomic call journals are authoritative; JSONL is recoverable."""
    def __init__(self, directory, signature):
        self.directory = Path(directory)
        path = self.directory / 'config.json'
        if path.exists():
            if read_json(path) != signature:
                raise ValueError('signature mismatch: config.json')
        elif any(p.name != '.lock' for p in self.directory.iterdir()):
            raise ValueError('missing signature: config.json')
        else:
            write_json(path, signature)
        self.calls = self.read_calls(self.directory)

    @staticmethod
    def read_calls(directory):
        return [read_json(p) for p in sorted(Path(directory).glob('r*/calls/*.json'))]

    def records(self, repeat):
        return {r['id']: r for call in self.calls if call['repeat'] == repeat
                for r in call['records']}

    def materialize(self, repeat):
        atomic_text(self.directory / f'r{repeat}/predictions.jsonl',
                    jsonl(sorted(self.records(repeat).values(), key=lambda r: r['id'])))

    def commit(self, call):
        path = self.directory / f"r{call['repeat']}/calls/{time.time_ns():020d}-{uuid.uuid4().hex}.json"
        write_json(path, call)
        self.calls.append(call)
        self.materialize(call['repeat'])


def clean_usage(usage):
    allowed = {'input_tokens', 'output_tokens', 'total_tokens', 'cached_input_tokens',
               'prompt_tokens', 'completion_tokens', 'cache_read_input_tokens',
               'cache_creation_input_tokens', 'reasoning_tokens'}
    return {k: v for k, v in (usage or {}).items() if k in allowed and type(v) is int and v >= 0}


def signature(blocks, system, model, repeat, size, split, backend_config):
    return dict(version=3, input_hash=digest(blocks), prompt_hash=digest(system), model=model,
                repeat=repeat, batch_size=size, split=split, backend_hash=digest(backend_config))


def run(blocks, backend, directory, *, system, model='gemini-3.8-flash-low', repeat=3,
        size=7, split='dev', retries=2, wait_minutes=None, backend_config=None,
        sleep=time.sleep, clock=time.perf_counter):
    if repeat < 1 or size < 1 or retries < 0:
        raise ValueError('counts: configuration')
    if wait_minutes is not None and (not math.isfinite(wait_minutes) or wait_minutes <= 0):
        raise ValueError('wait: configuration')
    config = signature(blocks, system, model, repeat, size, split, backend_config)
    failed = False
    with locked(Path(directory)):
        store = Store(directory, config)
        for r in range(1, repeat + 1):
            done = store.records(r)
            store.materialize(r)
            for batch in batches(blocks, size):
                ids = [b['id'] for b in batch]
                if all(i in done for i in ids):
                    continue
                batch_id, failures = digest(ids)[:20], 0
                while True:
                    started, status, usage, records = clock(), 'ok', {}, []
                    interrupted = None
                    try:
                        items = validate(parse_answer(backend.complete(system, render(batch))), batch)
                        # Validate then discard free-form notes, never persist raw response text.
                        records = [dict(id=b['id'], verdict=item['verdict'],
                                        none_reason=item['none_reason'], unsure=item['unsure'])
                                   for b, item in zip(batch, items)]
                    except UsageLimit as error:
                        status, usage = 'limit', error.usage
                    except BackendError as error:
                        status, usage = 'backend_error', error.usage
                        failures += 1
                    except (ValueError, TypeError, KeyError, StopIteration):
                        status = 'validation_error'
                        failures += 1
                    except KeyboardInterrupt as error:
                        status, interrupted = 'interrupted', error
                    usage = usage or getattr(backend, 'usage', {})
                    exhausted = status in ('backend_error', 'validation_error') and failures > retries
                    store.commit(dict(repeat=r, batch=batch_id, ids=ids, records=records,
                                      status=status, exhausted=exhausted,
                                      seconds=max(0, clock() - started), token_usage=clean_usage(usage),
                                      wait_seconds=wait_minutes * 60 if status == 'limit' and wait_minutes else 0))
                    if interrupted is not None:
                        raise interrupted
                    if status == 'ok':
                        break
                    if exhausted:
                        failed = True
                        break
                    if status == 'limit':
                        if wait_minutes is None:
                            return 3
                        remaining = wait_minutes * 60
                        while remaining > 0:
                            step = min(30, remaining)
                            sleep(step)
                            remaining -= step
    return 1 if failed else 0


class ReferenceFake(FakeBackend):
    def complete(self, system_prompt, user_message):
        numbers = [int(n) for n in re.findall(r'^<block id="(\d+)">$', user_message, re.M)]
        self.responses = iter([json.dumps([dict(block=n, verdict='none', none_reason='',
                                                unsure=False, note='가상 판정 결과이다.') for n in numbers])])
        return super().complete(system_prompt, user_message)


def make_backend(args):
    if args.backend == 'agy':
        return AgyBackend(args.model, 'low', args.timeout, args.agy_path)
    if args.backend == 'command':
        if not args.command:
            raise ValueError('command required: configuration')
        return CommandBackend(args.command, args.model, 'low', args.timeout)
    return ReferenceFake()


def output_directory(root, directory, name):
    reference = (Path(directory) / 'reference').resolve()
    target = (reference / real.safe_name(name)).resolve()
    if not target.is_relative_to(reference) or not target.is_relative_to(Path(directory).resolve()):
        raise ValueError('output path: configuration')
    return real.local_path(root, target)


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command_name', required=True)
    judge = sub.add_parser('judge')
    compare = sub.add_parser('compare')
    for child in (judge, compare):
        child.add_argument('--run', default='v1')
        child.add_argument('--out-name', required=True)
        child.add_argument('--split', choices=['dev', 'test'], default=None)
    judge.add_argument('--repeat', type=int, default=3)
    judge.add_argument('--batch-size', type=int, default=7)
    judge.add_argument('--retries', type=int, default=2, help='additional retries per batch, per invocation')
    judge.add_argument('--backend', choices=['agy', 'command', 'fake'], default='agy')
    judge.add_argument('--model', default='gemini-3.8-flash-low')
    judge.add_argument('--command')
    judge.add_argument('--agy-path')
    judge.add_argument('--timeout', type=float, default=600)
    judge.add_argument('--wait-on-limit', type=float, metavar='MINUTES')
    compare.add_argument('--annotator', default='vizatae')
    compare.add_argument('--cast', choices=real.CASTS, action='append')
    compare.add_argument('--open-test', action='store_true')
    compare.add_argument('--bootstrap', type=int, default=2000)
    return p


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        directory = real.run_directory(ROOT, args.run)
        out = output_directory(ROOT, directory, args.out_name)
        if args.command_name == 'compare':
            from .reference_report import compare
            compare(ROOT, directory, out, annotator=args.annotator,
                    split=args.split or ('all' if args.open_test else 'dev'),
                    open_test=args.open_test, cast=args.cast, bootstrap=args.bootstrap)
            print('reference compare: comparison.json, comparison.md')
            return 0
        if not math.isfinite(args.timeout) or args.timeout <= 0:
            raise ValueError('timeout: configuration')
        blocks = load_blocks(ROOT, directory, args.split or 'dev')
        system = (ROOT / 'prompts/judge.md').read_text(encoding='utf-8')
        result = run(blocks, make_backend(args), out, system=system, model=args.model,
                     repeat=args.repeat, size=args.batch_size, split=args.split or 'dev',
                     retries=args.retries, wait_minutes=args.wait_on_limit,
                     backend_config=dict(backend=args.backend, command=args.command, agy_path=args.agy_path))
        print(f'reference judge: exit={result}; resume with the same command')
        return result
    except KeyboardInterrupt:
        print('Interrupted: eval.reference; resume with the same command', file=sys.stderr)
        return 130
    except Exception as error:
        tb = error.__traceback__
        while tb and tb.tb_next:
            tb = tb.tb_next
        location = f'{Path(tb.tb_frame.f_code.co_filename).name}:{tb.tb_lineno}' if tb else 'eval.reference'
        print(f'{type(error).__name__}: {location}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
