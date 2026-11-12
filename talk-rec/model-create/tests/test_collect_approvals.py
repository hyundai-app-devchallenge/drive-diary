"""T20 approval format compatibility; no change to collector gating/checks."""

import csv
import os

import pytest

from pipeline import collect as c


def test_four_to_five_migration_idempotent_and_read_only_check(tmp_path, monkeypatch, capsys):
    path = tmp_path / "data/approved.tsv"
    path.parent.mkdir()
    path.write_text("video_id\tdecision\treason\tdecided_by\n00000000001\tapprove\topinion\tclaude\n", encoding="utf-8")
    before = path.read_bytes()
    approvals = c.read_approvals(path)
    assert approvals["00000000001"]["comment"] == ""
    assert path.read_bytes() == before
    monkeypatch.setattr(c, "ROOT", tmp_path)
    assert c.main(["--check"]) == 0
    assert "0 error(s)" in capsys.readouterr().out
    assert path.read_bytes() == before
    assert c.migrate_approvals(path)
    with path.open(encoding="utf-8", newline="") as stream:
        assert next(csv.reader(stream, delimiter="\t")) == c.APPROVAL_COLUMNS
    assert c.read_approvals(path) == approvals
    # Fixed old timestamp proves a second migration does not replace the file.
    os.utime(path, ns=(1000000000, 1000000000))
    migrated = path.read_bytes(), path.stat().st_mtime_ns
    assert not c.migrate_approvals(path)
    assert (path.read_bytes(), path.stat().st_mtime_ns) == migrated
    assert c.main(["--check"]) == 0
    assert (path.read_bytes(), path.stat().st_mtime_ns) == migrated


@pytest.mark.parametrize("name,decision,expected", [
    ("claude", "approve", None), ("claude", "reject", None),
    ("", "approve", None), ("  ", "approve", None),
    ("user", "approve", "approve"), ("검토자", "reject", "reject"),
    ("reviewer", "hold", "hold"), ("user", "invalid", None),
])
def test_human_decision(name, decision, expected):
    row = dict(decided_by=name, decision=decision)
    assert c.human_decision(row) == expected
    assert c.is_human_approved("id", {"id": row}) == (expected == "approve")
    assert not c.is_human_approved("missing", {"id": row})
    assert c.human_decision(None) is None


def test_five_column_write_sanitizes_and_failure_keeps_old_file(tmp_path, monkeypatch):
    path = tmp_path / "approved.tsv"
    row = dict(video_id="00000000001", decision="hold", reason="human review", decided_by="user",
               comment=" 합성\t코멘트\r\n" + "가" * 600)
    c.write_approvals(path, {row["video_id"]: row})
    saved = c.read_approvals(path)[row["video_id"]]
    assert saved["comment"].startswith("합성 코멘트 ")
    assert len(saved["comment"]) == 500
    assert len(path.read_text(encoding="utf-8").splitlines()) == 2
    assert not c.write_approvals(path, {row["video_id"]: row})
    before = path.read_bytes()
    def fail(*_):
        raise OSError("simulated replace failure")
    monkeypatch.setattr(c.os, "replace", fail)
    row["decision"] = "approve"
    with pytest.raises(OSError):
        c.write_approvals(path, {row["video_id"]: row})
    assert path.read_bytes() == before
    assert list(tmp_path.glob("*.tmp")) == []
    assert not c.migrate_approvals(tmp_path / "missing.tsv")
