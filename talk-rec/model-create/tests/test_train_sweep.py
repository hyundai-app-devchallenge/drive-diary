import copy
import json
from pathlib import Path

import pytest

from train.data import (current_snapshot, data_fingerprint, live_data_digest,
                        write_json, write_rows)
from train.run import load_config, reusable, seed_directory, validate_config


def example(key="a", story=0):
    return dict(window=dict(id=key, current="private text"), channel="c", weight=1,
                label=dict(id=key, story=story, kind=[], exclude=[], skip=False, labeler="llm:x", note="private"))


def test_short_seed_directories_are_separate():
    assert seed_directory(Path(".local/train/ms"), "s1c3", 2) == Path(".local/train/ms/s1c3/s2")
    assert seed_directory(Path("r"), "s1c3", 1) != seed_directory(Path("r"), "s1c3", 2)
    for seed in (-1, 2**32, True):
        with pytest.raises(ValueError, match="seeds"):
            seed_directory(Path("r"), "s1c3", seed)


def test_fingerprint_tracks_labels_ids_splits_weights_channels_but_not_text():
    a, b = example(), example("b", 2)
    base = data_fingerprint(dict(train=[a, b], dev=[]))
    assert base == data_fingerprint(dict(dev=[], train=[b, a]))
    private = copy.deepcopy(a)
    private["window"]["current"] = "other text"
    private["label"]["note"] = "other note"
    assert base == data_fingerprint(dict(train=[private, b], dev=[]))
    for field, value in (("story", 2), ("kind", ["recall"]), ("skip", True)):
        changed = copy.deepcopy(a)
        changed["label"][field] = value
        assert base != data_fingerprint(dict(train=[changed, b], dev=[]))
    assert base != data_fingerprint(dict(train=[a], dev=[b]))
    assert base != data_fingerprint(dict(train=[dict(a, channel="d"), b], dev=[]))
    assert base != data_fingerprint(dict(train=[dict(a, weight=3), b], dev=[]))


def test_skip_only_complete_matching_config_and_fingerprint(tmp_path):
    config = dict(seed=1, epochs=2)
    write_json(tmp_path / "resolved.json", config)
    write_json(tmp_path / "dev.eval.json", {})
    write_json(tmp_path / "run.json", dict(data_fingerprint="abc", snapshot_digest="ds", outputs=["dev.eval.json", "resolved.json"]))
    assert reusable(tmp_path, config, "abc", "ds")
    assert not reusable(tmp_path, dict(config, epochs=3), "abc", "ds")
    assert not reusable(tmp_path, config, "new-labels", "ds")
    assert not reusable(tmp_path, config, "abc", "new-windows")
    (tmp_path / "dev.eval.json").unlink()
    assert not reusable(tmp_path, config, "abc", "ds")
    (tmp_path / "run.json").write_text("incomplete")
    assert not reusable(tmp_path, config, "abc", "ds")


def test_live_data_changes_select_new_snapshot_including_new_label_files(tmp_path, monkeypatch):
    import train.data as data
    dirs = {n: tmp_path / n for n in ("w", "u", "g", "s")}
    for p in dirs.values():
        p.mkdir()
    manifest = tmp_path / "m.tsv"
    manifest.write_text("channel_id\tvideo_id\n")
    for split in ("train", "dev", "test"):
        write_rows(dirs["w"] / f"{split}.jsonl", [])
    config = dict(manifest=str(manifest), windows_dir=str(dirs["w"]), utterances_dir=str(dirs["u"]),
                  gold_dir=str(dirs["g"]), silver=[dict(dir=str(dirs["s"]), weight=1)], snapshot="unused")
    monkeypatch.setattr(data, "prepare_snapshot", lambda path, config: None)
    prefix = tmp_path / "ds"
    first = current_snapshot(prefix, config)
    assert first == current_snapshot(prefix, config)
    write_rows(dirs["s"] / "new.jsonl", [dict(id="x", story=2)])
    second = current_snapshot(prefix, config)
    assert first != second
    write_rows(dirs["s"] / "new.jsonl", [dict(id="x", story=0)])
    assert second != current_snapshot(prefix, config)
    before = live_data_digest(config)
    write_rows(dirs["w"] / "dev.jsonl", [dict(id="moved")])
    assert before != live_data_digest(config)


def test_sweep_cli_expands_seeds_and_propagates_small_run_options(tmp_path, monkeypatch):
    import train.sweep as sweep
    import train.report as report
    config = load_config("train/configs/s1c3.json")
    monkeypatch.setattr(sweep, "load_config", lambda p: copy.deepcopy(config))
    monkeypatch.setattr(sweep, "local_output", lambda p: tmp_path / Path(p).name)
    monkeypatch.setattr(sweep, "current_snapshot", lambda p, c: p)
    commands = []
    def child(command, env):
        commands.append((command, env))
        return type("Completed", (), dict(returncode=0))()
    monkeypatch.setattr(sweep.subprocess, "run", child)
    monkeypatch.setattr(report, "main", lambda args: 0)
    assert sweep.main(["--configs", "unused", "--seeds", "1", "2", "--epochs", "1", "--max-train-rows", "8"]) == 0
    assert len(commands) == 2
    assert [env["PYTHONHASHSEED"] for _, env in commands] == ["1", "2"]
    for command, _ in commands:
        assert "--resume" in command and "--max-train-rows" in command
    manifest = json.loads((tmp_path / "ms/sweep.json").read_text())
    assert [Path(p).name for p in manifest["runs"]] == ["s1", "s2"]


def test_config_seed_list_and_row_cap_validation():
    config = load_config("train/configs/s1c3.json")
    assert config["seeds"] == [1, 2, 3]
    for bad in ([1, 1], [], [-1]):
        with pytest.raises(ValueError, match="seeds"):
            validate_config(dict(config, seeds=bad))
    with pytest.raises(ValueError, match="max_train_rows"):
        validate_config(dict(config, max_train_rows=0))


def test_run_resume_skips_then_reruns_config_labels_or_missing_output(tmp_path, monkeypatch, capsys):
    import numpy as np
    import train.run as runner
    import train.s0 as s0
    config = load_config("train/configs/s0c0.json")
    config["data"]["snapshot"] = str(tmp_path / "ds")
    config_path = tmp_path / "c.json"
    write_json(config_path, config)
    train, dev = [example("a"), example("b", 2)], [example("d"), example("e", 2)]
    monkeypatch.setattr(runner, "prepare_snapshot", lambda p, c: dict(digest="snapshot", coverage={}))
    monkeypatch.setattr(runner, "used_data", lambda p, c: (train, dict(dev=dev, test=[])))
    calls = []
    def fit(*args):
        calls.append(1)
        return dict(heads=dict(binary=0), vocabulary={}, feature_scheme="fixture")
    monkeypatch.setattr(s0, "fit", fit)
    monkeypatch.setattr(s0, "predict", lambda m, ws: {k: np.array([.5] * len(ws)) for k in
                        ["binary", "ordinal", *[f"exclude:{f}" for f in runner.EXCLUDES]]})
    def evaluate(snapshot, output, split):
        write_json(output / f"{split}.eval.json", {})
        return []
    monkeypatch.setattr(runner, "evaluate_split", evaluate)
    kwargs = dict(output_root=tmp_path / "runs", seed=1, resume=True)
    first = runner.run(config_path, **kwargs)
    assert len(calls) == 1
    assert runner.run(config_path, **kwargs) == first
    assert len(calls) == 1
    runner.run(config_path, epochs=1, **kwargs)
    assert len(calls) == 2
    train[0]["label"]["story"] = 1  # label changed even without binary target changing
    changed = runner.run(config_path, epochs=1, **kwargs)
    assert len(calls) == 3
    assert changed["data_fingerprint"] != first["data_fingerprint"]
    (seed_directory(kwargs["output_root"], config["name"], 1) / "dev.scores.jsonl").unlink()
    runner.run(config_path, epochs=1, **kwargs)
    assert len(calls) == 4
    output = capsys.readouterr().out
    assert "SKIP s0c0/s1" in output and "RERUN s0c0/s1" in output
    assert "data_fingerprint" in output and "coverage" in output
    assert "private text" not in output
