"""Relay contract tests with memory-only HTTP streams and fake backends."""

from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
import json
import logging
import socket
import threading
from types import SimpleNamespace

import pytest

from tools import summary_relay as r


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail('relay tests must not open a network socket')
    monkeypatch.setattr(socket, 'socket', forbidden)
    monkeypatch.setattr(r.AgyBackend, 'complete', forbidden)


class FakeBackend:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.calls = []

    def complete(self, system, transcript):
        self.calls.append((system, transcript))
        answer = next(self.responses)
        if isinstance(answer, Exception):
            raise answer
        return answer


@pytest.fixture
def relay():
    return r.SummaryRelay(fake=True)


def answer(relay, **changes):
    data = dict(title='가족과 나눈 이야기', tags=[relay.tags[0]], kind='story')
    data.update(changes)
    return json.dumps(data, ensure_ascii=False)


def with_backend(relay, responses):
    backend = FakeBackend(responses)
    created = []
    def factory(**kwargs):
        created.append(kwargs)
        return backend
    relay.fake = False
    relay.backend_factory = factory
    return backend, created


class MemoryConnection:
    def __init__(self, raw):
        self.input = BytesIO(raw)
        self.output = bytearray()

    def makefile(self, *args):
        return self.input

    def sendall(self, raw):
        self.output.extend(raw)

    def settimeout(self, timeout):
        pass


def http(relay, *, method='POST', path='/summarize', data=None, raw=None, headers=None):
    body = raw if raw is not None else json.dumps(
        data if data is not None else {'transcript': '지어낸 테스트 전사'},
        ensure_ascii=False).encode('utf-8')
    fields = {'Host': '10.0.2.2:8771', 'Content-Type': 'application/json',
              'Content-Length': str(len(body))}
    fields.update(headers or {})
    lines = [f'{method} {path} HTTP/1.1']
    lines += [f'{key}: {value}' for key, value in fields.items() if value is not None]
    connection = MemoryConnection(('\r\n'.join(lines) + '\r\n\r\n').encode() + body)
    r.SummaryHandler(connection, ('127.0.0.1', 12345), SimpleNamespace(relay=relay))
    head, raw_body = bytes(connection.output).split(b'\r\n\r\n', 1)
    status = int(head.split(b' ', 2)[1])
    return status, json.loads(raw_body), head.decode()


def test_tags_come_only_from_prompt_tag_section(tmp_path):
    prompt = '## 제목\n미끼, 목록\n## 태그\n설명입니다.\n\n새 태그, 다른 태그\n## 종류\nstory, moment\n'
    path = tmp_path / 'summary.md'
    path.write_text(prompt, encoding='utf-8')
    service = r.SummaryRelay(fake=True, prompt_path=path)
    assert service.system == prompt
    assert service.tags == ('새 태그', '다른 태그')
    assert service.summarize({'transcript': '테스트'})[1]['tags'] == ['새 태그']


@pytest.mark.parametrize('prompt', ['', '## 태그\n목록 없음', '## 태그\n가, 나\n다, 라',
                                    '## 태그\n가, 가', '## 태그\n가, '])
def test_bad_prompt_fails_closed(prompt):
    with pytest.raises(ValueError):
        r.prompt_tags(prompt)


@pytest.mark.parametrize('title', ['가', '가' * 25])
@pytest.mark.parametrize('kind', ['story', 'moment'])
def test_valid_output_and_fences(relay, title, kind):
    expected = dict(title=title, tags=list(relay.tags[:3]), kind=kind)
    raw = json.dumps(expected, ensure_ascii=False)
    for wrapped in (raw, f'```json\n{raw}\n```', f'```\n{raw}\n```'):
        assert r.validate_output(wrapped, relay.tags) == expected


@pytest.mark.parametrize('change', [
    {'title': ''}, {'title': '가' * 26}, {'title': '   '}, {'title': 1},
    {'title': None}, {'title': '두\n줄'}, {'title': '\ud800'},
    {'tags': []}, {'tags': ['없는 태그']}, {'tags': [1]}, {'tags': [None]},
    {'tags': [[1]]}, {'tags': '추억'}, {'tags': None},
    {'kind': 'none'}, {'kind': None}, {'kind': ['story']},
])
def test_invalid_schema(relay, change):
    with pytest.raises(ValueError, match='^invalid output$'):
        r.validate_output(answer(relay, **change), relay.tags)


def test_too_many_tags_and_missing_fields(relay):
    for raw in (answer(relay, tags=list(relay.tags[:4])), '{}', '{"title":"가"}'):
        with pytest.raises(ValueError):
            r.validate_output(raw, relay.tags)


@pytest.mark.parametrize('raw', ['private invalid JSON', '[]', 'null', '1', '', None,
                                  '```json\n{}', 'explanation\n{}', '[' * 1200])
def test_invalid_json_sanitized(relay, raw):
    with pytest.raises(ValueError, match='^invalid output$'):
        r.validate_output(raw, relay.tags)


@pytest.mark.parametrize('model,base,effort', [
    (None, 'gemini-3.8-flash', 'medium'),
    ('gemini-3.8-flash-low', 'gemini-3.8-flash', 'low'),
    ('gemini-3.8-flash-high', 'gemini-3.8-flash', 'high'),
    ('gemini-3.8-flash-medium', 'gemini-3.8-flash', 'medium'),
    ('custom-model', 'custom-model', 'medium'),
])
def test_success_system_transcript_and_model(relay, model, base, effort):
    backend, created = with_backend(relay, [answer(relay)])
    payload = {'transcript': '그대로 전달할 지어낸 전사\n두 번째 줄'}
    if model is not None:
        payload['model'] = model
    status, result, headers = http(relay, data=payload)
    assert status == 200
    assert set(result) == {'title', 'tags', 'kind', 'model', 'elapsed_ms'}
    assert result['model'] == f'{base}-{effort}'
    assert type(result['elapsed_ms']) is int and result['elapsed_ms'] >= 0
    assert backend.calls == [(r.PROMPT_PATH.read_text(encoding='utf-8'), payload['transcript'])]
    assert created == [dict(model=base, effort=effort)]
    assert 'application/json; charset=utf-8' in headers
    assert 'Cache-Control: no-store' in headers


def test_default_backend_is_agy(relay, monkeypatch):
    backend = FakeBackend([answer(relay)])
    created = []
    def factory(**kwargs):
        created.append(kwargs)
        return backend
    monkeypatch.setattr(r, 'AgyBackend', factory)
    assert http(r.SummaryRelay())[0] == 200
    assert created == [dict(model='gemini-3.8-flash', effort='medium')]


def test_retry_once_then_success(relay):
    backend, created = with_backend(relay, ['private bad output', answer(relay)])
    assert http(relay)[0] == 200
    assert len(created) == 1 and len(backend.calls) == 2
    assert backend.calls[0] == backend.calls[1]


def test_invalid_output_stops_after_second_attempt(relay):
    backend, _ = with_backend(relay, ['bad', answer(relay, tags=[]), answer(relay)])
    assert http(relay)[:2] == (502, {'error': 'invalid_output'})
    assert len(backend.calls) == 2


@pytest.mark.parametrize('error,status,code', [
    (r.UsageLimit('private limit details'), 429, 'usage_limit'),
    (r.BackendError('private backend details'), 502, 'backend_error'),
    (RuntimeError('private unexpected details'), 502, 'backend_error'),
])
@pytest.mark.parametrize('retry', [False, True])
def test_backend_error_mapping_without_retry(relay, error, status, code, retry):
    backend, _ = with_backend(relay, (['bad'] if retry else []) + [error, answer(relay)])
    assert http(relay)[:2] == (status, {'error': code})
    assert len(backend.calls) == (2 if retry else 1)


def test_backend_construction_error_is_sanitized(relay):
    relay.fake = False
    def fail(**kwargs):
        raise r.BackendError('private initialization details')
    relay.backend_factory = fail
    assert http(relay)[:2] == (502, {'error': 'backend_error'})


@pytest.mark.parametrize('data', [[], None, {}, {'transcript': ''}, {'transcript': ' \n'},
    {'transcript': 1}, {'transcript': None}, {'transcript': ['text']},
    {'transcript': '\ud800'}, {'transcript': 'text', 'model': ''},
    {'transcript': 'text', 'model': None}, {'transcript': 'text', 'model': 1},
    {'transcript': 'text', 'model': '--flag'}, {'transcript': 'text', 'model': 'has spaces'},
    {'transcript': 'text', 'model': 'a' * 129}])
def test_invalid_requests_never_call_backend(relay, data):
    backend, created = with_backend(relay, [])
    # ensure_ascii=True intentionally permits an escaped surrogate fixture.
    assert http(relay, raw=json.dumps(data).encode())[:2] == (400, {'error': 'invalid_request'})
    assert backend.calls == created == []


@pytest.mark.parametrize('raw', [b'', b'{private bad json', b'\xff', b'[' * 1200])
def test_bad_http_json(relay, raw):
    assert http(relay, raw=raw)[:2] == (400, {'error': 'invalid_request'})


@pytest.mark.parametrize('headers,status,code', [
    ({'Content-Length': None}, 400, 'invalid_request'),
    ({'Content-Length': '-1'}, 400, 'invalid_request'),
    ({'Content-Length': 'wrong'}, 400, 'invalid_request'),
    ({'Content-Length': '1\r\nContent-Length: 2'}, 400, 'invalid_request'),
    ({'Transfer-Encoding': 'chunked'}, 400, 'invalid_request'),
    ({'Content-Length': str(r.MAX_REQUEST_BYTES + 1)}, 413, 'request_too_large'),
    ({'Content-Type': 'text/plain'}, 415, 'unsupported_media_type'),
    ({'Content-Type': None}, 415, 'unsupported_media_type'),
    ({'Content-Length': '1000'}, 400, 'invalid_request'),
])
def test_http_limits_and_headers(relay, headers, status, code):
    backend, _ = with_backend(relay, [])
    assert http(relay, raw=b'{}', headers=headers)[:2] == (status, {'error': code})
    assert not backend.calls


def test_request_size_boundary(relay):
    body = json.dumps({'transcript': 'x'}).encode()
    body += b' ' * (r.MAX_REQUEST_BYTES - len(body))
    assert http(relay, raw=body)[0] == 200
    assert http(relay, raw=body + b' ')[:2] == (413, {'error': 'request_too_large'})


def test_body_read_timeout(relay, monkeypatch):
    class SlowBody(BytesIO):
        def read(self, size=-1):
            raise TimeoutError('PRIVATE_TIMEOUT_DETAILS')
    monkeypatch.setattr(MemoryConnection, 'makefile',
                        lambda self, *args: SlowBody(self.input.getvalue()))
    backend, _ = with_backend(relay, [])
    assert http(relay)[:2] == (408, {'error': 'request_timeout'})
    assert not backend.calls


def test_health_fake_and_routes(relay):
    backend, _ = with_backend(relay, [])
    assert http(relay, method='GET', path='/health')[:2] == (200, dict(ok=True, model=r.DEFAULT_MODEL))
    assert not backend.calls
    relay.fake = True
    first = http(relay, data={'transcript': '첫 번째 가상 대화'})[1]
    second = http(relay, data={'transcript': '두 번째 가상 대화'})[1]
    assert {k: v for k, v in first.items() if k != 'elapsed_ms'} == {
        k: v for k, v in second.items() if k != 'elapsed_ms'}
    r.validate_output(json.dumps(first), relay.tags)
    assert not backend.calls
    for method, path in [('GET', '/summarize'), ('POST', '/health'), ('GET', '/health?x=1')]:
        assert http(relay, method=method, path=path)[:2] == (404, {'error': 'not_found'})
    assert http(relay, method='PUT')[:2] == (405, {'error': 'method_not_allowed'})


@pytest.mark.parametrize('port', [None, 9001])
def test_server_binds_only_loopback_and_default_port(relay, monkeypatch, port):
    captured = []
    monkeypatch.setattr(r.ThreadingHTTPServer, '__init__',
                        lambda self, address, handler: captured.append((address, handler)))
    server = r.SummaryServer(relay=relay) if port is None else r.SummaryServer(port, relay=relay)
    assert captured == [(('127.0.0.1', 8771 if port is None else port), r.SummaryHandler)]
    assert server.relay is relay
    args = r.parser().parse_args([])
    assert args.port == 8771 and not args.fake


def test_cli_fake_and_port(monkeypatch):
    captured = []
    class Server:
        def __init__(self, port, *, relay):
            captured.append((port, relay.fake))
        def __enter__(self):
            return self
        def __exit__(self, *args):
            pass
        def serve_forever(self):
            raise KeyboardInterrupt
    monkeypatch.setattr(r, 'SummaryServer', Server)
    assert r.main(['--fake', '--port', '9001']) == 0
    assert captured == [(9001, True)]
    for port in ('0', '-1', '65536'):
        with pytest.raises(SystemExit):
            r.main(['--port', port])


def test_one_backend_call_at_a_time_including_retry(relay):
    entered, release, second_started = threading.Event(), threading.Event(), threading.Event()
    calls = []
    def complete(system, transcript):
        calls.append(transcript)
        if len(calls) == 1:
            entered.set()
            assert release.wait(5)
            return 'invalid first output'
        return answer(relay)
    relay.fake = False
    relay.backend_factory = lambda **kwargs: SimpleNamespace(complete=complete)
    def second():
        second_started.set()
        return relay.summarize({'transcript': 'second'})
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(relay.summarize, {'transcript': 'first'})
        try:
            assert entered.wait(5)
            other = pool.submit(second)
            assert second_started.wait(5)
            assert calls == ['first']
            assert not relay.lock.acquire(blocking=False)
        finally:
            release.set()
        assert first.result(timeout=5)[0] == other.result(timeout=5)[0] == 200
    assert calls == ['first', 'first', 'second']


def test_logs_never_contain_transcript_title_or_backend_details(relay, caplog, capsys):
    private_transcript = 'PRIVATE_TRANSCRIPT_SENTINEL'
    private_title = 'PRIVATE_TITLE_SENTINEL'
    private_error = 'PRIVATE_ERROR_SENTINEL'
    caplog.set_level(logging.INFO, logger=r.LOGGER.name)
    with_backend(relay, [answer(relay, title=private_title), r.BackendError(private_error),
                         r.UsageLimit(private_error), private_title, private_transcript])
    for expected in (200, 502, 429, 502):
        assert http(relay, data={'transcript': private_transcript})[0] == expected
    assert http(relay, path='/health?' + private_transcript)[0] == 404
    assert http(relay, path='/' + private_transcript)[0] == 404
    assert http(relay, method=private_transcript)[0] == 405
    assert http(relay, raw=private_transcript.encode())[0] == 400
    logs = caplog.text + ''.join(capsys.readouterr())
    for private in (private_transcript, private_title, private_error):
        assert private not in logs
    assert 'method=POST path=/summarize status=200' in logs
    for field in ('request_bytes=', 'transcript_chars=', 'response_bytes=', 'elapsed_ms='):
        assert field in logs
    assert len(caplog.records) == 8


def test_server_suppresses_tracebacks(relay, monkeypatch, capsys):
    monkeypatch.setattr(r.ThreadingHTTPServer, '__init__', lambda *args: None)
    server = r.SummaryServer(relay=relay)
    try:
        raise RuntimeError('PRIVATE_TRANSCRIPT')
    except RuntimeError:
        server.handle_error(None, ('127.0.0.1', 1))
    assert capsys.readouterr() == ('', '')
