import json
from pathlib import Path

import pytest

from train.data import (build_input, drop_context, join_labels, label_files,
                        prepare_snapshot, read_labels, subsample_channels, write_rows)
from train.run import load_config, validate_config
from train.s0 import features, vectorize


def window(n=0, split="train"):
    return dict(id=f"yt:fixture#{n}", source="yt:fixture", split=split, clip=0,
                context=[], current="Fixture")


def label(n=0, story=0, skip=False, human=False):
    return dict(id=f"yt:fixture#{n}", story=story, kind=["recall"] if story >= 2 else [],
                exclude=[], skip=skip, labeler="human:tester" if human else "llm:tester", note="")


class Tokenizer:
    cls_token_id, sep_token_id = 101, 102

    def encode(self, text, add_special_tokens):
        assert not add_special_tokens
        return [ord(c) for c in text]


def test_input_normalization_segments_and_no_context():
    item = build_input(["Ａ!", "B"], "C?", Tokenizer(), 20)
    assert item == dict(input_ids=[101, 97, 102, 98, 102, 99, 102],
                        token_type_ids=[0, 0, 0, 0, 0, 1, 1], attention_mask=[1]*7)
    assert build_input([], "C", Tokenizer(), 20)["token_type_ids"] == [0, 1, 1]


def test_input_calls_authoritative_truncator(monkeypatch):
    import pipeline.windows as windows
    calls = []
    original = windows.truncate_tokens

    def spy(context, current, maximum):
        calls.append((context, current, maximum))
        return original(context, current, maximum)

    monkeypatch.setattr(windows, "truncate_tokens", spy)
    assert build_input(["old", "b"], "cd", Tokenizer(), 6)["input_ids"] == [101, 98, 102, 99, 100, 102]
    assert build_input(["b"], "abcdef", Tokenizer(), 5)["input_ids"] == [101, 100, 101, 102, 102]
    assert len(calls) == 2
    assert build_input([], "abc", Tokenizer(), 2)["input_ids"] == [101, 102]


def test_join_skip_missing_gold_override_and_weights():
    ws = [window(i) for i in range(5)]
    silver = {r["id"]: r for r in [label(0, 2), label(1, skip=True), label(2), label(3, 2)]}
    second = {window(0)["id"]: label(0, 3)}
    gold = {window(0)["id"]: label(0, 1, human=True), window(3)["id"]: label(3, skip=True, human=True)}
    rows, canonical, counts = join_labels(ws, [(silver, 0.5), (second, 2)], gold, 4)
    assert [(r["label"]["story"], r["weight"]) for r in rows] == [(1, 4), (0, 0.5)]
    assert counts == dict(total=5, labeled=4, skipped=2, retained=2, missing=1,
                          positives=0, gold=2, training_samples=2)
    assert len(canonical) == 4


def test_multiple_labelers_train_all_eval_highest_weight():
    key = window()["id"]
    rows, canonical, _ = join_labels([window()], [({key: label(story=2)}, 1), ({key: label(story=3)}, 2)], {}, 3)
    assert [r["weight"] for r in rows] == [1, 2]
    assert canonical[0]["story"] == 3


def test_percent_encoded_files_and_reserved_sidecars(tmp_path):
    write_rows(tmp_path / "yt%3Afixture.jsonl", [label()])
    (tmp_path / "run.jsonl").write_text('{"not":"a label"}')
    (tmp_path / "unclear.jsonl").write_text('{"not":"a label"}')
    assert list(read_labels(label_files(tmp_path))) == ["yt:fixture#0"]


def test_duplicate_labels_rejected(tmp_path):
    write_rows(tmp_path / "a.jsonl", [label(), label()])
    with pytest.raises(ValueError, match="duplicate"):
        read_labels(label_files(tmp_path))


def test_channel_subsamples_nested_order_independent():
    rows = [dict(channel=f"c{i//2}", id=i) for i in range(16)]
    p25 = subsample_channels(rows, 0.25, 42)
    p50 = subsample_channels(rows, 0.5, 42)
    assert len(p25) == 4 and len(p50) == 8
    assert {r["id"] for r in p25} <= {r["id"] for r in p50}
    assert {r["id"] for r in p25} == {r["id"] for r in subsample_channels(rows[::-1], .25, 42)}
    assert subsample_channels(rows, 1, 42) == rows


def test_context_dropout_reproducible_and_ordered():
    context = ["a", "b", "c"]
    results = [drop_context(context, 42, epoch, 0) for epoch in range(100)]
    assert results == [drop_context(context, 42, epoch, 0) for epoch in range(100)]
    assert {len(r) for r in results} == {0, 1, 2, 3}
    assert all(r == [c for c in context if c in r] for r in results)


def test_s0_hand_features_known_sqrt_scale_and_cutoff():
    assert features("Ａ! b") == {"_a", "a_", "_b", "b_", "_a_", "a_b", "_b_", "_a_b", "a_b_"}
    x = vectorize([features("a b"), features("xx")], {"_a": 0, "_b": 1, "zz": 2}).toarray()
    assert x[0].tolist() == pytest.approx([2**-0.5, 2**-0.5, 0])
    assert x[1].tolist() == [0, 0, 0]
    assert features("a"*512+"b") == features("a"*512)


def test_all_experiment_configs_valid():
    root = Path("train/configs")
    for p in json.loads((root / "all.json").read_text()):
        validate_config(load_config(root / p))


def test_snapshot_freezes_data_and_detects_tampering(tmp_path):
    ws, silver, gold, timing = [tmp_path / n for n in ("w", "s", "g", "u")]
    for p in (ws, silver, gold, timing):
        p.mkdir()
    manifest = tmp_path / "m.tsv"
    manifest.write_text("video_id\tchannel_id\nfixture\tc1\n", encoding="utf-8")
    for split in ("train", "dev", "test"):
        write_rows(ws / f"{split}.jsonl", [window()] if split == "train" else [])
    write_rows(silver / "yt%3Afixture.jsonl", [label(story=2)])
    write_rows(timing / "yt_fixture.jsonl", [dict(source="yt:fixture", utt=0, start=0, end=3, text="do not export")])
    cfg = dict(windows_dir=str(ws), manifest=str(manifest), utterances_dir=str(timing),
               silver=[dict(dir=str(silver), weight=1)], gold_dir=str(gold), gold_weight=3)
    target = tmp_path / "snap"
    info = prepare_snapshot(target, cfg)
    assert "text" not in (target / "timing/all.jsonl").read_text()
    write_rows(silver / "yt%3Afixture.jsonl", [label()])
    assert prepare_snapshot(target, cfg) == info
    (target / "train.labels.jsonl").write_text("\n")
    with pytest.raises(ValueError, match="changed"):
        prepare_snapshot(target, cfg)
