"""T50: invented speech and synthetic PCM only. No device/network required."""
import hashlib
import json
from pathlib import Path
import threading
import shutil
import subprocess
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import wave

import pytest

from demo import pleos
from demo.asr.data import validate_result
from demo.trial.data import input_text
from eval import real
from pipeline.blocks import Settings, approval_reason, closing_reason
from tools.listen.real import RealListenStore
from tools.listen.serve import make_server

VIDEOS = ['abcdefghijk', 'bcdefghijkl', 'cdefghijklm']


def audio(path, seconds=2):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), 'wb') as output:
        output.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        output.writeframes(b'\x01\x00' * int(seconds * 16000))


def approvals(root, rows=None):
    path = root / 'data/approved.tsv'
    path.parent.mkdir(parents=True, exist_ok=True)
    rows = rows or [(v, 'approve', 'user') for v in VIDEOS]
    path.write_text('video_id\tdecision\treason\tdecided_by\n' + ''.join(
        f'{video}\t{decision}\tmade up\t{who}\n' for video, decision, who in rows), encoding='utf-8')


def asr(video=VIDEOS[0], lines=None, seconds=200):
    return dict(id=f'yt:{video}@0', config='pleos', seconds=seconds, avg_logprob=None,
                no_speech_prob=None, lines=lines if lines is not None else [dict(start=.2, end=1, text='만든 말')])


@pytest.fixture
def setup(tmp_path):
    approvals(tmp_path)
    directory = real.run_directory(tmp_path)
    for video in VIDEOS:
        audio(tmp_path / '.local/audio' / (video + '.webm'))
    real.discover_sources(tmp_path, directory)
    real.write_objects(directory / 'asr.jsonl', [asr(v) for v in VIDEOS])
    blocks = real.build_blocks(tmp_path, directory)
    real.assign_split(tmp_path, VIDEOS)
    return tmp_path, directory, blocks


def judgment(block, name='가', keep='keep', kind='story', **changes):
    row = dict(id=block['id'] if isinstance(block, dict) else block, keep=keep,
               kind=kind if keep == 'keep' else '', none_reason='', unsure=False,
               policy=[], comment='지어낸 메모', listened=True, labeler='human:' + name, saved_at='2026-11-12T00:00:00+00:00')
    row.update(changes)
    return row


def put(directory, name, rows):
    real.write_objects(real.judgment_path(directory, name), rows)


def proposal(block, **changes):
    row = judgment(block)
    row.pop('listened')
    row.update(labeler='claude', basis='transcript')
    row.update(changes)
    return row


def test_suggestions_dev_only_last_wins_and_unassigned_dropped(setup, capsys):
    root, directory, blocks = setup
    mapping = real.read_split(root)
    dev = next(b for b in blocks if mapping[b['source'][3:]] == 'dev')
    test = next(b for b in blocks if mapping[b['source'][3:]] == 'test')
    unknown = dict(blocks[0], id='yt:defghijklmn#0', source='yt:defghijklmn')
    real.write_objects(directory / 'blocks.jsonl', blocks + [unknown])
    put(directory, 'claude', [proposal(dev), proposal(test, keep='invalid-private-test'),
                              proposal(unknown, keep='invalid-private-unknown'),
                              proposal(dev, kind='moment')])
    result = real.load_suggestions(root, directory, warn_test=True)
    assert set(result) == {dev['id']} and result[dev['id']]['kind'] == 'moment'
    assert capsys.readouterr().err == '경고: Claude test 제안 1개 제외\n'


@pytest.mark.parametrize('suggest', [None, 'claude'])
def test_suggestion_api_only_opted_in_dev(setup, suggest):
    root, directory, blocks = setup
    put(directory, 'claude', [proposal(b) for b in blocks])
    # Other human files remain inaccessible, even with suggestions enabled.
    real.judgment_path(directory, '나').write_text('broken private human file', encoding='utf-8')
    store = RealListenStore('v1', '가', root=root, suggest=suggest)
    rows = store.state()['rows']
    assert {r['split'] for r in rows} == {'dev', 'test'}
    for row in rows:
        assert ('suggestion' in row) == (suggest == 'claude' and row['split'] == 'dev')
        if 'suggestion' in row:
            assert set(row['suggestion']) == set(real.SUGGESTION_FIELDS)
    # Serialize the actual HTTP response as well as the store result.
    server = make_server(store, 0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        response = json.load(urlopen(f'http://127.0.0.1:{server.server_port}/api/state'))
        assert response['rows'] == rows
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def test_no_suggest_never_reads_claude_file(setup):
    root, directory, _ = setup
    real.judgment_path(directory, 'claude').parent.mkdir(parents=True, exist_ok=True)
    real.judgment_path(directory, 'claude').write_text('broken proposal', encoding='utf-8')
    assert all('suggestion' not in r for r in RealListenStore('v1', '가', root=root).state()['rows'])


@pytest.mark.parametrize('name', ['claude', 'Claude', 'CLAUDE'])
def test_claude_is_reserved_annotator(tmp_path, name):
    from tools.listen import serve
    with pytest.raises(ValueError, match='제안 전용'):
        RealListenStore('v1', name, root=tmp_path)
    with pytest.raises(SystemExit) as error:
        serve.main(['--real', 'v1', '--annotator', name, '--port', '8790'])
    assert error.value.code == 2


@pytest.mark.parametrize('change', [dict(kind='moment'), dict(keep='none', kind='', none_reason='negative'),
                                    dict(unsure=True), dict(policy=['external']), dict(comment='고친 이유')])
def test_review_saved_by_server_and_readable_after_restart(setup, change):
    root, directory, blocks = setup
    put(directory, 'claude', [proposal(b) for b in blocks])
    store = RealListenStore('v1', '가', root=root, suggest='claude')
    rows = store.state()['rows']
    dev = next(r for r in rows if r['split'] == 'dev')
    payload = dict(dev['suggestion'], id=dev['id'], listened=False)
    accepted = store.save(payload)['saved']
    assert accepted['suggested_by'] == 'claude' and accepted['review'] == 'accepted'
    assert accepted['labeler'] == 'human:가' and accepted['listened'] is False
    changed = store.save(dict(payload, **change))['saved']
    assert changed['review'] == 'changed'
    for row in rows:
        if row['split'] == 'test':
            answer = judgment(row['id'])
            saved = store.save({k: v for k, v in answer.items() if k not in ('labeler', 'saved_at')})['saved']
            assert not (real.REVIEW_KEYS & set(saved))
    ordinary = RealListenStore('v1', '가', root=root)
    assert next(r['answer'] for r in ordinary.state()['rows'] if r['id'] == dev['id']) == changed
    saved = ordinary.save(payload)['saved']
    assert not (real.REVIEW_KEYS & set(saved))
    with pytest.raises(ValueError):
        store.save(dict(payload, suggested_by='claude', review='accepted'))


def test_policy_order_is_not_a_review_change():
    suggestion = proposal(f'yt:{VIDEOS[0]}#0', policy=['optout', 'external'])
    assert real.review_result(dict(suggestion, policy=['external', 'optout']), suggestion) == 'accepted'
    suggestion = proposal(f'yt:{VIDEOS[0]}#0', keep='none', kind='', none_reason='not_car')
    assert real.review_result(dict(suggestion, none_reason='negative'), suggestion) == 'changed'


@pytest.mark.parametrize('change', [dict(labeler='human:가'), dict(basis='audio'),
                                    dict(listened=False), dict(kind='invalid'), dict(policy=['invalid'])])
def test_invalid_dev_suggestion_redacts_content(setup, change):
    root, directory, blocks = setup
    mapping = real.read_split(root)
    dev = next(b for b in blocks if mapping[b['source'][3:]] == 'dev')
    put(directory, 'claude', [proposal(dev, comment='PRIVATE invented comment', **change)])
    with pytest.raises(ValueError) as error:
        real.load_suggestions(root, directory)
    assert str(error.value) == '제안 오류 claude.jsonl:1'


def test_agree_human_claude_lock_warning_and_human_only_exports(setup, capsys, monkeypatch):
    root, directory, blocks = setup
    for name in ('가', '나'):
        put(directory, name, [judgment(b, name=name) for b in blocks])
    put(directory, 'claude', [proposal(b) for b in blocks])
    args = ['agree', '가', 'claude', '--root', str(root)]
    assert real.main(args) == 0
    captured = capsys.readouterr()
    report = json.loads(captured.out)
    assert report['including_unsure']['n'] == len(real.load_suggestions(root, directory))
    assert report['kind_agreement'] == 1 and 'test 제안' in captured.err
    assert '지어낸' not in captured.out + captured.err
    assert not (directory / 'test-access.log').exists()
    with pytest.raises(ValueError, match='test 잠김'):
        real.load_labels(root, directory, annotators=['가', 'claude'], command='agree', split='test')
    assert real.main(['open-test', 'agree', '가', 'claude', '--root', str(root)]) == 0
    assert (directory / 'test-access.log').exists()
    assert json.loads(capsys.readouterr().out)['annotators'] == 1

    original = real.read_objects
    def no_claude_read(path):
        assert Path(path).name != 'claude.jsonl'
        return original(path)
    monkeypatch.setattr(real, 'read_objects', no_claude_read)
    assert set(real.load_labels(root, directory)[1]) == {'가', '나'}
    for split in ('dev', 'test', 'all'):
        result = real.export(root, directory, split=split, open_test=split != 'dev')
        assert set(result['annotators']) == {'가', '나'}
        manifest = json.loads((directory / f'export/{split}/manifest.json').read_text(encoding='utf-8'))
        assert set(manifest['annotators']) == {'가', '나'}
        assert not (directory / f'export/{split}/annotators/claude.jsonl').exists()
        assert all(r['labeler'] == 'consensus' for r in original(directory / f'export/{split}/consensus.jsonl'))
    with pytest.raises(ValueError, match='제안 전용'):
        real.export(root, directory, annotators=['claude'])


def test_stats_counts_dev_reviews_only(setup):
    root, directory, blocks = setup
    mapping = real.read_split(root)
    rows = [judgment(b, suggested_by='claude', review='accepted' if i % 2 else 'changed')
            for i, b in enumerate(blocks)]
    put(directory, '가', rows)
    report = real.statistics_report(root, directory, split='all', open_test=True)
    assert report['splits']['dev']['reviews']['가'] == {
        value: sum(r['review'] == value for b, r in zip(blocks, rows) if mapping[b['source'][3:]] == 'dev')
        for value in ('accepted', 'changed')}
    assert '지어낸' not in json.dumps(report, ensure_ascii=False)


def test_sources_shared_approval_and_missing_audio(tmp_path):
    approvals(tmp_path, [(VIDEOS[0], 'approve', ' USER '), (VIDEOS[1], 'approve', 'ClAuDe'),
                          (VIDEOS[2], 'approve', ''), ('defghijklmn', 'reject', 'user'),
                          ('efghijklmno', 'approve', 'user')])
    audio(tmp_path / '.local/audio' / (VIDEOS[0] + '.webm'))
    rows = real.discover_sources(tmp_path, real.run_directory(tmp_path))
    assert [r['id'] for r in rows] == [VIDEOS[0], 'efghijklmno']
    assert rows[0]['seconds'] == 2 and rows[0]['status'] == 'ready'
    assert rows[1]['reason'] == 'audio_missing'
    assert approval_reason(dict(decision='approve', decided_by=' ClAuDe ')) == 'prescreened only'


def test_bad_audio_is_skipped_without_body(tmp_path):
    approvals(tmp_path)
    path = tmp_path / '.local/audio' / (VIDEOS[0] + '.webm')
    path.parent.mkdir(parents=True)
    path.write_bytes(b'private-test-body')
    rows = real.discover_sources(tmp_path, real.run_directory(tmp_path))
    assert rows[0]['reason'] == 'audio_invalid'
    assert 'private-test-body' not in json.dumps(rows)


@pytest.mark.parametrize('name', ['..', '.', '../a', 'a/b', 'a\\b', 'CON', 'nul.txt', ' x', 'x.', 'a:bad'])
def test_unsafe_names(name, tmp_path):
    with pytest.raises(ValueError):
        real.run_directory(tmp_path, name)
    with pytest.raises(ValueError):
        RealListenStore('v1', name, root=tmp_path)


def test_blocks_no_turn_limit_and_existing_rule():
    lines = [dict(start=i*.2, end=i*.2+.1, text='짧은 말') for i in range(30)]
    blocks = real.make_blocks([asr(lines=lines)])
    assert len(blocks) == 1 and blocks[0]['n_lines'] == 30
    assert closing_reason(lines[:12], lines[12], Settings()) == 'turns'
    assert closing_reason(lines[:12], lines[12], Settings(max_turns=None)) is None


def test_block_boundaries_grid():
    lines = [dict(start=a, end=b, text='지어낸 문장') for a, b in [(0, 10), (30, 40), (41, 130), (131, 132)]]
    blocks = real.make_blocks([asr(lines=lines)])
    assert [b['closing'] for b in blocks] == ['silence', 'length', 'length', 'range_end']
    assert [b['id'] for b in blocks] == [f'yt:{VIDEOS[0]}#{i}' for i in range(4)]
    assert sum(b['n_lines'] for b in blocks) == len(lines)
    table = real.grid([asr(lines=lines)])
    assert len(table.splitlines()) == 11
    assert '지어낸' not in table


def test_preserve_labeled_blocks_allow_new_source(setup):
    root, directory, blocks = setup
    put(directory, '가', [judgment(blocks[0])])
    rows = real.read_objects(directory / 'asr.jsonl')
    rows[0]['lines'][0]['text'] = '새로 만든 문장'
    real.write_objects(directory / 'asr.jsonl', rows)
    with pytest.raises(ValueError, match='기존 묶음'):
        real.build_blocks(root, directory)


def test_transcriptions_require_current_human_approval(setup):
    root, directory, _ = setup
    approvals(root, [(v, 'approve', 'claude') for v in VIDEOS])
    with pytest.raises(ValueError, match='asr.jsonl:1'):
        real.transcriptions(root, directory)


@pytest.mark.parametrize('change', [dict(start=-1), dict(end=400), dict(text=9)])
def test_invalid_asr_is_redacted(setup, change):
    root, directory, _ = setup
    row = asr()
    row['lines'][0].update(change)
    real.write_objects(directory / 'asr.jsonl', [row])
    with pytest.raises(ValueError) as error:
        real.transcriptions(root, directory)
    assert str(error.value) == 'ASR 오류 asr.jsonl:1'


def test_split_is_deterministic_append_only(tmp_path):
    initial = real.assign_split(tmp_path, VIDEOS[:2])
    assert set(initial.values()) == {'dev', 'test'}
    before = (tmp_path / 'data/real-split.tsv').read_bytes()
    assert real.assign_split(tmp_path, list(reversed(VIDEOS[:2]))) == initial
    assert before == (tmp_path / 'data/real-split.tsv').read_bytes()
    expanded = real.assign_split(tmp_path, VIDEOS)
    assert all(expanded[v] == value for v, value in initial.items())
    other = tmp_path / 'other'
    assert real.assign_split(other, list(reversed(VIDEOS[:2]))) == initial


def test_split_single_then_second_never_reassigns(tmp_path):
    initial = real.assign_split(tmp_path, VIDEOS[:1])
    result = real.assign_split(tmp_path, VIDEOS[:2])
    assert result[VIDEOS[0]] == initial[VIDEOS[0]]
    assert set(result.values()) == {'dev', 'test'}


def test_lock_and_default_filter_before_validation(setup):
    root, directory, blocks = setup
    mapping = real.read_split(root)
    rows = [judgment(b) for b in blocks]
    # Test rows may contain invalid labels: the dev reader must not validate/use them.
    for row, b in zip(rows, blocks):
        if mapping[b['source'][3:]] == 'test':
            row['keep'] = 'unread-test-label'
    put(directory, '가', rows)
    selected, labels = real.load_labels(root, directory)
    assert labels['가'] and all(r['keep'] == 'keep' for r in labels['가'].values())
    assert all(mapping[b['source'][3:]] == 'dev' for b in selected)
    assert not (directory / 'test-access.log').exists()
    with pytest.raises(ValueError, match='test 잠김'):
        real.load_labels(root, directory, split='test')
    assert not (directory / 'test-access.log').exists()


def test_test_access_logged_each_time_and_no_persistent_unlock(setup):
    root, directory, blocks = setup
    put(directory, '가', [judgment(b) for b in blocks])
    for command in ('stats', 'agree', 'export'):
        selected, labels = real.load_labels(root, directory, split='test', open_test=True, command=command)
        assert selected and labels['가']
    log = (directory / 'test-access.log').read_text()
    assert len(log.splitlines()) == 3 and '지어낸' not in log
    with pytest.raises(ValueError):
        real.load_labels(root, directory, split='test')


def test_no_split_fails_closed(setup):
    root, directory, blocks = setup
    (root / 'data/real-split.tsv').unlink()
    with pytest.raises(ValueError, match='분할 없음'):
        real.load_labels(root, directory)


def test_agreement_known_kappa_kind_and_unsure():
    a, b = {}, {}
    for i, (x, y) in enumerate([('keep', 'keep'), ('keep', 'none'), ('none', 'keep'), ('none', 'none')]):
        identity = f'yt:{VIDEOS[0]}#{i}'
        a[identity] = judgment(identity, keep=x, unsure=i == 1)
        b[identity] = judgment(identity, name='나', keep=y, kind='moment')
    report = real.agreement({'가': a, '나': b})
    assert report['including_unsure'] == dict(n=4, kappa=0)
    assert report['excluding_unsure']['n'] == 3
    assert report['kind_count'] == 1 and report['kind_agreement'] == 0
    assert len(report['disagreements']) == 3
    assert real.kappa([])['kappa'] is None
    assert real.kappa([(a[next(iter(a))], a[next(iter(a))])])['kappa'] is None
    assert real.agreement({'가': a})['annotators'] == 1


def test_stats_and_export_default_dev_consensus(setup):
    root, directory, blocks = setup
    for name in ('가', '나'):
        put(directory, name, [judgment(b, name=name) for b in blocks])
    selected, _ = real.load_labels(root, directory)
    report = real.statistics_report(root, directory)
    assert report['blocks'] == 3 and report['selected_blocks'] == len(selected)
    assert report['splits']['test']['judgments'] is None
    assert report['annotators']['가']['judgments'] == len(selected)
    counts = real.export(root, directory)
    assert counts['consensus'] == len(selected)
    output = real.read_objects(directory / 'export/dev/consensus.jsonl')
    assert {r['id'] for r in output} == {b['id'] for b in selected}
    assert output[0]['text'] == input_text(selected[0]['lines'])
    assert all('comment' not in r for r in output)
    assert '만든 말' not in json.dumps(report, ensure_ascii=False)
    assert '지어낸 메모' not in json.dumps(output, ensure_ascii=False)


def test_consensus_excludes_disagreement_unsure_or_not_listened(setup):
    root, directory, blocks = setup
    put(directory, '가', [judgment(b) for b in blocks])
    put(directory, '나', [judgment(b, name='나', unsure=True) for b in blocks])
    assert real.export(root, directory)['consensus'] == 0


def test_real_store_only_fixed_annotator_and_history(setup):
    root, directory, blocks = setup
    # A broken other annotator file must never even be opened by this store.
    path = real.judgment_path(directory, '나')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('private-other-label', encoding='utf-8')
    store = RealListenStore('v1', '가', root=root)
    row = judgment(blocks[0])
    payload = {k: v for k, v in row.items() if k not in ('saved_at', 'labeler')}
    store.save(payload)
    store.save(dict(payload, keep='none', kind='', none_reason='negative'))
    state = RealListenStore('v1', '가', root=root).state()
    assert state['answered'] == 1
    assert next(r['answer'] for r in state['rows'] if r['id'] == blocks[0]['id'])['keep'] == 'none'
    assert len(real.read_objects(store.path)) == 2
    assert 'private-other-label' not in json.dumps(state)
    assert [r['id'] for r in store.rows] == [r['id'] for r in RealListenStore('v1', '다', root=root).rows]


@pytest.mark.parametrize('changes', [dict(keep='bad'), dict(kind=''), dict(none_reason='negative'),
                                     dict(policy=['made-up']), dict(policy=['optout', 'optout']),
                                     dict(unsure=1), dict(listened=1), dict(labeler='human:나')])
def test_judgment_schema(changes, setup):
    _, _, blocks = setup
    with pytest.raises(ValueError):
        real.validate_judgment(judgment(blocks[0], **changes), '가')


def test_server_has_no_other_annotator_api_and_cuts_audio(setup, monkeypatch):
    root, directory, blocks = setup
    put(directory, '나', [judgment(b, name='나', comment='다른 판정자 비밀') for b in blocks])
    store = RealListenStore('v1', '가', root=root)
    server = make_server(store, 0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f'http://127.0.0.1:{server.server_port}'
    try:
        for suffix in ('/api/state', '/api/state?annotator=나', '/api/state?name=나'):
            # URL must be ASCII; this still probes attempts to select another labeler.
            from urllib.parse import quote
            data = urlopen(base + quote(suffix, safe='/?=&')).read().decode()
            assert '다른 판정자 비밀' not in data
            assert json.loads(data)['name'] == '가'
        for suffix in ('/judgments/나.jsonl', '/api/judgments', '/api/answers', '/api/state/나'):
            from urllib.parse import quote
            with pytest.raises(HTTPError) as error:
                urlopen(base + quote(suffix, safe='/'))
            assert error.value.code == 404
        page = urlopen(base).read().decode()
        assert '전사 보기' in page and '<details id="transcript">' in page
        from urllib.parse import quote
        response = urlopen(base + '/audio?id=' + quote(blocks[0]['id'], safe=''))
        assert response.read()[:4] == b'RIFF'
        clip, start, end = store.media(blocks[0]['id'])
        with wave.open(str(clip)) as wav:
            assert wav.getnframes() / wav.getframerate() == 2
        assert start == 0 and end == pytest.approx(2)
        payload = judgment(blocks[0], name='나')
        request = Request(base + '/api/decision', json.dumps(payload).encode(), {'Content-Type':'application/json'})
        with pytest.raises(HTTPError) as error:
            urlopen(request)
        assert error.value.code == 400
        assert not store.path.exists()
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


class FakeDevice:
    """Binary adb boundary with a persistent remote filesystem."""
    def __init__(self, entries):
        self.entries = entries
        self.files, self.calls = {}, []
        self.run_id = None

    def __call__(self, args, stdin=None, **kwargs):
        self.calls.append(args)
        if args == ['shell', 'am', 'get-current-user']:
            return b'10\n'
        if args[0] == 'exec-in':
            self.files[args[-1].split(' > ')[1]] = stdin.read()
        if args[-4:-1] == ['stat', '-c', '%s']:
            return str(len(self.files[args[-1]])).encode() + b'\r\n'
        if 'start-foreground-service' in args:
            self.run_id = args[args.index('run_id')+1]
        if args[-1] == pleos.REMOTE + '/batch-status.json':
            return json.dumps(dict(run_id=self.run_id, state='completed', total=len(self.entries), completed=len(self.entries))).encode()
        if args[-1].endswith('.tsv'):
            entry = next(e for e in self.entries if Path(e['name']).with_suffix('.tsv').name == Path(args[-1]).name)
            return ('kind\treceived_ms\taudio_ms\ttext\tmetadata\nfinal\t1\t' +
                    str(entry['seconds']*1000) + '\t지어낸 말\t\n').encode()
        return b''


def test_fake_adb_prepare_push_run_pull_collect_resume(setup):
    root, directory, _ = setup
    (directory / 'asr.jsonl').unlink()
    entries = pleos.prepare_sources(root, directory, chunk_seconds=.75)
    assert len(entries) == 9
    assert [e['offset'] for e in entries[:3]] == [0, .75, 1.5]
    assert [e['seconds'] for e in entries[:3]] == [.75, .75, .5]
    assert sum(e['seconds'] for e in entries) == 6
    device = FakeDevice(entries)
    pleos.push(root, device, workspace=directory)
    assert len(device.files) == 9
    assert all(c[0] == 'exec-out' for c in device.calls if 'stat' in c)
    assert not any('wc -c' in str(c) for c in device.calls)
    pleos.run(root, device, timeout=10, workspace=directory)
    pleos.pull(root, device, workspace=directory)
    rows = pleos.collect(root, workspace=directory)
    assert len(rows) == 3
    for row in rows:
        validate_result(row)
        assert row['config'] == 'pleos' and row['seconds'] == 2
        assert [(x['start'], x['end']) for x in row['lines']] == [(0, .75), (.75, 1.5), (1.5, 2)]
    before = (directory / 'asr.jsonl').read_bytes()
    assert pleos.prepare_sources(root, directory, chunk_seconds=.75) == []
    assert (directory / 'asr.jsonl').read_bytes() == before


def test_retry_only_failed_chunks(setup):
    root, directory, _ = setup
    (directory / 'asr.jsonl').unlink()
    entries = pleos.prepare_sources(root, directory, 1, ids=VIDEOS[:1])
    device = FakeDevice(entries)
    pleos.run(root, device, workspace=directory)
    pleos.pull(root, device, workspace=directory)
    raw = pleos.base(root, directory) / 'raw/pleos' / Path(entries[1]['name']).with_suffix('.tsv')
    raw.write_text('bad-header\n', encoding='utf-8')
    with pytest.raises(ValueError):
        pleos.collect(root, workspace=directory)
    pending = pleos.prepare_sources(root, directory, 1, ids=VIDEOS[:1])
    assert pending == entries[1:]
    device = FakeDevice(pending)
    pleos.run(root, device, workspace=directory)
    pleos.pull(root, device, workspace=directory)
    assert len(pleos.collect(root, workspace=directory)) == 1


def test_collect_requires_pulled_results(setup):
    root, directory, _ = setup
    (directory / 'asr.jsonl').unlink()
    pleos.prepare_sources(root, directory, 1)
    with pytest.raises(ValueError, match='invalid chunk result'):
        pleos.collect(root, workspace=directory)


def test_device_limit_and_changed_plan_require_new_run(setup):
    root, directory, _ = setup
    (directory / 'asr.jsonl').unlink()
    with pytest.raises(ValueError, match='95'):
        pleos.prepare_sources(root, directory, 600)
    pleos.prepare_sources(root, directory, 1)
    with pytest.raises(ValueError, match='changed'):
        pleos.prepare_sources(root, directory, 2)


def test_long_source_splits_without_overlap(tmp_path):
    approvals(tmp_path, [(VIDEOS[0], 'approve', 'user')])
    audio(tmp_path / '.local/audio' / (VIDEOS[0] + '.webm'), 190.25)
    directory = real.run_directory(tmp_path)
    entries = pleos.prepare_sources(tmp_path, directory)
    assert [e['offset'] for e in entries] == [0, 90, 180]
    assert [e['seconds'] for e in entries] == [90, 90, 10.25]
    payload = b''.join((pleos.base(tmp_path, directory) / 'pcm' / e['name']).read_bytes() for e in entries)
    assert payload == b'\x01\x00' * int(190.25 * 16000)


def test_completed_real_cli_bridge_never_calls_adb(setup, monkeypatch):
    root, directory, _ = setup
    def denied(*args, **kwargs):
        raise AssertionError('adb must not be called')
    monkeypatch.setattr(pleos, 'adb', denied)
    assert pleos.prepare_sources(root, directory) == []
    for command in ('push', 'run', 'pull', 'collect'):
        assert pleos.main([command, '--root', str(root), '--real', 'v1']) == 0


def test_cli_commands_and_redaction(setup, capsys):
    root, directory, blocks = setup
    put(directory, '가', [judgment(b) for b in blocks])
    for command in ('sources', 'blocks', 'grid', 'split', 'agree', 'export', 'stats'):
        assert real.main([command, '--root', str(root)]) == 0
        output = capsys.readouterr().out
        assert '만든 말' not in output and '지어낸 메모' not in output
    assert real.main(['open-test', 'stats', '--root', str(root)]) == 0
    capsys.readouterr()
    assert real.main(['stats', '--split', 'test', '--root', str(root)]) == 1
    assert 'ValueError' in capsys.readouterr().err
    (directory / 'asr.jsonl').write_text('PRIVATE_BODY', encoding='utf-8')
    assert real.main(['blocks', '--root', str(root)]) == 1
    assert 'PRIVATE_BODY' not in capsys.readouterr().err


def test_client_listening_ratio_navigation_and_save(tmp_path):
    """Execute the real client with a tiny browser stub, without a browser package."""
    node = shutil.which('node')
    if node is None:
        pytest.skip('node unavailable')
    page = Path(__file__).resolve().parents[1] / 'tools/listen/real.html'
    script = r'''
const fs=require('node:fs'), vm=require('node:vm'), assert=require('node:assert/strict');
const elements={}, handlers={};
function element(id){return elements[id]||=( {id,tagName:'DIV',value:'',checked:false,hidden:false,open:false,disabled:false,addEventListener(name,fn){handlers[id+':'+name]=fn;}} );}
const groups={keep:['keep','none'],kind:['story','moment'],policy:['optout','command','external']};
const inputs=Object.fromEntries(Object.entries(groups).map(([n,vs])=>[n,vs.map(value=>({name:n,value,checked:false}))]));
const audio=element('audio');Object.assign(audio,{paused:true,seeking:false,currentTime:0,duration:10,pause(){this.paused=true;},play(){this.paused=false;return Promise.resolve();},load(){}});
const document={getElementById:element,querySelectorAll(s){const name=/name="([^"]+)"/.exec(s)?.[1];return (inputs[name]||[]).filter(x=>!s.endsWith(':checked')||x.checked);},querySelector(s){return this.querySelectorAll(s)[0];},addEventListener(n,f){handlers['document:'+n]=f;}};
const rows=[0,1].map(i=>({id:'yt:abcdefghijk#'+i,start:0,end:10,turns:['invented speech'],note:'invented approval memo'}));
let sent;
const context=vm.createContext({document,assert,encodeURIComponent,fetch:async(path,opts)=>({ok:true,json:async()=>opts?.body?(sent=JSON.parse(opts.body),{saved:{...sent,labeler:'human:A'}}):{name:'A',rows,total:2}})});
const source=fs.readFileSync(process.argv[1],'utf8').split('<script>')[1].split('</script>')[0];
vm.runInContext(source,context);
setImmediate(async()=>{try{
vm.runInContext(`
assert.equal($('transcript').open,false);
assert.equal(state.total,2);
assert.equal($('rangeNote').textContent,'승인 메모 (전사 아님): invented approval memo');
audio.paused=false;audio.currentTime=0;last=0;
audio.seeking=true;audio.currentTime=9.9;watch();audio.seeking=false;watch();assert.equal(heard,false);
for(let repeat=0;repeat<5;repeat++){last=0;for(let t=.25;t<=2;t+=.25){audio.currentTime=t;watch();}}
assert.equal(heard,false);
last=0;for(let t=.25;t<=8.25;t+=.25){audio.currentTime=t;watch();}assert.equal(heard,true);
choose('keep','keep');choose('kind','moment');$('unsure').checked=true;$('comment').value='invented note';
assert.equal(checked('keep'),'keep');assert.equal(checked('kind'),'moment');assert.equal(busy,false);
`,context);
await vm.runInContext('save()',context);
assert.equal(sent.keep,'keep');assert.equal(sent.kind,'moment');assert.equal(sent.listened,true);assert.equal(sent.unsure,true);
vm.runInContext(`assert.equal(index,1);assert.equal($('transcript').open,false);move(-1);assert.equal(row().answer.kind,'moment');choose('keep','none');assert.equal(checked('kind'),'');assert.equal($('kindBox').hidden,true);`,context);
}catch(error){console.error(error);process.exitCode=1;}});
'''
    result = subprocess.run([node, '-e', script, str(page)], capture_output=True, text=True, timeout=20)
    assert result.returncode == 0, result.stderr


@pytest.mark.parametrize('storage_blocked', [False, True])
def test_client_filters_suggestions_human_priority_and_accept(storage_blocked):
    node = shutil.which('node')
    if node is None:
        pytest.skip('node unavailable')
    page = Path(__file__).resolve().parents[1] / 'tools/listen/real.html'
    script = r'''
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const elements={},handlers={},memory={'real-listen-split':'test'};
const blocked=process.argv[2]==='True';
function element(id){return elements[id]||={tagName:'DIV',value:'',checked:false,hidden:false,addEventListener(n,f){handlers[id+':'+n]=f;}};}
const inputs=Object.fromEntries(Object.entries({keep:['keep','none'],kind:['story','moment'],policy:['optout','command','external']}).map(([n,vs])=>[n,vs.map(value=>({value,checked:false}))]));
Object.assign(element('audio'),{paused:true,seeking:false,currentTime:0,duration:10,pause(){this.paused=true;},load(){},removeAttribute(){}});
const document={getElementById:element,querySelectorAll(s){const name=/name="([^"]+)"/.exec(s)?.[1];return(inputs[name]||[]).filter(x=>!s.endsWith(':checked')||x.checked);},querySelector(s){return this.querySelectorAll(s)[0];},addEventListener(n,f){handlers[n]=f;}};
const suggestion={keep:'keep',kind:'story',none_reason:'',unsure:true,policy:['optout'],comment:'invented reason'};
const rows=['dev','test','dev','test'].map((split,i)=>({id:'yt:abcdefghijk#'+i,split,start:0,end:10,turns:['invented speech'],suggestion}));
rows[2].answer={keep:'none',kind:'',none_reason:'negative',unsure:false,policy:[],comment:'invented human',listened:false};
let sent,fail=false;
const context=vm.createContext({document,assert,handlers,encodeURIComponent,localStorage:{getItem(k){if(blocked)throw Error();return memory[k];},setItem(k,v){if(blocked)throw Error();memory[k]=v;}},fetch:async(path,opts)=>({ok:!fail,json:async()=>opts?.body?(sent=JSON.parse(opts.body),fail?{error:'invented failure'}:{saved:{...sent,labeler:'human:A'}}):{name:'A',rows,total:4}})});
vm.runInContext(fs.readFileSync(process.argv[1],'utf8').split('<script>')[1].split('</script>')[0],context);
setImmediate(async()=>{try{
vm.runInContext(`assert.equal(filter,'${blocked?'all':'test'}');`,context);
vm.runInContext(`
setFilter('test');
assert.equal(visibleRows().length,2);assert.equal(row().id,'yt:abcdefghijk#1');
assert.equal($('suggestionBox').hidden,true);assert.equal(checked('keep'),'');assert.equal($('comment').value,'');
assert.ok($('progressText').textContent.includes('test 0/2 · 전체 1/4'));
move(1);assert.equal(row().id,'yt:abcdefghijk#3');move(1);assert.equal(index,1);
unjudged();assert.equal(row().id,'yt:abcdefghijk#1');move(-1);assert.equal(index,0);
setFilter('dev');assert.equal(row().id,'yt:abcdefghijk#0');
assert.equal($('suggestionBox').hidden,false);assert.equal(checked('kind'),'story');assert.equal(heard,false);
assert.equal($('unsure').checked,true);assert.equal($('comment').value,'invented reason');
assert.match($('suggestionText').textContent,/이야기/);
move(1);assert.equal(checked('keep'),'none');assert.equal($('reason').value,'negative');
assert.equal($('comment').value,'invented human');assert.equal($('unsure').checked,false);
move(-1);choose('kind','moment');
`,context);
await vm.runInContext('accept()',context);
assert.equal(sent.kind,'story');assert.equal(sent.listened,false);assert.equal(sent.comment,'invented reason');
vm.runInContext(`assert.equal(row().id,'yt:abcdefghijk#2');assert.equal(checked('keep'),'none');
assert.ok($('progressText').textContent.includes('dev 2/2 · 전체 2/4'));
setFilter('test');assert.equal($('suggestionBox').hidden,true);assert.equal($('suggestionText').textContent,'');
assert.equal(checked('keep'),'');assert.equal(checked('kind'),'');assert.equal($('reason').value,'');
assert.equal($('unsure').checked,false);assert.equal($('comment').value,'');`,context);
const previous=sent;await vm.runInContext('accept()',context);assert.equal(sent,previous);
vm.runInContext(`setFilter('dev');handlers.keydown({key:'a',target:{tagName:'BODY'},preventDefault(){}});`,context);
await new Promise(setImmediate);
assert.equal(sent.id,'yt:abcdefghijk#0');assert.equal(sent.kind,'story');
fail=true;await vm.runInContext('accept()',context);
vm.runInContext(`assert.equal(row().id,'yt:abcdefghijk#2');assert.equal($('error').textContent,'invented failure');assert.equal(busy,false);
state.rows=[];setFilter('test');assert.equal($('card').hidden,true);unjudged();move(1);`,context);
if(!blocked)assert.equal(memory['real-listen-split'],'test');
}catch(e){console.error(e);process.exitCode=1;}});
'''
    result = subprocess.run([node, '-e', script, str(page), str(storage_blocked)], capture_output=True, text=True, encoding='utf-8', timeout=20)
    assert result.returncode == 0, result.stderr


def range_table(root, rows):
    path = root / 'data/real-ranges.tsv'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('video_id\tstart\tend\tnote\n' + ''.join(
        f'{video}\t{start}\t{end}\t{note}\n' for video, start, end, note in rows), encoding='utf-8')
    return path


def test_range_times_clamping_fallback_and_ignored(tmp_path, capsys):
    approvals(tmp_path)
    for video in VIDEOS:
        audio(tmp_path / '.local/audio' / (video + '.webm'), 3)
    path = range_table(tmp_path, [(VIDEOS[0], '0:00', '0.5', 'invented A'),
                                 (VIDEOS[0], '0:00:01', 'end', 'invented B'),
                                 (VIDEOS[1], '1.25', '9', 'invented C'),
                                 ('unapproved!', 'invalid', 'invalid', 'ignored')])
    before = path.read_bytes()
    directory = real.run_directory(tmp_path)
    rows = real.discover_sources(tmp_path, directory)
    assert rows[0]['ranges'] == [dict(start=0, end=.5, note='invented A'),
                                 dict(start=1, end=3, note='invented B')]
    assert rows[0]['usable_seconds'] == 2.5
    assert rows[1]['range_adjustments'] == [dict(line=4, requested_end=9, end=3)]
    assert rows[2]['ranges'] == [dict(start=0, end=3, note='')]
    assert real.main(['sources', '--root', str(tmp_path)]) == 0
    summary = json.loads(capsys.readouterr().out)
    assert summary == dict(sources=3, seconds=9, usable_seconds=7.25, ignored_range_rows=1,
                           by_cast={c: dict(sources=3 if c == 'unknown' else 0,
                                            usable_seconds=7.25 if c == 'unknown' else 0)
                                    for c in real.CASTS})
    assert path.read_bytes() == before
    assert real.range_time('37:24', 1) == 2244
    assert real.range_time('1:02:03', 1) == 3723


@pytest.mark.parametrize('start,end,reason', [
    ('-1', '2', 'invalid time'), ('nan', '2', 'invalid time'),
    ('0:60', '2', 'invalid time'), ('1:60:00', '2', 'invalid time'),
    ('end', '2', 'invalid time'), ('0', 'END', 'invalid time'),
    ('2', '2', 'start must precede end'), ('2', '1', 'start must precede end'),
    ('3', 'end', 'start outside source'), ('4', '9', 'start outside source')])
def test_invalid_range_locations_and_redaction(tmp_path, capsys, start, end, reason):
    approvals(tmp_path, [(VIDEOS[0], 'approve', 'user')])
    audio(tmp_path / '.local/audio' / (VIDEOS[0] + '.webm'), 3)
    range_table(tmp_path, [(VIDEOS[0], start, end, 'PRIVATE invented memo')])
    with pytest.raises(real.RangeError, match=f'real-ranges.tsv:2: {reason}'):
        real.discover_sources(tmp_path, real.run_directory(tmp_path))
    assert real.main(['sources', '--root', str(tmp_path)]) == 1
    error = capsys.readouterr().err
    assert 'real-ranges.tsv:2:' in error and reason in error and 'PRIVATE' not in error
    assert pleos.main(['prepare', '--root', str(tmp_path), '--real', 'v1']) == 1
    assert reason in capsys.readouterr().err


@pytest.mark.parametrize('ends', [('2', '3'), ('end', '3')])
def test_overlapping_ranges_fail_with_line(tmp_path, ends):
    approvals(tmp_path)
    range_table(tmp_path, [(VIDEOS[0], '0', ends[0], ''), (VIDEOS[0], '1', ends[1], '')])
    with pytest.raises(real.RangeError, match='real-ranges.tsv:3: overlapping ranges'):
        real.discover_sources(tmp_path, real.run_directory(tmp_path))


def test_range_columns_and_adjacent_ranges(tmp_path):
    approvals(tmp_path)
    path = range_table(tmp_path, [(VIDEOS[0], '0', '1', ''), (VIDEOS[0], '1', 'end', '')])
    rows, ignored = real.read_ranges(tmp_path, VIDEOS)
    assert len(rows[VIDEOS[0]]) == 2 and ignored == 0
    path.write_text('video_id\tstart\tend\n', encoding='utf-8')
    with pytest.raises(real.RangeError, match='real-ranges.tsv:1: invalid columns'):
        real.read_ranges(tmp_path, VIDEOS)


def test_range_chunks_restore_timing_blocks_stats_and_clips(tmp_path):
    video = VIDEOS[0]
    approvals(tmp_path, [(video, 'approve', 'user')])
    source = tmp_path / '.local/audio' / (video + '.webm')
    audio(source, 8)
    payload = b''.join(i.to_bytes(2, 'little') * 16000 for i in range(1, 9))
    with wave.open(str(source), 'wb') as output:
        output.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        output.writeframes(payload)
    range_table(tmp_path, [(video, '1.25', '2.5', 'invented first'),
                           (video, '4', '6.5', 'invented second')])
    directory = real.run_directory(tmp_path)
    entries = pleos.prepare_sources(tmp_path, directory, chunk_seconds=1)
    assert [(e['offset'], e['seconds'], e['range_index']) for e in entries] == [
        (1.25, 1, 0), (2.25, .25, 0), (4, 1, 1), (5, 1, 1), (6, .5, 1)]
    for entry in entries:
        start = round(entry['offset']*32000)
        end = start + round(entry['seconds']*32000)
        assert (pleos.base(tmp_path, directory) / 'pcm' / entry['name']).read_bytes() == payload[start:end]
    device = FakeDevice(entries)
    pleos.push(tmp_path, device, workspace=directory)
    pleos.run(tmp_path, device, workspace=directory)
    pleos.pull(tmp_path, device, workspace=directory)
    rows = pleos.collect(tmp_path, workspace=directory)
    assert [(r['start'], r['end']) for r in rows[0]['lines']] == [
        (1.25, 2.25), (2.25, 2.5), (4, 5), (5, 6), (6, 6.5)]
    blocks = real.build_blocks(tmp_path, directory)
    assert [(b['start'], b['end'], b['range_index'], b['closing']) for b in blocks] == [
        (1.25, 2.5, 0, 'range_end'), (4, 6.5, 1, 'range_end')]
    table = real.grid(real.transcriptions(tmp_path, directory))
    assert 'range_end 비율' in table and table.splitlines()[2].endswith('0.000 | 0.000 | 1.000 |')
    real.assign_split(tmp_path, [video])
    report = real.statistics_report(tmp_path, directory)
    assert report['by_source'] == {video: dict(usable_seconds=3.75, blocks=2)}
    assert 'invented' not in json.dumps(report)
    store = RealListenStore('v1', '가', root=tmp_path)
    assert {r['note'] for r in store.state()['rows']} == {'invented first', 'invented second'}
    for block in blocks:
        clip, start, end = store.media(block['id'])
        with wave.open(str(clip)) as wav:
            assert wav.getnframes() / wav.getframerate() == block['seconds']
        assert start == 0 and end == block['seconds']


def test_pcm_fractional_ranges_round_inward(tmp_path):
    approvals(tmp_path, [(VIDEOS[0], 'approve', 'user')])
    audio(tmp_path / '.local/audio' / (VIDEOS[0] + '.webm'), 2)
    range_table(tmp_path, [(VIDEOS[0], '.10001', '1.10001', '')])
    entries = pleos.prepare_sources(tmp_path, real.run_directory(tmp_path))
    assert len(entries) == 1
    assert entries[0]['offset'] >= .10001
    assert entries[0]['offset'] + entries[0]['seconds'] <= 1.10001


def test_range_replan_cleans_preparation_and_stale_raw(setup):
    root, directory, _ = setup
    (directory / 'asr.jsonl').unlink()
    entries = pleos.prepare_sources(root, directory, 1)
    folder = pleos.base(root, directory)
    legacy = pleos.read_json(folder / 'plan.json')
    for row in legacy.values():
        del row['ranges']
        for entry in row['entries']:
            del entry['range_index']
    pleos.write_json(folder / 'plan.json', legacy)
    stale = folder / 'pcm/obsolete.pcm'
    stale.write_bytes(b'old')
    raw = folder / 'raw/pleos/failed.tsv'
    pleos.atomic(raw, b'bad-header\n')
    range_table(root, [(VIDEOS[0], '.5', '1.5', 'invented')])
    updated = pleos.prepare_sources(root, directory, 1)
    assert updated != entries and not stale.exists() and not raw.exists()
    assert not (directory / 'blocks.jsonl').exists()
    assert [(e['offset'], e['seconds']) for e in updated if e['source_id'] == f'yt:{VIDEOS[0]}@0'] == [(.5, 1)]
    assert len(list((folder / 'pcm').glob('*.pcm'))) == len(updated)
    before = (folder / 'plan.json').read_bytes()
    assert pleos.prepare_sources(root, directory, 1) == updated
    assert (folder / 'plan.json').read_bytes() == before


@pytest.mark.parametrize('protection', ['part', 'asr', 'judgment', 'raw'])
def test_range_replan_requires_no_results_anywhere_in_run(setup, protection):
    root, directory, blocks = setup
    (directory / 'asr.jsonl').unlink()
    entries = pleos.prepare_sources(root, directory, 1)
    folder = pleos.base(root, directory)
    before = (folder / 'plan.json').read_bytes()
    if protection == 'part':
        pleos.write_json(folder / 'parts' / (entries[-1]['name']+'.json'), asr())
    elif protection == 'asr':
        real.write_objects(directory / 'asr.jsonl', [asr(VIDEOS[-1])])
    elif protection == 'judgment':
        put(directory, '가', [judgment(blocks[-1])])
    else:
        pleos.atomic(folder / 'raw/pleos/success.tsv',
                     'kind\treceived_ms\taudio_ms\ttext\tmetadata\nfinal\t1\t100\tinvented\t\n')
    range_table(root, [(VIDEOS[0], '.5', '1.5', '')])
    with pytest.raises(ValueError, match='ranges changed; choose a new run'):
        pleos.prepare_sources(root, directory, 1)
    assert (folder / 'plan.json').read_bytes() == before
    assert len(list((folder / 'pcm').glob('*.pcm'))) == len(entries)


def test_blocks_reject_lines_crossing_or_outside_range():
    for start, end in [(0, 2), (2, 3)]:
        row = asr(lines=[dict(start=start, end=end, text='invented')])
        row['ranges'] = [dict(start=0, end=1, note=''), dict(start=3, end=4, note='')]
        with pytest.raises(ValueError, match='range boundary'):
            real.make_blocks([row])


def test_clip_padding_is_bounded_in_original_time_and_cache_changes(tmp_path, monkeypatch):
    video = VIDEOS[0]
    approvals(tmp_path, [(video, 'approve', 'user')])
    audio(tmp_path / '.local/audio' / (video + '.webm'), 10)
    range_table(tmp_path, [(video, '2', '8', 'invented approval')])
    directory = real.run_directory(tmp_path)
    real.discover_sources(tmp_path, directory)
    real.write_objects(directory / 'asr.jsonl', [asr(seconds=10, lines=[
        dict(start=2.5, end=3, text='invented A'), dict(start=7, end=7.5, text='invented B')])])
    blocks = real.build_blocks(tmp_path, directory, silence=1)
    real.assign_split(tmp_path, [video])
    calls = []
    def fake_cut(source, destination, start, end):
        calls.append((start, end))
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(b'synthetic clip placeholder')
    monkeypatch.setattr('tools.listen.clips.cut', fake_cut)
    store = RealListenStore('v1', '가', root=tmp_path)
    paths = [store.media(b['id'])[0] for b in blocks]
    assert calls == [(2, 5), (5, 8)]
    store.media(blocks[0]['id'])
    assert len(calls) == 2
    range_table(tmp_path, [(video, '2.25', '7.75', 'invented approval')])
    real.discover_sources(tmp_path, directory)
    new_store = RealListenStore('v1', '가', root=tmp_path)
    assert new_store.media(blocks[0]['id'])[0] != paths[0]
    assert calls[-1] == (2.25, 5)


def test_real_chunk_cap_and_serial_argument(setup, monkeypatch):
    root, directory, _ = setup
    (directory / 'asr.jsonl').unlink()
    with pytest.raises(ValueError, match='90'):
        pleos.prepare_sources(root, directory, 90.5)
    entries = pleos.prepare_sources(root, directory)
    device = FakeDevice(entries)
    serials = []
    def fake_adb(args, serial, executable, **kwargs):
        serials.append(serial)
        return device(args, **kwargs)
    monkeypatch.setattr(pleos, 'adb', fake_adb)
    assert pleos.main(['push', '--root', str(root), '--real', 'v1', '--serial', 'chosen-device']) == 0
    assert serials and set(serials) == {'chosen-device'}


def test_split_preserves_existing_bytes_when_appending(tmp_path):
    path = tmp_path / 'data/real-split.tsv'
    path.parent.mkdir(parents=True)
    original = f'video_id\tsplit\r\n{VIDEOS[0]}\tdev\r\n'.encode()
    path.write_bytes(original)
    real.assign_split(tmp_path, VIDEOS[:2])
    assert path.read_bytes().startswith(original)


def cast_table(root, rows):
    path = root / 'data/real-cast.tsv'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('video_id\tcast\tby\tnote\n' + ''.join(
        f'{video}\t{cast}\tuser\tinvented metadata\n' for video, cast in rows), encoding='utf-8-sig')
    return path


def channel_table(root, rows):
    path = root / 'data/sources.tsv'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('video_id\tchannel_id\ttitle\n' + ''.join(
        f'{video}\t{channel}\tinvented metadata\n' for video, channel in rows), encoding='utf-8-sig')


def test_cast_table_absent_and_all_values(tmp_path):
    assert real.read_cast(tmp_path) == {}
    videos = VIDEOS + ['defghijklmn', 'efghijklmno']
    cast_table(tmp_path, zip(videos, real.CASTS))
    assert real.read_cast(tmp_path) == dict(zip(videos, real.CASTS))


@pytest.mark.parametrize('body,line,reason', [
    ('video_id\tcast\tby\n', 1, 'invalid columns'),
    ('video_id\tcast\tby\tnote\nabcdefghijk\tprivate-invalid\tuser\tprivate note\n', 2, 'invalid cast'),
    ('video_id\tcast\tby\tnote\nabcdefghijk\t\tuser\tprivate note\n', 2, 'invalid cast'),
    ('video_id\tcast\tby\tnote\nabcdefghijk\tchild_family\tuser\tprivate note\n'
     'abcdefghijk\tfriends\tuser\tprivate note\n', 3, 'duplicate video id'),
    ('video_id\tcast\tby\tnote\ninvalid\tunknown\tuser\tprivate note\n', 2, 'invalid video id'),
    ('video_id\tcast\tby\tnote\nabcdefghijk\tunknown\tuser\n', 2, 'invalid columns'),
    ('video_id\tcast\tby\tnote\nabcdefghijk\tunknown\tuser\tprivate note\textra\n', 2, 'invalid columns'),
])
def test_cast_table_errors_with_safe_cli_locations(tmp_path, capsys, body, line, reason):
    path = cast_table(tmp_path, [])
    path.write_text(body, encoding='utf-8')
    expected = f'real-cast.tsv:{line}: {reason}'
    with pytest.raises(real.CastError) as error:
        real.read_cast(tmp_path)
    assert str(error.value) == expected
    assert real.main(['sources', '--root', str(tmp_path)]) == 1
    captured = capsys.readouterr()
    assert captured.err == expected + '\n' and captured.out == ''


def test_cast_table_invalid_encoding(tmp_path):
    path = cast_table(tmp_path, [])
    path.write_bytes(b'\xff')
    with pytest.raises(real.CastError, match='real-cast.tsv:1: invalid encoding'):
        real.read_cast(tmp_path)


def test_sources_cast_channel_and_usable_summary(setup, capsys):
    root, directory, _ = setup
    cast_table(root, [(VIDEOS[0], 'child_family'), (VIDEOS[1], 'child_family')])
    channel_table(root, [(VIDEOS[0], 'family-a'), (VIDEOS[1], '')])
    (root / '.local/audio' / (VIDEOS[1] + '.webm')).unlink()
    (root / 'data/real-ranges.tsv').write_text(
        f'video_id\tstart\tend\tnote\n{VIDEOS[0]}\t0\t1\tinvented range\n', encoding='utf-8')
    assert real.main(['sources', '--root', str(root)]) == 0
    report = json.loads(capsys.readouterr().out)
    rows = json.loads((directory / 'sources.json').read_text(encoding='utf-8'))
    assert [(r['cast'], r['channel_id']) for r in rows] == [
        ('child_family', 'family-a'), ('child_family', ''), ('unknown', '')]
    assert report['by_cast']['child_family'] == dict(sources=2, usable_seconds=1)
    assert report['by_cast']['unknown'] == dict(sources=1, usable_seconds=2)
    assert report['by_cast']['couple'] == dict(sources=0, usable_seconds=0)
    assert 'invented' not in json.dumps(report)


def test_sources_without_channel_column(setup):
    root, directory, _ = setup
    (root / 'data/sources.tsv').write_text(f'video_id\ttitle\n{VIDEOS[0]}\tinvented\n', encoding='utf-8')
    assert all(r['channel_id'] == '' and r['cast'] == 'unknown'
               for r in real.discover_sources(root, directory))


def test_split_new_channel_hash_and_later_video_inherits(tmp_path):
    channel = 'invented-family'
    channel_table(tmp_path, [(v, channel) for v in VIDEOS])
    expected = 'test' if int(hashlib.sha256(channel.encode()).hexdigest(), 16) / 2**256 < .4 else 'dev'
    first = real.assign_split(tmp_path, VIDEOS[:2])
    assert first == dict.fromkeys(VIDEOS[:2], expected)
    expanded = real.assign_split(tmp_path, VIDEOS)
    assert expanded == dict.fromkeys(VIDEOS, expected)
    before = (tmp_path / 'data/real-split.tsv').read_bytes()
    assert real.assign_split(tmp_path, reversed(VIDEOS)) == expanded
    assert (tmp_path / 'data/real-split.tsv').read_bytes() == before


@pytest.mark.parametrize('split', ['dev', 'test'])
def test_split_inherits_channel_outside_requested_videos_without_rebalancing(tmp_path, split):
    channel_table(tmp_path, [(v, 'same-family') for v in VIDEOS])
    path = tmp_path / 'data/real-split.tsv'
    original = f'video_id\tsplit\r\n{VIDEOS[0]}\t{split}\r\n'.encode()
    path.write_bytes(original)
    result = real.assign_split(tmp_path, VIDEOS[1:])
    assert result == dict.fromkeys(VIDEOS, split)
    assert path.read_bytes().startswith(original)


def test_split_minimum_uses_whole_new_channel_groups(tmp_path):
    videos = VIDEOS + ['defghijklmn']
    channel_table(tmp_path, [(v, 'family-a' if i < 2 else 'family-b') for i, v in enumerate(videos)])
    result = real.assign_split(tmp_path, videos)
    assert result[videos[0]] == result[videos[1]]
    assert result[videos[2]] == result[videos[3]]
    assert set(result.values()) == {'dev', 'test'}


@pytest.mark.parametrize('first', ['dev', 'test'])
def test_split_mixed_existing_channel_warns_and_preserves_bytes(tmp_path, capsys, first):
    channel_table(tmp_path, [(v, 'mixed-family') for v in VIDEOS])
    second = 'dev' if first == 'test' else 'test'
    path = tmp_path / 'data/real-split.tsv'
    original = f'video_id\tsplit\r\n{VIDEOS[1]}\t{first}\r\n{VIDEOS[0]}\t{second}'.encode()
    path.write_bytes(original)
    # Warn even on a no-op, without reading or rewriting any local judgments.
    real.assign_split(tmp_path, VIDEOS[:2])
    assert path.read_bytes() == original
    expected_warning = f'경고: 채널 분할 혼재 mixed-family {VIDEOS[0]} {VIDEOS[1]}\n'
    assert capsys.readouterr().err == expected_warning
    result = real.assign_split(tmp_path, VIDEOS[2:])
    assert result == {VIDEOS[1]: first, VIDEOS[0]: second, VIDEOS[2]: first}
    assert path.read_bytes() == original + f'\n{VIDEOS[2]}\t{first}\n'.encode()
    assert capsys.readouterr().err == expected_warning


@pytest.fixture
def cast_setup(setup):
    root, directory, blocks = setup
    # Make all three casts observable in dev, then add an unknown test source.
    path = root / 'data/real-split.tsv'
    path.write_text('video_id\tsplit\n' + ''.join(f'{v}\tdev\n' for v in VIDEOS)
                    + 'defghijklmn\ttest\n', encoding='utf-8')
    cast_table(root, [(VIDEOS[0], 'child_family'), (VIDEOS[1], 'friends')])
    extra = real.make_blocks([asr('defghijklmn')])
    real.write_objects(directory / 'blocks.jsonl', blocks + extra)
    for name in ('가', '나'):
        put(directory, name, [judgment(b, name=name, keep='none' if i == 1 else 'keep')
                              for i, b in enumerate(blocks + extra)])
    return root, directory, blocks + extra


@pytest.mark.parametrize('casts,indices', [(None, [0, 1, 2]), (['child_family'], [0]),
                                         (['unknown'], [2]), (['child_family', 'friends'], [0, 1]),
                                         (['child_family', 'child_family'], [0]),
                                         (['couple'], [])])
def test_cast_filters_labels_stats_agree_and_export(cast_setup, capsys, casts, indices):
    root, directory, blocks = cast_setup
    flags = [flag for cast in casts or [] for flag in ('--cast', cast)]
    expected = {blocks[i]['id'] for i in indices}
    selected, labels = real.load_labels(root, directory, cast=casts)
    assert {b['id'] for b in selected} == set(labels['가']) == expected
    report = real.statistics_report(root, directory, cast=casts)
    assert report['selected_blocks'] == len(indices)
    assert report['annotators']['가']['judgments'] == len(indices)
    # All-cast counts remain independent of the requested cast; test labels stay locked.
    for cast, total, selected_count, keep in [('child_family', 1, 1, {'keep': 1}),
                                               ('friends', 1, 1, {'none': 1}),
                                               ('unknown', 2, 1, {'keep': 1})]:
        group = report['by_cast'][cast]
        assert group['blocks'] == total and group['selected_blocks'] == selected_count
        assert group['annotators']['가']['keep'] == keep
    assert report['splits']['test']['judgments'] is None
    assert '만든 말' not in json.dumps(report, ensure_ascii=False)
    assert '지어낸 메모' not in json.dumps(report, ensure_ascii=False)
    assert real.main(['stats', '--root', str(root), *flags]) == 0
    assert json.loads(capsys.readouterr().out)['selected_blocks'] == len(indices)
    assert real.main(['agree', '가', '나', '--root', str(root), *flags]) == 0
    agreement = json.loads(capsys.readouterr().out)
    assert agreement['distributions']['가']['judgments'] == len(indices)
    if indices:
        assert agreement['including_unsure']['n'] == len(indices)
    assert real.main(['export', '--root', str(root), *flags]) == 0
    assert json.loads(capsys.readouterr().out)['consensus'] == len(indices)
    for file in ('annotators/가.jsonl', 'annotators/나.jsonl', 'consensus.jsonl'):
        rows = real.read_objects(directory / 'export/dev' / file)
        assert {r['id'] for r in rows} == expected
        assert {r['cast'] for r in rows} == {selected[i]['cast'] for i in range(len(selected))}
        assert all('comment' not in r for r in rows)
    manifest = json.loads((directory / 'export/dev/manifest.json').read_text(encoding='utf-8'))
    assert manifest['cast'] == (sorted(set(casts)) if casts is not None else None)
    assert not (directory / 'test-access.log').exists()


@pytest.mark.parametrize('casts,expected', [([], 3), (['child_family'], 1),
                                           (['child_family', 'friends'], 2), (['couple'], 0)])
def test_grid_cast_cli_filters_sources(cast_setup, capsys, casts, expected):
    root, _, _ = cast_setup
    flags = [flag for cast in casts for flag in ('--cast', cast)]
    assert real.main(['grid', '--root', str(root), *flags]) == 0
    table = capsys.readouterr().out
    assert len(table.splitlines()) == 11 and '만든 말' not in table
    assert all(int(row.split('|')[3].strip()) == expected for row in table.splitlines()[2:])


@pytest.mark.parametrize('command', ['stats', 'agree', 'export'])
@pytest.mark.parametrize('split', ['test', 'all'])
def test_cast_never_bypasses_test_gate_even_empty(cast_setup, monkeypatch, capsys, command, split):
    root, directory, _ = cast_setup
    def forbidden(*args, **kwargs):
        pytest.fail('locked command touched judgments')
    monkeypatch.setattr(real, '_read_judgments', forbidden)
    args = [command, '--root', str(root), '--split', split, '--cast', 'couple']
    assert real.main(args) == 1
    assert 'ValueError' in capsys.readouterr().err
    assert not (directory / 'test-access.log').exists()
    with pytest.raises(ValueError, match='test 잠김'):
        real.load_labels(root, directory, split=split, cast=['couple'])


@pytest.mark.parametrize('command', ['stats', 'agree', 'export'])
@pytest.mark.parametrize('cast,count', [('unknown', 1), ('couple', 0)])
def test_open_test_cast_logs_before_read_and_does_not_persist(cast_setup, monkeypatch, capsys, command, cast, count):
    root, directory, _ = cast_setup
    original = real._read_judgments
    def logged(*args, **kwargs):
        log = (directory / 'test-access.log').read_text(encoding='utf-8')
        assert f'\t{command}\tsplit=test\n' in log
        return original(*args, **kwargs)
    monkeypatch.setattr(real, '_read_judgments', logged)
    assert real.main(['open-test', command, '--root', str(root), '--cast', cast]) == 0
    report = json.loads(capsys.readouterr().out)
    if command == 'stats':
        assert report['selected_blocks'] == count
        assert report['by_cast']['unknown']['annotators']['가']['judgments'] == 1
    elif command == 'agree':
        assert report['distributions']['가']['judgments'] == count
    else:
        assert report['consensus'] == count
        assert all(r['cast'] == cast for r in real.read_objects(directory / 'export/test/consensus.jsonl'))
    assert len((directory / 'test-access.log').read_text().splitlines()) == 1
    with pytest.raises(ValueError, match='test 잠김'):
        real.load_labels(root, directory, split='test', cast=['unknown'])


def test_cast_filters_before_label_validation_but_stats_reads_all_authorized_casts(cast_setup):
    root, directory, blocks = cast_setup
    rows = [judgment(b) for b in blocks]
    rows[1]['keep'] = 'invalid-other-dev-cast'
    rows[-1]['keep'] = 'invalid-test-label'
    put(directory, '가', rows)
    selected, labels = real.load_labels(root, directory, cast=['child_family'])
    assert len(selected) == len(labels['가']) == 1
    with pytest.raises(ValueError, match='판정 오류'):
        real.statistics_report(root, directory, cast=['child_family'])
    rows[1] = judgment(blocks[1])
    put(directory, '가', rows)
    assert real.statistics_report(root, directory, cast=['child_family'])['selected_blocks'] == 1
