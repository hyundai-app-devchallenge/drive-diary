"""Debug-only, loopback HTTP relay for agy titles, categories and keywords.

Run from model-create: python -m tools.summary_relay --port 8771
The Android emulator uses http://10.0.2.2:8771.
"""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
from pathlib import Path
import re
import socket
import threading
import time

from pipeline.label import AgyBackend, BackendError, UsageLimit, agy_model

DEFAULT_MODEL = 'gemini-3.8-flash-medium'
DEFAULT_PORT = 8771
MAX_REQUEST_BYTES = 32 * 1024
PROMPT_PATH = Path(__file__).resolve().parents[1] / 'prompts/summary.md'
STORY_PROMPT_PATH = PROMPT_PATH.with_name('story_split.md')
MAX_SEGMENTS = 12
MAX_SPLIT_REQUEST_BYTES = 256 * 1024
LOGGER = logging.getLogger(__name__)


def prompt_categories(system):
    """Read the comma-separated vocabulary only from the prompt's 분류 section."""
    section = re.search(r'^##[ \t]+분류[ \t]*\r?\n(.*?)(?=^##[ \t]+|\Z)',
                        system, re.M | re.S)
    lines = [line.strip() for line in section[1].splitlines() if ',' in line] if section else []
    if len(lines) != 1:
        raise ValueError('summary prompt category list missing or ambiguous')
    categories = tuple(category.strip() for category in lines[0].split(','))
    if not all(categories) or len(set(categories)) != len(categories):
        raise ValueError('summary prompt category list invalid')
    return categories


def output_json(answer):
    if not isinstance(answer, str):
        raise ValueError('invalid output')
    answer = answer.strip()
    fence = re.fullmatch(r'```(?:json)?\s*\n?(.*?)\s*```', answer, re.S | re.I)
    if fence:
        answer = fence[1]
    try:
        return json.loads(answer)
    except (ValueError, TypeError, RecursionError):
        raise ValueError('invalid output') from None


def validate_fields(data, allowed_categories, *, allow_empty=False):
    """Shared title/category/keyword rules for summary and story split."""
    try:
        if not isinstance(data, dict):
            raise ValueError('invalid output')
        title, kind = data.get('title'), data.get('kind')
        category, keywords = data.get('category'), data.get('keywords')
        if (not isinstance(title, str) or (not (allow_empty and title == '') and
                (not 1 <= len(title) <= 25 or not title.strip()))
                or '\n' in title or '\r' in title):
            raise ValueError('invalid output')
        title.encode('utf-8')
        if (not isinstance(category, str) or (category not in allowed_categories and
                not (allow_empty and category == ''))
                or not isinstance(keywords, list) or len(keywords) > 2
                or kind not in (('story', 'moment', 'none') if allow_empty else ('story', 'moment'))):
            raise ValueError('invalid output')
        normalized_keywords = []
        seen = set()
        for keyword in keywords:
            if not isinstance(keyword, str):
                raise ValueError('invalid output')
            keyword = keyword.strip()
            if (not re.fullmatch(r'[가-힣A-Za-z0-9·]{1,6}', keyword)
                    or keyword.isdigit() or keyword in allowed_categories
                    or keyword.casefold() in seen):
                raise ValueError('invalid output')
            seen.add(keyword.casefold())
            normalized_keywords.append(keyword)
    except (ValueError, TypeError, RecursionError):
        # JSONDecodeError contains the original response; never propagate it.
        raise ValueError('invalid output') from None
    return dict(title=title, category=category, keywords=normalized_keywords, kind=kind)


def validate_output(answer, allowed_categories):
    return validate_fields(output_json(answer), allowed_categories)


def validate_split_output(answer, allowed_categories, segment_count):
    """Strict ordered partition; bool/float indices are never segment numbers."""
    try:
        data = output_json(answer)
        if (type(segment_count) is not int or not 1 <= segment_count <= MAX_SEGMENTS
                or not isinstance(data, dict) or set(data) != {'stories'}
                or not isinstance(data['stories'], list) or not data['stories']):
            raise ValueError('invalid output')
        stories, covered = [], []
        for group in data['stories']:
            if (not isinstance(group, dict) or set(group) !=
                    {'segments', 'title', 'category', 'keywords', 'kind'}
                    or not isinstance(group['segments'], list) or not group['segments']
                    or any(type(index) is not int for index in group['segments'])):
                raise ValueError('invalid output')
            fields = validate_fields(group, allowed_categories, allow_empty=group['kind'] == 'none')
            covered.extend(group['segments'])
            stories.append(dict(segments=group['segments'], **fields))
        if covered != list(range(1, segment_count + 1)):
            raise ValueError('invalid output')
        return dict(stories=stories)
    except (ValueError, TypeError, RecursionError):
        raise ValueError('invalid output') from None


class SummaryRelay:
    def __init__(self, *, fake=False, backend_factory=None, prompt_path=PROMPT_PATH):
        self.system = Path(prompt_path).read_text(encoding='utf-8')
        self.categories = prompt_categories(self.system)
        self.fake = fake
        self.backend_factory = backend_factory or AgyBackend
        self.lock = threading.Lock()

    def complete(self, model, system, transcript, validate, fake_result):
        """One backend/agy path, with the same lock and invalid-output retry."""
        base, effort = agy_model(model, 'medium')
        with self.lock:
            try:
                if self.fake:
                    return 200, fake_result, f'{base}-{effort}'
                backend = self.backend_factory(model=base, effort=effort)
                for attempt in range(2):
                    answer = backend.complete(system, transcript)
                    try:
                        return 200, validate(answer), f'{base}-{effort}'
                    except ValueError:
                        if attempt == 1:
                            return 502, {'error': 'invalid_output'}, None
            except UsageLimit:
                return 429, {'error': 'usage_limit'}, None
            except Exception:
                # Includes BackendError; never disclose private backend details.
                return 502, {'error': 'backend_error'}, None

    def story_split(self, data):
        start = time.monotonic()
        if not isinstance(data, dict):
            return 400, {'error': 'invalid_request'}
        segments, model = data.get('segments'), data.get('model', DEFAULT_MODEL)
        if (not isinstance(segments, list) or not 1 <= len(segments) <= MAX_SEGMENTS
                or any(not isinstance(s, str) or not s.strip() for s in segments)
                or not isinstance(model, str)
                or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', model)):
            return 400, {'error': 'invalid_request'}
        transcript = '\n\n'.join(f'[{i}]\n{text}' for i, text in enumerate(segments, 1))
        try:
            transcript.encode('utf-8')
            system = STORY_PROMPT_PATH.read_text(encoding='utf-8')
            categories = prompt_categories(system)
        except (UnicodeError, OSError, ValueError):
            return 400, {'error': 'invalid_request'}
        fake = dict(stories=[dict(segments=list(range(1, len(segments) + 1)),
                                 title='테스트 이야기', category=categories[0], keywords=[], kind='story')])
        status, result, effective_model = self.complete(
            model, system, transcript, lambda answer: validate_split_output(answer, categories, len(segments)), fake)
        if status != 200:
            return status, result
        stories = [dict(s, tags=([s['category']] if s['category'] else []) + s['keywords'])
                   for s in result['stories']]
        return 200, dict(stories=stories, model=effective_model,
                         elapsed_ms=round((time.monotonic() - start) * 1000))

    def summarize(self, data):
        start = time.monotonic()
        if not isinstance(data, dict):
            return 400, {'error': 'invalid_request'}
        transcript = data.get('transcript')
        model = data.get('model', DEFAULT_MODEL)
        if (not isinstance(transcript, str) or not transcript.strip()
                or not isinstance(model, str)
                or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', model)):
            return 400, {'error': 'invalid_request'}
        try:
            transcript.encode('utf-8')
        except UnicodeError:
            return 400, {'error': 'invalid_request'}
        status, result, effective_model = self.complete(
            model, self.system, transcript, lambda answer: validate_output(answer, self.categories),
            dict(title='테스트 대화', category=self.categories[0], keywords=[], kind='story'))
        if status != 200:
            return status, result
        return 200, dict(result, tags=[result['category']] + result['keywords'],
                         model=effective_model,
                         elapsed_ms=round((time.monotonic() - start) * 1000))


class SummaryHandler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(10)
        self.started = time.monotonic()
        self.request_bytes = self.transcript_chars = 0

    def log_message(self, format, *args):
        pass  # BaseHTTPRequestHandler logs raw paths and request lines.

    def send_json(self, status, data):
        raw = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.close_connection = True
        # Unknown URLs, query strings, and methods can themselves contain private text.
        path = getattr(self, 'path', '').partition('?')[0]
        path = path if path in ('/health', '/summarize', '/story-split') else '/<unknown>'
        method = getattr(self, 'command', '')
        method = method if method in ('GET', 'POST', 'HEAD', 'PUT', 'DELETE', 'OPTIONS', 'PATCH') else 'OTHER'
        LOGGER.info('method=%s path=%s status=%d request_bytes=%d transcript_chars=%d '
                    'response_bytes=%d elapsed_ms=%d', method, path, status,
                    self.request_bytes, self.transcript_chars, len(raw),
                    round((time.monotonic() - self.started) * 1000))
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        if getattr(self, 'command', '') != 'HEAD':
            self.wfile.write(raw)

    def send_error(self, code, message=None, explain=None):
        # Also sanitize parser errors and unsupported HTTP methods.
        self.send_json(405 if code == 501 else code,
                       {'error': 'method_not_allowed' if code == 501 else 'invalid_request'})

    def do_GET(self):
        if self.path != '/health':
            self.send_json(404, {'error': 'not_found'})
            return
        self.send_json(200, {'ok': True, 'model': DEFAULT_MODEL})

    def do_POST(self):
        if self.path not in ('/summarize', '/story-split'):
            self.send_json(404, {'error': 'not_found'})
            return
        lengths = self.headers.get_all('Content-Length', [])
        if (self.headers.get('Transfer-Encoding') is not None or len(lengths) != 1
                or not re.fullmatch(r'[0-9]{1,10}', lengths[0])):
            self.send_json(400, {'error': 'invalid_request'})
            return
        size = int(lengths[0])
        limit = MAX_SPLIT_REQUEST_BYTES if self.path == '/story-split' else MAX_REQUEST_BYTES
        if size > limit:
            self.send_json(413, {'error': 'request_too_large'})
            return
        if self.headers.get('Content-Type', '').partition(';')[0].strip().lower() != 'application/json':
            self.send_json(415, {'error': 'unsupported_media_type'})
            return
        try:
            raw = self.rfile.read(size)
            self.request_bytes = len(raw)
            if len(raw) != size:
                raise ValueError('incomplete request')
            data = json.loads(raw.decode('utf-8'))
        except TimeoutError:
            self.send_json(408, {'error': 'request_timeout'})
            return
        except (ValueError, RecursionError):
            self.send_json(400, {'error': 'invalid_request'})
            return
        if isinstance(data, dict) and isinstance(data.get('transcript'), str):
            self.transcript_chars = len(data['transcript'])
        if self.path == '/story-split':
            if isinstance(data, dict) and isinstance(data.get('segments'), list):
                self.transcript_chars = sum(len(s) for s in data['segments'] if isinstance(s, str))
            status, result = self.server.relay.story_split(data)
        else:
            status, result = self.server.relay.summarize(data)
        self.send_json(status, result)


class SummaryServer(ThreadingHTTPServer):
    def __init__(self, port=DEFAULT_PORT, *, relay=None):
        self.relay = relay if relay is not None else SummaryRelay()
        # Deliberately no host option: never listen on a LAN/public interface.
        super().__init__(('127.0.0.1', port), SummaryHandler)

    def handle_error(self, request, client_address):
        pass  # Never emit a request/response-bearing traceback.

    def shutdown_request(self, request):
        # On this Windows host, loopback connections are sometimes reset right after
        # the response is sent (also with a bare http.server). Half-closing and draining
        # until the client closes first lowered resets from ~20% to ~3% in tests.
        try:
            request.shutdown(socket.SHUT_WR)
            request.settimeout(2)
            while request.recv(4096):
                pass
        except OSError:
            pass
        super().shutdown_request(request)


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('--port', type=int, default=DEFAULT_PORT)
    result.add_argument('--fake', action='store_true', help='deterministic response; never call agy')
    return result


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    if not 1 <= args.port <= 65535:
        p.error('--port must be between 1 and 65535')
    logging.basicConfig(level=logging.INFO, format='%(message)s')
    try:
        with SummaryServer(args.port, relay=SummaryRelay(fake=args.fake)) as server:
            server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError):
        p.exit(2, 'Relay startup failed; check the prompt and port.\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
