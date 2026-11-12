"""Curated, two-stage YouTube collection; see collect.md for the source contract."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unicodedata
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from itertools import zip_longest
from urllib.parse import parse_qs, quote, urlparse

ROOT = Path(__file__).resolve().parents[1]
LEGACY_COLUMNS = (
    "video_id url channel_id channel title duration_s upload_date license "
    "downloaded_at split lowdensity status note"
).split()
COLUMNS = LEGACY_COLUMNS + ["description", "channel_subscriber_count", "view_count", "query"]
APPROVAL_COLUMNS = ["video_id", "decision", "reason", "decided_by", "comment"]
REVIEW_COLUMNS = ["video_id", "channel", "title", "duration_minutes", "description", "query", "url"]
SPLIT_OVERRIDE_COLUMNS = ["channel_id", "split", "reason"]
SPLITS = ("train", "dev", "test")
MAX_PER_CHANNEL = 20
SHORT_MIN_DURATION = 60
AUDIO_EXTENSIONS = {"webm", "m4a", "opus", "ogg", "mp3", "aac", "wav", "flac", "mp4"}
# Designer-editable table: (metadata field, regex). Each key is a reason code.
AUTO_REJECT_PATTERNS = {
    "broadcaster_channel": ("channel", r"MBC|KBS|SBS|JTBC|tvN|YTN|연합뉴스|채널\s*A|TV\s*조선|"
                            r"(?<![a-z])ENA(?![a-z])|Mnet|뉴스|EBS|디글|Diggle|픽잇|Pick\s*it|오분순삭"),
    "compilation": ("title", r"모음|\.zip\b|레전드|풀버전|다시보기|하이라이트|오분순삭|#shorts\b"),
    "music_or_performance": ("title", r"\b(music|playlist|concert|performance|cover|karaoke|bgm|ost|m/?v)\b"
                             r"|플레이리스트|노래모음|노래 모음|음악|뮤직|콘서트|공연|연주|직캠|노래방"),
}
BLOCKED = re.compile(
    r"sign.?in|log.?in|not a bot|bot check|confirm your age|age.restricted|"
    r"too many requests|rate.?limit|HTTP Error (?:403|429)|account required|"
    r"cookies.*(?:required|needed)|members.only|private video",
    re.IGNORECASE,
)


def clean(value: object) -> str:
    return re.sub(r"[\s\x00-\x1f\x7f]+", " ", str(value if value is not None else "")).strip()


def channel_split(channel_id: str) -> str:
    """SHA-256 UTF-8, first 64 bits big endian, modulo 100: 0..69/70..84/85..99."""
    if not channel_id:
        return ""  # Unknown channels never enter training.
    bucket = int.from_bytes(hashlib.sha256(channel_id.encode("utf-8")).digest()[:8], "big") % 100
    return "train" if bucket < 70 else "dev" if bucket < 85 else "test"


def read_split_overrides(path: Path) -> dict[str, str]:
    """Read channel pins; absent files mean no pins for legacy/isolated inputs."""
    if not path.exists():
        return {}
    overrides = {}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames != SPLIT_OVERRIDE_COLUMNS:
            raise ValueError("Unexpected split override header")
        for row in reader:
            if None in row or any(value is None for value in row.values()):
                raise ValueError("Malformed split override row")
            channel = row["channel_id"]
            if not channel.strip() or channel != channel.strip() or channel in overrides:
                raise ValueError(f"Empty or duplicate split override channel: {channel}")
            if row["split"] not in SPLITS or not row["reason"].strip():
                raise ValueError(f"Invalid split override split/reason: {channel}")
            overrides[channel] = row["split"]
    return overrides


def lowdensity_channels(rows) -> set[str]:
    return {row["channel_id"] for row in rows if row["lowdensity"] == "1" and row["channel_id"]}


def split_override_errors(rows, overrides: dict[str, str]) -> list[str]:
    return [f"lowdensity channel override outside test: {channel}"
            for channel in sorted(lowdensity_channels(rows))
            if channel in overrides and overrides[channel] != "test"]


def effective_split(row: dict, test_channels: set[str], overrides: dict[str, str]) -> str:
    if row["lowdensity"] == "1" or row["channel_id"] in test_channels:
        return "test"
    return overrides.get(row["channel_id"], channel_split(row["channel_id"]))


class Manifest:
    def __init__(self, path: Path, scratch: Path, overrides: Path | None = None):
        self.path, self.scratch = path, scratch
        self.overrides = read_split_overrides(overrides if overrides is not None
                                              else path.with_name("split-overrides.tsv"))
        self.rows: dict[str, dict[str, str]] = {}
        if path.exists():
            with path.open(encoding="utf-8", newline="") as stream:
                reader = csv.DictReader(stream, delimiter="\t")
                if reader.fieldnames not in (LEGACY_COLUMNS, COLUMNS):
                    raise ValueError("Unexpected manifest header")
                for row in reader:
                    if not row["video_id"] or row["video_id"] in self.rows:
                        raise ValueError("Empty or duplicate manifest video_id")
                    if None in row or any(value is None for value in row.values()):
                        raise ValueError("Malformed manifest row")
                    self.rows[row["video_id"]] = dict.fromkeys(COLUMNS, "") | row

    def save(self) -> None:
        # Atomic replace preserves the previous complete TSV even on interruption.
        stream = io.StringIO(newline="")
        writer = csv.DictWriter(stream, fieldnames=COLUMNS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(self.rows.values())
        payload = stream.getvalue().encode("utf-8")
        if self.path.exists() and self.path.read_bytes() == payload:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.scratch.mkdir(parents=True, exist_ok=True)
        temporary = self.scratch / "sources.tmp.tsv"
        with temporary.open("wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, self.path)

    def update(self, video_id: str, **values: object) -> dict[str, str]:
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", video_id):
            raise ValueError(f"Invalid YouTube video id: {video_id!r}")
        row = self.rows.setdefault(video_id, dict.fromkeys(COLUMNS, ""))
        row.update(video_id=video_id, **{key: clean(value) for key, value in values.items()})
        row["lowdensity"] = row["lowdensity"] or "0"
        row["status"] = row["status"] or "candidate"
        self.recompute_splits()
        self.save()
        return row

    def recompute_splits(self) -> int:
        errors = split_override_errors(self.rows.values(), self.overrides)
        if errors:
            raise ValueError("; ".join(errors))
        test_channels = lowdensity_channels(self.rows.values())
        changed = 0
        for item in self.rows.values():
            split = effective_split(item, test_channels, self.overrides)
            changed += item["split"] != split
            item["split"] = split
        return changed

    def migrate_splits(self) -> dict:
        """Only migrate split cells; do not apply approvals or move audio."""
        changed = self.recompute_splits()
        self.save()
        return dict(changed=changed, rows=len(self.rows), failed=0)


@dataclass(frozen=True)
class Source:
    kind: str
    value: str
    lowdensity: bool = False


def parse_source(line: str) -> Source | None:
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    lowdensity = line.startswith("lowdensity ")
    if lowdensity:
        line = line[len("lowdensity "):].strip()
    match = re.fullmatch(r"(search|channel|video):\s*(.+)", line)
    if match:
        return Source(match[1], match[2].strip(), lowdensity)
    if line.startswith(("https://", "http://")):
        return Source("video" if video_id_from_url(line) else "channel", line, lowdensity)
    if ":" in line and not line.startswith(("http:", "https:")):
        raise ValueError(f"Unknown source prefix: {line}")
    return Source("search", line, lowdensity)


def video_id_from_url(value: str) -> str | None:
    parsed = urlparse(value)
    if parsed.hostname in {"youtu.be", "www.youtu.be"}:
        candidate = parsed.path.strip("/")
    elif parsed.hostname in {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"}:
        candidate = parse_qs(parsed.query).get("v", [""])[0]
        if not candidate and parsed.path.startswith(("/shorts/", "/live/", "/embed/")):
            candidate = parsed.path.split("/")[2]
    else:
        candidate = value  # Also accept an explicit video id for --lowdensity-video.
    return candidate if re.fullmatch(r"[A-Za-z0-9_-]{11}", candidate) else None


def metadata_fields(info: dict) -> dict:
    return {"url": f"https://www.youtube.com/watch?v={info['id']}",
            "channel_id": info.get("channel_id"), "channel": info.get("channel"),
            "title": info.get("title"), "duration_s": info.get("duration"),
            "upload_date": info.get("upload_date"), "license": info.get("license"),
            "description": clean(info.get("description"))[:300],
            "channel_subscriber_count": info.get("channel_follower_count"),
            "view_count": info.get("view_count")}


def rejection_reasons(info: dict, minimum: float = 480, maximum: float = 10800,
                      korean_only: bool = True, skip_music: bool = True,
                      reject_live_archives: bool = True) -> list[str]:
    reasons = []
    duration = info.get("duration")
    if not isinstance(duration, (int, float)) or not math.isfinite(duration) or duration <= 0:
        reasons.append("duration_unknown")
    elif duration < minimum:
        reasons.append("duration_too_short")
    elif duration > min(maximum, 10800):
        reasons.append("duration_too_long")
    if info.get("is_live") or info.get("live_status") in {"is_live", "is_upcoming", "post_live"}:
        reasons.append("live_or_upcoming")
    elif info.get("was_live") or info.get("live_status") == "was_live":
        reasons.append("live_archive")
    if info.get("is_premiere") or info.get("premiere_timestamp") or (
            info.get("release_timestamp") and not info.get("was_live")
            and info.get("live_status") not in {"was_live", "is_live", "post_live"}):
        reasons.append("premiere")
    for reason, (field, pattern) in AUTO_REJECT_PATTERNS.items():
        if reason == "music_or_performance" and not skip_music:
            continue
        if re.search(pattern, info.get(field) or "", re.IGNORECASE):
            reasons.append(reason)
    categories = {str(category).casefold() for category in info.get("categories") or []}
    if skip_music and categories & {"music", "음악"} and "music_or_performance" not in reasons:
        reasons.append("music_or_performance")
    languages = [info.get("language") or ""]
    languages.extend(f.get("language") or "" for f in info.get("formats") or [] if f.get("acodec") != "none")
    if korean_only and not (any(lang.lower().startswith("ko") for lang in languages)
                            or re.search(r"[가-힣]", info.get("title") or "")):
        reasons.append("not_korean")
    if not info.get("channel_id"):
        reasons.append("channel_id_missing")
    return reasons


def audio_files(directory: Path, video_id: str) -> list[Path]:
    return sorted(p for p in directory.glob(f"{video_id}.*")
                  if p.is_file() and p.suffix.lstrip(".").lower() in AUDIO_EXTENSIONS and p.stat().st_size > 0)


def note_with(note: str, *items: str, remove: tuple[str, ...] = ()) -> str:
    parts = [part for part in note.split(";") if part and not part.startswith(remove)]
    return ";".join(dict.fromkeys(parts + [item for item in items if item]))


def read_approvals(path: Path) -> dict[str, dict[str, str]]:
    if not path.exists():
        return {}  # Fail closed: a missing list authorizes no downloads.
    decisions = {}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        if reader.fieldnames not in (APPROVAL_COLUMNS[:4], APPROVAL_COLUMNS):
            raise ValueError("Unexpected approval header")
        for row in reader:
            if None in row or any(value is None for value in row.values()):
                raise ValueError("Malformed approval row")
            row = {key: clean(value) for key, value in row.items()}
            row["comment"] = clean(row.get("comment", ""))[:500]
            video_id = row["video_id"]
            if not re.fullmatch(r"[A-Za-z0-9_-]{11}", video_id) or video_id in decisions:
                raise ValueError(f"Invalid or duplicate approval video_id: {video_id}")
            if row["decision"] not in {"approve", "reject", "hold"}:
                raise ValueError(f"Invalid decision for {video_id}: {row['decision']}")
            decisions[video_id] = row
    return decisions


def human_decision(row) -> str | None:
    """Claude is pre-screening only; other named reviewers are human."""
    if not row or not clean(row.get("decided_by")) or clean(row.get("decided_by")) == "claude":
        return None
    decision = row.get("decision")
    return decision if decision in ("approve", "reject", "hold") else None


def is_human_approved(video_id, approvals) -> bool:
    return human_decision(approvals.get(video_id)) == "approve"


def write_approvals(path: Path, approvals: dict) -> bool:
    """Atomically write five columns; return False without touching identical files."""
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=APPROVAL_COLUMNS, delimiter="\t", lineterminator="\n")
    writer.writeheader()
    for video_id, row in approvals.items():
        if (not re.fullmatch(r"[A-Za-z0-9_-]{11}", video_id)
                or row.get("video_id", video_id) != video_id
                or row.get("decision") not in ("approve", "reject", "hold")):
            raise ValueError("Invalid approval row")
        values = {key: clean(row.get(key, "")) for key in APPROVAL_COLUMNS}
        values.update(video_id=video_id, comment=values["comment"][:500])
        writer.writerow(values)
    payload = stream.getvalue().encode("utf-8")
    if path.exists() and path.read_bytes() == payload:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix="approved-", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return True


def migrate_approvals(path: Path) -> bool:
    """Explicit, idempotent four-to-five-column migration; reads never migrate."""
    return write_approvals(path, read_approvals(path)) if path.exists() else False


def move_audio(source: Path, destination: Path, video_id: str) -> int:
    files = audio_files(source, video_id)
    if files:
        destination.mkdir(parents=True, exist_ok=True)
    for path in files:
        target = destination / path.name
        if target.exists():
            raise CollectionError(f"refusing to overwrite preserved audio: {target}")
        path.rename(target)
    return len(files)


def reject_video(manifest: Manifest, audio_dir: Path, video_id: str, note: str) -> int:
    # Record first; rerunning applies unfinished moves after an interruption.
    manifest.update(video_id, status="rejected", note=note)
    return move_audio(audio_dir, audio_dir / "_rejected", video_id)


def apply_decisions(manifest: Manifest, audio_dir: Path, decisions: dict) -> dict:
    rejected = moved = 0
    for video_id, row in list(manifest.rows.items()):
        decision = decisions.get(video_id, {}).get("decision")
        if decision == "reject":
            note = note_with(row["note"], "manual:" + decisions[video_id]["reason"], remove=("manual:",))
            moved += reject_video(manifest, audio_dir, video_id, note)
            rejected += 1
        elif decision == "approve" and any(p.startswith("manual:") for p in row["note"].split(";")):
            # Reapproval reuses preserved audio, including after a partially completed move.
            move_audio(audio_dir / "_rejected", audio_dir, video_id)
            manifest.update(video_id, status="downloaded" if audio_files(audio_dir, video_id) else "candidate",
                            note=note_with(row["note"], remove=("manual:",)))
        elif decision in {None, "hold"} and row["status"] == "failed":
            manifest.update(video_id, status="candidate")
    return dict(manual_rejected=rejected, audio_moved=moved)


def check_manifest(manifest: Manifest, audio_dir: Path) -> list[str]:
    errors = split_override_errors(manifest.rows.values(), manifest.overrides)
    for directory, expected_status in ((audio_dir, "downloaded"), (audio_dir / "_rejected", "rejected")):
        for path in sorted(directory.iterdir()) if directory.exists() else []:
            if path == audio_dir / "_rejected" and path.is_dir():
                continue
            name = path.relative_to(audio_dir).as_posix()
            if not path.is_file():
                errors.append(f"unexpected directory: {name}")
                continue
            row = manifest.rows.get(path.stem)
            if row is None:
                errors.append(f"audio without manifest row: {name}")
            elif path not in audio_files(directory, path.stem):
                errors.append(f"invalid/empty audio: {name}")
            elif row["status"] != expected_status:
                errors.append(f"audio with status {row['status']}: {name}")
    test_channels = lowdensity_channels(manifest.rows.values())
    for video_id, row in manifest.rows.items():
        files = audio_files(audio_dir, video_id)
        if row["status"] == "downloaded" and not files:
            errors.append(f"downloaded row without audio: {video_id}")
        if len(files + audio_files(audio_dir / "_rejected", video_id)) > 1:
            errors.append(f"multiple audio files: {video_id}")
        expected = effective_split(row, test_channels, manifest.overrides)
        if row["split"] != expected:
            errors.append(f"incorrect channel split: {video_id}")
    return errors


class CollectionError(RuntimeError):
    pass


class BlockedError(CollectionError):
    pass


class Youtube:
    def __init__(self, scratch: Path, audio_dir: Path, deno: Path):
        self.scratch, self.audio_dir = scratch, audio_dir
        temporary = scratch / "tmp"
        temporary.mkdir(parents=True, exist_ok=True)
        self.environment = dict(os.environ, TMP=str(temporary), TEMP=str(temporary), PYTHONUTF8="1",
                                PYTHONDONTWRITEBYTECODE="1", DENO_DIR=str(scratch / "deno"))
        self.base = [sys.executable, "-m", "yt_dlp", "--ignore-config", "--no-js-runtimes",
                     "--js-runtimes", f"deno:{deno}", "--cache-dir", str(scratch / "yt-dlp"),
                     "--proxy", "", "--socket-timeout", "30", "--retries", "0", "--fragment-retries", "0"]

    def lines(self, arguments: list[str]):
        # Stream discovery output so every emitted video gets a row before the next.
        with tempfile.TemporaryFile(mode="w+", encoding="utf-8", dir=self.scratch) as errors:
            process = subprocess.Popen(self.base + arguments, stdout=subprocess.PIPE, stderr=errors,
                                       text=True, encoding="utf-8", env=self.environment)
            try:
                for line in process.stdout:
                    yield line.strip()
                code = process.wait()
            finally:
                process.stdout.close()
                if process.poll() is None:
                    process.terminate()
                    process.wait()
            errors.seek(0)
            detail = errors.read().strip()
        if detail:
            (self.scratch / "last-stderr.txt").write_text(detail, encoding="utf-8")
            print(detail, file=sys.stderr, flush=True)
        if code or BLOCKED.search(detail):
            exception = BlockedError if BLOCKED.search(detail) else CollectionError
            raise exception(detail or f"yt-dlp exited {code}")

    def discover(self, source: Source, count: int):
        if source.kind == "video":
            video_id = video_id_from_url(source.value)
            if not video_id:
                raise ValueError(f"Invalid video URL: {source.value}")
            yield {"id": video_id}
            return
        key = hashlib.sha256(f"{source.kind}:{source.value}:{count}".encode("utf-8")).hexdigest()
        cache = self.scratch / f"discovery-{key}.jsonl"
        done = self.scratch / f"discovery-{key}.done"
        seen = set()
        if cache.exists():
            for line in cache.read_text(encoding="utf-8").splitlines():
                # A crash can leave a partial final cache line; the manifest is authoritative.
                try:
                    info = json.loads(line)
                except json.JSONDecodeError:
                    continue
                seen.add(info.get("id"))
                yield info
        if done.exists():
            return
        target = f"ytsearch{count}:{source.value}" if source.kind == "search" else source.value
        with cache.open("a", encoding="utf-8") as output:
            if cache.stat().st_size and not cache.read_bytes().endswith(b"\n"):
                output.write("\n")  # Isolate a truncated cache line before appending.
            for line in self.lines(["--flat-playlist", "--skip-download", "--dump-json",
                                    "--playlist-end", str(count), "--", target]):
                info = json.loads(line)
                if info.get("id") not in seen:
                    yield info  # Caller durably records it before cache write.
                    output.write(json.dumps(info, ensure_ascii=False) + "\n")
                    output.flush()
                    seen.add(info.get("id"))
        done.touch()

    def metadata(self, video_id: str) -> dict:
        cache = self.scratch / f"metadata-{video_id}.json"
        try:
            if cache.exists():
                info = json.loads(cache.read_text(encoding="utf-8"))
            else:
                lines = list(self.lines(["--no-playlist", "--skip-download", "--dump-single-json", "--",
                                        f"https://www.youtube.com/watch?v={video_id}"]))
                info = json.loads("\n".join(lines))
        except json.JSONDecodeError as error:
            raise CollectionError(f"invalid metadata JSON for {video_id}: {error}") from error
        if info.get("id") != video_id:
            raise CollectionError(f"metadata video ID mismatch for {video_id}")
        if cache.exists():
            return info
        temporary = cache.with_suffix(".tmp")
        temporary.write_text(json.dumps(info, ensure_ascii=False), encoding="utf-8")
        os.replace(temporary, cache)
        return info

    def download(self, video_id: str) -> None:
        self.audio_dir.mkdir(parents=True, exist_ok=True)
        for line in self.lines(["--no-playlist", "--no-overwrites", "--no-progress", "--no-simulate",
                                "-f", "bestaudio[language^=ko]/bestaudio", "-P", str(self.audio_dir),
                                "-P", f"temp:{self.scratch / 'tmp'}", "-o", "%(id)s.%(ext)s", "--",
                                f"https://www.youtube.com/watch?v={video_id}"]):
            print(line, flush=True)
        if not audio_files(self.audio_dir, video_id):
            raise CollectionError("yt-dlp completed without a nonempty audio file")


def filter_reasons(info: dict, args, decisions=None) -> list[str]:
    if decisions is None:
        path = getattr(args, "approvals", None)
        decisions = read_approvals(path) if path is not None else {}
    minimum = min(SHORT_MIN_DURATION, args.min_duration) if is_human_approved(info["id"], decisions or {}) else args.min_duration
    return rejection_reasons(info, minimum, args.max_duration,
                             not args.allow_non_korean, not args.allow_music)


def retry_short(manifest: Manifest, decisions: dict, video_ids: list[str],
                minimum: float = SHORT_MIN_DURATION) -> dict:
    """Offline candidate revival, with complete validation before a single save."""
    if not video_ids:
        raise ValueError("retry-short requires explicit video IDs")
    if not math.isfinite(minimum) or not 0 < minimum <= 10800:
        raise ValueError("retry-short requires 0 < min-duration <= 10800 (finite seconds)")
    video_ids = list(dict.fromkeys(video_ids))
    rejection_codes = set(AUTO_REJECT_PATTERNS) | {
        "duration_unknown", "duration_too_short", "duration_too_long",
        "live_or_upcoming", "live_archive", "premiere", "not_korean", "channel_id_missing",
    }
    errors = []
    for video_id in video_ids:
        row = manifest.rows.get(video_id)
        reasons = []
        if human_decision(decisions.get(video_id)) == "reject":
            reasons.append("human reject in approvals")
        if row is None:
            reasons.append("not in manifest")
        else:
            if row["status"] != "rejected":
                reasons.append(f"status must be rejected (got {row['status']})")
            parts = {part for part in row["note"].split(";") if part}
            rejects = {part for part in parts if part in rejection_codes or part.startswith("manual:")}
            if rejects != {"duration_too_short"}:
                reasons.append("requires duration_too_short only (got " + ", ".join(sorted(rejects)) + ")")
            try:
                duration = float(row["duration_s"])
            except ValueError:
                duration = float("nan")
            if not math.isfinite(duration) or not minimum <= duration <= 10800:
                reasons.append(f"duration must be {minimum:g}–10800 seconds (got {row['duration_s']!r})")
        if reasons:
            errors.append(dict(video_id=video_id, reasons=reasons))
    if errors:
        return dict(retried=[], errors=errors, failed=len(errors))
    for video_id in video_ids:
        row = manifest.rows[video_id]
        row.update(status="candidate", note=";".join(
            part for part in row["note"].split(";") if part != "duration_too_short"))
    manifest.save()
    return dict(retried=video_ids, failed=0)


def retry_short_approved(manifest: Manifest, decisions: dict, video_ids: list[str]) -> dict:
    """Offline, narrowly scoped revival; preserve every unrelated cell and rule."""
    if not video_ids:
        raise ValueError("retry-short-approved requires explicit video IDs")
    for video_id in video_ids:
        row = manifest.rows.get(video_id)
        if (not row or not is_human_approved(video_id, decisions)
                or row["status"] != "rejected" or row["note"] != "duration_too_short"
                or not 180 <= float(row["duration_s"]) <= 10800):
            raise ValueError(f"Not a human-approved short-only rejection: {video_id}")
    for video_id in video_ids:
        manifest.rows[video_id].update(status="candidate", note="")
    manifest.save()
    return dict(retried=video_ids, failed=0)


def discover_candidates(manifest: Manifest, youtube: Youtube, sources: list[Source], args,
                        expanded_from: dict[str, str] | None = None) -> dict:
    """Metadata only. The cap counts new IDs, including automatically rejected IDs."""
    added = failures = skipped_non_video = 0
    groups = []
    for source in sources:
        ids = []
        for info in youtube.discover(source, args.search_results):
            video_id = info.get("id")
            # Search/channel feeds may contain playlists, channels, or shelves.
            # Explicit video inputs retain their strict validation/error behavior.
            if source.kind != "video" and not (
                    isinstance(video_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{11}", video_id)):
                skipped_non_video += 1
                continue
            old = manifest.rows.get(video_id)
            if old is None and added >= args.max_candidates:
                continue
            fields = {}
            if old is None:
                fields = metadata_fields(info)
                fields.update(query=f"{source.kind}: {source.value}")
                added += 1
            if source.lowdensity:
                fields["lowdensity"] = 1
            if expanded_from:
                channel_id = expanded_from[source.value]
                fields["note"] = note_with(old["note"] if old else "", f"expanded_from={channel_id}")
            if fields:
                manifest.update(video_id, **fields)
            ids.append(video_id)
        groups.append(ids)
    ordered = list(dict.fromkeys(video_id for batch in zip_longest(*groups)
                                for video_id in batch if video_id))
    rejected = Counter()
    for video_id in ordered:
        row = manifest.rows[video_id]
        try:
            info = youtube.metadata(video_id)
            manifest.update(video_id, **metadata_fields(info))
            # Manual/previous automatic rejects are sticky; discovery never revives them.
            if row["status"] == "rejected":
                continue
            reasons = filter_reasons(info, args)
            if reasons:
                reject_video(manifest, youtube.audio_dir, video_id, note_with(row["note"], *reasons))
                rejected.update(reasons)
            elif row["status"] != "downloaded":
                manifest.update(video_id, status="candidate", note=note_with(row["note"], remove=("error:",)))
        except CollectionError as error:
            manifest.update(video_id, note=note_with(row["note"], f"error:{error}", remove=("error:",)))
            failures += 1
            print(f"metadata failed {video_id}: {error}", file=sys.stderr, flush=True)
            if isinstance(error, BlockedError):
                raise
    return dict(added=added, inspected=len(ordered), rejected_by_rule=dict(rejected), failed=failures,
                skipped_non_video=skipped_non_video)


def expand_channels(manifest: Manifest, youtube: Youtube, decisions: dict, args) -> dict:
    if not 1 <= args.per_channel <= MAX_PER_CHANNEL:
        raise ValueError(f"per-channel must be between 1 and {MAX_PER_CHANNEL}")
    rejected_channels = {row["channel_id"] for video_id, row in manifest.rows.items()
                         if decisions.get(video_id, {}).get("decision") == "reject"}
    channels = sorted({row["channel_id"] for video_id, row in manifest.rows.items()
                       if row["channel_id"] and row["status"] != "rejected"
                       and decisions.get(video_id, {}).get("decision") == "approve"
                       and row["channel_id"] not in rejected_channels})
    summary = dict(added=0, failed=0, channels={}, rejected_by_rule={}, skipped_non_video=0)
    rules = Counter()
    for channel_id in channels:
        remaining = args.max_candidates - summary["added"]
        if remaining <= 0:
            break
        base = f"https://www.youtube.com/channel/{channel_id}"
        queries = getattr(args, "channel_query", [])
        urls = ([f"{base}/search?query={quote(query, safe='')}" for query in queries]
                if queries else [f"{base}/videos"])
        channel_args = argparse.Namespace(**vars(args))
        channel_args.search_results = args.per_channel
        channel_args.max_candidates = min(remaining, args.per_channel)
        result = discover_candidates(manifest, youtube, [Source("channel", url) for url in urls], channel_args,
                                     expanded_from={url: channel_id for url in urls})
        summary["channels"][channel_id] = result["added"]
        summary["added"] += result["added"]
        summary["failed"] += result["failed"]
        summary["skipped_non_video"] += result["skipped_non_video"]
        rules.update(result["rejected_by_rule"])
    summary["rejected_by_rule"] = dict(rules)
    return summary


def migrate_and_refilter(manifest: Manifest, audio_dir: Path, args, sources: list[Source]) -> dict:
    """Backfill appended fields from local caches; audit existing candidates offline."""
    queries = {}
    for source in sources:
        # Old runs used 5 results. Also recover other cached counts used in this run.
        for count in {5, args.search_results}:
            key = hashlib.sha256(f"{source.kind}:{source.value}:{count}".encode("utf-8")).hexdigest()
            cache = manifest.scratch / f"discovery-{key}.jsonl"
            if cache.exists():
                for line in cache.read_text(encoding="utf-8").splitlines():
                    try:
                        queries.setdefault(json.loads(line)["id"], f"{source.kind}: {source.value}")
                    except (json.JSONDecodeError, KeyError):
                        continue
    counts = Counter()
    removed = 0
    for video_id, row in list(manifest.rows.items()):
        cache = manifest.scratch / f"metadata-{video_id}.json"
        info = json.loads(cache.read_text(encoding="utf-8")) if cache.exists() else dict(
            id=video_id, channel_id=row["channel_id"], channel=row["channel"], title=row["title"],
            duration=float(row["duration_s"]) if row["duration_s"] else None)
        fields = metadata_fields(info)
        manifest.update(video_id, **{key: fields[key] for key in COLUMNS[len(LEGACY_COLUMNS):-1]},
                        query=row["query"] or queries.get(video_id, ""))
        if row["status"] != "candidate":
            continue
        reasons = filter_reasons(info, args)
        counts.update(reasons)
        if reasons:
            removed += 1
            reject_video(manifest, audio_dir, video_id, note_with(row["note"], *reasons))
    manifest.save()
    return dict(removed=removed, rejected_by_rule=dict(counts), failed=0)


def write_review(manifest: Manifest, decisions: dict, path: Path) -> int:
    rows = [row for video_id, row in manifest.rows.items()
            if row["status"] == "candidate" and decisions.get(video_id, {}).get("decision")
            not in {"approve", "reject"}]
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=REVIEW_COLUMNS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        for row in sorted(rows, key=lambda r: (r["channel"].casefold(), r["title"], r["video_id"])):
            writer.writerow({key: row[key] for key in REVIEW_COLUMNS if key != "duration_minutes"}
                            | {"duration_minutes": f"{float(row['duration_s']) / 60:.2f}" if row["duration_s"] else ""})
    return len(rows)


def hangul_share(text: str) -> float | None:
    hangul = latin = 0
    for char in text:
        if unicodedata.category(char).startswith("L"):
            name = unicodedata.name(char, "")
            hangul += "HANGUL" in name
            latin += "LATIN" in name
    return hangul / (hangul + latin) if hangul + latin else None


def review_flags(manifest: Manifest) -> list[dict]:
    reviews = []
    for video_id, row in sorted(manifest.rows.items()):
        parts = row["note"].split(";")
        flags = [flag for flag in ("low_speech", "non_korean", "no_speech") if flag in parts]
        if row["status"] != "downloaded" or not flags:
            continue
        numbers = {}
        for part in parts:
            key, _, value = part.partition("=")
            if key not in {"speech_share", "hangul_share", "utterance_count", "utterances_per_minute"}:
                continue
            try:
                number = int(value) if key == "utterance_count" else float(value)
            except ValueError:
                continue
            if math.isfinite(number):
                numbers[key] = number
        reviews.append(dict(video_id=video_id, flags=flags, **numbers))
    return reviews


def mark_density(manifest: Manifest, stats_dir: Path, threshold: float = 0.15,
                 hangul_threshold: float = 0.8, min_utterances: int = 10) -> dict:
    if not math.isfinite(threshold) or not 0 <= threshold <= 1:
        raise ValueError("speech threshold must be between 0 and 1")
    if not math.isfinite(hangul_threshold) or not 0 <= hangul_threshold <= 1:
        raise ValueError("Hangul threshold must be between 0 and 1")
    if type(min_utterances) is not int or min_utterances < 0:
        raise ValueError("minimum utterances must be a nonnegative integer")
    marked = low = non_korean = no_speech = 0
    for video_id, row in list(manifest.rows.items()):
        path = stats_dir / f"yt_{video_id}.stats.json"
        if not path.exists():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        duration, removed, count = (data[key] for key in
                                    ("audio_duration", "vad_removed_seconds", "utterance_count"))
        if (any(isinstance(n, bool) or not isinstance(n, (int, float)) or not math.isfinite(n)
                for n in (duration, removed, count)) or duration <= 0 or not 0 <= removed <= duration
                or count < 0 or int(count) != count):
            raise ValueError(f"invalid speech density stats: {path.name}")
        share = (duration - removed) / duration
        transcript = stats_dir / f"yt_{video_id}.jsonl"
        language_share = None
        if transcript.exists():
            texts = []
            for line in transcript.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                utterance = json.loads(line)
                if not isinstance(utterance, dict) or not isinstance(utterance.get("text"), str):
                    raise ValueError(f"invalid utterance text: {transcript.name}")
                texts.append(utterance["text"])
            language_share = hangul_share("".join(texts))
        items = [f"speech_share={share:.6f}", f"utterance_count={int(count)}",
                 f"utterances_per_minute={count * 60 / duration:.6f}"]
        if language_share is not None:
            items.append(f"hangul_share={language_share:.6f}")
            if language_share < hangul_threshold:
                items.append("non_korean")
                non_korean += 1
        if count < min_utterances:
            items.append("no_speech")
            no_speech += 1
        if share < threshold:
            items.append("low_speech")
            low += 1
        manifest.update(video_id, note=note_with(row["note"], *items,
                        remove=("speech_share=", "hangul_share=", "utterance_count=",
                                "utterances_per_minute=", "low_speech", "non_korean", "no_speech")))
        marked += 1
    return dict(marked=marked, low_speech=low, non_korean=non_korean, no_speech=no_speech, failed=0)


def collect(manifest: Manifest, youtube: Youtube, args, decisions: dict | None = None) -> dict:
    """Stage 2: only manifest IDs explicitly approved in the decision list may download."""
    if decisions is None:
        decisions = read_approvals(args.approvals)
    apply_decisions(manifest, youtube.audio_dir, decisions)
    ordered = [video_id for video_id in manifest.rows
               if decisions.get(video_id, {}).get("decision") == "approve"]
    completed = {video_id for video_id in ordered
                 if manifest.rows[video_id]["status"] == "downloaded"
                 and audio_files(youtube.audio_dir, video_id)}
    existing = selected = len(completed)
    seconds = sum(float(manifest.rows[video_id]["duration_s"]) for video_id in completed)
    downloaded = failures = 0
    last_download = None
    for video_id in ordered:
        if selected >= args.max_videos:
            break
        row = manifest.rows[video_id]
        if video_id in completed or row["status"] == "rejected":
            continue
        try:
            info = youtube.metadata(video_id)
            manifest.update(video_id, **metadata_fields(info))
            reasons = filter_reasons(info, args, decisions)
            if reasons:
                reject_video(manifest, youtube.audio_dir, video_id, note_with(row["note"], *reasons))
                continue
            duration = float(row["duration_s"])
            if seconds + duration > args.max_hours * 3600:
                manifest.update(video_id, note=note_with(row["note"], "run_hours_cap"))
                continue
            selected += 1
            seconds += duration
            if audio_files(youtube.audio_dir, video_id):
                manifest.update(video_id, status="downloaded", note=note_with(row["note"], "recovered existing audio"),
                                downloaded_at=row["downloaded_at"] or datetime.now(timezone.utc).isoformat())
                existing += 1
                continue
            manifest.update(video_id, status="candidate", note=note_with(row["note"], remove=("error:", "run_hours_cap")))
            if last_download is not None:
                time.sleep(max(0, args.delay - (time.monotonic() - last_download)))
            last_download = time.monotonic()
            youtube.download(video_id)
            manifest.update(video_id, status="downloaded", downloaded_at=datetime.now(timezone.utc).isoformat())
            downloaded += 1
        except CollectionError as error:
            manifest.update(video_id, status="failed", note=note_with(row["note"], f"error:{error}", remove=("error:",)))
            failures += 1
            print(f"failed {video_id}: {error}", file=sys.stderr, flush=True)
            if isinstance(error, BlockedError):
                raise
    return dict(downloaded=downloaded, existing=existing, failed=failures,
                selected=selected, selected_hours=round(seconds / 3600, 4))


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("command", nargs="?", default="discover", metavar="COMMAND",
                        help="discover (default), download, apply-decisions, review-sheet, review-flags, expand, mark-density, refilter, migrate-splits, retry-short, retry-short-approved")
    result.add_argument("sources", nargs="*", help="query, URL, or 'search:/channel:/video:' line")
    result.add_argument("--queries", type=Path, help="source file; defaults to data/queries.txt when no positional sources")
    result.add_argument("--lowdensity-video", action="append", default=[], metavar="URL_OR_ID")
    result.add_argument("--max-videos", type=int, default=10)
    result.add_argument("--max-candidates", type=int, default=150, help="maximum new IDs, including auto-rejects")
    result.add_argument("--per-channel", type=int, default=5, help=f"expansion uploads per channel, hard cap {MAX_PER_CHANNEL}")
    result.add_argument("--channel-query", action="append", default=[], help="expand: search each approved channel; repeat for multiple terms")
    result.add_argument("--approvals", type=Path, default=ROOT / "data/approved.tsv")
    result.add_argument("--split-overrides", type=Path, default=ROOT / "data/split-overrides.tsv")
    result.add_argument("--speech-threshold", type=float, default=0.15)
    result.add_argument("--hangul-threshold", type=float, default=0.8)
    result.add_argument("--min-utterances", type=int, default=10)
    result.add_argument("--max-hours", type=float, default=5)
    result.add_argument("--search-results", type=int, default=5, help="maximum results per query/channel (cached)")
    result.add_argument("--delay", type=float, default=3, help="minimum seconds between download starts")
    result.add_argument("--min-duration", type=float, default=480,
                        help="seconds, inclusive (default: 480; retry-short: 60; human approve: at most 60)")
    result.add_argument("--max-duration", type=float, default=10800, help="seconds, inclusive")
    result.add_argument("--allow-non-korean", action="store_true")
    result.add_argument("--allow-music", action="store_true")
    result.add_argument("--reject-live-archives", action="store_true", help=argparse.SUPPRESS)
    result.add_argument("--deno", type=Path, default=ROOT.parent / "dev-tools/.local/deno/deno.exe")
    result.add_argument("--check", action="store_true", help="offline manifest/audio consistency check")
    return result


def main(argv: list[str] | None = None) -> int:
    options = parser()
    argv = list(sys.argv[1:] if argv is None else argv)
    commands = {"discover", "download", "apply-decisions", "review-sheet", "review-flags", "expand", "mark-density", "refilter", "migrate-splits", "retry-short", "retry-short-approved"}
    # Legacy bare query/URL invocations now safely default to metadata discovery.
    if argv and not argv[0].startswith("-") and argv[0] not in commands:
        argv.insert(0, "discover")
    options.set_defaults(min_duration=None)
    args = options.parse_args(argv)
    if args.command not in commands:
        args.sources.insert(0, args.command)
        args.command = "discover"
    if args.min_duration is None:
        args.min_duration = SHORT_MIN_DURATION if args.command == "retry-short" else 480
    scratch, audio_dir = ROOT / ".local/collect", ROOT / ".local/audio"
    try:
        manifest = Manifest(ROOT / "data/sources.tsv", scratch, args.split_overrides)
        if args.check:
            errors = check_manifest(manifest, audio_dir)
            for error in errors:
                print(error, file=sys.stderr)
            print(f"check: {len(errors)} error(s), {len(manifest.rows)} manifest row(s)")
            return int(bool(errors))
        if args.command == "migrate-splits":
            if args.sources or args.lowdensity_video or args.queries or args.channel_query:
                options.error("migrate-splits accepts no discovery inputs")
            print(json.dumps(manifest.migrate_splits()))
            return 0
        if args.command == "retry-short-approved":
            print(json.dumps(retry_short_approved(manifest, read_approvals(args.approvals), args.sources)))
            return 0
        if args.command == "retry-short":
            if args.lowdensity_video or args.queries or args.channel_query:
                options.error("retry-short accepts explicit video IDs only")
            summary = retry_short(manifest, read_approvals(args.approvals), args.sources, args.min_duration)
            for error in summary.get("errors", []):
                print(f"{error['video_id']}: {'; '.join(error['reasons'])}", file=sys.stderr)
            print(json.dumps(summary, ensure_ascii=False))
            return 2 if summary["failed"] else 0
        errors = split_override_errors(manifest.rows.values(), manifest.overrides)
        if errors:
            raise ValueError("; ".join(errors))
        numbers = (args.max_hours, args.delay, args.min_duration, args.max_duration,
                   args.speech_threshold, args.hangul_threshold)
        if (args.max_videos <= 0 or args.search_results <= 0 or args.max_candidates <= 0
                or not 1 <= args.per_channel <= MAX_PER_CHANNEL or not 0 <= args.speech_threshold <= 1
                or not 0 <= args.hangul_threshold <= 1 or args.min_utterances < 0
                or not all(math.isfinite(n) for n in numbers)
                or args.max_hours <= 0 or args.delay < 0 or args.min_duration <= 0
                or args.max_duration < args.min_duration):
            options.error(f"positive finite limits, 0 < min-duration <= max-duration, thresholds between 0 and 1, "
                          f"nonnegative min-utterances, "
                          f"and 1 <= per-channel <= {MAX_PER_CHANNEL} are required")
        if args.channel_query and (args.command != "expand" or any(not query.strip() for query in args.channel_query)):
            options.error("nonempty --channel-query terms belong to expand only")
        sources = []
        source_file = args.queries or (ROOT / "data/queries.txt" if not args.sources
                                      and args.command in {"discover", "refilter"} else None)
        if source_file:
            sources.extend(filter(None, (parse_source(line) for line in source_file.read_text(encoding="utf-8-sig").splitlines())))
        sources.extend(filter(None, (parse_source(line) for line in args.sources)))
        for value in args.lowdensity_video:
            video_id = video_id_from_url(value)
            if not video_id:
                options.error(f"invalid lowdensity video: {value}")
            sources.append(Source("video", f"https://www.youtube.com/watch?v={video_id}", True))
        if args.command == "discover" and not sources:
            options.error("no sources")
        if args.command not in {"discover", "refilter"} and sources:
            options.error("sources belong to stage-1 discover only")
        if args.command in {"discover", "download", "expand"} and not args.deno.is_file():
            options.error(f"Deno not found: {args.deno}")
        # Inspection flags must never apply decisions or move audio as a side effect.
        if args.command in {"mark-density", "review-flags"}:
            summary = (mark_density(manifest, ROOT / ".local/utterances", args.speech_threshold,
                                    args.hangul_threshold, args.min_utterances)
                       if args.command == "mark-density" else dict(sources=review_flags(manifest), failed=0))
            print(json.dumps(summary, ensure_ascii=False))
            return 0
        decisions = read_approvals(args.approvals)
        manifest.save()
        applied = apply_decisions(manifest, audio_dir, decisions)
        if args.command == "apply-decisions":
            summary = applied | {"failed": 0}
        elif args.command == "review-sheet":
            summary = dict(review_rows=write_review(manifest, decisions, scratch / "review.tsv"), failed=0)
        elif args.command == "refilter":
            summary = migrate_and_refilter(manifest, audio_dir, args, sources)
        else:
            youtube = Youtube(scratch, audio_dir, args.deno.resolve())
            if args.command == "discover":
                summary = discover_candidates(manifest, youtube, sources, args)
            elif args.command == "expand":
                summary = expand_channels(manifest, youtube, decisions, args)
            else:
                summary = collect(manifest, youtube, args, decisions)
        print(json.dumps(summary, ensure_ascii=False))
        return int(summary["failed"] > 0)
    except (CollectionError, ValueError, OSError) as error:
        print(f"stopped: {error}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("interrupted; completed manifest updates are preserved", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
