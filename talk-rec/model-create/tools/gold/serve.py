"""Loopback-only, standard-library human labeling server. See README.md."""

import argparse
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import random
import re
import socket
import tempfile
import threading
import time
from urllib.parse import parse_qs, quote, urlsplit

from common.schema import SPLITS, validate_label, validate_utterance, validate_window

ROOT = Path(__file__).resolve().parents[2]
LABEL_FIELDS = {"id", "story", "kind", "exclude", "skip", "labeler", "note"}
MAX_REQUEST_BODY = 65536


class GoldHTTPServer(ThreadingHTTPServer):
    def shutdown_request(self, request):
        # Let the peer consume the length-delimited response and close before
        # shutting down our side. Immediate server-initiated shutdown can reset
        # the connection while the response is still in flight on Windows.
        # Bound both time and bytes for peers that never finish.
        try:
            deadline = time.monotonic() + 2
            remaining = MAX_REQUEST_BODY
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


def read_jsonl(path):
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if line.strip():
                try:
                    yield json.loads(line)
                except ValueError as exc:
                    raise ValueError(f"{path.name}:{line_number}: invalid JSON") from exc


def atomic_write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def sample_clips(clips, target, seed):
    """Round-robin shuffled sources; take whole clips until >= target windows."""
    if type(target) is not int or target <= 0:
        raise ValueError("sample은 양의 창 개수여야 합니다.")
    rng = random.Random(seed)
    groups = defaultdict(list)
    for clip in sorted(clips, key=lambda c: (c["source"], c["clip"])):
        groups[clip["source"]].append(clip)
    sources = sorted(groups)
    rng.shuffle(sources)
    for values in groups.values():
        rng.shuffle(values)
    chosen, count = [], 0
    while sources and count < target:
        remaining = []
        for source in sources:
            clip = groups[source].pop()
            chosen.append(clip)
            count += len(clip["ids"])
            if groups[source]:
                remaining.append(source)
            if count >= target:
                break
        sources = remaining
    return sorted(chosen, key=lambda c: (c["source"], c["clip"]))


class GoldStore:
    def __init__(self, name, *, root=ROOT, splits=None, sources=None, sample=None, seed=0):
        if not re.fullmatch(r"[\w-]{1,64}", name, flags=re.UNICODE):
            raise ValueError("이름은 한글·영문·숫자·밑줄·하이픈 1~64자로 입력하세요.")
        self.name = name
        self.lock = threading.RLock()
        self.directory = Path(root) / "data/gold"
        self.path = self.directory / f"{name}.jsonl"
        self.position_path = self.directory / f"{name}.position.json"
        windows, groups = {}, {}
        for split in sorted(splits or SPLITS):
            for row in read_jsonl(Path(root) / f".local/windows/{split}.jsonl"):
                validate_window(row)
                if row["split"] != split:
                    raise ValueError("창의 split과 파일 이름이 다릅니다.")
                if sources and row["source"] not in sources:
                    continue
                if row["id"] in windows:
                    raise ValueError("중복 창 id")
                windows[row["id"]] = row
                key = row["source"], row["clip"]
                groups.setdefault(key, {"source": key[0], "clip": key[1], "ids": []})["ids"].append(row["id"])
        self.clips = list(groups.values())
        if not self.clips:
            raise ValueError("선택 조건에 맞는 창이 없습니다.")
        if sample is not None:
            self.clips = sample_clips(self.clips, sample, seed)
        else:
            self.clips.sort(key=lambda c: (c["source"], c["clip"]))
        selected_sources = {clip["source"] for clip in self.clips}
        utterances = defaultdict(list)
        for path in sorted((Path(root) / ".local/utterances").glob("*.jsonl")):
            for row in read_jsonl(path):
                if row.get("source") in selected_sources:
                    validate_utterance(row)
                    utterances[row["source"]].append(row)
        self.turns, self.indices = {}, {}
        for source in selected_sources:
            turns = sorted(utterances[source], key=lambda u: (u["start"], u["utt"]))
            self.turns[source] = turns
            for index, turn in enumerate(turns):
                identifier = f"{source}#{turn['utt']}"
                if identifier in self.indices:
                    raise ValueError("중복 발화 id")
                self.indices[identifier] = index
        self.ids = []
        for clip in self.clips:
            if any(identifier not in self.indices for identifier in clip["ids"]):
                raise ValueError("창에 대응하는 발화가 없습니다.")
            clip["ids"].sort(key=self.indices.__getitem__)
            positions = [self.indices[identifier] for identifier in clip["ids"]]
            if positions != list(range(positions[0], positions[-1] + 1)):
                raise ValueError("클립 발화가 연속적이지 않습니다.")
            for identifier in clip["ids"]:
                index = self.indices[identifier]
                turn = self.turns[clip["source"]][index]
                context = self.context_indices(clip["source"], index)
                if (windows[identifier]["current"] != turn["text"] or
                        windows[identifier]["context"] != [self.turns[clip["source"]][i]["text"] for i in context]):
                    raise ValueError("창과 발화 문맥이 다릅니다. 창을 다시 생성하세요.")
            self.ids.extend(clip["ids"])
        self.id_set = set(self.ids)
        if sample is not None:
            sample_path = self.directory / f"sample-{seed}.txt"
            content = "".join(identifier + "\n" for identifier in self.ids)
            if sample_path.exists() and sample_path.read_text(encoding="utf-8") != content:
                raise ValueError("같은 seed의 표본이 이미 다릅니다. 동일 조건이나 다른 seed를 사용하세요.")
            atomic_write(sample_path, content)
        self.labels = {}
        if self.path.exists():
            for row in read_jsonl(self.path):
                validate_label(row)
                if set(row) != LABEL_FIELDS or row["labeler"] != f"human:{name}":
                    raise ValueError("기존 gold 파일의 필드 또는 라벨러가 다릅니다.")
                if row["id"] in self.labels:
                    raise ValueError("기존 gold 파일에 중복 id가 있습니다.")
                self.labels[row["id"]] = row
        self.position = next((identifier for identifier in self.ids if identifier not in self.labels), self.ids[-1])
        if self.position_path.exists():
            position = json.loads(self.position_path.read_text(encoding="utf-8"))["id"]
            if position in self.id_set:
                self.position = position

    def context_indices(self, source, index):
        turns = self.turns[source]
        indices = []
        for previous in range(index - 1, max(-1, index - 4), -1):
            if turns[previous + 1]["start"] - turns[previous]["end"] > 20:
                break
            indices.append(previous)
        return list(reversed(indices))

    def state(self):
        return {"name": self.name, "position": self.position, "total": len(self.ids),
                "done": len(self.id_set & self.labels.keys()),
                "clips": [{**clip, "done": sum(i in self.labels for i in clip["ids"])} for clip in self.clips]}

    def clip(self, index):
        if not 0 <= index < len(self.clips):
            raise ValueError("클립 번호가 범위를 벗어났습니다.")
        clip = self.clips[index]
        source = clip["source"]
        turns = self.turns[source]
        first, last = self.indices[clip["ids"][0]], self.indices[clip["ids"][-1]]
        rows = []
        for i in range(max(0, first - 3), last + 1):
            turn = turns[i]
            identifier = f"{source}#{turn['utt']}"
            rows.append({"id": identifier, "text": turn["text"], "start": turn["start"], "end": turn["end"],
                         "gap": None if i == 0 else turn["start"] - turns[i - 1]["end"],
                         "context_ids": [f"{source}#{turns[j]['utt']}" for j in self.context_indices(source, i)],
                         "labelable": identifier in clip["ids"], "label": self.labels.get(identifier)})
        return {**clip, "utterances": rows,
                "listen_url": (f"https://www.youtube.com/watch?v={quote(source[3:], safe='')}&t={int(turns[first]['start'])}s"
                               if source.startswith("yt:") else None)}

    def set_position(self, identifier):
        if identifier not in self.id_set:
            raise ValueError("선택되지 않은 id입니다.")
        with self.lock:
            atomic_write(self.position_path, json.dumps({"id": identifier}, ensure_ascii=False) + "\n")
            self.position = identifier

    def save_label(self, row):
        if not isinstance(row, dict) or set(row) - LABEL_FIELDS:
            raise ValueError("id와 라벨 필드만 허용합니다. 전사 본문을 보내지 마세요.")
        row = dict(row)
        row.setdefault("labeler", f"human:{self.name}")
        validate_label(row)
        if row["labeler"] != f"human:{self.name}" or row["id"] not in self.id_set:
            raise ValueError("라벨러 또는 id가 올바르지 않습니다.")
        with self.lock:
            labels = {**self.labels, row["id"]: row}
            atomic_write(self.path, "".join(json.dumps(labels[i], ensure_ascii=False) + "\n" for i in sorted(labels)))
            self.labels = labels
            return self.state()


def make_server(store, port=8765):
    page = Path(__file__).with_name("index.html").read_bytes()

    class Handler(BaseHTTPRequestHandler):
        timeout = 5

        def log_message(self, *_):
            pass  # Do not log labels, notes or transcript data.

        def respond(self, status, payload, content_type="application/json; charset=utf-8"):
            body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            self.end_headers()
            self.wfile.write(body)

        def read_body(self):
            # Drain a bounded declared body before *any* application response,
            # including host/origin/content-type rejections. Closing with unread
            # request bytes can reset the connection before the client sees it.
            lengths = self.headers.get_all("Content-Length", [])
            if self.headers.get("Transfer-Encoding") is not None:
                raise ValueError("Transfer-Encoding is not supported")
            if len(lengths) > 1 or (lengths and not re.fullmatch(r"[0-9]+", lengths[0])):
                raise ValueError("Invalid Content-Length")
            length = int(lengths[0]) if lengths else 0
            try:
                body = self.rfile.read(min(length, MAX_REQUEST_BODY))
            except socket.timeout:
                raise ValueError("Incomplete request body (timeout)") from None
            if length > MAX_REQUEST_BODY:
                raise ValueError("Request body exceeds 65536 bytes")
            if len(body) != length:
                raise ValueError("Incomplete request body")
            return body

        def allowed(self):
            port_number = self.server.server_port
            hosts = {f"127.0.0.1:{port_number}", f"localhost:{port_number}"}
            host = self.headers.get("Host", "")
            origin = self.headers.get("Origin")
            if host not in hosts or (origin is not None and origin != f"http://{host}"):
                self.respond(403, {"error": "로컬 페이지에서만 사용할 수 있습니다."})
                return False
            return True

        def do_GET(self):
            try:
                self.read_body()
            except ValueError as exc:
                self.respond(400, {"error": str(exc)})
                return
            if not self.allowed():
                return
            url = urlsplit(self.path)
            try:
                if url.path == "/":
                    self.respond(200, page, "text/html; charset=utf-8")
                elif url.path == "/api/state":
                    self.respond(200, store.state())
                elif url.path == "/api/clip":
                    self.respond(200, store.clip(int(parse_qs(url.query).get("index", ["0"])[0])))
                else:
                    self.respond(404, {"error": "없는 경로입니다."})
            except (ValueError, TypeError) as exc:
                self.respond(400, {"error": str(exc)})

        def do_POST(self):
            try:
                body = self.read_body()
            except ValueError as exc:
                self.respond(400, {"error": str(exc)})
                return
            if not self.allowed():
                return
            status = 200
            try:
                if self.headers.get_content_type() != "application/json":
                    raise ValueError("JSON 요청만 허용합니다.")
                if not body:
                    raise ValueError("요청 크기가 올바르지 않습니다.")
                row = json.loads(body)
                if self.path == "/api/label":
                    payload = store.save_label(row)
                elif self.path == "/api/position":
                    if not isinstance(row, dict) or set(row) != {"id"} or type(row["id"]) is not str:
                        raise ValueError("id 하나만 보내세요.")
                    store.set_position(row["id"])
                    payload = store.state()
                else:
                    status, payload = 404, {"error": "없는 경로입니다."}
            except (ValueError, TypeError) as exc:
                status, payload = 400, {"error": str(exc)}
            except OSError:
                status, payload = 500, {"error": "파일 저장에 실패했습니다. 디스크 상태를 확인하고 다시 시도하세요."}
            # A socket write failure must not enter the storage error handler and
            # attempt a second response on the same connection.
            self.respond(status, payload)

    return GoldHTTPServer(("127.0.0.1", port), Handler)


def main(argv=None):
    parser = argparse.ArgumentParser(description="한국어 gold 라벨링 (로컬 전용)")
    parser.add_argument("--name", required=True)
    parser.add_argument("--split", nargs="+", choices=sorted(SPLITS))
    parser.add_argument("--source", nargs="+", help="예: yt:VIDEO_ID")
    parser.add_argument("--sample", type=int, help="최소 창 개수. 마지막 클립은 통째로 포함")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args(argv)
    try:
        store = GoldStore(args.name, splits=args.split, sources=args.source, sample=args.sample, seed=args.seed)
        server = make_server(store, args.port)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(f"http://127.0.0.1:{server.server_port}  |  {len(store.ids)} windows, {len(store.clips)} clips  |  Ctrl+C 종료")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
