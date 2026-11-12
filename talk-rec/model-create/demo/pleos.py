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
import shutil
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


def base(root, workspace=None):
    if workspace is not None:
        return Path(workspace) / "pleos"
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


def manifest(root, workspace=None):
    entries = read_json(base(root, workspace) / 'manifest.json')['clips']
    if (not entries and workspace is None) or len({e['name'] for e in entries}) != len(entries):
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


def push(root, call, user=None, *, workspace=None, verify_timeout=10,
         now=time.monotonic, sleep=time.sleep):
    if not math.isfinite(verify_timeout) or verify_timeout <= 0:
        raise ValueError('invalid push verification timeout')
    entries = manifest(root, workspace)
    if not entries:
        return
    for entry in entries:
        if not (base(root, workspace) / 'pcm' / entry['name']).is_file():
            raise ValueError('prepared PCM missing')
    user = current_user(call, user)
    clean(call, user)
    call(app_shell(user, 'mkdir -p ' + REMOTE))
    for entry in entries:
        source = base(root, workspace) / 'pcm' / entry['name']
        destination = REMOTE + '/' + entry['name']
        with source.open('rb') as stream:
            call(run_as(user, 'sh', '-c', 'cat > ' + destination, mode='exec-in'), stdin=stream)
            expected = os.fstat(stream.fileno()).st_size
        # exec-in can return before the device's cat finishes writing. Retry only
        # undersized files, using direct stat and a separate deadline per file.
        deadline = now() + verify_timeout
        while True:
            remaining = deadline - now()
            if remaining <= 0:
                raise TransferError('push-verify', 'PCM size mismatch')
            try:
                size = call(run_as(user, 'stat', '-c', '%s', destination, mode='exec-out'),
                            timeout=remaining).strip()
            except AdbError as error:
                if error.kind == 'TimeoutExpired':
                    raise TransferError('push-verify', 'PCM size mismatch') from None
                raise
            if not size.isdigit() or int(size) > expected or now() > deadline:
                raise TransferError('push-verify', 'PCM size mismatch')
            if int(size) == expected:
                break
            sleep(min(.2, max(0, deadline - now())))


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
        now=time.monotonic, sleep=time.sleep, user=None, start_timeout=15, *, workspace=None):
    if workspace is not None and speed != 100:
        raise ValueError('real sources require speed 100')
    entries = manifest(root, workspace)
    if not entries:
        return
    if only:
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', only):
            raise ValueError('unsafe filename prefix')
        entries = [e for e in entries if e['name'].startswith(only)]
    if not entries:
        raise ValueError('prefix selected no clips')
    user = current_user(call, user)
    run_id = uuid.uuid4().hex
    folder = base(root, workspace) / 'raw' / config(speed)
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


def pull(root, call, speed=100, user=None, *, workspace=None):
    if workspace is not None and not manifest(root, workspace):
        return
    folder = base(root, workspace) / 'raw' / config(speed)
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


def load_runs(root, workspace=None):
    for file in sorted((base(root, workspace) / 'raw').glob('pleos*/run.json')):
        meta = read_json(file)
        if file.parent.name != config(meta['speed_pct']):
            raise ValueError('run speed mismatch')
        status_file = file.parent / 'batch-status.json'
        status = read_json(status_file) if status_file.exists() else {}
        if status.get('run_id') != meta['run_id'] or status.get('state') != 'completed':
            raise ValueError('run has not completed; run and pull first')
        yield file.parent, meta


def results(root, workspace=None):
    for folder, meta in load_runs(root, workspace):
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
                if record['audio_ms'] is None:
                    if workspace is not None:
                        broken += 1
                        continue
                    raise ValueError('missing final audio position')
                end = max(previous, min(entry['seconds'], max(
                    0., (record['audio_ms'] - meta['padding_ms']) / 1000)))
                lines.append(dict(start=previous if end is not None else None, end=end, text=record['text']))
                previous = end
            row = dict(id=entry['id'], config=config(meta['speed_pct']), lines=lines,
                       seconds=entry['seconds'], callbacks=len(lines),
                       errors=broken + sum(r['kind'] == 'error' for r in records))
            yield row, records, meta


def asr_row(row):
    from demo.asr.data import validate_result
    result = {key: row[key] for key in ('id', 'config', 'lines', 'seconds')}
    result.update(avg_logprob=None, no_speech_prob=None)
    return validate_result(result)


def collect(root, *, workspace=None):
    if workspace is not None:
        return collect_sources(root, workspace)
    rows = [asr_row(row) for row, _, _ in results(root)]
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


def stream_pcm(source, destination):
    """Decode a whole source with bounded memory; raw little-endian PCM16."""
    import av
    import array
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    samples = 0
    try:
        with av.open(str(source)) as container, destination.open('wb') as output:
            resampler = av.AudioResampler(format='s16', layout='mono', rate=16000)
            def write(frame):
                nonlocal samples
                payload = bytes(frame.planes[0])[:frame.samples * 2]
                if sys.byteorder != 'little':
                    values = array.array('h', payload)
                    values.byteswap()
                    payload = values.tobytes()
                output.write(payload)
                samples += frame.samples
            for frame in container.decode(audio=0):
                for converted in resampler.resample(frame):
                    write(converted)
            for converted in resampler.resample(None):
                write(converted)
        if not samples:
            raise ValueError('empty source audio')
        return samples / 16000
    except Exception:
        destination.unlink(missing_ok=True)
        raise ValueError('PCM conversion failed') from None


def prepare_sources(root, workspace, chunk_seconds=90, ids=None, limit=None):
    """Split each approved range, retaining original source offsets."""
    from eval.real import discover_sources, read_objects
    if not math.isfinite(chunk_seconds) or not 0 < chunk_seconds <= 90:
        raise ValueError('chunk limit is 90 seconds (device PCM limit is 95 seconds)')
    rows = [r for r in discover_sources(root, workspace) if r['status'] == 'ready']
    if ids and set(ids) - {r['id'] for r in rows}:
        raise ValueError('source not approved or unavailable')
    rows = [r for r in rows if not ids or r['id'] in ids][:limit]
    completed = {r['id'] for r in read_objects(Path(workspace) / 'asr.jsonl')}
    folder = base(root, workspace)
    old = read_json(folder / 'plan.json') if (folder / 'plan.json').exists() else {}
    plan = dict(old)
    def bounds(ranges):
        return [[r['start'], r['end']] for r in ranges]
    changed_ranges = False
    # Preflight every selected source before deleting or generating anything.
    for row in rows:
        video = row['id']
        if video not in old:
            continue
        source = Path(root) / '.local/audio' / (video + '.webm')
        if old[video]['signature'] != [source.stat().st_size, source.stat().st_mtime_ns, chunk_seconds]:
            raise ValueError('source or chunk size changed; choose a new run')
        previous = old[video].get('ranges', [dict(start=0, end=row['seconds'])])
        changed_ranges |= bounds(previous) != bounds(row['ranges'])
    if changed_ranges:
        # A successful chunk anywhere in this run freezes its plan, even before collect.
        protected = bool(completed) or any((folder / 'parts').glob('*.json'))
        protected |= any(p.read_bytes().strip() for p in (Path(workspace) / 'judgments').glob('*.jsonl'))
        for path in (folder / 'raw').glob('pleos*/*.tsv'):
            records, broken = parse_tsv(path)
            if not broken and not any(r['kind'] == 'error' or
                                      (r['kind'] == 'final' and r['audio_ms'] is None) for r in records):
                protected = True
        if protected:
            raise ValueError('ranges changed; choose a new run')
        # Resolve/check all recursive targets within this run before deleting them.
        workspace_path = Path(workspace).resolve()
        targets = [folder / 'pcm', folder / 'raw', Path(workspace) / 'clips']
        if any(not p.resolve().is_relative_to(workspace_path) for p in targets):
            raise ValueError('unsafe preparation path')
        for path in targets:
            if path.exists():
                shutil.rmtree(path)
        for path in (folder / 'plan.json', folder / 'manifest.json', Path(workspace) / 'blocks.jsonl'):
            path.unlink(missing_ok=True)
        plan = {}
    for row in rows:
        video = row['id']
        source = Path(root) / '.local/audio' / (video + '.webm')
        signature = [source.stat().st_size, source.stat().st_mtime_ns, chunk_seconds]
        if video in plan:
            continue
        if f'yt:{video}@0' in completed:
            continue
        temporary = folder / 'source.pcm'
        try:
            seconds = stream_pcm(source, temporary)
            entries = []
            with temporary.open('rb') as stream:
                for range_index, usable in enumerate(row['ranges']):
                    # Quantize inward: not even a partial sample may escape a range.
                    offset = math.ceil(usable['start'] * 16000)
                    stop = min(math.floor(usable['end'] * 16000), int(seconds * 16000))
                    stream.seek(offset * 2)
                    while offset < stop:
                        count = min(stop-offset, max(1, int(chunk_seconds * 16000)))
                        payload = stream.read(count * 2)
                        if len(payload) != count * 2:
                            raise ValueError('source PCM length mismatch')
                        identity = f'yt:{video}@{len(entries)}'
                        name = pcm_name(identity)
                        atomic(folder / 'pcm' / name, payload)
                        entries.append(dict(id=identity, source_id=f'yt:{video}@0', name=name,
                                            seconds=count / 16000, offset=offset / 16000,
                                            range_index=range_index))
                        offset += count
            plan[video] = dict(signature=signature, seconds=row['seconds'], entries=entries,
                               ranges=row['ranges'])
            write_json(folder / 'plan.json', plan)
        finally:
            temporary.unlink(missing_ok=True)
    entries = [e for r in rows if f"yt:{r['id']}@0" not in completed
               for e in plan[r['id']]['entries']
               if not (folder / 'parts' / (e['name'] + '.json')).exists()]
    write_json(folder / 'manifest.json', dict(version=2, clips=entries))
    return entries


def collect_sources(root, workspace):
    from eval.real import read_objects, write_objects
    folder = base(root, workspace)
    plan = read_json(folder / 'plan.json') if (folder / 'plan.json').exists() else {}
    failed = False
    seen = set()
    pending = manifest(root, workspace)
    for row, _, meta in results(root, workspace) if pending else []:
        entry = next(e for e in meta['clips'] if e['id'] == row['id'])
        seen.add(entry['name'])
        if meta['speed_pct'] != 100 or row['errors']:
            failed = True
            continue
        write_json(folder / 'parts' / (entry['name'] + '.json'), asr_row(row))
    if any(e['name'] not in seen for e in pending):
        failed = True
    existing = {r['id']: r for r in read_objects(Path(workspace) / 'asr.jsonl')}
    for video, source in sorted(plan.items()):
        identity = f'yt:{video}@0'
        if identity in existing:
            continue
        paths = [folder / 'parts' / (e['name'] + '.json') for e in source['entries']]
        if not all(p.exists() for p in paths):
            continue
        lines = []
        for entry, path in zip(source['entries'], paths):
            part = read_json(path)
            for line in part['lines']:
                lines.append(dict(start=line['start'] + entry['offset'],
                                  end=line['end'] + entry['offset'], text=line['text']))
        existing[identity] = asr_row(dict(id=identity, config='pleos', lines=lines, seconds=source['seconds']))
    write_objects(Path(workspace) / 'asr.jsonl', list(existing.values()))
    if failed:
        raise ValueError('invalid chunk result; successful chunks retained')
    return list(existing.values())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'push', 'run', 'pull', 'collect', 'stats', 'clean'])
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--real', help='실제 평가 실행 이름 (예: v1)')
    parser.add_argument('--chunk-seconds', type=float, default=90, help='구간 내 조각 길이 (최대 90초)')
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--adb', default=None)
    parser.add_argument('--user', type=int, help='Android user ID (default: am get-current-user)')
    parser.add_argument('--start-timeout', type=float, default=15, help='seconds to observe service startup')
    parser.add_argument('--push-verify-timeout', type=float, default=10,
                        help='seconds to wait for each pushed PCM size to match')
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
            or not math.isfinite(args.push_verify_timeout) or args.push_verify_timeout <= 0
            or (args.limit is not None and args.limit <= 0)):
        parser.error('invalid speed, limit, or timeout')
    def call(arguments, **kwargs):
        budget = min(30, args.timeout, kwargs.pop('timeout', 30))
        return adb(arguments, args.serial, args.adb, timeout=budget, **kwargs)
    try:
        workspace = None
        if args.real:
            from eval.real import run_directory
            workspace = run_directory(args.root, args.real)
        if args.command == 'prepare':
            if workspace is None:
                prepare(args.root, args.ids, args.limit)
            else:
                entries = prepare_sources(args.root, workspace, args.chunk_seconds, args.ids, args.limit)
                print(json.dumps(dict(chunks=len(entries), seconds=sum(e['seconds'] for e in entries))))
        elif args.command == 'push':
            push(args.root, call, args.user, workspace=workspace,
                 verify_timeout=args.push_verify_timeout)
        elif args.command == 'run':
            run(args.root, call, args.speed_pct, args.padding_ms, args.timeout, args.poll, args.only,
                user=args.user, start_timeout=args.start_timeout, workspace=workspace)
        elif args.command == 'pull':
            pull(args.root, call, args.speed_pct, args.user, workspace=workspace)
        elif args.command == 'collect':
            collect(args.root, workspace=workspace)
        elif args.command == 'stats':
            if workspace is not None:
                raise ValueError('use eval.real stats')
            stats(args.root)
        else:
            clean(call, args.user)
    except (OSError, ValueError, KeyError, RuntimeError, TimeoutError) as error:
        # Neither malformed input nor SDK/adb exception messages may reveal speech.
        if args.real:
            from eval.real import RangeError
            if isinstance(error, RangeError):
                print(str(error), file=sys.stderr)
                return 1
            trace = error.__traceback__
            while trace.tb_next:
                trace = trace.tb_next
            print(f'{type(error).__name__} {Path(trace.tb_frame.f_code.co_filename).name}:{trace.tb_lineno}', file=sys.stderr)
            return 1
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
