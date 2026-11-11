import json

import pytest

from demo.asr import report
from demo.asr.metrics import summarize
from tools.asrpick.store import PickStore
from tools.listen.storage import atomic_write, encode_lines, read_jsonl
from test_asr_http import comparison_root


def test_report_counts_last_wins_source_and_no_text(tmp_path):
    comparison_root(tmp_path)
    store = PickStore("tester", root=tmp_path)
    first, second, third = [r["id"] for r in store.rows]
    merged = next(i+1 for i,o in enumerate(store.options[first]) if len(o["configs"]) == 2)
    store.save(dict(id=first, choice="all_bad", comment="not-in-report"))
    store.save(dict(id=first, choice=merged, comment="not-in-report"))
    store.save(dict(id=second, choice="similar", comment=""))
    store.save(dict(id=third, choice="all_bad", comment=""))
    for name, records in store.runs.items():
        stats = dict(config=name, clip_count=3, metrics=summarize(list(records.values())),
                     total_seconds=12.5, model_load_seconds=2.5, inference_seconds=10,
                     gpu=dict(peak_mib=1024, samples=50))
        path = tmp_path / ".local/demo/asr" / (name+".stats.json")
        path.write_text(json.dumps(stats), encoding="utf-8")
    out = tmp_path / "report.md"
    result = report.report(store.path, out, root=tmp_path)
    overall = result["overall"]
    assert overall["total"] == 3 and overall["all_bad"] == overall["similar"] == 1
    for name in store.options[first][merged-1]["configs"]:
        assert overall["configs"][name] == dict(count=1, rate=1/3)
        assert result["per_source"]["yt:IOlxH67P1JM"]["configs"][name]["rate"] == 1
    content = out.read_text(encoding="utf-8")
    assert "33.3%" in content and "12.50" in content and "1024.00" in content
    assert "not-in-report" not in content and "PLACEHOLDER" not in content
    assert not list(tmp_path.glob("*.tmp"))


def test_empty_picks_and_missing_stats(tmp_path):
    comparison_root(tmp_path)
    picks = tmp_path / "picks.jsonl"
    picks.write_bytes(b"")
    out = tmp_path / "report.md"
    result = report.report(picks, out, root=tmp_path)
    assert result["overall"]["total"] == 0
    assert all(c["rate"] == 0 for c in result["overall"]["configs"].values())
    assert "미측정" in out.read_text(encoding="utf-8")


def test_report_rejects_unknown_picks(tmp_path):
    comparison_root(tmp_path)
    picks = tmp_path / "picks.jsonl"
    atomic_write(picks, encode_lines([dict(id="yt:00000000001@0", picked=["unknown"], comment="", decided_at="now")]))
    with pytest.raises(ValueError):
        report.report(picks, tmp_path / "report.md", root=tmp_path)


def test_incomplete_runs_rejected(tmp_path):
    comparison_root(tmp_path)
    path = tmp_path / ".local/demo/asr/turbo-base.jsonl"
    atomic_write(path, encode_lines(read_jsonl(path)[:1]))
    with pytest.raises(ValueError):
        PickStore("tester", root=tmp_path)


def test_mismatched_stats_rejected(tmp_path):
    comparison_root(tmp_path)
    path = tmp_path / ".local/demo/asr/turbo-base.stats.json"
    path.write_text(json.dumps(dict(config="turbo-base", clip_count=2, metrics={})), encoding="utf-8")
    with pytest.raises(ValueError):
        report.report(tmp_path / "no-picks", tmp_path / "report.md", root=tmp_path)
