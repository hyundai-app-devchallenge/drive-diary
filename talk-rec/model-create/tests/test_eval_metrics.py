import pytest

from eval.metrics import binary_metrics, minimal_pair_metrics, slice_metrics, utterance_metrics


def row(ident, story, score, **updates):
    return dict(id=ident, story=story, score=score, kind=["recall"] if story >= 2 else [],
                exclude=[], exclude_scores={}, labeler="human:tester", split="test", **updates)


def test_hand_computed_tied_ap_roc_and_f1():
    # Scores .9(P), .8(N), .8(P), .1(N). AP = (1 + 2/3)/2 = 5/6.
    # ROC = (2 wins + 1 win + half a tie)/4 = 7/8.
    result = binary_metrics([1, 0, 1, 0], [.9, .8, .8, .1], .85, 2)
    assert result == dict(n=4, positives=2, negatives=2, reliable=True,
                         pr_auc=pytest.approx(5/6), roc_auc=7/8,
                         tp=1, fp=0, fn=1, tn=2, precision=1, recall=.5,
                         f1=pytest.approx(2/3), threshold=.85,
                         best_f1=.8, best_f1_threshold=.8)
    # Permuting a tied block cannot change AP or ROC.
    reversed_tie = binary_metrics([1, 1, 0, 0], [.9, .8, .8, .1])
    assert reversed_tie["pr_auc"] == result["pr_auc"]
    assert reversed_tie["roc_auc"] == result["roc_auc"]


def test_best_f1_tie_chooses_highest_threshold():
    # At .9: 2/(2+0+1)=2/3; at .6: 4/(4+2+0)=2/3.
    result = binary_metrics([1, 0, 0, 1], [.9, .8, .7, .6])
    assert result["best_f1"] == pytest.approx(2/3)
    assert result["best_f1_threshold"] == .9


@pytest.mark.parametrize("gold,scores,ap,roc,best", [
    ([], [], None, None, None),
    ([0, 0], [.9, .1], None, None, .9),
    ([1, 1], [.9, .1], 1, None, .1),
])
def test_degenerate_defined_values(gold, scores, ap, roc, best):
    result = binary_metrics(gold, scores, threshold=1)
    assert result["pr_auc"] == ap
    assert result["roc_auc"] == roc
    assert result["best_f1_threshold"] == best
    assert result["precision"] == result["recall"] == result["f1"] == 0


def test_skip_and_partial_exclude_scores():
    rows = [row("p", 2, .9), row("n", 0, .1), row("s", 0, 1, skip=True)]
    rows[0]["exclude"] = ["sensitive"]
    rows[0]["exclude_scores"] = {"sensitive": .8}
    rows[1]["exclude_scores"] = {"sensitive": .2}
    rows[2]["exclude_scores"] = {"sensitive": 1}
    result = utterance_metrics(rows)
    assert result["n"] == 2
    assert result["pr_auc"] == result["roc_auc"] == result["f1"] == 1
    flag = result["exclude"]["sensitive"]
    assert flag["pr_auc"] == flag["roc_auc"] == flag["f1"] == 1
    assert (flag["coverage_n"], flag["missing_n"]) == (2, 0)
    rows[1]["exclude_scores"] = {}
    flag = utterance_metrics(rows)["exclude"]["sensitive"]
    assert (flag["coverage_n"], flag["missing_n"], flag["pr_auc"], flag["roc_auc"]) == (1, 1, 1, None)


def test_every_slice_including_empty_positive_and_empty_group():
    rows = [row("p", 2, .9, meta={"language": "ko", "asr kind": "pleos"}),
            row("n", 0, .1, meta={"language": "en", "asr kind": "whisper"}),
            row("skipped", 0, 1, skip=True, meta={"language": "skip-only"})]
    rows[1]["kind"] = ["family_fact"]  # story=1 is allowed for this tag
    rows[1]["story"] = 1
    rows[1]["labeler"] = "llm:model"
    rows[1]["split"] = "dev"
    groups = slice_metrics(rows, ["language", "asr kind"], min_positives=2)
    assert set(groups) == {"kind", "labeler_type", "split", "meta:language", "meta:asr kind"}
    for dimension, value in [("kind", "recall"), ("labeler_type", "human"),
                             ("split", "test"), ("meta:language", "ko"), ("meta:asr kind", "pleos")]:
        result = groups[dimension][value]
        assert result["n"] == result["positives"] == 1
        assert result["precision"] == result["recall"] == result["f1"] == result["pr_auc"] == 1
        assert result["roc_auc"] is None
        assert result["reliable"] is False
    empty_positive = groups["meta:language"]["en"]
    assert (empty_positive["n"], empty_positive["positives"], empty_positive["pr_auc"],
            empty_positive["reliable"]) == (1, 0, None, False)
    assert groups["kind"]["moment"]["n"] == 0
    assert groups["split"]["train"]["n"] == 0
    assert "skip-only" not in groups["meta:language"]
    assert utterance_metrics(rows, positive_story=1)["positives"] == 2


def test_minimal_pairs_tie_wrong_skip_removed_and_missing_error():
    rows = [row("a", 2, .9), row("b", 0, .1), row("c", 2, .9)]
    pairs = [dict(pair="win", positive="a", negative="b"),
             dict(pair="tie", positive="a", negative="c"),
             dict(pair="skip", positive="skipped", negative="unknown")]
    assert minimal_pair_metrics(pairs, rows, {"skipped"}) == dict(n=2, correct=1, skipped=1, accuracy=.5)
    assert minimal_pair_metrics([], rows)["accuracy"] is None
    with pytest.raises(ValueError, match="1 ids missing.*unknown"):
        minimal_pair_metrics([dict(positive="unknown", negative="a")], rows)


@pytest.mark.parametrize("gold,scores,options", [
    ([1], [], {}), ([2], [.2], {}), ([1], [float("nan")], {}),
    ([1], [1.1], {}), ([1], [.2], {"threshold": -1}), ([1], [.2], {"min_positives": 0}),
])
def test_metric_validation(gold, scores, options):
    with pytest.raises(ValueError):
        binary_metrics(gold, scores, **options)
