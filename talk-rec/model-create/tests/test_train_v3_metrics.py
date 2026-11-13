import numpy as np
import pytest

from eval.v3.metrics import (aggregate, bootstrap_indices, choose_model, held_out,
                             select_threshold, selection_rule, values)
from train.v3.data import RealExample


def rows():
    return [RealExample(str(i), s, '창작', label, sec, 'child_family', False)
            for i, (s, label, sec) in enumerate([('a', 0, 10), ('a', 1, 20),
                                                ('b', 0, 30), ('b', 2, 40)])]


def test_threshold_constraint_and_ties():
    y = [0, 0, 1, 1, 2, 2, 1, 1, 2]
    p = [.1, .4, .3, .5, .6, .7, .8, .9, 1.]
    t = select_threshold(y, p)
    assert t == .5  # 6/7 >= .85, with perfect specificity
    assert select_threshold([0, 1], [.5, .5]) == .5
    assert select_threshold([0, 0], [.2, .8]) > .8
    with pytest.raises(ValueError):
        select_threshold([], [])


def test_loso_never_calibrates_on_held_source():
    rs = rows()
    p = [.4, .5, .1, .2]
    pred, folds = held_out(rs, p)
    assert folds['a'] == select_threshold([0, 2], [.1, .2])
    assert folds['b'] == select_threshold([0, 1], [.4, .5])
    assert pred == [True, True, False, False]
    altered = p[:2] + [.8, .9]
    _, other = held_out(rs, altered)
    assert other['b'] == folds['b']


def test_metrics_and_exposure_hand_calculation():
    m = values([0, 1, 0, 2], [.1, .8, .7, .2], [False, True, True, False],
               [0, 1, 2, 2], [10, 20, 30, 40], 200)
    assert m['keep_recall'] == m['none_specificity'] == m['keep_precision'] == m['accuracy'] == .5
    assert m['accuracy3'] == m['auroc'] == .75
    assert m['minutes_per_hour'] == 15.
    assert values([0], [0], [False], None, [10], 20, hard=True)['keep_recall'] is None


def test_cluster_bootstrap_repeats_whole_sources_and_exposure():
    rs = rows()
    for ix, draw in bootstrap_indices(rs, 100):
        assert len(ix) == 4 and len(draw) == 2
        assert sum(i < 2 for i in ix) in (0, 2, 4)
    run = dict(probability=[1.] * 4, prediction=[True] * 4, pred3=None)
    # Equal density: 30/60 and 70/140 => exactly 30 min/hour for every source draw.
    result = aggregate(rs, [run], dict(a=60., b=140.), bootstrap=100)
    assert result == aggregate(rs, [run], dict(a=60., b=140.), bootstrap=100)
    interval = result['metrics']['minutes_per_hour']['cluster_ci95']
    assert interval['low'] == interval['high'] == 30.
    block = result['metrics']['minutes_per_hour']['block_ci95']
    assert block['low'] < block['high']
    assert result['metrics']['keep_recall']['std'] == 0


def test_seed_sd_hard_auc_and_selection():
    rs = rows()
    a = dict(probability=[.1, .9, .1, .9], prediction=[False, True, False, True], pred3=[0, 1, 0, 2])
    b = dict(probability=[.9, .1, .9, .1], prediction=[True, False, True, False], pred3=[0, 1, 0, 2])
    result = aggregate(rs, [a, b], dict(a=100, b=100), bootstrap=30)
    assert result['metrics']['accuracy']['mean'] == .5
    assert result['metrics']['accuracy']['std'] == pytest.approx(np.sqrt(.5))
    hard = aggregate(rs, [a], dict(a=100, b=100), bootstrap=10, hard=True)
    assert hard['metrics']['auroc']['mean'] is None
    def interval(lo, hi):
        return dict(keep_recall=dict(cluster_ci95=dict(low=lo, high=hi)))
    assert choose_model(interval(.5, .8), interval(.8, 1)) == 'M0'
    assert choose_model(interval(.5, .8), interval(.81, 1)) == 'M1'
    assert 'M0를 선택' in selection_rule(interval(.5, .8), interval(.8, 1))
