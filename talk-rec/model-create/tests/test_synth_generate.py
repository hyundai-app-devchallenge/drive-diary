from copy import deepcopy
import json

import pytest

from pipeline.label import BackendError, FakeBackend, UsageLimit
from synth.cards import sample_cards
from synth.generate import (SynthFakeBackend, fake_scene, generate, main, parse_response,
                            request_batch, similarity, validate_cards, validate_scenes)
from synth.io import digest, read_jsonl, write_json, write_jsonl


@pytest.fixture
def batch():
    cards = sample_cards(12)[:4]
    return cards, [fake_scene(c) for c in cards]


def test_fake_backend_reuses_existing_class(batch):
    cards, scenes = batch
    backend = SynthFakeBackend()
    assert isinstance(backend, FakeBackend)
    from synth.generate import render_prompt
    assert json.loads(backend.complete(*render_prompt(cards))) == scenes
    assert len(backend.calls) == 1


@pytest.mark.parametrize('fault', ['count', 'card', 'extra', 'speaker', 'order', 'negative', 'nan', 'booltime',
                                   'zero_duration', 'short', 'long', 'lines', 'ban', 'empty', 'why', 'overlap'])
def test_scene_validation_rejects_bad_output(batch, fault):
    cards, scenes = batch
    validate_scenes(scenes, cards)
    bad = deepcopy(scenes)
    item, line = bad[0], bad[0]['lines'][0]
    if fault == 'count': bad.pop()
    if fault == 'card': item['card'] += 1
    if fault == 'extra': item['stage_direction'] = '설명'
    if fault == 'speaker': line['spk'] = '외부인'
    if fault == 'order': item['lines'][-1]['t'] = -1
    if fault == 'negative': line['t'] = -1
    if fault == 'nan': line['dur'] = float('nan')
    if fault == 'booltime': line['t'] = False
    if fault == 'zero_duration': line['dur'] = 0
    if fault == 'short': item['lines'][-1]['dur'] = .001; item['lines'][-1]['t'] = 0
    if fault == 'long': line['dur'] = 200
    if fault == 'lines': item['lines'] = []
    if fault == 'ban': line['text'] = '아이폰'
    if fault == 'empty': line['text'] = ' '
    if fault == 'why': item['why'] = ''
    if fault == 'overlap': line['overlap'] = 1
    with pytest.raises(ValueError):
        validate_scenes(bad, cards)


def test_4gram_duplicate_within_and_across_batches(batch):
    cards, scenes = batch
    assert similarity(scenes[0], scenes[0]) == 1
    with pytest.raises(ValueError, match='duplicate'):
        validate_scenes(scenes[:1], cards[:1], scenes[:1])
    cards[1] = dict(cards[0], card=cards[1]['card'])
    scenes[1] = dict(deepcopy(scenes[0]), card=cards[1]['card'])
    with pytest.raises(ValueError, match='duplicate'):
        validate_scenes(scenes, cards)


def test_retry_only_invalid_batch(batch, tmp_path):
    cards, scenes = batch
    backend = FakeBackend(['[]', 'not json', json.dumps(scenes)])
    result, calls = request_batch(cards, lambda: backend, [])
    assert result == scenes and calls == 3 and len(backend.calls) == 3
    assert len({user for _, user in backend.calls}) == 1


def test_three_invalid_attempts_stop(batch):
    cards, _ = batch
    backend = FakeBackend(['[]'] * 3)
    with pytest.raises(ValueError):
        request_batch(cards, lambda: backend, [])
    assert len(backend.calls) == 3


def test_successful_batch_not_retried_when_next_batch_invalid(tmp_path):
    cards = sample_cards(8)
    backend = FakeBackend([json.dumps([fake_scene(c) for c in cards[:4]]), '[]', '[]',
                           json.dumps([fake_scene(c) for c in cards[4:]])])
    assert len(generate(cards, tmp_path / 'scenes.jsonl', lambda: backend, {})) == 8
    users = [user for _, user in backend.calls]
    assert len(users) == 4 and users[0] != users[1] and users[1] == users[2] == users[3]


def test_cross_batch_duplicate_retries_same_call(tmp_path):
    cards = sample_cards(4)
    cards += [dict(c, card=c['card'] + 4) for c in cards]
    first = [fake_scene(c) for c in cards[:4]]
    duplicates = [dict(s, card=s['card'] + 4) for s in first]
    last = [fake_scene(c) for c in cards[4:]]
    backend = FakeBackend([json.dumps(first), json.dumps(duplicates), json.dumps(last)])
    assert len(generate(cards, tmp_path / 'scenes.jsonl', lambda: backend, {})) == 8
    assert len(backend.calls) == 3


def test_usage_limit_wait_and_no_wait(batch, monkeypatch):
    cards, scenes = batch
    backend = FakeBackend([UsageLimit('limit'), json.dumps(scenes)])
    sleeps = []
    monkeypatch.setattr('synth.generate.time.sleep', sleeps.append)
    assert request_batch(cards, lambda: backend, [], wait_minutes=2)[0] == scenes
    assert sleeps == [60, 60]
    with pytest.raises(UsageLimit):
        request_batch(cards, lambda: FakeBackend([UsageLimit('limit')]), [])


def test_resume_completed_calls_and_changed_input(tmp_path):
    cards = sample_cards(12)
    out = tmp_path / 'scenes.jsonl'
    first = [fake_scene(c) for c in cards[:4]]
    backend = FakeBackend([json.dumps(first), UsageLimit('stopped')])
    with pytest.raises(UsageLimit):
        generate(cards, out, lambda: backend, {'backend': 'fake'})
    assert read_jsonl(out) == first
    backend2 = SynthFakeBackend()
    result = generate(cards, out, lambda: backend2, {'backend': 'fake'})
    assert len(result) == 12 and len(backend2.calls) == 2
    assert len(generate(cards, out, lambda: backend2, {'backend': 'fake'})) == 12
    assert len(backend2.calls) == 2
    cards[0]['topic'] += ' 변경'
    with pytest.raises(ValueError, match='configuration'):
        generate(cards, out, SynthFakeBackend, {'backend': 'fake'})


def test_concurrent_output_deterministic(tmp_path):
    cards = sample_cards(20)
    a = generate(cards, tmp_path / 'a.jsonl', SynthFakeBackend, {}, concurrency=1)
    b = generate(cards, tmp_path / 'b.jsonl', SynthFakeBackend, {}, concurrency=3)
    assert a == b
    assert [s['card'] for s in b] == list(range(1, 21))


def test_fake_demo_path_blocked(tmp_path):
    with pytest.raises(SystemExit) as error:
        main(['--cards', 'unused', '--backend', 'fake', '--out', str(tmp_path / 'demo/scenes.jsonl')])
    assert error.value.code == 2
    assert not (tmp_path / 'demo').exists()


def test_json_fence_and_no_trailing_prose():
    assert parse_response('```json\n[]\n```') == []
    with pytest.raises(ValueError):
        parse_response('[] extra')


def test_invalid_card_rejected(batch):
    cards, _ = batch
    cards[0]['n_turns'] = True
    with pytest.raises(ValueError):
        validate_cards(cards)


def test_backend_error_retries_same_batch_then_succeeds(batch, capsys):
    cards, scenes = batch
    backend = FakeBackend([BackendError('agy denied actions PRIVATE'), json.dumps(scenes)])
    result, used = request_batch(cards, lambda: backend, [])
    assert result == scenes and used == 2
    assert backend.calls[0] == backend.calls[1]
    log = capsys.readouterr().err
    assert '검증 실패 0, 백엔드 오류 1' in log
    assert 'PRIVATE' not in log


def test_validation_and_backend_errors_share_three_attempts(batch, capsys):
    cards, _ = batch
    backend = FakeBackend([BackendError('denied'), '[]', BackendError('denied')])
    with pytest.raises(ValueError, match='budget exhausted'):
        request_batch(cards, lambda: backend, [])
    assert len(backend.calls) == 3
    assert '검증 실패 1, 백엔드 오류 2' in capsys.readouterr().err


@pytest.mark.parametrize('concurrency', [1, 3])
def test_failed_batch_skipped_later_saved_and_only_failed_resumed(tmp_path, capsys, concurrency):
    cards = sample_cards(12)
    out = tmp_path / 'scenes.jsonl'
    calls = []

    class SelectiveBackend(SynthFakeBackend):
        def complete(self, system, user):
            ids = [c['card'] for c in json.loads(user)]
            calls.append(ids)
            if ids == [5, 6, 7, 8]:
                raise BackendError('agy denied actions PRIVATE')
            return super().complete(system, user)

    result = generate(cards, out, SelectiveBackend, {}, concurrency=concurrency)
    assert [s['card'] for s in result] == [1, 2, 3, 4, 9, 10, 11, 12]
    assert read_jsonl(out) == result
    assert calls.count([5, 6, 7, 8]) == 3
    assert len(calls) == 5
    meta = json.loads(out.with_suffix('.meta.json').read_text(encoding='utf-8'))
    assert meta['failed_cards'] == [5, 6, 7, 8]
    assert meta['failure_counts'] == dict(validation_errors=0, backend_errors=3)
    output = capsys.readouterr()
    assert '실패한 카드 번호: [5, 6, 7, 8]' in output.out
    assert 'PRIVATE' not in output.out + output.err

    backend = SynthFakeBackend()
    assert len(generate(cards, out, lambda: backend, {}, concurrency=concurrency)) == 12
    assert len(backend.calls) == 1
    assert [c['card'] for c in json.loads(backend.calls[0][1])] == [5, 6, 7, 8]
    assert json.loads(out.with_suffix('.meta.json').read_text(encoding='utf-8'))['failed_cards'] == []
    assert not list(tmp_path.rglob('*.lock'))
    assert not list(tmp_path.rglob('*.tmp'))


def test_main_partial_failure_exit_code_and_resume(tmp_path, monkeypatch):
    cards = sample_cards(8)
    source, out = tmp_path / 'cards.jsonl', tmp_path / 'scenes.jsonl'
    write_jsonl(source, cards)

    class DeniedBackend(SynthFakeBackend):
        def complete(self, system, user):
            if json.loads(user)[0]['card'] == 1:
                raise BackendError('denied')
            return super().complete(system, user)

    monkeypatch.setattr('synth.generate.SynthFakeBackend', DeniedBackend)
    argv = ['--cards', str(source), '--out', str(out), '--backend', 'fake']
    assert main(argv) == 4
    assert len(read_jsonl(out)) == 4
    monkeypatch.setattr('synth.generate.SynthFakeBackend', SynthFakeBackend)
    assert main(argv) == 0
    assert len(read_jsonl(out)) == 8


def test_legacy_prompt_metadata_resumes_existing_scenes(tmp_path):
    from synth.generate import JSON_ONLY, render_prompt
    cards = sample_cards(16)
    out = tmp_path / 'scenes.jsonl'
    saved = [fake_scene(c) for c in cards[:12]]
    write_jsonl(out, saved)
    prompt = render_prompt([])[0]
    assert prompt.startswith(JSON_ONLY)
    write_json(out.with_suffix('.meta.json'), dict(cards_hash=digest(cards),
               prompt_hash=digest(prompt.removeprefix(JSON_ONLY)), version=1))
    backend = SynthFakeBackend()
    assert len(generate(cards, out, lambda: backend, {})) == 16
    assert len(backend.calls) == 1
    assert read_jsonl(out)[:12] == saved


def test_changed_prompt_metadata_rejected(tmp_path):
    cards = sample_cards(4)
    out = tmp_path / 'scenes.jsonl'
    write_json(out.with_suffix('.meta.json'), dict(cards_hash=digest(cards),
               prompt_hash=digest('unrelated prompt'), version=1))
    with pytest.raises(ValueError, match='configuration'):
        generate(cards, out, SynthFakeBackend, {})


def test_all_batches_failed_can_resume_without_scene_file(tmp_path):
    cards = sample_cards(4)
    out = tmp_path / 'scenes.jsonl'
    backend = FakeBackend([BackendError('denied')] * 3)
    assert generate(cards, out, lambda: backend, {}) == []
    assert not out.exists()
    assert json.loads(out.with_suffix('.meta.json').read_text(encoding='utf-8'))['failed_cards'] == [1, 2, 3, 4]
    assert len(generate(cards, out, SynthFakeBackend, {})) == 4


def test_usage_limit_keeps_exit_code_and_is_not_backend_failure(tmp_path, monkeypatch):
    cards = tmp_path / 'cards.jsonl'
    out = tmp_path / 'scenes.jsonl'
    write_jsonl(cards, sample_cards(4))
    backend = FakeBackend([UsageLimit('limit')])
    monkeypatch.setattr('synth.generate.SynthFakeBackend', lambda: backend)
    assert main(['--cards', str(cards), '--out', str(out), '--backend', 'fake']) == 3
    assert len(backend.calls) == 1
    meta = json.loads(out.with_suffix('.meta.json').read_text(encoding='utf-8'))
    assert meta['failure_counts'] == dict(validation_errors=0, backend_errors=0)
    assert meta['failed_cards'] == []


def test_concurrent_duplicate_budget_exhaustion_skips_only_that_batch(tmp_path):
    cards = sample_cards(4)
    cards += [dict(c, card=c['card'] + 4) for c in cards[:4]]
    cards += [dict(c, card=c['card'] + 8) for c in cards[:4]]
    calls = []
    class DuplicateBackend(SynthFakeBackend):
        def complete(self, system, user):
            batch = json.loads(user)
            calls.append(batch[0]['card'])
            if batch[0]['card'] == 5:
                return json.dumps([dict(fake_scene(dict(c, card=c['card'] - 4)), card=c['card']) for c in batch])
            return super().complete(system, user)
    out = tmp_path / 'scenes.jsonl'
    result = generate(cards, out, DuplicateBackend, {}, concurrency=3)
    assert [s['card'] for s in result] == [1, 2, 3, 4, 9, 10, 11, 12]
    assert calls.count(5) == 3 and calls.count(1) == calls.count(9) == 1
    meta = json.loads(out.with_suffix('.meta.json').read_text(encoding='utf-8'))
    assert meta['failed_cards'] == [5, 6, 7, 8]
    assert meta['failure_counts'] == dict(validation_errors=3, backend_errors=0)
