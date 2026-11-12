import copy
import json

import pytest

from train.data import digest_json, write_json, write_rows
from train.report import (build_report, channel_bootstrap, load_run, markdown,
                          summarize)


def scored(perfect=True):
    return [dict(id=f"{c}-{y}", channel=c, positive=y,
                 score=(.9 if y else .1) if perfect else (.1 if y else .9))
            for c in ("ch-a", "ch-b") for y in (0, 1)]


def record(name, seed, value):
    return dict(info=dict(name=name, seed=seed, snapshot_digest="same", dev_fingerprint="dev",
                          data_fingerprint=f"data-{seed}", train_samples=10 * seed,
                          training_seconds=seed * 2, coverage={s: dict(total=10, labeled=8, skipped=1,
                          retained=7, missing=2, positives=3) for s in ("train", "dev", "test")}),
                config=dict(name=name, seed=seed, model="s1", context_turns=3, max_length=160,
                            channel_fraction=1.),
                result=dict(config=dict(false_per_hour_budget=3, positive_story=2),
                            utterance=dict(n=4, positives=2, pr_auc=value, best_f1=value / 2),
                            episodes=dict(budget=dict(recall=value / 4))), scores=scored())


def test_hand_mean_and_sample_sd_not_population_sd():
    result = summarize([1, 2, 3])
    assert result == dict(mean=2, sd=1, defined_n=3, total_n=3)
    assert summarize([1])["sd"] is None
    assert summarize([None, 2]) == dict(mean=2, sd=None, defined_n=1, total_n=2)
    assert summarize([None])["mean"] is None


def test_report_uses_harness_metrics_and_actual_training_rows():
    # Deliberately different from AP of the saved perfect scores: representative
    # metrics must be copied from the harness rather than recomputed.
    report = build_report([record("s1c3", s, v) for s, v in ((1, .2), (2, .4), (3, .6))])
    row = report["rows"][0]
    assert row["pr_auc"]["mean"] == pytest.approx(.4)
    assert row["pr_auc"]["sd"] == pytest.approx(.2)
    assert row["best_f1"]["mean"] == pytest.approx(.2)
    assert row["episode_recall_at_budget"]["mean"] == pytest.approx(.1)
    assert row["training_seconds"]["sd"] == pytest.approx(2)
    assert row["training_rows"]["mean"] == 20
    assert [r["fraction"] for r in report["learning_curve"]] == [.25, .5, 1.]
    assert report["learning_curve"][2]["training_rows"]["sd"] == 10


def test_bootstrap_known_perfect_vs_reversed_and_determinism():
    runs = {"s1c3": {1: scored(False), 2: scored(False)},
            "better": {1: scored(), 2: scored()}}
    result = channel_bootstrap(runs)
    assert result == channel_bootstrap(runs)
    assert result["better"]["pr_auc_95"] == dict(low=1, high=1, valid_draws=1000)
    assert result["better"]["difference_95"] == dict(low=.5, high=.5, valid_draws=1000)
    assert result["better"]["probability_beats_baseline"] == 1
    assert result["s1c3"]["probability_beats_baseline"] == 0  # ties are not wins


def test_bootstrap_means_seed_metrics_without_pooling_scores():
    runs = {"s1c3": {1: scored(False), 2: scored(False)},
            "mixed": {1: scored(), 2: scored(False)}}
    result = channel_bootstrap(runs)["mixed"]
    assert result["pr_auc_95"]["low"] == .75
    assert result["difference_95"]["high"] == .25


def test_bootstrap_resamples_whole_channels_and_omits_undefined_draws():
    # Two channels, one positive and one negative. Of four equiprobable channel
    # draws: PP => delta 0; PN/NP => delta .5; NN => undefined.
    good = [dict(id="n", channel="a", positive=0, score=.1),
            dict(id="p", channel="b", positive=1, score=.9)]
    bad = [dict(r, score=1-r["score"]) for r in good]
    result = channel_bootstrap({"s1c3": {1: bad}, "good": {1: good}})["good"]
    assert result["difference_95"]["low"] == 0
    assert result["difference_95"]["high"] == .5
    assert 700 < result["difference_95"]["valid_draws"] < 800
    assert .6 < result["probability_beats_baseline"] < .73


def test_under_ten_channel_warning_and_privacy():
    report = build_report([record("s1c3", 1, .4)])
    assert any("<10" in w and "unreliable" in w for w in report["warnings"])
    text = markdown(report)
    assert "UNRELIABLE INTERVAL" in text
    assert "100%" in text and "25%" in text and "50%" in text
    assert "sample SD" in text and "not a p-value" in text
    assert "current" not in json.dumps(report) and "title" not in json.dumps(report)
    assert report["channels"][0]["positive_rate"] == .5
    assert report["channels"][0]["pr_auc"]["mean"] == 1


def test_ten_channels_has_no_small_channel_warning():
    r = record("s1c3", 1, .4)
    r["scores"] = [dict(row, id=f"{i}-{row['id']}", channel=f"ch-{i}")
                   for i in range(10) for row in scored()[:2]]
    report = build_report([r])
    assert not any("<10" in w for w in report["warnings"])


def test_unpaired_seed_sets_not_silently_compared():
    report = build_report([record("s1c3", 1, .4), record("other", 2, .5)])
    assert report["rows"][1]["bootstrap"]["probability_beats_baseline"] is None
    assert any("seed sets differ" in w for w in report["warnings"])


def test_paired_point_difference_is_from_harness_means():
    report = build_report([record("s1c3", 1, .4), record("other", 1, .6)])
    assert report["rows"][1]["bootstrap"]["difference_mean"] == pytest.approx(.2)
    assert "Paired Δ [95%]" in markdown(report)


@pytest.mark.parametrize("field", ["snapshot_digest", "dev_fingerprint"])
def test_report_refuses_mixed_data_generations(field):
    a, b = record("s1c3", 1, .4), record("s1c3", 2, .5)
    b["info"][field] = "changed"
    with pytest.raises(ValueError, match="differ"):
        build_report([a, b])


def test_report_refuses_mixed_hyperparameters():
    a, b = record("s1c3", 1, .4), record("s1c3", 2, .5)
    b["config"]["max_length"] = 256
    with pytest.raises(ValueError, match="hyperparameters"):
        build_report([a, b])


def test_report_refuses_unaligned_paired_rows():
    bad = copy.deepcopy(scored())
    bad[0]["positive"] = 1
    with pytest.raises(ValueError, match="dev IDs"):
        channel_bootstrap({"s1c3": {1: scored()}, "other": {1: bad}})


def test_load_run_joins_saved_scores_by_id_and_validates_counts(tmp_path):
    r = record("s1c3", 1, .4)
    write_json(tmp_path / "resolved.json", r["config"])
    write_json(tmp_path / "run.json", dict(r["info"], config_fingerprint=digest_json(r["config"]), outputs=["dev.eval.json"]))
    write_json(tmp_path / "dev.eval.json", r["result"])
    write_rows(tmp_path / "dev.targets.jsonl", [dict(id=s["id"], channel=s["channel"], story=2*s["positive"]) for s in r["scores"]])
    write_rows(tmp_path / "dev.scores.jsonl", [dict(id=s["id"], story=s["score"]) for s in reversed(r["scores"])])
    assert load_run(tmp_path)["scores"] == r["scores"]
    r["result"]["utterance"]["n"] = 3
    write_json(tmp_path / "dev.eval.json", r["result"])
    with pytest.raises(ValueError, match="counts"):
        load_run(tmp_path)
