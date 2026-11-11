import json

import pytest

from demo import compare as c
from demo.data import atomic_text, jsonl


def save(path, records):
    atomic_text(path, jsonl(records))
    return path


def test_hand_computed_metrics():
    score = c.metrics(['story', 'moment', 'none', 'none'], ['story', 'none', 'moment', 'none'])
    assert score['confusion'] == [[1, 0, 0], [0, 0, 1], [0, 1, 1]]
    assert score['accuracy'] == .5
    assert score['kappa'] == pytest.approx(.2)
    assert score['keep_recall'] == score['keep_precision'] == .5
    assert score['story_recall'] == score['story_precision'] == 1


def test_undefined_scores():
    assert c.metrics([], [])['accuracy'] is None
    score = c.metrics(['none'], ['none'])
    assert score['accuracy'] == 1 and score['kappa'] is None
    assert score['story_precision'] is None and score['keep_recall'] is None


def test_paired_counts_exact_mcnemar_and_bootstrap():
    pair = c.paired(['story'] * 4, ['story', 'story', 'none', 'none'], ['story', 'none', 'story', 'none'], 50)
    assert [pair[k] for k in ('both', 'only_a', 'only_b', 'neither')] == [1, 1, 1, 1]
    assert pair['p'] == 1 and pair['delta'] == 0
    assert c.paired(['story'] * 6, ['none'] * 6, ['story'] * 6, 50)['p'] == .03125
    result = c.bootstrap(['story', 'none'], ['story', 'none'], 100)
    assert result['accuracy']['low'] == result['accuracy']['high'] == 1
    assert result == c.bootstrap(['story', 'none'], ['story', 'none'], 100)
    assert c.bootstrap([], [], 10)['accuracy']['valid'] == 0


def test_report_last_answer_unsure_subsets_and_people(tmp_path):
    gold = save(tmp_path / 'a.jsonl', [dict(id='s@0', verdict='none'), dict(id='s@0', verdict='story', comment='PRIVATE_SENTINEL'),
                                    dict(id='s@1', verdict='moment'), dict(id='s@2', verdict='none'), dict(id='s@3', verdict='unsure')])
    human = save(tmp_path / 'b.jsonl', [dict(id=f's@{i}', verdict=v) for i, v in enumerate(['story', 'none', 'none', 'unsure'])])
    paths = []
    for arm, values in [('text', ['story', 'none', 'moment']), ('acoustic', ['story', 'moment', 'none'])]:
        paths.append(save(tmp_path / f'{arm}.jsonl', [dict(id=f's@{i}', arm=arm, verdict=v, seconds=2, model='fake', note='PRIVATE_SENTINEL') for i, v in enumerate(values)]))
    acoustic = save(tmp_path / 'events.jsonl', [dict(id='s@1', threshold=.3, block=dict(events={'laughter': .3}))])
    acoustic.with_suffix('.stats.json').write_text(json.dumps({'blocks': [dict(id='s@1', seconds=1.5)]}), encoding='utf-8')
    result = c.report([gold, human], paths, acoustic, repeats=30)
    assert '유효 정답 3개, unsure 제외 1개' in result
    assert 'PRIVATE_SENTINEL' not in result
    assert '| s@1 | moment | none |' in result
    assert '| A 틀림 | 2 | 0 |' in result
    assert '| 웃음·노래 감지 | 1 |' in result
    assert '| 정답 moment 또는 웃음·노래 감지 | 1 |' in result
    assert '사람 간 일치도' in result
    assert '묶음당 시간 2.000초' in result
    assert '묶음당 3.500초' in result


def test_glob_and_duplicate_arm_rejected(tmp_path):
    gold = save(tmp_path / 'gold.jsonl', [dict(id='s@0', verdict='none')])
    arm = save(tmp_path / 'text.jsonl', [dict(id='s@0', arm='text', verdict='none', seconds=0, model='fake')])
    assert c.expand([str(tmp_path / 'text*.jsonl')]) == [arm]
    with pytest.raises(ValueError, match='multiple'):
        c.report([gold], [arm, arm], repeats=10)
    with pytest.raises(ValueError, match='matched no'):
        c.expand([str(tmp_path / 'missing*')])
