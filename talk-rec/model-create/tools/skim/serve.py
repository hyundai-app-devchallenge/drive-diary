"""Loopback-only Korean skim UI. No external assets or transcript logging."""
import argparse
from collections import defaultdict
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import random
import re
import secrets
import threading
import time
from urllib.parse import urlsplit

from common.schema import validate_block
from synth.io import output_lock, read_jsonl, write_jsonl
from synth.patterns import BY_ID, LABELS

ASSETS = Path(__file__).resolve().parent


def safe_name(name):
    if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9가-힣_-]{1,40}', name):
        raise ValueError('이름에는 한글·영문·숫자·밑줄·붙임표만 사용할 수 있습니다.')
    if re.fullmatch(r'(?i:con|prn|aux|nul|com[0-9]|lpt[0-9])', name):
        raise ValueError('예약된 파일 이름입니다.')
    return name


def stratified_sample(labels, limit=60, seed=43):
    if limit < 1:
        raise ValueError('limit must be positive')
    rng = random.Random(seed)
    groups = defaultdict(lambda: defaultdict(list))
    for row in labels:
        groups[row['verdict']][row['pattern']].append(row['id'])
    for label in groups:
        for values in groups[label].values():
            rng.shuffle(values)
    pattern_order = {label: list(groups[label]) for label in groups}
    for values in pattern_order.values():
        rng.shuffle(values)
    chosen = []
    label_counts, pattern_counts = defaultdict(int), defaultdict(int)
    while len(chosen) < min(limit, len(labels)):
        available = [label for label in LABELS if any(groups[label].values())]
        label = min(available, key=lambda x: label_counts[x])
        candidates = [p for p in pattern_order[label] if groups[label][p]]
        pattern = min(candidates, key=lambda p: pattern_counts[label, p])
        chosen.append(groups[label][pattern].pop())
        label_counts[label] += 1
        pattern_counts[label, pattern] += 1
    return chosen


class SkimStore:
    def __init__(self, directory, name, limit=60, seed=43):
        self.directory = Path(directory).resolve()
        meta = json.loads((self.directory / 'meta.json').read_text(encoding='utf-8'))
        if not meta.get('demo_only') or not meta.get('complete'):
            raise ValueError('완료된 demo_only 데이터만 검토할 수 있습니다.')
        self.name = safe_name(name)
        rows = {key: read_jsonl(self.directory / f'{key}.jsonl') for key in ('blocks', 'clean', 'labels')}
        for values in rows.values():
            if len({r['id'] for r in values}) != len(values):
                raise ValueError('duplicate id')
        self.blocks, self.clean, self.labels = ({r['id']: r for r in rows[k]} for k in ('blocks', 'clean', 'labels'))
        if set(self.blocks) != set(self.clean) or set(self.blocks) != set(self.labels):
            raise ValueError('dataset id mismatch')
        for row in self.blocks.values():
            validate_block(row)
        self.ids = stratified_sample(rows['labels'], limit, seed)
        self.path = self.directory / 'skim' / (self.name + '.jsonl')
        self.ensure_path()
        saved = read_jsonl(self.path) if self.path.exists() else []
        self.saved = {r['id']: r for r in saved}
        self.opened, self.revealed = set(), set()
        self.lock = threading.RLock()

    def ensure_path(self):
        if not self.path.resolve().is_relative_to(self.directory) or self.path.is_symlink() or self.path.parent.is_symlink():
            raise ValueError('검토 경로가 데이터 디렉터리를 벗어났습니다.')

    def check_id(self, identity):
        if not isinstance(identity, str) or identity not in self.ids:
            raise ValueError('unknown review id')

    def item(self, index):
        if type(index) is not int or not 0 <= index < len(self.ids):
            raise ValueError('index outside sample')
        identity = self.ids[index]
        saved = self.saved.get(identity)
        # No intended label, pattern, clean text, why, or family in this response.
        return dict(index=index, total=len(self.ids), id=identity, turns=self.blocks[identity]['turns'],
                    seconds=self.blocks[identity]['features']['seconds'], decision=saved,
                    done=sum(i in self.saved for i in self.ids))

    def open_clean(self, identity):
        self.check_id(identity)
        with self.lock:
            self.opened.add(identity)
        return dict(lines=self.clean[identity]['lines'])

    def reveal(self, identity):
        self.check_id(identity)
        with self.lock:
            self.revealed.add(identity)
        row = self.labels[identity]
        return dict(label=row['verdict'], pattern=row['pattern'], boundary=row['boundary'],
                    description=BY_ID[row['pattern']]['description'], why=self.clean[identity]['why'],
                    card=self.clean[identity]['card'])

    def save(self, data):
        if set(data) != {'id', 'real', 'verdict', 'comment'}:
            raise ValueError('invalid decision fields')
        identity = data['id']
        self.check_id(identity)
        if data['real'] not in ('yes', 'no', 'maybe') or data['verdict'] not in LABELS:
            raise ValueError('both judgments required')
        if not isinstance(data['comment'], str) or len(data['comment']) > 2000 or any(
            ord(ch) < 32 and ch not in '\n\t' for ch in data['comment']
        ):
            raise ValueError('invalid comment')
        with self.lock, output_lock(self.path):
            self.ensure_path()
            # Merge latest disk state as another tab/server may have written it.
            latest = read_jsonl(self.path) if self.path.exists() else []
            records = {r['id']: r for r in latest}
            previous = records.get(identity, {})
            row = dict(data, decided_at=datetime.now(timezone.utc).isoformat(),
                       opened_clean=identity in self.opened or previous.get('opened_clean', False),
                       revealed_label=identity in self.revealed or previous.get('revealed_label', False))
            records[identity] = row
            write_jsonl(self.path, [records[k] for k in sorted(records)])
            self.saved = records
        return row


class SkimServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, store):
        super().__init__(address, SkimHandler)
        self.store = store
        self.token = secrets.token_urlsafe(32)

    def handle_error(self, request, client_address):
        pass

    def shutdown_request(self, request):
        # Windows can reset a response still in flight on immediate shutdown.
        # Drain only a bounded amount; do not keep request threads alive forever.
        try:
            deadline = time.monotonic() + 2
            remaining = 16384
            while remaining:
                timeout = deadline - time.monotonic()
                if timeout <= 0:
                    break
                request.settimeout(timeout)
                data = request.recv(min(8192, remaining))
                if not data:
                    break
                remaining -= len(data)
        except OSError:
            pass
        finally:
            super().shutdown_request(request)


class SkimHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, status, body, mime='application/json; charset=utf-8'):
        raw = body.encode('utf-8') if isinstance(body, str) else json.dumps(body, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('X-Frame-Options', 'DENY')
        self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.end_headers()
        self.wfile.write(raw)

    def allowed(self, api=False):
        port = self.server.server_address[1]
        hosts = {f'127.0.0.1:{port}', f'localhost:{port}'}
        origin = self.headers.get('Origin')
        if self.headers.get('Host') not in hosts or (origin and origin not in {f'http://{h}' for h in hosts}):
            self.send(403, {'error': '외부 접근 차단'})
            return False
        if api and not secrets.compare_digest(self.headers.get('X-Skim-Token', ''), self.server.token):
            self.send(403, {'error': '검토 세션 확인 실패'})
            return False
        return True

    def do_GET(self):
        route = urlsplit(self.path)
        if not self.allowed():
            return
        if route.query or route.path not in ('/', '/app.js', '/style.css'):
            self.send(404, {'error': '없는 경로'})
            return
        filename = 'index.html' if route.path == '/' else route.path[1:]
        content = (ASSETS / filename).read_text(encoding='utf-8')
        if filename == 'index.html':
            content = content.replace('{{TOKEN}}', self.server.token)
        self.send(200, content, {'index.html': 'text/html; charset=utf-8', 'app.js': 'text/javascript; charset=utf-8',
                                 'style.css': 'text/css; charset=utf-8'}[filename])

    def do_POST(self):
        self.close_connection = True
        if not self.allowed(api=True):
            return
        route = urlsplit(self.path)
        if route.query or route.path not in ('/api/item', '/api/clean', '/api/label', '/api/save'):
            self.send(404, {'error': '없는 경로'})
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 16384 or self.headers.get('Content-Type', '').split(';')[0] != 'application/json':
                raise ValueError('invalid request')
            data = json.loads(self.rfile.read(size))
            if not isinstance(data, dict):
                raise ValueError('object required')
            store = self.server.store
            if route.path == '/api/item' and set(data) == {'index'}:
                result = store.item(data['index'])
            elif route.path == '/api/clean' and set(data) == {'id'}:
                result = store.open_clean(data['id'])
            elif route.path == '/api/label' and set(data) == {'id'}:
                result = store.reveal(data['id'])
            elif route.path == '/api/save':
                result = store.save(data)
            else:
                raise ValueError('invalid fields')
            self.send(200, result)
        except (ValueError, KeyError, TypeError, OSError):
            self.send(400, {'error': '입력 또는 저장 경로를 확인하세요'})


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--name', required=True)
    p.add_argument('--port', type=int, default=8769)
    p.add_argument('--limit', type=int, default=60)
    p.add_argument('--seed', type=int, default=43)
    p.add_argument('--data-dir', default='.local/synth/demo')
    args = p.parse_args(argv)
    try:
        store = SkimStore(args.data_dir, args.name, args.limit, args.seed)
        with SkimServer(('127.0.0.1', args.port), store) as server:
            print(f'훑어보기: http://127.0.0.1:{server.server_port} (종료: Ctrl+C)', flush=True)
            server.serve_forever()
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, KeyError):
        p.exit(2, '화면 시작 실패: 이름·데이터·포트를 확인하세요.\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
