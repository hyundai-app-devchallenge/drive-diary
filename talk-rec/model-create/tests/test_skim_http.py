from collections import Counter
from contextlib import contextmanager
import http.client
import json
from pathlib import Path
import threading

import pytest

from synth.cards import sample_cards
from synth.degrade import export
from synth.generate import fake_scene
from synth.io import read_jsonl
from tools.skim.serve import ASSETS, SkimServer, SkimStore, safe_name, stratified_sample


@pytest.fixture
def dataset(tmp_path):
    cards = sample_cards(40)
    export(cards, [fake_scene(c) for c in cards], tmp_path, 'mixed')
    return tmp_path


@contextmanager
def running(store):
    server = SkimServer(('127.0.0.1', 0), store)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        assert not thread.is_alive()


def request(server, path, data=None, headers=None):
    connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
    defaults = {'Content-Type': 'application/json', 'X-Skim-Token': server.token}
    defaults.update(headers or {})
    try:
        connection.request('POST' if data is not None else 'GET', path,
                           json.dumps(data).encode() if data is not None else None, defaults)
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read().decode('utf-8')
    finally:
        connection.close()


def test_sample_balanced_seeded(dataset):
    labels = read_jsonl(dataset / 'labels.jsonl')
    chosen = stratified_sample(labels, 30)
    by_id = {r['id']: r for r in labels}
    assert chosen == stratified_sample(labels, 30)
    assert chosen != stratified_sample(labels, 30, seed=44)
    assert len(set(chosen)) == 30
    counts = Counter(by_id[i]['verdict'] for i in chosen)
    # moment has only eight available cards, so the two larger strata fill up.
    assert counts == {'story': 11, 'moment': 8, 'none': 11}
    # Each label covers as many distinct patterns as its share of the sample allows.
    for verdict, count in counts.items():
        available = {r['pattern'] for r in labels if r['verdict'] == verdict}
        picked = {by_id[i]['pattern'] for i in chosen if by_id[i]['verdict'] == verdict}
        assert len(picked) == min(count, len(available))


@pytest.mark.parametrize('name', ['', '../escape', 'a/b', r'a\b', '..', 'a:b', 'CON', 'com1', '한글 이름', 'a\n'])
def test_name_path_blocking(name):
    with pytest.raises(ValueError):
        safe_name(name)


def test_default_response_hides_label_and_original(dataset):
    store = SkimStore(dataset, '검토자', limit=12)
    with running(store) as server:
        status, headers, body = request(server, '/')
        assert status == 200 and 'lang="ko"' in body
        assert '<section id="clean" hidden>' in body
        assert '<section id="intended" hidden>' in body
        assert '{{TOKEN}}' not in body
        assert 'https://' not in body and 'http://' not in body
        assert "default-src 'self'" in headers['Content-Security-Policy']
        status, _, body = request(server, '/api/item', {'index': 0})
        assert status == 200
        value = json.loads(body)
        assert value['decision'] is None
        assert set(value) == {'index', 'total', 'id', 'turns', 'seconds', 'decision', 'done'}
        assert value['turns'] == store.blocks[value['id']]['turns']
        for key in ('label', 'pattern', 'why', 'lines', 'family', 'card'):
            assert key not in value


def test_save_requires_both_and_open_flags_persist(dataset):
    store = SkimStore(dataset, '검토자', limit=10)
    identity = store.ids[0]
    with running(store) as server:
        data = dict(id=identity, real='yes', verdict='story', comment='끊어지는 말이 자연스럽다')
        status, _, _ = request(server, '/api/save', dict(data, verdict=None))
        assert status == 400 and not store.path.exists()
        assert request(server, '/api/clean', {'id': identity})[0] == 200
        status, _, body = request(server, '/api/label', {'id': identity})
        assert status == 200 and json.loads(body)['label'] == store.labels[identity]['verdict']
        status, _, body = request(server, '/api/save', data)
        assert status == 200
        saved = json.loads(body)
        assert saved['opened_clean'] and saved['revealed_label']
        assert saved['decided_at'].endswith('+00:00')
        assert set(saved) == {'id', 'real', 'verdict', 'comment', 'decided_at', 'opened_clean', 'revealed_label'}
    new = SkimStore(dataset, '검토자', limit=10)
    assert new.item(0)['decision'] == saved
    new.save(dict(data, real='no'))
    assert len(read_jsonl(new.path)) == 1
    assert read_jsonl(new.path)[0]['opened_clean'] is True
    assert not list((dataset / 'skim').glob('*.lock'))


def test_flags_false_until_opened(dataset):
    store = SkimStore(dataset, 'person')
    row = store.save(dict(id=store.ids[0], real='maybe', verdict='none', comment=''))
    assert row['opened_clean'] is False and row['revealed_label'] is False


@pytest.mark.parametrize('path', ['/../labels.jsonl', '/%2e%2e/labels.jsonl', '/labels.jsonl',
                                  '/app.js/../../clean.jsonl', '/api/item?index=0'])
def test_http_static_path_blocking(dataset, path):
    with running(SkimStore(dataset, 'path')) as server:
        assert request(server, path)[0] == 404


def test_http_csrf_host_and_index_validation(dataset):
    with running(SkimStore(dataset, 'security')) as server:
        assert request(server, '/', headers={'Host': 'attacker.example'})[0] == 403
        assert request(server, '/', headers={'Origin': 'http://attacker.example'})[0] == 403
        assert request(server, '/api/item', {'index': 0}, {'X-Skim-Token': 'wrong'})[0] == 403
        assert request(server, '/api/item', {'index': -1})[0] == 400
        assert request(server, '/api/item', {'index': True})[0] == 400
        assert request(server, '/api/clean', {'id': '../secret'})[0] == 400
        assert request(server, '/api/save', {'id': 'bad'})[0] == 400
        assert request(server, '/api/item', {'index': 0, 'extra': 1})[0] == 400


def test_symlink_escape_if_supported(dataset, tmp_path):
    outside = tmp_path / 'outside'
    outside.mkdir()
    try:
        (dataset / 'skim').symlink_to(outside, target_is_directory=True)
    except OSError:
        pytest.skip('Windows symlink privilege unavailable')
    with pytest.raises(ValueError):
        SkimStore(dataset, 'person')


def test_script_shortcuts_and_safe_text_rendering():
    js = (ASSETS / 'app.js').read_text(encoding='utf-8')
    assert 'innerHTML' not in js
    assert '.textContent' in js
    assert "event.target.matches('textarea,input')" in js
    assert "event.key === 'Escape'" in js
    for key in ['y:', 'n:', 'm:', "'1':", "'2':", "'3':", 'o:', 'l:', 'c:', 'j:', 'k:']:
        assert key in js
