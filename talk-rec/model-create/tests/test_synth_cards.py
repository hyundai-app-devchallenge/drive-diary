from collections import Counter
from statistics import median

import pytest

from pipeline.label import AgyBackend, windows_command_length
from synth.cards import sample_cards
from synth.generate import render_prompt, validate_cards
from synth.patterns import BOUNDARY_PATTERNS, BY_ID, DEFINITIONS, SITUATIONS, TOPICS


def test_cards_exact_quotas_and_targets():
    cards = sample_cards()
    assert len(cards) == 200
    assert Counter(c['label'] for c in cards) == {'story': 80, 'moment': 40, 'none': 80}
    assert sum(c['boundary'] for c in cards) == 30
    assert median(c['n_turns'] for c in cards) == 9
    assert sum(c['n_turns'] == 1 for c in cards) == 14
    assert max(Counter((c['pattern'], c['topic']) for c in cards).values()) <= 2
    validate_cards(cards)
    for c in cards:
        assert 15 <= c['seconds'] <= 90
        assert c['topic'] in TOPICS[BY_ID[c['pattern']]['group']]
        assert 0 <= len(c['interruptions']) <= 2
        assert 2 <= len(c['family']) <= 5
        assert len({m['spk'] for m in c['family']}) == len(c['family'])
        assert sum(m['role'] == '운전자' for m in c['family']) == 1
        if c['boundary']:
            assert c['pattern'] in BOUNDARY_PATTERNS[c['label']]
        if c['label'] == 'story':
            assert c['n_turns'] >= 6
        if c['boundary'] and c['pattern'] in ('after_none', 'driving_only'):
            assert c['n_turns'] >= 8
        if c['pattern'] == 'fragments':
            assert c['n_turns'] <= 2


def test_cards_deterministic_and_cover_inventory():
    cards = sample_cards()
    assert cards == sample_cards()
    assert cards != sample_cards(seed=44)
    assert {c['pattern'] for c in cards} == set(BY_ID)
    assert {c['situation'] for c in cards} == set(SITUATIONS)
    assert len(SITUATIONS) >= 12
    assert len({t for group in TOPICS.values() for t in group}) >= 60
    assert {len(c['family']) for c in cards} == {2, 3, 4, 5}
    for label in ('story', 'moment', 'none'):
        counts = Counter(c['pattern'] for c in cards if c['label'] == label)
        # Boundary quotas necessarily raise a few patterns; all remain represented.
        assert min(counts.values()) >= 3


def test_custom_ratio_and_single_pattern_topic_limit():
    cards = sample_cards(100, ratios=dict(story=.2, moment=.3, none=.5), max_repeat=1)
    assert Counter(c['label'] for c in cards) == {'story': 20, 'moment': 30, 'none': 50}
    assert max(Counter((c['pattern'], c['topic']) for c in cards).values()) == 1
    assert all(c['label'] == 'story' for c in sample_cards(20, ratios=dict(story=1, moment=0, none=0)))


@pytest.mark.parametrize('kwargs', [dict(n=0), dict(n=-1), dict(boundary_rate=1.1), dict(max_repeat=0),
                                  dict(ratios=dict(story=-1, moment=1, none=1)),
                                  dict(ratios=dict(story=0, moment=0, none=0)), dict(n=10000)])
def test_invalid_sampling(kwargs):
    with pytest.raises(ValueError):
        sample_cards(**kwargs)


def test_prompt_render_and_agy_command_budget():
    cards = sample_cards()
    system, user = render_prompt(cards[:4])
    assert '{{' not in system
    assert all(definition in system for definition in DEFINITIONS.values())
    assert all(p['description'] in system for p in BY_ID.values())
    assert '드라마 대본이 아니다' in system
    assert '실존 인물·상표·특정 종교·정치' in system
    import json
    assert json.loads(user) == cards[:4]
    backend = AgyBackend('gemini-3.8-flash-medium', path='agy.exe')
    for i in range(0, 200, 4):
        system, user = render_prompt(cards[i:i+4])
        assert windows_command_length(backend.command(backend.format_prompt(system, user))) < 32000
