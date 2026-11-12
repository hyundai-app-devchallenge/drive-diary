"""Collector tests use synthetic metadata and local files only."""

import csv
import json
import sys
from collections import Counter
from pathlib import Path
from types import SimpleNamespace

import pytest

from pipeline import collect as c


def info(number=1, **overrides):
    result = dict(id=f"{number:011d}", channel_id="UCfamily", channel="가족 채널",
                  title="차 안 가족 대화", duration=900, language=None,
                  upload_date="20260101", license="Standard YouTube License",
                  live_status="not_live", categories=["People & Blogs"])
    result.update(overrides)
    return result


@pytest.fixture
def manifest(tmp_path):
    return c.Manifest(tmp_path / "sources.tsv", tmp_path / "scratch")


def add(manifest, number=1, **overrides):
    metadata = info(number)
    return manifest.update(metadata["id"], **(c.metadata_fields(metadata) | overrides))


def test_hash_stability_and_proportions():
    # Fixed cross-machine vectors, including non-ASCII input.
    assert [c.channel_split(x) for x in ("UCfamily", "UC000000", "UC000001", "채널")] == [
        "train", "test", "test", "dev"]
    counts = Counter(c.channel_split(f"channel-{n}") for n in range(10000))
    assert 6800 <= counts["train"] <= 7200
    assert 1300 <= counts["dev"] <= 1700
    assert 1300 <= counts["test"] <= 1700
    assert c.channel_split("") == ""


def test_split_independent_of_manifest_membership_and_order(manifest):
    channels = [f"channel-{n}" for n in range(100)]
    expected = {channel: c.channel_split(channel) for channel in channels}
    for number, channel in enumerate(reversed(channels), 1):
        add(manifest, number, channel_id=channel)
        assert all(row["split"] == expected[row["channel_id"]] for row in manifest.rows.values())
    assert {channel: c.channel_split(channel) for channel in channels} == expected


def test_override_pin_survives_hash_change_and_new_rows(manifest, monkeypatch, tmp_path):
    path = manifest.path.with_name("split-overrides.tsv")
    path.write_text("channel_id\tsplit\treason\nUCfamily\tdev\thuman gold\n", encoding="utf-8")
    pinned = c.Manifest(manifest.path, manifest.scratch)
    add(pinned)
    monkeypatch.setattr(c, "channel_split", lambda channel: "test")
    add(pinned, 2)
    assert {row["split"] for row in pinned.rows.values()} == {"dev"}
    assert c.check_manifest(pinned, tmp_path / "audio") == []
    pinned.rows["00000000001"]["split"] = "test"
    assert "incorrect channel split: 00000000001" in c.check_manifest(pinned, tmp_path / "audio")


@pytest.mark.parametrize("payload", [
    "wrong\theader\n",
    "channel_id\tsplit\treason\nUCfamily\tdev\n",
    "channel_id\tsplit\treason\nUCfamily\tother\tgold\n",
    "channel_id\tsplit\treason\n\tdev\tgold\n",
    "channel_id\tsplit\treason\nUCfamily\tdev\t\n",
    "channel_id\tsplit\treason\nUCfamily\tdev\tgold\nUCfamily\ttest\tgold\n",
    "channel_id\tsplit\treason\nUCfamily\tdev\tgold\nUCfamily\tdev\tgold\n",
])
def test_override_file_validation(tmp_path, payload):
    path = tmp_path / "split-overrides.tsv"
    path.write_text(payload, encoding="utf-8")
    with pytest.raises(ValueError):
        c.read_split_overrides(path)


def test_lowdensity_override_conflict_check_and_migration(manifest, tmp_path):
    add(manifest, lowdensity=1)
    path = manifest.path.with_name("split-overrides.tsv")
    path.write_text("channel_id\tsplit\treason\nUCfamily\ttrain\tgold\n", encoding="utf-8")
    loaded = c.Manifest(manifest.path, manifest.scratch)
    before = manifest.path.read_bytes()
    assert c.check_manifest(loaded, tmp_path / "audio") == [
        "lowdensity channel override outside test: UCfamily"]
    with pytest.raises(ValueError, match="lowdensity"):
        loaded.migrate_splits()
    assert manifest.path.read_bytes() == before
    path.write_text("channel_id\tsplit\treason\nUCfamily\ttest\tgold\n", encoding="utf-8")
    loaded = c.Manifest(manifest.path, manifest.scratch)
    assert c.check_manifest(loaded, tmp_path / "audio") == []


def test_migrate_splits_cli_only_changes_splits_and_is_idempotent(manifest, tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(c, "ROOT", tmp_path)
    manifest.path = tmp_path / "data/sources.tsv"
    for n, status in enumerate(("downloaded", "candidate", "rejected", "failed"), 1):
        add(manifest, n, status=status, channel_id=f"channel-{n}")
    add(manifest, 5, channel_id="channel-1", lowdensity=1)
    for row in manifest.rows.values():
        row["split"] = "old"
    manifest.save()
    copy_rows = [dict(row) for row in manifest.rows.values()]
    (tmp_path / "data/split-overrides.tsv").write_text(
        "channel_id\tsplit\treason\nchannel-2\tdev\tgold\n", encoding="utf-8")
    monkeypatch.setattr(c, "apply_decisions", lambda *args: pytest.fail("migration applied decisions"))
    monkeypatch.setattr(c, "Youtube", lambda *args: pytest.fail("migration instantiated network backend"))
    assert c.main(["migrate-splits"]) == 0
    assert json.loads(capsys.readouterr().out)["changed"] == 5
    loaded = c.Manifest(manifest.path, manifest.scratch)
    assert [row | {"split": "old"} for row in loaded.rows.values()] == copy_rows
    assert loaded.rows["00000000001"]["split"] == "test"
    assert loaded.rows["00000000002"]["split"] == "dev"
    before, mtime = manifest.path.read_bytes(), manifest.path.stat().st_mtime_ns
    assert c.main(["migrate-splits"]) == 0
    assert json.loads(capsys.readouterr().out)["changed"] == 0
    assert manifest.path.read_bytes() == before and manifest.path.stat().st_mtime_ns == mtime


@pytest.mark.parametrize("overrides,reason", [
    ({"duration": 479}, "duration_too_short"),
    ({"duration": 10801}, "duration_too_long"),
    ({"duration": None}, "duration_unknown"),
    ({"duration": float("nan")}, "duration_unknown"),
    ({"is_live": True}, "live_or_upcoming"),
    ({"live_status": "is_upcoming"}, "live_or_upcoming"),
    ({"live_status": "post_live"}, "live_or_upcoming"),
    ({"title": "차 안 음악 플레이리스트"}, "music_or_performance"),
    ({"categories": ["Music"]}, "music_or_performance"),
    ({"title": "A family trip"}, "not_korean"),
    ({"channel_id": None}, "channel_id_missing"),
])
def test_filter_rejections(overrides, reason):
    assert reason in c.rejection_reasons(info(**overrides))


def test_filter_boundaries_and_configuration():
    assert c.rejection_reasons(info(duration=480)) == []
    assert c.rejection_reasons(info(duration=10800)) == []
    assert c.rejection_reasons(info(title="Family vlog", language="ko-KR")) == []
    assert c.rejection_reasons(info(title="Family", formats=[{"language": "ko", "acodec": "opus"}])) == []
    assert c.rejection_reasons(info(duration=60), minimum=30) == []
    assert c.rejection_reasons(info(title="Music"), korean_only=False, skip_music=False) == []
    assert c.rejection_reasons(info(live_status="was_live"), reject_live_archives=True) == ["live_archive"]
    assert c.rejection_reasons(info(duration=10, title="Concert")) == [
        "duration_too_short", "music_or_performance", "not_korean"]


def test_tsv_sanitizing_round_trip_and_idempotence(manifest):
    add(manifest, title='차\t안\n대화\r\n"가족"', channel="가족\t채널\n", note="a\x00b")
    original = manifest.path.read_bytes()
    assert len(original.decode("utf-8").splitlines()) == 2
    restored = c.Manifest(manifest.path, manifest.scratch)
    assert restored.rows == manifest.rows
    row = restored.rows["00000000001"]
    assert row["title"] == '차 안 대화 "가족"'
    assert row["channel"] == "가족 채널"
    assert row["note"] == "a b"
    restored.update("00000000001", **row_without_id(row))
    assert manifest.path.read_bytes() == original
    with manifest.path.open(encoding="utf-8", newline="") as stream:
        assert len(list(csv.DictReader(stream, delimiter="\t"))) == 1


def row_without_id(row):
    return {key: value for key, value in row.items() if key != "video_id"}


def test_lowdensity_promotes_existing_and_future_channel_rows(manifest):
    assert add(manifest)["split"] == "train"
    add(manifest, 2, lowdensity=1)
    assert manifest.rows["00000000001"]["split"] == "test"
    assert add(manifest, 3)["split"] == "test"
    assert manifest.rows["00000000001"]["lowdensity"] == "0"
    assert c.Manifest(manifest.path, manifest.scratch).rows == manifest.rows


def test_duplicate_or_malformed_rows_fail(manifest):
    add(manifest)
    payload = manifest.path.read_text(encoding="utf-8")
    manifest.path.write_text(payload + payload.splitlines()[1] + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate"):
        c.Manifest(manifest.path, manifest.scratch)


def test_interrupted_atomic_update_keeps_previous_manifest(manifest, monkeypatch):
    add(manifest)
    before = manifest.path.read_bytes()

    def interrupted(*args):
        raise OSError("simulated interruption before replace")

    monkeypatch.setattr(c.os, "replace", interrupted)
    with pytest.raises(OSError):
        add(manifest, 2)
    assert manifest.path.read_bytes() == before
    assert len(c.Manifest(manifest.path, manifest.scratch).rows) == 1


def test_check_both_directions_and_recovery(manifest, tmp_path):
    audio = tmp_path / "audio"
    audio.mkdir()
    add(manifest, status="downloaded")
    assert c.check_manifest(manifest, audio) == ["downloaded row without audio: 00000000001"]
    (audio / "00000000001.webm").write_bytes(b"audio")
    assert c.check_manifest(manifest, audio) == []
    (audio / "00000000002.webm").write_bytes(b"audio")
    assert c.check_manifest(manifest, audio) == ["audio without manifest row: 00000000002.webm"]
    add(manifest, 2)
    assert c.check_manifest(manifest, audio) == ["audio with status candidate: 00000000002.webm"]
    manifest.update("00000000002", status="downloaded")
    assert c.check_manifest(manifest, audio) == []
    (audio / "00000000002.m4a").write_bytes(b"audio")
    assert "multiple audio files: 00000000002" in c.check_manifest(manifest, audio)
    (audio / "00000000001.webm").write_bytes(b"")
    assert "invalid/empty audio: 00000000001.webm" in c.check_manifest(manifest, audio)


def test_check_cli_exit_code_and_no_mutation(manifest, tmp_path, monkeypatch):
    monkeypatch.setattr(c, "ROOT", tmp_path)
    manifest.path = tmp_path / "data/sources.tsv"
    add(manifest, status="downloaded")
    before = manifest.path.read_bytes()
    assert c.main(["--check"]) == 1
    assert manifest.path.read_bytes() == before
    audio = tmp_path / ".local/audio"
    audio.mkdir(parents=True)
    (audio / "00000000001.m4a").write_bytes(b"audio")
    assert c.main(["--check"]) == 0


def test_source_lines():
    assert c.parse_source(" # comment") is None
    assert c.parse_source("lowdensity search: 무편집 드라이브") == c.Source("search", "무편집 드라이브", True)
    assert c.parse_source("channel: https://youtube.com/@family/videos").kind == "channel"
    assert c.parse_source("https://youtu.be/abcdefghijk").kind == "video"
    assert c.parse_source("차 안 대화").kind == "search"
    with pytest.raises(ValueError):
        c.parse_source("typo: query")
    for url in ("https://youtu.be/abcdefghijk?t=2", "https://youtube.com/watch?v=abcdefghijk",
                "https://youtube.com/shorts/abcdefghijk", "abcdefghijk"):
        assert c.video_id_from_url(url) == "abcdefghijk"


class FakeYoutube:
    def __init__(self, directory, failure=None):
        self.audio_dir = directory
        self.downloads = []
        self.failure = failure

    def discover(self, source, count):
        for number in range(1, count + 1):
            yield info(number)

    def metadata(self, video_id):
        return info(int(video_id))

    def download(self, video_id):
        self.downloads.append(video_id)
        if self.failure:
            raise self.failure
        self.audio_dir.mkdir(exist_ok=True)
        (self.audio_dir / f"{video_id}.webm").write_bytes(b"audio")


def options(**overrides):
    values = dict(search_results=12, max_videos=10, max_hours=5, min_duration=480,
                  max_duration=10800, allow_non_korean=False, allow_music=False,
                  reject_live_archives=True, delay=0, max_candidates=150, per_channel=5)
    values.update(overrides)
    return SimpleNamespace(**values)


def run_approved(manifest, backend, sources, args):
    # The legacy resumability/cap tests now explicitly exercise both stages.
    c.discover_candidates(manifest, backend, sources, args)
    decisions = {f"{n:011d}": {"decision": "approve"} for n in range(1, 13)}
    return c.collect(manifest, backend, args, decisions)


def test_ten_video_cap_second_run_byte_identical(manifest, tmp_path):
    backend = FakeYoutube(tmp_path / "audio")
    source = [c.Source("search", "차 안")]
    first = run_approved(manifest, backend, source, options())
    assert first["downloaded"] == 10
    assert len(manifest.rows) == 12  # Even candidates beyond the download cap are recorded.
    before = manifest.path.read_bytes()
    second = run_approved(manifest, backend, source, options())
    assert second["downloaded"] == 0 and second["existing"] == 10
    assert len(backend.downloads) == 10
    assert manifest.path.read_bytes() == before
    assert c.check_manifest(manifest, backend.audio_dir) == []
    for row in list(manifest.rows.values())[:10]:
        assert row["downloaded_at"].endswith("+00:00")


def test_existing_later_video_reserves_budget_before_new_earlier_video(manifest, tmp_path):
    backend = FakeYoutube(tmp_path / "audio")
    backend.audio_dir.mkdir()
    add(manifest, 2, status="downloaded")
    (backend.audio_dir / "00000000002.webm").write_bytes(b"audio")
    source = [c.Source("search", "차 안")]
    summary = run_approved(manifest, backend, source, options(max_hours=0.3))
    assert summary["downloaded"] == 0 and summary["existing"] == 1
    assert backend.downloads == []
    assert manifest.rows["00000000001"]["status"] == "candidate"


def test_failed_rows_resume_and_hours_cap(manifest, tmp_path):
    backend = FakeYoutube(tmp_path / "audio", c.CollectionError("network interruption"))
    source = [c.Source("search", "차 안")]
    assert run_approved(manifest, backend, source, options(max_hours=0.5))["failed"] == 2
    assert manifest.rows["00000000001"]["status"] == "failed"
    backend.failure = None
    assert run_approved(manifest, backend, source, options(max_hours=0.5))["downloaded"] == 2
    assert run_approved(manifest, backend, source, options(max_hours=0.5))["downloaded"] == 0
    assert len(backend.downloads) == 4


def test_block_stops_immediately_and_persists_failure(manifest, tmp_path):
    backend = FakeYoutube(tmp_path / "audio", c.BlockedError("Sign in to confirm you're not a bot"))
    with pytest.raises(c.BlockedError):
        run_approved(manifest, backend, [c.Source("search", "가족")], options())
    assert len(backend.downloads) == 1
    assert manifest.rows["00000000001"]["status"] == "failed"
    assert "not a bot" in manifest.rows["00000000001"]["note"]


def test_interrupted_after_file_written_resumes_without_download(manifest, tmp_path):
    backend = FakeYoutube(tmp_path / "audio")
    backend.audio_dir.mkdir()
    (backend.audio_dir / "00000000001.webm").write_bytes(b"complete audio")
    add(manifest, status="candidate")
    summary = run_approved(manifest, backend, [c.Source("search", "가족")], options(max_videos=1))
    assert summary["existing"] == 1 and backend.downloads == []
    assert manifest.rows["00000000001"]["status"] == "downloaded"


def test_ytdlp_arguments_and_cache_locations(tmp_path):
    backend = c.Youtube(tmp_path / "collect", tmp_path / "audio", Path("D:/deno.exe"))
    assert backend.base[backend.base.index("--js-runtimes") + 1] == "deno:D:\\deno.exe"
    assert backend.environment["TMP"] == str(tmp_path / "collect/tmp")
    assert backend.environment["DENO_DIR"] == str(tmp_path / "collect/deno")
    assert "--ignore-config" in backend.base
    assert backend.base[backend.base.index("--proxy") + 1] == ""


def test_discovery_cache_resumes_truncated_line(tmp_path, monkeypatch):
    backend = c.Youtube(tmp_path / "collect", tmp_path / "audio", Path("D:/deno.exe"))
    source = c.Source("search", "가족")
    monkeypatch.setattr(backend, "lines", lambda arguments: iter([json.dumps(info(1))]))
    assert len(list(backend.discover(source, 2))) == 1
    done = next(backend.scratch.glob("discovery-*.done"))
    done.unlink()
    cache = next(backend.scratch.glob("discovery-*.jsonl"))
    with cache.open("a", encoding="utf-8") as stream:
        stream.write('{"id":')
    monkeypatch.setattr(backend, "lines", lambda arguments: iter([json.dumps(info(1)), json.dumps(info(2))]))
    assert [item["id"] for item in backend.discover(source, 2)] == ["00000000001", "00000000002"]

    def no_network(arguments):
        pytest.fail("completed discovery must reuse cache")

    monkeypatch.setattr(backend, "lines", no_network)
    assert len(list(backend.discover(source, 2))) == 2


def test_real_subprocess_block_detection_without_network(tmp_path):
    backend = c.Youtube(tmp_path / "collect", tmp_path / "audio", Path("D:/deno.exe"))
    backend.base = [sys.executable, "-c", "import sys; print('HTTP Error 429', file=sys.stderr); sys.exit(1)"]
    with pytest.raises(c.BlockedError, match="HTTP Error 429"):
        list(backend.lines([]))


def test_metadata_cache_and_language_live_fields(tmp_path, monkeypatch):
    backend = c.Youtube(tmp_path / "collect", tmp_path / "audio", Path("D:/deno.exe"))
    metadata = info(language="ko", live_status="was_live")
    monkeypatch.setattr(backend, "lines", lambda arguments: iter([json.dumps(metadata)]))
    assert backend.metadata(metadata["id"]) == metadata

    def no_network(arguments):
        pytest.fail("cached metadata must avoid the network")

    monkeypatch.setattr(backend, "lines", no_network)
    assert backend.metadata(metadata["id"])["language"] == "ko"
    assert backend.metadata(metadata["id"])["live_status"] == "was_live"
    cache = backend.scratch / f"metadata-{metadata['id']}.json"
    cache.write_text("broken", encoding="utf-8")
    with pytest.raises(c.CollectionError, match="invalid metadata JSON"):
        backend.metadata(metadata["id"])


@pytest.mark.parametrize("message", ["HTTP Error 429", "Sign in to confirm you're not a bot",
                                     "This video is age-restricted", "HTTP Error 403", "rate limit"])
def test_block_detection(message):
    assert c.BLOCKED.search(message)
