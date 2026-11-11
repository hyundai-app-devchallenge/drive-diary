"""Curated collection contracts, using synthetic metadata and files only."""

import csv
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

from pipeline import collect as c


def metadata(n=1, **changes):
    return dict(id=f"{n:011d}", channel_id="UCfamily", channel="가족 채널",
                title="차 안 가족 대화", duration=900, language="ko",
                live_status="not_live", description="차 안\t가족\n대화" * 80,
                channel_follower_count=1234, view_count=5678) | changes


def args(**changes):
    return SimpleNamespace(**(dict(search_results=5, max_candidates=150, per_channel=5,
                                  max_videos=10, max_hours=5, min_duration=480,
                                  max_duration=10800, allow_non_korean=False,
                                  allow_music=False, delay=0) | changes))


@pytest.fixture
def manifest(tmp_path):
    return c.Manifest(tmp_path / "data/sources.tsv", tmp_path / "scratch")


def add(manifest, n=1, **changes):
    return manifest.update(f"{n:011d}", **(c.metadata_fields(metadata(n)) | changes))


class Backend:
    def __init__(self, path):
        self.audio_dir = path
        self.calls = []
        self.downloads = []

    def discover(self, source, count):
        self.calls.append((source, count))
        return iter(metadata(n) for n in range(1, count + 1))

    def metadata(self, video_id):
        return metadata(int(video_id))

    def download(self, video_id):
        self.downloads.append(video_id)
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        (self.audio_dir / f"{video_id}.webm").write_bytes(b"audio")


def decision(value, reason="designer decision"):
    return dict(decision=value, reason=reason, decided_by="claude")


@pytest.mark.parametrize("channel", ["MBCNEWS", "KBS Entertain", "SBS", "JTBC Entertainment",
                                      "tvN", "YTN", "연합뉴스", "채널A", "TV조선", "ENA",
                                      "Mnet", "서울뉴스", "EBS Documentary", "디글 클래식", "오분순삭"])
def test_broadcaster_patterns(channel):
    assert "broadcaster_channel" in c.rejection_reasons(metadata(channel=channel))


@pytest.mark.parametrize("title", ["모음", "수다.zip", "레전드", "풀버전", "다시보기",
                                    "하이라이트", "오분순삭", "#shorts", "#SHORTS"])
def test_compilation_patterns(title):
    assert "compilation" in c.rejection_reasons(metadata(title="가족 " + title))


@pytest.mark.parametrize("changes,reason", [
    ({"is_live": True}, "live_or_upcoming"),
    ({"live_status": "is_upcoming"}, "live_or_upcoming"),
    ({"live_status": "post_live"}, "live_or_upcoming"),
    ({"live_status": "was_live"}, "live_archive"),
    ({"was_live": True}, "live_archive"),
    ({"is_premiere": True}, "premiere"),
    ({"premiere_timestamp": 123}, "premiere"),
    ({"release_timestamp": 123}, "premiere"),
    ({"duration": 10801}, "duration_too_long"),
])
def test_new_nonpattern_rejects(changes, reason):
    assert reason in c.rejection_reasons(metadata(**changes))


def test_hard_three_hour_ceiling_and_no_broadcaster_false_positive():
    assert "duration_too_long" in c.rejection_reasons(metadata(duration=10801), maximum=20000)
    assert c.rejection_reasons(metadata(duration=10800, channel="Selena 가족")) == []


def test_discovery_never_downloads_and_caps_new_ids(manifest, tmp_path):
    backend = Backend(tmp_path / "audio")
    add(manifest, 1)
    result = c.discover_candidates(manifest, backend, [c.Source("search", "가족")],
                                   args(max_candidates=2))
    assert result["added"] == 2
    assert len(manifest.rows) == 3 and backend.downloads == []
    row = manifest.rows["00000000002"]
    assert row["status"] == "candidate" and row["query"] == "search: 가족"
    assert len(row["description"]) == 300 and "\t" not in row["description"] and "\n" not in row["description"]
    assert row["channel_subscriber_count"] == "1234" and row["view_count"] == "5678"
    before = manifest.path.read_bytes()
    assert c.discover_candidates(manifest, backend, [c.Source("search", "가족")],
                                args(max_candidates=2, search_results=3))["added"] == 0
    assert manifest.path.read_bytes() == before


def test_automatic_rejection_during_discovery(manifest, tmp_path):
    backend = Backend(tmp_path / "audio")
    backend.metadata = lambda video_id: metadata(int(video_id), channel="MBC", title="가족 모음")
    result = c.discover_candidates(manifest, backend, [c.Source("search", "가족")], args(search_results=1))
    assert result["rejected_by_rule"] == {"broadcaster_channel": 1, "compilation": 1}
    assert manifest.rows["00000000001"]["status"] == "rejected"
    assert backend.downloads == []


def test_download_refuses_hold_reject_unlisted_and_missing_list(manifest, tmp_path):
    backend = Backend(tmp_path / "audio")
    for n in range(1, 5):
        add(manifest, n)
    decisions = {"00000000001": decision("approve"), "00000000002": decision("hold"),
                 "00000000003": decision("reject", "off target")}
    c.collect(manifest, backend, args(), decisions)
    assert backend.downloads == ["00000000001"]
    assert manifest.rows["00000000002"]["status"] == "candidate"
    assert manifest.rows["00000000003"]["status"] == "rejected"
    assert "manual:off target" in manifest.rows["00000000003"]["note"]
    assert manifest.rows["00000000004"]["status"] == "candidate"
    c.collect(manifest, backend, args(approvals=tmp_path / "missing.tsv"))
    assert backend.downloads == ["00000000001"]


def test_approval_does_not_bypass_automatic_filters(manifest, tmp_path):
    add(manifest)
    backend = Backend(tmp_path / "audio")
    backend.metadata = lambda video_id: metadata(channel="MBC")
    c.collect(manifest, backend, args(), {"00000000001": decision("approve")})
    assert backend.downloads == [] and manifest.rows["00000000001"]["status"] == "rejected"


def test_reject_moves_audio_idempotently_and_reapproval_restores(manifest, tmp_path):
    row = add(manifest, status="downloaded", downloaded_at="original timestamp", note="expanded_from=UCfamily")
    audio = tmp_path / "audio"
    audio.mkdir()
    original = audio / "00000000001.webm"
    original.write_bytes(b"preserve me")
    decisions = {row["video_id"]: decision("reject", "no speech")}
    assert c.apply_decisions(manifest, audio, decisions)["audio_moved"] == 1
    archived = audio / "_rejected" / original.name
    assert not original.exists() and archived.read_bytes() == b"preserve me"
    assert row["status"] == "rejected" and row["downloaded_at"] == "original timestamp"
    assert row["note"] == "expanded_from=UCfamily;manual:no speech"
    assert c.check_manifest(manifest, audio) == []
    before = manifest.path.read_bytes()
    assert c.apply_decisions(manifest, audio, decisions)["audio_moved"] == 0
    assert manifest.path.read_bytes() == before
    c.apply_decisions(manifest, audio, {row["video_id"]: decision("approve")})
    assert row["status"] == "downloaded" and original.read_bytes() == b"preserve me"
    assert c.check_manifest(manifest, audio) == []


def test_rejected_move_recovers_interruption(manifest, tmp_path, monkeypatch):
    row = add(manifest, status="downloaded")
    audio = tmp_path / "audio"
    audio.mkdir()
    (audio / "00000000001.webm").write_bytes(b"audio")
    rename = Path.rename
    with monkeypatch.context() as patch:
        patch.setattr(Path, "rename", lambda *a: (_ for _ in ()).throw(OSError("interrupted")))
        with pytest.raises(OSError):
            c.apply_decisions(manifest, audio, {row["video_id"]: decision("reject")})
    assert Path.rename is rename
    c.apply_decisions(manifest, audio, {row["video_id"]: decision("reject")})
    assert c.check_manifest(manifest, audio) == []


def test_check_rejected_audio_bidirectionally(manifest, tmp_path):
    add(manifest, status="rejected")
    archive = tmp_path / "audio/_rejected"
    archive.mkdir(parents=True)
    (archive / "00000000001.webm").write_bytes(b"audio")
    assert c.check_manifest(manifest, archive.parent) == []
    manifest.update("00000000001", status="candidate")
    assert c.check_manifest(manifest, archive.parent) == ["audio with status candidate: _rejected/00000000001.webm"]
    (archive / "unknown.webm").write_bytes(b"orphan")
    assert "audio without manifest row: _rejected/unknown.webm" in c.check_manifest(manifest, archive.parent)


def test_expansion_cap_and_exclusion_of_rejected_channels(manifest, tmp_path):
    add(manifest, 100)
    add(manifest, 101, channel_id="UCreject")
    add(manifest, 102, channel_id="UCreject")  # Mixed decisions veto this channel.
    add(manifest, 103, channel_id="UChold")
    decisions = {"00000000100": decision("approve"), "00000000101": decision("reject"),
                 "00000000102": decision("approve"), "00000000103": decision("hold")}
    backend = Backend(tmp_path / "audio")
    result = c.expand_channels(manifest, backend, decisions, args(per_channel=3))
    assert result["added"] == 3 and result["channels"] == {"UCfamily": 3}
    assert [(s.value, n) for s, n in backend.calls] == [("https://www.youtube.com/channel/UCfamily/videos", 3)]
    assert all("expanded_from=UCfamily" in manifest.rows[f"{n:011d}"]["note"] for n in range(1, 4))
    assert backend.downloads == []
    for cap in (0, c.MAX_PER_CHANNEL + 1):
        with pytest.raises(ValueError, match="per-channel"):
            c.expand_channels(manifest, backend, decisions, args(per_channel=cap))


def test_expansion_shares_auto_filters_and_global_cap(manifest, tmp_path):
    add(manifest, 100)
    backend = Backend(tmp_path / "audio")
    backend.metadata = lambda video_id: metadata(int(video_id), title="가족 모음")
    result = c.expand_channels(manifest, backend, {"00000000100": decision("approve")}, args(max_candidates=2))
    assert result["added"] == 2 and result["rejected_by_rule"] == {"compilation": 2}


@pytest.mark.parametrize("global_cap,expected", [(10, 3), (2, 2)])
def test_channel_query_urls_share_cap_and_auto_rejects(manifest, tmp_path, global_cap, expected):
    add(manifest, 100)
    youtube = c.Youtube(tmp_path / "runner", tmp_path / "audio", Path("fake-deno"))
    calls = []

    def lines(arguments):
        calls.append(arguments)
        ids = [1, 2] if len(calls) == 1 else [2, 3, 4]
        return iter(json.dumps(metadata(n)) for n in ids)

    youtube.lines = lines
    youtube.metadata = lambda video_id: metadata(int(video_id), title="가족 모음")
    result = c.expand_channels(manifest, youtube, {"00000000100": decision("approve")},
                               args(per_channel=3, max_candidates=global_cap,
                                    channel_query=["드라이브", "차 안 & ?"]))
    assert [call[-1] for call in calls] == [
        "https://www.youtube.com/channel/UCfamily/search?query=%EB%93%9C%EB%9D%BC%EC%9D%B4%EB%B8%8C",
        "https://www.youtube.com/channel/UCfamily/search?query=%EC%B0%A8%20%EC%95%88%20%26%20%3F",
    ]
    assert all(call[call.index("--playlist-end") + 1] == "3" for call in calls)
    assert result["channels"] == {"UCfamily": expected}
    assert result["added"] == expected and result["rejected_by_rule"] == {"compilation": expected}
    assert all(manifest.rows[f"{n:011d}"]["status"] == "rejected" for n in range(1, expected + 1))
    assert not youtube.audio_dir.exists()


def test_tsv_migration_preserves_every_legacy_cell_and_order(manifest):
    add(manifest, note="legacy note", downloaded_at="original")
    add(manifest, 2, status="rejected")
    original = [{key: row[key] for key in c.LEGACY_COLUMNS} for row in manifest.rows.values()]
    with manifest.path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=c.LEGACY_COLUMNS, delimiter="\t")
        writer.writeheader()
        writer.writerows(original)
    legacy_bytes = manifest.path.read_bytes()
    restored = c.Manifest(manifest.path, manifest.scratch)
    assert manifest.path.read_bytes() == legacy_bytes  # --check can still be read-only.
    restored.save()
    assert [{key: row[key] for key in c.LEGACY_COLUMNS} for row in restored.rows.values()] == original
    assert all(row[key] == "" for row in restored.rows.values() for key in c.COLUMNS[len(c.LEGACY_COLUMNS):])
    before = restored.path.read_bytes()
    restored.save()
    assert restored.path.read_bytes() == before


def test_density_arithmetic_flag_only_and_rerun(manifest, tmp_path):
    row = add(manifest, note="expanded_from=UCfamily")
    add(manifest, 2, status="rejected")
    stats = tmp_path / "stats"
    stats.mkdir()
    path = stats / "yt_00000000001.stats.json"
    path.write_text(json.dumps(dict(audio_duration=120, vad_removed_seconds=108, utterance_count=9)))
    assert c.mark_density(manifest, stats) == dict(marked=1, low_speech=1, non_korean=0, no_speech=1, failed=0)
    assert row["note"] == "expanded_from=UCfamily;speech_share=0.100000;utterance_count=9;utterances_per_minute=4.500000;no_speech;low_speech"
    assert row["status"] == "candidate" and row["split"] == c.channel_split("UCfamily")
    before = manifest.path.read_bytes()
    c.mark_density(manifest, stats)
    assert manifest.path.read_bytes() == before
    c.mark_density(manifest, stats, threshold=0.1)
    assert "low_speech" not in row["note"]
    assert manifest.rows["00000000002"]["status"] == "rejected"


@pytest.mark.parametrize("text,share", [("가나다라a 123!? 漢字", 0.8), ("ㄱᄀéＡ", 0.5),
                                        ("ABC", 0), ("가나", 1), ("123!? 漢字", None), ("", None)])
def test_hangul_share_counts_only_hangul_and_latin_letters(text, share):
    assert c.hangul_share(text) == share


@pytest.mark.parametrize("threshold,minimum,flags", [
    (0.8, 10, []), (0.81, 10, ["non_korean"]), (0.8, 11, ["no_speech"]),
    (0.81, 11, ["non_korean", "no_speech"]), (0, 0, []),
])
def test_transcript_density_thresholds_and_stale_flags(manifest, tmp_path, threshold, minimum, flags):
    row = add(manifest, status="downloaded", note="keep;non_korean;no_speech;hangul_share=0.1")
    (tmp_path / "yt_00000000001.stats.json").write_text(json.dumps(dict(
        audio_duration=120, vad_removed_seconds=0, utterance_count=10)))
    (tmp_path / "yt_00000000001.jsonl").write_text(
        json.dumps(dict(text="가나"), ensure_ascii=False) + "\n\n" + json.dumps(dict(text="다라a 123 漢字")),
        encoding="utf-8")
    result = c.mark_density(manifest, tmp_path, hangul_threshold=threshold, min_utterances=minimum)
    assert "hangul_share=0.800000" in row["note"] and "utterance_count=10" in row["note"]
    assert [flag for flag in ("non_korean", "no_speech") if flag in row["note"].split(";")] == flags
    assert result["non_korean"] == int("non_korean" in flags)
    assert result["no_speech"] == int("no_speech" in flags)
    assert row["status"] == "downloaded" and row["note"].startswith("keep;")


@pytest.mark.parametrize("text", [None, "", "123 漢字"])
def test_density_missing_or_letterless_transcript_has_unknown_share(manifest, tmp_path, text):
    row = add(manifest)
    (tmp_path / "yt_00000000001.stats.json").write_text(json.dumps(dict(
        audio_duration=120, vad_removed_seconds=120, utterance_count=0)))
    if text is not None:
        (tmp_path / "yt_00000000001.jsonl").write_text(json.dumps(dict(text=text)))
    c.mark_density(manifest, tmp_path)
    assert "hangul_share=" not in row["note"] and "non_korean" not in row["note"]
    assert "no_speech" in row["note"] and "low_speech" in row["note"]


@pytest.mark.parametrize("kwargs", [dict(hangul_threshold=-0.1), dict(hangul_threshold=1.1),
                                    dict(hangul_threshold=float("nan")), dict(min_utterances=-1),
                                    dict(min_utterances=1.5)])
def test_invalid_transcript_thresholds(manifest, tmp_path, kwargs):
    with pytest.raises(ValueError):
        c.mark_density(manifest, tmp_path, **kwargs)


def test_review_flags_cli_is_read_only_and_has_no_transcript(manifest, tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(c, "ROOT", tmp_path)
    note = "low_speech;non_korean;no_speech;speech_share=0.1;hangul_share=0.2;utterance_count=9;utterances_per_minute=4.5;PRIVATE"
    add(manifest, 1, status="downloaded", note=note)
    add(manifest, 2, status="candidate", note=note)
    add(manifest, 3, status="rejected", note=note)
    add(manifest, 4, status="downloaded", note="non_koreanish;manual:no_speech")
    before = manifest.path.read_bytes()
    monkeypatch.setattr(c, "apply_decisions", lambda *a: pytest.fail("inspection applied decisions"))
    assert c.main(["review-flags"]) == 0
    output = capsys.readouterr().out
    assert "PRIVATE" not in output
    assert json.loads(output) == dict(sources=[dict(video_id="00000000001",
        flags=["low_speech", "non_korean", "no_speech"], speech_share=0.1,
        hangul_share=0.2, utterance_count=9, utterances_per_minute=4.5)], failed=0)
    assert manifest.path.read_bytes() == before
    assert c.main(["mark-density"]) == 0
    assert manifest.path.read_bytes() == before


@pytest.mark.parametrize("duration,removed,count", [(0, 0, 0), (100, 101, 1), (100, -1, 1),
                                                       (100, 0, -1), (100, 0, 1.5)])
def test_invalid_density_stats(manifest, tmp_path, duration, removed, count):
    add(manifest)
    (tmp_path / "yt_00000000001.stats.json").write_text(json.dumps(dict(
        audio_duration=duration, vad_removed_seconds=removed, utterance_count=count)))
    with pytest.raises(ValueError, match="density stats"):
        c.mark_density(manifest, tmp_path)


def test_review_contents_sorted_by_channel_and_includes_holds(manifest, tmp_path):
    add(manifest, 1, channel="Z family", query="search: 차 안")
    add(manifest, 2, channel="A family")
    add(manifest, 3)
    add(manifest, 4)
    add(manifest, 5, status="rejected")
    add(manifest, 6, status="downloaded")
    decisions = {"00000000002": decision("hold"), "00000000003": decision("approve"),
                 "00000000004": decision("reject")}
    path = tmp_path / "review.tsv"
    assert c.write_review(manifest, decisions, path) == 2
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        rows = list(reader)
        assert reader.fieldnames == c.REVIEW_COLUMNS
    assert [row["video_id"] for row in rows] == ["00000000002", "00000000001"]
    assert rows[1]["duration_minutes"] == "15.00" and rows[1]["query"] == "search: 차 안"
    assert rows[1]["url"] == "https://www.youtube.com/watch?v=00000000001"
    assert len(rows[1]["description"]) == 300


def test_refilter_uses_cache_without_changing_legacy_metadata(manifest, tmp_path):
    row = add(manifest, channel="original", note="run_hours_cap")
    manifest.scratch.mkdir(exist_ok=True)
    (manifest.scratch / "metadata-00000000001.json").write_text(json.dumps(metadata(channel="MBC")))
    result = c.migrate_and_refilter(manifest, tmp_path / "audio", args(), [])
    assert result["removed"] == 1 and result["rejected_by_rule"] == {"broadcaster_channel": 1}
    assert row["channel"] == "original" and row["status"] == "rejected"
    assert row["channel_subscriber_count"] == "1234"


@pytest.mark.parametrize("payload", ["video_id\tdecision\treason\tdecided_by\n00000000001\tmaybe\tx\tclaude\n",
                                    "video_id\tdecision\treason\tdecided_by\n00000000001\tapprove\tx\n",
                                    "wrong\theader\n"])
def test_approval_validation_fails_closed(tmp_path, payload):
    path = tmp_path / "approved.tsv"
    path.write_text(payload, encoding="utf-8")
    with pytest.raises(ValueError):
        c.read_approvals(path)


def test_metadata_block_stops_without_audio_and_keeps_candidates(manifest, tmp_path):
    backend = Backend(tmp_path / "audio")
    backend.metadata = lambda video_id: (_ for _ in ()).throw(c.BlockedError("Sign in to confirm you're not a bot"))
    with pytest.raises(c.BlockedError):
        c.discover_candidates(manifest, backend, [c.Source("search", "가족")], args())
    assert backend.downloads == []
    assert "not a bot" in manifest.rows["00000000001"]["note"]


def test_block_warning_even_on_success_exit(tmp_path):
    backend = c.Youtube(tmp_path / "collect", tmp_path / "audio", Path("D:/deno.exe"))
    backend.base = [sys.executable, "-c", "import sys; print('Sign in to confirm you are not a bot', file=sys.stderr)"]
    with pytest.raises(c.BlockedError):
        list(backend.lines([]))


def test_cli_discovery_default_and_offline_commands(tmp_path, monkeypatch):
    monkeypatch.setattr(c, "ROOT", tmp_path)
    (tmp_path / "data").mkdir()
    (tmp_path / "data/queries.txt").write_text("search: 가족", encoding="utf-8")
    deno = tmp_path / "deno.exe"
    deno.touch()
    backend = Backend(tmp_path / ".local/audio")
    monkeypatch.setattr(c, "Youtube", lambda *a: backend)
    assert c.main(["discover", "--deno", str(deno), "--max-candidates", "2"]) == 0
    assert backend.downloads == []
    assert c.main(["--deno", str(deno), "search: family"]) == 0
    assert c.main(["review-sheet"]) == 0
    assert c.main(["--check"]) == 0
    assert c.main(["download", "--deno", str(deno)]) == 0
    assert backend.downloads == []
