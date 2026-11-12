"""T06: resumable CLI silver labeling. See label.md for operation and recovery."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
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
import threading
import time
from typing import Protocol
from urllib.parse import quote

from common.schema import validate_label, validate_utterance, validate_window

ROOT = Path(__file__).resolve().parents[1]
LIMIT_EXIT = 3


def digest(value) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode('utf-8')).hexdigest()


def read_jsonl(path):
    try:
        with Path(path).open(encoding='utf-8') as stream:
            return [json.loads(line) for line in stream if line.strip()]
    except (ValueError, UnicodeError):
        raise ValueError('invalid UTF-8 JSONL input') from None


def atomic_text(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.tmp-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def jsonl(records):
    return ''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in records)


def source_filename(source):
    name = quote(source, safe='')
    if name.lower() in {'run', 'unclear'}:
        name = f'%{ord(name[0]):02X}' + name[1:]
    return name + '.jsonl'


@dataclass(frozen=True)
class Chunk:
    source: str
    clip: int
    index: int
    turns: tuple[dict, ...]
    overlap: int

    @property
    def id(self):
        return f'{self.source}/clip:{self.clip}/chunk:{self.index}'


def chunk_clip(source, clip, turns, size=40):
    if size <= 3:
        raise ValueError('chunk size must exceed overlap')
    offset, index = 0, 0
    while offset < len(turns):
        end = min(offset + size, len(turns))
        yield Chunk(source, clip, index, tuple(turns[offset:end]), 0 if index == 0 else 3)
        if end == len(turns):
            break
        offset, index = end - 3, index + 1


def load_clips(windows, utterances, split, source=None, limit=None):
    selected = {}
    seen = set()
    for row in read_jsonl(Path(windows) / f'{split}.jsonl'):
        validate_window(row)
        if row['split'] != split:
            raise ValueError('window split mismatch')
        if row['id'] in seen:
            raise ValueError('duplicate window id')
        seen.add(row['id'])
        if source is None or row['source'] == source:
            selected.setdefault((row['source'], row['clip']), []).append(row)
    keys = sorted(selected)
    if limit is not None:
        keys = keys[:limit]
    sources = {key[0] for key in keys}
    timing = {}
    for path in sorted(Path(utterances).glob('*.jsonl')):
        for row in read_jsonl(path):
            if not isinstance(row, dict):
                raise ValueError('utterance must be an object')
            if row.get('source') not in sources:
                continue
            validate_utterance(row)
            key = (row['source'], row['utt'])
            if key in timing:
                raise ValueError('duplicate utterance id')
            timing[key] = row
    # Gaps always refer to the actual preceding utterance, even at clip/chunk boundaries.
    gaps = {}
    for name in sources:
        previous = None
        for row in sorted((r for r in timing.values() if r['source'] == name),
                          key=lambda r: (r['start'], r['utt'])):
            gaps[(name, row['utt'])] = 0 if previous is None else row['start'] - previous['end']
            previous = row
    clips = []
    for name, number in keys:
        turns = []
        for window in selected[(name, number)]:
            utt = int(window['id'].rpartition('#')[2])
            row = timing.get((name, utt))
            if row is None or row['text'] != window['current']:
                raise ValueError('window does not resolve to matching utterance')
            turns.append(dict(row, gap=gaps[(name, utt)]))
        turns.sort(key=lambda r: (r['start'], r['utt']))
        clips.append(list(chunk_clip(name, number, turns)))
    return clips


def prompt_templates():
    template = (ROOT / 'prompts/labeler.md').read_text(encoding='utf-8')
    blocks = re.findall(r'```text\s*\n(.*?)\n```', template, re.S)
    if len(blocks) != 2:
        raise ValueError('expected system and user prompt templates')
    return blocks[0].replace('{{RUBRIC}}', (ROOT / 'rubric.md').read_text(encoding='utf-8')), blocks[1]


def render_prompt(chunk, template):
    lines = []
    for row in chunk.turns:
        gap = row['gap']
        # Do not round a gap just above 20s down to the context boundary.
        seconds = str(gap)
        if seconds.endswith('.0'):
            seconds = seconds[:-2]
        sign = '+' if gap >= 0 else ''
        lines.append(f"[{row['utt']}] ({sign}{seconds}) {row['text']}")
    return template.replace('{{SOURCE_KIND}}', 'youtube' if chunk.source.startswith('yt:') else 'corpus').replace('{{CLIP}}', '\n'.join(lines))


def parse_answer(text, expected_utts=None):
    decoder = json.JSONDecoder()
    candidates = []
    for match in re.finditer(r'\[', text):
        try:
            result, _ = decoder.raw_decode(text[match.start():])
        except ValueError:
            continue
        if isinstance(result, list) and all(isinstance(item, dict) for item in result):
            candidates.append(result)
            if expected_utts is not None and [item.get('utt') for item in result] == expected_utts:
                return result
    if candidates:
        return next((candidate for candidate in candidates if candidate), candidates[0])
    raise ValueError('answer must contain a JSON array of objects')


def validate_answer(items, chunk, labeler):
    if len(items) != len(chunk.turns):
        raise ValueError('item count must match input')
    result, warnings = [], []
    for item, turn in zip(items, chunk.turns):
        if not isinstance(item, dict) or type(item.get('utt')) is not int or item['utt'] != turn['utt']:
            raise ValueError('utt numbers and order must match input')
        if set(item) != {'utt', 'story', 'kind', 'exclude', 'skip', 'note'}:
            raise ValueError('answer fields must match prompt schema')
        record = {k: v for k, v in item.items() if k != 'utt'}
        record.update(id=f"{chunk.source}#{turn['utt']}", labeler=labeler)
        validate_label(record)
        if record['skip'] and not chunk.source.startswith('yt:'):
            warnings.append(record['id'])
        result.append(record)
    return result, warnings


class BackendError(Exception):
    """Sanitized error: never include CLI output, which can contain transcripts."""

    def __init__(self, message, usage=None):
        super().__init__(message)
        self.usage = usage or {}


class UsageLimit(BackendError):
    pass


class Backend(Protocol):
    usage: dict

    def complete(self, system_prompt: str, user_message: str) -> str: ...


def combined_prompt(system_prompt, user_message):
    return system_prompt + '\n\n' + user_message


def run_process(argv, prompt, cwd, timeout):
    env = dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONUTF8='1')
    try:
        result = subprocess.run(argv, input=prompt, capture_output=True, text=True,
                                encoding='utf-8', errors='replace', cwd=cwd,
                                env=env, timeout=timeout, shell=False)
    except subprocess.TimeoutExpired:
        raise BackendError('CLI timeout') from None
    except OSError:
        raise BackendError('CLI could not be started') from None
    if result.returncode:
        output = result.stdout + '\n' + result.stderr
        if re.search(r'rate[ _-]?limit|usage[ _-]?limit|subscription.{0,50}limit|quota.{0,30}(exceed|exhaust)|exceed.{0,30}quota|resource_exhausted|too many requests|\b429\b|limit.{0,25}(reached|exceeded)|한도.{0,15}(초과|도달)', output, re.I):
            raise UsageLimit('CLI subscription/usage or rate limit', token_usage(result.stdout, result.stderr))
        category = 'CLI failure'
        for pattern, name in [(r'home directory', 'home directory unavailable'),
                              (r'sandbox|permission denied|access is denied', 'sandbox/permission failure'),
                              (r'auth|unauthorized|\b401\b', 'authentication failure'),
                              (r'network|connect|dns', 'network failure')]:
            if re.search(pattern, output, re.I):
                category = name
                break
        raise BackendError(f'{category} (exit {result.returncode})', token_usage(result.stdout, result.stderr))
    return result


def token_usage(stdout, stderr):
    usage = {}
    for line in stdout.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if isinstance(event, dict) and isinstance(event.get('usage'), dict):
            for key, value in event['usage'].items():
                if 'token' in key and type(value) is int:
                    usage[key] = value
    match = re.search(r'tokens used\s*\n\s*([\d,]+)', stderr, re.I)
    if match:
        usage['total_tokens'] = int(match[1].replace(',', ''))
    return usage


class CodexBackend:
    def __init__(self, model, effort='high', timeout=600):
        self.model, self.effort, self.timeout = model, effort, timeout
        self.usage = {}

    def command(self, cwd, final):
        executable = shutil.which('codex.exe') or shutil.which('codex.cmd') or shutil.which('codex')
        if not executable:
            raise BackendError('codex executable not found')
        launcher = [executable]
        if Path(executable).suffix.lower() == '.cmd':
            # npm's .cmd shim goes through cmd.exe, which can corrupt quoted TOML
            # arguments. Invoke the exact same installed JS launcher via Node.
            script = Path(executable).parent / 'node_modules/@openai/codex/bin/codex.js'
            if script.is_file():
                node = Path(executable).parent / 'node.exe'
                node = str(node) if node.is_file() else shutil.which('node.exe') or shutil.which('node')
                if not node:
                    raise BackendError('npm Codex launcher requires Node')
                launcher = [node, str(script)]
        return launcher + ['exec', '--model', self.model, '--config',
                f'model_reasoning_effort="{self.effort}"', '--sandbox', 'read-only',
                '--skip-git-repo-check', '--ephemeral', '--ignore-user-config',
                '--ignore-rules', '--config', 'web_search="disabled"',
                '--config', 'developer_instructions="Label only the supplied input. Do not use tools, read files, or access the network. Return only the requested JSON array."',
                '--color', 'never', '--json', '--cd', str(cwd),
                '--output-last-message', str(final), '-']

    def complete(self, system_prompt, user_message):
        self.usage = {}
        # The cwd is empty; the final response is written outside it by the CLI.
        with tempfile.TemporaryDirectory(prefix='silver-label-') as temp:
            parent = Path(temp)
            cwd = parent / 'empty'
            cwd.mkdir()
            final = parent / 'final.txt'
            result = run_process(self.command(cwd, final), combined_prompt(system_prompt, user_message), cwd, self.timeout)
            self.usage = token_usage(result.stdout, result.stderr)
            # Reject actual tool use instead of accepting a potentially contaminated answer.
            for line in result.stdout.splitlines():
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if isinstance(event, dict) and event.get('type') in {'item.started', 'item.completed'}:
                    item = event.get('item', {})
                    if item.get('type') in {'command_execution', 'mcp_tool_call', 'web_search', 'file_change'}:
                        raise BackendError('unexpected Codex tool use')
            if not final.exists():
                raise BackendError('CLI did not write a final message')
            return final.read_text(encoding='utf-8')


AGY_COMMAND_LIMIT = 32000  # UTF-16 code units, including quoting and terminating NUL.
AGY_LIMIT_PATTERN = re.compile(
    r'rate[ _-]?limit|usage[ _-]?limit|subscription.{0,50}limit|quota.{0,30}(exceed|exhaust)|'
    r'exceed.{0,30}quota|resource_exhausted|too many requests|\b429\b|'
    r'limit.{0,25}(reached|exceeded)|한도.{0,15}(초과|도달)', re.I)


def default_agy_path():
    local = os.environ.get('LOCALAPPDATA')
    if local:
        installed = Path(local) / 'Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/agy/bin/agy.exe'
        if installed.is_file():
            return str(installed)
    return shutil.which('agy.exe') or shutil.which('agy')


def agy_model(model, effort):
    match = re.search(r'-(low|medium|high)$', model)
    if match:
        return model[:match.start()], match[1]
    if effort not in {'low', 'medium', 'high'}:
        raise ValueError('agy effort must be low, medium or high')
    return model, effort


def agy_prompt(system_prompt, user_message):
    return f'--- BEGIN SYSTEM PROMPT ---\n{system_prompt}\n--- END SYSTEM PROMPT ---\n\n--- BEGIN USER MESSAGE ---\n{user_message}\n--- END USER MESSAGE ---'


def windows_command_length(argv):
    return len(subprocess.list2cmdline(argv).encode('utf-16-le')) // 2 + 1


def agy_arguments(executable, model, effort, prompt):
    model, effort = agy_model(model, effort)
    return [executable, '--model', f'{model}-{effort}', '--output-format', 'json',
            '--sandbox', '--print=' + prompt]


def system_ansi_encoding():
    if os.name == 'nt':
        import ctypes
        return f'cp{ctypes.windll.kernel32.GetACP()}'
    import locale
    return locale.getpreferredencoding(False)


def decode_agy(data):
    for encoding in dict.fromkeys(('utf-8', system_ansi_encoding())):
        try:
            text = data.decode(encoding, errors='strict')
        except UnicodeError:
            continue
        if '\ufffd' in text:
            raise BackendError('agy output contains replacement characters')
        return text
    raise BackendError('agy output is neither strict UTF-8 nor system ANSI')


class AgyBackend:
    format_prompt = staticmethod(agy_prompt)

    def __init__(self, model='gemini-3.8-flash', effort='medium', timeout=600, path=None):
        self.model, self.effort = agy_model(model, effort)
        self.timeout, self.usage = timeout, {}
        self.executable = path or default_agy_path()
        if self.executable and (Path(self.executable).is_file() or '/' in self.executable or '\\' in self.executable):
            self.executable = str(Path(self.executable).resolve())

    def command(self, prompt):
        if not self.executable:
            raise BackendError('agy executable not found; supply --agy-path')
        argv = agy_arguments(self.executable, self.model, self.effort, prompt)
        size = windows_command_length(argv)
        if size > AGY_COMMAND_LIMIT:
            raise BackendError(f'agy command line is {size} UTF-16 units; safe limit is {AGY_COMMAND_LIMIT}; reduce chunk size (prompt was not truncated)')
        return argv

    def complete(self, system_prompt, user_message):
        self.usage = {}
        argv = self.command(self.format_prompt(system_prompt, user_message))
        # agy does not read a plain stdin prompt. Never allow interactive reads.
        with tempfile.TemporaryDirectory(prefix='silver-agy-') as cwd:
            try:
                result = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True,
                                        cwd=cwd, timeout=self.timeout, shell=False,
                                        env=dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONUTF8='1'))
            except subprocess.TimeoutExpired:
                raise BackendError('agy CLI timeout') from None
            except OSError:
                raise BackendError('agy CLI could not be started') from None
        stdout, stderr = decode_agy(result.stdout), decode_agy(result.stderr)
        try:
            envelope = json.loads(stdout)
        except ValueError:
            if AGY_LIMIT_PATTERN.search(stdout + '\n' + stderr):
                raise UsageLimit('agy subscription/usage or rate limit') from None
            raise BackendError('agy did not return a JSON object') from None
        if isinstance(envelope, dict) and isinstance(envelope.get('usage'), dict):
            self.usage = {k: v for k, v in envelope['usage'].items() if 'token' in k and type(v) is int}
        if AGY_LIMIT_PATTERN.search(json.dumps(envelope, ensure_ascii=False) + '\n' + stderr):
            raise UsageLimit('agy subscription/usage or rate limit', self.usage)
        if not isinstance(envelope, dict):
            raise BackendError('agy did not return a JSON object')
        if result.returncode:
            raise BackendError(f'agy CLI failure (exit {result.returncode})', self.usage)
        if envelope.get('status') != 'SUCCESS':
            raise BackendError('agy status is not SUCCESS', self.usage)
        # A successful agy envelope omits denied_actions entirely.
        if envelope.get('denied_actions', []) != []:
            raise BackendError('agy denied actions', self.usage)
        response = envelope.get('response')
        if not isinstance(response, str) or not response.strip():
            raise BackendError('agy response is empty or invalid', self.usage)
        if '\ufffd' in response:
            raise BackendError('agy response contains replacement characters', self.usage)
        return response


def command_arguments(command):
    if os.name != 'nt':
        return shlex.split(command)
    # Use Windows' own quoting rules, preserving backslashes and quoted paths.
    import ctypes
    from ctypes import wintypes
    count = ctypes.c_int()
    parse = ctypes.windll.shell32.CommandLineToArgvW
    parse.argtypes = [wintypes.LPCWSTR, ctypes.POINTER(ctypes.c_int)]
    parse.restype = ctypes.POINTER(wintypes.LPWSTR)
    values = parse(command, ctypes.byref(count))
    if not values:
        raise ValueError('invalid command template')
    try:
        return [values[i] for i in range(count.value)]
    finally:
        ctypes.windll.kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        ctypes.windll.kernel32.LocalFree(values)


class CommandBackend:
    def __init__(self, command, model, effort, timeout=600):
        self.argv = [arg.replace('{model}', model).replace('{effort}', effort)
                     for arg in command_arguments(command)]
        if not self.argv:
            raise ValueError('command must not be empty')
        self.timeout, self.usage = timeout, {}

    def complete(self, system_prompt, user_message):
        self.usage = {}
        with tempfile.TemporaryDirectory(prefix='silver-command-') as cwd:
            result = run_process(self.argv, combined_prompt(system_prompt, user_message), cwd, self.timeout)
        self.usage = token_usage(result.stdout, result.stderr)
        return result.stdout


class FakeBackend:
    def __init__(self, responses=None):
        self.responses = iter(responses) if responses is not None else None
        self.calls, self.usage = [], {}

    def complete(self, system_prompt, user_message):
        self.calls.append((system_prompt, user_message))
        if self.responses is not None:
            response = next(self.responses)
            if isinstance(response, BaseException):
                raise response
            return response
        return json.dumps([dict(utt=int(n), story=1, kind=[], exclude=[], skip=False,
                                note='테스트용 결정적 라벨')
                           for n in re.findall(r'^\[(\d+)\]', user_message, re.M)], ensure_ascii=False)


class Store:
    """Atomic chunk journals are authoritative; JSONL files are recoverable views."""
    def __init__(self, directory, signature):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.signature = signature
        self.entries = {}
        metadata = self.directory / '.config.json'
        if metadata.exists() and json.loads(metadata.read_text(encoding='utf-8')) != signature:
            raise ValueError('output configuration changed; use a new --run-tag')
        atomic_text(metadata, json.dumps(signature))
        for path in (self.directory / '.chunks').glob('*.json'):
            entry = json.loads(path.read_text(encoding='utf-8'))
            self.entries[entry['log']['chunk_id']] = entry
        self.materialize()

    def finished(self, chunk, fingerprint):
        previous = self.entries.get(chunk.id)
        if previous and previous['fingerprint'] != fingerprint:
            raise ValueError('chunk input changed; use a new --run-tag')
        return bool(previous and previous['log']['status'] == 'ok')

    def commit(self, chunk, fingerprint, labels, log):
        with self.lock:
            # Keep costs/errors from earlier interrupted or failed invocations.
            previous = self.entries.get(chunk.id)
            if previous:
                old = previous['log']
                log = dict(log)
                for key in ('attempts', 'wall_seconds', 'prompt_characters',
                            'validation_errors', 'backend_errors', 'limit_errors'):
                    log[key] = log.get(key, 0) + old.get(key, 0)
                merged_usage = dict(old.get('token_usage') or {})
                for key, value in (log.get('token_usage') or {}).items():
                    merged_usage[key] = merged_usage.get(key, 0) + value
                log['token_usage'] = merged_usage or None
            entry = dict(fingerprint=fingerprint, labels=labels, log=log)
            atomic_text(self.directory / '.chunks' / (digest(chunk.id) + '.json'), json.dumps(entry, ensure_ascii=False))
            self.entries[chunk.id] = entry
            self.materialize()

    def materialize(self):
        labels, logs = {}, []
        for key in sorted(self.entries):
            entry = self.entries[key]
            logs.append(entry['log'])
            for record in entry['labels']:
                validate_label(record)
                if record['id'] in labels:
                    raise ValueError('duplicate committed label id')
                labels[record['id']] = record
        by_source = {}
        for record in labels.values():
            by_source.setdefault(record['id'].rpartition('#')[0], []).append(record)
        for source, records in by_source.items():
            records.sort(key=lambda r: int(r['id'].rpartition('#')[2]))
            atomic_text(self.directory / source_filename(source), jsonl(records))
        atomic_text(self.directory / 'unclear.jsonl', jsonl([labels[k] for k in sorted(labels) if '기준 불명확' in labels[k]['note']]))
        atomic_text(self.directory / 'run.jsonl', jsonl(logs))


def run_clips(clips, store, backend_factory, system, template, labeler, retries=2,
              concurrency=1, wait_minutes=None, stop=None):
    stop = stop or threading.Event()
    limited = threading.Event()

    def process_clip(chunks):
        backend = backend_factory()
        for chunk in chunks:
            if stop.is_set():
                break
            user = render_prompt(chunk, template)
            fingerprint = digest([system, user, chunk.overlap])
            if store.finished(chunk, fingerprint):
                continue
            start = time.monotonic()
            attempts, errors, format_errors, limits, prompt_chars, usage = 0, 0, 0, 0, 0, {}
            status, error, labels, warning_ids = 'failed', '', [], []
            while errors <= retries and not stop.is_set():
                message = user + (f'\n\nValidation error: {error}\nReturn the complete corrected JSON array.' if error else '')
                attempts += 1
                prompt_chars += len(getattr(backend, 'format_prompt', combined_prompt)(system, message))
                try:
                    answer = backend.complete(system, message)
                    for key, value in getattr(backend, 'usage', {}).items():
                        usage[key] = usage.get(key, 0) + value
                    validated, warning_ids = validate_answer(parse_answer(answer, [t['utt'] for t in chunk.turns]), chunk, labeler)
                    labels = validated[chunk.overlap:]
                    status = 'ok'
                    break
                except UsageLimit as exc:
                    limits += 1
                    for key, value in exc.usage.items():
                        usage[key] = usage.get(key, 0) + value
                    if wait_minutes is not None:
                        print('CLI limit reached; waiting before retry (completed chunks are saved).', file=sys.stderr)
                        remaining = wait_minutes * 60
                        while remaining > 0 and not stop.is_set():
                            seconds = min(remaining, 30)
                            stop.wait(seconds)
                            remaining -= seconds
                        continue
                    status, error = 'limited', 'CLI subscription/usage or rate limit'
                    limited.set()
                    stop.set()
                    break
                except (ValueError, BackendError) as exc:
                    # Only our own schema/parser/backend messages may reach logs or retries.
                    error = str(exc)
                    errors += 1
                    format_errors += isinstance(exc, ValueError)
                    if isinstance(exc, BackendError):
                        for key, value in exc.usage.items():
                            usage[key] = usage.get(key, 0) + value
            log = dict(chunk_id=chunk.id, utterance_count=len(chunk.turns),
                       new_utterance_count=len(chunk.turns) - chunk.overlap,
                       attempts=attempts, wall_seconds=round(time.monotonic() - start, 6),
                       prompt_characters=prompt_chars, token_usage=usage or None,
                       validation_errors=format_errors, backend_errors=errors - format_errors,
                       limit_errors=limits,
                       status=status, error=error if status != 'ok' else None,
                       corpus_skip_ids=warning_ids)
            store.commit(chunk, fingerprint, labels, log)
            if warning_ids:
                print(f'Warning: corpus skip on {len(warning_ids)} utterance(s); see run.jsonl ids.', file=sys.stderr)
            print(f'{chunk.id}: {status}, attempts={attempts}', file=sys.stderr)

    executor = ThreadPoolExecutor(max_workers=concurrency)
    try:
        list(executor.map(process_clip, clips))
    except KeyboardInterrupt:
        stop.set()
        raise
    finally:
        executor.shutdown(wait=True, cancel_futures=True)
    if limited.is_set():
        print('CLI limit reached. Resume with the same command; completed chunks will be skipped. Use --wait-on-limit MINUTES to wait and retry.', file=sys.stderr)
        return LIMIT_EXIT
    return 1 if any(e['log']['status'] != 'ok' for e in store.entries.values()) else 0


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('--backend', choices=['codex', 'agy', 'command', 'fake'], default='codex')
    result.add_argument('--model', help='Codex default: gpt-6-luna (verified in installed models_cache.json)')
    result.add_argument('--effort', choices=['minimal', 'low', 'medium', 'high', 'xhigh'], default='high')
    result.add_argument('--command', help='argv template with optional {model} and {effort}; stdin prompt, stdout answer; no shell operators')
    result.add_argument('--agy-path', default=default_agy_path(), help='agy executable; default installed LocalAppData path, then PATH lookup')
    result.add_argument('--split', choices=['train', 'dev', 'test'], default='train')
    result.add_argument('--source')
    result.add_argument('--limit-clips', type=int)
    result.add_argument('--concurrency', type=int, default=1)
    result.add_argument('--retries', type=int, default=2, help='additional retries after initial attempt')
    result.add_argument('--wait-on-limit', type=float, metavar='MINUTES')
    result.add_argument('--timeout', type=float, default=600)
    result.add_argument('--dry-run', action='store_true')
    result.add_argument('--run-tag', default='')
    result.add_argument('--windows', type=Path, default=ROOT / '.local/windows')
    result.add_argument('--utterances', type=Path, default=ROOT / '.local/utterances')
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    if (args.concurrency < 1 or args.retries < 0 or args.timeout <= 0 or not math.isfinite(args.timeout) or
            (args.limit_clips is not None and args.limit_clips < 0) or
            (args.wait_on_limit is not None and (args.wait_on_limit <= 0 or not math.isfinite(args.wait_on_limit)))):
        parser().error('counts must be nonnegative, concurrency/timeout/wait must be positive')
    if args.backend == 'command' and (not args.command or not args.model):
        parser().error('--command and --model are required for the command backend')
    model = args.model or ('gpt-6-luna' if args.backend == 'codex' else 'gemini-3.8-flash' if args.backend == 'agy' else 'deterministic')
    if args.backend == 'agy':
        try:
            model, args.effort = agy_model(model, args.effort)
        except ValueError as exc:
            parser().error(str(exc))
    name = '-'.join(quote(part, safe='') for part in (args.backend, model, args.effort))
    if args.run_tag:
        name += '-' + quote(args.run_tag, safe='')
    directory = ROOT / '.local/labels' / name
    try:
        system, template = prompt_templates()
        clips = load_clips(args.windows, args.utterances, args.split, args.source, args.limit_clips)
        if args.dry_run:
            destination = directory / 'prompts'
            largest_prompt, largest_argv = 0, 0
            for chunks in clips:
                for chunk in chunks:
                    user = render_prompt(chunk, template)
                    prompt = agy_prompt(system, user) if args.backend == 'agy' else combined_prompt(system, user)
                    atomic_text(destination / (digest(chunk.id) + '.txt'), prompt)
                    largest_prompt = max(largest_prompt, len(prompt))
                    if args.backend == 'agy':
                        largest_argv = max(largest_argv, windows_command_length(agy_arguments(args.agy_path or 'agy.exe', model, args.effort, prompt)))
            print(f'Rendered {sum(map(len, clips))} prompts to {destination}')
            if args.backend == 'agy':
                print(f'Largest prompt: {largest_prompt} characters; largest Windows argv: {largest_argv} UTF-16 units including NUL; safe limit: {AGY_COMMAND_LIMIT}')
            return 0
        signature = dict(backend=args.backend, model=model, effort=args.effort,
                         command_hash=digest(args.command), prompt_hash=digest([system, template]))
        if args.backend == 'agy':
            signature['agy_path_hash'] = digest(args.agy_path)
        # OS advisory lock releases on process death; no stale-lock deletion is needed.
        directory.mkdir(parents=True, exist_ok=True)
        with (directory / '.lock').open('a+b') as lock:
            if os.name == 'nt':
                import msvcrt
                lock.seek(0)
                lock.write(b'0')
                lock.flush()
                lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            store = Store(directory, signature)
            def factory():
                if args.backend == 'codex':
                    return CodexBackend(model, args.effort, args.timeout)
                if args.backend == 'agy':
                    return AgyBackend(model, args.effort, args.timeout, args.agy_path)
                if args.backend == 'command':
                    return CommandBackend(args.command, model, args.effort, args.timeout)
                return FakeBackend()
            return run_clips(clips, store, factory, system, template,
                             f'llm:{args.backend}:{model}:{args.effort}', args.retries,
                             args.concurrency, args.wait_on_limit)
    except (OSError, ValueError):
        print('Input/output validation failed or output directory is locked; check inputs and use a new --run-tag for changed inputs/configuration.', file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print('Interrupted. Resume with the same command; completed chunks are saved.', file=sys.stderr)
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
