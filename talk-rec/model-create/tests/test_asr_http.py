"""Real loopback HTTP, synthetic files only, all servers shut down in finally."""
from contextlib import contextmanager
from http.client import HTTPConnection
import json
import threading
from urllib.parse import quote

import pytest

from demo.asr.configs import CONFIGS
from tools.asrpick import serve
from tools.asrpick.store import PickStore, blind_options, read_picks
from tools.listen.storage import atomic_write, encode_lines, read_jsonl
from test_asr_core import fixture_root


def comparison_root(root):
    rows = fixture_root(root)
    for i, name in enumerate(CONFIGS):
        results = [dict(id=r["id"], config=name, lines=[dict(start=0, end=1, text=f"PLACEHOLDER-{min(i,2)}")],
                        seconds=1, avg_logprob=-.2, no_speech_prob=.1) for r in rows]
        atomic_write(root / ".local/demo/asr" / f"{name}.jsonl", encode_lines(results))
    return rows


@pytest.fixture
def store(tmp_path):
    comparison_root(tmp_path)
    return PickStore("tester", root=tmp_path)


@contextmanager
def running(store):
    server = serve.make_server(store, 0)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        assert not thread.is_alive()


def request(server, path="/api/state", payload=None, headers=None, method=None, raw=None):
    conn = HTTPConnection("127.0.0.1", server.server_port, timeout=5)
    try:
        conn.request(method or ("POST" if payload is not None or raw is not None else "GET"), path,
                     body=raw if raw is not None else json.dumps(payload).encode() if payload is not None else None,
                     headers={"Content-Type": "application/json"} | (headers or {}))
        response = conn.getresponse()
        return response.status, response.headers, response.read()
    finally:
        conn.close()


def test_shuffle_deterministic_and_merged(store):
    block_id = store.rows[0]["id"]
    results = {name: records[block_id] for name, records in store.runs.items()}
    first = blind_options("tester", block_id, results)
    assert first == blind_options("tester", block_id, dict(reversed(list(results.items()))))
    assert len(first) == 3 and sorted(len(x["configs"]) for x in first) == [1, 1, 2]
    assert first == PickStore("tester", root=store.root).options[block_id]
    permutations = {tuple(o["text"] for o in blind_options("tester", f"clip-{i}", results)) for i in range(30)}
    assert len(permutations) > 1


def test_merge_ignores_timing_and_line_boundaries():
    results = {"a": dict(lines=[dict(start=0, end=1, text="X"), dict(start=2, end=3, text="Y")]),
               "b": dict(lines=[dict(start=8, end=10, text="X Y")]),
               "c": dict(lines=[dict(start=0, end=1, text="X  Y")])}
    options = blind_options("tester", "synthetic", results)
    assert len(options) == 2
    assert any(o["configs"] == ["a", "b"] for o in options)


def test_priority_limit_next_batch_and_review(store):
    assert [r["source"] for r in store.rows[:2]] == ["yt:IOlxH67P1JM", "yt:zn4tDPT4RLQ"]
    one = PickStore("tester", root=store.root, limit=1)
    first = one.rows[0]["id"]
    one.save(dict(id=first, choice="all_bad", comment=""))
    assert PickStore("tester", root=store.root, limit=1).rows[0]["id"] != first
    assert PickStore("tester", root=store.root, limit=1, review=True).rows[0]["id"] == first


def test_save_reselect_blind_response_and_restart(store):
    block_id = store.rows[0]["id"]
    merged_slot = next(i+1 for i, x in enumerate(store.options[block_id]) if len(x["configs"]) == 2)
    with running(store) as server:
        assert server.server_address[0] == "127.0.0.1"
        status, headers, page = request(server, "/")
        assert status == 200 and '<html lang="ko">' in page.decode()
        assert "media-src 'self'" in headers["Content-Security-Policy"]
        assert b"<script src=" not in page and b"https://" not in page
        for choice in (1, "all_bad", "similar", merged_slot):
            status, _, body = request(server, "/api/decision", dict(id=block_id, choice=choice, comment="synthetic-comment"))
            assert status == 200 and json.loads(body) == {"saved": True}
            assert not any(name.encode() in body for name in CONFIGS)
        status, _, body = request(server)
        public = json.loads(body)
        assert public["answered"] == 1 and public["rows"][0]["answer"]["choice"] == merged_slot
        assert not any(name.encode() in body for name in CONFIGS)
    rows = read_jsonl(store.path)
    assert len(rows) == 4 and rows[-1]["decided_at"]
    assert set(read_picks(store.path)[block_id]["picked"]) == set(store.options[block_id][merged_slot-1]["configs"])
    restarted = PickStore("tester", root=store.root, review=True)
    assert restarted.options == store.options
    assert restarted.state()["answered"] == 1
    assert not list(store.path.parent.glob("*.tmp")) and not list(store.path.parent.glob("*.lock"))


@pytest.mark.parametrize("path", ["/../secret", "/%2e%2e/secret", "/audio?id=..%2F..%2Fsecret",
    "/audio?id=yt%3AIOlxH67P1JM%400%2F..", "/.local/demo/asr/turbo-base.jsonl"])
def test_path_escape(store, path):
    with running(store) as server:
        assert request(server, path)[0] == 404


@pytest.mark.parametrize("name", ["../x", "x/y", "x\\y", "/tmp/x", "CON", "x:", "", "..", "NUL", "name."])
def test_session_path_escape(tmp_path, name):
    with pytest.raises(ValueError):
        PickStore(name, root=tmp_path)


@pytest.mark.parametrize("headers", [{"Host": "evil.test"}, {"Origin": "https://evil.test"}, {"Sec-Fetch-Site": "cross-site"}])
def test_cross_origin_blocked(store, headers):
    with running(store) as server:
        assert request(server, headers=headers)[0] == 403
        assert request(server, "/api/decision", dict(id=store.rows[0]["id"], choice=1, comment=""), headers=headers)[0] == 403
    assert not store.path.exists()


@pytest.mark.parametrize("header,status,expected", [(None,200,bytes(range(100))), ("bytes=10-19",206,bytes(range(10,20))),
    ("bytes=-5",206,bytes(range(95,100))), ("bytes=99-500",206,bytes([99])), ("bytes=100-",416,b""), ("bytes=2-1",416,b"")])
def test_audio_ranges(store, header, status, expected):
    with running(store) as server:
        path = "/audio?id="+quote(store.rows[0]["id"])
        code, _, body = request(server, path, headers={"Range": header} if header else {})
        assert code == status and body == expected
        assert request(server, path, method="HEAD")[2] == b""


@pytest.mark.parametrize("choice", [0, 4, -1, True, None, [], {}, "turbo-base"])
def test_invalid_choices(store, choice):
    with running(store) as server:
        assert request(server, "/api/decision", dict(id=store.rows[0]["id"], choice=choice, comment=""))[0] == 400
    assert not store.path.exists()


def test_bad_body_and_outside_session(store):
    with running(store) as server:
        assert request(server, "/api/decision", raw=b"not-json")[0] == 400
        assert request(server, "/api/decision", dict(id="../../secret", choice=1, comment=""))[0] == 400
        assert request(server, "/api/decision", dict(id=store.rows[0]["id"], choice=1, comment="x"*2001))[0] == 400
        assert request(server, "/api/decision", raw=b"{}", headers={"Content-Type":"text/plain"})[0] == 400


def test_concurrent_saves_preserved(store):
    errors = []
    def save(i):
        try:
            store.save(dict(id=store.rows[0]["id"], choice="all_bad", comment=str(i)))
        except Exception as exc:
            errors.append(type(exc).__name__)
    threads = [threading.Thread(target=save, args=(i,)) for i in range(12)]
    for thread in threads: thread.start()
    for thread in threads: thread.join(timeout=5)
    assert not errors and len(read_jsonl(store.path)) == 12
    assert all(not thread.is_alive() for thread in threads)


def test_file_symlink_escape(store, tmp_path):
    outside = tmp_path / "outside.wav"
    outside.write_bytes(b"private")
    clip = store.media(store.rows[0]["id"])
    clip.unlink()
    try:
        clip.symlink_to(outside)
    except OSError:
        pytest.skip("Windows symlink permission unavailable")
    with running(store) as server:
        assert request(server, "/audio?id="+quote(store.rows[0]["id"]))[0] == 400
