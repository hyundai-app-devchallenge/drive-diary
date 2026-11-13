"""Workers AI tests use a fake urllib opener; no network or real credentials."""

from io import BytesIO
import json
import socket
import traceback
from urllib.error import HTTPError, URLError

import pytest

from tools import cloudflare_ai as cf
from tools import cloudflare_probe as probe
from tools import summary_relay as relay

TOKEN = 'INVENTED_TOKEN_SENTINEL'
ACCOUNT = 'invented-account'


@pytest.fixture(autouse=True)
def isolated(monkeypatch):
    monkeypatch.setenv('CLOUDFLARE_ACCOUNT_ID', ACCOUNT)
    monkeypatch.setenv('CLOUDFLARE_API_TOKEN', TOKEN)
    monkeypatch.setattr(cf, '_NO_JSON_MODE', set())
    def forbidden(*args, **kwargs):
        pytest.fail('Cloudflare tests must never open a network socket')
    monkeypatch.setattr(socket, 'socket', forbidden)


class Response(BytesIO):
    def __init__(self, data, status=200):
        super().__init__(json.dumps(data).encode() if not isinstance(data, bytes) else data)
        self.status = status


class Opener:
    def __init__(self, *responses):
        self.responses = iter(responses)
        self.calls = []

    def open(self, request, *, timeout):
        self.calls.append((request, timeout))
        response = next(self.responses)
        if isinstance(response, Exception):
            raise response
        return response


def envelope(content='{"value": 1}', **changes):
    result = dict(choices=[dict(message=dict(content=content), finish_reason='stop')],
                  usage=dict(completion_tokens_details=dict(reasoning_tokens=17)))
    result.update(changes)
    return result


def http_error(status, message):
    return HTTPError('https://api.cloudflare.com/' + TOKEN, status, TOKEN,
                     {}, BytesIO(json.dumps(dict(success=False, errors=[dict(message=message)])).encode()))


def test_success_options_metadata_and_credentials_only_in_header():
    schema = dict(name='test', schema=dict(type='object'))
    opener = Opener(Response(envelope()))
    backend = cf.CloudflareBackend(opener=opener, json_schema=schema,
                                   chat_template_kwargs={'enable_thinking': False})
    result = backend.complete('invented system', 'invented user')
    assert result.content == '{"value": 1}'
    assert result.http_status == 200 and result.response_format_accepted is True
    assert result.finish_reason == 'stop' and result.reasoning_tokens == 17 and result.latency_ms >= 0
    request, timeout = opener.calls[0]
    assert request.full_url == f'https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/ai/v1/chat/completions'
    assert request.get_header('Authorization') == 'Bearer ' + TOKEN
    assert request.get_method() == 'POST' and timeout == 120
    data = json.loads(request.data)
    assert data == dict(model=cf.DEFAULT_MODEL, messages=[dict(role='system', content='invented system'),
                                                        dict(role='user', content='invented user')],
                        max_completion_tokens=1024, temperature=.2,
                        chat_template_kwargs={'enable_thinking': False},
                        response_format=dict(type='json_schema', json_schema=schema))
    assert TOKEN not in repr(backend) + repr(result) + request.data.decode()


def test_custom_options_and_optional_usage():
    opener = Opener(Response(envelope(usage={})))
    result = cf.CloudflareBackend(model='@cf/test/model', max_completion_tokens=33,
                                  temperature=0, timeout=7, opener=opener).complete('s', 'u')
    request, timeout = opener.calls[0]
    assert timeout == 7 and json.loads(request.data)['max_completion_tokens'] == 33
    assert json.loads(request.data)['temperature'] == 0
    assert 'response_format' not in json.loads(request.data)
    assert 'chat_template_kwargs' not in json.loads(request.data)
    assert result.reasoning_tokens is None and result.response_format_accepted is None


@pytest.mark.parametrize('text', ['{"value": {"nested": "}\\\"{"}}',
    '```json\n{"value": 1}\n```',
    '<think>{"wrong": true}</think>\n{"value": 1}',
    '<analysis>reasoning {"wrong": 1}</analysis>Explanation: {broken} {"value": 1} suffix',
    'Leading explanation\n```JSON\n{"value": 1}\n```'])
def test_json_extraction(text):
    assert 'value' in json.loads(cf.extract_json(text))


@pytest.mark.parametrize('text', ['not JSON ' + TOKEN, '```json\n[1]\n```', None])
def test_extraction_errors_are_sanitized(text):
    with pytest.raises(ValueError) as caught:
        cf.extract_json(text)
    assert str(caught.value) == 'invalid output' and TOKEN not in repr(caught.value)


@pytest.mark.parametrize('message', ["JSON Mode couldn't be met", 'response_format unsupported'])
def test_json_mode_fallback_once_and_process_memory_across_instances(message):
    opener = Opener(http_error(400, message), Response(envelope()), Response(envelope()))
    first = cf.CloudflareBackend(opener=opener, json_schema={}).complete('s', 'u')
    other = cf.CloudflareBackend(opener=opener, json_schema={}).complete('s', 'u')
    assert [bool('response_format' in json.loads(r.data)) for r, _ in opener.calls] == [True, False, False]
    assert first.response_format_accepted is other.response_format_accepted is False
    # A different model still gets its first schema attempt.
    separate = Opener(Response(envelope()))
    assert cf.CloudflareBackend(model='@cf/test/other', json_schema={}, opener=separate).complete(
        's', 'u').response_format_accepted is True


def test_fallback_is_not_repeated_when_plain_request_also_fails():
    opener = Opener(http_error(422, 'JSON mode unsupported'), http_error(400, 'response_format bad'))
    with pytest.raises(cf.CloudflareError) as caught:
        cf.CloudflareBackend(opener=opener, json_schema={}).complete('s', 'u')
    assert len(opener.calls) == 2 and caught.value.response_format_accepted is False


@pytest.mark.parametrize('status,code,retryable', [(401, 'authentication_error', False),
    (403, 'authentication_error', False), (429, 'usage_limit', True), (500, 'backend_error', True),
    (503, 'backend_error', True), (400, 'backend_error', False)])
def test_http_errors_sanitized_and_do_not_retry(status, code, retryable, caplog, capsys):
    opener = Opener(http_error(status, TOKEN + (' JSON Mode' if status != 400 else '')))
    backend = cf.CloudflareBackend(opener=opener, json_schema={})
    system, user = 'PRIVATE_SYSTEM', 'PRIVATE_USER'
    with pytest.raises(cf.CloudflareError) as caught:
        backend.complete(system, user)
    error = caught.value
    assert error.code == code and error.retryable is retryable
    assert error.http_status == (401 if status == 403 else status) and error.latency_ms >= 0
    assert len(opener.calls) == 1
    output = ''.join(traceback.format_exception(error)) + repr(error) + repr(backend) + caplog.text
    output += ''.join(capsys.readouterr())
    assert TOKEN not in output and 'PRIVATE_' not in output


@pytest.mark.parametrize('message,status', [('Authentication failed', 401), ('rate limit exceeded', 429),
    ('internal server error 500', 502), ('bad request', 400)])
def test_wrapped_errors(message, status):
    opener = Opener(Response(dict(success=False, errors=[dict(message=message)])))
    with pytest.raises(cf.CloudflareError) as caught:
        cf.CloudflareBackend(opener=opener).complete('s', 'u')
    assert caught.value.http_status == status


def test_wrapped_json_mode_error_falls_back():
    opener = Opener(Response(dict(success=False, errors=[dict(message='JSON Mode could not be met')])),
                    Response(envelope()))
    result = cf.CloudflareBackend(opener=opener, json_schema={}).complete('s', 'u')
    assert result.response_format_accepted is False and len(opener.calls) == 2


@pytest.mark.parametrize('variable', ['CLOUDFLARE_ACCOUNT_ID', 'CLOUDFLARE_API_TOKEN'])
def test_missing_env_never_opens_connection(monkeypatch, variable):
    monkeypatch.delenv(variable)
    opener = Opener()
    with pytest.raises(cf.CloudflareError) as caught:
        cf.CloudflareBackend(opener=opener).complete('s', 'u')
    assert caught.value.code == 'cloudflare_not_configured' and caught.value.http_status == 503
    assert not opener.calls


@pytest.mark.parametrize('failure', [URLError(TOKEN), TimeoutError(TOKEN), RuntimeError(TOKEN)])
def test_network_exceptions_never_expose_credentials(failure, caplog):
    with pytest.raises(cf.CloudflareError) as caught:
        cf.CloudflareBackend(opener=Opener(failure)).complete('s', 'u')
    assert caught.value.retryable and caught.value.code == 'backend_error'
    assert TOKEN not in ''.join(traceback.format_exception(caught.value)) + caplog.text


@pytest.mark.parametrize('data', [b'not JSON', {}, dict(choices=[]), envelope(content=None),
                                 envelope(usage={'completion_tokens_details': 'bad'}),
                                 envelope(usage={'completion_tokens_details': {'reasoning_tokens': True}})])
def test_malformed_envelope_is_safe(data):
    with pytest.raises(cf.CloudflareError, match='backend_error'):
        cf.CloudflareBackend(opener=Opener(Response(data))).complete('s', 'u')


def test_response_size_limit(monkeypatch):
    monkeypatch.setattr(cf, 'MAX_RESPONSE_BYTES', 10)
    with pytest.raises(cf.CloudflareError, match='backend_error'):
        cf.CloudflareBackend(opener=Opener(Response(b'x' * 11))).complete('s', 'u')


def test_redirects_are_not_followed():
    assert cf._NoRedirect().redirect_request(None, None, 302, TOKEN, {}, 'https://example.org') is None
    with pytest.raises(cf.CloudflareError):
        cf.CloudflareBackend(opener=Opener(http_error(302, TOKEN))).complete('s', 'u')


@pytest.mark.parametrize('variant,kwargs', [('default', None), ('no-thinking', {'enable_thinking': False})])
def test_probe_default_output_and_variants(monkeypatch, capsys, variant, kwargs):
    categories = relay.prompt_categories(relay.STORY_PROMPT_PATH.read_text(encoding='utf-8'))
    group = dict(segments=[1], title='구름과 종이배', category=categories[0], keywords=[], kind='story',
                 keep=True, reason='')
    content = '<think>invented reasoning</think>\n' + json.dumps(dict(stories=[group]))
    opener = Opener(Response(envelope(content)))
    created = []
    def backend(**options):
        created.append(options)
        return cf.CloudflareBackend(**options, opener=opener)
    monkeypatch.setattr(probe, 'CloudflareBackend', backend)
    assert probe.main(['--model', cf.DEFAULT_MODEL, '--variant', variant]) == 0
    output = capsys.readouterr().out
    report = json.loads(output)
    assert report['validation'] == 'pass' and report['error_code'] is None
    assert report['has_think_or_prefix'] is True and report['reasoning_tokens'] == 17
    assert created[0]['chat_template_kwargs'] == kwargs
    assert set(report) == {'http_status', 'response_format_accepted', 'finish_reason', 'reasoning_tokens',
                           'latency_ms', 'has_think_or_prefix', 'content_length', 'validation', 'error_code'}
    assert content not in output and TOKEN not in output and 'invented reasoning' not in output
    assert json.loads(opener.calls[0][0].data)['messages'][1]['content'] == probe.INVENTED_DIALOGUE


def test_probe_show_and_validation_failure(monkeypatch, capsys):
    opener = Opener(Response(envelope('invented invalid content')))
    monkeypatch.setattr(probe, 'CloudflareBackend', lambda **kw: cf.CloudflareBackend(**kw, opener=opener))
    assert probe.main(['--show']) == 2
    lines = capsys.readouterr().out.splitlines()
    assert json.loads(lines[0])['error_code'] == 'invalid_output'
    assert lines[1] == 'invented invalid content'


def test_probe_safe_error_and_finish_reason(monkeypatch, capsys):
    monkeypatch.delenv('CLOUDFLARE_API_TOKEN')
    assert probe.main([]) == 2
    result = json.loads(capsys.readouterr().out)
    assert result['error_code'] == 'cloudflare_not_configured' and result['latency_ms'] >= 0
    data = envelope()
    data['choices'][0]['finish_reason'] = TOKEN
    result = cf.CloudflareBackend(opener=Opener(Response(data)))
    monkeypatch.setenv('CLOUDFLARE_API_TOKEN', TOKEN)
    assert result.complete('s', 'u').finish_reason == 'unknown'


def test_cloudflare_stitch_eval_preserves_model_and_safe_filename(tmp_path, monkeypatch):
    from eval.stitch import core, split, value
    blocks = [dict(id='invented#0', source='invented', start=0, end=90, closing='length',
                   text='지어낸 종이배 대화 ' * 10, chars=80, probability=.99, model_kind='story',
                   human=dict(keep='keep', kind='story', policy=False))]
    directory = tmp_path / '.local/eval/stitch'
    directory.mkdir(parents=True)
    records = core.snapshot([blocks])
    (directory / 'runs.jsonl').write_text(json.dumps(records[0]), encoding='utf-8')
    monkeypatch.setattr(split, 'load_dev', lambda root: (blocks, {}))
    fake = relay.SummaryRelay(fake=True)
    def send(address, payload):
        assert payload['model'] == cf.DEFAULT_MODEL
        return fake.story_split(payload)[1]
    report = split.execute(root=tmp_path, relay='http://127.0.0.1:8771', policy='cont0.5_min40',
                           model=cf.DEFAULT_MODEL, send=send)
    assert report['ok'] == 1 and report['failed'] == 0
    path = directory / 'split-cont0.5_min40-cf-google--gemma-4-26b-a4b-it.jsonl'
    assert json.loads(path.read_text(encoding='utf-8'))['model'] == cf.DEFAULT_MODEL
    # Same evaluator used for agy can read this flat filename and its rows.
    assert isinstance(value.execute(path, root=tmp_path), dict)
