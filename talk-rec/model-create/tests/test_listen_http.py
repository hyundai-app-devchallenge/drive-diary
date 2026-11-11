from contextlib import contextmanager
from http.client import HTTPConnection
import json
import threading

import pytest

from tools.listen import serve as s
from tools.listen.storage import encode_lines, read_answers, read_jsonl, atomic_write


@pytest.fixture
def store(tmp_path):
    path = tmp_path / '.local/demo/sample.jsonl'
    path.parent.mkdir(parents=True)
    path.write_bytes(encode_lines([dict(id='yt:00000000001@0', source='yt:00000000001', start=10, end=40,
        turns=['합성'], prefix=['직전 합성'], origin='approved',
        audio=dict(path='.local/audio/00000000001.webm',start=10,end=40))]))
    audio = tmp_path / '.local/audio/00000000001.webm'
    audio.parent.mkdir()
    audio.write_bytes(bytes(range(100)))
    return s.ListenStore('검토자', root=tmp_path)


@contextmanager
def running(store):
    server = s.make_server(store, 0)
    thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        assert not thread.is_alive()


def request(server, path='/api/state', payload=None, headers=None, method=None):
    conn = HTTPConnection('127.0.0.1', server.server_port, timeout=5)
    try:
        conn.request(method or ('POST' if payload is not None else 'GET'), path,
                     body=json.dumps(payload).encode() if payload is not None else None,
                     headers={'Content-Type':'application/json'} | (headers or {}))
        response = conn.getresponse()
        return response.status, response.headers, response.read()
    finally:
        conn.close()


def test_list_save_rejudge_resume_and_page(store):
    with running(store) as server:
        assert server.server_address[0] == '127.0.0.1'
        status, _, body = request(server)
        state = json.loads(body)
        assert status == 200 and state['total'] == 1 and state['answered'] == 0
        assert state['rows'][0]['play_start'] == 8 and state['rows'][0]['play_end'] == 41
        for verdict in ('story','moment','none','unsure','story'):
            payload = dict(id='yt:00000000001@0',verdict=verdict,comment='합성 의견',listened=True,opened_text=False)
            status, _, body = request(server, '/api/decision', payload)
            saved = json.loads(body)['saved']
            assert status == 200 and saved['decided_at'] and saved['verdict'] == verdict
        assert len(read_jsonl(store.path)) == 5
        assert read_answers(store.path)['yt:00000000001@0']['verdict'] == 'story'
        state = json.loads(request(server)[2])
        assert state['answered'] == 1 and state['rows'][0]['answer']['listened'] is True
        status, headers, page = request(server, '/')
        assert status == 200 and 'media-src' in headers['Content-Security-Policy']
        html = page.decode()
        assert '<html lang="ko">' in html and '<details id="text">' in html
        assert '<script src=' not in html and 'title' not in state['rows'][0]
    resumed = s.ListenStore('검토자', root=store.root)
    assert resumed.state()['answered'] == 1


@pytest.mark.parametrize('header,status,expected,content_range', [
    (None,200,bytes(range(100)),None),('bytes=10-19',206,bytes(range(10,20)),'bytes 10-19/100'),
    ('bytes=95-',206,bytes(range(95,100)),'bytes 95-99/100'),('bytes=-5',206,bytes(range(95,100)),'bytes 95-99/100'),
    ('bytes=98-999',206,bytes(range(98,100)),'bytes 98-99/100'),
    ('bytes=100-',416,b'','bytes */100'),('bytes=20-10',416,b'','bytes */100'),
    ('bytes=-0',416,b'','bytes */100'),('bytes=0-2,4-5',416,b'','bytes */100')])
def test_ranges(store,header,status,expected,content_range):
    with running(store) as server:
        code, headers, body = request(server,'/audio?id=yt%3A00000000001%400',headers={'Range':header} if header else {})
        assert code == status and body == expected
        assert headers.get('Content-Range') == content_range


def test_clip_preferred_and_head(store):
    clips = store.root / '.local/demo/clips'
    clips.mkdir()
    (clips / 'yt_00000000001@0.wav').write_bytes(b'clip')
    with running(store) as server:
        assert json.loads(request(server)[2])['rows'][0]['clipped'] is True
        assert request(server,'/audio?id=yt%3A00000000001%400')[2] == b'clip'
        status, headers, body = request(server,'/audio?id=yt%3A00000000001%400',method='HEAD')
        assert status == 200 and headers['Content-Type'] == 'audio/wav' and body == b''


@pytest.mark.parametrize('path',['/../data/approved.tsv','/.local/audio/00000000001.webm','/audio?id=..%2F..%2Fdata%2Fapproved.tsv','/audio/../../secret','/audio?id=yt%3A00000000001%400%2F..'])
def test_path_escape(store,path):
    with running(store) as server:
        assert request(server,path)[0] == 404


@pytest.mark.parametrize('bad', [dict(verdict='bad'),dict(listened=1),dict(opened_text='yes'),dict(id='yt:00000000002@0'),dict(comment=None),dict(extra=True)])
def test_invalid_save(store,bad):
    payload = dict(id='yt:00000000001@0',verdict='story',comment='',listened=False,opened_text=False) | bad
    with running(store) as server:
        assert request(server,'/api/decision',payload)[0] == 400
    assert not store.path.exists()


@pytest.mark.parametrize('name',['../escape','a/b','a\\b','','CON','name.',' user','a:b'])
def test_unsafe_names(store,name):
    with pytest.raises(ValueError):
        s.ListenStore(name,root=store.root)


def test_host_origin_and_atomic_failure(store,monkeypatch):
    with running(store) as server:
        assert request(server,headers={'Host':'evil.invalid'})[0] == 403
        payload = dict(id='yt:00000000001@0',verdict='story',comment='',listened=False,opened_text=False)
        assert request(server,'/api/decision',payload,{'Origin':'http://evil.invalid'})[0] == 403
        store.save(payload)
        before = store.path.read_bytes()
        def fail(*_):
            raise OSError('synthetic failure')
        monkeypatch.setattr('tools.listen.storage.os.replace',fail)
        assert request(server,'/api/decision',payload)[0] == 500
        assert store.path.read_bytes() == before


def test_answer_reader_missing_malformed_and_last_wins(store):
    assert read_answers(store.path) == {}
    atomic_write(store.path,b'{broken\n')
    with pytest.raises(ValueError):
        read_answers(store.path)
