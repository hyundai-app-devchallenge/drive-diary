"""Loopback-only video approvals, with no audio or manifest mutations."""

import argparse
from collections import Counter, defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import re
import socket
import threading
import time
from urllib.parse import parse_qs, urlsplit

from pipeline.collect import (
    Manifest, clean, hangul_share, human_decision, is_human_approved,
    migrate_approvals, read_approvals, write_approvals,
)

ROOT = Path(__file__).resolve().parents[2]
MAX_REQUEST_BODY = 65536
DECISIONS = ("approve", "reject", "hold")
FILTERS = ("unreviewed", *DECISIONS, "all")


class ReviewHTTPServer(ThreadingHTTPServer):
    def shutdown_request(self, request):
        # Same bounded peer drain as tools/gold/serve.py: immediate shutdown can
        # reset a response still in flight on Windows, especially on rejections.
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

    def handle_error(self, request, client_address):
        pass  # Never put request data or transcript-bearing tracebacks in logs.


def reviewer_name(value):
    if (not isinstance(value, str) or not value.strip()
            or value.strip().casefold() == "claude" or clean(value) != value.strip()):
        raise ValueError("검토자 이름은 비어 있거나 claude일 수 없으며 제어 문자를 넣을 수 없습니다.")
    return value.strip()


def number(value):
    try:
        result = float(value)
        return result if math.isfinite(result) and result >= 0 else None
    except (TypeError, ValueError):
        return None


def read_json(path, *, lines=False):
    try:
        with path.open(encoding="utf-8") as stream:
            return [json.loads(line) for line in stream if line.strip()] if lines else json.load(stream)
    except FileNotFoundError:
        return [] if lines else {}
    except (ValueError, UnicodeError):
        # JSONDecodeError can carry transcript text. Never propagate it.
        raise ValueError("로컬 JSON 파일 형식이 올바르지 않습니다.") from None


def listening_links(video_id, utterances, duration):
    """Nearest-lower utterance index floor((N-1)*q), not time interpolation.

    Links use whole seconds (floor); lines begin at the selected utterance.
    Short transcripts may repeat samples or have fewer than three remaining lines.
    """
    result = []
    for percent in (10, 30, 50, 70, 90):
        index = (len(utterances) - 1) * percent // 100 if utterances else 0
        seconds = int(utterances[index]["start"] if utterances else (number(duration) or 0) * percent / 100)
        result.append(dict(percent=percent, seconds=seconds,
                           url=f"https://www.youtube.com/watch?v={video_id}&t={seconds}s",
                           lines=[dict(start=row["start"], text=row["text"])
                                  for row in utterances[index:index + 3]]))
    return result


def label_shares(directory):
    """Separate each run and v1 utterances (#) / v2 blocks (@); deduplicate IDs.

    Published label files live directly in a run directory. Do not descend into
    .chunks journals, old pytest fixtures, or combine run/unclear duplicates.
    """
    grouped = defaultdict(dict)
    if not directory.exists():
        return {}
    files = list(directory.glob("*.jsonl")) + list(directory.glob("*/*.jsonl"))
    for path in sorted(files):
        if path.stem in {"run", "unclear"} or path.parent.name.startswith("."):
            continue
        for row in read_json(path, lines=True):
            if not isinstance(row, dict) or type(row.get("skip")) is not bool:
                continue
            match = re.fullmatch(r"yt:([A-Za-z0-9_-]{11})([#@])[0-9]+", str(row.get("id", "")))
            if not match or not str(row.get("labeler", "")).startswith("llm:"):
                continue
            video_id, unit = match.groups()
            run = str(path.parent.relative_to(directory))
            grouped[video_id, run, unit][row["id"]] = row["skip"]
    result = defaultdict(list)
    for (video_id, run, unit), labels in grouped.items():
        result[video_id].append(dict(run=run, unit="block" if unit == "@" else "utterance",
                                     count=len(labels), share=sum(labels.values()) / len(labels)))
    return result


def video_details(row, directory, skips):
    path = directory / f"yt_{row['video_id']}.jsonl"
    utterances = read_json(path, lines=True)
    if any(not isinstance(u, dict) or number(u.get("start")) is None
           or number(u.get("end")) is None or not isinstance(u.get("text"), str) for u in utterances):
        raise ValueError("로컬 발화 파일 형식이 올바르지 않습니다.")
    utterances.sort(key=lambda u: u["start"])
    stats = read_json(path.with_suffix(".stats.json"))
    duration = number(stats.get("audio_duration")) or number(row["duration_s"])
    removed = number(stats.get("vad_removed_seconds"))
    count = len(utterances) if path.exists() else number(stats.get("utterance_count"))
    probabilities = [number(u.get("mean_word_probability")) for u in utterances]
    probabilities = [p for p in probabilities if p is not None and p <= 1]
    speech = None
    if duration and removed is not None and removed <= duration:
        speech = (duration - removed) / duration
    elif duration and utterances:
        # Union intervals so overlapping utterances never double-count speech.
        total, end = 0.0, 0.0
        for u in utterances:
            stop = min(duration, u["end"])
            total += max(0, stop - max(end, u["start"]))
            end = max(end, stop)
        speech = total / duration
    return dict(
        metrics=dict(speech_share=speech, utterance_count=count,
                     utterances_per_minute=count * 60 / duration if count is not None and duration else None,
                     hangul_share=hangul_share(" ".join(u["text"] for u in utterances)),
                     asr_confidence=sum(probabilities) / len(probabilities) if probabilities else None,
                     skip_shares=skips),
        listening=listening_links(row["video_id"], utterances, row["duration_s"]),
    )


class ReviewStore:
    def __init__(self, name, *, root=ROOT, labels=None):
        self.name = reviewer_name(name)
        self.root = Path(root)
        self.path = self.root / "data/approved.tsv"
        self.lock = threading.RLock()
        self.manifest = Manifest(self.root / "data/sources.tsv", self.root / ".local/review")
        migrate_approvals(self.path)
        skips = label_shares(Path(labels) if labels is not None else self.root / ".local/labels")
        self.videos = {}
        for video_id, row in self.manifest.rows.items():
            if row["status"] not in {"downloaded", "candidate"}:
                continue
            # No transcript bodies in state-independent disk files or console logs.
            self.videos[video_id] = dict(row) | video_details(
                row, self.root / ".local/utterances", skips.get(video_id, []))

    def summary(self, approvals=None):
        if approvals is None:
            with self.lock:
                return self.summary(read_approvals(self.path))
        counts = Counter(human_decision(approvals.get(v)) for v in self.manifest.rows)
        channels = {split: set() for split in ("train", "dev", "test")}
        for video_id, row in self.manifest.rows.items():
            if is_human_approved(video_id, approvals) and row["channel_id"] and row["split"] in channels:
                channels[row["split"]].add(row["channel_id"])
        reviewed = sum(human_decision(approvals.get(v)) is not None for v in self.videos)
        return dict(human_decisions={key: counts[key] for key in DECISIONS},
                    unreviewed=counts[None], total=len(self.manifest.rows),
                    progress=dict(reviewed=reviewed, total=len(self.videos)),
                    approved_channels={key: len(value) for key, value in channels.items()},
                    unapplied_rejects=sum(row["status"] == "downloaded"
                                         and human_decision(approvals.get(v)) == "reject"
                                         for v, row in self.manifest.rows.items()))

    def state(self, status="all", downloaded=False):
        if status not in FILTERS:
            raise ValueError("알 수 없는 필터입니다.")
        with self.lock:
            approvals = read_approvals(self.path)
            videos = []
            for video_id, row in self.videos.items():
                approval = approvals.get(video_id)
                decision = human_decision(approval)
                if downloaded and row["status"] != "downloaded":
                    continue
                if status != "all" and (decision or "unreviewed") != status:
                    continue
                videos.append(row | dict(human=approval if decision else None,
                                         prescreen=approval if approval and approval["decided_by"] == "claude" else None))
            videos.sort(key=lambda row: (row["human"] is not None, row["status"] != "downloaded",
                                         row["video_id"]))
            return dict(reviewer=self.name, videos=videos, summary=self.summary(approvals))

    def save(self, payload):
        if (not isinstance(payload, dict) or set(payload) != {"video_id", "decision", "comment"}
                or any(not isinstance(payload[key], str) for key in payload)):
            raise ValueError("video_id, decision, comment 문자열만 보내세요.")
        if payload["video_id"] not in self.manifest.rows:
            raise ValueError("매니페스트에 없는 영상입니다.")
        if payload["decision"] not in DECISIONS:
            raise ValueError("승인, 거절, 보류 중 하나를 선택하세요.")
        with self.lock:
            approvals = read_approvals(self.path)
            row = dict(payload, decided_by=self.name, reason="human review")
            row["comment"] = clean(row["comment"])[:500]
            approvals[row["video_id"]] = row
            write_approvals(self.path, approvals)
            return dict(saved=row, summary=self.summary(approvals))


def make_server(store, port=8766):
    page = Path(__file__).with_name("index.html").read_bytes()

    class Handler(BaseHTTPRequestHandler):
        timeout = 5

        def log_message(self, *_):
            pass

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
            if length > MAX_REQUEST_BODY or len(body) != length:
                raise ValueError("요청 본문이 너무 크거나 불완전합니다.")
            return body

        def allowed(self):
            hosts = {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}
            host, origin = self.headers.get("Host", ""), self.headers.get("Origin")
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
                    query = parse_qs(url.query)
                    self.respond(200, store.state(query.get("filter", ["all"])[0],
                                                  query.get("downloaded", ["0"])[0] == "1"))
                elif url.path == "/api/summary":
                    self.respond(200, store.summary())
                else:
                    self.respond(404, {"error": "없는 경로입니다."})
            except (ValueError, OSError):
                self.respond(400, {"error": "검토 데이터를 읽지 못했습니다. 파일 형식을 확인하세요."})

        def do_POST(self):
            # Drain before host/origin/type validation, as in the gold server.
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
                try:
                    payload = json.loads(body)
                except (ValueError, UnicodeError):
                    raise ValueError("JSON 요청 형식이 올바르지 않습니다.") from None
                if self.path == "/api/decision":
                    payload = store.save(payload)
                else:
                    status, payload = 404, {"error": "없는 경로입니다."}
            except (ValueError, TypeError) as exc:
                status, payload = 400, {"error": str(exc)}
            except OSError:
                status, payload = 500, {"error": "파일 저장에 실패했습니다. 디스크 상태를 확인하세요."}
            self.respond(status, payload)

    return ReviewHTTPServer(("127.0.0.1", port), Handler)


def main(argv=None):
    parser = argparse.ArgumentParser(description="영상 사람 검토 콘솔 (로컬 전용)")
    parser.add_argument("--name", required=True, help="승인 파일에 기록할 사람 이름")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--labels", type=Path, help="선택: silver 라벨 실행 디렉터리 또는 상위 디렉터리")
    args = parser.parse_args(argv)
    try:
        store = ReviewStore(args.name, labels=args.labels)
        server = make_server(store, args.port)
    except (ValueError, OSError) as exc:
        parser.error(str(exc))
    print(f"http://127.0.0.1:{server.server_port} | {len(store.videos)} videos | Ctrl+C 종료")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
