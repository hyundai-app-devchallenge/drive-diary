import json

import pytest

from demo import run_arm as r
from demo.data import read_jsonl
from demo.listen import FakeListenBackend
from pipeline.label import BackendError, FakeBackend, UsageLimit


def blocks(n=8, source='s'):
    return [dict(id=f'{source}@{i}', source=source, block=i, start=i * 2., end=i * 2. + 1,
                 utts=[i], turns=[''], gap_before=None,
                 turn_times=[dict(utt=i, start=i * 2., end=i * 2. + 1)],
                 audio=dict(path='unused.webm', start=i * 2., end=i * 2. + 1)) for i in range(n)]


def answer(bs, verdict='story'):
    return json.dumps([dict(block=b['block'], verdict=verdict, note='구조적인 반응이 이어진다.') for b in bs])


def logs(out):
    return [json.loads(p.read_text(encoding='utf-8')) for p in (out.parent / ('.' + out.stem + '.run')).glob('call-*.json')]


def test_batch_order_no_source_crossing():
    bs = blocks(8) + blocks(2, 'z')
    batches = list(r.batches(list(reversed(bs)), {'s@1'}))
    assert [[b['block'] for b in batch] for batch in batches] == [[0, 2, 3, 4, 5, 6], [7], [0, 1]]


def test_retries_only_failed_call_resume_and_journal_recovery(tmp_path):
    bs, out = blocks(), tmp_path / 'text.jsonl'
    backend = FakeBackend([answer(bs[:6]), 'not JSON', answer(bs[6:])])
    ticks = iter(range(20))
    assert r.run(bs, backend, out, clock=lambda: next(ticks)) == 0
    assert len(backend.calls) == 3
    assert read_jsonl(out)[-1]['seconds'] == 1
    again = FakeBackend([])
    assert r.run(bs, again, out) == 0
    assert not again.calls
    out.unlink()
    assert r.run(bs, again, out) == 0
    assert len(read_jsonl(out)) == 8
    assert len(logs(out)) == 3
    assert set(logs(out)[0]) >= {'ids', 'seconds', 'token_usage', 'status'}


@pytest.mark.parametrize('invalid', [
    [], [dict(block=0, verdict='unsure', note='x')], [dict(block=True, verdict='none', note='x')],
    [dict(block=1, verdict='none', note='x')], [dict(block=0, verdict='none', note='')],
    [dict(block=0, verdict='none', note='x', extra=1)],
])
def test_validation(invalid):
    with pytest.raises(ValueError):
        r.validate(invalid, blocks(1))


def test_validation_three_attempts_then_resume(tmp_path):
    bs, out = blocks(1), tmp_path / 'text.jsonl'
    backend = FakeBackend(['[]'] * 3)
    assert r.run(bs, backend, out) == 1
    assert len(backend.calls) == 3 and not read_jsonl(out)
    assert r.run(bs, FakeBackend([answer(bs)]), out) == 0
    assert len(logs(out)) == 4


def test_limit_wait_usage_and_stop(tmp_path):
    bs = blocks(1)
    out = tmp_path / 'text.jsonl'
    assert r.run(bs, FakeBackend([UsageLimit('limit')]), out) == 3
    sleeps = []
    backend = FakeBackend([UsageLimit('limit', {'input_tokens': 12}), answer(bs)])
    assert r.run(bs, backend, out, wait_minutes=1.2, sleep=sleeps.append) == 0
    assert sleeps == [30, 30, 12]
    assert any(log['token_usage'] == {'input_tokens': 12} for log in logs(out))
    assert any(log['wait_seconds'] == 72 for log in logs(out))


def test_backend_error_has_no_raw_log(tmp_path):
    out = tmp_path / 'text.jsonl'
    assert r.run(blocks(1), FakeBackend([BackendError('PRIVATE_SENTINEL')]), out) == 1
    assert 'PRIVATE_SENTINEL' not in ''.join(p.read_text(encoding='utf-8') for p in out.parent.rglob('*.json'))


def test_configuration_change_rejected(tmp_path):
    bs, out = blocks(1), tmp_path / 'text.jsonl'
    assert r.run(bs, FakeBackend([answer(bs)]), out) == 0
    with pytest.raises(ValueError, match='configuration'):
        r.run(bs, FakeBackend([]), out, prompt='changed')


def test_fake_listen_interface_only(tmp_path):
    out = tmp_path / 'listen.jsonl'
    assert r.run(blocks(2), FakeListenBackend(), out, arm='listen', model='fake-listen') == 0
    assert all(row['verdict'] == 'none' and row['model'] == 'fake-listen' for row in read_jsonl(out))
    assert len(logs(out)) == 2


def test_acoustic_input_and_missing_record(tmp_path):
    bs, out = blocks(1), tmp_path / 'acoustic.jsonl'
    with pytest.raises(ValueError, match='missing acoustic'):
        r.run(bs, FakeBackend([]), out, arm='acoustic')
    backend = FakeBackend([answer(bs)])
    assert r.run(bs, backend, out, arm='acoustic', acoustic={'s@0': {'id': 's@0'}}) == 0
    assert 'speakers="0"' in backend.calls[0][1]
