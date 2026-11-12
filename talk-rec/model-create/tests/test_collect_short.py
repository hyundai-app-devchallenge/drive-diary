"""T02d short-video revival and length checks; synthetic manifests/audio only."""

import json

import pytest

from pipeline import collect as c


@pytest.fixture
def manifest(tmp_path):
    return c.Manifest(tmp_path / "data/sources.tsv", tmp_path / "scratch")


def add(manifest, n=1, **changes):
    return manifest.update(f"{n:011d}", **(dict(
        duration_s=90, status="rejected", note="duration_too_short",
        channel_id="UCfamily", title="아이와 가족의 차 안 대화",
    ) | changes))


def decision(value="approve", reviewer="user"):
    return dict(decision=value, decided_by=reviewer, reason="synthetic review")


def offline_only(monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("retry-short must not instantiate a backend or apply decisions")
    monkeypatch.setattr(c, "Youtube", forbidden)
    monkeypatch.setattr(c, "apply_decisions", forbidden)


@pytest.mark.parametrize("duration", [60, 90, 427, 10800])
def test_retry_short_without_approval_preserves_other_cells(manifest, duration):
    video_id = add(manifest, duration_s=duration,
                   note="expanded_from=UCfamily;duration_too_short;speech_share=0.5")["video_id"]
    before = dict(manifest.rows[video_id])
    assert c.retry_short(manifest, {}, [video_id]) == dict(retried=[video_id], failed=0)
    assert manifest.rows[video_id] == before | dict(
        status="candidate", note="expanded_from=UCfamily;speech_share=0.5")
    assert c.Manifest(manifest.path, manifest.scratch).rows == manifest.rows


def test_retry_short_cli_is_offline_and_does_not_require_human_approval(
        manifest, tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(c, "ROOT", tmp_path)
    offline_only(monkeypatch)
    ids = [add(manifest, n)["video_id"] for n in range(1, 5)]
    approvals_path = tmp_path / "data/approved.tsv"
    c.write_approvals(approvals_path, {
        ids[1]: decision("hold"), ids[2]: decision(reviewer="claude"),
        ids[3]: decision(),
    })
    before = approvals_path.read_bytes(), approvals_path.stat().st_mtime_ns
    assert c.main(["retry-short", *ids]) == 0
    assert json.loads(capsys.readouterr().out) == dict(retried=ids, failed=0)
    assert all(row["status"] == "candidate" for row in c.Manifest(
        manifest.path, manifest.scratch).rows.values())
    assert (approvals_path.read_bytes(), approvals_path.stat().st_mtime_ns) == before
    assert not (tmp_path / ".local/audio").exists()


@pytest.mark.parametrize("changes,approval,expected", [
    ({"note": "duration_too_short;not_korean"}, None, "duration_too_short only"),
    ({"note": "duration_too_short;music_or_performance"}, None, "duration_too_short only"),
    ({"note": "duration_too_short;manual:off target"}, None, "duration_too_short only"),
    ({"note": "duration_too_short_extra"}, None, "duration_too_short only"),
    ({"note": ""}, None, "duration_too_short only"),
    ({}, decision("reject"), "human reject"),
    ({"status": "candidate"}, None, "status must be rejected"),
    ({"status": "downloaded"}, None, "status must be rejected"),
    ({"status": "failed"}, None, "status must be rejected"),
    ({"duration_s": 59.9}, None, "duration must be 60"),
    ({"duration_s": 10801}, None, "duration must be 60"),
    ({"duration_s": ""}, None, "duration must be 60"),
    ({"duration_s": "unknown"}, None, "duration must be 60"),
    ({"duration_s": "nan"}, None, "duration must be 60"),
    ({"duration_s": "inf"}, None, "duration must be 60"),
])
def test_retry_short_validates_entire_batch_before_mutation(
        manifest, tmp_path, monkeypatch, capsys, changes, approval, expected):
    monkeypatch.setattr(c, "ROOT", tmp_path)
    offline_only(monkeypatch)
    good = add(manifest)["video_id"]
    bad = add(manifest, 2, **changes)["video_id"]
    missing = "00000000003"
    approvals_path = tmp_path / "data/approved.tsv"
    c.write_approvals(approvals_path, {bad: approval} if approval else {})
    before = manifest.path.read_bytes(), manifest.path.stat().st_mtime_ns
    approvals_before = approvals_path.read_bytes(), approvals_path.stat().st_mtime_ns
    assert c.main(["retry-short", good, bad, missing]) == 2
    captured = capsys.readouterr()
    summary = json.loads(captured.out)
    assert summary["retried"] == [] and summary["failed"] == 2
    assert [error["video_id"] for error in summary["errors"]] == [bad, missing]
    assert bad in captured.err and expected in captured.err
    assert missing in captured.err and "not in manifest" in captured.err
    assert (manifest.path.read_bytes(), manifest.path.stat().st_mtime_ns) == before
    assert (approvals_path.read_bytes(), approvals_path.stat().st_mtime_ns) == approvals_before
    assert c.Manifest(manifest.path, manifest.scratch).rows == manifest.rows
    assert not (tmp_path / ".local").exists()


@pytest.mark.parametrize("minimum,duration,success", [(30, 30, True), (120, 90, False)])
def test_retry_short_cli_explicit_minimum(manifest, tmp_path, monkeypatch, capsys,
                                         minimum, duration, success):
    monkeypatch.setattr(c, "ROOT", tmp_path)
    offline_only(monkeypatch)
    video_id = add(manifest, duration_s=duration)["video_id"]
    # Global options before the command must use the same command-specific default.
    assert c.main(["--approvals", str(tmp_path / "missing.tsv"), "retry-short",
                   video_id, "--min-duration", str(minimum)]) == (0 if success else 2)
    assert json.loads(capsys.readouterr().out)["failed"] == (0 if success else 1)


@pytest.mark.parametrize("minimum", ["0", "-1", "nan", "inf", "10801"])
def test_retry_short_invalid_minimum_never_writes(manifest, tmp_path, monkeypatch, capsys, minimum):
    monkeypatch.setattr(c, "ROOT", tmp_path)
    video_id = add(manifest)["video_id"]
    before = manifest.path.read_bytes()
    assert c.main(["retry-short", video_id, "--min-duration", minimum]) == 2
    assert "min-duration" in capsys.readouterr().err
    assert manifest.path.read_bytes() == before


def test_retry_short_requires_explicit_ids(manifest, tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(c, "ROOT", tmp_path)
    add(manifest)
    before = manifest.path.read_bytes()
    assert c.main(["retry-short"]) == 2
    assert "explicit video IDs" in capsys.readouterr().err
    assert manifest.path.read_bytes() == before


class Backend:
    def __init__(self, directory, base_duration, **metadata):
        self.audio_dir = directory
        self.duration = base_duration
        self.extra = metadata
        self.downloads = []

    def metadata(self, video_id):
        return dict(id=video_id, duration=self.duration, channel_id="UCfamily",
                    title="아이와 가족의 차 안 대화", language="ko") | self.extra

    def download(self, video_id):
        self.downloads.append(video_id)
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        (self.audio_dir / f"{video_id}.webm").write_bytes(b"synthetic audio")


@pytest.mark.parametrize("reviewer,duration,minimum,expected", [
    ("user", 90, 480, True), ("user", 60, 480, True), ("user", 59, 480, False),
    ("user", 30, 30, True), ("user", 29, 30, False),
    ("claude", 90, 480, False), ("claude", 479, 480, False), ("claude", 480, 480, True),
])
def test_short_download_length_check(manifest, tmp_path, reviewer, duration, minimum, expected):
    video_id = add(manifest, status="candidate", note="", duration_s=duration)["video_id"]
    backend = Backend(tmp_path / "audio", duration)
    args = c.parser().parse_args(["--min-duration", str(minimum), "--delay", "0"])
    # A short approved video must also fit a budget smaller than the discovery minimum.
    args.max_hours = duration / 3600
    summary = c.collect(manifest, backend, args, {video_id: decision(reviewer=reviewer)})
    assert summary["downloaded"] == int(expected)
    assert backend.downloads == ([video_id] if expected else [])
    assert manifest.rows[video_id]["status"] == ("downloaded" if expected else "rejected")
    if not expected:
        assert manifest.rows[video_id]["note"] == "duration_too_short"


def test_revived_candidate_still_needs_approval_to_download(manifest, tmp_path):
    video_id = add(manifest)["video_id"]
    assert c.retry_short(manifest, {}, [video_id])["failed"] == 0
    backend = Backend(tmp_path / "audio", 90)
    args = c.parser().parse_args([])
    assert c.collect(manifest, backend, args, {})["downloaded"] == 0
    assert backend.downloads == [] and manifest.rows[video_id]["status"] == "candidate"
    assert c.collect(manifest, backend, args, {video_id: decision()})["downloaded"] == 1


@pytest.mark.parametrize("extra,reason", [
    ({"is_live": True}, "live_or_upcoming"),
    ({"channel": "MBC"}, "broadcaster_channel"),
    ({"duration": 10801}, "duration_too_long"),
])
def test_short_human_download_retains_other_rejects(manifest, tmp_path, extra, reason):
    video_id = add(manifest, status="candidate", note="")["video_id"]
    backend = Backend(tmp_path / "audio", 90, **extra)
    result = c.collect(manifest, backend, c.parser().parse_args([]), {video_id: decision()})
    assert result["downloaded"] == 0 and backend.downloads == []
    assert reason in manifest.rows[video_id]["note"].split(";")


def test_default_discovery_minimum_is_unchanged():
    assert c.parser().parse_args([]).min_duration == 480
    info = dict(id="00000000001", duration=90, channel_id="UCfamily", language="ko")
    assert c.filter_reasons(info, c.parser().parse_args([]), {}) == ["duration_too_short"]
    assert c.filter_reasons(info, c.parser().parse_args(["--min-duration", "60"]), {}) == []
