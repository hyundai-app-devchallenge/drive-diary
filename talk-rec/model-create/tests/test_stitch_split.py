"""Invented input, fake relay and memory-only transports; no agy/network."""
import http.client
import json

import pytest

from eval.stitch import core as c, split as s
from tools import summary_relay as r


def story(segments, **changes):
    result = dict(segments=segments, title='종이배 이야기', category='놀이', keywords=['종이배'], kind='story',
                  keep=True, reason='')
    result.update(changes)
    return result


def validate(groups, count=3):
    return r.validate_split_output(json.dumps(dict(stories=groups)), r.prompt_categories(
        r.STORY_PROMPT_PATH.read_text(encoding='utf-8')), count)


def test_valid_partition_and_none_empty_metadata():
    groups = [story([1, 2]), story([3], title='', category='', keywords=[], kind='none',
                                keep=False, reason='no_content')]
    assert validate(groups) == dict(stories=groups)
    assert validate([story([1, 2, 3], kind='none', keep=False, reason='no_content')])['stories'][0]['kind'] == 'none'


@pytest.mark.parametrize('reason', ['not_in_car', 'not_family', 'negative', 'procedure', 'no_content'])
def test_hidden_real_talk_retains_metadata(reason):
    group = story([1, 2, 3], keep=False, reason=reason)
    assert validate([group])['stories'] == [group]


@pytest.mark.parametrize('changes', [dict(keep=1), dict(keep=0), dict(keep='false'), dict(keep=None),
    dict(reason='procedure'), dict(keep=False), dict(keep=False, reason='bad'), dict(reason=None),
    dict(reason=[]), dict(reason={}), dict(kind='none'),
    dict(kind='none', keep=False, reason='procedure')])
def test_invalid_keep_reason_contract(changes):
    with pytest.raises(ValueError, match='invalid output'):
        validate([story([1, 2, 3], **changes)])


@pytest.mark.parametrize('field', ['keep', 'reason'])
def test_new_split_requires_value_fields(field):
    group = story([1, 2, 3])
    del group[field]
    with pytest.raises(ValueError, match='invalid output'):
        validate([group])


@pytest.mark.parametrize('segments', [[], [1, 3], [1, 1, 2, 3], [2, 1, 3], [0, 1, 2, 3],
                                    [1, 2, 3, 4], [True, 2, 3], [1., 2, 3], ['1', 2, 3]])
def test_invalid_coverage_and_order(segments):
    with pytest.raises(ValueError, match='invalid output'):
        validate([story(segments)])


@pytest.mark.parametrize('changes', [dict(title=''), dict(title='가' * 26), dict(category='없는 분류'),
    dict(keywords=['12']), dict(keywords=['놀이']), dict(keywords=['AB', 'ab']),
    dict(keywords=['두 단어']), dict(keywords=['가나다라마바사']), dict(kind='bad'),
    dict(extra='PRIVATE_INVENTED')])
def test_split_shares_summary_validation(changes):
    with pytest.raises(ValueError, match='invalid output'):
        validate([story([1, 2, 3], **changes)])


def test_strict_top_level_and_prompt_invented_examples():
    categories = r.prompt_categories(r.STORY_PROMPT_PATH.read_text(encoding='utf-8'))
    assert categories == r.prompt_categories(r.PROMPT_PATH.read_text(encoding='utf-8'))
    for raw in ['{}', '[]', 'null', '{"stories":[]}', '{"stories":[],"extra":1}']:
        with pytest.raises(ValueError):
            r.validate_split_output(raw, categories, 1)
    import re
    examples = re.findall(r'→ `([^`]+)`', r.STORY_PROMPT_PATH.read_text(encoding='utf-8'))
    assert len(examples) == 2
    for raw, count in zip(examples, [3, 2]):
        r.validate_split_output(raw, categories, count)


@pytest.mark.parametrize('url', ['https://127.0.0.1:8771', 'http://example.com', 'http://127.0.0.2',
    'http://user@127.0.0.1', 'http://127.0.0.1/path', 'http://127.0.0.1?x=1',
    'http://127.0.0.1#x', 'http://127.0.0.1:0', 'http://127.0.0.1:99999'])
def test_only_literal_loopback_http(url):
    with pytest.raises(ValueError):
        s.relay_address(url)


def test_connection_reset_retry_limit_and_no_http_error_retry():
    calls = []
    def flaky(address, payload):
        calls.append(1)
        if len(calls) < 3:
            raise ConnectionResetError()
        return {'ok': True}
    assert s.request_with_retry(('127.0.0.1', 8771), {}, send=flaky)[:2] == ({'ok': True}, 3)
    for error, attempts in [(ConnectionResetError(), 3), (http.client.RemoteDisconnected(), 3),
                            (TimeoutError(), 1), (ValueError('PRIVATE'), 1)]:
        calls.clear()
        def fail(*args):
            calls.append(1)
            raise error
        with pytest.raises((ConnectionError, ValueError)):
            s.request_with_retry(('127.0.0.1', 8771), {}, send=fail)
        assert len(calls) == attempts


def test_request_transport_loopback_path_headers_and_close(monkeypatch):
    calls = []
    class Connection:
        def __init__(self, *args, **kwargs):
            calls.append((args, kwargs))
        def request(self, method, path, raw, headers):
            calls.append((method, path, json.loads(raw), headers))
        def getresponse(self):
            class Response:
                status = 200
                def read(self, limit):
                    return b'{"ok": true}'
            return Response()
        def close(self):
            calls.append('closed')
    monkeypatch.setattr(s.http.client, 'HTTPConnection', Connection)
    assert s.request_once(('127.0.0.1', 8771), dict(segments=['invented'])) == {'ok': True}
    assert calls[0][0] == ('127.0.0.1', 8771)
    assert calls[1][0:2] == ('POST', '/story-split') and calls[-1] == 'closed'


def fake_blocks(count):
    return [dict(id=f'invented#{i}', source='invented', start=i * 90, end=(i + 1) * 90,
                 closing='length', text='지어낸 종이배 대화 ' * 10, chars=80,
                 human=dict(keep='keep', kind='story', policy=False),
                 probability=.99, model_kind='story') for i in range(count)]


def test_end_to_end_fake_windows_snapshot_privacy(tmp_path, monkeypatch, capsys):
    blocks = fake_blocks(25)
    directory = tmp_path / '.local/eval/stitch'
    directory.mkdir(parents=True)
    records = c.snapshot([blocks])
    (directory / 'runs.jsonl').write_text('\n'.join(json.dumps(row) for row in records), encoding='utf-8')
    monkeypatch.setattr(s, 'load_dev', lambda root: (blocks, {}))
    relay, calls = r.SummaryRelay(fake=True), []
    def send(address, payload):
        calls.append(payload)
        return relay.story_split(payload)[1]
    summary = s.execute(root=tmp_path, relay='http://127.0.0.1:8771', policy='cont0.5_min40', send=send)
    assert [len(p['segments']) for p in calls] == [12, 12, 1]
    assert summary['runs_sent'] == summary['ok'] == summary['windowed_runs'] == 1
    assert summary['windows_ok'] == 3 and summary['stories_per_run'] == {3: 1}
    assert (summary['keep'], summary['hide'], summary['reasons']) == (3, 0, {})
    output = list(directory.glob('split-*.jsonl'))[0].read_text(encoding='utf-8')
    assert blocks[0]['text'] not in output and not ''.join(capsys.readouterr())
    rows = [json.loads(line) for line in output.splitlines()]
    assert [row['segment_offset'] for row in rows] == [0, 12, 24]
    assert rows[-1]['stories'][0]['ids'] == ['invented#24']
    assert rows[-1]['stories'][0]['keep'] is True and rows[-1]['stories'][0]['reason'] == ''
    blocks[0]['chars'] = 0
    with pytest.raises(ValueError, match='snapshot mismatch'):
        s.restore_runs(blocks, records, 'cont0.5_min40')


def test_no_saved_runs_and_failed_outputs_are_sanitized(tmp_path, monkeypatch):
    blocks = fake_blocks(2)
    blocks[0]['probability'] = blocks[1]['probability'] = .1
    directory = tmp_path / '.local/eval/stitch'
    directory.mkdir(parents=True)
    def prepare():
        (directory / 'runs.jsonl').write_text(json.dumps(c.snapshot([blocks])[0]), encoding='utf-8')
    prepare()
    monkeypatch.setattr(s, 'load_dev', lambda root: (blocks, {}))
    calls = []
    def fail(*args):
        calls.append(1)
        raise RuntimeError('PRIVATE_INVENTED_DETAILS')
    assert s.execute(root=tmp_path, relay='http://127.0.0.1', policy='cont0.5_min40', send=fail)['runs_sent'] == 0
    assert not calls
    blocks[0]['probability'] = .99
    prepare()
    assert s.execute(root=tmp_path, relay='http://127.0.0.1', policy='cont0.5_min40', send=fail)['failed'] == 1
    assert 'PRIVATE' not in next(directory.glob('split-*')).read_text(encoding='utf-8')


def test_split_persists_hidden_decision_and_counts(tmp_path, monkeypatch):
    blocks = fake_blocks(2)
    directory = tmp_path / '.local/eval/stitch'
    directory.mkdir(parents=True)
    (directory / 'runs.jsonl').write_text(json.dumps(c.snapshot([blocks])[0]), encoding='utf-8')
    monkeypatch.setattr(s, 'load_dev', lambda root: (blocks, {}))
    def send(address, payload):
        groups = [story([1]), story([2], keep=False, reason='procedure')]
        return dict(stories=[dict(group, tags=['놀이', '종이배']) for group in groups],
                    model=r.DEFAULT_MODEL, elapsed_ms=1)
    summary = s.execute(root=tmp_path, relay='http://127.0.0.1', policy='cont0.5_min40', send=send)
    assert (summary['keep'], summary['hide'], summary['reasons']) == (1, 1, {'procedure': 1})
    output = json.loads(next(directory.glob('split-*')).read_text(encoding='utf-8'))
    assert output['stories'][1]['keep'] is False and output['stories'][1]['reason'] == 'procedure'
