"""T20 uses only synthetic local fixtures and loopback HTTP; no browser/network."""

from contextlib import contextmanager
from email.message import Message
from http.client import HTTPConnection
from io import BytesIO
import json
import socket
import threading

import pytest

from pipeline import collect as c
from tools.review import serve as r


def jsonl(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


@pytest.fixture
def root(tmp_path):
    manifest = c.Manifest(tmp_path / "data/sources.tsv", tmp_path / "scratch")
    for n, status, channel in [(1, "downloaded", "one"), (2, "candidate", "two"),
                                (3, "downloaded", "one"), (4, "rejected", "four"),
                                (5, "failed", "five"), (6, "candidate", "six")]:
        manifest.update(f"{n:011d}", status=status, channel_id=channel, channel="합성 채널",
                        title="합성 영상", duration_s=100, query="search: 합성", upload_date="20261101")
    # Use stored manifest splits deliberately: the console must not recompute them.
    for n, split in [(1, "dev"), (2, "train"), (3, "dev"), (4, "test"), (5, "test"), (6, "test")]:
        manifest.rows[f"{n:011d}"]["split"] = split
    manifest.save()
    c.write_approvals(tmp_path / "data/approved.tsv", {
        "00000000001": dict(video_id="00000000001", decision="approve", reason="합성 사전 의견", decided_by="claude"),
        "00000000003": dict(video_id="00000000003", decision="approve", reason="human review", decided_by="user", comment="기존 합성 의견"),
    })
    utterances = [dict(source="yt:00000000001", utt=i, start=i * 8 + .75, end=i * 8 + 4.75,
                       text=f"합성 대사 {i}", mean_word_probability=.2 if i % 2 else .8) for i in range(11)]
    jsonl(tmp_path / ".local/utterances/yt_00000000001.jsonl", utterances)
    (tmp_path / ".local/utterances/yt_00000000001.stats.json").write_text(
        json.dumps(dict(audio_duration=100, vad_removed_seconds=40, utterance_count=11)), encoding="utf-8")
    for run, unit in [("silver1", "#"), ("silver2", "@")]:
        jsonl(tmp_path / f".local/labels/{run}/yt%3A00000000001.jsonl", [
            dict(id=f"yt:00000000001{unit}{i}", skip=i == 0, labeler="llm:fake") for i in range(4)])
    # Mirrors the same rows in unclear: must not double-count them.
    jsonl(tmp_path / ".local/labels/silver1/unclear.jsonl", [dict(id="yt:00000000001#0", skip=True, labeler="llm:fake")])
    return tmp_path


@contextmanager
def running(store):
    server = r.make_server(store, port=0)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        assert not thread.is_alive()


def request(server, path="/api/state", payload=None, *, raw=None, headers=None, method=None):
    conn = HTTPConnection("127.0.0.1", server.server_port, timeout=5)
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else raw
    headers = {"Content-Type": "application/json"} | (headers or {})
    try:
        conn.request(method or ("POST" if body is not None else "GET"), path, body=body, headers=headers)
        response = conn.getresponse()
        data = response.read()
        return response.status, response.headers, data
    finally:
        conn.close()


def get(server, path="/api/state"):
    status, _, body = request(server, path)
    assert status == 200
    return json.loads(body)


def test_list_indicators_filters_prescreen_and_no_mutations(root):
    manifest = (root / "data/sources.tsv").read_bytes()
    store = r.ReviewStore("검토자", root=root)
    with running(store) as server:
        assert server.server_address[0] == "127.0.0.1"
        state = get(server)
        assert [v["video_id"] for v in state["videos"]] == ["00000000001", "00000000002", "00000000006", "00000000003"]
        video = state["videos"][0]
        assert video["human"] is None and video["prescreen"]["reason"] == "합성 사전 의견"
        m = video["metrics"]
        assert m["speech_share"] == .6 and m["utterance_count"] == 11
        assert m["utterances_per_minute"] == 6.6 and m["hangul_share"] == 1
        assert m["asr_confidence"] == pytest.approx((6 * .8 + 5 * .2) / 11)
        assert [(s["unit"], s["share"], s["count"]) for s in m["skip_shares"]] == [("utterance", .25, 4), ("block", .25, 4)]
        assert len(get(server, "/api/state?filter=unreviewed")["videos"]) == 3
        assert len(get(server, "/api/state?filter=approve")["videos"]) == 1
        assert len(get(server, "/api/state?filter=reject")["videos"]) == 0
        assert len(get(server, "/api/state?filter=hold")["videos"]) == 0
        assert len(get(server, "/api/state?downloaded=1")["videos"]) == 2
        assert len(get(server, "/api/state?filter=unreviewed&downloaded=1")["videos"]) == 1
    assert (root / "data/sources.tsv").read_bytes() == manifest
    assert not (root / ".local/audio").exists()


def test_save_all_decisions_replace_claude_resume_and_summary(root, capsys):
    path = root / "data/approved.tsv"
    store = r.ReviewStore("검토자", root=root)
    with running(store) as server:
        for n, decision in [(1, "approve"), (2, "hold"), (6, "reject"), (1, "reject"), (3, "approve"), (4, "approve")]:
            payload = dict(video_id=f"{n:011d}", decision=decision, comment="  합성\t코멘트\r\n" + "나" * 600)
            status, _, body = request(server, "/api/decision", payload)
            assert status == 200
            saved = json.loads(body)["saved"]
            assert saved["decided_by"] == "검토자" and saved["reason"] == "human review"
            assert saved["decision"] == decision and len(saved["comment"]) == 500
            assert "\t" not in saved["comment"] and "\n" not in saved["comment"]
            assert c.read_approvals(path)[payload["video_id"]] == saved
        state = get(server)
        assert state["summary"] == get(server, "/api/summary")
        s = state["summary"]
        assert s["human_decisions"] == {"approve": 2, "reject": 2, "hold": 1}
        assert s["unreviewed"] == 1 and s["progress"] == {"reviewed": 4, "total": 4}
        assert s["approved_channels"] == {"train": 0, "dev": 1, "test": 1}
        assert s["unapplied_rejects"] == 1
        video = next(v for v in state["videos"] if v["video_id"] == "00000000001")
        assert video["prescreen"] is None and video["human"]["decision"] == "reject"
    with running(r.ReviewStore("다음 검토자", root=root)) as server:
        resumed = get(server)
        assert resumed["summary"] == state["summary"]
        assert resumed["videos"] == state["videos"]
    assert len(path.read_text(encoding="utf-8").splitlines()) == 6  # Header + five unique IDs.
    logs = capsys.readouterr()
    assert not logs.out and not logs.err


@pytest.mark.parametrize("payload", [
    dict(video_id="unknown0000", decision="approve", comment=""),
    dict(video_id="00000000001", decision="rating", comment=""),
    dict(video_id="00000000001", decision=[], comment=""),
    dict(video_id="00000000001", decision="approve", comment=None),
    dict(video_id="00000000001", decision="approve", comment="", decided_by="claude"),
    dict(video_id="00000000001", decision="approve"), [], None,
])
def test_invalid_payload_does_not_write(root, payload):
    with running(r.ReviewStore("user", root=root)) as server:
        before = (root / "data/approved.tsv").read_bytes(), (root / "data/approved.tsv").stat().st_mtime_ns
        status, _, _ = request(server, "/api/decision", raw=json.dumps(payload).encode())
        assert status == 400
        assert ((root / "data/approved.tsv").read_bytes(), (root / "data/approved.tsv").stat().st_mtime_ns) == before


@pytest.mark.parametrize("name", ["", "  ", "claude", " CLAUDE ", "이름\n추가", None])
def test_invalid_reviewer_does_not_migrate(root, name):
    path = root / "data/approved.tsv"
    before = path.read_bytes(), path.stat().st_mtime_ns
    with pytest.raises(ValueError):
        r.ReviewStore(name, root=root)
    assert (path.read_bytes(), path.stat().st_mtime_ns) == before


def test_listening_quantiles_hand_example_and_candidates():
    rows = [dict(start=n * 10 + .9, text=f"합성 {n}") for n in range(11)]
    links = r.listening_links("00000000001", rows, 100)
    assert [link["seconds"] for link in links] == [10, 30, 50, 70, 90]
    assert [link["lines"][0]["text"] for link in links] == [f"합성 {n}" for n in (1, 3, 5, 7, 9)]
    assert links[0]["lines"] == rows[1:4]
    assert links[-1]["lines"] == rows[9:]
    assert links[2]["url"] == "https://www.youtube.com/watch?v=00000000001&t=50s"
    candidates = r.listening_links("00000000001", [], 210)
    assert [link["seconds"] for link in candidates] == [21, 63, 105, 147, 189]
    assert all(not link["lines"] for link in candidates)
    assert [link["seconds"] for link in r.listening_links("00000000001", rows[:1], 100)] == [0] * 5


def test_no_optional_files_and_union_speech(root):
    store = r.ReviewStore("user", root=root, labels=root / "absent")
    video = next(v for v in store.state()["videos"] if v["video_id"] == "00000000002")
    assert all(v is None for k, v in video["metrics"].items() if k != "skip_shares")
    assert video["metrics"]["skip_shares"] == []
    assert [s["seconds"] for s in video["listening"]] == [10, 30, 50, 70, 90]
    row = dict(video_id="00000000009", duration_s="100")
    jsonl(root / "union/yt_00000000009.jsonl", [
        dict(start=0, end=20, text="합성"), dict(start=10, end=30, text="대사")])
    assert r.video_details(row, root / "union", [])["metrics"]["speech_share"] == .3


def test_http_page_security_rejections_and_logs(root, capsys):
    with running(r.ReviewStore("user", root=root)) as server:
        status, headers, page = request(server, "/")
        assert status == 200 and "text/html" in headers["Content-Type"]
        assert headers["Cache-Control"] == "no-store"
        # Local security software may rewrite the wire CSP. Verify the exact
        # application header below using the same handler without interception.
        assert "connect-src 'self'" in headers["Content-Security-Policy"]
        html = page.decode("utf-8")
        assert '<html lang="ko">' in html
        assert '<iframe' not in html and '<script src=' not in html and 'textContent' in html
        assert 'noopener noreferrer' in html and "link.target = '_blank'" in html
        before = (root / "data/approved.tsv").read_bytes()
        payload = dict(video_id="00000000001", decision="approve", comment="합성 대사 로그 금지")
        for headers, expected in [({"Host": "evil.invalid"}, 403),
                                  ({"Origin": "http://evil.invalid"}, 403),
                                  ({"Content-Type": "text/plain"}, 400)]:
            assert request(server, "/api/decision", payload, headers=headers)[0] == expected
        assert request(server, "/api/decision", raw=b'{"comment":"synthetic secret"')[0] == 400
        assert request(server, "/api/decision", raw=b"x" * (r.MAX_REQUEST_BODY + 1))[0] == 400
        assert request(server, "/api/decision", raw=b"")[0] == 400
        assert request(server, "/absent", payload)[0] == 404
        assert request(server, "/absent")[0] == 404
        assert request(server, "/api/state?filter=bad")[0] == 400
        assert (root / "data/approved.tsv").read_bytes() == before
        assert '합성 대사' in json.dumps(get(server), ensure_ascii=False)
    logs = capsys.readouterr()
    assert logs.out == logs.err == ""


def test_save_failure_http_keeps_file(root, monkeypatch):
    with running(r.ReviewStore("user", root=root)) as server:
        before = (root / "data/approved.tsv").read_bytes()
        def fail(*_):
            raise OSError("simulated disk failure")
        monkeypatch.setattr(c.os, "replace", fail)
        assert request(server, "/api/decision", dict(video_id="00000000001", decision="hold", comment=""))[0] == 500
        assert (root / "data/approved.tsv").read_bytes() == before


def test_channel_summary_unique_and_ten_target(root):
    store = r.ReviewStore("user", root=root)
    approvals = c.read_approvals(store.path)
    for split in ("dev", "test"):
        for n in range(10):
            video_id = f"{100 + n + (100 if split == 'test' else 0):011d}"
            store.manifest.rows[video_id] = dict(channel_id=f"{split}{n}", split=split, status="candidate")
            approvals[video_id] = dict(decided_by="user", decision="approve")
    assert store.summary(approvals)["approved_channels"] == {"train": 0, "dev": 11, "test": 10}


def test_bad_json_never_exposes_transcript(root):
    path = root / ".local/utterances/yt_00000000001.jsonl"
    path.write_text('{"text": "synthetic private text"', encoding="utf-8")
    with pytest.raises(ValueError) as exc:
        r.ReviewStore("user", root=root)
    assert "synthetic private text" not in str(exc.value)


@pytest.mark.parametrize("headers,body", [
    ([("Content-Length", "nope")], b"x"),
    ([("Content-Length", "1"), ("Content-Length", "1")], b"x"),
    ([("Transfer-Encoding", "chunked")], b"x"),
    ([("Content-Length", "10")], b"short"),
    ([("Content-Length", str(r.MAX_REQUEST_BODY + 1))], b"x" * (r.MAX_REQUEST_BODY + 1)),
], ids=["bad-length", "duplicate-length", "transfer-encoding", "truncated", "oversized"])
def test_invalid_framing_without_local_http_interception(headers, body):
    server = r.make_server(None, 0)
    try:
        handler = server.RequestHandlerClass.__new__(server.RequestHandlerClass)
        handler.headers = Message()
        for name, value in headers:
            handler.headers[name] = value
        handler.rfile = BytesIO(body)
        with pytest.raises(ValueError):
            handler.read_body()
    finally:
        server.server_close()


def test_application_csp_and_graceful_connection_shutdown():
    server = r.make_server(None, 0)
    calls = []
    class Peer:
        def settimeout(self, timeout):
            assert 0 < timeout <= 2
            calls.append("timeout")
        def recv(self, size):
            assert size == 8192
            calls.append("recv")
            return b""
        def shutdown(self, how):
            assert how == socket.SHUT_WR
            calls.append("shutdown")
        def close(self):
            calls.append("close")
    try:
        handler = server.RequestHandlerClass.__new__(server.RequestHandlerClass)
        handler.request_version = "HTTP/1.1"
        handler.requestline = "GET / HTTP/1.1"
        handler.command = "GET"
        handler.wfile = BytesIO()
        handler.respond(200, {})
        assert b"default-src 'none'" in handler.wfile.getvalue()
        assert b"frame-ancestors 'none'" in handler.wfile.getvalue()
        server.shutdown_request(Peer())
        assert calls == ["timeout", "recv", "shutdown", "close"]
    finally:
        server.server_close()
