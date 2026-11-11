"""Only structural empty ASR fixtures; no transcript snapshots."""
import copy

import pytest

from demo.data import atomic_text, jsonl, load_blocks
from demo.render import MARKERS, render_text_only, render_with_acoustic


def block():
    return dict(id='s@0', source='s', block=0, start=10., end=14., gap_before=None,
                utts=[7, 9, 10], turns=['', '', ''],
                turn_times=[dict(utt=7, start=10., end=11.), dict(utt=9, start=11.5, end=13.), dict(utt=10, start=12.5, end=14.)])


def test_text_format_and_purity():
    b = block()
    before = copy.deepcopy(b)
    assert render_text_only(b) == '<block id="0" seconds="4" gap_before="null">\n[+0] \n[+0.5] \n[-0.5] \n</block>'
    assert b == before


@pytest.mark.parametrize('event,label', MARKERS)
def test_marker_threshold_inclusive(event, label):
    b = block()
    a = dict(id=b['id'], threshold=.3, turns=[dict(utt=7, speaker='A', events={event: .3})],
             block=dict(n_speakers=3, events=dict(music=.1, singing=.7, laughter=.4), overlap_share=.2))
    before = copy.deepcopy(a)
    text = render_with_acoustic(b, a)
    assert f'[화자A]  ({label})' in text
    assert 'speakers="3" music="0.100" singing="0.700" laughter="0.400" overlap="0.200"' in text
    assert '[화자?]' in text
    assert a == before
    a['turns'][0]['events'][event] = .2999
    assert f'({label})' not in render_with_acoustic(b, a)
    a['threshold'] = .2
    assert f'({label})' in render_with_acoustic(b, a)


def test_empty_acoustic_and_missing_timing():
    b = block()
    assert render_with_acoustic(b, {}) == render_text_only(b)
    assert 'speakers="0"' in render_with_acoustic(b, {'id': b['id']})
    with pytest.raises(ValueError, match='id mismatch'):
        render_with_acoustic(b, {'id': 'other'})
    del b['turn_times']
    with pytest.raises(ValueError, match='turn_times'):
        render_text_only(b)


def test_resolve_nonconsecutive_utts_and_order(tmp_path):
    b = block()
    timings = b.pop('turn_times')
    atomic_text(tmp_path / 'sample.jsonl', jsonl([b]))
    atomic_text(tmp_path / 'utterances' / 's.jsonl', jsonl([dict(t, source='s', text='') for t in timings]))
    rows = load_blocks(tmp_path / 'sample.jsonl', tmp_path / 'utterances')
    assert rows[0]['turn_times'] == timings
    assert rows[0]['turns'] == ['', '', '']
    b['utts'][0] = 99
    atomic_text(tmp_path / 'sample.jsonl', jsonl([b]))
    with pytest.raises(ValueError, match='resolve'):
        load_blocks(tmp_path / 'sample.jsonl', tmp_path / 'utterances')
