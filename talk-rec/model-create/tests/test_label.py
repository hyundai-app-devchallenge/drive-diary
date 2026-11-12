"""T06 synthetic fixtures: no network, transcript snapshots, or real CLI calls."""
import copy
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import threading
from types import SimpleNamespace

import pytest

from common.schema import validate_label
from pipeline import label as l


@pytest.fixture
def tmp_path(tmp_path):
    # Nested outputs + SHA-256 names exceed MAX_PATH with long external basetemp.
    return Path('\\\\?\\' + str(tmp_path.resolve())) if os.name == 'nt' else tmp_path


def turns(n, source='yt:fixture'):
    return [dict(source=source, utt=i, start=i * 3, end=i * 3 + 1,
                 text=f'가상 발화 {i}', gap=0 if i == 0 else 2,
                 asr='human', speaker=None) for i in range(n)]


def answer(chunk, story=1, note='판정 근거'):
    return json.dumps([dict(utt=t['utt'], story=story, kind=['recall'] if story >= 2 else [],
                            exclude=[], skip=False, note=note) for t in chunk.turns], ensure_ascii=False)


def save(path, records):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(l.jsonl(records), encoding='utf-8')


@pytest.fixture
def templates():
    return l.prompt_templates()


def store(tmp_path):
    return l.Store(tmp_path / 'labels', {'test': True})


def execute(clips, storage, backend, templates, **kwargs):
    return l.run_clips(clips, storage, lambda: backend, *templates, 'llm:fake:fixture:high', **kwargs)


def labels(storage):
    return [r for e in storage.entries.values() for r in e['labels']]


def test_chunk_overlap_and_label_precedence(tmp_path, templates):
    chunks = list(l.chunk_clip('yt:fixture', 7, turns(85)))
    assert [len(c.turns) for c in chunks] == [40, 40, 11]
    assert [c.overlap for c in chunks] == [0, 3, 3]
    assert [t['utt'] for t in chunks[1].turns[:3]] == [37, 38, 39]
    assert chunks[1].turns[-3:] == chunks[2].turns[:3]
    backend = l.FakeBackend([answer(c, story=i + 1) for i, c in enumerate(chunks)])
    storage = store(tmp_path)
    assert execute([chunks], storage, backend, templates) == 0
    records = {r['id']: r for r in labels(storage)}
    assert len(records) == 85
    assert records['yt:fixture#39']['story'] == 1
    assert records['yt:fixture#40']['story'] == 2
    assert records['yt:fixture#76']['story'] == 2
    assert records['yt:fixture#77']['story'] == 3
    for record in records.values():
        validate_label(record)


@pytest.mark.parametrize('n,lengths', [(0, []), (1, [1]), (40, [40]), (41, [40, 4]), (77, [40, 40])])
def test_chunk_boundaries(n, lengths):
    assert [len(c.turns) for c in l.chunk_clip('s', 0, turns(n))] == lengths


def test_exact_prompt_template_and_gaps(templates):
    system, template = templates
    assert '{{RUBRIC}}' not in system and '{{' not in system
    assert (l.ROOT / 'rubric.md').read_text(encoding='utf-8') in system
    rows = turns(3)
    rows[1]['gap'], rows[2]['gap'] = 20.01, -0.5
    chunk = l.Chunk('yt:fixture', 0, 0, tuple(rows), 0)
    assert l.render_prompt(chunk, template) == '<clip source="youtube">\n[0] (+0) 가상 발화 0\n[1] (+20.01) 가상 발화 1\n[2] (-0.5) 가상 발화 2\n</clip>'
    assert '<clip source="corpus">' in l.render_prompt(l.Chunk('corpus:fixture', 0, 0, tuple(rows), 0), template)


def input_fixture(tmp_path, n=45):
    rows = turns(n)
    windows = [dict(id=f"{r['source']}#{r['utt']}", source=r['source'], split='train',
                    clip=0 if r['utt'] < 41 else 1, context=[], current=r['text']) for r in rows]
    save(tmp_path / 'windows/train.jsonl', list(reversed(windows)))
    save(tmp_path / 'utterances/opaque-file.jsonl', [{k: v for k, v in r.items() if k != 'gap'} for r in reversed(rows)])
    return tmp_path / 'windows', tmp_path / 'utterances'


def test_load_authoritative_source_order_timing_and_filters(tmp_path):
    windows, utterances = input_fixture(tmp_path)
    clips = l.load_clips(windows, utterances, 'train')
    assert len(clips) == 2 and len(clips[0]) == 2
    assert clips[0][0].turns[0]['utt'] == 0
    assert clips[1][0].turns[0]['utt'] == 41
    assert clips[1][0].turns[0]['gap'] == 2
    assert len(l.load_clips(windows, utterances, 'train', 'yt:fixture', 1)) == 1
    assert l.load_clips(windows, utterances, 'train', 'absent') == []
    assert l.load_clips(windows, utterances, 'train', limit=0) == []


@pytest.mark.parametrize('corruption', ['window_duplicate', 'utterance_duplicate', 'missing', 'text', 'split', 'bad_json'])
def test_input_validation(tmp_path, corruption):
    windows, utterances = input_fixture(tmp_path, 2)
    w = l.read_jsonl(windows / 'train.jsonl')
    u = l.read_jsonl(utterances / 'opaque-file.jsonl')
    if corruption == 'window_duplicate':
        w.append(w[0])
    elif corruption == 'utterance_duplicate':
        u.append(u[0])
    elif corruption == 'missing':
        u.pop()
    elif corruption == 'text':
        w[0]['current'] = 'different'
    elif corruption == 'split':
        w[0]['split'] = 'dev'
    save(windows / 'train.jsonl', w)
    save(utterances / 'opaque-file.jsonl', u)
    if corruption == 'bad_json':
        (windows / 'train.jsonl').write_text('{secret transcript', encoding='utf-8')
    with pytest.raises(ValueError) as error:
        l.load_clips(windows, utterances, 'train')
    assert 'secret transcript' not in str(error.value)


@pytest.mark.parametrize('wrap', ['{}', '```json\n{}\n```', 'stray [not json] text\n{}\nafter', '설명\n```\n{}\n```\n끝'])
def test_dirty_fenced_json(wrap):
    chunk = next(l.chunk_clip('yt:fixture', 0, turns(2)))
    assert len(l.validate_answer(l.parse_answer(wrap.format(answer(chunk))), chunk, 'llm:fake:m:high')[0]) == 2


@pytest.mark.parametrize('text', ['', '{}', '[1,2]', '[{"utt": bad}]'])
def test_non_array_invalid_json(text):
    with pytest.raises(ValueError):
        l.parse_answer(text)


def test_dirty_json_with_other_arrays_and_gap_precision(templates):
    chunk = next(l.chunk_clip('yt:fixture', 0, turns(1)))
    chunk.turns[0]['gap'] = 20.000001
    assert '(+20.000001)' in l.render_prompt(chunk, templates[1])
    text = 'intro [] example [{"utt":999}] actual ' + answer(chunk) + ' trailing []'
    assert l.parse_answer(text, [0]) == json.loads(answer(chunk))


@pytest.mark.parametrize('change', [
    {'story': True}, {'story': -1}, {'story': 4}, {'story': 1.0}, {'story': '1'},
    {'kind': 'recall'}, {'kind': ['unknown']}, {'kind': ['family_fact', 'family_fact']},
    {'story': 0, 'kind': ['family_fact']}, {'story': 1, 'kind': ['recall']},
    {'story': 1, 'kind': ['family_fact', 'recall']}, {'story': 2}, {'story': 3},
    {'exclude': 'external'}, {'exclude': ['unknown']}, {'exclude': ['optout', 'optout']},
    {'skip': 1}, {'skip': 'false'}, {'skip': True},
    {'skip': True, 'story': 0, 'exclude': ['external']},
    {'skip': True, 'story': 0, 'kind': ['family_fact']},
    {'utt': True}, {'utt': 99}, {'note': None}, {'id': 'unexpected'}, {'labeler': 'unexpected'}
])
def test_each_validation_rule(change):
    chunk = next(l.chunk_clip('yt:fixture', 0, turns(1)))
    items = json.loads(answer(chunk))
    items[0].update(change)
    with pytest.raises(ValueError):
        l.validate_answer(items, chunk, 'llm:fake:m:high')


def test_counts_order_required_fields_and_valid_independent_excludes():
    chunk = next(l.chunk_clip('yt:fixture', 0, turns(2)))
    items = json.loads(answer(chunk, 3))
    for invalid in [items[:1], items + [items[0]], list(reversed(items)), [items[0], items[0]]]:
        with pytest.raises(ValueError):
            l.validate_answer(invalid, chunk, 'llm:fake:m:high')
    for field in items[0]:
        missing = copy.deepcopy(items)
        missing[0].pop(field)
        with pytest.raises(ValueError):
            l.validate_answer(missing, chunk, 'llm:fake:m:high')
    items[0]['exclude'] = ['sensitive', 'optout']
    assert l.validate_answer(items, chunk, 'llm:fake:m:high')[0][0]['story'] == 3
    items[0].update(story=1, kind=['family_fact'])
    assert l.validate_answer(items, chunk, 'llm:fake:m:high')[0][0]['kind'] == ['family_fact']


def test_corpus_skip_warning_and_unclear(tmp_path, templates, capsys):
    chunk = next(l.chunk_clip('corpus:fixture', 0, turns(1, 'corpus:fixture')))
    items = json.loads(answer(chunk, 0, '기준 불명확: 검토 필요'))
    items[0]['skip'] = True
    backend = l.FakeBackend([json.dumps(items)])
    storage = store(tmp_path)
    assert execute([[chunk]], storage, backend, templates) == 0
    assert 'Warning: corpus skip' in capsys.readouterr().err
    assert l.read_jsonl(storage.directory / 'unclear.jsonl') == labels(storage)
    assert storage.entries[chunk.id]['log']['corpus_skip_ids'] == ['corpus:fixture#0']


def test_retry_feedback_then_success_and_failure_continues(tmp_path, templates, capsys):
    chunks = list(l.chunk_clip('yt:fixture', 0, turns(41)))
    backend = l.FakeBackend(['bad', answer(chunks[0]), 'bad', 'bad', 'bad'])
    storage = store(tmp_path)
    assert execute([chunks], storage, backend, templates) == 1
    logs = [storage.entries[c.id]['log'] for c in chunks]
    assert [r['attempts'] for r in logs] == [2, 3]
    assert [r['validation_errors'] for r in logs] == [1, 3]
    assert 'Validation error: answer must contain' in backend.calls[1][1]
    assert len(labels(storage)) == 40
    captured = capsys.readouterr()
    for text in [captured.out, captured.err, (storage.directory / 'run.jsonl').read_text(encoding='utf-8')]:
        assert '가상 발화' not in text
    # Failed chunk is retried on a later run, completed chunk is not.
    recovered = l.Store(storage.directory, {'test': True})
    replacement = l.FakeBackend()
    assert execute([chunks], recovered, replacement, templates) == 0
    assert len(replacement.calls) == 1 and len(labels(recovered)) == 41
    assert recovered.entries[chunks[1].id]['log']['validation_errors'] == 3
    assert recovered.entries[chunks[1].id]['log']['attempts'] == 4


def test_failed_chunk_does_not_block_later_chunk(tmp_path, templates):
    chunks = list(l.chunk_clip('yt:fixture', 0, turns(41)))
    storage = store(tmp_path)
    assert execute([chunks], storage, l.FakeBackend(['bad', answer(chunks[1])]), templates, retries=0) == 1
    assert [r['id'] for r in labels(storage)] == ['yt:fixture#40']


def test_resume_after_interruption_no_duplicates(tmp_path, templates):
    chunks = list(l.chunk_clip('yt:fixture', 0, turns(85)))
    storage = store(tmp_path)
    with pytest.raises(KeyboardInterrupt):
        execute([chunks], storage, l.FakeBackend([answer(chunks[0]), KeyboardInterrupt()]), templates)
    assert len(labels(storage)) == 40
    resumed = l.Store(storage.directory, {'test': True})
    backend = l.FakeBackend()
    assert execute([chunks], resumed, backend, templates) == 0
    assert len(backend.calls) == 2
    records = l.read_jsonl(resumed.directory / 'yt%3Afixture.jsonl')
    assert len(records) == len({r['id'] for r in records}) == 85
    assert len(l.read_jsonl(resumed.directory / 'run.jsonl')) == 3
    assert execute([chunks], resumed, l.FakeBackend([]), templates) == 0


def test_atomic_commit_recovers_views_and_unclear(tmp_path, templates, monkeypatch):
    chunks = list(l.chunk_clip('yt:fixture', 0, turns(1)))
    storage = store(tmp_path)
    def interrupt():
        raise OSError('simulated crash between chunk commit and materialization')
    monkeypatch.setattr(storage, 'materialize', interrupt)
    with pytest.raises(OSError):
        execute([chunks], storage, l.FakeBackend([answer(chunks[0], note='기준 불명확')]), templates)
    recovered = l.Store(storage.directory, {'test': True})
    assert execute([chunks], recovered, l.FakeBackend([]), templates) == 0
    assert len(l.read_jsonl(recovered.directory / 'unclear.jsonl')) == 1
    assert len(l.read_jsonl(recovered.directory / 'run.jsonl')) == 1
    assert not list(recovered.directory.rglob('.tmp-*'))


def test_atomic_replace_failure_preserves_previous(tmp_path, monkeypatch):
    path = tmp_path / 'output'
    l.atomic_text(path, 'previous')
    def fail(*args):
        raise OSError('interruption')
    monkeypatch.setattr(l.os, 'replace', fail)
    with pytest.raises(OSError):
        l.atomic_text(path, 'new')
    assert path.read_text(encoding='utf-8') == 'previous'
    assert not list(tmp_path.glob('.tmp-*'))


def test_changed_input_and_configuration_rejected(tmp_path, templates):
    chunk = next(l.chunk_clip('yt:fixture', 0, turns(1)))
    storage = store(tmp_path)
    execute([[chunk]], storage, l.FakeBackend(), templates)
    with pytest.raises(ValueError, match='configuration changed'):
        l.Store(storage.directory, {'test': False})
    changed = turns(1)
    changed[0]['text'] = 'changed'
    with pytest.raises(ValueError, match='input changed'):
        execute([[l.Chunk(chunk.source, 0, 0, tuple(changed), 0)]], storage, l.FakeBackend(), templates)


def test_rate_limit_exit_and_resume(tmp_path, templates, capsys):
    chunks = list(l.chunk_clip('yt:fixture', 0, turns(85)))
    storage = store(tmp_path)
    backend = l.FakeBackend([answer(chunks[0]), l.UsageLimit('limit')])
    assert execute([chunks], storage, backend, templates) == l.LIMIT_EXIT == 3
    assert len(backend.calls) == 2 and len(labels(storage)) == 40
    assert 'Resume with the same command' in capsys.readouterr().err
    resumed = l.Store(storage.directory, {'test': True})
    assert execute([chunks], resumed, l.FakeBackend(), templates) == 0
    assert len(labels(resumed)) == 85


def test_wait_on_limit_does_not_consume_validation_retries(tmp_path, templates):
    chunk = next(l.chunk_clip('yt:fixture', 0, turns(1)))
    backend = l.FakeBackend([l.UsageLimit('limit'), answer(chunk)])
    class InstantStop(threading.Event):
        def wait(self, timeout=None):
            assert timeout <= 30
            return False
    storage = store(tmp_path)
    assert execute([[chunk]], storage, backend, templates, retries=0, wait_minutes=2, stop=InstantStop()) == 0
    assert storage.entries[chunk.id]['log']['attempts'] == 2
    assert storage.entries[chunk.id]['log']['validation_errors'] == 0


@pytest.mark.parametrize('message', ['usage limit reached', 'rate_limit_exceeded', 'Too many requests', 'HTTP 429', 'subscription usage limit', 'quota exhausted', 'You exceeded your current quota', 'RESOURCE_EXHAUSTED', '사용 한도 초과'])
def test_cli_limit_detection_and_redaction(tmp_path, message):
    code = f'import sys; print({message!r}); print("PRIVATE TRANSCRIPT",file=sys.stderr); sys.exit(1)'
    with pytest.raises(l.UsageLimit) as error:
        l.run_process([sys.executable, '-c', code], '비공개 입력', tmp_path, 10)
    assert 'PRIVATE' not in str(error.value)


def test_cli_other_error_redaction_and_timeout(tmp_path, monkeypatch):
    with pytest.raises(l.BackendError, match='home directory unavailable'):
        l.run_process([sys.executable, '-c', 'import sys; print("Could not find home directory: PRIVATE"); sys.exit(1)'], '비공개', tmp_path, 10)
    with pytest.raises(l.BackendError, match='could not be started'):
        l.run_process(['nonexistent-labeler-fixture'], '비공개', tmp_path, 10)
    def timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired('private', 1, output='PRIVATE')
    monkeypatch.setattr(l.subprocess, 'run', timeout)
    with pytest.raises(l.BackendError, match='CLI timeout'):
        l.run_process([], '비공개', tmp_path, 1)


def test_codex_flags_final_message_usage_utf8_empty_cwd(monkeypatch):
    captured = {}
    monkeypatch.setattr(l.shutil, 'which', lambda name: 'codex.exe' if name == 'codex.exe' else None)
    def process(argv, **kwargs):
        captured.update(argv=argv, **kwargs)
        cwd = Path(kwargs['cwd'])
        assert cwd.is_dir() and list(cwd.iterdir()) == []
        assert kwargs['encoding'] == 'utf-8' and kwargs['env']['PYTHONIOENCODING'] == 'utf-8'
        assert kwargs['shell'] is False and kwargs['capture_output'] is True
        Path(argv[argv.index('--output-last-message') + 1]).write_text('최종 응답', encoding='utf-8')
        return SimpleNamespace(returncode=0, stdout='{"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":20,"output_tokens":10}}\n', stderr='private progress')
    monkeypatch.setattr(l.subprocess, 'run', process)
    backend = l.CodexBackend('gpt-6-luna')
    assert backend.complete('시스템', '사용자') == '최종 응답'
    argv = captured['argv']
    assert argv[1] == 'exec' and argv[-1] == '-'
    assert argv[argv.index('--sandbox') + 1] == 'read-only'
    assert argv[argv.index('--model') + 1] == 'gpt-6-luna'
    assert 'model_reasoning_effort="high"' in argv
    assert any('Do not use tools' in value for value in argv)
    assert all(flag in argv for flag in ['--skip-git-repo-check', '--ephemeral', '--ignore-user-config', '--ignore-rules', '--json'])
    assert '--dangerously-bypass-approvals-and-sandbox' not in argv
    assert captured['input'] == '시스템\n\n사용자'
    assert backend.usage == dict(input_tokens=100, cached_input_tokens=20, output_tokens=10)
    assert not Path(captured['cwd']).exists()


@pytest.mark.parametrize('tool', ['command_execution', 'mcp_tool_call', 'web_search', 'file_change'])
def test_codex_rejects_tool_calls(monkeypatch, tool):
    monkeypatch.setattr(l.shutil, 'which', lambda name: 'codex.exe')
    monkeypatch.setattr(l, 'run_process', lambda *args: SimpleNamespace(stdout=json.dumps(dict(type='item.completed', item={'type': tool})), stderr=''))
    with pytest.raises(l.BackendError, match='tool use'):
        l.CodexBackend('gpt-6-luna').complete('system', 'user')


def test_command_backend_utf8_and_template():
    code = 'import sys; s=sys.stdin.read(); assert "시스템" in s and "사용자" in s; print("한글 응답"); print("tokens used\\n123",file=sys.stderr)'
    args = [sys.executable, '-c', code, '{model}', '{effort}']
    command = subprocess.list2cmdline(args) if os.name == 'nt' else shlex.join(args)
    backend = l.CommandBackend(command, 'fixture-model', 'medium')
    assert backend.argv[-2:] == ['fixture-model', 'medium']
    assert backend.complete('시스템', '사용자').strip() == '한글 응답'
    assert backend.usage == {'total_tokens': 123}


def test_npm_codex_launcher_avoids_batch_quoting(tmp_path, monkeypatch):
    command = tmp_path / 'codex.cmd'
    command.write_text('@echo off', encoding='utf-8')
    script = tmp_path / 'node_modules/@openai/codex/bin/codex.js'
    script.parent.mkdir(parents=True)
    script.write_text('// npm launcher fixture', encoding='utf-8')
    found = {'codex.cmd': str(command), 'node.exe': 'node.exe'}
    monkeypatch.setattr(l.shutil, 'which', lambda name: found.get(name))
    argv = l.CodexBackend('gpt-6-luna').command(tmp_path, tmp_path / 'final.txt')
    assert argv[:3] == ['node.exe', str(script), 'exec']
    assert any('developer_instructions="' in value and 'Do not use tools' in value for value in argv)


def test_concurrency_separate_backends_and_unique_ids(tmp_path, templates):
    clips = [list(l.chunk_clip(f'yt:fixture{i}', i, turns(41, f'yt:fixture{i}'))) for i in range(5)]
    storage = store(tmp_path)
    assert l.run_clips(clips, storage, l.FakeBackend, *templates, 'llm:fake:m:high', concurrency=3) == 0
    assert len(labels(storage)) == 205
    assert len({r['id'] for r in labels(storage)}) == 205
    assert len(l.read_jsonl(storage.directory / 'run.jsonl')) == 10


def test_main_dry_run_cli_fake_resume_and_run_tag(tmp_path, monkeypatch, templates, capsys):
    windows, utterances = input_fixture(tmp_path)
    monkeypatch.setattr(l, 'ROOT', tmp_path)
    monkeypatch.setattr(l, 'prompt_templates', lambda: templates)
    args = ['--backend', 'fake', '--windows', str(windows), '--utterances', str(utterances), '--limit-clips', '1']
    monkeypatch.setattr(l.FakeBackend, 'complete', lambda *args: pytest.fail('dry run called backend'))
    assert l.main(args + ['--dry-run']) == 0
    prompts = tmp_path / '.local/labels/fake-deterministic-high/prompts'
    assert len(list(prompts.glob('*.txt'))) == 2
    assert '가상 발화' in next(prompts.glob('*.txt')).read_text(encoding='utf-8')
    assert not (prompts.parent / 'run.jsonl').exists()
    monkeypatch.undo()
    monkeypatch.setattr(l, 'ROOT', tmp_path)
    monkeypatch.setattr(l, 'prompt_templates', lambda: templates)
    assert l.main(args) == 0
    assert l.main(args) == 0
    assert l.main(args + ['--run-tag', 'rerun']) == 0
    assert (prompts.parent.parent / 'fake-deterministic-high-rerun/run.jsonl').exists()
    assert len(l.read_jsonl(prompts.parent / 'yt%3Afixture.jsonl')) == 41
    assert '가상 발화' not in capsys.readouterr().err


def test_main_limit_distinct_exit(tmp_path, monkeypatch, templates, capsys):
    windows, utterances = input_fixture(tmp_path, 2)
    monkeypatch.setattr(l, 'ROOT', tmp_path)
    monkeypatch.setattr(l, 'prompt_templates', lambda: templates)
    # Factory replacement is independent of the real backend and never invokes a CLI.
    backend = SimpleNamespace(complete=lambda *args: (_ for _ in ()).throw(l.UsageLimit('limit')), usage={})
    monkeypatch.setattr(l, 'FakeBackend', lambda: backend)
    assert l.main(['--backend', 'fake', '--windows', str(windows), '--utterances', str(utterances)]) == 3
    assert 'Resume with the same command' in capsys.readouterr().err


@pytest.mark.parametrize('args', [['--concurrency', '0'], ['--retries', '-1'], ['--limit-clips', '-1'], ['--wait-on-limit', '0'], ['--wait-on-limit', 'nan'], ['--timeout', '0'], ['--timeout', 'inf'], ['--backend', 'command']])
def test_bad_cli_arguments(args):
    with pytest.raises(SystemExit) as error:
        l.main(args)
    assert error.value.code == 2


def test_reserved_source_filename_does_not_overwrite_logs(tmp_path, templates):
    storage = store(tmp_path)
    for name in ['run', 'unclear']:
        chunk = next(l.chunk_clip(name, 0, turns(1, name)))
        assert execute([[chunk]], storage, l.FakeBackend(), templates) == 0
    assert len(l.read_jsonl(storage.directory / 'run.jsonl')) == 2
    assert len(l.read_jsonl(storage.directory / 'unclear.jsonl')) == 0
    assert l.read_jsonl(storage.directory / '%72un.jsonl')[0]['id'] == 'run#0'
