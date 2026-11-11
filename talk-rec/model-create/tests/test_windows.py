"""T05 tests: synthetic utterances only; no network or transcript fixtures."""

from collections import Counter
import copy
import csv
import json
import random
import sys
from types import SimpleNamespace

import pytest

from common.schema import validate_window
from pipeline import windows as w
from pipeline.collect import channel_split


def utterance(index=0, start=None, end=None, source="yt:fixture", text=None):
    start = index * 10 if start is None else start
    return dict(source=source, utt=index, start=start,
                end=start + 10 if end is None else end,
                text=f"fixture turn {index}" if text is None else text,
                asr="human", speaker=None)


def manifest_row(video="fixture", split="dev", channel="fixture-channel", lowdensity="0", status="downloaded"):
    return dict(video_id=video, channel_id=channel, split=split, lowdensity=lowdensity, status=status)


def save_jsonl(path, records):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")


def save_manifest(path, rows):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(manifest_row()), delimiter="\t")
        writer.writeheader()
        writer.writerows(rows)


class FixtureTokenizer:
    cls_token_id = 101
    sep_token_id = 102

    def __init__(self):
        self.seen = []

    def encode(self, text, *, add_special_tokens):
        assert add_special_tokens is False
        self.seen.append(text)
        return [200 + i for i, _ in enumerate(text.split())]


@pytest.mark.parametrize("gap,expected", [(20.0, [0]), (20.01, [])])
def test_context_gap_boundary(gap, expected):
    records = [utterance(0, 0, 1), utterance(1, 1 + gap, 22 + gap)]
    assert w.context_indices(records, 1) == expected
    assert w.assign_clips(records) == ([0, 0] if gap == 20.0 else [0, 1])


def test_context_stops_at_first_gap_walks_adjacent_and_is_causal():
    records = [utterance(0, 0, 1), utterance(1, 21.01, 22),
               utterance(2, 42, 43), utterance(3, 63, 64), utterance(4, 84, 85)]
    assert w.context_indices(records, 3) == [1, 2]
    assert w.context_indices(records, 4) == [1, 2, 3]
    # Oldest context can be >20s from current: the limit applies per adjacent gap.
    for index, window in enumerate(w.build_source(records, "train")):
        assert window["context"] == [records[i]["text"] for i in w.context_indices(records, index)]
        assert records[index]["text"] not in window["context"]
        assert all(i < index for i in w.context_indices(records, index))


def test_max_three_chronological_full_text_id_and_no_clip_context_reset():
    records = [utterance(i, text=f"Full Ａ! turn {i}") for i in range(65)]
    windows = w.build_source(records, "dev")
    for index, window in enumerate(windows):
        validate_window(window)
        assert window["id"] == f"yt:fixture#{index}"
        assert window["current"] == records[index]["text"]
        assert window["context"] == [u["text"] for u in records[max(0, index - 3):index]]
    assert windows[24]["clip"] == 1
    assert windows[24]["context"] == [records[i]["text"] for i in (21, 22, 23)]


def clip_spans(records, clips):
    return [max(u["end"] for u, c in zip(records, clips) if c == clip)
            - min(u["start"] for u, c in zip(records, clips) if c == clip)
            for clip in sorted(set(clips))]


def test_clips_prefer_largest_silence_over_target():
    records = []
    start = 0
    for index in range(70):
        records.append(utterance(index, start, start + 10))
        start += 10 + (10 if index == 18 else 2 if index == 23 else 0)
    clips = w.assign_clips(records)
    assert clips[18:20] == [0, 1]  # 190s cut, despite a smaller gap nearer 240s.
    spans = clip_spans(records, clips)
    assert all(180 <= span <= 300 for span in spans[:-1])
    assert 0 <= spans[-1] <= 300


@pytest.mark.parametrize("count,spans", [(18, [180]), (30, [300]), (65, [240, 240, 170])])
def test_clip_length_limits_target_tie_and_short_final_tail(count, spans):
    records = [utterance(i) for i in range(count)]
    assert clip_spans(records, w.assign_clips(records)) == spans


@pytest.mark.parametrize("boundary", [180, 300])
def test_cut_candidate_boundaries_are_inclusive(boundary):
    records = []
    start = 0
    for index in range(65):
        records.append(utterance(index, start, start + 10))
        start += 10 + (5 if index == boundary // 10 - 1 else 0)
    assert clip_spans(records, w.assign_clips(records))[0] == boundary


def test_forced_gap_permits_short_tail_only_at_run_end():
    records = [utterance(i) for i in range(65)]
    offset = records[-1]["end"] + 20.01
    records += [utterance(65 + i, offset + i * 10, offset + i * 10 + 10) for i in range(40)]
    clips = w.assign_clips(records)
    assert clip_spans(records, clips) == [240, 240, 170, 240, 160]
    assert clips[64] != clips[65]
    assert w.context_indices(records, 65) == []
    assert w.assign_clips([]) == []


def test_many_synthetic_clips_respect_limits_and_forced_boundaries():
    rng = random.Random(5)
    records = []
    start = 0
    for index in range(1500):
        duration = rng.uniform(.1, 12)
        records.append(utterance(index, start, start + duration))
        start += duration + rng.choice([0, 1, 5, 20, 20.01])
    clips = w.assign_clips(records)
    for clip in sorted(set(clips)):
        indices = [i for i, value in enumerate(clips) if value == clip]
        first, last = indices[0], indices[-1]
        span = max(u["end"] for u in records[first:last + 1]) - records[first]["start"]
        assert span <= 300
        if span < 180 and last + 1 < len(records):
            assert records[last + 1]["start"] - records[last]["end"] > 20
        for i in indices[1:]:
            assert records[i]["start"] - records[i - 1]["end"] <= 20


def test_read_groups_sources_sorts_times_and_rejects_duplicate_ids(tmp_path):
    save_jsonl(tmp_path / "mixed.jsonl", [utterance(3), utterance(0, source="yt:other"), utterance(1)])
    grouped = w.read_utterances(tmp_path)
    assert [u["utt"] for u in grouped["yt:fixture"]] == [1, 3]
    assert len(grouped) == 2
    save_jsonl(tmp_path / "duplicate.jsonl", [utterance(3)])
    with pytest.raises(ValueError, match="duplicate utterance id"):
        w.read_utterances(tmp_path)


@pytest.mark.parametrize("budget,dropped", [(17, 0), (13, 1), (9, 2), (5, 3)])
def test_truncation_drops_whole_oldest_context_first(budget, dropped):
    context = [[1, 2, 3], [4, 5, 6], [7, 8, 9]]
    current = [10, 11, 12]
    original = copy.deepcopy(context)
    result = w.truncate_tokens(context, current, budget)
    assert result.context == tuple(tuple(turn) for turn in context[dropped:])
    assert result.current == tuple(current)
    assert result.dropped_context == dropped
    assert not result.current_truncated
    assert w.total_tokens(result.context, result.current) == budget
    assert context == original and current == [10, 11, 12]


@pytest.mark.parametrize("context", [[], [[1], [2], [3]]])
def test_current_left_truncation_keeps_last_tokens(context):
    result = w.truncate_tokens(context, list(range(20)), 8)
    assert result.context == ()
    assert result.current == tuple(range(14, 20))
    assert result.current_truncated
    assert result.dropped_context == len(context)
    assert w.total_tokens(result.context, result.current) == 8


def test_truncation_empty_turns_and_special_token_minimum():
    assert w.truncate_tokens([], [1, 2], 2).current == ()
    assert w.truncate_tokens([], [], 2).current_truncated is False
    assert w.total_tokens([[], [], []], []) == 5
    for budget in (1, -1, 2.5, True):
        with pytest.raises(ValueError, match="integer >= 2"):
            w.truncate_tokens([], [], budget)


def test_normalization_model_layout_and_current_segment_ids():
    tokenizer = FixtureTokenizer()
    encoded = w.model_input(["Ａ!", "BB? CC", "DD"], "ＥＥ, FF!", tokenizer)
    assert tokenizer.seen == ["a", "bb cc", "dd", "ee ff"]
    assert encoded == {
        "input_ids": [101, 200, 102, 200, 201, 102, 200, 102, 200, 201, 102],
        "token_type_ids": [0] * 8 + [1] * 3, "attention_mask": [1] * 11,
    }
    encoded = w.model_input(["old turn", "recent turn"], "one two three four five", tokenizer, 5)
    assert encoded["input_ids"] == [101, 202, 203, 204, 102]
    assert encoded["token_type_ids"] == [0, 1, 1, 1, 1]


@pytest.fixture
def built(tmp_path, monkeypatch):
    utterances = tmp_path / "utterances"
    output = tmp_path / "windows"
    manifest = tmp_path / "sources.tsv"
    rows = [manifest_row(), manifest_row("second", "test", "test-channel", "1")]
    # Deliberately disagree with channel hash: windows must trust the stored split.
    assert channel_split(rows[0]["channel_id"]) != rows[0]["split"]
    records = [utterance(i) for i in range(4)]
    save_jsonl(utterances / "synthetic.jsonl", records + [utterance(source="yt:second"),
                                                       utterance(source="yt:unmanifested")])
    save_manifest(manifest, rows)
    tokenizer = FixtureTokenizer()
    monkeypatch.setitem(sys.modules, "transformers", SimpleNamespace(
        AutoTokenizer=SimpleNamespace(from_pretrained=lambda *args, **kwargs: tokenizer)))
    args = ["--utterances", str(utterances), "--manifest", str(manifest), "--out-dir", str(output)]
    assert w.main(args) == 0
    return SimpleNamespace(utterances=utterances, output=output, manifest=manifest,
                           args=args, rows=rows, records=records)


def test_cli_manifest_split_lowdensity_skip_stats_and_full_text(built):
    stats = json.loads((built.output / "stats.json").read_text(encoding="utf-8"))
    assert stats["splits"] == {
        "train": dict(windows=0, sources=0, channels=0, clips=0),
        "dev": dict(windows=4, sources=1, channels=1, clips=1),
        "test": dict(windows=1, sources=1, channels=1, clips=1),
    }
    assert stats["utterance_sources_present"] == 3
    assert stats["skipped_sources"] == [dict(source="yt:unmanifested", utterances=1, reason="no manifest row")]
    assert stats["context_length_histogram"] == {"0": 2, "1": 1, "2": 1, "3": 1}
    assert stats["tokens"]["median"] == 9
    assert stats["tokens"]["p90"] == pytest.approx(15.4)
    assert stats["tokens"]["p99"] == pytest.approx(16.84)
    assert stats["tokens"]["max"] == 17
    assert stats["tokens"]["over_limit_share"] == 0
    assert stats["check"]["error_count"] == stats["check"]["channel_overlap"] == 0
    windows = w.read_jsonl(built.output / "dev.jsonl")
    assert windows[-1]["current"] == built.records[-1]["text"]


def test_offline_check_never_imports_transformers(built, monkeypatch, capsys):
    monkeypatch.setitem(sys.modules, "transformers", None)
    assert w.main([*built.args, "--check"]) == 0
    result = json.loads(capsys.readouterr().out.splitlines()[-1])
    assert result == dict(windows_checked=5, channel_overlap=0, error_count=0, errors=[])


@pytest.mark.parametrize("status", ["rejected", "candidate", "failed"])
def test_status_filter_build_and_check_reject_stale_windows(built, status):
    built.rows[0]["status"] = status
    save_manifest(built.manifest, built.rows)
    assert w.main([*built.args, "--check"]) == 1
    assert w.main(built.args) == 0
    assert w.read_jsonl(built.output / "dev.jsonl") == []
    stats = json.loads((built.output / "stats.json").read_text(encoding="utf-8"))
    assert dict(source="yt:fixture", utterances=4, reason=f"status:{status}") in stats["skipped_sources"]
    assert stats["splits"]["dev"]["sources"] == stats["splits"]["dev"]["channels"] == 0
    assert stats["downloaded_splits"]["dev"] == dict(sources=0, channels=0)
    assert w.main([*built.args, "--check"]) == 0


def test_downloaded_inventory_separate_from_window_counts_and_warning_threshold(built):
    built.rows += [manifest_row("no-utterances", channel="another-channel"),
                   manifest_row("same-channel"),
                   manifest_row("rejected", channel="rejected-channel", status="rejected")]
    save_manifest(built.manifest, built.rows)
    assert w.main([*built.args, "--min-channels", "1"]) == 0
    stats = json.loads((built.output / "stats.json").read_text(encoding="utf-8"))
    assert stats["downloaded_splits"]["dev"] == dict(sources=3, channels=2)
    assert stats["splits"]["dev"]["sources"] == stats["splits"]["dev"]["channels"] == 1
    assert stats["warnings"] == []
    assert w.main([*built.args, "--min-channels", "2"]) == 0
    stats = json.loads((built.output / "stats.json").read_text(encoding="utf-8"))
    assert stats["warnings"] == [dict(split=split, reason="channels_below_minimum", channels=1, minimum=2)
                                 for split in ("dev", "test")]
    assert w.main([*built.args, "--min-channels", "0"]) == 0
    assert json.loads((built.output / "stats.json").read_text(encoding="utf-8"))["warnings"] == []
    assert w.main([*built.args, "--min-channels", "-1"]) == 1


def test_windows_checks_pins_and_rejects_conflicts(built):
    path = built.manifest.with_name("split-overrides.tsv")
    path.write_text("channel_id\tsplit\treason\nfixture-channel\tdev\tgold\n", encoding="utf-8")
    assert w.main(built.args) == w.main([*built.args, "--check"]) == 0
    path.write_text("channel_id\tsplit\treason\nfixture-channel\ttrain\tgold\n", encoding="utf-8")
    assert w.main(built.args) == w.main([*built.args, "--check"]) == 1
    path.write_text("channel_id\tsplit\treason\ntest-channel\tdev\tgold\n", encoding="utf-8")
    assert w.main(built.args) == w.main([*built.args, "--check"]) == 1
    path.write_text("channel_id\tsplit\treason\nfixture-channel\tdev\tgold\nfixture-channel\ttest\tgold\n", encoding="utf-8")
    assert w.main(built.args) == w.main([*built.args, "--check"]) == 1


@pytest.mark.parametrize("mutation", ["duplicate", "unresolved", "future", "reverse", "gap",
                                     "clip", "split", "current", "missing", "schema", "json"])
def test_check_rejects_corruption_and_returns_exit_one(built, mutation):
    path = built.output / "dev.jsonl"
    records = w.read_jsonl(path)
    if mutation == "duplicate":
        records.append(records[0])
    elif mutation == "unresolved":
        records[0]["id"] = "yt:fixture#999"
    elif mutation == "future":
        records[0]["context"] = [records[-1]["current"]]
    elif mutation == "reverse":
        records[-1]["context"].reverse()
    elif mutation == "gap":
        items = built.records
        items[-1]["start"] = 100
        items[-1]["end"] = 110
        save_jsonl(built.utterances / "synthetic.jsonl", items)
    elif mutation == "clip":
        records[0]["clip"] = 9
    elif mutation == "split":
        records[0]["split"] = "train"
    elif mutation == "current":
        records[0]["current"] = "changed synthetic fixture"
    elif mutation == "missing":
        records.pop()
    elif mutation == "schema":
        records[0]["clip"] = -1
    elif mutation == "json":
        path.write_text('{"fixture":', encoding="utf-8")
    if mutation != "json":
        save_jsonl(path, records)
    assert w.main([*built.args, "--check"]) == 1


@pytest.mark.parametrize("violation", ["lowdensity", "lowdensity-peer", "channel-overlap", "channel", "split"])
def test_invalid_manifest_rejected_by_build_and_check(built, violation):
    rows = built.rows
    if violation == "lowdensity":
        rows[0]["lowdensity"] = "1"
    elif violation == "lowdensity-peer":
        rows[1]["channel_id"] = rows[0]["channel_id"]
    elif violation == "channel-overlap":
        rows[1].update(channel_id=rows[0]["channel_id"], lowdensity="0")
    elif violation == "channel":
        rows[0]["channel_id"] = ""
    elif violation == "split":
        rows[0]["split"] = ""
    save_manifest(built.manifest, rows)
    assert w.main(built.args) == 1
    assert w.main([*built.args, "--check"]) == 1


def test_channel_overlap_in_output_reported(built):
    records = w.read_jsonl(built.output / "dev.jsonl")
    save_jsonl(built.output / "train.jsonl", [records[0] | {"split": "train"}])
    result = w.check_windows(built.output, w.read_utterances(built.utterances), w.read_manifest(built.manifest))
    assert result["channel_overlap"] == 1
    assert result["error_count"] > 0


def test_token_report_counts_each_drop_and_current_truncation_without_changing_text():
    windows = []
    for index, (context, current) in enumerate([
        (["a " * 40] * 3, "b " * 40),  # 165 -> drop 1
        (["a " * 70] * 3, "b " * 40),  # 255 -> drop 2
        (["a " * 70] * 3, "b " * 100),  # 315 -> drop 3
        (["a"] * 3, "b " * 200),  # current alone requires left truncation
    ]):
        windows.append(dict(id=f"yt:fixture#{index}", source="yt:fixture", split="dev",
                            clip=0, context=context, current=current))
    original = copy.deepcopy(windows)
    stats = w.make_stats(windows, {}, {"fixture": manifest_row()}, FixtureTokenizer())
    assert stats["tokens"]["context_turns_dropped"] == {"0": 0, "1": 1, "2": 1, "3": 2}
    assert stats["tokens"]["current_truncated"] == 1
    assert stats["tokens"]["over_limit"] == 4
    assert stats["tokens"]["over_limit_share"] == 1
    assert windows == original


def test_empty_stats_and_check_missing_output(tmp_path):
    stats = w.make_stats([], {}, {}, FixtureTokenizer())
    assert stats["tokens"]["median"] is stats["tokens"]["max"] is None
    assert stats["tokens"]["over_limit_share"] == 0
    assert w.check_windows(tmp_path, {}, {})["error_count"] == 3


def test_malformed_json_does_not_echo_payload(tmp_path):
    path = tmp_path / "bad.jsonl"
    path.write_text('{"text":"private fixture text"', encoding="utf-8")
    with pytest.raises(ValueError, match="invalid JSON") as error:
        w.read_jsonl(path)
    assert "private fixture text" not in str(error.value)
