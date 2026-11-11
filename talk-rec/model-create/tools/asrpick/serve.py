"""한국어 ASR 눈가림 선택 화면. 127.0.0.1 전용."""
import argparse
import json
from http.server import BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from tools.listen.serve import byte_range
from tools.review.serve import ReviewHTTPServer
from .store import PickStore


def make_server(store, port=8768):
    page = Path(__file__).with_name("index.html").read_bytes()

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
            self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; media-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            for key, value in (extra or {}).items():
                self.send_header(key, value)
            self.end_headers()

        def respond(self, status, value, kind="application/json; charset=utf-8", extra=None):
            body = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode("utf-8")
            self.headers_for(status, len(body), kind, extra)
            if self.command != "HEAD":
                self.wfile.write(body)

        def allowed(self):
            host = self.headers.get("Host")
            if (host != f"127.0.0.1:{self.server.server_port}"
                    or self.headers.get("Origin", f"http://{host}") != f"http://{host}"
                    or self.headers.get("Sec-Fetch-Site") == "cross-site"):
                self.respond(403, {"error": "127.0.0.1 로컬 화면에서만 사용하세요."})
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
                    path = store.media(block_id)
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
                        self.headers_for(status, max(0, end - start + 1), "audio/wav", extra)
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
                self.respond(400, {"error": "표본 또는 클립 경로를 확인하세요."})

        def do_POST(self):
            if not self.allowed():
                return
            try:
                if self.path != "/api/decision":
                    self.respond(404, {"error": "없는 경로입니다."})
                    return
                lengths = self.headers.get_all("Content-Length", [])
                if (self.headers.get("Transfer-Encoding") or len(lengths) != 1 or not lengths[0].isdigit()
                        or not 0 < int(lengths[0]) <= 65536 or self.headers.get_content_type() != "application/json"):
                    raise ValueError("요청 형식 오류")
                body = self.rfile.read(int(lengths[0]))
                if len(body) != int(lengths[0]):
                    raise ValueError("불완전한 요청")
                self.respond(200, store.save(json.loads(body)))
            except (ValueError, TypeError, UnicodeError, KeyError):
                self.respond(400, {"error": "선택 요청 형식을 확인하세요."})
            except OSError:
                self.respond(500, {"error": "저장 실패. 다시 시도하세요."})

    return ReviewHTTPServer(("127.0.0.1", port), Handler)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--port", type=int, default=8768)
    parser.add_argument("--limit", type=int, default=12)
    parser.add_argument("--review", action="store_true", help="저장된 선택도 포함하여 처음부터 재검토")
    args = parser.parse_args(argv)
    try:
        store = PickStore(args.name, limit=args.limit, review=args.review)
        server = make_server(store, args.port)
    except (ValueError, OSError, KeyError, TypeError):
        parser.error("승인 표본·클립·설정별 전체 결과·이름·포트를 확인하세요.")
    print(f"http://127.0.0.1:{server.server_port} | {len(store.rows)}개 | Ctrl+C 종료")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
