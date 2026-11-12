"""Debug-only, loopback HTTP relay for agy conversation titles and tags.

Run from model-create: python -m tools.summary_relay --port 8771
The Android emulator uses http://10.0.2.2:8771.
"""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
from pathlib import Path
import re
import threading
import time

from pipeline.label import AgyBackend, BackendError, UsageLimit, agy_model

DEFAULT_MODEL = 'gemini-3.8-flash-medium'
DEFAULT_PORT = 8771
MAX_REQUEST_BYTES = 32 * 1024
PROMPT_PATH = Path(__file__).resolve().parents[1] / 'prompts/summary.md'
LOGGER = logging.getLogger(__name__)


def prompt_tags(system):
    """Read the comma-separated vocabulary only from the prompt's 태그 section."""
    section = re.search(r'^##[ \t]+태그[ \t]*\r?\n(.*?)(?=^##[ \t]+|\Z)',
                        system, re.M | re.S)
    lines = [line.strip() for line in section[1].splitlines() if ',' in line] if section else []
    if len(lines) != 1:
        raise ValueError('summary prompt tag list missing or ambiguous')
    tags = tuple(tag.strip() for tag in lines[0].split(','))
    if not all(tags) or len(set(tags)) != len(tags):
        raise ValueError('summary prompt tag list invalid')
    return tags


def validate_output(answer, allowed_tags):
    if not isinstance(answer, str):
        raise ValueError('invalid output')
    answer = answer.strip()
    fence = re.fullmatch(r'```(?:json)?\s*\n?(.*?)\s*```', answer, re.S | re.I)
    if fence:
        answer = fence[1]
    try:
        data = json.loads(answer)
        if not isinstance(data, dict):
            raise ValueError('invalid output')
        title, tags, kind = data.get('title'), data.get('tags'), data.get('kind')
        if (not isinstance(title, str) or not 1 <= len(title) <= 25 or not title.strip()
                or '\n' in title or '\r' in title):
            raise ValueError('invalid output')
        title.encode('utf-8')
        if (not isinstance(tags, list) or not 1 <= len(tags) <= 3
                or any(not isinstance(tag, str) or tag not in allowed_tags for tag in tags)
                or kind not in ('story', 'moment')):
            raise ValueError('invalid output')
    except (ValueError, TypeError, RecursionError):
        # JSONDecodeError contains the original response; never propagate it.
        raise ValueError('invalid output') from None
    return dict(title=title, tags=tags, kind=kind)


class SummaryRelay:
    def __init__(self, *, fake=False, backend_factory=None, prompt_path=PROMPT_PATH):
        self.system = Path(prompt_path).read_text(encoding='utf-8')
        self.tags = prompt_tags(self.system)
        self.fake = fake
        self.backend_factory = backend_factory or AgyBackend
        self.lock = threading.Lock()

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
        base, effort = agy_model(model, 'medium')
        effective_model = f'{base}-{effort}'
        # Hold the lock across both attempts, including backend construction.
        with self.lock:
            try:
                if self.fake:
                    result = dict(title='테스트 대화', tags=[self.tags[0]], kind='story')
                else:
                    backend = self.backend_factory(model=base, effort=effort)
                    for attempt in range(2):
                        answer = backend.complete(self.system, transcript)
                        try:
                            result = validate_output(answer, self.tags)
                            break
                        except ValueError:
                            if attempt == 1:
                                return 502, {'error': 'invalid_output'}
            except UsageLimit:
                return 429, {'error': 'usage_limit'}
            except BackendError:
                return 502, {'error': 'backend_error'}
            except Exception:
                # Do not expose exception messages or transcript-bearing tracebacks.
                return 502, {'error': 'backend_error'}
        return 200, dict(result, model=effective_model,
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
        path = path if path in ('/health', '/summarize') else '/<unknown>'
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
        if self.path != '/summarize':
            self.send_json(404, {'error': 'not_found'})
            return
        lengths = self.headers.get_all('Content-Length', [])
        if (self.headers.get('Transfer-Encoding') is not None or len(lengths) != 1
                or not re.fullmatch(r'[0-9]{1,10}', lengths[0])):
            self.send_json(400, {'error': 'invalid_request'})
            return
        size = int(lengths[0])
        if size > MAX_REQUEST_BYTES:
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
        status, result = self.server.relay.summarize(data)
        self.send_json(status, result)


class SummaryServer(ThreadingHTTPServer):
    def __init__(self, port=DEFAULT_PORT, *, relay=None):
        self.relay = relay if relay is not None else SummaryRelay()
        # Deliberately no host option: never listen on a LAN/public interface.
        super().__init__(('127.0.0.1', port), SummaryHandler)

    def handle_error(self, request, client_address):
        pass  # Never emit a request/response-bearing traceback.


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
