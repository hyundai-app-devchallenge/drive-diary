"""Resumable A/B labeling, and the fake-only C interface."""
from __future__ import annotations

import argparse
from itertools import groupby
import json
from pathlib import Path
import time

# Reuse the existing transports unchanged, including agy's success-envelope handling.
from pipeline.label import AgyBackend, BackendError, CommandBackend, FakeBackend, UsageLimit, parse_answer

from .data import VERDICTS, atomic_text, digest, finite, jsonl, latest, load_blocks, read_jsonl, write_json
from .listen import FakeListenBackend
from .render import render_text_only, render_with_acoustic

PROMPT = Path(__file__).resolve().parents[1] / 'prompts/demo-story.md'


def validate(items, blocks):
    if not isinstance(items, list) or len(items) != len(blocks):
        raise ValueError('answer count mismatch')
    for item, block in zip(items, blocks):
        if not isinstance(item, dict) or set(item) != {'block', 'verdict', 'note'}:
            raise ValueError('answer schema mismatch')
        if type(item['block']) is not int or item['block'] != block['block']:
            raise ValueError('block order mismatch')
        if item['verdict'] not in VERDICTS or not isinstance(item['note'], str) or not item['note'].strip():
            raise ValueError('invalid verdict/note')
        if len(item['note']) > 500 or '\n' in item['note']:
            raise ValueError('note must be one short sentence')
        # Do not persist verbatim transcript turns in judgments or reports.
        if any(len(text.strip()) >= 4 and text.strip() in item['note'] for text in block['turns']):
            raise ValueError('note contains input quotation')
    return items


def batches(blocks, done, size=6):
    # No cross-source batch: block numbers restart at zero in each source.
    for _, group in groupby(sorted(blocks, key=lambda b: (b['source'], b['start'], b['block'])), key=lambda b: b['source']):
        pending = [b for b in group if b['id'] not in done]
        for offset in range(0, len(pending), size):
            yield pending[offset:offset + size]


class ArmStore:
    """Atomic call journals make output and cost logs recoverable after interruption."""
    def __init__(self, out, signature, arm):
        self.out = Path(out)
        self.directory = self.out.parent / ('.' + self.out.stem + '.run')
        self.directory.mkdir(parents=True, exist_ok=True)
        config = self.directory / 'config.json'
        if config.exists() and json.loads(config.read_text(encoding='utf-8')) != signature:
            raise ValueError('run configuration/input changed; use a different output')
        self.records = latest(self.out) if self.out.exists() else {}
        self.costs = {}
        if any(row.get('arm') != arm or row.get('model') != signature['model'] for row in self.records.values()):
            raise ValueError('existing output arm/model mismatch')
        if not config.exists() and self.records:
            raise ValueError('existing output has no run signature; use a different output')
        write_json(config, signature)
        for path in sorted(self.directory.glob('call-*.json')):
            call = json.loads(path.read_text(encoding='utf-8'))
            for identity in call['ids']:
                self.costs[identity] = self.costs.get(identity, 0) + call['seconds'] / len(call['ids'])
            for record in call.get('records', []):
                self.records[record['id']] = record
        self.materialize()

    def materialize(self):
        atomic_text(self.out, jsonl(sorted(self.records.values(), key=lambda r: r['id'])))

    def commit(self, records, log):
        # One immutable journal per attempted call, including validation/limit errors.
        stamp = f'{time.time_ns()}-{digest(log)[:12]}'
        write_json(self.directory / f'call-{stamp}.json', {'records': records, **log})
        for identity in log['ids']:
            self.costs[identity] = self.costs.get(identity, 0) + log['seconds'] / len(log['ids'])
        for record in records:
            self.records[record['id']] = record
        self.materialize()


def run(blocks, backend, out, arm='text', model='fake', acoustic=None, retries=2,
        wait_minutes=None, sleep=time.sleep, clock=time.perf_counter, prompt=None, backend_config=None):
    prompt = PROMPT.read_text(encoding='utf-8') if prompt is None else prompt
    if arm not in ('text', 'acoustic', 'listen') or retries < 0:
        raise ValueError('invalid arm/retries')
    if wait_minutes is not None and (not finite(wait_minutes) or wait_minutes <= 0):
        raise ValueError('wait minutes must be positive')
    acoustic = acoustic or {}
    if arm == 'acoustic' and any(b['id'] not in acoustic for b in blocks):
        raise ValueError('missing acoustic record')
    signature = dict(arm=arm, model=model, prompt=digest(prompt), sample=digest(blocks),
                     acoustic=digest(acoustic) if arm == 'acoustic' else None,
                     backend=type(backend).__name__, backend_config=digest(backend_config))
    store = ArmStore(out, signature, arm)
    for batch in batches(blocks, store.records, size=1 if arm == 'listen' else 6):
        user = '\n\n'.join(render_with_acoustic(b, acoustic[b['id']]) if arm == 'acoustic'
                           else render_text_only(b) for b in batch) if arm != 'listen' else ''
        failures = 0
        while True:
            started = clock()
            status, records, usage = 'ok', [], {}
            stop, limited = False, False
            try:
                if arm == 'listen':
                    b = batch[0]
                    audio = b['audio']
                    item = backend.judge(audio['path'], audio['start'], audio['end'], prompt)
                    items = [dict(item, block=b['block'])]
                else:
                    items = parse_answer(backend.complete(prompt, user))
                validate(items, batch)
            except UsageLimit as error:
                usage = error.usage
                status, limited = 'limit', True
                stop = wait_minutes is None
            except BackendError as error:
                usage = error.usage
                status, stop = 'backend_error', True
            except (ValueError, TypeError, KeyError, StopIteration):
                status = 'validation_error'
                failures += 1
                stop = failures > retries
            elapsed = clock() - started
            usage = usage or getattr(backend, 'usage', {}) or {}
            if status == 'ok':
                records = [dict(id=b['id'], arm=arm, verdict=item['verdict'], note=item['note'],
                                seconds=store.costs.get(b['id'], 0) + elapsed / len(batch), model=model)
                           for b, item in zip(batch, items)]
            store.commit(records, dict(ids=[b['id'] for b in batch], status=status,
                                       seconds=elapsed, token_usage=usage,
                                       wait_seconds=wait_minutes * 60 if limited and not stop else 0))
            if status == 'ok':
                break
            if stop:
                return 3 if limited else 1
            if limited:
                # Waiting is excluded from inference seconds and stored separately.
                remaining = wait_minutes * 60
                while remaining > 0:
                    step = min(30, remaining)
                    sleep(step)
                    remaining -= step
    return 0


def make_backend(args, blocks):
    if args.arm == 'listen':
        if args.backend != 'fake':
            raise ValueError('listen supports only --backend fake in T41')
        return FakeListenBackend(), 'fake-listen'
    if args.backend == 'agy':
        return AgyBackend(args.model, effort='low', timeout=args.timeout, path=args.agy_path), args.model
    if args.backend == 'command':
        if not args.command:
            raise ValueError('--command required for command backend')
        return CommandBackend(args.command, args.model, 'low', args.timeout), args.model
    # CLI resume may change batch boundaries; use a tiny adapter to prepare each
    # response from the actual envelope while still calling the imported fake.
    class DemoFake(FakeBackend):
        def complete(self, system_prompt, user_message):
            import re
            numbers = [int(n) for n in re.findall(r'<block id="(\d+)"', user_message)]
            self.responses = iter([json.dumps([dict(block=n, verdict='none', note='가짜 백엔드의 고정 판정이다.') for n in numbers])])
            return super().complete(system_prompt, user_message)
    return DemoFake(), 'fake'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--arm', required=True, choices=['text', 'acoustic', 'listen'])
    parser.add_argument('--backend', choices=['agy', 'fake', 'command'], default='agy')
    parser.add_argument('--sample', default='.local/demo/sample.jsonl')
    parser.add_argument('--acoustic', default='.local/demo/acoustic.jsonl')
    parser.add_argument('--utterances', default='.local/utterances')
    parser.add_argument('--out', required=True)
    parser.add_argument('--model', default='gemini-3.8-flash-low')
    parser.add_argument('--agy-path')
    parser.add_argument('--command')
    parser.add_argument('--timeout', type=float, default=600)
    parser.add_argument('--wait-on-limit', type=float, metavar='MINUTES')
    args = parser.parse_args(argv)
    try:
        blocks = load_blocks(args.sample, args.utterances)
        acoustic = {}
        if args.arm == 'acoustic':
            for row in read_jsonl(args.acoustic):
                if row['id'] in acoustic:
                    raise ValueError('duplicate acoustic id')
                acoustic[row['id']] = row
        backend, model = make_backend(args, blocks)
        return run(blocks, backend, args.out, args.arm, model, acoustic, wait_minutes=args.wait_on_limit,
                   backend_config=dict(backend=args.backend, command=args.command, agy_path=args.agy_path))
    except Exception as error:
        print(f'run_arm failed ({type(error).__name__}); check input/configuration; no response body logged')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
