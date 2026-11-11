from copy import deepcopy
import json
import random
from pathlib import Path

import pytest

from pipeline import blocks as b


def utterances(times, source="yt:v"):
    return [dict(source=source, utt=i * 2, start=start, end=end, text=f"turn {i}",
                 asr="whisper", speaker=None) for i, (start, end) in enumerate(times)]


def manifest(video="v", **kw):
    return {video: dict(video_id=video, channel_id="c", split="train", lowdensity="0", status="downloaded", **kw)}


def approvals(decider="person", decision="approve"):
    return {"v": dict(video_id="v", decision=decision, reason="", decided_by=decider)}


@pytest.mark.parametrize("gap,sizes", [(20, [1, 1]), (19.99, [2])])
def test_silence_exact_boundary(gap, sizes):
    result = b.build_source(utterances([(0, 1), (1 + gap, 2 + gap)]), "train")
    assert [len(x["utts"]) for x in result] == sizes


@pytest.mark.parametrize("end,sizes", [(90, [2]), (90.001, [1, 1])])
def test_span_exact_boundary(end, sizes):
    result = b.build_source(utterances([(0, 70), (71, end)]), "train")
    assert [len(x["utts"]) for x in result] == sizes


def test_twelve_turns_and_final_reason():
    result = b.build_source(utterances([(i, i + .5) for i in range(13)]), "train")
    assert [len(x["utts"]) for x in result] == [12, 1]
    assert [x["closed_by"] for x in result] == ["turns", "end_of_source"]
    assert result[1]["prefix"] == ["turn 10", "turn 11"]
    assert result[1]["utts"] == [24]  # original indices, not positions


def test_long_single_turn_never_split():
    result = b.build_source(utterances([(0, 150), (151, 152)]), "train")
    assert [x["features"]["seconds"] for x in result] == [150, 1]
    assert result[0]["closed_by"] == "length"


@pytest.mark.parametrize("gap,prefix", [(60, ["turn 0", "turn 1"]), (60.01, []), (601, [])])
def test_prefix_cut_and_gap_cap(gap, prefix):
    result = b.build_source(utterances([(0, 1), (2, 3), (3 + gap, 4 + gap)]), "train")
    assert result[0]["prefix"] == []
    assert result[0]["gap_before"] is None
    assert result[0]["features"]["gap_before"] == 600
    assert result[1]["prefix"] == prefix
    assert result[1]["gap_before"] == gap
    assert result[1]["features"]["gap_before"] == min(gap, 600)


def test_prefix_config_and_one_turn_previous():
    result = b.build_source(utterances([(0, 1), (22, 23)]), "train", b.Settings(prefix_gap=20))
    assert result[1]["prefix"] == []
    result = b.build_source(utterances([(0, 1), (22, 23)]), "train")
    assert result[1]["prefix"] == ["turn 0"]
    assert b.build_source(utterances([(0, 1)], "yt:other"), "test")[0]["prefix"] == []


def test_features_hand_example_and_zero_span():
    result = b.build_source(utterances([(0, 2), (3, 7), (10, 12)]), "train")
    assert result[0]["features"] == dict(n_turns=3, seconds=12, speech_share=8/12, median_gap=2, gap_before=600)
    single = b.build_source(utterances([(0, 0)]), "train")[0]
    assert single["features"]["speech_share"] == single["features"]["median_gap"] == 0


def test_overlapping_timestamps_use_max_end_and_unclamped_features():
    result = b.build_source(utterances([(0, 10), (1, 2)]), "train")[0]
    assert result["end"] == 10
    assert result["features"]["speech_share"] == 1.1
    assert result["features"]["median_gap"] == -9


def test_reason_ties_have_stable_precedence():
    turns = utterances([(0, 1)])
    assert b.closing_reason(turns, utterances([(30, 100)])[0], b.Settings(max_turns=1)) == "silence"
    assert b.closing_reason(turns, utterances([(2, 100)])[0], b.Settings(max_turns=1)) == "length"
    assert b.closing_reason(turns, None, b.Settings(max_turns=1)) == "end_of_source"


@pytest.mark.parametrize("decider,decision,allow,eligible", [
    ("person", "approve", False, True), ("claude", "approve", False, False),
    ("claude", "approve", True, True), (" Claude ", "approve", False, False),
    ("", "approve", False, False), ("  ", "approve", True, False),
    ("person", "reject", True, False), ("person", "hold", False, False),
])
def test_eligibility(decider, decision, allow, eligible):
    assert (b.skip_reason("yt:v", manifest(), approvals(decider, decision), allow) is None) == eligible


def test_missing_approval_manifest_and_status():
    assert b.skip_reason("yt:v", manifest(), {}) == "no approval row"
    assert b.skip_reason("yt:x", manifest(), approvals()) == "no manifest row"
    row = manifest()
    row["v"]["status"] = "rejected"
    assert b.skip_reason("yt:v", row, approvals()) == "status:rejected"


@pytest.mark.parametrize("fifth", [False, True])
def test_approval_four_five_columns_by_header(tmp_path, fifth):
    path = tmp_path / "a.tsv"
    # Deliberately reordered header.
    path.write_text("decided_by\treason\tvideo_id\tdecision" + ("\tcomment" if fifth else "") + "\n"
                    + "person\t\tv\tapprove" + ("\t" if fifth else "") + "\n", encoding="utf-8")
    assert b.read_approvals(path)["v"]["decided_by"] == "person"


def test_approval_retry_after_partial_write(tmp_path, monkeypatch):
    path = tmp_path / "a.tsv"
    path.write_text("video_id\tdecision\treason\tdecided_by\nv\tapprove\n", encoding="utf-8")
    retries = []
    def repair(seconds):
        retries.append(seconds)
        path.write_text("video_id\tdecision\treason\tdecided_by\nv\tapprove\t\tperson\n", encoding="utf-8")
    monkeypatch.setattr(b.time, "sleep", repair)
    assert b.read_approvals(path)["v"]["decided_by"] == "person"
    assert len(retries) == 1


def test_approval_fails_closed_after_retry(tmp_path, monkeypatch):
    path = tmp_path / "a.tsv"
    path.write_text("bad\n", encoding="utf-8")
    retries = []
    monkeypatch.setattr(b.time, "sleep", retries.append)
    with pytest.raises(ValueError):
        b.read_approvals(path)
    assert len(retries) == 1


def test_prescreened_default_output_refused_before_reading(capsys):
    assert b.main(["--allow-prescreened"]) == 1
    assert b.main(["sweep", "--allow-prescreened", "--out-dir", str(b.DEFAULT_OUTPUT / ".." / "blocks")]) == 1
    assert "different --out-dir" in capsys.readouterr().err


def test_fit_unchanged_exact_and_immutable():
    prefix, turns = [[1], [2]], [[3], [4]]
    before = deepcopy((prefix, turns))
    result = b.fit_tokens(prefix, turns, 9)
    assert result.prefix_indices == (0, 1) and result.turn_indices == (0, 1)
    assert not result.dropped_prefix and not result.dropped_middle
    assert (prefix, turns) == before
    assert b.total_tokens(result.prefix, result.turns) == 9


def test_fit_drops_oldest_prefix_first():
    result = b.fit_tokens([[1], [2]], [[3]], 5)
    assert result.prefix_indices == (1,)
    assert result.turn_indices == (0,)
    assert result.dropped_prefix == 1


@pytest.mark.parametrize("n,removed", [(6, [2, 3, 1, 4]), (7, [3, 2, 4, 1, 5])])
def test_fit_middle_alternates_centre_out(n, removed):
    for count in range(1, len(removed) + 1):
        result = b.fit_tokens([[9]], [[i] for i in range(n)], 1 + 2 * (n - count))
        assert result.prefix_indices == ()
        assert result.turn_indices == tuple(i for i in range(n) if i not in removed[:count])
        assert result.dropped_middle == count
        assert not result.dropped_earliest


def test_fit_endpoint_fallback_and_last_truncation():
    result = b.fit_tokens([], [[0] * 10, [1] * 2], 5)
    assert result.turn_indices == (1,)
    assert result.dropped_earliest and not result.last_truncated
    result = b.fit_tokens([[99]], [[1, 2, 3, 4, 5]], 4)
    assert result.turns == ((4, 5),) and result.turn_indices == (0,)
    assert result.dropped_prefix == 1 and result.last_truncated
    assert b.fit_tokens([], [[1, 2]], 2).turns == ((),)


@pytest.mark.parametrize("limit", [0, 1, 2.1, True])
def test_fit_bad_limit(limit):
    with pytest.raises(ValueError):
        b.fit_tokens([], [[1]], limit)


def test_fit_empty_block():
    with pytest.raises(ValueError):
        b.fit_tokens([], [])


class Tokenizer:
    cls_token_id = 101
    sep_token_id = 102
    def __init__(self):
        self.inputs = []
    def encode(self, text, add_special_tokens):
        assert add_special_tokens is False
        self.inputs.append(text)
        return [len(t) for t in text.split()]


def test_model_input_normalization_specials_segments():
    tokenizer = Tokenizer()
    encoded = b.model_input(["Ａ!"], ["B.. C", "D"], tokenizer)
    assert tokenizer.inputs == ["a", "b c", "d"]
    assert encoded == dict(input_ids=[101, 1, 102, 1, 1, 102, 1, 102],
                           token_type_ids=[0, 0, 0, 1, 1, 1, 1, 1], attention_mask=[1] * 8)


def test_token_stats_all_branches():
    class LengthTokenizer:
        def encode(self, text, add_special_tokens):
            return [0] * int(text)
    records = [dict(prefix=[], turns=["1"]), dict(prefix=["10"], turns=["1"]),
               dict(prefix=[], turns=["1", "10", "1"]),
               dict(prefix=[], turns=["10", "1"]), dict(prefix=[], turns=["10"])]
    stats = b.token_stats(records, LengthTokenizer(), 5)
    assert stats["branches"] == dict(unchanged=1, drop_prefix=1, drop_middle=1, drop_earliest=1, truncate_last=1)
    assert stats["over_limit"] == 4
    assert stats["max"] == 16


def save_blocks(path, blocks):
    for split in b.SPLITS:
        (path / f"{split}.jsonl").write_text("".join(json.dumps(x) + "\n" for x in blocks if x["split"] == split), encoding="utf-8")


def test_partition_property_randomized():
    rng = random.Random(22)
    for _ in range(120):
        times, end = [], 0
        for i in range(rng.randint(1, 80)):
            start = end + rng.choice([0, 1, 19.99, 20, 61])
            end = start + rng.choice([0, 1, 12, 95])
            times.append((start, end))
        us = utterances(times)
        settings = b.Settings(rng.choice([10, 20, 30]), rng.choice([60, 90, 120]), rng.choice([1, 8, 12, 16]))
        result = b.build_source(us, "train", settings)
        assert [i for x in result for i in x["utts"]] == [u["utt"] for u in us]
        for block in result:
            members = [u for u in us if u["utt"] in block["utts"]]
            assert len(members) <= settings.max_turns
            assert len(members) == 1 or block["features"]["seconds"] <= settings.max_seconds
            assert all(y["start"] - x["end"] < settings.silence for x, y in zip(members, members[1:]))


@pytest.mark.parametrize("corrupt", ["loss", "overlap", "index", "prefix", "turns", "closed_by", "split", "ineligible", "duplicate"])
def test_checker_detects_corruption(tmp_path, corrupt):
    grouped = {"yt:v": utterances([(0, 1), (22, 23), (24, 25)])}
    records = b.build_all(grouped, manifest(), approvals(), b.Settings())
    save_blocks(tmp_path, records)
    assert b.check_blocks(tmp_path, grouped, manifest(), approvals())["error_count"] == 0
    if corrupt == "loss":
        records.pop()
    elif corrupt == "duplicate":
        records.append(deepcopy(records[0]))
    elif corrupt == "ineligible":
        save_blocks(tmp_path, records)
        assert b.check_blocks(tmp_path, grouped, manifest(), approvals("claude"))["error_count"] > 0
        return
    elif corrupt == "overlap":
        records[1]["utts"][0] = records[0]["utts"][0]
    elif corrupt == "index":
        records[0]["utts"][0] = 999
    elif corrupt == "split":
        records[0]["split"] = "dev"
    else:
        records[1][corrupt] = [] if corrupt == "prefix" else ["changed"] if corrupt == "turns" else "turns"
    save_blocks(tmp_path, records)
    assert b.check_blocks(tmp_path, grouped, manifest(), approvals())["error_count"] > 0


def test_manifest_channel_lowdensity_and_overrides():
    rows = manifest()
    rows["x"] = dict(rows["v"], video_id="x", split="dev")
    assert any("multiple splits" in e for e in b.manifest_errors(["yt:v", "yt:x"], rows))
    rows["x"]["lowdensity"] = "1"
    assert any("lowdensity" in e for e in b.manifest_errors(["yt:v"], rows))
    assert any("override" in e for e in b.manifest_errors(["yt:v"], rows, {"c": "train"}))
    rows = manifest()
    assert any("disagrees" in e for e in b.manifest_errors(["yt:v"], rows, {"c": "dev"}))


def test_empty_stats_and_all_grid_rows():
    stats = b.make_stats([], {}, {}, {}, Tokenizer(), b.Settings())
    assert stats["tokens"]["median"] is None
    assert stats["tokens"]["over_320_share"] == 0
    assert len(stats["warnings"]) == 2
    result = b.sweep({}, {}, {}, Tokenizer())
    assert len(result["rows"]) == 27
    assert result["audio_hours"] == 0


def test_read_utterances_grouping_order_duplicate_and_manifest(tmp_path):
    records = utterances([(1, 2), (0, 1)])
    path = tmp_path / "arbitrary.jsonl"
    path.write_text("".join(json.dumps(r) + "\n" for r in records), encoding="utf-8")
    assert [r["utt"] for r in b.read_utterances(tmp_path)["yt:v"]] == [2, 0]
    path.write_text(json.dumps(records[0]) + "\n" + json.dumps(records[0]) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate"):
        b.read_utterances(tmp_path)
    path = tmp_path / "m.tsv"
    path.write_text("video_id\tchannel_id\tsplit\tlowdensity\tstatus\nv\tc\ttrain\t0\tdownloaded\n", encoding="utf-8")
    assert b.read_manifest(path)["v"]["split"] == "train"


def test_cli_build_check_and_sweep_without_transcript_logs(tmp_path, monkeypatch, capsys):
    import sys
    from types import SimpleNamespace
    monkeypatch.setattr(b, "DEFAULT_OUTPUT", tmp_path / "training")
    us = tmp_path / "u"
    us.mkdir()
    data = utterances([(0, 1), (3, 4), (22, 23)])
    (us / "u.jsonl").write_text("".join(json.dumps(r) + "\n" for r in data), encoding="utf-8")
    m, a, output = tmp_path / "m.tsv", tmp_path / "a.tsv", tmp_path / "out"
    m.write_text("video_id\tchannel_id\tsplit\tlowdensity\tstatus\tduration_s\nv\tc\ttrain\t0\tdownloaded\t100\n", encoding="utf-8")
    a.write_text("video_id\tdecision\treason\tdecided_by\nv\tapprove\t\tclaude\n", encoding="utf-8")
    class AutoTokenizer:
        @staticmethod
        def from_pretrained(name, **kwargs):
            assert name == b.TOKENIZER and kwargs["local_files_only"] is True
            return Tokenizer()
    monkeypatch.setitem(sys.modules, "transformers", SimpleNamespace(AutoTokenizer=AutoTokenizer))
    args = ["--utterances", str(us), "--manifest", str(m), "--approvals", str(a), "--out-dir", str(output)]
    assert b.main(args) == 0
    assert json.loads((output / "stats.json").read_text(encoding="utf-8"))["splits"]["train"]["blocks"] == 0
    assert b.main([*args, "--allow-prescreened", "--max-turns", "1"]) == 0
    stats = json.loads((output / "stats.json").read_text(encoding="utf-8"))
    assert stats["prescreened_included"] is True
    assert stats["splits"]["train"]["blocks"] == 3
    # Checker reads saved nondefault settings; never loads a tokenizer.
    monkeypatch.setitem(sys.modules, "transformers", None)
    assert b.main([*args, "--allow-prescreened", "--check"]) == 0
    assert b.main([*args, "--check"]) == 1
    monkeypatch.setitem(sys.modules, "transformers", SimpleNamespace(AutoTokenizer=AutoTokenizer))
    before = {p: p.read_bytes() for p in tmp_path.rglob("*") if p.is_file()}
    assert b.main(["sweep", *args, "--allow-prescreened"]) == 0
    assert before == {p: p.read_bytes() for p in tmp_path.rglob("*") if p.is_file()}
    report = tmp_path / "s.json"
    assert b.main(["sweep", *args, "--allow-prescreened", "--json", str(report)]) == 0
    sweep = json.loads(report.read_text(encoding="utf-8"))
    assert sweep["audio_hours"] == 100 / 3600 and len(sweep["rows"]) == 27
    assert sweep["prescreened_included"] is True
    logged = capsys.readouterr()
    assert all(r["text"] not in logged.out and r["text"] not in logged.err for r in data)


def test_sweep_setting_columns_do_not_collide_with_closing_shares():
    result = b.sweep({"yt:v": utterances([(0, 1), (22, 23)])}, manifest(), approvals(), Tokenizer())
    row = result["rows"][0]
    assert row["silence"] == 10
    assert row["closed_silence_share"] == .5
    assert row["closed_end_of_source_share"] == .5
