"""Only invented content. Tests must never open .local real inputs."""
from copy import deepcopy
import json
from pathlib import Path

import pytest

from demo.trial.data import Example, input_text, split_scenes
from synth.curate import main as curate_main, strip_sound_text, strip_sounds
from train.v3.data import load_dev, stratified_subset


@pytest.mark.parametrize('text,expected', [
    ('푸하하! 허허 으앙 ㅋㅋ ㅎㅎ 키득키득', ''),
    ('(하하) [허허] {깔깔} …히히… ~크크~', ''),
    ('축하하 허허벌판 호호 불어', '축하하 허허벌판 호호 불어'),
    ('하 ㅋ ㅎ 앙', '하 ㅋ ㅎ 앙'),
    ('안녕 푸하하! 반가워', '안녕 반가워'),
    ('하하하냐 하하,다음 푸하하끝', '하하하냐 하하,다음 푸하하끝'),
    ('  하나\t둘  ', '하나 둘'),
])
def test_strip_whole_sound_tokens(text, expected):
    assert strip_sound_text(text) == expected


def test_strip_cli_preserves_metadata_and_write_jsonl_bytes(tmp_path):
    from synth.io import write_jsonl
    rows = [dict(card=1, why='창작', lines=[dict(t=0, dur=1, spk='a', text='허허!'),
                                            dict(t=1, dur=2, spk='b', text='축하하 하하')])]
    before = deepcopy(rows)
    result = strip_sounds(rows)
    assert rows == before
    assert result[0]['lines'] == [dict(t=1, dur=2, spk='b', text='축하하')]
    source, out, expected = (tmp_path / name for name in ('in.jsonl', 'out.jsonl', 'expected.jsonl'))
    write_jsonl(source, rows)
    write_jsonl(expected, result)
    assert curate_main(['strip-sounds', '--scenes', str(source), '--out', str(out)]) == 0
    assert out.read_bytes() == expected.read_bytes()


def examples():
    rows = [Example(f's{i}', i, '창작', i % 3) for i in range(300)]
    for i in range(0, 90, 2):
        rows[i].pair_id = rows[i+1].pair_id = f'p{i}'
    return rows


def test_subset_label_pair_proportions_and_reproducibility():
    rows = examples()
    chosen = stratified_subset(rows, 100)
    assert len(chosen) == 100
    assert [r.id for r in chosen] == [r.id for r in stratified_subset(rows, 100)]
    assert abs(sum(bool(r.pair_id) for r in chosen) - 30) <= 2
    assert all(abs(sum(r.label == label for r in chosen) - 100/3) <= 2 for label in range(3))
    ids = {r.id for r in chosen}
    for i in range(0, 90, 2):
        assert (f's{i}' in ids) == (f's{i+1}' in ids)
    a, b = split_scenes(chosen)
    assert not {r.scene for r in a} & {r.scene for r in b}
    assert not {r.pair_id for r in a if r.pair_id} & {r.pair_id for r in b if r.pair_id}
    with pytest.raises(ValueError):
        stratified_subset(rows, 301)


def fixture_real(tmp_path):
    from eval import real
    (tmp_path / 'data').mkdir()
    (tmp_path / 'data/real-split.tsv').write_text('video_id\tsplit\nabcdefghijk\tdev\nABCDEFGHIJK\tdev\nlmnopqrstuv\ttest\n', encoding='utf-8')
    (tmp_path / 'data/real-cast.tsv').write_text('video_id\tcast\tby\tnote\nabcdefghijk\tchild_family\tperson\t\nABCDEFGHIJK\tfriends\tperson\t\nlmnopqrstuv\tchild_family\tperson\t\n', encoding='utf-8')
    directory = real.run_directory(tmp_path)
    blocks, humans = [], []
    for video in ('abcdefghijk', 'ABCDEFGHIJK', 'lmnopqrstuv'):
        for i in range(3):
            identity = f'yt:{video}#{i}'
            blocks.append(dict(id=identity, source=f'yt:{video}', seconds=10.,
                               lines=[dict(start=0, end=10, text='PRIVATE_INVENTED_PHRASE')]))
            humans.append(dict(id=identity, keep='none' if i == 0 or video == 'ABCDEFGHIJK' else 'keep',
                kind='story' if i and video != 'ABCDEFGHIJK' else '', none_reason='', unsure=i == 1,
                policy=[], comment='PRIVATE_INVENTED_COMMENT', listened=True, labeler='human:vizatae', saved_at='2026-11-12'))
    real.write_objects(directory / 'blocks.jsonl', blocks)
    real.write_objects(real.judgment_path(directory, 'vizatae'), humans)
    (directory / 'sources.json').write_text(json.dumps([dict(id=v, usable_seconds=60.) for v in ('abcdefghijk', 'ABCDEFGHIJK', 'lmnopqrstuv')]), encoding='utf-8')
    return directory


def test_real_gate_and_shared_input(tmp_path):
    directory = fixture_real(tmp_path)
    rows, usable, counts = load_dev(tmp_path)
    assert len(rows) == 6 and counts['unknown_cast_blocks'] == 0
    assert all('lmnopqrstuv' not in r.id for r in rows)
    assert rows[0].text == input_text(['PRIVATE_INVENTED_PHRASE'])
    assert 'PRIVATE' not in repr(rows[0])
    assert not (directory / 'test-access.log').exists()
    with pytest.raises(ValueError):
        load_dev(tmp_path, split='test')
    assert not (directory / 'test-access.log').exists()
    test, _, _ = load_dev(tmp_path, split='test', open_test=True)
    assert len(test) == 3 and 'split=test' in (directory / 'test-access.log').read_text()
