import json
import math
from pathlib import Path
import struct
import subprocess
import wave

import pytest

from demo import pleos as p
from tools.listen.storage import clip_name


ID = 'yt:abcdefghijk@1'
ID2 = 'yt:abcdefghijk@2'


@pytest.fixture
def root(tmp_path):
    (tmp_path / '.local/demo/clips').mkdir(parents=True)
    rows = [dict(id=ID, origin='approved', turns=['가나다', '라마']),
            dict(id=ID2, origin='prescreened', turns=['바'])]
    (tmp_path / '.local/demo/sample.jsonl').write_text(
        '\n'.join(json.dumps(r) for r in rows), encoding='utf-8')
    return tmp_path


def sine(path, rate=48000, seconds=.25):
    with wave.open(str(path), 'wb') as output:
        output.setparams((2, 2, rate, 0, 'NONE', 'not compressed'))
        output.writeframes(b''.join(struct.pack('<hh', *([int(9000 * math.sin(2 * math.pi * 440 * i / rate))] * 2))
                                    for i in range(int(rate * seconds))))


def prepared(root):
    sine(root / '.local/demo/clips' / clip_name(ID))
    return p.prepare(root)


def write_tsv(path, rows):
    def escape(text):
        return text.replace('\\', '\\\\').replace('\n', '\\n').replace('\t', '\\t').replace('\r', '\\r')
    p.atomic(path, 'kind\treceived_ms\taudio_ms\ttext\tmetadata\n' + ''.join(
        '\t'.join(escape(str(x)) for x in row) + '\n' for row in rows))


class Device:
    def __init__(self, total=1, finish=True, state='completed'):
        self.calls = []
        self.run_id = 'stale'
        self.total, self.finish, self.state = total, finish, state
        self.polls = 0

    def __call__(self, args, stdin=None, **kwargs):
        self.calls.append([str(a) for a in args])
        if args == ['shell', 'am', 'get-current-user']:
            return b'10\n'
        if 'start-foreground-service' in args:
            self.run_id = args[args.index('run_id') + 1]
            return b'Starting service'
        if args[0] == 'exec-out' and args[-1].endswith('batch-status.json'):
            self.polls += 1
            if self.polls == 1:
                return b'{"run_id":"stale","state":"completed"}'
            return json.dumps(dict(run_id=self.run_id, state=self.state if self.finish else 'running',
                                   total=self.total, completed=self.total, errors=0)).encode()
        return b''


def clock():
    tick = [0.]
    return lambda: tick[0], lambda seconds: tick.__setitem__(0, tick[0] + seconds)


def test_pcm_conversion_sine(root):
    entries = prepared(root)
    data = (p.base(root) / 'pcm' / entries[0]['name']).read_bytes()
    assert len(data) == 8000
    assert entries[0]['seconds'] == .25
    values = struct.unpack('<' + 'h' * (len(data) // 2), data)
    assert max(values) > 8000
    crossings = sum(a <= 0 < b for a, b in zip(values, values[1:]))
    assert 105 <= crossings <= 115


def test_name_mapping_and_approved_selection(root):
    entries = prepared(root)
    assert len(entries) == 1 and entries[0]['id'] == ID
    assert p.SAFE.fullmatch(entries[0]['name'])
    assert p.pcm_name(ID) != p.pcm_name(ID2)
    assert p.manifest(root) == entries
    assert not list((root / '.local/demo').rglob('*.tmp'))


def test_ids_limit(root):
    sine(root / '.local/demo/clips' / clip_name(ID))
    assert len(p.prepare(root, [ID], 1)) == 1
    with pytest.raises(ValueError):
        p.prepare(root, [ID2])


def test_no_approved(root):
    (root / '.local/demo/sample.jsonl').write_text('', encoding='utf-8')
    with pytest.raises(ValueError):
        p.prepare(root)


def test_tsv_escape_and_broken_lines(root):
    path = p.base(root) / 'test.tsv'
    text = 'a\tb\nc\rd\\n'
    write_tsv(path, [('partial', 1, 100, '중간', ''), ('final', 40, 200, text, 'epd:1,2'),
                     ('error', 41, 200, 'sdk_error', '')])
    with path.open('a', encoding='utf-8') as output:
        output.write('broken\nfinal\tnan\t2\tx\t\nfinal\t1\t-2\tx\t\nfinal\t1\t2\tx\\z\t\n')
    records, broken = p.parse_tsv(path)
    assert records[1]['text'] == text
    assert len(records) == 3 and broken == 4


@pytest.mark.parametrize('value,expected', [('\\\\n', '\\n'), ('\\n', '\n'), ('abc', 'abc')])
def test_unescape(value, expected):
    assert p.unescape(value) == expected


@pytest.mark.parametrize('value', ['x\\', '\\q'])
def test_bad_escape(value):
    with pytest.raises(ValueError):
        p.unescape(value)


def test_bad_header(root):
    path = p.base(root) / 'bad.tsv'
    p.atomic(path, 'wrong\n')
    assert p.parse_tsv(path) == ([], 1)


def test_push_exact_selection_and_clean(root):
    entries = prepared(root)
    calls, uploaded = [], []
    def call(args, stdin=None):
        calls.append(list(map(str, args)))
        if args == ['shell', 'am', 'get-current-user']:
            return b'10\n'
        if args[0] == 'exec-in':
            assert stdin is not None and not stdin.closed
            uploaded.append(stdin.read())
        if args[-1].startswith("'wc -c < "):
            return str(len(uploaded[-1])).encode() + b'\n'
        return b''
    p.push(root, call)
    assert calls[:5] == [
        ['shell', 'am', 'get-current-user'],
        ['shell', 'am', 'stopservice', '--user', '10', '-n', p.SERVICE],
        ['shell', 'dumpsys', 'activity', 'services', p.SERVICE],
        ['shell', 'run-as', p.PACKAGE, '--user', '10', 'sh', '-c', "'rm -rf files/batch'"],
        ['shell', 'rm', '-rf', p.LEGACY_REMOTE]]
    assert calls[5] == ['shell', 'run-as', p.PACKAGE, '--user', '10', 'sh', '-c',
                        "'mkdir -p files/batch'"]
    assert calls[6] == ['exec-in', 'run-as', p.PACKAGE, '--user', '10', 'sh', '-c',
                        'cat > files/batch/' + entries[0]['name']]
    assert uploaded == [(p.base(root) / 'pcm' / entries[0]['name']).read_bytes()]
    assert not any(c[0] == 'push' for c in calls)


def test_run_stale_status_then_completed(root):
    prepared(root)
    device = Device()
    now, sleep = clock()
    status = p.run(root, device, speed=200, padding=500, timeout=10, poll=1, now=now, sleep=sleep)
    assert status['run_id'] == device.run_id
    start = next(c for c in device.calls if 'start-foreground-service' in c)
    assert start[3:5] == ['--user', '10']
    assert start[start.index('speed_pct') + 1] == '200'
    assert start[start.index('padding_ms') + 1] == '500'
    assert (p.base(root) / 'raw/pleos-200/run.json').exists()


def test_run_timeout_stops_service(root):
    prepared(root)
    device = Device(finish=False)
    now, sleep = clock()
    with pytest.raises(TimeoutError):
        p.run(root, device, timeout=3, poll=1, now=now, sleep=sleep)
    assert device.calls[-1] == ['shell', 'am', 'stopservice', '--user', '10', '-n', p.SERVICE]


@pytest.mark.parametrize('state,total', [('error', 1), ('failed', 1), ('cancelled', 1), ('completed', 2)])
def test_run_rejects_failure_or_count_mismatch(root, state, total):
    prepared(root)
    now, sleep = clock()
    with pytest.raises(RuntimeError):
        p.run(root, Device(total=total, state=state), timeout=3, poll=1, now=now, sleep=sleep)


def make_run(root, speed=100, rows=None):
    entries = prepared(root)
    folder = p.base(root) / 'raw' / p.config(speed)
    p.write_json(folder / 'run.json', dict(run_id='run1', speed_pct=speed, padding_ms=500, clips=entries))
    p.write_json(folder / 'batch-status.json', dict(run_id='run1', state='completed'))
    write_tsv(folder / Path(entries[0]['name']).with_suffix('.tsv'), rows or [
        ('partial', 5, 500, '중간', ''), ('final', 800, 600, '최종\n문장', ''),
        ('final', 950, 750, '끝', ''), ('error', 951, 750, 'sdk_test', '')])
    return folder


def test_collect_schema_and_speed_config(root):
    make_run(root)
    make_run(root, 200)
    rows = p.collect(root)
    assert {r['config'] for r in rows} == {'pleos', 'pleos-200'}
    row = rows[0]
    assert set(row) == {'id', 'config', 'lines', 'seconds', 'callbacks', 'errors'}
    assert row['callbacks'] == 2 and row['errors'] == 1
    assert row['lines'][0] == dict(start=0., end=.1, text='최종\n문장')
    assert row['lines'][1]['start'] == .1 and row['lines'][1]['end'] == .25
    assert len((root / '.local/demo/asr/pleos.jsonl').read_text(encoding='utf-8').splitlines()) == 2


def test_collect_null_positions_and_no_finals(root):
    make_run(root, rows=[('final', 0, '', '결과', '')])
    row = p.collect(root)[0]
    assert row['lines'][0]['start'] is None and row['lines'][0]['end'] is None
    make_run(root, rows=[('event', 0, 0, '', 'ready')])
    assert p.collect(root)[0]['lines'] == []
    assert p.stats(root)['no_result_clips'] == 1


def test_stats_never_contains_transcript(root):
    make_run(root)
    report = p.stats(root)
    clip = report['clips'][0]
    assert clip['chars'] == len('최종\n문장끝')
    assert clip['whisper_chars'] == 5
    assert clip['char_ratio'] == clip['chars'] / 5
    assert clip['callback_delay_ms']['mean'] == 200
    text = (p.base(root) / 'stats.json').read_text(encoding='utf-8')
    assert '문장' not in text and '가나다' not in text


def test_missing_tsv_is_counted(root):
    folder = make_run(root)
    for file in folder.glob('*.tsv'):
        file.unlink()
    row = p.collect(root)[0]
    assert row['errors'] == 1 and row['callbacks'] == 0


def test_pull_and_collect_flow(root):
    prepared(root)
    device = Device()
    now, sleep = clock()
    p.run(root, device, timeout=3, poll=1, now=now, sleep=sleep)
    original = device.__call__
    def call(args, **kwargs):
        if args[-1].endswith('.tsv'):
            return b'kind\treceived_ms\taudio_ms\ttext\tmetadata\nfinal\t100\t200\ttest\t\n'
        return original(args)
    p.pull(root, call)
    assert p.collect(root)[0]['callbacks'] == 1


def test_collect_rejects_previous_run_status(root):
    folder = make_run(root)
    status = p.read_json(folder / 'batch-status.json')
    status['run_id'] = 'old'
    p.write_json(folder / 'batch-status.json', status)
    with pytest.raises(ValueError):
        p.collect(root)


def test_adb_boundary(monkeypatch):
    calls = []
    def fake(command, **kwargs):
        calls.append((command, kwargs))
        return type('Result', (), dict(returncode=0, stdout=b'ok', stderr=b''))()
    monkeypatch.setattr(p.subprocess, 'run', fake)
    assert p.adb(['devices'], 'serial', 'fake-adb') == b'ok'
    assert calls[0][0] == ['fake-adb', '-s', 'serial', 'devices']
    assert calls[0][1]['capture_output']


def test_unsafe_manifest(root):
    prepared(root)
    data = p.read_json(p.base(root) / 'manifest.json')
    data['clips'][0]['name'] = '../escape.pcm'
    p.write_json(p.base(root) / 'manifest.json', data)
    with pytest.raises(ValueError):
        p.push(root, lambda _: pytest.fail('adb must not be called'))


def test_clean_waits_for_service_destroy_before_delete():
    calls = []
    responses = iter([b'ServiceRecord{abc u10 com.drivediary.sttprobe/.BatchSttService}', b''])
    def call(args, **kwargs):
        calls.append(args)
        return next(responses) if 'dumpsys' in args else b''
    # Use a virtual clock to avoid a real wait in the stop barrier.
    now, sleep = clock()
    call(['shell', 'am', 'stopservice', '--user', '10', '-n', p.SERVICE])
    p.wait_stopped(call, now=now, sleep=sleep, user=10)
    call(p.app_shell(10, 'rm -rf ' + p.REMOTE))
    assert len([c for c in calls if 'dumpsys' in c]) == 2
    assert calls[-1] == p.app_shell(10, 'rm -rf ' + p.REMOTE)


def test_wait_stopped_timeout():
    now, sleep = clock()
    with pytest.raises(TimeoutError):
        p.wait_stopped(lambda _: b'ServiceRecord{abc u10 com.drivediary.sttprobe/.BatchSttService}', timeout=.4, now=now, sleep=sleep, user=10)


def test_broken_utf8_line(root):
    path = p.base(root) / 'utf8.tsv'
    p.atomic(path, b'kind\treceived_ms\taudio_ms\ttext\tmetadata\nfinal\t1\t2\t\xff\t\n')
    assert p.parse_tsv(path) == ([], 1)


def test_bad_status_json_is_retryable():
    assert p.read_status(lambda _: b'{broken', user=10) is None
    assert p.read_status(lambda _: b'[]', user=10) is None


def test_run_prefix(root):
    entry = prepared(root)[0]
    device = Device()
    now, sleep = clock()
    prefix = entry['name'][:12]
    p.run(root, device, only=prefix, timeout=3, poll=1, now=now, sleep=sleep)
    assert next(c for c in device.calls if 'start-foreground-service' in c)[-3:] == ['--es', 'only', prefix]


def adb_result(code=0, stdout=b'', stderr=b''):
    return subprocess.CompletedProcess([], code, stdout, stderr)


@pytest.mark.parametrize('command', ['clean', 'push'])
def test_stopservice_255_and_missing_directory_are_harmless(root, monkeypatch, command):
    prepared(root)
    calls = []
    def fake(argv, **kwargs):
        args = argv[3:]
        calls.append(args)
        if 'stopservice' in args:
            return adb_result(255, b'Service not stopped: was not running.')
        if args == ['shell', 'am', 'get-current-user']:
            return adb_result(stdout=b'10\n')
        if args[0] == 'exec-in':
            assert kwargs['stdin'].read()
        if args[-1].startswith("'wc -c < "):
            return adb_result(stdout=b'8000\n')
        return adb_result()
    monkeypatch.setattr(p.subprocess, 'run', fake)
    assert p.main([command, '--root', str(root), '--adb', 'fake-adb']) == 0
    assert calls[1:5] == [['shell', 'am', 'stopservice', '--user', '10', '-n', p.SERVICE],
                           ['shell', 'dumpsys', 'activity', 'services', p.SERVICE],
                           p.app_shell(10, 'rm -rf ' + p.REMOTE),
                           ['shell', 'rm', '-rf', p.LEGACY_REMOTE]]


def test_timeout_stopservice_255_preserves_timeout(root, monkeypatch):
    prepared(root)
    device = Device(finish=False)
    def fake(argv, **kwargs):
        args = argv[3:]
        if 'stopservice' in args:
            return adb_result(255, b'Service not stopped: was not running.')
        return adb_result(stdout=device(args))
    monkeypatch.setattr(p.subprocess, 'run', fake)
    now, sleep = clock()
    with pytest.raises(TimeoutError, match='batch timed out'):
        p.run(root, lambda args, **kwargs: p.adb(args, **kwargs), timeout=2, poll=1, now=now, sleep=sleep)


def test_status_file_missing_then_completed_with_real_adb_boundary(root, monkeypatch):
    prepared(root)
    device = Device()
    polls = 0
    def fake(argv, **kwargs):
        nonlocal polls
        args = argv[3:]
        if args[0] == 'exec-out' and args[-1].endswith('batch-status.json'):
            polls += 1
            if polls == 1:
                return adb_result(1, stderr=b'cat: batch-status.json: No such file or directory')
            device.polls = 1  # Next status contains the matching run ID.
        return adb_result(stdout=device(args))
    monkeypatch.setattr(p.subprocess, 'run', fake)
    now, sleep = clock()
    status = p.run(root, lambda args, **kwargs: p.adb(args, **kwargs), timeout=3, poll=1, now=now, sleep=sleep)
    assert status['state'] == 'completed' and polls == 2


def test_status_transport_error_is_not_missing_file(monkeypatch):
    monkeypatch.setattr(p.subprocess, 'run', lambda *a, **k: adb_result(1, stderr=b'device offline'))
    with pytest.raises(p.AdbError) as error:
        p.read_status(lambda args, **kwargs: p.adb(args, **kwargs), user=10)
    assert error.value.returncode == 1 and not error.value.missing


@pytest.mark.parametrize('args', [p.app_shell(10, 'rm -rf files/batch'),
                                  p.app_shell(10, 'ls files/batch'),
                                  p.run_as(10, 'sh', '-c', 'cat > files/batch/clip.pcm', mode='exec-in')])
def test_other_adb_failures_stay_errors(monkeypatch, args):
    monkeypatch.setattr(p.subprocess, 'run', lambda *a, **k: adb_result(1, stderr=b'Permission denied'))
    with pytest.raises(p.AdbError) as error:
        p.adb(args)
    assert error.value.returncode == 1


@pytest.mark.parametrize('stdout,stderr', [(b'Error: service not found', b''),
                                         (b'Starting service', b'java.lang.SecurityException: denied')])
def test_start_foreground_service_checks_both_streams(root, monkeypatch, capsys, stdout, stderr):
    prepared(root)
    monkeypatch.setattr(p.subprocess, 'run', lambda *a, **k: adb_result(0, stdout, stderr))
    assert p.main(['run', '--root', str(root), '--user', '10']) == 1
    log = capsys.readouterr().err
    assert 'stage=run/shell.am.start-foreground-service' in log
    assert 'exception=AdbError' in log and 'adb_exit=0' in log
    assert ('Error' in log or 'SecurityException' in log)


def test_failure_diagnostics_redact_cat_body(root, monkeypatch, capsys):
    folder = make_run(root)
    metadata = p.read_json(folder / 'run.json')
    def fake(argv, **kwargs):
        if argv[-1].endswith('batch-status.json'):
            return adb_result(stdout=json.dumps(dict(run_id=metadata['run_id'], state='completed')).encode())
        return adb_result(7, b'PRIVATE TRANSCRIPT', b'PRIVATE STDERR')
    monkeypatch.setattr(p.subprocess, 'run', fake)
    assert p.main(['pull', '--root', str(root), '--user', '10']) == 1
    log = capsys.readouterr().err
    assert 'stage=pull/exec-out.run-as' in log
    assert 'exception=AdbError' in log and 'adb_exit=7' in log
    assert 'PRIVATE' not in log


def test_allowlisted_diagnostics_truncated(monkeypatch):
    monkeypatch.setattr(p.subprocess, 'run', lambda *a, **k: adb_result(2, b'x' * 300, b'y' * 300))
    with pytest.raises(p.AdbError) as error:
        p.adb(['shell', 'am', 'get-current-user'])
    assert error.value.detail == 'x' * 200


@pytest.mark.parametrize('error', [OSError('PRIVATE'), subprocess.TimeoutExpired('PRIVATE', 1)])
def test_adb_launch_failure_diagnostics(root, monkeypatch, capsys, error):
    def fake(*args, **kwargs):
        raise error
    monkeypatch.setattr(p.subprocess, 'run', fake)
    assert p.main(['clean', '--root', str(root)]) == 1
    log = capsys.readouterr().err
    assert f'cause={type(error).__name__}' in log and 'adb_exit=unavailable' in log
    assert 'PRIVATE' not in log


def test_normal_start_stderr_is_not_an_error(monkeypatch):
    monkeypatch.setattr(p.subprocess, 'run', lambda *a, **k: adb_result(
        stdout=b'Starting service: Intent { cmp=com.drivediary.sttprobe/.BatchSttService }',
        stderr=b'Warning: harmless notice'))
    assert p.adb(['shell', 'am', 'start-foreground-service', '-n', p.SERVICE]).startswith(b'Starting service')


@pytest.mark.parametrize('raw', [b'10\n', b'0\n', b' 12\r\n'])
def test_current_user_query(raw):
    calls = []
    def call(args, **kwargs):
        calls.append(args)
        return raw
    assert p.current_user(call) == int(raw)
    assert calls == [['shell', 'am', 'get-current-user']]


@pytest.mark.parametrize('raw', [b'', b'current user: 10', b'-1', b'10\n11', b'PRIVATE'])
def test_current_user_rejects_invalid_output(raw):
    with pytest.raises(ValueError, match='invalid Android current user'):
        p.current_user(lambda _: raw)


def test_explicit_user_skips_query_and_scopes_clean():
    calls = []
    p.clean(lambda args: calls.append(args) or b'', user=12)
    assert calls == [
        ['shell', 'am', 'stopservice', '--user', '12', '-n', p.SERVICE],
        ['shell', 'dumpsys', 'activity', 'services', p.SERVICE],
        ['shell', 'run-as', p.PACKAGE, '--user', '12', 'sh', '-c', "'rm -rf files/batch'"],
        ['shell', 'rm', '-rf', p.LEGACY_REMOTE]]


def test_clean_does_not_wait_for_another_users_service():
    calls = []
    def call(args, **kwargs):
        calls.append(args)
        return b'ServiceRecord{abc u0 com.drivediary.sttprobe/.BatchSttService}'
    p.clean(call, user=10)
    assert len([c for c in calls if 'dumpsys' in c]) == 1
    assert calls[-1] == ['shell', 'rm', '-rf', p.LEGACY_REMOTE]


@pytest.mark.parametrize('size', [b'7999', b'8001', b'', b'Permission denied'])
def test_push_rejects_unverified_transfer(root, size):
    prepared(root)
    uploads = []
    def call(args, stdin=None):
        if args[0] == 'exec-in':
            uploads.append(stdin.read())
        return size if args[-1].startswith("'wc -c < ") else b''
    with pytest.raises(p.TransferError) as error:
        p.push(root, call, user=10)
    assert error.value.stage == 'push-verify'
    assert len(uploads) == 1 and len(uploads[0]) == 8000


def test_exec_in_streams_large_binary_file_without_shell(monkeypatch, tmp_path):
    source = tmp_path / 'input.pcm'
    payload = bytes(range(256)) * 10240
    source.write_bytes(payload)
    seen = []
    def fake(argv, **kwargs):
        assert argv == ['fake-adb', '-s', 'serial', 'exec-in', 'run-as', p.PACKAGE,
                        '--user', '10', 'sh', '-c', 'cat > files/batch/clip.pcm']
        assert kwargs.get('shell', False) is False
        assert not kwargs.get('text') and 'input' not in kwargs
        seen.append(kwargs['stdin'].read())
        return adb_result()
    monkeypatch.setattr(p.subprocess, 'run', fake)
    with source.open('rb') as stream:
        p.adb(p.run_as(10, 'sh', '-c', 'cat > files/batch/clip.pcm', mode='exec-in'),
              'serial', 'fake-adb', stdin=stream)
    assert seen == [payload]


@pytest.mark.parametrize('body', [None, b'{broken', b'{"run_id":"old","state":"starting"}',
                                  b'{"state":"running"}'])
def test_service_not_started_fails_at_start_deadline(root, body, capsys):
    prepared(root)
    calls = []
    def call(args, **kwargs):
        calls.append(args)
        if args[0] == 'exec-out':
            if body is None:
                raise p.AdbError('exec-out.run-as', 1, missing=True)
            return body
        return b''
    now, sleep = clock()
    with pytest.raises(p.ServiceStartTimeout):
        p.run(root, call, user=10, timeout=3600, poll=20, now=now, sleep=sleep)
    assert now() == 15
    assert calls[-1] == ['shell', 'am', 'stopservice', '--user', '10', '-n', p.SERVICE]
    assert 'stage=wait-starting' in capsys.readouterr().out


def test_starting_status_switches_to_full_timeout(root):
    prepared(root)
    run_id = None
    now, sleep = clock()
    def call(args, **kwargs):
        nonlocal run_id
        if 'start-foreground-service' in args:
            run_id = args[args.index('run_id') + 1]
        if args[0] == 'exec-out':
            return json.dumps(dict(run_id=run_id, state='starting' if now() < 3 else 'running')).encode()
        return b''
    with pytest.raises(TimeoutError, match='batch timed out'):
        p.run(root, call, user=10, start_timeout=2, timeout=20, poll=1, now=now, sleep=sleep)
    assert now() == 20


def test_start_timeout_diagnostic_and_override(root, monkeypatch, capsys):
    prepared(root)
    now, sleep = clock()
    original = p.run
    def run(*args, **kwargs):
        return original(*args, **kwargs, now=now, sleep=sleep)
    monkeypatch.setattr(p, 'run', run)
    calls = []
    def fake(argv, **kwargs):
        calls.append(argv[3:])
        return adb_result(stdout=b'{}')
    monkeypatch.setattr(p.subprocess, 'run', fake)
    assert p.main(['run', '--root', str(root), '--user', '10', '--start-timeout', '3',
                   '--timeout', '3600', '--poll', '20']) == 1
    assert now() == 3
    log = capsys.readouterr().err
    assert 'stage=run/wait-starting' in log and 'service did not start' in log
    assert not any('get-current-user' in c for c in calls)


def test_device_error_reason_is_reported_without_body(root, monkeypatch, capsys):
    prepared(root)
    run_id = None
    def fake(argv, **kwargs):
        nonlocal run_id
        args = argv[3:]
        if 'start-foreground-service' in args:
            run_id = args[args.index('run_id') + 1]
        if args[0] == 'exec-out':
            return adb_result(stdout=json.dumps(dict(run_id=run_id, state='error',
                                                     reason='no_input_pcm', text='PRIVATE')).encode())
        return adb_result()
    monkeypatch.setattr(p.subprocess, 'run', fake)
    assert p.main(['run', '--root', str(root), '--user', '10']) == 1
    log = capsys.readouterr().err
    assert 'stage=run/wait-completed; reason=no_input_pcm' in log
    assert 'PRIVATE' not in log


def test_pull_preserves_binary_output_and_user(root):
    folder = make_run(root)
    payload = b'\xef\xbb\xbfheader\r\n\x00\xff\r\n'
    calls = []
    def call(args, **kwargs):
        calls.append(args)
        if args[-1].endswith('batch-status.json'):
            return b'{"run_id":"run1","state":"completed"}'
        return payload
    p.pull(root, call, user=10)
    assert next(folder.glob('*.tsv')).read_bytes() == payload
    assert all(c[:5] == ['exec-out', 'run-as', p.PACKAGE, '--user', '10'] for c in calls)


def test_pull_rejects_different_user(root):
    folder = make_run(root)
    metadata = p.read_json(folder / 'run.json')
    metadata['user'] = 10
    p.write_json(folder / 'run.json', metadata)
    with pytest.raises(ValueError, match='another Android user'):
        p.pull(root, lambda _: pytest.fail('no remote reads'), user=0)


@pytest.mark.parametrize('stderr', [b'run-as: package not found: No such file or directory',
                                    b'Permission denied: No such file or directory'])
def test_run_as_failure_is_not_a_missing_status(monkeypatch, stderr):
    monkeypatch.setattr(p.subprocess, 'run', lambda *a, **k: adb_result(1, stderr=stderr))
    with pytest.raises(p.AdbError) as error:
        p.read_status(lambda args, **kwargs: p.adb(args, **kwargs), user=10)
    assert not error.value.missing


@pytest.mark.parametrize('option,value', [('--user', '-1'), ('--start-timeout', '0'),
                                         ('--start-timeout', 'nan')])
def test_invalid_user_and_start_timeout(option, value):
    with pytest.raises(SystemExit) as error:
        p.main(['clean', option, value])
    assert error.value.code == 2



def test_status_read_uses_remaining_startup_budget(root):
    prepared(root)
    now, sleep = clock()
    budgets = []
    def call(args, **kwargs):
        if 'start-foreground-service' in args:
            sleep(14)
            assert kwargs['timeout'] == 15
        if args[0] == 'exec-out':
            budgets.append(kwargs['timeout'])
            sleep(kwargs['timeout'])
            raise p.AdbError('exec-out.run-as', kind='TimeoutExpired')
        return b''
    with pytest.raises(p.ServiceStartTimeout):
        p.run(root, call, user=10, timeout=3600, now=now, sleep=sleep)
    assert budgets == [1] and now() == 15
