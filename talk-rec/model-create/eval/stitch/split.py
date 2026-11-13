"""T60 Part B: one local relay request per saved run (at most 12 segments)."""
import argparse
from collections import Counter
import contextlib
import errno
import http.client
import io
import json
from pathlib import Path
import sys
import time
from urllib.parse import urlsplit

from .core import build_runs, decide, histogram, parse_policy, quantile
from .measure import ROOT, load_dev
from tools.summary_relay import (DEFAULT_MODEL, MAX_SEGMENTS, MAX_SPLIT_REQUEST_BYTES,
                                 STORY_PROMPT_PATH, effective_model as relay_model, prompt_categories,
                                 valid_model, validate_split_output)

MAX_ATTEMPTS = 3
MAX_RESPONSE_BYTES = 1024 * 1024


def relay_address(url):
    try:
        address = urlsplit(url)
        if (address.scheme != 'http' or address.hostname not in ('127.0.0.1', '::1')
                or address.username is not None or address.password is not None
                or address.path not in ('', '/') or address.query or address.fragment):
            raise ValueError('invalid relay address')
        port = address.port if address.port is not None else 80
        if not 1 <= port <= 65535:
            raise ValueError('invalid relay address')
        return address.hostname, port
    except ValueError:
        raise ValueError('invalid loopback relay address') from None


def request_once(address, payload):
    """No redirects, proxies, remote destinations or response logging."""
    raw = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode('utf-8')
    if len(raw) > MAX_SPLIT_REQUEST_BYTES:
        raise ValueError('request too large')
    # agy sometimes takes 3-6 min per call; the relay still answers.
    connection = http.client.HTTPConnection(*address, timeout=480)
    try:
        connection.request('POST', '/story-split', raw,
                           {'Content-Type': 'application/json', 'Connection': 'close'})
        response = connection.getresponse()
        body = response.read(MAX_RESPONSE_BYTES + 1)
        if response.status != 200 or len(body) > MAX_RESPONSE_BYTES:
            raise ValueError('relay request failed')
        return json.loads(body.decode('utf-8'))
    finally:
        connection.close()


def request_with_retry(address, payload, *, send=request_once):
    start = time.perf_counter()
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            result = send(address, payload)
            return result, attempt, (time.perf_counter() - start) * 1000
        except (ConnectionResetError, http.client.RemoteDisconnected, http.client.IncompleteRead):
            if attempt == MAX_ATTEMPTS:
                raise ConnectionError('relay connection reset') from None
        except OSError as error:
            if (error.errno not in (errno.ECONNRESET, 10054) and getattr(error, 'winerror', None) != 10054
                    or attempt == MAX_ATTEMPTS):
                raise ConnectionError('relay connection failed') from None


def windows(saved):
    return [saved[start:start + MAX_SEGMENTS] for start in range(0, len(saved), MAX_SEGMENTS)]


def restore_runs(blocks, records, policy):
    """Rehydrate only through the dev gate, reject incomplete/stale decisions."""
    threshold, minimum = parse_policy(policy)
    runs = build_runs(blocks)
    if len(runs) != len(records):
        raise ValueError('run snapshot mismatch; rerun measure')
    for index, (run, record) in enumerate(zip(runs, records)):
        if record['run'] != index or [b['id'] for b in run] != [b['id'] for b in record['blocks']]:
            raise ValueError('run snapshot mismatch; rerun measure')
        for block, old in zip(run, record['blocks']):
            probability = old['probability']
            if (not isinstance(probability, (int, float)) or not 0 <= probability <= 1
                    or old['human'] != block['human']):
                raise ValueError('invalid run snapshot')
            block['probability'] = probability
        decisions = decide(run, threshold, minimum)
        if any(type(old['decisions'][policy]) is not bool or old['decisions'][policy] != save
               for old, save in zip(record['blocks'], decisions)):
            raise ValueError('decision snapshot mismatch; rerun measure')
    return runs


def checked_response(result, count, categories):
    try:
        groups = result['stories']
        # The relay adds tags; the underlying LLM contract does not include them.
        fields = ('segments', 'title', 'category', 'keywords', 'kind', 'keep', 'reason')
        raw = json.dumps(dict(stories=[{k: s[k] for k in fields} for s in groups]), ensure_ascii=False)
        clean = validate_split_output(raw, categories, count)['stories']
        for story, original in zip(clean, groups):
            story['tags'] = ([story['category']] if story['category'] else []) + story['keywords']
            if original['tags'] != story['tags']:
                raise ValueError('invalid relay output')
        elapsed = result['elapsed_ms']
        if type(elapsed) is not int or elapsed < 0:
            raise ValueError('invalid relay output')
        return clean
    except (ValueError, TypeError, KeyError, RecursionError):
        raise ValueError('invalid relay output') from None


def execute(*, relay, policy, model=DEFAULT_MODEL, root=ROOT, send=request_once):
    from tools.listen.storage import atomic_write, encode_lines
    parse_policy(policy)
    address = relay_address(relay)
    if not valid_model(model):
        raise ValueError('invalid model')
    effective_model = relay_model(model)
    directory = Path(root) / '.local/eval/stitch'
    records = [json.loads(line) for line in (directory / 'runs.jsonl').read_text(encoding='utf-8').splitlines()
               if line.strip()]
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        blocks, _ = load_dev(root)
    runs = restore_runs(blocks, records, policy)
    categories = prompt_categories(STORY_PROMPT_PATH.read_text(encoding='utf-8'))
    threshold, minimum = parse_policy(policy)
    rows, per_run, latencies = [], [], []
    sent = ok = failed = none = window_ok = window_failed = windowed_runs = 0
    keep = hide = 0
    reasons = Counter()
    for index, run in enumerate(runs):
        saved = [b for b, save in zip(run, decide(run, threshold, minimum)) if save]
        if not saved:
            continue
        sent += 1
        chunks = windows(saved)
        windowed_runs += len(chunks) > 1
        run_ok, story_count = True, 0
        for number, chunk in enumerate(chunks):
            started = time.perf_counter()
            row = dict(run=index, window=number + 1, window_count=len(chunks),
                       segment_offset=number * MAX_SEGMENTS,
                       ids=[b['id'] for b in chunk], policy=policy, model=effective_model)
            try:
                result, attempts, latency = request_with_retry(
                    address, dict(segments=[b['text'] for b in chunk], model=model), send=send)
                stories = checked_response(result, len(chunk), categories)
                if result['model'] != effective_model:
                    raise ValueError('relay model mismatch')
                for story in stories:
                    story['ids'] = [chunk[n - 1]['id'] for n in story['segments']]
                row.update(status='ok', stories=stories, attempts=attempts,
                           latency_ms=latency, backend_elapsed_ms=result['elapsed_ms'])
                story_count += len(stories)
                none += sum(s['kind'] == 'none' for s in stories)
                keep += sum(s['keep'] for s in stories)
                hide += sum(not s['keep'] for s in stories)
                reasons.update(s['reason'] for s in stories if not s['keep'])
                window_ok += 1
                latencies.append(latency)
            except Exception:
                # Neither backend error messages nor malformed private answers are persisted.
                row.update(status='failed', error='request_failed',
                           latency_ms=(time.perf_counter() - started) * 1000)
                window_failed += 1
                run_ok = False
            rows.append(row)
        if run_ok:
            ok += 1
            per_run.append(story_count)
        else:
            failed += 1
    # Keep the exact model in rows/requests, but encode CF paths for a flat filename
    # accepted by eval.stitch.value. Existing agy filenames are unchanged.
    file_model = ('cf-' + effective_model[4:].replace('/', '--')
                  if effective_model.startswith('@cf/') else effective_model)
    atomic_write(directory / f'split-{policy}-{file_model}.jsonl', encode_lines(rows))
    return dict(runs_sent=sent, ok=ok, failed=failed, skipped_runs=len(runs) - sent,
                windows_ok=window_ok, windows_failed=window_failed, windowed_runs=windowed_runs,
                stories_per_run=histogram(per_run), none=none, keep=keep, hide=hide,
                reasons=dict(sorted(reasons.items())), median_latency_ms=quantile(latencies, .5))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--relay', default='http://127.0.0.1:8771')
    parser.add_argument('--policy', default='cont0.5_min40')
    parser.add_argument('--model', default=DEFAULT_MODEL)
    args = parser.parse_args(argv)
    try:
        result = execute(relay=args.relay, policy=args.policy, model=args.model)
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 0 if not result['failed'] else 2
    except Exception:
        print('stitch_split_failed', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
