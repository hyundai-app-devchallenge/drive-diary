from collections import Counter
import json

import pytest

from tools.listen import sample as s
from tools.listen.storage import encode_lines, read_jsonl


def block(video, number, single=False):
    return dict(id=f'yt:{video}@{number}', source=f'yt:{video}', split='train', block=number,
                start=number * 100 + 2, end=number * 100 + 42, gap_before=None,
                utts=[number] if single else [number, number+1],
                turns=['합성 발화'] if single else ['합성 발화', '합성 응답'], prefix=[],
                features=dict(n_turns=1 if single else 2, seconds=40, speech_share=number / 12,
                              median_gap=12-number, gap_before=600), closed_by='length')


def write(root, directory, rows):
    path = root / f'.local/{directory}/train.jsonl'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encode_lines(rows))


@pytest.fixture
def sample_root(tmp_path):
    approved = [block('00000000001', i) for i in range(6)]
    pre = [block(f'{v:011d}', i, single=i < 3) for v in range(2, 22) for i in range(12)]
    write(tmp_path, 'blocks', approved + [block('00000000099', 0)])
    write(tmp_path, 'blocks-pre', approved + pre + [block('00000000098', 0)])
    audio = tmp_path / '.local/audio'
    audio.mkdir()
    for v in range(1, 22):
        (audio / f'{v:011d}.webm').write_bytes(b'0123456789')
    return tmp_path


def test_deterministic_full_records_quotas_and_missing_audio(sample_root):
    out = sample_root / '.local/demo/sample.jsonl'
    report = s.generate(sample_root, out=out)
    before = out.read_bytes(), out.with_suffix('.meta.json').read_bytes()
    assert s.generate(sample_root, out=out) == report
    assert before == (out.read_bytes(), out.with_suffix('.meta.json').read_bytes())
    rows = read_jsonl(out)
    assert len(rows) == len({r['id'] for r in rows}) == 60
    assert report['origins'] == {'approved': 6, 'prescreened': 54}
    assert report['single_turn'] >= 5
    approved = [r for r in rows if r['origin'] == 'approved']
    assert {r['id'] for r in approved} == {f'yt:00000000001@{i}' for i in range(6)}
    assert max(Counter(r['source'] for r in rows if r['origin'] == 'prescreened').values()) <= 4
    for r in rows:
        assert r['audio'] == dict(path=f'.local/audio/{r["source"][3:]}.webm', start=r['start'], end=r['end'])
        assert {k:v for k,v in r.items() if k not in {'audio','origin'}} == block(r['source'][3:], r['block'], len(r['turns']) == 1)
    meta = json.loads(out.with_suffix('.meta.json').read_text(encoding='utf8'))
    assert meta['demo_only'] is True and meta['seed'] == 1 and meta['n'] == 60
    assert meta['dense_shortfall'] == 0
    assert all('00000000099' not in r['id'] and '00000000098' not in r['id'] for r in rows)
    # Dense half (27) follows the random half (27); exact top-half intersection.
    assert all(r['block'] >= 6 for r in rows[-27:])


def test_seed_changes_random_selection(sample_root):
    a, b = sample_root / 'a.jsonl', sample_root / 'b.jsonl'
    s.generate(sample_root, out=a, seed=1)
    s.generate(sample_root, out=b, seed=2)
    assert a.read_bytes() != b.read_bytes()


def test_keep_existing_only_appends_approved_and_is_idempotent(sample_root):
    out = sample_root / '.local/demo/sample.jsonl'
    s.generate(sample_root, out=out)
    previous = read_jsonl(out)
    # Existing prescreened is now approved: preserve its original record.
    promotion = dict(previous[10])
    promotion.pop('audio'); promotion.pop('origin')
    new = block('00000000001', 100)
    write(sample_root, 'blocks', [block('00000000001', i) for i in range(6)] + [promotion, new])
    s.generate(sample_root, out=out, keep_existing=True)
    rows = read_jsonl(out)
    assert rows[:60] == previous and len(rows) == 61
    assert rows[-1]['id'] == new['id'] and rows[-1]['origin'] == 'approved'
    snapshot = out.read_bytes(), out.with_suffix('.meta.json').read_bytes()
    s.generate(sample_root, out=out, keep_existing=True)
    assert snapshot == (out.read_bytes(), out.with_suffix('.meta.json').read_bytes())


def test_missing_existing_audio_fails_without_changing_sample(sample_root):
    out = sample_root / '.local/demo/sample.jsonl'
    s.generate(sample_root, out=out)
    previous = out.read_bytes()
    (sample_root / '.local/audio/00000000001.webm').unlink()
    with pytest.raises(ValueError):
        s.generate(sample_root, out=out, keep_existing=True)
    assert out.read_bytes() == previous


def test_insufficient_single_turns_fails_without_output(sample_root):
    write(sample_root, 'blocks-pre', [])
    with pytest.raises(ValueError):
        s.generate(sample_root)
    assert not (sample_root / '.local/demo/sample.jsonl').exists()
