"""Relay contract tests with memory-only HTTP streams and fake backends."""

from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
import json
import logging
import re
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
    data = dict(title='함께 나눈 이야기', category=relay.categories[0], keywords=[], kind='story')
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


@pytest.mark.parametrize('newline', ['\n', '\r\n'])
def test_categories_come_only_from_prompt_category_section(tmp_path, newline):
    prompt = ('## 제목\n미끼, 목록\n## 분류\n설명입니다.\n\n새 분류, 다른 분류\n'
              '## 키워드\n사물, 활동\n## 종류\nstory, moment\n').replace('\n', newline)
    path = tmp_path / 'summary.md'
    path.write_text(prompt, encoding='utf-8', newline='')
    service = r.SummaryRelay(fake=True, prompt_path=path)
    assert service.system == prompt.replace('\r\n', '\n')
    assert r.prompt_categories(prompt) == service.categories == ('새 분류', '다른 분류')
    result = service.summarize({'transcript': '테스트'})[1]
    assert result['category'] == '새 분류'
    assert result['keywords'] == []
    assert result['tags'] == ['새 분류']


@pytest.mark.parametrize('prompt', ['', '## 분류\n목록 없음', '## 분류\n가, 나\n다, 라',
                                    '## 분류\n가, 가', '## 분류\n가, ', '## 태그\n가, 나'])
def test_bad_prompt_fails_closed(prompt):
    with pytest.raises(ValueError):
        r.prompt_categories(prompt)


def test_prompt_has_13_categories_and_three_valid_invented_examples(relay):
    assert relay.categories == ('추억', '아이의 하루', '학교', '놀이', '음식', '자연·풍경',
                                '여행 계획', '가족 이야기', '친구 이야기', '운전·길',
                                '노래·웃음', '동물', '계절')
    raw = r.PROMPT_PATH.read_bytes()
    assert not raw.startswith(b'\xef\xbb\xbf') and b'\r' not in raw
    examples = re.findall(r'→ `([^`]+)`', raw.decode('utf-8'))
    assert len(examples) == 3
    results = [r.validate_output(example, relay.categories) for example in examples]
    assert any(result['keywords'] == [] for result in results)
    assert any(result['category'] in ('친구 이야기', '운전·길') for result in results)


@pytest.mark.parametrize('title', ['가', '가' * 25])
@pytest.mark.parametrize('kind', ['story', 'moment'])
def test_valid_output_and_fences(relay, title, kind):
    expected = dict(title=title, category=relay.categories[0], keywords=['만두', 'RC카'], kind=kind)
    raw = json.dumps(expected, ensure_ascii=False)
    for wrapped in (raw, f'```json\n{raw}\n```', f'```\n{raw}\n```'):
        assert r.validate_output(wrapped, relay.categories) == expected


def test_every_prompt_category_is_valid(relay):
    for category in relay.categories:
        assert r.validate_output(answer(relay, category=category), relay.categories)['category'] == category


@pytest.mark.parametrize('keywords,expected', [
    ([], []), (['가'], ['가']), (['가나다라마바'], ['가나다라마바']),
    (['ABC123', '봄·비'], ['ABC123', '봄·비']),
    ([' 만두 ', '\tRC카\n'], ['만두', 'RC카']),
])
def test_valid_keywords_and_trim(relay, keywords, expected):
    assert r.validate_output(answer(relay, keywords=keywords), relay.categories)['keywords'] == expected


@pytest.mark.parametrize('keywords', [
    ['가나다라마바사'], ['abcdefg'], ['두 단어'], ['두\t단어'], ['두\n단어'], ['두\u00a0단어'],
    ['123'], [' 123 '], [''], ['   '], ['만두', '만두'], ['ABC', 'abc'], [' ABC ', 'abc'],
    ['만두', '신발', '우산'], ['고양이!'], ['a_b'], ['a-b'], ['a/b'], ['😀'], ['漢字'],
    ['café'], ['а'], ['\ud800'], [1], [None], [True], [[]], [{}], '만두', None, {},
])
def test_invalid_keywords(relay, keywords):
    with pytest.raises(ValueError, match='^invalid output$'):
        r.validate_output(answer(relay, keywords=keywords), relay.categories)


def test_keywords_cannot_equal_any_category_after_trim(relay):
    for category in relay.categories:
        with pytest.raises(ValueError, match='^invalid output$'):
            r.validate_output(answer(relay, keywords=[f' {category} ']), relay.categories)


@pytest.mark.parametrize('change', [
    {'title': ''}, {'title': '가' * 26}, {'title': '   '}, {'title': 1},
    {'title': None}, {'title': '두\n줄'}, {'title': '\ud800'},
    {'category': ''}, {'category': '없는 분류'}, {'category': ' 추억 '},
    {'category': ['추억']}, {'category': 1}, {'category': None},
    {'kind': 'none'}, {'kind': None}, {'kind': ['story']},
])
def test_invalid_schema(relay, change):
    with pytest.raises(ValueError, match='^invalid output$'):
        r.validate_output(answer(relay, **change), relay.categories)


def test_missing_fields_and_old_contract_rejected(relay):
    data = json.loads(answer(relay))
    missing = [json.dumps({k: v for k, v in data.items() if k != field}) for field in data]
    for raw in missing + ['{}', '{"title":"가","tags":["추억"],"kind":"story"}']:
        with pytest.raises(ValueError):
            r.validate_output(raw, relay.categories)


@pytest.mark.parametrize('raw', ['private invalid JSON', '[]', 'null', '1', '', None,
                                  '```json\n{}', 'explanation\n{}', '[' * 1200])
def test_invalid_json_sanitized(relay, raw):
    with pytest.raises(ValueError, match='^invalid output$'):
        r.validate_output(raw, relay.categories)


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
    assert set(result) == {'title', 'category', 'keywords', 'tags', 'kind', 'model', 'elapsed_ms'}
    assert result['category'] == relay.categories[0] and result['keywords'] == []
    assert result['tags'] == [result['category']]
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


@pytest.mark.parametrize('bad', ['private bad output', '{"category":"없는 분류"}',
                                 '{"title":"가","category":"추억","keywords":["123"],"kind":"story"}'])
def test_retry_once_then_success(relay, bad):
    backend, created = with_backend(relay, [bad, answer(relay)])
    assert http(relay)[0] == 200
    assert len(created) == 1 and len(backend.calls) == 2
    assert backend.calls[0] == backend.calls[1]


def test_invalid_output_stops_after_second_attempt(relay):
    backend, _ = with_backend(relay, ['bad', answer(relay, keywords=['가', '나', '다']), answer(relay)])
    assert http(relay)[:2] == (502, {'error': 'invalid_output'})
    assert len(backend.calls) == 2


@pytest.mark.parametrize('keywords', [[], ['만두'], ['만두', 'RC카']])
def test_response_tags_combine_category_and_trimmed_keywords(relay, keywords):
    with_backend(relay, [answer(relay, category='친구 이야기',
                                keywords=[f' {keyword} ' for keyword in keywords],
                                tags=['ignored legacy tags'])])
    status, result, _ = http(relay)
    assert status == 200
    assert result['category'] == '친구 이야기'
    assert result['keywords'] == keywords
    assert result['tags'] == ['친구 이야기'] + keywords


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
    r.validate_output(json.dumps(first), relay.categories)
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
    private_keyword = 'Secret'
    caplog.set_level(logging.INFO, logger=r.LOGGER.name)
    with_backend(relay, [answer(relay, title=private_title, keywords=[private_keyword]), r.BackendError(private_error),
                         r.UsageLimit(private_error), private_title, private_transcript])
    for expected in (200, 502, 429, 502):
        assert http(relay, data={'transcript': private_transcript})[0] == expected
    assert http(relay, path='/health?' + private_transcript)[0] == 404
    assert http(relay, path='/' + private_transcript)[0] == 404
    assert http(relay, method=private_transcript)[0] == 405
    assert http(relay, raw=private_transcript.encode())[0] == 400
    logs = caplog.text + ''.join(capsys.readouterr())
    for private in (private_transcript, private_title, private_keyword, private_error):
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


def test_story_split_fake_route(relay):
    status, data, head = http(relay, path='/story-split', data={'segments': ['지어낸 첫 대화', '지어낸 다음 대화']})
    assert status == 200 and 'Connection: close' in head
    assert data['stories'][0]['segments'] == [1, 2]
    assert data['stories'][0]['tags'] == [relay.categories[0]]
    assert data['model'] == r.DEFAULT_MODEL and data['elapsed_ms'] >= 0
    assert http(relay)[0] == 200


@pytest.mark.parametrize('data', [None, [], {}, {'segments': []}, {'segments': ['']},
    {'segments': [' \n']}, {'segments': [1]}, {'segments': ['\ud800']},
    {'segments': ['invented'] * 13}, {'segments': ['invented'], 'model': '--bad'}])
def test_story_split_invalid_request_never_calls_backend(relay, data):
    backend, _ = with_backend(relay, [])
    assert http(relay, path='/story-split', raw=json.dumps(data).encode())[:2] == (
        400, {'error': 'invalid_request'})
    assert not backend.calls


def test_story_split_backend_retry_validation_and_private_logs(relay, caplog):
    caplog.set_level(logging.INFO, logger=r.LOGGER.name)
    group = dict(segments=[1, 2], title='PRIVATE_TITLE', category=relay.categories[0], keywords=[], kind='story')
    backend, created = with_backend(relay, ['{"stories":[]}', json.dumps(dict(stories=[group]))])
    payload = dict(segments=['PRIVATE_TRANSCRIPT_1', 'PRIVATE_TRANSCRIPT_2'], model='gemini-3.8-flash-high')
    status, data, _ = http(relay, path='/story-split', data=payload)
    assert status == 200 and len(backend.calls) == 2
    assert created == [dict(model='gemini-3.8-flash', effort='high')]
    assert backend.calls[0][0] == r.STORY_PROMPT_PATH.read_text(encoding='utf-8')
    assert backend.calls[0][1] == '[1]\nPRIVATE_TRANSCRIPT_1\n\n[2]\nPRIVATE_TRANSCRIPT_2'
    assert data['stories'][0]['title'] == 'PRIVATE_TITLE'
    assert 'PRIVATE' not in caplog.text and 'path=/story-split status=200' in caplog.text
    with_backend(relay, ['{}', '{}'])
    assert http(relay, path='/story-split', data=payload)[:2] == (502, {'error': 'invalid_output'})
    with_backend(relay, [r.BackendError('PRIVATE_ERROR')])
    assert http(relay, path='/story-split', data=payload)[:2] == (502, {'error': 'backend_error'})
    assert 'PRIVATE' not in caplog.text
