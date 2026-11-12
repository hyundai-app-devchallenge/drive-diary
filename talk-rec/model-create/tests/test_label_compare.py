import json

import pytest

from pipeline import label_compare as c
from pipeline.label import jsonl


def label(index, story, **extra):
    return dict(id=f's#{index}', story=story, kind=['recall'] if story >= 2 else [],
                exclude=[], skip=False, labeler='llm:fake:m:high', note='근거', **extra)


def test_hand_computed_kappas_and_per_flag_agreement(tmp_path, capsys):
    # A=[0,1,2,3], B=[0,3,2,1]. Both have uniform story marginals.
    # Binary agreement=.5, chance=.5 => Cohen kappa=(.5-.5)/(1-.5)=0.
    # Quadratic observed disagreement=(0+4+0+4)/4=2.
    # Independent uniform marginals give E[(A-B)^2]=2.5 => weighted kappa=1-2/2.5=.2.
    # (Dividing both disagreements by 3^2 gives the same weighted kappa.)
    a = [label(i, s) for i, s in enumerate([0, 1, 2, 3])]
    b = [label(i, s) for i, s in enumerate([0, 3, 2, 1])]
    b[1]['exclude'] = ['sensitive']
    b[0]['skip'] = True
    b[2]['note'], b[2]['labeler'] = '다른 근거', 'llm:other:m:high'
    for name, rows in [('a', a), ('b', b)]:
        directory = tmp_path / name
        directory.mkdir()
        (directory / 's.jsonl').write_text(jsonl(rows), encoding='utf-8')
        (directory / 'unclear.jsonl').write_text(jsonl(rows[:1]), encoding='utf-8')
        (directory / 'run.jsonl').write_text('{"chunk_id":"c"}\n', encoding='utf-8')
    assert c.main([str(tmp_path / 'a'), str(tmp_path / 'b')]) == 0
    result = json.loads(capsys.readouterr().out)
    assert result['shared_ids'] == 4
    assert result['exact_agreement'] == .25
    assert result['story_exact_agreement'] == .5
    assert result['binary_story_agreement'] == .5
    assert result['binary_story_kappa'] == 0
    assert result['story_quadratic_weighted_kappa'] == pytest.approx(.2)
    assert result['kind']['recall'] == .5
    assert result['kind']['moment'] == 1
    assert result['exclude']['sensitive'] == .75
    assert result['exclude']['optout'] == 1
    assert result['skip_agreement'] == .75
    assert result['story_difference_ge_2'] == ['s#1', 's#3']


def test_shared_only_set_order_and_self_agreement():
    a = {r['id']: r for r in [label(0, 0), label(1, 2), label(2, 3)]}
    a['s#1']['kind'] = ['recall', 'feeling']
    b = {key: dict(row) for key, row in a.items()}
    b['s#1']['kind'] = ['feeling', 'recall']
    b.pop('s#2')
    b['s#9'] = label(9, 1)
    result = c.compare(a, b)
    assert result['shared_ids'] == 2
    assert result['only_a'] == result['only_b'] == 1
    assert result['exact_agreement'] == 1
    assert result['binary_story_kappa'] == 1
    assert result['story_quadratic_weighted_kappa'] == 1
    assert c.compare(a, a)['exact_agreement'] == 1


def test_empty_and_constant_kappa_defined_as_null():
    empty = c.compare({}, {})
    assert empty['exact_agreement'] is None
    assert empty['binary_story_kappa'] is None
    a = {'s#0': label(0, 1)}
    result = c.compare(a, a)
    assert result['exact_agreement'] == 1
    assert result['binary_story_kappa'] is None
    assert result['story_quadratic_weighted_kappa'] is None


def test_duplicate_invalid_missing_directory(tmp_path, capsys):
    rows = [label(0, 1)] * 2
    (tmp_path / 's.jsonl').write_text(jsonl(rows), encoding='utf-8')
    with pytest.raises(ValueError, match='duplicate'):
        c.load_labels(tmp_path)
    (tmp_path / 's.jsonl').write_text('{"text":"PRIVATE TRANSCRIPT"}\n', encoding='utf-8')
    assert c.main([str(tmp_path), str(tmp_path)]) == 2
    assert 'PRIVATE' not in capsys.readouterr().err
    assert c.main([str(tmp_path / 'absent'), str(tmp_path)]) == 2
