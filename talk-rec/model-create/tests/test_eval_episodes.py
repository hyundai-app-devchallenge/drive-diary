from copy import deepcopy

import pytest

from eval.episodes import (AssemblyConfig, assemble_episodes, episode_metrics,
                           evaluate_episodes, exclusion_safety)


def turn(utt, score, source="a", start=None, **updates):
    start = utt * 2 if start is None else start
    return dict(id=f"{source}#{utt}", source=source, start=start, end=start + 1,
                score=score, story=2 if score >= .8 else 0, exclude=[], **updates)


def episode(source, start, end):
    return dict(source=source, start=start, end=end)


def test_hysteresis_dips_close_two_sources_order_and_purity():
    rows = [turn(i, score) for i, score in enumerate([.8, .5, .1, .4, .1, .1, .5, .8])]
    rows += [turn(0, .8, "b"), turn(1, .5, "b")]
    rows.reverse()
    before = deepcopy(rows)
    result = assemble_episodes(rows, AssemblyConfig(tau_open=.8, tau_keep=.4, context_turns=0))
    assert result == [dict(source="a", start=0, end=7, ids=["a#0", "a#1", "a#2", "a#3"]),
                      dict(source="a", start=14, end=15, ids=["a#7"]),
                      dict(source="b", start=0, end=3, ids=["b#0", "b#1"])]
    assert rows == before


def test_context_silence_clip_and_explicit_boundary():
    rows = [turn(0, .1), turn(1, .1), turn(2, .9, context_count=1),
            turn(3, .9, start=25)]  # 25 - 5 == 20 closes exactly
    result = assemble_episodes(rows)
    assert result[0]["ids"] == ["a#1", "a#2"]
    assert result[1]["ids"] == ["a#3"]
    for boundary in ({"clip": 1}, {"boundary_before": True}, {"unlabeled_before": True}):
        rows = [turn(0, .9, clip=0), turn(1, .9)]
        rows[1].update(boundary)
        assert len(assemble_episodes(rows)) == 2
    rows = [turn(0, .9, clip=0), turn(1, .9, clip=1, unlabeled_before=True)]
    assert len(assemble_episodes(rows, AssemblyConfig(break_on_clip=False, bridge_unlabeled=True))) == 1


def test_skip_does_not_open_or_count_as_low_or_become_context():
    rows = [turn(0, .9), turn(1, 0, skip=True), turn(2, 0), turn(3, .5)]
    assert assemble_episodes(rows)[0]["ids"] == ["a#0", "a#2", "a#3"]
    assert assemble_episodes([turn(0, .9, skip=True)]) == []


def test_configurable_close_and_trailing_lows():
    rows = [turn(0, .9), turn(1, .1), turn(2, .5)]
    assert assemble_episodes(rows, AssemblyConfig(low_turns=1))[0]["end"] == 1
    assert assemble_episodes(rows[:2])[0]["end"] == 1
    assert assemble_episodes(rows[:2], AssemblyConfig(include_trailing_low=True))[0]["end"] == 3


def test_hand_computed_episode_matching_false_rate_and_exclusion():
    gold = [episode("a", 0, 10), episode("b", 0, 10)]
    predicted = [episode("a", 5, 10), episode("b", 9, 12), episode("a", 10, 11)]
    # One exact 50% match, a 10% partial overlap (not false), and one touching-only false.
    assert episode_metrics(gold, predicted, 7200) == dict(
        gold_n=2, predicted_n=3, found_n=1, false_n=1, recall=.5,
        false_per_hour=.5, duration_seconds=7200)
    assert episode_metrics(gold, predicted, 7200, .1)["recall"] == 1
    rows = [dict(id="1", source="a", start=6, end=7, exclude=["command", "sensitive"]),
            dict(id="2", source="a", start=0, end=1, exclude=["command"]),
            dict(id="skip", source="a", start=6, end=7, exclude=["command"], skip=True)]
    safety = exclusion_safety(rows, predicted)
    assert safety["command"] == dict(n=2, inside_n=1, inside_share=.5)
    assert safety["sensitive"] == dict(n=1, inside_n=1, inside_share=1)
    assert safety["optout"] == dict(n=0, inside_n=0, inside_share=None)


def test_hand_computed_curve_and_budget_gold_same_grouping():
    rows = [turn(0, .9), turn(1, .5), turn(2, .1), turn(3, .1), turn(4, .8)]
    rows[1]["story"] = 2
    rows[4]["story"] = 0
    config = AssemblyConfig(tau_open=.8, tau_keep=.4, context_turns=0)
    result = evaluate_episodes(rows, 3600, config, sweep=[.9, .8, 1], false_per_hour_budget=0)
    assert result["gold"] == [dict(source="a", start=0, end=3, ids=["a#0", "a#1"])]
    assert [(p["tau_open"], p["recall"], p["false_per_hour"]) for p in result["curve"]] == [
        (.8, 1, 1), (.9, 1, 0), (1, 0, 0)]
    assert result["budget"] == dict(false_per_hour=0, recall_target=.75, recall=1,
                                     tau_open=.9, tau_keep=.4, meets_target=True)


def test_empty_no_gold_no_duration_and_no_feasible_budget():
    result = evaluate_episodes([], 0)
    assert result["recall"] is result["false_per_hour"] is result["budget"]["recall"] is None
    assert result["gold_n"] == result["predicted_n"] == 0
    rows = [turn(0, .9), turn(1, .1), turn(2, .1), turn(3, 1)]
    rows[3]["story"] = 0
    result = evaluate_episodes(rows, 3600, AssemblyConfig(context_turns=0), sweep=[1], false_per_hour_budget=0)
    assert result["budget"]["recall"] is None
    no_gold = episode_metrics([], [episode("a", 0, 1)], 3600)
    assert no_gold["recall"] is None
    assert no_gold["false_per_hour"] == 1


@pytest.mark.parametrize("options", [dict(tau_open=.2, tau_keep=.3), dict(low_turns=0),
    dict(silence_seconds=0), dict(context_turns=-1), dict(tau_open=float("nan"))])
def test_invalid_assembly_configuration(options):
    with pytest.raises(ValueError):
        AssemblyConfig(**options)


def test_duplicate_and_invalid_timing_errors():
    with pytest.raises(ValueError, match="duplicate id"):
        assemble_episodes([turn(0, .9), turn(0, .8)])
    bad = turn(0, .9)
    bad["end"] = 0
    with pytest.raises(ValueError, match="timing"):
        assemble_episodes([bad])
    with pytest.raises(ValueError, match="overlap_fraction"):
        episode_metrics([], [], 0, 0)
