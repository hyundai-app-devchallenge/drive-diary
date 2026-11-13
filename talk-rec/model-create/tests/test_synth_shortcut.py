"""Only invented speech/fixtures; never read local real transcripts."""
import json

import numpy as np
import pytest

from demo.trial.data import Example, LABELS, split_scenes
from demo.trial.models import shape
from synth.cards import sample_cards
from synth.io import write_json, write_jsonl
from synth.shortcut import (card_shapes, cross_validate, distribution, main,
                            real_shapes, synth_shapes)


def test_400_seed_91_cards_pass(tmp_path, capsys):
    path = tmp_path / 'cards.jsonl'
    write_jsonl(path, sample_cards(400, 91))
    examples, values, stats = card_shapes(path)
    assert np.asarray(values).shape == (400, 3)
    result = cross_validate(examples, values)
    assert result['cv_accuracy'] <= result['limit']
    assert result['majority'] == .6 and not result['fail']
    assert result['validation_observations'] == 400
    assert all(set(r.label for r in split_scenes(examples, seed)[1]) == {0, 1, 2} for seed in (43, 44))
    assert main(['--cards', str(path)]) == 0
    output = capsys.readouterr().out
    assert 'PASS' in output and '| none | seconds |' in output
    assert 'chars' not in output and 'family' in output


def make_blocks(path):
    blocks, labels = [], []
    for i in range(90):
        label = LABELS[i % 3]
        turns = ['창작전용합성모양검사'] * (2 if label == 'none' else 22)
        blocks.append(dict(id=f'synth:{i+1}@0', source=f'synth:{i+1}', turns=turns, start=0, end=len(turns)*3))
        labels.append(dict(id=blocks[-1]['id'], verdict=label))
    write_jsonl(path / 'blocks.jsonl', blocks)
    write_jsonl(path / 'labels.jsonl', labels)


def test_blocks_fail_and_repeated_directories_stay_grouped(tmp_path, capsys, monkeypatch):
    directory = tmp_path / 'synth'
    make_blocks(directory)
    rows, values, stats, skipped = synth_shapes([directory, directory, tmp_path / 'missing'])
    assert len(rows) == 180 and skipped == 1
    np.testing.assert_array_equal(values, shape([r.text for r in rows]))
    seen = []
    def checked_split(examples, seed):
        a, b = split_scenes(examples, seed)
        assert not {r.scene for r in a} & {r.scene for r in b}
        seen.append(seed)
        return a, b
    monkeypatch.setattr('synth.shortcut.split_scenes', checked_split)
    result = cross_validate(rows, values)
    assert len(seen) == 5 and result['cv_accuracy'] == 1. and result['fail']
    assert main(['--synth-dir', str(directory), '--synth-dir', str(directory)]) == 3
    output = capsys.readouterr().out
    assert 'FAIL' in output and 'ngram CV accuracy=' in output
    assert distribution(stats, 'none', 'n_turns') == '2.00 / 2.00 / 2.00 / 2.00'


def test_real_table_uses_humans_and_timestamp_span(tmp_path, capsys):
    directory = tmp_path / 'synth'
    make_blocks(directory)
    judge, asr = tmp_path / 'judge.json', tmp_path / 'asr.jsonl'
    write_json(judge, dict(ids=['private-identity'], human={'private-identity': 'none'}))
    write_jsonl(asr, [dict(id='private-identity', config='pleos', seconds=.01,
                         lines=[dict(start=10, end=90, text='창작전용비공개문장')])])
    assert real_shapes(judge, asr) == [dict(label='none', n_turns=1, chars=9, seconds=80)]
    assert main(['--synth-dir', str(directory), '--real-judge', str(judge), '--real-asr', str(asr)]) == 3
    output = capsys.readouterr().out
    assert '| 실제 |' in output and '80.00 / 80.00 / 80.00 / 80.00' in output
    assert '비공개' not in output and 'private-identity' not in output


def test_errors_are_sanitized_and_incomplete_inputs_rejected(tmp_path, capsys):
    malformed = tmp_path / 'cards.jsonl'
    malformed.write_text('{"비공개예외내용":', encoding='utf-8')
    assert main(['--cards', str(malformed)]) == 2
    output = capsys.readouterr()
    assert '비공개예외내용' not in output.out + output.err
    with pytest.raises(SystemExit):
        main(['--synth-dir', 'unused', '--real-judge', 'unused'])
    directory = tmp_path / 'synth'
    make_blocks(directory)
    labels = directory / 'labels.jsonl'
    labels.write_text('', encoding='utf-8')
    assert main(['--synth-dir', str(directory)]) == 2


def test_conflicting_labels_across_exports_rejected(tmp_path):
    a, b = tmp_path / 'a', tmp_path / 'b'
    make_blocks(a)
    make_blocks(b)
    path = b / 'labels.jsonl'
    rows = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
    rows[0]['verdict'] = 'story'
    write_jsonl(path, rows)
    examples, values, _, _ = synth_shapes([a, b])
    with pytest.raises(ValueError, match='conflicting'):
        cross_validate(examples, values)


@pytest.mark.parametrize('accuracy,fail', [(.7, False), (.8, True)])
def test_strict_ten_percentage_point_rule(accuracy, fail, monkeypatch):
    # Fixed held-out outcomes isolate the decision rule from model fitting.
    examples = [Example(str(i), i, '', 0 if i < 4 else 1 + i % 2) for i in range(10)]
    monkeypatch.setattr('synth.shortcut.split_scenes', lambda rows, seed: (rows, rows))
    gold = np.asarray([r.label != 0 for r in examples])
    prediction = gold.copy()
    prediction[:round((1-accuracy)*10)] ^= True
    class Model:
        def fit(self, x, y):
            pass
        def predict(self, x):
            return prediction
    monkeypatch.setattr('synth.shortcut.make_pipeline', lambda *args: Model())
    result = cross_validate(examples, np.ones((10, 3)))
    assert result['cv_accuracy'] == pytest.approx(accuracy)
    assert result['fail'] is fail
