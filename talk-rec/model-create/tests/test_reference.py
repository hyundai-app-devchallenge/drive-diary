"""Only invented blocks and scripted fake transports; no real corpus access."""
import json

import pytest

from eval import reference as r
from eval import real
from pipeline.label import BackendError, FakeBackend, UsageLimit


def blocks(n=8, video='abcdefghijk'):
    return [dict(id=f'yt:{video}#{i}', source=f'yt:{video}', start=i * 10., end=i * 10. + 5,
                 seconds=5., lines=[dict(start=i * 10., end=i * 10. + 5,
                                        text='지어낸 연습 대화 글이다')]) for i in range(n)]


def answer(n, verdict='story', **changes):
    return json.dumps([dict(dict(block=i, verdict=verdict, none_reason='', unsure=False,
                                 note='가족들이 추억을 서로 보탠다.'), **changes) for i in range(n)])


def logs(out):
    return r.Store.read_calls(out)


def run(bs, backend, out, **options):
    return r.run(bs, backend, out, system='지어낸 판정 지시문', repeat=options.pop('repeat', 1), **options)


def fixture_root(tmp_path):
    (tmp_path / 'data').mkdir()
    (tmp_path / 'data/real-split.tsv').write_text('video_id\tsplit\nabcdefghijk\tdev\nlmnopqrstuv\ttest\n', encoding='utf-8')
    (tmp_path / 'prompts').mkdir()
    (tmp_path / 'prompts/judge.md').write_text('지어낸 판정 지시문', encoding='utf-8')
    directory = real.run_directory(tmp_path, 'v1')
    real.write_objects(directory / 'blocks.jsonl', blocks() + blocks(1, 'lmnopqrstuv'))
    return directory


def test_batches_local_numbers_blank_lines_and_full_system(tmp_path):
    bs = blocks() + blocks(2, 'lmnopqrstuv')
    bs[0]['lines'] = [dict(text='가상 첫 줄'), dict(text=''), dict(text='가상 마지막 줄')]
    fake = FakeBackend([answer(7), answer(1), answer(2)])
    assert run(bs, fake, tmp_path / 'out') == 0
    assert [len(b) for b in r.batches(bs)] == [7, 1, 2]
    assert [call[0] for call in fake.calls] == ['지어낸 판정 지시문'] * 3
    assert '<block id="0">\n가상 첫 줄\n\n가상 마지막 줄\n</block>' in fake.calls[0][1]
    assert fake.calls[1][1].startswith('<block id="0">\n')
    assert '<block id="7">' not in fake.calls[0][1]
    assert len(logs(tmp_path / 'out')) == 3


@pytest.mark.parametrize('changes', [
    dict(block=True), dict(block=1), dict(verdict='keep'), dict(verdict=[]),
    dict(none_reason='other'), dict(none_reason='negative'), dict(none_reason='not_family'),
    dict(unsure=1), dict(note=''), dict(note=' '), dict(note='x' * 501),
    dict(note='첫 문장이다. 둘째 문장이다.'), dict(note='한 줄\n두 줄'),
    dict(note='한 줄\r두 줄'), dict(note='한 줄\u2028두 줄'), dict(note=None),
    dict(note='끝의 줄바꿈도 거부한다.\n'), dict(note='끝의 구분자도 거부한다.\u2028'),
    dict(note='첫 문장이다.둘째 문장이다.'),
    dict(extra=1),
])
def test_validation_rejects(changes):
    with pytest.raises(ValueError):
        r.validate(json.loads(answer(1, **changes)), blocks(1))


@pytest.mark.parametrize('items', [[], {}, [None], [dict(block=0)], json.loads(answer(2))])
def test_count_and_schema(items):
    with pytest.raises(ValueError):
        r.validate(items, blocks(1))


@pytest.mark.parametrize('reason', r.REASONS)
def test_none_reasons_accepted(reason):
    assert r.validate(json.loads(answer(1, 'none', none_reason=reason)), blocks(1))


def test_quote_from_another_block_drops_note():
    bs = blocks(2)
    bs[1]['lines'] = [dict(text='가상으로 만든 인용방지 연습문장')]
    items = json.loads(answer(2))
    items[0]['note'] = '일부 인용방지연습 문장 내용을 담는다.'
    assert r.validate(items, bs)[0]['note'] == ''


def test_short_shared_phrase_is_not_a_quote():
    bs = blocks(1)
    bs[0]['lines'] = [dict(text='가상의 아이와 아빠가 하는 대화')]
    items = json.loads(answer(1))
    items[0]['note'] = '아빠가 하는 질문에 아이가 답한다.'
    assert r.validate(items, bs)


def test_backend_and_validation_retry_same_batch_continue_and_resume(tmp_path):
    bs, out = blocks(15), tmp_path / 'out'
    fake = FakeBackend([answer(7), BackendError('PRIVATE_SENTINEL'), '[]',
                        BackendError('PRIVATE_SENTINEL'), answer(1)])
    ticks = iter(range(30))
    assert run(bs, fake, out, retries=2, clock=lambda: next(ticks)) == 1
    assert len(fake.calls) == 5
    assert fake.calls[1][1] == fake.calls[2][1] == fake.calls[3][1]
    assert fake.calls[4][1] != fake.calls[3][1]
    assert [c['status'] for c in logs(out)] == ['ok', 'backend_error', 'validation_error', 'backend_error', 'ok']
    assert logs(out)[3]['exhausted']
    assert len(real.read_objects(out / 'r1/predictions.jsonl')) == 8
    assert all(c['seconds'] == 1 for c in logs(out))
    again = FakeBackend([answer(7)])
    assert run(bs, again, out) == 0
    assert len(again.calls) == 1 and again.calls[0][1] == fake.calls[1][1]
    assert len(real.read_objects(out / 'r1/predictions.jsonl')) == 15
    persisted = ''.join(p.read_text(encoding='utf-8') for p in out.rglob('*') if p.is_file())
    assert 'PRIVATE_SENTINEL' not in persisted
    assert bs[0]['lines'][0]['text'] not in persisted
    assert '추억을 서로' not in persisted


def test_limit_clean_stop_independent_repeats_and_resume(tmp_path):
    bs, out = blocks(1), tmp_path / 'out'
    fake = FakeBackend([answer(1), UsageLimit('PRIVATE_SENTINEL', {'input_tokens': 12})])
    assert run(bs, fake, out, repeat=3) == 3
    assert [c['repeat'] for c in logs(out)] == [1, 2]
    assert logs(out)[1]['token_usage'] == {'input_tokens': 12}
    again = FakeBackend([answer(1, 'moment'), answer(1, 'none')])
    assert run(bs, again, out, repeat=3) == 0
    assert len(again.calls) == 2
    assert [real.read_objects(out / f'r{i}/predictions.jsonl')[0]['verdict']
            for i in (1, 2, 3)] == ['story', 'moment', 'none']
    (out / 'r1/predictions.jsonl').unlink()
    assert run(bs, FakeBackend([]), out, repeat=3) == 0
    assert (out / 'r1/predictions.jsonl').exists()


def test_limit_wait_not_retry_and_all_call_costs(tmp_path):
    out, sleeps = tmp_path / 'out', []
    fake = FakeBackend([UsageLimit('private', {'total_tokens': 7}), answer(1)])
    assert run(blocks(1), fake, out, retries=0, wait_minutes=1.2, sleep=sleeps.append) == 0
    assert sleeps == [30, 30, 12]
    assert logs(out)[0]['wait_seconds'] == 72
    assert logs(out)[0]['exhausted'] is False


def test_interrupt_records_call_and_resume(tmp_path):
    out = tmp_path / 'out'
    with pytest.raises(KeyboardInterrupt):
        run(blocks(8), FakeBackend([answer(7), KeyboardInterrupt()]), out)
    assert logs(out)[-1]['status'] == 'interrupted'
    fake = FakeBackend([answer(1)])
    assert run(blocks(8), fake, out) == 0
    assert len(fake.calls) == 1


@pytest.mark.parametrize('changed', ['input', 'prompt', 'model', 'repeat', 'size', 'backend', 'split'])
def test_signature_mismatch_before_any_call(tmp_path, changed):
    bs, out = blocks(1), tmp_path / 'out'
    assert run(bs, FakeBackend([answer(1)]), out) == 0
    options = dict(system='지어낸 판정 지시문', repeat=1)
    if changed == 'input':
        bs[0]['lines'][0]['text'] = '다른 가상의 대화'
    elif changed == 'prompt':
        options['system'] = '다른 지시문'
    elif changed == 'backend':
        options['backend_config'] = {'command': 'other'}
    else:
        options[changed] = {'model': 'other', 'repeat': 2, 'size': 6, 'split': 'test'}[changed]
    fake = FakeBackend([])
    with pytest.raises(ValueError, match='signature'):
        r.run(bs, fake, out, **options)
    assert not fake.calls


def test_fake_cli_judge_and_resume_never_load_labels(tmp_path, monkeypatch):
    fixture_root(tmp_path)
    monkeypatch.setattr(r, 'ROOT', tmp_path)
    monkeypatch.setattr(real, 'load_labels', lambda *a, **kw: pytest.fail('human labels opened'))
    args = ['judge', '--run', 'v1', '--split', 'dev', '--repeat', '3',
            '--backend', 'fake', '--model', 'gemini-3.8-flash-low', '--out-name', 'fake-v3']
    assert r.main(args) == r.main(args) == 0
    out = tmp_path / '.local/real/v1/reference/fake-v3'
    assert len(logs(out)) == 6
    assert all(len(real.read_objects(out / f'r{i}/predictions.jsonl')) == 8 for i in (1, 2, 3))
    args[args.index('dev')] = 'test'
    args[-1] = 'fake-test'
    assert r.main(args) == 0  # No human labels read; --open-test is a comparison gate.


@pytest.mark.parametrize('name', ['../bad', 'a/b', 'CON', '', 'trailing.', 'a\\b'])
def test_output_names_cannot_escape(tmp_path, name):
    with pytest.raises(ValueError):
        r.output_directory(tmp_path, tmp_path / '.local/real/v1', name)


def test_input_selection_sort_and_schema(tmp_path):
    directory = fixture_root(tmp_path)
    assert len(r.load_blocks(tmp_path, directory, 'dev')) == 8
    assert len(r.load_blocks(tmp_path, directory, 'test')) == 1
    bs = blocks(1)
    bs[0]['lines'][0]['text'] = '한 줄\n잘못된 추가 줄'
    real.write_objects(directory / 'blocks.jsonl', bs)
    with pytest.raises(ValueError, match='blocks.jsonl:1'):
        r.load_blocks(tmp_path, directory, 'dev')


def test_error_output_only_type_and_location(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(r, 'ROOT', tmp_path)
    def broken(*args):
        raise ValueError('PRIVATE_SENTINEL')
    monkeypatch.setattr(r, 'load_blocks', broken)
    assert r.main(['judge', '--out-name', 'safe', '--backend', 'fake']) == 2
    output = capsys.readouterr().err
    assert 'ValueError:' in output and 'test_reference.py:' in output
    assert 'PRIVATE_SENTINEL' not in output


def test_usage_whitelist():
    assert r.clean_usage({'input_tokens': 3, 'total_tokens': 6, 'private_tokens': 'PRIVATE',
                          'output_tokens': True, 'prompt_tokens': -1}) == {'input_tokens': 3, 'total_tokens': 6}


def test_success_usage_and_repeat_after_failure(tmp_path):
    class MeteredFake(FakeBackend):
        def complete(self, system, user):
            self.usage = {'input_tokens': 17, 'output_tokens': 3}
            return super().complete(system, user)
    out = tmp_path / 'out'
    fake = MeteredFake([BackendError('private'), answer(1), answer(1, 'moment')])
    assert run(blocks(1), fake, out, repeat=3, retries=0) == 1
    assert [c['repeat'] for c in logs(out)] == [1, 2, 3]
    assert all(c['token_usage'] == {'input_tokens': 17, 'output_tokens': 3} for c in logs(out))
    assert len(real.read_objects(out / 'r2/predictions.jsonl')) == 1


def test_advisory_lock_rejects_second_writer_and_releases(tmp_path):
    out = tmp_path / 'out'
    with r.locked(out):
        with pytest.raises(OSError):
            with r.locked(out):
                pytest.fail('second lock succeeded')
    assert run(blocks(1), FakeBackend([answer(1)]), out) == 0


def test_command_and_agy_factories_do_not_call_transport(monkeypatch):
    args = r.parser().parse_args(['judge', '--out-name', 'x', '--backend', 'agy'])
    backend = r.make_backend(args)
    assert backend.model == 'gemini-3.8-flash' and backend.effort == 'low'
    args.backend = 'command'
    with pytest.raises(ValueError):
        r.make_backend(args)
    seen = []
    monkeypatch.setattr(r, 'CommandBackend', lambda *values: seen.append(values))
    args.command = 'fake-executable {model}'
    r.make_backend(args)
    assert seen == [('fake-executable {model}', 'gemini-3.8-flash-low', 'low', 600)]
