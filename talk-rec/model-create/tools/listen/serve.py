"""한국어 청취 판정 화면. 127.0.0.1 전용."""
import argparse
from datetime import datetime, timezone
import json
from http.server import BaseHTTPRequestHandler
from pathlib import Path
import re
import threading
from urllib.parse import parse_qs, urlsplit

from tools.review.serve import ReviewHTTPServer
from .storage import (ROOT, atomic_write, audio_path, clip_name, encode_lines,
                      read_answers, read_jsonl, validate_answer)


class ListenStore:
    def __init__(self, name, *, root=ROOT):
        if (not isinstance(name, str) or not name or name != name.strip()
                or len(name) > 80 or re.search(r'[<>:"/\\|?*\x00-\x1f]', name)
                or name.endswith('.') or name.split('.')[0].upper() in
                {'CON', 'PRN', 'AUX', 'NUL', *('COM'+str(i) for i in range(10)), *('LPT'+str(i) for i in range(10))}):
            raise ValueError("파일명으로 사용할 수 있는 이름을 입력하세요.")
        self.root = Path(root)
        self.name = name
        self.path = self.root / ".local/demo/answers" / f"{name}.jsonl"
        self.rows = read_jsonl(self.root / ".local/demo/sample.jsonl")
        self.by_id = {r["id"]: r for r in self.rows}
        if len(self.by_id) != len(self.rows):
            raise ValueError("표본 id가 중복되었습니다.")
        for row in self.rows:
            audio_path(self.root, row)
        self.lock = threading.RLock()
        read_answers(self.path)

    def media(self, block_id):
        row = self.by_id[block_id]
        directory = (self.root / ".local/demo/clips").resolve()
        clip = (directory / clip_name(block_id)).resolve()
        if not clip.is_relative_to(directory):
            raise ValueError("클립 경로 오류")
        if clip.is_file():
            return clip, 0, row["end"] - row["start"] + min(2, row["start"]) + 1
        return audio_path(self.root, row), max(0, row["start"] - 2), row["end"] + 1

    def state(self):
        with self.lock:
            answers = read_answers(self.path)
            rows = []
            for row in self.rows:
                path, start, end = self.media(row["id"])
                rows.append(dict(id=row["id"], source=row["source"], start=row["start"], end=row["end"],
                                 turns=row["turns"], prefix=row["prefix"], play_start=start, play_end=end,
                                 clipped=path.suffix == ".wav", answer=answers.get(row["id"])))
            return dict(name=self.name, rows=rows, answered=sum(r["id"] in answers for r in self.rows), total=len(rows))

    def save(self, payload):
        if not isinstance(payload, dict) or set(payload) != {"id", "verdict", "comment", "listened", "opened_text"}:
            raise ValueError("판정 요청 형식 오류")
        row = validate_answer(dict(payload, decided_at=datetime.now(timezone.utc).isoformat()))
        if row["id"] not in self.by_id or len(row["comment"]) > 2000:
            raise ValueError("표본 id 또는 코멘트를 확인하세요.")
        with self.lock:
            old = self.path.read_bytes() if self.path.exists() else b""
            if old and not old.endswith(b"\n"):
                old += b"\n"
            atomic_write(self.path, old + encode_lines([row]))
        return dict(saved=row)


def byte_range(header, size):
    if header is None:
        return 0, size - 1, 200
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", header)
    if not match or not any(match.groups()) or size == 0:
        raise ValueError("Range 오류")
    a, b = match.groups()
    if not a:
        if int(b) <= 0:
            raise ValueError("Range 오류")
        start, end = max(0, size - int(b)), size - 1
    else:
        start, end = int(a), min(int(b), size - 1) if b else size - 1
    if start >= size or start > end:
        raise ValueError("Range 오류")
    return start, end, 206


def make_server(store, port=8767):
    page = Path(__file__).with_name(getattr(store, 'page_name', 'index.html')).read_bytes()

    class Handler(BaseHTTPRequestHandler):
        timeout = 5

        def log_message(self, *_):
            pass

        def headers_for(self, status, length, kind, extra=None):
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(length))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; media-src 'self'; frame-ancestors 'none'")
            for key, value in (extra or {}).items():
                self.send_header(key, value)
            self.end_headers()

        def respond(self, status, payload, kind="application/json; charset=utf-8", extra=None):
            body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.headers_for(status, len(body), kind, extra)
            if self.command != "HEAD":
                self.wfile.write(body)

        def allowed(self):
            host = self.headers.get("Host")
            if host not in {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"} or self.headers.get("Origin", f"http://{host}") != f"http://{host}":
                self.respond(403, {"error": "로컬 페이지에서만 사용할 수 있습니다."})
                return False
            return True

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            if not self.allowed():
                return
            url = urlsplit(self.path)
            try:
                if url.path == "/":
                    self.respond(200, page, "text/html; charset=utf-8")
                elif url.path == "/api/state":
                    self.respond(200, store.state())
                elif url.path == "/audio":
                    block_id = parse_qs(url.query).get("id", [""])[0]
                    if block_id not in store.by_id:
                        self.respond(404, {"error": "없는 표본입니다."})
                        return
                    path, _, _ = store.media(block_id)
                    # Only server-selected, validated paths; no user-supplied filenames.
                    with path.open("rb") as stream:
                        size = path.stat().st_size
                        try:
                            start, end, status = byte_range(self.headers.get("Range"), size)
                        except ValueError:
                            self.respond(416, b"", extra={"Content-Range": f"bytes */{size}"})
                            return
                        extra = {"Accept-Ranges": "bytes"}
                        if status == 206:
                            extra["Content-Range"] = f"bytes {start}-{end}/{size}"
                        self.headers_for(status, max(0, end - start + 1), "audio/wav" if path.suffix == ".wav" else "audio/webm", extra)
                        if self.command != "HEAD":
                            stream.seek(start)
                            remaining = end - start + 1
                            while remaining > 0:
                                chunk = stream.read(min(65536, remaining))
                                if not chunk:
                                    break
                                self.wfile.write(chunk)
                                remaining -= len(chunk)
                else:
                    self.respond(404, {"error": "없는 경로입니다."})
            except (ValueError, KeyError, OSError):
                self.respond(400, {"error": "표본 또는 오디오 파일을 확인하세요."})

        def read_body(self):
            lengths = self.headers.get_all("Content-Length", [])
            if self.headers.get("Transfer-Encoding") or len(lengths) != 1 or not lengths[0].isdigit():
                raise ValueError("요청 길이 오류")
            length = int(lengths[0])
            if not 0 < length <= 65536:
                raise ValueError("요청 크기 오류")
            body = self.rfile.read(length)
            if len(body) != length:
                raise ValueError("불완전한 요청")
            return body

        def do_POST(self):
            try:
                body = self.read_body()
            except (ValueError, OSError):
                self.respond(400, {"error": "요청 본문 형식 오류"})
                return
            if not self.allowed():
                return
            try:
                if self.path != "/api/decision":
                    self.respond(404, {"error": "없는 경로입니다."})
                    return
                if self.headers.get_content_type() != "application/json":
                    raise ValueError("JSON만 허용합니다.")
                self.respond(200, store.save(json.loads(body)))
            except (ValueError, TypeError, UnicodeError):
                self.respond(400, {"error": "판정 요청 형식을 확인하세요."})
            except OSError:
                self.respond(500, {"error": "저장 실패. 다시 시도하세요."})

    return ReviewHTTPServer(("127.0.0.1", port), Handler)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", help="기존 데모 판정자")
    parser.add_argument("--real", help="실제 평가 실행 이름")
    parser.add_argument("--annotator", help="실제 묶음 판정자")
    parser.add_argument("--suggest", choices=['claude'], help="dev에서 Claude 제안 확인")
    parser.add_argument("--port", type=int, default=8767)
    args = parser.parse_args(argv)
    try:
        if args.real:
            from .real import RealListenStore
            if not args.annotator or args.name:
                parser.error('--real에는 --annotator를 쓰세요.')
            store = RealListenStore(args.real, args.annotator, suggest=args.suggest)
        else:
            if not args.name or args.annotator or args.suggest:
                parser.error('데모에는 --name을 쓰세요.')
            store = ListenStore(args.name)
        server = make_server(store, args.port)
    except (ValueError, OSError, KeyError, TypeError) as exc:
        if args.real:
            trace = exc.__traceback__
            while trace.tb_next:
                trace = trace.tb_next
            parser.error(f'{type(exc).__name__} {Path(trace.tb_frame.f_code.co_filename).name}:{trace.tb_lineno}')
        parser.error(str(exc))
    print(f"http://127.0.0.1:{server.server_port} | {len(store.rows)}개 | Ctrl+C 종료")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
