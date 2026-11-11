from copy import deepcopy
import json

import pytest

from synth.cards import sample_cards
from synth.degrade import DEFAULT_PROFILE, degrade, export, load_profile, main
from synth.generate import (fake_scene, load_examples, render_prompt, select_examples,
                            validate_scenes)
from synth.io import read_jsonl, write_json, write_jsonl
from synth.patterns import BY_ID
from synth.report import build_report


def line(text, t=0, spk='아이1', overlap=False, dur=2):
    return dict(t=t, dur=dur, text=text, spk=spk, overlap=overlap)


def test_level_zero_only_cleans_and_splits_and_navigation_is_exact():
    rows = [line('하나, 둘! 셋 넷 다섯 여섯 일곱 여덟 아홉 열'),
            line('잠시 후, 우회전입니다!', t=3, spk='내비')]
    original = deepcopy(rows)
    profile = deepcopy(DEFAULT_PROFILE)
    profile['line_length_histogram'] = [dict(chars=5, count=1)]
    turns = degrade(rows, level=0, profile=profile)
    assert rows == original
    assert ''.join(t['text'].replace(' ', '') for t in turns[:-1]) == '하나둘셋넷다섯여섯일곱여덟아홉열'
    assert turns[-1] == dict(t=3, dur=2, text=rows[-1]['text'])
    assert all('spk' not in t for t in turns)
    assert len(turns) > 2


def test_short_drops_seed_fallback_and_overlap():
    profile = dict(DEFAULT_PROFILE, short_reply_drop_rate=1)
    rows = [line('네'), line('어른이 충분히 길게 말하는 부분', t=3, spk='엄마')]
    stats = {}
    turns = degrade(rows, profile=profile, stats=stats)
    assert stats['short_reply_dropped'] == stats['drop_count'] == 1
    assert min(t['t'] for t in turns) == 3
    assert turns == degrade(rows, profile=profile)
    stats = {}
    assert degrade(rows[:1], profile=profile, stats=stats)
    assert stats['fallback_count'] == 1
    assert stats['drop_count'] == stats['short_reply_dropped'] == 0
    overlap = [line('첫 번째 충분히 긴 발화'), line('다른 쪽 충분히 긴 발화', t=1, overlap=True)]
    assert any(degrade(overlap, seed=s) != degrade([dict(l, overlap=False) for l in overlap], seed=s)
               for s in range(10))


def test_child_age_controls_errors_and_no_repetition_hallucinations():
    rows = [line('학교에서 종이를 접다가 모서리를 다시 펴고 친구에게 건넸어 ' * 30)]
    def changed(age, level=1):
        family = [dict(spk='아이1', age=age)]
        from difflib import SequenceMatcher
        return sum(1-SequenceMatcher(None, rows[0]['text'].replace(' ', ''),
                    ''.join(t['text'].replace(' ', '') for t in degrade(rows, level, seed, family=family)),
                    autojunk=False).ratio() for seed in range(10))
    assert changed('유아 3~5세') > changed('중년')
    assert changed('초등 고학년', 2) > changed('초등 고학년', 1)
    assert changed('중고생') == changed('중년')
    for seed in range(10):
        turns = degrade([line('새로운 낱말들로 발화를 구성한다')], seed=seed)
        assert sum(len(t['text']) for t in turns) < 50


def test_export_partial_shuffled_cards_preserves_labels(tmp_path):
    cards = sample_cards(12)
    selected = [cards[8], cards[1], cards[6]]
    scenes = [fake_scene(c) for c in selected]
    original = deepcopy((cards, scenes))
    blocks = export(cards, scenes, tmp_path, profile=DEFAULT_PROFILE)
    assert [b['id'] for b in blocks] == [f"synth:{c['card']}@0" for c in selected]
    labels = read_jsonl(tmp_path/'labels.jsonl')
    assert [(l['verdict'], l['pattern'], l['boundary']) for l in labels] == [
        (c['label'], c['pattern'], c['boundary']) for c in selected]
    assert (cards, scenes) == original
    for bad in ([scenes[0], scenes[0]], [dict(scenes[0], card=99)]):
        with pytest.raises(ValueError):
            export(cards, bad, tmp_path)


def test_profile_fallback_cli_and_numeric_report(tmp_path, capsys):
    assert load_profile(tmp_path/'absent.json') == DEFAULT_PROFILE
    profile = dict(DEFAULT_PROFILE, clips=25)
    write_json(tmp_path/'profile.json', dict(summary=profile))
    cards = sample_cards(4)
    write_jsonl(tmp_path/'cards.jsonl', cards)
    write_jsonl(tmp_path/'scenes.jsonl', [fake_scene(cards[2])])
    assert main(['--cards', str(tmp_path/'cards.jsonl'), '--scenes', str(tmp_path/'scenes.jsonl'),
                 '--out-dir', str(tmp_path/'out'), '--style', 'pleos', '--level', '1',
                 '--profile', str(tmp_path/'profile.json')]) == 0
    printed = capsys.readouterr().out.splitlines()
    stats = json.loads(printed[-1])
    assert stats['line_count'] > 0
    blocks = read_jsonl(tmp_path/'out/blocks.jsonl')
    report = build_report([], {}, blocks, [], profile, stats)
    assert 'Pleos 프로필과 합성 출력' in report
    assert all(t not in report for t in blocks[0]['turns'])
    zero = dict(DEFAULT_PROFILE, short_reply_drop_rate=0,
                char_alignment=dict(substitution_rate=0, deletion_rate=0))
    source = '서로 다른 단어를 가진 허구의 발화만 넣는다'
    turns = degrade([line(source)], family=[dict(spk='아이1', age='유아')], profile=zero)
    assert ''.join(t['text'].replace(' ', '') for t in turns) == source.replace(' ', '')
    write_json(tmp_path/'invalid.json', dict(summary=dict(profile, short_reply_drop_rate=1.1)))
    with pytest.raises(ValueError):
        load_profile(tmp_path/'invalid.json')


def test_examples_cover_labels_seed_pattern_and_copy_rejection():
    examples = load_examples()
    assert len(examples) == 28
    assert all(BY_ID[e['card']['pattern']]['label'] == e['card']['label'] for e in examples)
    cards = sample_cards(200)
    for i in range(0, 200, 4):
        batch = cards[i:i+4]
        selected = select_examples(batch)
        assert 2 <= len(selected) <= 3
        assert {c['label'] for c in batch} <= {e['card']['label'] for e in selected}
        assert selected == select_examples(batch)
        system, _ = render_prompt(batch)
        assert '{{EXAMPLES}}' not in system
        assert all(e['note'] in system for e in selected)
    assert select_examples(cards[:4], seed=1) != select_examples(cards[:4], seed=2)
    for example in examples:
        card = dict(example['card'], card=1, n_turns=len(example['lines']))
        scene = dict(card=1, lines=example['lines'], why=example['why'])
        with pytest.raises(ValueError, match='duplicate'):
            validate_scenes([scene], [card])


def test_examples_directory_is_sorted_and_new_files_are_included(tmp_path, monkeypatch):
    import synth.generate as generation
    directory = tmp_path / 'prompts/synth-examples'
    directory.mkdir(parents=True)
    for name, value in [('z.json', 3), ('a.json', 1), ('b.json', 2)]:
        (directory / name).write_text(json.dumps([value]), encoding='utf-8')
    (directory / 'ignored.md').write_text('ignored', encoding='utf-8')
    monkeypatch.setattr(generation, 'ROOT', tmp_path)
    assert load_examples() == [1, 2, 3]


def test_example_selection_prefers_matching_label_and_length(monkeypatch):
    from synth.generate import length_band
    cards = [dict(sample_cards(4)[0], label=label, seconds=seconds, n_turns=turns, card=i)
             for i, (label, seconds, turns) in enumerate([('none', 80, 24), ('story', 20, 6), ('moment', 75, 22)], 1)]
    for seed in (1, 43, 91):
        selected = select_examples(cards, seed)
        assert {(e['card']['label'], length_band(e['card'])) for e in selected} == {
            ('none', 'long'), ('story', 'short'), ('moment', 'long')}
    # With no matching band, retain the matching label.
    pool = [e for e in load_examples() if e['card']['label'] != 'none' or length_band(e['card']) != 'long']
    monkeypatch.setattr('synth.generate.load_examples', lambda: pool)
    assert select_examples(cards[:1])[0]['card']['label'] == 'none'


def test_tolerances_and_story_flow_variants():
    cards = sample_cards(200)
    stories = [c for c in cards if c['label'] == 'story']
    assert sum('flow_variant' in c for c in stories) == round(.3*len(stories))
    card = dict(cards[0], n_turns=10, seconds=40)
    for turns, seconds in ((6, 20), (14, 60)):
        scene = fake_scene(dict(card, n_turns=turns, seconds=seconds))
        validate_scenes([scene], [card])
    for turns, seconds in ((5, 40), (15, 40), (10, 19), (10, 61)):
        with pytest.raises(ValueError):
            validate_scenes([fake_scene(dict(card, n_turns=turns, seconds=seconds))], [card])


def test_agy_budget_reduces_examples_then_batch_size(tmp_path, monkeypatch):
    import synth.generate as generation
    from pipeline.label import AgyBackend, agy_arguments, agy_prompt, windows_command_length
    cards = [dict(c, label='story', pattern='kid_recount') for c in sample_cards(4)]
    def size(batch, count):
        system, user = render_prompt(batch, example_count=count)
        return windows_command_length(agy_arguments('agy.exe', 'gemini-3.8-flash', 'medium',
                                                    agy_prompt(system, user)))
    one_two, one_three = size(cards[:1], 2), size(cards[:1], 3)
    assert one_two < one_three
    monkeypatch.setattr(generation, 'AGY_COMMAND_LIMIT', one_two)
    rendered, _ = render_prompt(cards[:1])
    examples = json.loads(rendered.split('## 새로 지어낸 장면 예시')[1].split('\n', 3)[3].split('\n\n실존')[0])
    assert len(examples) == 2

    # Inflate invented card metadata enough that even the two-example prompt
    # requires smaller batches. No external executable is invoked.
    for card in cards:
        card['topic'] += ' 허구의 화제' * 500
    monkeypatch.setattr(generation, 'AGY_COMMAND_LIMIT', 32000)
    limit = max(size([card], 2) for card in cards)
    assert size(cards, 2) > limit
    monkeypatch.setattr(generation, 'AGY_COMMAND_LIMIT', limit)
    seen = []
    # Use compact invented responses so large card metadata doesn't consume the
    # scene content check or create near-duplicate scenes.
    from synth.generate import fake_scene as make_scene
    class CompactAgy(AgyBackend):
        def __init__(self):
            self.executable, self.model, self.effort = 'agy.exe', 'gemini-3.8-flash', 'medium'
        def complete(self, system, user):
            self.command(self.format_prompt(system, user))
            batch = json.loads(user)
            seen.append(len(batch))
            return json.dumps([make_scene(dict(c, topic=str(c['card']))) for c in batch], ensure_ascii=False)
    result = generation.generate(cards, tmp_path/'scenes.jsonl', CompactAgy, dict(backend='agy'))
    assert len(result) == 4
    assert max(seen) < 4
