from copy import deepcopy
from functools import partial
import re

import pytest

from common.schema import validate_block
from synth.cards import sample_cards
from synth.degrade import PROBABILITIES, degrade, export, reconstruct, to_block
from synth.generate import fake_scene
from synth.io import read_jsonl

# The original corruption-rule regressions explicitly exercise Whisper style.
degrade = partial(degrade, style='whisper')


def line(text='가나다 나는 지우에게 말을 해요', t=0, dur=2, spk='아이1', overlap=False):
    return dict(text=text, t=t, dur=dur, spk=spk, overlap=overlap)


def force(rule):
    return dict(enabled={k: k == rule for k in PROBABILITIES}, probabilities={rule: (1, 1, 1)})


@pytest.mark.parametrize('rule', list(PROBABILITIES))
def test_every_rule_has_independent_switch(rule):
    rows = [line('나는, 지우에게 가나다 말을 해요!', dur=3), line('여기요', t=3.4, dur=1, overlap=True)]
    off = {k: False for k in PROBABILITIES}
    assert degrade(rows, 2, 43, enabled=off) == [dict(t=r['t'], dur=r['dur'], text=r['text']) for r in rows]
    # Every rule can be called in isolation, without hidden prerequisites.
    assert degrade(rows, 1, 43, **force(rule))


def test_seeded_expected_substitution_drop_and_particles():
    assert degrade([line('가나다')], **force('substitute')) == [dict(t=0, dur=2, text='까라따')]
    assert degrade([line('나는 공을 여기에서')], **force('particle_drop')) == [dict(t=0, dur=2, text='나 공 여기')]
    assert degrade([line('가나다')], **force('syllable_drop')) == [dict(t=0, dur=2, text='음')]
    assert degrade([line('가나다')], **force('word_drop')) == [dict(t=0, dur=2, text='음')]
    assert degrade([line('안녕, 여기!')], **force('punctuation')) == [dict(t=0, dur=2, text='안녕 여기')]


def test_name_variation_per_occurrence():
    rows = [line('지우', t=i * 3) for i in range(8)]
    result = degrade(rows, seed=8, **force('name_variant'))
    assert set(r['text'] for r in result) <= {'지우', '지유', '지오'}
    assert len({r['text'] for r in result}) >= 2


def test_hallucination_eight_to_twenty_and_seed():
    result = degrade([line('사과')], seed=4, **force('hallucinate'))
    assert 9 <= len(result[0]['text'].split()) <= 21
    assert set(result[0]['text'].split()) == {'사과'}
    assert result == degrade([line('사과')], seed=4, **force('hallucinate'))


def test_overlap_drop_and_all_drop_fallback():
    rows = [line('첫 말'), line('겹친 말', t=1, overlap=True)]
    assert [r['text'] for r in degrade(rows, **force('overlap_drop'))] == ['첫 말']
    assert len(degrade([line(overlap=True)], **force('overlap_drop'))) == 1


def test_navigation_mix():
    result = degrade([line()], **force('nav_mix'))
    assert len(result) == 2
    assert any('입니다' in r['text'] for r in result)
    assert all('spk' not in r for r in result)


def test_merge_below_one_second_across_speakers_and_overlap():
    rows = [line('가', dur=2), line('나', t=2.9, dur=1, spk='엄마'),
            line('다', t=4.9, dur=1, spk='아빠')]
    assert reconstruct(rows) == [dict(t=0, dur=3.9, text='가 나'), dict(t=4.9, dur=1, text='다')]
    assert reconstruct([line('가', dur=5), line('나', t=2, dur=1), line('다', t=5.5, dur=1)]) == [dict(t=0, dur=6.5, text='가 나 다')]


def test_split_by_word_weight_hand_calculation_and_exact_limit():
    # Six equal-weight words / 30 seconds = 5 seconds each. Pairs last 10s.
    assert reconstruct([line('가 나 다 라 마 바', dur=30)]) == [
        dict(t=0, dur=10, text='가 나'), dict(t=10, dur=10, text='다 라'), dict(t=20, dur=10, text='마 바')]
    assert reconstruct([line('가 나', dur=12)]) == [dict(t=0, dur=12, text='가 나')]
    # 1:2:1 characters over 20s -> 5,10,5s, split at word boundaries.
    assert reconstruct([line('가 나나 다', dur=20)]) == [dict(t=0, dur=5, text='가'), dict(t=5, dur=10, text='나나'), dict(t=15, dur=5, text='다')]
    assert reconstruct([line('가', dur=20)]) == [dict(t=0, dur=12, text='가')]
    # Keep the next word's estimated start and all text, even for long tokens.
    assert reconstruct([line('가나다라 마', dur=30)]) == [dict(t=0, dur=12, text='가나다라'), dict(t=24, dur=6, text='마')]


def test_clean_level_and_purity():
    rows = [line('나는, 여기!'), line('말해요', t=2.5, spk='엄마')]
    original = deepcopy(rows)
    assert degrade(rows, 0) == [dict(t=0, dur=4.5, text='나는, 여기! 말해요')]
    assert rows == original
    assert degrade(rows, 2, seed=4) == degrade(rows, 2, seed=4)


def test_normal_and_severe_seeded_snapshot():
    rows = [line('지우는 가방에서 종이를 꺼냈어요 그런데, 나는 왜요?', dur=3),
            line('아까 네가 넣었잖아 여기 있네!', t=4.5, spk='엄마')]
    assert degrade(rows, 1, 43) == [dict(t=0, dur=3, text='지오는 가방에서 종이를 꺼냈어요 그런데 나는 왜요'),
                                    dict(t=4.5, dur=2, text='아까 네가 넣었잖아 여기 있네')]
    assert degrade(rows, 2, 43) == [dict(t=0, dur=3, text='지오는 종이를 그런데 나는 왜요 이어서 직진입니다'),
                                    dict(t=4.5, dur=2, text='아까 네가 넣었잖아 여기')]


def test_child_has_higher_error_probability():
    rows = [line('가나다 ' * 60)]
    adult = [dict(rows[0], spk='엄마')]
    kwargs = dict(enabled={k: k == 'substitute' for k in PROBABILITIES}, probabilities={'substitute': (0, .2, .2)})
    child_error = sum(degrade(rows, seed=s, **kwargs)[0]['text'] != rows[0]['text'].strip() for s in range(5))
    def count_changes(spk):
        return sum(sum(a != b for a, b in zip(degrade([line('가' * 1000, spk=spk)], seed=s, **kwargs)[0]['text'], '가' * 1000)) for s in range(5))
    assert child_error == 5
    assert count_changes('아이1') > count_changes('엄마') * 1.7


def test_block_features_hand_calculation():
    b = to_block(7, [dict(t=0, dur=2, text='하나'), dict(t=4, dur=2, text='둘')], 1)
    validate_block(b)
    assert b['id'] == 'synth:7@0' and b['prefix'] == []
    assert b['features'] == dict(n_turns=2, seconds=6, speech_share=4/6, median_gap=2, gap_before=600)
    assert b['gap_before'] is None
    overlap = to_block(8, [dict(t=0, dur=4, text='하나'), dict(t=2, dur=3, text='둘')], 0)
    assert overlap['features']['speech_share'] == 1.4
    assert overlap['features']['median_gap'] == -2


def test_export_200_records_schema_and_mixed_levels(tmp_path):
    cards = sample_cards()
    scenes = [fake_scene(c) for c in cards]
    blocks = export(cards, scenes, tmp_path, 'mixed')
    assert len(blocks) == 200
    for b in blocks:
        validate_block(b)
        assert b['demo_only'] is True
        assert all('spk' not in t for t in b['timing'])
        assert all(t['end'] - t['start'] <= 12.0000001 for t in b['timing'])
    assert {b['level'] for b in blocks} == {0, 1, 2}
    assert len(read_jsonl(tmp_path / 'clean.jsonl')) == 200
    assert len(read_jsonl(tmp_path / 'labels.jsonl')) == 200
    assert not list(tmp_path.glob('*.lock')) and not list(tmp_path.glob('.tmp*'))
    import json
    assert json.loads((tmp_path / 'meta.json').read_text(encoding='utf-8'))['complete']


@pytest.mark.parametrize('level', [-1, 3])
def test_bad_level(level):
    with pytest.raises(ValueError):
        degrade([line()], level)
