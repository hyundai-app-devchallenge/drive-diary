"""Invented ids/labels only: joins, denominators, compatibility and output privacy."""
from copy import deepcopy
import json

import pytest

from eval.stitch import value as v

POLICY = 'cont0.957_min0'


def block(index, label, judge=True, cast='child_family'):
    return dict(id=f'invented#{index}', cast=cast,
                human=None if label is None else dict(keep=label, kind='story' if label == 'keep' else '',
                                                      policy=False),
                decisions={POLICY: judge})


def story(ids, segments, keep=True, reason=''):
    return dict(ids=ids, segments=segments, kind='story', keep=keep, reason=reason,
                title='PRIVATE_INVENTED_TITLE', category='놀이', keywords=['종이배'])


def window(run, ids, stories, **changes):
    result = dict(run=run, window=1, window_count=1, segment_offset=0,
                  ids=ids, stories=stories, status='ok', policy=POLICY, model='fake-medium')
    result.update(changes)
    return result


@pytest.fixture
def inputs():
    records = [dict(run=0, blocks=[block(0, 'keep'), block(1, 'none'), block(2, 'keep', False),
                                  block(3, 'none', False), block(4, None, cast='adult_family')]),
               dict(run=1, blocks=[block(5, 'none', cast='friends'), block(6, 'keep', cast='couple')])]
    rows = [window(0, ['invented#0', 'invented#1', 'invented#4'],
                   [story(['invented#0', 'invented#1'], [1, 2], False, 'procedure'),
                    story(['invented#4'], [3])]),
            window(1, ['invented#5', 'invented#6'],
                   [story(['invented#5'], [1], False, 'not_family'), story(['invented#6'], [2])])]
    return records, rows


def test_block_inheritance_full_denominator_cast_and_reasons(inputs):
    report = v.evaluate(*inputs, POLICY)
    overall = report['overall']
    assert overall['blocks'] == 7 and overall['human_keep'] == 3 and overall['human_none'] == 3
    assert overall['judge_saved'] == dict(human_keep=dict(kept=1, hidden=1),
                                        human_none=dict(kept=0, hidden=2), unlabeled=dict(kept=1, hidden=0))
    assert overall['judge_alone'] == dict(saved_blocks=5, human_keep_saved=2, keep_recall=2 / 3, false_saves=2)
    assert overall['judge_and_llm'] == dict(saved_blocks=2, human_keep_saved=1, keep_recall=1 / 3, false_saves=0)
    assert overall['change']['false_saves_removed'] == 2
    assert overall['change']['human_keep_hidden'] == 1
    assert overall['change']['keep_recall_delta'] == pytest.approx(-1 / 3)
    assert overall['reason_by_human'] == dict(human_keep={'procedure': 1, '': 1},
                                            human_none={'procedure': 1, 'not_family': 1}, unlabeled={'': 1})
    assert report['stories'] == dict(keep=2, hide=2, reasons={'procedure': 1, 'not_family': 1})
    casts = report['by_cast']
    assert casts['child_family']['judge_alone']['keep_recall'] == .5
    assert casts['child_family']['judge_and_llm']['keep_recall'] == 0
    assert casts['friends']['judge_saved']['human_none']['hidden'] == 1
    assert casts['couple']['judge_and_llm']['keep_recall'] == 1
    assert casts['adult_family']['judge_and_llm']['keep_recall'] is None


def test_legacy_files_default_to_keep_and_missing_cast_is_reported(inputs):
    records, rows = inputs
    for record in records:
        for b in record['blocks']:
            del b['cast']
    for row in rows:
        for group in row['stories']:
            del group['keep'], group['reason']
    rows[0]['stories'][0]['kind'] = 'none'  # T60 had no value gate, including none.
    report = v.evaluate(records, rows, POLICY)
    assert report['coverage']['legacy_stories_assumed_keep'] == 4
    assert report['coverage']['cast_missing_blocks'] == 7
    assert report['by_cast'] == {}
    assert report['overall']['judge_alone'] == report['overall']['judge_and_llm']


@pytest.mark.parametrize('changes', [dict(keep=1), dict(keep='true'), dict(keep=None),
    dict(keep=False, reason=''), dict(keep=True, reason='negative'), dict(keep=False, reason='bad'),
    dict(keep=False, reason=None), dict(kind='none', keep=True, reason=''),
    dict(kind='none', keep=False, reason='negative')])
def test_invalid_new_value_contract_is_rejected(inputs, changes):
    records, rows = inputs
    rows[0]['stories'][0].update(changes)
    with pytest.raises(v.ValueInputError, match='keep/reason'):
        v.evaluate(records, rows, POLICY)


@pytest.mark.parametrize('field', ['keep', 'reason'])
def test_half_legacy_contract_is_rejected(inputs, field):
    records, rows = inputs
    del rows[0]['stories'][0][field]
    with pytest.raises(v.ValueInputError, match='keep/reason'):
        v.evaluate(records, rows, POLICY)


@pytest.mark.parametrize('mode', ['failed', 'missing', 'duplicate', 'unknown_run', 'wrong_ids',
    'story_ids', 'coverage', 'order', 'boolean_index', 'offset', 'count', 'policy', 'decision', 'duplicate_block'])
def test_incomplete_stale_or_ambiguous_joins_are_rejected(inputs, mode):
    records, rows = inputs
    if mode == 'failed':
        rows[0].update(status='failed', error='PRIVATE_INVENTED_ERROR')
    elif mode == 'missing':
        rows.pop()
    elif mode == 'duplicate':
        rows.append(deepcopy(rows[0]))
    elif mode == 'unknown_run':
        rows[0]['run'] = 100
    elif mode == 'wrong_ids':
        rows[0]['ids'][0] = 'invented#2'
    elif mode == 'story_ids':
        rows[0]['stories'][0]['ids'].reverse()
    elif mode == 'coverage':
        rows[0]['stories'].pop()
    elif mode == 'order':
        rows[0]['stories'].reverse()
    elif mode == 'boolean_index':
        rows[0]['stories'][0]['segments'][0] = True
    elif mode == 'offset':
        rows[0]['segment_offset'] = 12
    elif mode == 'count':
        rows[0]['window_count'] = 2
    elif mode == 'policy':
        rows[0]['policy'] = 'cont0.5_min40'
    elif mode == 'decision':
        records[0]['blocks'][0]['decisions'][POLICY] = 1
    elif mode == 'duplicate_block':
        records[0]['blocks'].append(deepcopy(records[0]['blocks'][0]))
    with pytest.raises(v.ValueInputError):
        v.evaluate(records, rows, POLICY)


def test_windows_with_dropped_blocks_and_window_local_indices():
    blocks = [block(i, 'keep', judge=i != 4) for i in range(14)]
    saved = [b['id'] for b in blocks if b['decisions'][POLICY]]
    rows = [window(0, saved[:12], [story(saved[:12], list(range(1, 13)))], window_count=2),
            window(0, saved[12:], [story(saved[12:], [1], False, 'negative')],
                   window=2, window_count=2, segment_offset=12)]
    report = v.evaluate([dict(run=0, blocks=blocks)], list(reversed(rows)), POLICY)
    assert report['coverage']['windows'] == 2
    assert report['overall']['judge_alone']['keep_recall'] == 13 / 14
    assert report['overall']['judge_and_llm']['keep_recall'] == 12 / 14


def write_inputs(tmp_path, inputs):
    records, rows = inputs
    directory = tmp_path / '.local/eval/stitch'
    directory.mkdir(parents=True)
    path = directory / f'split-{POLICY}-fake-medium.jsonl'
    (directory / 'runs.jsonl').write_text('\n'.join(map(json.dumps, records)), encoding='utf-8')
    path.write_text('\n'.join(map(json.dumps, rows)), encoding='utf-8')
    return path


def test_cli_and_json_contain_aggregates_only(tmp_path, inputs, monkeypatch, capsys):
    records, _ = inputs
    records[0]['blocks'][0]['comment'] = 'PRIVATE_INVENTED_COMMENT'
    path = write_inputs(tmp_path, inputs)
    execute = v.execute
    monkeypatch.setattr(v, 'execute', lambda path: execute(path, root=tmp_path))
    assert v.main([str(path)]) == 0
    stdout, stderr = capsys.readouterr()
    assert not stderr
    output = path.with_name(path.stem.replace('split-', 'value-', 1) + '.json')
    assert json.loads(stdout) == json.loads(output.read_text(encoding='utf-8'))
    for private in ('PRIVATE', 'invented#', '종이배', 'title', 'keywords'):
        assert private not in stdout + output.read_text(encoding='utf-8')


def test_failed_cli_is_clear_sanitized_and_does_not_write(tmp_path, inputs, monkeypatch, capsys):
    records, rows = inputs
    rows[0].update(status='failed', error='PRIVATE_INVENTED_ERROR')
    path = write_inputs(tmp_path, (records, rows))
    execute = v.execute
    monkeypatch.setattr(v, 'execute', lambda path: execute(path, root=tmp_path))
    assert v.main([str(path)]) == 2
    stdout, stderr = capsys.readouterr()
    assert not stdout and 'failed split windows' in stderr and 'PRIVATE' not in stderr
    assert not list(path.parent.glob('value-*.json'))
    path.write_text('{PRIVATE_INVENTED_JSON', encoding='utf-8')
    assert v.main([str(path)]) == 2
    assert 'PRIVATE' not in ''.join(capsys.readouterr())


def test_empty_split_when_judge_saves_nothing(tmp_path):
    path = write_inputs(tmp_path, ([dict(run=0, blocks=[block(0, 'keep', False)])], []))
    report = v.execute(path, root=tmp_path)
    assert report['coverage']['windows'] == 0
    assert report['overall']['judge_alone']['keep_recall'] == 0
    assert report['overall']['judge_and_llm']['keep_recall'] == 0
    assert v.evaluate([], [], POLICY)['overall']['judge_alone']['keep_recall'] is None
