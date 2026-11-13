"""Invented blocks only; policy semantics and aggregate privacy."""
import json

import pytest

from eval.stitch import core as c, measure as m


def block(index, *, probability=.98, keep='keep', kind='story', chars=100, **changes):
    result = dict(id=f'invented#{index}', source='invented', start=index * 90, end=(index + 1) * 90,
                  closing='length', chars=chars, probability=probability, model_kind='story',
                  human=None if keep is None else dict(keep=keep, kind=kind if keep == 'keep' else '', policy=False))
    result.update(changes)
    return result


def test_runs_maximal_sorted_and_boundaries():
    rows = [block(0), block(1), block(2, closing='silence'), block(3),
            block(4, start=362, closing='range_end'), block(5), block(6, start=543),
            block(7, source='other')]
    runs = c.build_runs(list(reversed(rows)))
    assert [len(run) for run in runs] == [3, 2, 1, 1, 1]
    assert [[b['id'] for b in run] for run in runs] == [
        ['invented#0', 'invented#1', 'invented#2'], ['invented#3', 'invented#4'],
        ['invented#5'], ['invented#6'], ['invented#7']]
    assert c.build_runs([]) == []
    with pytest.raises(ValueError, match='duplicate'):
        c.build_runs([rows[0], rows[0]])


@pytest.mark.parametrize('closing', ['silence', 'range_end', 'manual', 'end'])
def test_non_length_ends_run(closing):
    assert len(c.build_runs([block(0, closing=closing), block(1)])) == 2


def test_range_boundary_and_overlapping_times_do_not_join():
    assert len(c.build_runs([block(0, range_index=0), block(1, range_index=1)])) == 2
    assert len(c.build_runs([block(0), block(1, start=89)])) == 2
    with pytest.raises(ValueError):
        c.build_runs([block(0, end=float('nan'))])


def test_hysteresis_immediate_predecessor_and_inclusive_threshold():
    run = [block(i, probability=p) for i, p in enumerate([.9, .957, .5, .49, .9, .957, .5])]
    assert c.decide(run, .5) == [False, True, True, False, False, True, True]
    assert c.decide(run) == [False, True, False, False, False, True, False]
    run[1]['human']['policy'] = True
    assert c.decide(run, .5)[1:3] == [True, True]  # offline grid ignores labels
    assert c.decide(run, .5, enforce_policy=True)[1:3] == [False, False]
    assert c.decide([block(0, probability=.5)], .3) == [False]  # run resets


def test_min_content_unicode_whitespace_and_hysteresis_reset():
    assert c.content_chars(' 가 나\t\nA\u3000B\u00a0') == 4
    run = [block(0, chars=40), block(1, chars=39), block(2, probability=.7)]
    assert c.decide(run, .5, 0) == [True, True, True]
    assert c.decide(run, .5, 40) == [True, False, False]
    assert c.decide([block(0, chars=0)], .5, 0) == [True]


def test_aggregate_holes_false_saves_kind_and_gate_cascade():
    run = [block(0, chars=30), block(1, probability=.4, kind='moment', model_kind='none'),
           block(2), block(3, probability=.6, keep='none', chars=20),
           block(4, probability=.6, keep='keep', chars=100)]
    report = c.aggregate([run, [block(6, keep='none')], [block(7, keep=None)]])
    assert report['current']['human_keep_dropped'] == dict(first=0, continuation=2)
    assert report['current']['holes'] == 1
    assert report['current']['false_saves'] == dict(first=1, continuation=0)
    cont = next(r for r in report['hysteresis'] if r['t_cont'] == .5)
    assert cont['false_saves'] == dict(first=1, continuation=1)
    assert cont['keep_recall'] == .75 and cont['saved_seconds'] == 540
    kinds = report['kind_consistency']
    assert kinds['adjacent_human_keep_pairs'] == 2
    assert kinds['human_different'] == kinds['model_different'] == kinds['both_different'] == 2
    gate = next(r for r in report['min_content'] if r['t_cont'] == .5 and r['min_chars'] == 40)
    assert gate['directly_below_minimum'] == dict(human_keep=1, human_none=1, unlabeled=0)
    assert gate['removed_vs_min0'] == dict(human_keep=2, human_none=1, unlabeled=0)
    assert report['runs']['continuation_share'] == 4 / 7
    assert report['runs']['seconds'] == dict(median=90, p90=378, max=450)
    assert report['counts']['unlabeled'] == 1


def test_empty_aggregate_and_policy_roundtrip():
    result = c.aggregate([])
    assert result['current']['keep_recall'] is None
    assert result['runs']['seconds']['median'] is None
    for threshold in c.CONT_THRESHOLDS:
        for minimum in c.MIN_CHARS:
            assert c.parse_policy(c.policy_name(threshold, minimum)) == (threshold, minimum)
    for invalid in ['../path', 'cont0.4_min40', 'cont0.5_min41']:
        with pytest.raises(ValueError):
            c.parse_policy(invalid)


def test_snapshot_and_report_explicitly_exclude_private_fields():
    row = block(0, text='PRIVATE_INVENTED_TEXT', comment='PRIVATE_INVENTED_COMMENT')
    result = json.dumps(c.snapshot([[row]])) + json.dumps(c.aggregate([[row]]))
    assert 'PRIVATE_INVENTED' not in result
    saved = c.snapshot([[row]])[0]['blocks'][0]
    assert set(saved) == {'id', 'probability', 'model_kind', 'human', 'decisions'}
    assert len(saved['decisions']) == 30


def test_gate_dev_only_unknown_skipped_unlabeled_retained(tmp_path, monkeypatch, capsys):
    from eval import real
    calls = []
    def gate(root, directory, **kwargs):
        calls.append((root, directory, kwargs))
        print('PRIVATE_GATE_DIAGNOSTIC')
        rows = [dict(block(i), cast=cast, lines=[{'text': ' 가 \t나 '}])
                for i, cast in enumerate(['friends', 'unknown', 'child_family'])]
        return rows, {'vizatae': {'invented#0': dict(keep='keep', kind='story', policy=['invented'])}}
    monkeypatch.setattr(real, 'load_labels', gate)
    rows, counts = m.load_dev(tmp_path)
    assert calls == [(tmp_path, real.run_directory(tmp_path, 'v1'), dict(annotators=['vizatae'], split='dev'))]
    assert len(rows) == 2 and rows[1]['human'] is None
    assert rows[0]['human']['policy'] is True and rows[0]['chars'] == 2
    assert counts == dict(unknown_cast_skipped=1)
    assert 'PRIVATE' not in ''.join(capsys.readouterr())


def test_int8_parity_overlap_only_and_main_sanitizes(monkeypatch, capsys):
    report = m.parity([block(0, probability=.9), block(1)], [
        dict(dataset='dev', id='invented#0', int8=.9),
        dict(dataset='synthetic_val', id='invented#1', int8=0)])
    assert report['overlap'] == 1 and report['max_abs_diff'] == 0 and report['passed']
    assert not m.parity([], [])['passed']
    def fail():
        raise ValueError('PRIVATE_INVENTED_TRANSCRIPT')
    monkeypatch.setattr(m, 'execute', fail)
    assert m.main([]) == 2
    assert 'PRIVATE' not in ''.join(capsys.readouterr())
