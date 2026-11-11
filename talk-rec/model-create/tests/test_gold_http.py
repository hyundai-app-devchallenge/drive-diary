"""T07 HTTP integration, causal context, atomic persistence and sampling."""

from contextlib import contextmanager
from email.message import Message
from http.client import HTTPResponse
from io import BytesIO
import json
import socket
import threading
from types import SimpleNamespace
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

import pytest

from common.schema import validate_label
from tools.gold.serve import GoldHTTPServer, GoldStore, MAX_REQUEST_BODY, ROOT, atomic_write, make_server, sample_clips


def write_rows(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


@pytest.fixture
def dataset(tmp_path):
    turns, windows = [], []
    # Prefix beyond a >20s gap, an exact 20s gap, a clip boundary without a gap,
    # and one additional source. Deliberately repeated text still uses distinct IDs.
    for source in ("yt:fixture", "corpus:other"):
        times = [(0, 1), (22, 23), (43, 44), (45, 46), (47, 48), (49, 50)]
        for i, (start, end) in enumerate(times):
            turns.append(dict(source=source, utt=i, start=start, end=end,
                              text=f"비공개 테스트 전사 {source} {i}", asr="human", speaker=None))
            context = []
            for j in range(i - 1, max(-1, i - 4), -1):
                if times[j + 1][0] - times[j][1] > 20:
                    break
                context.append(f"비공개 테스트 전사 {source} {j}")
            windows.append(dict(id=f"{source}#{i}", source=source, split="dev",
                                clip=0 if i == 0 else 1 if i < 4 else 2,
                                context=list(reversed(context)), current=turns[-1]["text"]))
    write_rows(tmp_path / ".local/utterances/arbitrary-name.jsonl", list(reversed(turns)))
    write_rows(tmp_path / ".local/windows/dev.jsonl", list(reversed(windows)))
    return tmp_path


@contextmanager
def running(store):
    server = make_server(store, 0)
    assert server.server_address[0] == "127.0.0.1"
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        assert not thread.is_alive()


def request(base, path, body=None, headers=None):
    supplied = {"Content-Type": "application/json", **(headers or {})}
    req = Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=supplied)
    try:
        response = urlopen(req, timeout=5)
    except HTTPError as exc:
        response = exc
    with response:
        raw = response.read()
        data = raw.decode() if response.headers.get_content_type() == "text/html" else json.loads(raw)
        return response.status, data, response.headers


def label(identifier, **updates):
    return {"id": identifier, "story": 2, "kind": ["recall"], "exclude": [],
            "skip": False, "note": "", **updates}


def test_http_whole_clip_save_and_resume(dataset):
    store = GoldStore("테스트", root=dataset, splits=["dev"], sources=["yt:fixture"])
    with running(store) as base:
        status, page, headers = request(base, "/")
        assert status == 200 and '<html lang="ko">' in page
        assert "전사 본문을 붙여 넣거나 인용하지 마세요" in page
        assert 'src="http' not in page and 'href="http' not in page
        assert headers["Cache-Control"] == "no-store"
        assert "connect-src 'self'" in headers["Content-Security-Policy"]
        status, clip, _ = request(base, "/api/clip?index=1")
        assert status == 200
        assert clip["listen_url"] == "https://www.youtube.com/watch?v=fixture&t=22s"
        assert [t["gap"] for t in clip["utterances"]] == [None, 21, 20, 1]
        assert clip["utterances"][0]["labelable"] is False
        assert clip["utterances"][1]["context_ids"] == []
        assert clip["utterances"][2]["context_ids"] == ["yt:fixture#1"]
        assert clip["utterances"][3]["context_ids"] == ["yt:fixture#1", "yt:fixture#2"]
        for identifier in clip["ids"]:
            status, state, _ = request(base, "/api/label", label(identifier))
            assert status == 200
        assert state["done"] == len(clip["ids"])
        last = clip["ids"][-1]
        assert request(base, "/api/position", {"id": last})[0] == 200
        status, boundary, _ = request(base, "/api/clip?index=2")
        assert status == 200
        assert boundary["utterances"][-2]["context_ids"] == ["yt:fixture#1", "yt:fixture#2", "yt:fixture#3"]
    text = store.path.read_text(encoding="utf-8")
    rows = [json.loads(line) for line in text.splitlines()]
    assert len(rows) == len(clip["ids"])
    for row in rows:
        validate_label(row)
        assert set(row) == {"id", "story", "kind", "exclude", "skip", "labeler", "note"}
        assert row["labeler"] == "human:테스트"
    for turn in clip["utterances"]:
        assert turn["text"] not in text
    resumed = GoldStore("테스트", root=dataset, splits=["dev"], sources=["yt:fixture"])
    with running(resumed) as base:
        _, state, _ = request(base, "/api/state")
        assert state["position"] == last
        assert state["done"] == len(clip["ids"])
        assert request(base, "/api/clip?index=1")[1]["utterances"][-1]["label"] == rows[-1]


@pytest.mark.parametrize("updates", [
    {"story": 2, "kind": []}, {"story": 3, "kind": []},
    {"story": 1, "kind": ["recall"]}, {"story": 0, "kind": ["family_fact"]},
    {"skip": True}, {"skip": True, "story": 0, "kind": [], "exclude": ["external"]},
    {"kind": ["unknown"]}, {"exclude": ["command", "command"]},
    {"story": True}, {"story": 4}, {"note": 7}, {"labeler": "human:other"},
    {"id": "not-selected"}, {"current": "do not save text"},
])
def test_server_rejects_invalid_labels_without_writing(dataset, updates):
    store = GoldStore("reject", root=dataset, splits=["dev"])
    with running(store) as base:
        status, result, _ = request(base, "/api/label", label(store.ids[0], **updates))
        assert status == 400 and "error" in result
    assert not store.path.exists()


def test_http_validation_and_origin(dataset):
    store = GoldStore("http", root=dataset, splits=["dev"])
    with running(store) as base:
        assert request(base, "/api/label", label(store.ids[0]), {"Origin": "https://foreign.example"})[0] == 403
        assert request(base, "/api/state", headers={"Host": "foreign.example"})[0] == 403
        assert request(base, "/api/label", label(store.ids[0]), {"Content-Type": "text/plain"})[0] == 400
        assert request(base, "/api/label", [1])[0] == 400
        assert request(base, "/api/position", {"id": []})[0] == 400
        assert request(base, "/api/position", {"id": "unknown"})[0] == 400
        assert request(base, "/api/clip?index=-1")[0] == 400
        assert request(base, "/api/clip?index=bad")[0] == 400
        assert request(base, "/rubric.md")[0] == 404
        valid = label(store.ids[0], skip=True, story=0, kind=[])
        assert request(base, "/api/label", valid)[0] == 200
        # Story/exclusion judgments are independent.
        assert request(base, "/api/label", label(store.ids[0], story=3, exclude=["sensitive"]))[0] == 200
        assert request(base, "/api/label", label(store.ids[1], story=1, kind=["family_fact"]))[0] == 200


@pytest.mark.parametrize('headers,status', [
    ({'Origin': 'https://foreign.example'}, 403),
    ({'Host': 'foreign.example'}, 403),
    ({'Content-Type': 'text/plain'}, 400),
    ({}, 400),
])
def test_rejections_consume_split_body_and_frame_one_response(dataset, headers, status):
    store = GoldStore('split-body', root=dataset, splits=['dev'])
    server = make_server(store, 0)
    entered = threading.Event()
    consumed = []
    original = server.RequestHandlerClass
    class Observed(original):
        def read_body(self):
            entered.set()
            body = super().read_body()
            consumed.append(len(body))
            return body
    server.RequestHandlerClass = Observed
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01})
    thread.start()
    body = b'{"invalid":true}'
    try:
        with socket.create_connection(server.server_address, timeout=5) as client:
            supplied = {'Host': f'127.0.0.1:{server.server_port}', 'Content-Type': 'application/json', **headers}
            head = 'POST /api/label HTTP/1.1\r\n' + ''.join(f'{k}: {v}\r\n' for k, v in supplied.items())
            client.sendall((head + f'Content-Length: {len(body)}\r\nConnection: close\r\n\r\n').encode() + body[:1])
            assert entered.wait(5)
            assert consumed == []  # Server is still waiting for the declared body.
            client.sendall(body[1:])
            response = HTTPResponse(client)
            response.begin()
            raw = response.read()
            assert response.status == status
            assert int(response.getheader('Content-Length')) == len(raw)
            assert 'error' in json.loads(raw)
            assert consumed == [len(body)]
            client.shutdown(socket.SHUT_WR)
            assert client.recv(1) == b''  # No second response follows the first.
            response.close()
        assert not store.path.exists()
    finally:
        server.shutdown()
        server.server_close()
        thread.join(5)
        assert not thread.is_alive()


@pytest.mark.parametrize('length,body,match,consumed', [
    ('5', b'{}', 'Incomplete', 2),
    (str(MAX_REQUEST_BODY + 1), b'x' * (MAX_REQUEST_BODY + 1), 'exceeds', MAX_REQUEST_BODY),
    ('-1', b'{}', 'Invalid', 0),
    ('bad', b'{}', 'Invalid', 0),
], ids=['short', 'oversized', 'negative', 'nonnumeric'])
def test_request_body_bounds_and_truncation(length, body, match, consumed):
    server = make_server(SimpleNamespace(), 0)
    try:
        handler = server.RequestHandlerClass.__new__(server.RequestHandlerClass)
        handler.headers = Message()
        handler.headers['Content-Length'] = length
        handler.rfile = BytesIO(body)
        with pytest.raises(ValueError, match=match):
            handler.read_body()
        assert handler.rfile.tell() == consumed
    finally:
        server.server_close()


def test_socket_write_failure_does_not_attempt_second_response():
    server = make_server(SimpleNamespace(save_label=lambda row: {'ok': True}), 0)
    try:
        handler = server.RequestHandlerClass.__new__(server.RequestHandlerClass)
        handler.headers = Message()
        handler.headers['Content-Type'] = 'application/json'
        handler.read_body = lambda: b'{}'
        handler.allowed = lambda: True
        handler.path = '/api/label'
        calls = []
        def fail(status, payload):
            calls.append(status)
            raise ConnectionResetError('closed peer')
        handler.respond = fail
        with pytest.raises(ConnectionResetError):
            handler.do_POST()
        assert calls == [200]
    finally:
        server.server_close()


def test_graceful_close_waits_for_peer_before_shutdown():
    server = GoldHTTPServer(('127.0.0.1', 0), object)
    calls = []
    class Peer:
        def settimeout(self, timeout):
            calls.append(('timeout', timeout))
        def recv(self, size):
            calls.append(('recv', size))
            return b''
        def shutdown(self, how):
            calls.append(('shutdown', how))
        def close(self):
            calls.append(('close',))
    try:
        server.shutdown_request(Peer())
        assert calls[0][0] == 'timeout' and 0 < calls[0][1] <= 2
        assert calls[1:] == [('recv', 8192), ('shutdown', socket.SHUT_WR), ('close',)]
    finally:
        server.server_close()


def test_preserve_other_filters_and_fallback_position(dataset):
    original = GoldStore("filters", root=dataset, splits=["dev"])
    original.save_label(label("corpus:other#0"))
    original.set_position("corpus:other#0")
    narrowed = GoldStore("filters", root=dataset, splits=["dev"], sources=["yt:fixture"])
    assert narrowed.position == "yt:fixture#0"
    narrowed.save_label(label("yt:fixture#0"))
    assert "corpus:other#0" in narrowed.labels
    narrowed.position_path.unlink()
    resumed = GoldStore("filters", root=dataset, splits=["dev"], sources=["yt:fixture"])
    assert resumed.position == "yt:fixture#1"
    assert GoldStore("filters", root=dataset, splits=["dev"]).clip(0)["listen_url"] is None


def test_sampling_deterministic_stratified_whole_clips(dataset):
    all_clips = GoldStore("all", root=dataset, splits=["dev"]).clips
    chosen = sample_clips(all_clips, 4, 13)
    assert chosen == sample_clips(list(reversed(all_clips)), 4, 13)
    assert len({c["source"] for c in chosen}) == 2
    assert sum(len(c["ids"]) for c in chosen) >= 4
    assert all(c in all_clips for c in chosen)
    first = GoldStore("first", root=dataset, splits=["dev"], sample=4, seed=13)
    second = GoldStore("second", root=dataset, splits=["dev"], sample=4, seed=13)
    assert first.ids == second.ids
    assert (dataset / "data/gold/sample-13.txt").read_text().splitlines() == first.ids
    with pytest.raises(ValueError, match="同|다릅니다"):
        GoldStore("collision", root=dataset, splits=["dev"], sample=100, seed=13)
    assert len(sample_clips(all_clips, 999, 13)) == len(all_clips)
    with pytest.raises(ValueError):
        sample_clips(all_clips, 0, 13)


def test_atomic_write_failure_preserves_old_file(dataset, monkeypatch):
    path = dataset / "data/gold/atomic.jsonl"
    atomic_write(path, "old\n")
    def fail(*_):
        raise OSError("simulated replace failure")
    monkeypatch.setattr("tools.gold.serve.os.replace", fail)
    with pytest.raises(OSError):
        atomic_write(path, "new\n")
    assert path.read_text() == "old\n"
    assert list(path.parent.glob("*.tmp")) == []


@pytest.mark.parametrize("name", ["../bad", "a/b", "a\\b", "", "a.b", "a" * 65])
def test_unsafe_name(dataset, name):
    with pytest.raises(ValueError):
        GoldStore(name, root=dataset, splits=["dev"])


def test_stale_windows_rejected(dataset):
    path = dataset / ".local/windows/dev.jsonl"
    rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    rows[0]["context"] = ["wrong context"]
    write_rows(path, rows)
    with pytest.raises(ValueError, match="문맥"):
        GoldStore("stale", root=dataset, splits=["dev"])


def test_real_clip_end_to_end_and_cleanup():
    """Read real local inputs; write only temporary gold IDs/labels and delete them."""
    if not (ROOT / ".local/windows/dev.jsonl").exists():
        pytest.skip("Real local windows are not present")
    name = "goldtest_" + uuid.uuid4().hex
    path = ROOT / f"data/gold/{name}.jsonl"
    position_path = ROOT / f"data/gold/{name}.position.json"
    try:
        store = GoldStore(name, splits=["train"])
        with running(store) as base:
            _, clip, _ = request(base, "/api/clip?index=0")
            for identifier in clip["ids"]:
                assert request(base, "/api/label", label(identifier))[0] == 200
            assert request(base, "/api/position", {"id": clip["ids"][-1]})[0] == 200
        text = path.read_text(encoding="utf-8")
        rows = [json.loads(line) for line in text.splitlines()]
        assert len(rows) == len(clip["ids"])
        for row in rows:
            validate_label(row)
            assert set(row) == {"id", "story", "kind", "exclude", "skip", "labeler", "note"}
        for turn in clip["utterances"]:
            assert turn["text"] not in text
        resumed = GoldStore(name, splits=["train"])
        assert resumed.state()["done"] == len(clip["ids"])
        assert resumed.position == clip["ids"][-1]
    finally:
        path.unlink(missing_ok=True)
        position_path.unlink(missing_ok=True)
