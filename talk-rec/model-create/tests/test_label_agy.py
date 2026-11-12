"""Synthetic agy byte fixtures and subprocess contract; no live CLI calls."""
import json
import os
from pathlib import Path
import subprocess
import threading
from types import SimpleNamespace

import pytest

from pipeline import label as l


@pytest.fixture
def tmp_path(tmp_path):
    # Nested outputs + SHA-256 names exceed MAX_PATH with long external basetemp.
    return Path('\\\\?\\' + str(tmp_path.resolve())) if os.name == 'nt' else tmp_path


# Synthetic envelope fixtures frozen as UTF-8 and Windows Korean ANSI bytes.
UTF8 = b'{"status":"SUCCESS","response":"\xed\x85\x8c\xec\x8a\xa4\xed\x8a\xb8","usage":{"input_tokens":11,"output_tokens":3,"thinking_tokens":2,"cache_read_tokens":4,"total_tokens":20}}'
CP949 = b'{"status":"SUCCESS","response":"\xc5\xd7\xbd\xba\xc6\xae","usage":{"input_tokens":11,"output_tokens":3,"thinking_tokens":2,"cache_read_tokens":4,"total_tokens":20}}'
USAGE = dict(input_tokens=11, output_tokens=3, thinking_tokens=2, cache_read_tokens=4, total_tokens=20)


def result(monkeypatch, stdout=UTF8, stderr=b'', code=0):
    monkeypatch.setattr(l.subprocess, 'run', lambda *a, **k: SimpleNamespace(stdout=stdout, stderr=stderr, returncode=code))


@pytest.mark.parametrize('fixture', [UTF8, CP949])
def test_byte_fixtures_strict_utf8_then_ansi(monkeypatch, fixture):
    monkeypatch.setattr(l, 'system_ansi_encoding', lambda: 'cp949')
    result(monkeypatch, fixture)
    backend = l.AgyBackend(path='agy.exe')
    assert backend.complete('system', 'user') == '테스트'
    assert backend.usage == USAGE


def test_utf8_is_preferred_to_ansi(monkeypatch):
    monkeypatch.setattr(l, 'system_ansi_encoding', lambda: 'cp1252')
    assert l.decode_agy(UTF8) == UTF8.decode('utf-8')


def test_explicit_empty_denied_actions_is_accepted(monkeypatch):
    result(monkeypatch, json.dumps(dict(json.loads(UTF8), denied_actions=[])).encode())
    assert l.AgyBackend(path='agy.exe').complete('system', 'user') == '테스트'


@pytest.mark.parametrize('data', [b'\x81', b'\xff', b'\xef\xbf\xbd'])
def test_undecodable_or_replacement_bytes(monkeypatch, data):
    monkeypatch.setattr(l, 'system_ansi_encoding', lambda: 'cp949')
    result(monkeypatch, data)
    with pytest.raises(l.BackendError, match='strict UTF-8|replacement'):
        l.AgyBackend(path='agy.exe').complete('system', 'user')


@pytest.mark.parametrize('model,effort,base,effective', [
    ('gemini-3.8-flash', 'medium', 'gemini-3.8-flash', 'medium'),
    ('gemini-3.8-flash-low', 'high', 'gemini-3.8-flash', 'low'),
    ('gemini-3.8-flash-high', 'medium', 'gemini-3.8-flash', 'high'),
    ('gemini-3.8-flash-medium', 'medium', 'gemini-3.8-flash', 'medium'),
])
def test_argv_model_effort_and_delimiters(model, effort, base, effective):
    backend = l.AgyBackend(model, effort, path='agy.exe')
    prompt = l.agy_prompt('시스템\n둘', '사용자\n둘')
    assert backend.command(prompt) == ['agy.exe', '--model', base + '-' + effective,
                                      '--output-format', 'json', '--sandbox', '--print=' + prompt]
    assert l.agy_model(model, effort) == (base, effective)
    assert prompt == '--- BEGIN SYSTEM PROMPT ---\n시스템\n둘\n--- END SYSTEM PROMPT ---\n\n--- BEGIN USER MESSAGE ---\n사용자\n둘\n--- END USER MESSAGE ---'
    assert '--effort' not in backend.command(prompt)


def test_subprocess_contract_empty_cwd_devnull_and_cleanup(monkeypatch):
    captured = {}
    def process(argv, **kwargs):
        captured.update(argv=argv, **kwargs)
        assert list(Path(kwargs['cwd']).iterdir()) == []
        assert kwargs['stdin'] == subprocess.DEVNULL and 'input' not in kwargs
        assert kwargs['shell'] is False and kwargs['capture_output'] is True
        assert 'text' not in kwargs and 'encoding' not in kwargs
        assert kwargs['env']['PYTHONIOENCODING'] == 'utf-8'
        assert kwargs['env']['PYTHONUTF8'] == '1'
        assert kwargs['timeout'] == 17
        assert all('dangerously' not in arg for arg in argv)
        return SimpleNamespace(returncode=0, stdout=UTF8, stderr=b'')
    monkeypatch.setattr(l.subprocess, 'run', process)
    assert l.AgyBackend(timeout=17, path='agy.exe').complete('system', 'user') == '테스트'
    assert not Path(captured['cwd']).exists()


@pytest.mark.parametrize('change,match', [
    ({'response': ''}, 'empty'), ({'response': ' \n'}, 'empty'),
    ({'response': None}, 'invalid'), ({'response': '\ufffd'}, 'replacement'),
    ({'denied_actions': ['PRIVATE']}, 'denied'), ({'denied_actions': None}, 'denied'),
    ({'denied_actions': {}}, 'denied'), ({'denied_actions': 'PRIVATE'}, 'denied'),
    ({'status': 'FAILED'}, 'SUCCESS'), ({'status': None}, 'SUCCESS'),
])
def test_envelope_errors_with_usage_are_sanitized(monkeypatch, change, match):
    envelope = json.loads(UTF8)
    envelope.update(change)
    result(monkeypatch, json.dumps(envelope).encode())
    with pytest.raises(l.BackendError, match=match) as error:
        l.AgyBackend(path='agy.exe').complete('system', 'user')
    assert error.value.usage == USAGE
    assert 'PRIVATE' not in str(error.value)


@pytest.mark.parametrize('stdout', [b'', b'PRIVATE', b'[]', b'{}\n{}', b'null'])
def test_invalid_envelope(monkeypatch, stdout):
    result(monkeypatch, stdout)
    with pytest.raises(l.BackendError, match='JSON object|SUCCESS'):
        l.AgyBackend(path='agy.exe').complete('system', 'user')


@pytest.mark.parametrize('message', ['rate_limit_exceeded', 'quota exhausted', 'HTTP 429',
                                    'Too many requests', 'RESOURCE_EXHAUSTED', '사용 한도 초과'])
@pytest.mark.parametrize('location', ['response', 'stderr', 'failure'])
def test_limits_in_success_failure_and_stderr(monkeypatch, message, location):
    envelope = json.loads(UTF8)
    if location == 'response':
        envelope['response'] = message
    if location == 'failure':
        envelope.update(status='ERROR', error=message, response='')
    result(monkeypatch, json.dumps(envelope, ensure_ascii=False).encode(),
           message.encode() if location == 'stderr' else b'', int(location == 'failure'))
    with pytest.raises(l.UsageLimit) as error:
        l.AgyBackend(path='agy.exe').complete('system', 'user')
    assert error.value.usage == USAGE


def test_non_json_limit_and_nonzero_exit(monkeypatch):
    result(monkeypatch, b'quota exhausted PRIVATE', code=1)
    with pytest.raises(l.UsageLimit):
        l.AgyBackend(path='agy.exe').complete('system', 'user')
    result(monkeypatch, b'[]', b'quota exhausted PRIVATE', code=1)
    with pytest.raises(l.UsageLimit):
        l.AgyBackend(path='agy.exe').complete('system', 'user')
    result(monkeypatch, code=7)
    with pytest.raises(l.BackendError, match='exit 7') as error:
        l.AgyBackend(path='agy.exe').complete('system', 'user')
    assert error.value.usage == USAGE


@pytest.mark.parametrize('exception,match', [(OSError('PRIVATE'), 'could not be started'),
    (subprocess.TimeoutExpired('PRIVATE', 1, output=b'PRIVATE'), 'timeout')])
def test_launch_failures(monkeypatch, exception, match):
    def fail(*a, **k):
        raise exception
    monkeypatch.setattr(l.subprocess, 'run', fail)
    with pytest.raises(l.BackendError, match=match) as error:
        l.AgyBackend(path='agy.exe').complete('system', 'user')
    assert 'PRIVATE' not in str(error.value)


def test_path_resolution_installed_then_path(tmp_path, monkeypatch):
    monkeypatch.setenv('LOCALAPPDATA', str(tmp_path))
    monkeypatch.setattr(l.shutil, 'which', lambda name: 'PATH-agy' if name == 'agy' else None)
    assert l.default_agy_path() == 'PATH-agy'
    installed = tmp_path / 'Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/agy/bin/agy.exe'
    installed.parent.mkdir(parents=True)
    installed.touch()
    assert l.default_agy_path() == str(installed)
    assert l.parser().parse_args([]).agy_path == str(installed)
    monkeypatch.setattr(l, 'default_agy_path', lambda: None)
    with pytest.raises(l.BackendError, match='not found'):
        l.AgyBackend().complete('system', 'user')


def test_prompt_length_guard_measures_quoted_utf16_and_never_launches(monkeypatch):
    backend = l.AgyBackend(path='agy.exe')
    monkeypatch.setattr(l.subprocess, 'run', lambda *a, **k: pytest.fail('oversized prompt launched'))
    base = l.windows_command_length(backend.command(''))
    assert l.windows_command_length(backend.command('a' * (l.AGY_COMMAND_LIMIT - base))) == l.AGY_COMMAND_LIMIT
    for prompt in ['a' * (l.AGY_COMMAND_LIMIT - base + 1), '"' * 16000, '😀' * 16000]:
        with pytest.raises(l.BackendError, match='safe limit.*not truncated'):
            backend.command(prompt)
    with pytest.raises(l.BackendError, match='safe limit'):
        backend.complete('system', 'x' * 32767)


def test_retry_limit_wait_and_usage_materialization(tmp_path, monkeypatch):
    turn = dict(utt=0, text='synthetic', gap=0, start=0, end=1)
    chunk = l.Chunk('yt:fixture', 0, 0, (turn,), 0)
    envelope = json.loads(UTF8)
    good = dict(envelope, response=json.dumps([dict(utt=0, story=1, kind=[], exclude=[], skip=False, note='')]))
    outputs = iter([dict(envelope, denied_actions=['denied']), dict(envelope, status='ERROR', response='quota exhausted'), good])
    monkeypatch.setattr(l.subprocess, 'run', lambda *a, **k: SimpleNamespace(returncode=0, stderr=b'', stdout=json.dumps(next(outputs)).encode()))
    storage = l.Store(tmp_path, {'test': True})
    factory = lambda: l.AgyBackend(path='agy.exe')
    args = ([[chunk]], storage, factory, 'system', '{{TURNS}}', 'llm:agy:gemini-3.8-flash:medium')
    assert l.run_clips(*args, retries=1) == 3
    assert storage.entries[chunk.id]['log']['backend_errors'] == 1
    assert l.run_clips(*args, retries=0) == 0
    row = l.read_jsonl(tmp_path / 'run.jsonl')[0]
    assert row['token_usage'] == {k: v * 3 for k, v in USAGE.items()}
    assert row['limit_errors'] == 1 and row['attempts'] == 3
    assert l.read_jsonl(tmp_path / 'yt%3Afixture.jsonl')[0]['labeler'] == args[-1]
    outputs = iter([dict(envelope, status='ERROR', response='quota exhausted'), good])
    class InstantStop(threading.Event):
        def wait(self, timeout=None):
            return False
    fresh = l.Store(tmp_path / 'wait', {'test': True})
    assert l.run_clips([[chunk]], fresh, factory, 'system', '{{TURNS}}', args[-1], retries=0,
                       wait_minutes=1, stop=InstantStop()) == 0
    assert fresh.entries[chunk.id]['log']['attempts'] == 2


def test_main_naming_dry_run_and_suffix(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(l, 'ROOT', tmp_path)
    monkeypatch.setattr(l, 'prompt_templates', lambda: ('system', '{{TURNS}}'))
    chunk = l.Chunk('yt:fixture', 0, 0, (dict(utt=0, text='synthetic', gap=0),), 0)
    monkeypatch.setattr(l, 'load_clips', lambda *a: [[chunk]])
    args = ['--backend', 'agy', '--model', 'gemini-3.8-flash-medium', '--effort', 'high', '--agy-path', 'agy.exe']
    complete = l.AgyBackend.complete
    monkeypatch.setattr(l.AgyBackend, 'complete', lambda *a: pytest.fail('dry run called backend'))
    assert l.main(args + ['--dry-run']) == 0
    directory = tmp_path / '.local/labels/agy-gemini-3.8-flash-medium'
    prompt = next((directory / 'prompts').glob('*.txt')).read_text(encoding='utf-8')
    assert prompt == l.agy_prompt('system', l.render_prompt(chunk, '{{TURNS}}'))
    assert f'Largest prompt: {len(prompt)} characters' in capsys.readouterr().out
    result(monkeypatch, json.dumps(dict(json.loads(UTF8), response='[{"utt":0,"story":1,"kind":[],"exclude":[],"skip":false,"note":""}]')).encode())
    monkeypatch.setattr(l.AgyBackend, 'complete', complete)
    assert l.main(args) == 0
    assert l.read_jsonl(directory / 'yt%3Afixture.jsonl')[0]['labeler'] == 'llm:agy:gemini-3.8-flash:medium'
    assert l.read_jsonl(directory / 'run.jsonl')[0]['token_usage'] == USAGE


@pytest.mark.parametrize('effort', ['minimal', 'xhigh'])
def test_invalid_agy_effort(effort):
    with pytest.raises(SystemExit) as error:
        l.main(['--backend', 'agy', '--effort', effort])
    assert error.value.code == 2
