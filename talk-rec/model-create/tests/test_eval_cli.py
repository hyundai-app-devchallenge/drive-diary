import json
from pathlib import Path
import subprocess
import sys

import pytest

from eval.io import load_inputs, read_jsonl, read_metadata, score_validator
from eval.run import compare_results, evaluate
from eval.episodes import AssemblyConfig


ROOT = Path(__file__).resolve().parents[1]


def jsonl(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
    return path


@pytest.fixture
def fixture(tmp_path):
    specs = [
        ("a", 0, 0, .1, [], ["command"]),
        ("a", 1, 2, .9, ["recall"], []),
        ("a", 2, 2, .6, ["moment"], []),
        ("a", 3, 0, .1, [], ["external"]),
        ("a", 4, 0, .1, [], ["command"]),
        ("a", 5, 0, .8, [], ["sensitive"]),
        ("b", 0, 2, .2, ["feeling"], []),
        ("b", 1, 0, .2, [], ["optout"]),
    ]
    labels, scores, windows, timings = [], [], [], {"a": [], "b": []}
    for source, utt, story, score, kinds, excludes in specs:
        ident = f"{source}#{utt}"
        labels.append(dict(id=ident, story=story, kind=kinds, exclude=excludes, skip=False,
                           labeler="llm:model" if utt == 2 else "human:tester"))
        scores.append(dict(id=ident, story=score,
                           exclude={"command": .9 if "command" in excludes else .1}))
        windows.append(dict(id=ident, source=source, split="test", clip=0))
        timings[source].append(dict(source=source, utt=utt, start=utt*10, end=utt*10+10))
    labels.append(dict(id="a#6", story=0, kind=[], exclude=[], skip=True, labeler="human:tester"))
    timings["a"].append(dict(source="a", utt=6, start=60, end=70))
    paths = dict(labels=jsonl(tmp_path / "labels.jsonl", labels),
                 scores=jsonl(tmp_path / "scores.jsonl", scores),
                 windows=jsonl(tmp_path / "windows.jsonl", windows),
                 utterances_dir=tmp_path / "utterances",
                 pairs=jsonl(tmp_path / "pairs.jsonl", [
                     dict(pair="win", positive="a#1", negative="a#0"),
                     dict(pair="tie", positive="b#0", negative="b#1"),
                     dict(pair="skip", positive="a#6", negative="a#0")]),
                 meta=tmp_path / "meta.tsv")
    for source, records in timings.items():
        jsonl(paths["utterances_dir"] / f"{source}.jsonl", records)
    paths["meta"].write_text("id\tlanguage\tasr kind\n" + "".join(
        f"{r['id']}\t{'ko' if r['story'] >= 2 else 'mixed'}\tpleos\n" for r in labels), encoding="utf-8")
    return paths


def load(paths, **options):
    return load_inputs(paths["labels"], paths["scores"], paths["windows"], paths["utterances_dir"],
                       paths["meta"], paths["pairs"], **options)


def test_hand_computed_all_metrics_after_real_joins(fixture):
    inputs = load(fixture)
    result = evaluate(inputs, AssemblyConfig(context_turns=0), sweep=[.8, .9], min_positives=1)
    assert result["counts"] == dict(labels_n=9, evaluated_n=8, skipped_n=1, sources_n=2, timing_n=9)
    u = result["utterance"]
    # Ranked positives .9, .6, .2; the .2 block also has one negative.
    # AP = (1 + 2/3 + 3/5)/3 = 34/45. ROC = (5+4+3.5)/(3*5)=5/6.
    assert u["pr_auc"] == pytest.approx(34/45)
    assert u["roc_auc"] == pytest.approx(5/6)
    assert (u["tp"], u["fp"], u["fn"], u["tn"]) == (2, 1, 1, 4)
    assert u["precision"] == u["recall"] == u["f1"] == pytest.approx(2/3)
    assert u["best_f1"] == .75
    assert u["best_f1_threshold"] == .2
    assert u["exclude"]["command"]["pr_auc"] == 1
    e = result["episodes"]
    assert (e["gold_n"], e["predicted_n"], e["found_n"], e["false_n"]) == (2, 2, 1, 1)
    assert e["recall"] == .5
    assert e["duration_seconds"] == 80
    assert e["false_per_hour"] == 45  # 1 / (80/3600)
    assert e["budget"] == dict(false_per_hour=3, recall_target=.75, recall=.5,
                                tau_open=.9, tau_keep=.3, meets_target=False)
    assert [(p["tau_open"], p["recall"], p["false_per_hour"]) for p in e["curve"]] == [
        (.5, .5, 45), (.8, .5, 45), (.9, .5, 0)]
    assert e["exclusion_safety"]["sensitive"] == dict(n=1, inside_n=1, inside_share=1)
    assert e["exclusion_safety"]["command"] == dict(n=2, inside_n=0, inside_share=0)
    assert result["minimal_pairs"] == dict(n=2, correct=1, skipped=1, accuracy=.5)
    mixed = result["slices"]["meta:language"]["mixed"]
    assert (mixed["n"], mixed["positives"], mixed["reliable"], mixed["pr_auc"]) == (5, 0, False, None)


def test_cli_outputs_and_two_result_compare(fixture, tmp_path):
    args = []
    for name, path in fixture.items():
        args += ["--" + name.replace("_", "-"), str(path)]
    output = tmp_path / "result.json"
    command = [sys.executable, "-m", "eval.run", *args, "--context-turns", "0",
               "--sweep", ".8", ".9", "--out", str(output)]
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    assert completed.returncode == 0, completed.stderr
    result = json.loads(output.read_text(encoding="utf-8"))
    assert result["utterance"]["pr_auc"] == pytest.approx(34/45)
    markdown = output.with_suffix(".md").read_text(encoding="utf-8")
    assert "meta:language=mixed" in markdown and "Assumptions" in markdown
    assert "best_f1_threshold" in markdown and "before storage policy" in markdown
    second = tmp_path / "second.json"
    result["utterance"]["pr_auc"] = .5
    second.write_text(json.dumps(result), encoding="utf-8")
    compared = subprocess.run([sys.executable, "-m", "eval.run", "compare", str(output), str(second)],
                              cwd=ROOT, capture_output=True, text=True)
    assert compared.returncode == 0, compared.stderr
    assert "utterance.pr_auc | 0.755556 | 0.5" in compared.stdout
    assert str(output) in compared.stdout and str(second) in compared.stdout
    assert "slices.meta:language.mixed.n" in compared.stdout


def test_missing_scores_lists_count_and_ids(fixture):
    records = list(read_jsonl(fixture["scores"]).values())
    jsonl(fixture["scores"], records[2:])
    with pytest.raises(ValueError, match="2 ids missing from scores: a#0, a#1"):
        load(fixture)


@pytest.mark.parametrize("name,key", [("labels", "id"), ("scores", "id"),
                                      ("windows", "id"), ("pairs", "pair")])
def test_duplicate_records_error(fixture, name, key):
    records = list(read_jsonl(fixture[name], key=key).values())
    jsonl(fixture[name], records + records[:1])
    with pytest.raises(ValueError, match=f"duplicate {key}"):
        load(fixture)


def test_duplicate_timing_and_metadata_errors(fixture):
    path = fixture["utterances_dir"] / "a.jsonl"
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    jsonl(path, rows + rows[:1])
    with pytest.raises(ValueError, match="duplicate timing id: a#0"):
        load(fixture)
    fixture["meta"].write_text("id\tlanguage\na#0\tko\na#0\ten\n", encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate id: a#0"):
        read_metadata(fixture["meta"])


def test_empty_inputs_cli_defined(fixture, tmp_path):
    jsonl(fixture["labels"], [])
    jsonl(fixture["pairs"], [])
    result = evaluate(load(fixture))
    assert result["counts"]["evaluated_n"] == 0
    assert result["utterance"]["pr_auc"] is None
    assert result["episodes"]["recall"] is None
    assert result["minimal_pairs"]["accuracy"] is None
    json.dumps(result, allow_nan=False)


def test_minimal_timing_filename_fallback_and_safe_colon(fixture):
    path = fixture["utterances_dir"] / "a.jsonl"
    timings = [json.loads(line) for line in path.read_text().splitlines()]
    jsonl(path, [dict(start=r["start"], end=r["end"]) for r in timings])
    assert load(fixture)["duration_seconds"] == 80


def test_source_timing_origin_skip_interval_and_duration_mode(fixture):
    path = fixture["utterances_dir"] / "a.jsonl"
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    # A skipped ten-second turn inside the source's timing extent is removed;
    # another unlabeled turn extends the known audio endpoint to 100 seconds.
    rows.append(dict(source="a", utt=7, start=90, end=100))
    jsonl(path, rows)
    assert load(fixture)["duration_seconds"] == 110  # 100-10 + 20
    assert load(fixture, duration_mode="evaluated_span")["duration_seconds"] == 80


def test_unlabeled_boundary_and_metadata_missing(fixture):
    records = list(read_jsonl(fixture["labels"]).values())
    jsonl(fixture["labels"], [r for r in records if r["id"] != "a#1"])
    inputs = load(fixture)
    assert next(r for r in inputs["rows"] if r["id"] == "a#2")["unlabeled_before"] is True
    fixture["meta"].write_text("id\tlanguage\n", encoding="utf-8")
    jsonl(fixture["pairs"], [])
    result = evaluate(load(fixture))
    assert result["slices"]["meta:language"]["(missing)"]["n"] == 7


def test_context_cannot_reach_back_past_skipped_window_turn(fixture):
    labels = list(read_jsonl(fixture["labels"]).values())[:3]
    labels[1].update(story=0, kind=[], exclude=[], skip=True)
    jsonl(fixture["labels"], labels)
    windows = list(read_jsonl(fixture["windows"]).values())
    windows[2]["context"] = ["the skipped turn"]
    jsonl(fixture["windows"], windows)
    jsonl(fixture["pairs"], [])
    result = evaluate(load(fixture))
    assert result["episodes"]["predicted"][0]["ids"] == ["a#2"]


@pytest.mark.parametrize("record", [dict(id="x", story=float("nan")), dict(id="x", story=True),
    dict(id="x", story=.1, exclude={"unknown": .2}), dict(id="x", story=.1, exclude={"command": -1})])
def test_invalid_scores(tmp_path, record):
    with pytest.raises(ValueError):
        read_jsonl(jsonl(tmp_path / "score.jsonl", [record]), validator=score_validator)


def test_bad_json_and_compare_format(tmp_path):
    path = tmp_path / "bad.jsonl"
    path.write_text("{not json}\n", encoding="utf-8")
    with pytest.raises(ValueError, match="bad.jsonl:1"):
        read_jsonl(path)
    path.write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError, match="unsupported evaluation result"):
        compare_results([path])


def test_cli_error_is_clear_without_traceback(fixture, tmp_path):
    jsonl(fixture["scores"], [])
    args = [sys.executable, "-m", "eval.run"]
    for name in ("labels", "scores", "windows", "utterances_dir"):
        args += ["--" + name.replace("_", "-"), str(fixture[name])]
    args += ["--out", str(tmp_path / "bad.json")]
    completed = subprocess.run(args, cwd=ROOT, capture_output=True, text=True)
    assert completed.returncode == 2
    assert "8 ids missing from scores" in completed.stderr
    assert "Traceback" not in completed.stderr
