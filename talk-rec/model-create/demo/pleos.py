"""Local Pleos batch STT bridge. Console output contains counts only, never transcripts."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
import uuid

from tools.listen.storage import clip_name

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.drivediary.sttprobe'
REMOTE = 'files/batch'
LEGACY_REMOTE = '/sdcard/Android/data/com.drivediary.sttprobe/files/batch'
SERVICE = 'com.drivediary.sttprobe/.BatchSttService'
SAFE = re.compile(r'clip_[0-9a-f]{32}\.pcm')


def atomic(path, value):
    """Temporary files live outside .local/demo, and are always removed."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = value if isinstance(value, bytes) else value.encode('utf-8')
    # Same volume as the output, without leaving locks/temp directories in demo.
    staging = next((parent for parent in path.parents if parent.name == '.local'), path.parent)
    fd, temporary = tempfile.mkstemp(prefix='pleos-', dir=staging)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def write_json(path, value):
    atomic(path, json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + '\n')


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def read_sample(root):
    return [json.loads(line) for line in (root / '.local/demo/sample.jsonl').read_text(
        encoding='utf-8-sig').splitlines() if line.strip()]


def base(root):
    return root / '.local/demo/pleos'


def config(speed):
    return 'pleos' if speed == 100 else f'pleos-{speed}'


def pcm_name(block_id):
    return 'clip_' + hashlib.sha256(block_id.encode('utf-8')).hexdigest()[:32] + '.pcm'


def convert_pcm(source, destination):
    import av
    chunks = []
    with av.open(str(source)) as container:
        resampler = av.AudioResampler(format='s16', layout='mono', rate=16000)
        for frame in container.decode(audio=0):
            for converted in resampler.resample(frame):
                chunks.append(bytes(converted.planes[0])[:converted.samples * 2])
        for converted in resampler.resample(None):
            chunks.append(bytes(converted.planes[0])[:converted.samples * 2])
    payload = b''.join(chunks)
    if not payload or len(payload) > 95 * 32000:
        raise ValueError('PCM must contain between 0 and 95 seconds')
    atomic(destination, payload)
    return len(payload) / 32000


def prepare(root, ids=None, limit=None):
    wanted = set(ids) if ids else None
    approved = [r for r in read_sample(root) if r.get('origin') == 'approved']
    if wanted and wanted - {r['id'] for r in approved}:
        raise ValueError('requested IDs must exist and have approved origin')
    selected = [r for r in approved if wanted is None or r['id'] in wanted]
    if limit is not None:
        selected = selected[:limit]
    if not selected:
        raise ValueError('no approved clips selected')
    entries = []
    seen = set()
    for row in selected:
        filename = pcm_name(row['id'])
        if filename in seen:
            raise ValueError('duplicate clip identity')
        seen.add(filename)
        seconds = convert_pcm(root / '.local/demo/clips' / clip_name(row['id']), base(root) / 'pcm' / filename)
        entries.append(dict(id=row['id'], name=filename, seconds=seconds))
    write_json(base(root) / 'manifest.json', dict(version=1, clips=entries))
    return entries


def manifest(root):
    entries = read_json(base(root) / 'manifest.json')['clips']
    if not entries or len({e['name'] for e in entries}) != len(entries):
        raise ValueError('invalid or empty manifest')
    for entry in entries:
        if not SAFE.fullmatch(entry['name']) or entry['name'] != pcm_name(entry['id']):
            raise ValueError('unsafe manifest name')
    return entries


class AdbError(RuntimeError):
    """Diagnostics contain command names and allowlisted output, never cat bodies."""
    def __init__(self, stage, returncode=None, kind=None, detail='', missing=False):
        super().__init__('adb command failed')
        self.stage, self.returncode, self.kind = stage, returncode, kind
        self.detail, self.missing = detail, missing


def adb(args, serial='emulator-5554', executable=None, timeout=30, stdin=None):
    """Binary subprocess boundary; stdin is an open file, including on Windows."""
    args = list(map(str, args))
    stage = '.'.join(args[:3] if args[:2] == ['shell', 'am'] else args[:2])
    executable = executable or str(Path(os.environ.get('LOCALAPPDATA', '')) /
                                   'Android/Sdk/platform-tools/adb.exe')
    try:
        result = subprocess.run([executable, '-s', serial, *args], capture_output=True,
                                stdin=stdin, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise AdbError(stage, kind=type(error).__name__) from None
    # Android also returns 255 for an already stopped service. The user-specific
    # dumpsys barrier determines whether it is safe to remove batch files.
    if args[:3] == ['shell', 'am', 'stopservice'] and args[-2:] == ['-n', SERVICE]:
        return result.stdout
    output = result.stdout + b'\n' + result.stderr
    safe_output = args[:2] in (['shell', 'am'], ['shell', 'pm'])
    detail = ' '.join(output.decode('utf-8', errors='replace').split())[:200] if safe_output else ''
    missing = (args[:2] == ['exec-out', 'run-as']
               and args[-2:] == ['cat', REMOTE + '/batch-status.json']
               and b'No such file or directory' in output
               and not re.search(rb'permission denied|run-as:|device offline|error:', output, re.I))
    if result.returncode:
        raise AdbError(stage, result.returncode, detail=detail, missing=missing)
    if args[:3] == ['shell', 'am', 'start-foreground-service'] and re.search(
            rb'error|exception|not found|not allowed|unable to start', output, re.I):
        raise AdbError(stage, result.returncode, detail=detail)
    return result.stdout


def current_user(call, user=None):
    if user is None:
        raw = call(['shell', 'am', 'get-current-user']).strip()
        if not re.fullmatch(rb'[0-9]+', raw):
            raise ValueError('invalid Android current user')
        user = int(raw)
    if not isinstance(user, int) or user < 0:
        raise ValueError('invalid Android user')
    return user


def run_as(user, *args, mode='shell'):
    return [mode, 'run-as', PACKAGE, '--user', str(user), *args]


def app_shell(user, command):
    # adb shell joins arguments into a remote shell command; quote sh -c's body.
    # exec-in/exec-out instead receive their command arguments directly.
    return run_as(user, 'sh', '-c', shlex.quote(command))


def stop_service(call, user):
    call(['shell', 'am', 'stopservice', '--user', str(user), '-n', SERVICE])


def wait_stopped(call, timeout=10, now=time.monotonic, sleep=time.sleep, user=None):
    user = current_user(call, user)
    deadline = now() + timeout
    # dumpsys lists ServiceRecord entries for multiple users; only this user's
    # records block cleanup. Other users' running batches must not be stopped.
    record = re.compile(rb'ServiceRecord\{[^\r\n}]*\bu' + str(user).encode() + rb'\b')
    while record.search(call(['shell', 'dumpsys', 'activity', 'services', SERVICE])):
        if now() >= deadline:
            raise TimeoutError('batch service did not stop')
        sleep(.2)


def clean(call, user=None):
    user = current_user(call, user)
    stop_service(call, user)
    wait_stopped(call, user=user)
    call(app_shell(user, 'rm -rf ' + REMOTE))
    # Migration cleanup only: the previous bridge wrote shell-owned user-0 files.
    call(['shell', 'rm', '-rf', LEGACY_REMOTE])


def push(root, call, user=None):
    entries = manifest(root)
    for entry in entries:
        if not (base(root) / 'pcm' / entry['name']).is_file():
            raise ValueError('prepared PCM missing')
    user = current_user(call, user)
    clean(call, user)
    call(app_shell(user, 'mkdir -p ' + REMOTE))
    for entry in entries:
        source = base(root) / 'pcm' / entry['name']
        destination = REMOTE + '/' + entry['name']
        with source.open('rb') as stream:
            call(run_as(user, 'sh', '-c', 'cat > ' + destination, mode='exec-in'), stdin=stream)
            expected = os.fstat(stream.fileno()).st_size
        size = call(app_shell(user, 'wc -c < ' + destination)).strip()
        if not size.isdigit() or int(size) != expected:
            raise TransferError('push-verify', 'PCM size mismatch')


class TransferError(RuntimeError):
    def __init__(self, stage, reason):
        super().__init__(reason)
        self.stage, self.reason = stage, reason


class ServiceStartTimeout(TimeoutError):
    stage = 'wait-starting'
    reason = 'service did not start (서비스가 뜨지 않음)'


def read_status(call, user=None, timeout=None):
    user = current_user(call, user)
    try:
        args = run_as(user, 'cat', REMOTE + '/batch-status.json', mode='exec-out')
        payload = call(args) if timeout is None else call(args, timeout=timeout)
        status = json.loads(payload)
        return status if isinstance(status, dict) else None
    except AdbError as error:
        if not error.missing:
            raise
        return None
    except (ValueError, UnicodeError):
        return None


def run(root, call, speed=100, padding=0, timeout=3000, poll=2, only=None,
        now=time.monotonic, sleep=time.sleep, user=None, start_timeout=15):
    entries = manifest(root)
    if only:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', only):
            raise ValueError('unsafe filename prefix')
        entries = [e for e in entries if e['name'].startswith(only)]
    if not entries:
        raise ValueError('prefix selected no clips')
    user = current_user(call, user)
    run_id = uuid.uuid4().hex
    folder = base(root) / 'raw' / config(speed)
    metadata = dict(run_id=run_id, user=user, speed_pct=speed, padding_ms=padding, clips=entries)
    write_json(folder / 'run.json', metadata)
    args = ['shell', 'am', 'start-foreground-service', '--user', str(user), '-n', SERVICE,
            '--ei', 'speed_pct', str(speed), '--ei', 'padding_ms', str(padding),
            '--es', 'run_id', run_id]
    if only:
        args += ['--es', 'only', only]
    deadline = now() + timeout
    startup_deadline = min(deadline, now() + start_timeout)
    print(f'Pleos run: stage=start-service user={user}')
    response = call(args, timeout=min(timeout, start_timeout))
    if b'Error' in response or b'Exception' in response:
        raise TransferError('start-service', 'Android refused batch service start')
    print('Pleos run: stage=wait-starting')
    started = False
    while True:
        limit = deadline if started else startup_deadline
        if now() >= limit:
            stop_service(call, user)
            if not started:
                raise ServiceStartTimeout('service did not start')
            raise TimeoutError('batch timed out; service stop requested')
        try:
            status = read_status(call, user, timeout=limit - now())
        except AdbError as error:
            if not started and error.kind == 'TimeoutExpired':
                stop_service(call, user)
                raise ServiceStartTimeout('service did not start') from None
            raise
        if (status and status.get('run_id') == run_id
                and status.get('state') in ('starting', 'running', 'completed', 'error', 'failed', 'cancelled')):
            if not started:
                started = True
                print('Pleos run: stage=wait-completed')
            if status.get('state') in ('error', 'failed', 'cancelled'):
                # Only allow machine reason codes, never arbitrary device text.
                reason = status.get('reason', 'batch_failed_or_cancelled')
                if reason not in ('no_input_pcm', 'input_directory_unreadable',
                                   'batch_directory_unavailable', 'invalid_options',
                                   'another_probe_running', 'status_write_failed'):
                    reason = 'batch_failed_or_cancelled'
                raise TransferError('wait-completed', reason)
            if status.get('state') == 'completed':
                if status.get('total') != len(entries) or status.get('completed') != len(entries):
                    raise TransferError('wait-completed', 'device clip count differs; push again')
                wait_stopped(call, timeout=min(10, max(.1, deadline - now())), user=user)
                return status
        limit = deadline if started else startup_deadline
        sleep(min(poll, max(0, limit - now())))


def pull(root, call, speed=100, user=None):
    folder = base(root) / 'raw' / config(speed)
    metadata = read_json(folder / 'run.json')
    user = current_user(call, user)
    if metadata.get('user', user) != user:
        raise ValueError('run belongs to another Android user')
    status = read_status(call, user)
    if not status or status.get('run_id') != metadata['run_id'] or status.get('state') != 'completed':
        raise ValueError('no matching completed device run')
    for entry in metadata['clips']:
        filename = Path(entry['name']).with_suffix('.tsv').name
        if not SAFE.fullmatch(entry['name']):
            raise ValueError('unsafe run filename')
        atomic(folder / filename, call(run_as(user, 'cat', REMOTE + '/' + filename, mode='exec-out')))
    write_json(folder / 'batch-status.json', status)


def unescape(value):
    escapes = {'t': '\t', 'n': '\n', 'r': '\r', '\\': '\\'}
    def replace(match):
        if match[1] not in escapes:
            raise ValueError('invalid TSV escape')
        return escapes[match[1]]
    if (len(value) - len(value.rstrip('\\'))) & 1:
        raise ValueError('incomplete TSV escape')
    return re.sub(r'\\(.)', replace, value)


def parse_tsv(path):
    records, broken = [], 0
    with Path(path).open(encoding='utf-8-sig', errors='replace') as stream:
        header = stream.readline().rstrip('\r\n').split('\t')
        if header != ['kind', 'received_ms', 'audio_ms', 'text', 'metadata']:
            return [], 1
        for line in stream:
            try:
                parts = line.rstrip('\r\n').split('\t')
                if '\ufffd' in line or len(parts) != 5 or parts[0] not in ('partial', 'final', 'error', 'event'):
                    raise ValueError()
                received = float(parts[1]) if parts[1] else None
                position = float(parts[2]) if parts[2] else None
                if any(x is not None and (not math.isfinite(x) or x < 0) for x in (received, position)):
                    raise ValueError()
                records.append(dict(kind=parts[0], received_ms=received, audio_ms=position,
                                    text=unescape(parts[3]), metadata=unescape(parts[4])))
            except ValueError:
                broken += 1
    return records, broken


def load_runs(root):
    for file in sorted((base(root) / 'raw').glob('pleos*/run.json')):
        meta = read_json(file)
        if file.parent.name != config(meta['speed_pct']):
            raise ValueError('run speed mismatch')
        status_file = file.parent / 'batch-status.json'
        status = read_json(status_file) if status_file.exists() else {}
        if status.get('run_id') != meta['run_id'] or status.get('state') != 'completed':
            raise ValueError('run has not completed; run and pull first')
        yield file.parent, meta


def results(root):
    for folder, meta in load_runs(root):
        for entry in meta['clips']:
            if not SAFE.fullmatch(entry['name']):
                raise ValueError('unsafe run filename')
            tsv = folder / Path(entry['name']).with_suffix('.tsv')
            records, broken = parse_tsv(tsv) if tsv.exists() else ([], 1)
            lines = []
            previous = 0.
            for record in records:
                if record['kind'] != 'final':
                    continue
                end = None if record['audio_ms'] is None else min(entry['seconds'], max(
                    0., (record['audio_ms'] - meta['padding_ms']) / 1000))
                lines.append(dict(start=previous if end is not None else None, end=end, text=record['text']))
                previous = end
            row = dict(id=entry['id'], config=config(meta['speed_pct']), lines=lines,
                       seconds=entry['seconds'], callbacks=len(lines),
                       errors=broken + sum(r['kind'] == 'error' for r in records))
            yield row, records, meta


def collect(root):
    rows = [row for row, _, _ in results(root)]
    if not rows:
        raise ValueError('no runs to collect')
    atomic(root / '.local/demo/asr/pleos.jsonl', ''.join(
        json.dumps(row, ensure_ascii=False, allow_nan=False) + '\n' for row in rows))
    return rows


def stats(root):
    samples = {row['id']: row for row in read_sample(root)}
    clips = []
    for row, records, meta in results(root):
        chars = sum(len(line['text']) for line in row['lines'])
        whisper = sum(len(turn) for turn in samples[row['id']]['turns'])
        delays = [r['received_ms'] - r['audio_ms'] * 100 / meta['speed_pct'] for r in records
                  if r['kind'] == 'final' and r['received_ms'] is not None and r['audio_ms'] is not None]
        clips.append(dict(id=row['id'], config=row['config'], finals=row['callbacks'], chars=chars,
                          errors=row['errors'], whisper_chars=whisper,
                          char_ratio=chars / whisper if whisper else None,
                          callback_delay_ms=dict(count=len(delays), mean=sum(delays) / len(delays) if delays else None,
                                                 min=min(delays) if delays else None, max=max(delays) if delays else None)))
    report = dict(clips=clips, no_result_clips=sum(c['finals'] == 0 for c in clips),
                  clip_configs=len(clips),
                  by_config={name: dict(clips=sum(c['config'] == name for c in clips),
                                        no_result_clips=sum(c['config'] == name and c['finals'] == 0 for c in clips))
                             for name in sorted({c['config'] for c in clips})},
                  latency_definition='received_ms - audio_ms * 100 / speed_pct; delivery lag, not utterance latency')
    if not clips:
        raise ValueError('no runs for stats')
    write_json(base(root) / 'stats.json', report)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'push', 'run', 'pull', 'collect', 'stats', 'clean'])
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--adb', default=None)
    parser.add_argument('--user', type=int, help='Android user ID (default: am get-current-user)')
    parser.add_argument('--start-timeout', type=float, default=15, help='seconds to observe service startup')
    parser.add_argument('--ids', nargs='+')
    parser.add_argument('--limit', type=int)
    parser.add_argument('--speed-pct', type=int, default=100)
    parser.add_argument('--padding-ms', type=int, choices=[0, 500], default=0)
    parser.add_argument('--only')
    parser.add_argument('--timeout', type=float, default=3000)
    parser.add_argument('--poll', type=float, default=2)
    args = parser.parse_args(argv)
    if (not 25 <= args.speed_pct <= 1600 or not math.isfinite(args.timeout) or not math.isfinite(args.poll)
            or args.timeout <= 0 or args.poll <= 0 or not math.isfinite(args.start_timeout)
            or args.start_timeout <= 0 or (args.user is not None and args.user < 0)
            or (args.limit is not None and args.limit <= 0)):
        parser.error('invalid speed, limit, or timeout')
    def call(arguments, **kwargs):
        budget = min(30, args.timeout, kwargs.pop('timeout', 30))
        return adb(arguments, args.serial, args.adb, timeout=budget, **kwargs)
    try:
        if args.command == 'prepare':
            prepare(args.root, args.ids, args.limit)
        elif args.command == 'push':
            push(args.root, call, args.user)
        elif args.command == 'run':
            run(args.root, call, args.speed_pct, args.padding_ms, args.timeout, args.poll, args.only,
                user=args.user, start_timeout=args.start_timeout)
        elif args.command == 'pull':
            pull(args.root, call, args.speed_pct, args.user)
        elif args.command == 'collect':
            collect(args.root)
        elif args.command == 'stats':
            stats(args.root)
        else:
            clean(call, args.user)
    except (OSError, ValueError, KeyError, RuntimeError, TimeoutError) as error:
        # Neither malformed input nor SDK/adb exception messages may reveal speech.
        diagnostic = f'Pleos {args.command} failed: stage={args.command}'
        if isinstance(error, AdbError):
            diagnostic += (f'/{error.stage}; exception={type(error).__name__}'
                           f'; cause={error.kind or type(error).__name__}'
                           f'; adb_exit={error.returncode if error.returncode is not None else "unavailable"}')
            if error.detail:
                diagnostic += f'; output={error.detail}'
        else:
            if isinstance(error, (TransferError, ServiceStartTimeout)):
                diagnostic += f'/{error.stage}; reason={error.reason}'
            diagnostic += f'; exception={type(error).__name__}; adb_exit=unavailable'
        print(diagnostic, file=sys.stderr)
        return 1
    print(f'Pleos {args.command} completed.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
