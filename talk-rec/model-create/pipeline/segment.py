"""Transcribe local media on CUDA and group words using the device's limits."""

import argparse
from collections import Counter
import json
import math
import os
from pathlib import Path
import re
import sysconfig
from time import perf_counter

from common.schema import validate_utterance


ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / ".local"
MODEL = ROOT.parent / "dev-tools/.local/models/whisper-turbo"
RATE = 16_000
PRE_ROLL = 0.3
MEDIA_EXTENSIONS = {".webm", ".m4a", ".mp4", ".mkv", ".wav", ".mp3", ".ogg", ".flac", ".opus", ".aac", ".mov"}
DECODING = dict(language="ko", task="transcribe", word_timestamps=True,
                condition_on_previous_text=False, beam_size=5, temperature=0.0,
                vad_filter=True, no_speech_threshold=None,
                log_prob_threshold=-1.0, compression_ratio_threshold=2.4,
                hallucination_silence_threshold=None)
FILTER_NAMES = ("vad_non_speech", "segment_no_speech", "missing_word_times",
                "invalid_word_time", "long_word_artifact", "non_speech_marker",
                "korean_closing", "repeated_token", "low_confidence")


def source_name(source):
    """Keep Windows paths safe without silently aliasing arbitrary source IDs."""
    if not source.strip() or re.search(r'[<>"/\\|?*\x00-\x1f]', source):
        raise ValueError("source contains an invalid filename character")
    name = source.replace(":", "_")
    if name.endswith((".", " ")) or name.split(".")[0].upper() in {
        "CON", "PRN", "AUX", "NUL", *[f"COM{i}" for i in range(1, 10)],
        *[f"LPT{i}" for i in range(1, 10)],
    }:
        raise ValueError("source is not a safe Windows filename")
    return name


def group_words(words, gap_seconds=1.0, max_seconds=12.0):
    """Input must be nonnegative-duration words in start order, already cleaned."""
    if not math.isfinite(gap_seconds) or gap_seconds <= 0 or not math.isfinite(max_seconds) or max_seconds <= 0:
        raise ValueError("gap and cap must be finite and positive")
    groups, current = [], []
    previous_start = -1.0
    group_end = 0.0
    for word in words:
        start, end = word["start"], word["end"]
        if not (math.isfinite(start) and math.isfinite(end) and 0 <= start <= end
                and start >= previous_start and end - start <= max_seconds + 1e-9):
            raise ValueError("invalid, unsorted, or over-cap word")
        previous_start = start
        if current and (start - current[-1]["end"] >= gap_seconds - 1e-9
                        or max(group_end, end) - current[0]["start"] > max_seconds + 1e-9):
            groups.append(current)
            current = []
            group_end = 0.0
        current.append(word)
        group_end = max(group_end, end)
    if current:
        groups.append(current)
    return groups


def rejection(words, min_probability=0.2, repeat_count=6,
              closing_repeats=2, closing_probability=0.5):
    """One primary removal reason per utterance; never normalize stored text."""
    text = "".join(w["text"] for w in words).strip()
    probability = sum(w["probability"] for w in words) / len(words)
    if not text or re.fullmatch(
        r"(?:\s|[♪♫]|\[(?:음악|무음|잡음|박수|music|silence|noise)\]|"
        r"\((?:음악|무음|잡음|박수|music|silence|noise)\))+", text, re.I
    ):
        return "non_speech_marker"
    compact = "".join(c for c in text.lower() if c.isalnum())
    phrase = r"(?:시청해주셔서감사합니다|시청해주셔서고맙습니다|시청감사합니다|감사합니다|다음영상에서만나요|구독과좋아요부탁드립니다)"
    closing = re.fullmatch(f"(?:{phrase})+", compact)
    credits = re.match(r"^(?:자막제공|자막제작|자막번역|한글자막|subtitlesby|amaraorg)", compact)
    if credits or (closing and (len(re.findall(phrase, compact)) >= closing_repeats
                               or probability < closing_probability)):
        return "korean_closing"
    tokens = re.findall(r"[^\W_]+", text.lower())
    if (len(tokens) >= repeat_count and len(set(tokens)) == 1
            and tokens[0] not in {"웃음", "laughter", "laughs", "lol"}
            and not re.fullmatch(r"[ㅋㅎ하허호히헤흐크깔낄푸]+|(?:ha)+|(?:he)+", tokens[0])):
        return "repeated_token"
    if probability < min_probability:
        return "low_confidence"
    return None


def pcm_boundaries(start, end, audio_samples, tail_seconds=0.2):
    """Return integer sample offsets, including pre-roll without clipping speech."""
    if tail_seconds < 0 or not math.isfinite(tail_seconds):
        raise ValueError("tail must be finite and nonnegative")
    first = max(0, round(start * RATE) - round(PRE_ROLL * RATE))
    speech_end = round(end * RATE)
    if not 0 <= first <= speech_end <= audio_samples:
        raise ValueError("speech boundaries outside audio")
    last = min(audio_samples, speech_end + round(tail_seconds * RATE), first + 12 * RATE)
    if last < speech_end:
        raise ValueError("speech plus pre-roll exceeds 12 seconds; use the PCM grouping budget")
    if last <= first:
        raise ValueError("PCM interval is empty; zero-duration speech requires padding")
    return first, last


def make_record(source, utt, words, audio_samples=None, tail_seconds=0.2):
    record = dict(source=source, utt=utt, start=float(words[0]["start"]),
                  end=float(max(w["end"] for w in words)),
                  text="".join(w["text"] for w in words).strip(),
                  asr="whisper", speaker=None,
                  mean_word_probability=float(sum(w["probability"] for w in words) / len(words)),
                  words=[dict(start=float(w["start"]), end=float(w["end"]),
                              text=w["text"], probability=float(w["probability"])) for w in words])
    if audio_samples is not None:
        first, last = pcm_boundaries(record["start"], record["end"], audio_samples, tail_seconds)
        record["pcm"] = dict(start=first / RATE, end=last / RATE,
                             start_sample=first, end_sample=last, sample_rate=RATE)
    validate_utterance(record)
    return record


def distribution(values):
    if not values:
        return dict(min=None, median=None, p90=None, max=None)
    values = sorted(values)
    def quantile(p):
        index = (len(values) - 1) * p
        low = math.floor(index)
        return values[low] + (values[math.ceil(index)] - values[low]) * (index - low)
    return dict(min=values[0], median=quantile(0.5), p90=quantile(0.9), max=values[-1])


def build_records(raw, source, args):
    removed = list(raw["vad_removed"])
    words = []
    # Reserving pre-roll is necessary to preserve all speech in a <=12 s PCM.
    cap = min(args.max_seconds, 12.0 - PRE_ROLL) if args.export_pcm else args.max_seconds
    for segment in raw["segments"]:
        reason = None
        if segment["no_speech_prob"] >= args.no_speech_probability and segment["avg_logprob"] < args.no_speech_logprob:
            reason = "segment_no_speech"
        elif not segment["words"] and segment["text"].strip():
            reason = "missing_word_times"
        if reason:
            removed.append(dict(reason=reason, unit="segment", **segment))
            continue
        for word in segment["words"]:
            start, end = word["start"], word["end"]
            if not (math.isfinite(start) and math.isfinite(end) and 0 <= start <= end <= raw["audio_duration"]):
                reason = "invalid_word_time"
            elif end - start > min(args.max_word_seconds, cap) + 1e-9:
                reason = "long_word_artifact"
            else:
                words.append(word)
                continue
            removed.append(dict(reason=reason, unit="word", **word))
    words.sort(key=lambda w: w["start"])
    records = []
    groups = group_words(words, args.gap_seconds, cap)
    for group in groups:
        reason = rejection(group, args.min_probability, args.repeat_count,
                           args.closing_repeats, args.closing_probability)
        if reason:
            removed.append(dict(reason=reason, unit="utterance", start=group[0]["start"],
                                end=max(w["end"] for w in group), words=group))
        else:
            records.append(make_record(source, len(records), group,
                                       raw["audio_samples"] if args.export_pcm else None, args.tail_seconds))
    counts = Counter(item["reason"] for item in removed)
    stats = dict(source=source, audio_duration=raw["audio_duration"],
                 inference_seconds=raw["inference_seconds"],
                 real_time_factor=raw["inference_seconds"] / raw["audio_duration"],
                 utterance_count=len(records), candidate_utterance_count=len(groups),
                 duration_seconds=distribution([r["end"] - r["start"] for r in records]),
                 char_count_median=distribution([len(r["text"]) for r in records])["median"],
                 zero_duration_words_kept=sum(w["start"] == w["end"] for r in records for w in r["words"]),
                 removed_counts={name: counts[name] for name in FILTER_NAMES},
                 vad_removed_seconds=sum(r["end"] - r["start"] for r in raw["vad_removed"]),
                 removed=removed, decoding=raw["decoding"],
                 parameters={k: v for k, v in vars(args).items() if not isinstance(v, Path)},
                 effective_word_span_cap=cap)
    return records, stats


def cuda_dll_directories():
    nvidia = Path(sysconfig.get_path("platlib")) / "nvidia"
    present = [str(nvidia / package / "bin") for package in ("cublas", "cudnn", "cuda_nvrtc")
               if (nvidia / package / "bin").is_dir()]
    os.environ["PATH"] = os.pathsep.join([*present, os.environ.get("PATH", "")])
    return [os.add_dll_directory(directory) for directory in present] if os.name == "nt" else []


def transcribe(model, audio, args):
    from faster_whisper.vad import VadOptions, get_speech_timestamps

    vad = dict(threshold=args.vad_threshold, min_speech_duration_ms=0,
               min_silence_duration_ms=1000, speech_pad_ms=300)
    started = perf_counter()
    # Audit the same VAD used by transcribe, which restores original-source times.
    chunks = get_speech_timestamps(audio, VadOptions(**vad))
    removed, previous = [], 0
    for chunk in [*chunks, dict(start=len(audio), end=len(audio))]:
        if chunk["start"] > previous:
            removed.append(dict(reason="vad_non_speech", unit="region",
                                start=previous / RATE, end=chunk["start"] / RATE))
        previous = chunk["end"]
    rows = []
    if chunks:
        segments, _ = model.transcribe(audio, **DECODING, vad_parameters=vad)
        for index, segment in enumerate(segments):
            rows.append(dict(start=float(segment.start), end=float(segment.end), text=segment.text,
                             avg_logprob=float(segment.avg_logprob), no_speech_prob=float(segment.no_speech_prob),
                             words=[dict(start=float(w.start), end=float(w.end), text=w.word, probability=float(w.probability))
                                    for w in segment.words or []]))
            if index % 100 == 0:
                print(f"  decoded through {segment.end:.1f}s / {len(audio) / RATE:.1f}s", flush=True)
    return dict(audio_duration=len(audio) / RATE, audio_samples=len(audio),
                inference_seconds=perf_counter() - started, segments=rows,
                vad_removed=removed, decoding={**DECODING, "vad_parameters": vad},
                model=str(MODEL), device="cuda", compute_type="float16")


def input_sources(media, source):
    if media.is_file():
        if not source:
            raise ValueError("a single file requires --source")
        return [(media, source)]
    if not media.is_dir() or source:
        raise ValueError("provide a media file with --source, or a directory without --source")
    sources = [(p, "yt:" + p.stem) for p in sorted(media.iterdir())
               if p.is_file() and p.suffix.lower() in MEDIA_EXTENSIONS]
    names = [source_name(s) for _, s in sources]
    if len(set(names)) != len(names):
        raise ValueError("multiple media files map to the same source")
    return sources


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("media", type=Path)
    result.add_argument("--source")
    result.add_argument("--force", action="store_true")
    result.add_argument("--export-pcm", action="store_true")
    result.add_argument("--reuse-transcript", action="store_true", help="Reuse a matching .local/segment Whisper cache")
    result.add_argument("--gap-seconds", type=float, default=1.0)
    result.add_argument("--max-seconds", type=float, default=12.0)
    result.add_argument("--max-word-seconds", type=float, default=3.0)
    result.add_argument("--tail-seconds", type=float, default=0.2)
    result.add_argument("--vad-threshold", type=float, default=0.5)
    result.add_argument("--no-speech-probability", type=float, default=0.6)
    result.add_argument("--no-speech-logprob", type=float, default=-1.0)
    result.add_argument("--min-probability", type=float, default=0.2)
    result.add_argument("--repeat-count", type=int, default=6)
    result.add_argument("--closing-repeats", type=int, default=2)
    result.add_argument("--closing-probability", type=float, default=0.5)
    return result


def main():
    cli = parser()
    args = cli.parse_args()
    for name in ("gap_seconds", "max_seconds", "max_word_seconds"):
        if not math.isfinite(getattr(args, name)) or getattr(args, name) <= 0:
            cli.error(f"{name} must be finite and positive")
    if args.max_seconds > 12:
        cli.error("max-seconds must not exceed the device's 12 seconds")
    for name in ("vad_threshold", "no_speech_probability", "min_probability", "closing_probability"):
        if not 0 <= getattr(args, name) <= 1:
            cli.error(f"{name} must be in 0..1")
    if not math.isfinite(args.tail_seconds) or args.tail_seconds < 0 or not math.isfinite(args.no_speech_logprob):
        cli.error("tail must be finite and nonnegative; no-speech-logprob must be finite")
    if min(args.repeat_count, args.closing_repeats) < 2:
        cli.error("repeat thresholds must be at least 2")
    try:
        sources = input_sources(args.media, args.source)
        pending = [(p, s, source_name(s)) for p, s in sources
                   if args.force or not (LOCAL / "utterances" / f"{source_name(s)}.jsonl").exists()]
    except ValueError as error:
        cli.error(str(error))
    print(f"sources={len(sources)} skipped={len(sources) - len(pending)} pending={len(pending)}", flush=True)
    if not pending:
        return
    if not (MODEL / "model.bin").is_file():
        raise RuntimeError(f"local model unavailable: {MODEL}; no download or CPU fallback")
    handles = cuda_dll_directories()
    try:
        import ctranslate2
        from faster_whisper import WhisperModel
        from faster_whisper.audio import decode_audio

        if ctranslate2.get_cuda_device_count() < 1 or "float16" not in ctranslate2.get_supported_compute_types("cuda"):
            raise RuntimeError("CUDA float16 unavailable; no CPU fallback")
        model = None
        for media, source, name in pending:
            audio = decode_audio(str(media), sampling_rate=RATE)
            if not len(audio):
                raise ValueError(f"empty audio: {media}")
            cache = LOCAL / "segment" / f"{name}.whisper.json"
            identity = dict(path=str(media.resolve()), size=media.stat().st_size,
                            mtime_ns=media.stat().st_mtime_ns, vad_threshold=args.vad_threshold,
                            decoding=DECODING, model=str(MODEL))
            if args.reuse_transcript:
                raw = json.loads(cache.read_text(encoding="utf-8"))
                if raw["input"] != identity:
                    raise ValueError("cached transcript does not match input/decoding options")
            else:
                if model is None:
                    model = WhisperModel(str(MODEL), device="cuda", compute_type="float16", local_files_only=True)
                raw = transcribe(model, audio, args)
                raw["input"] = identity
                cache.parent.mkdir(parents=True, exist_ok=True)
                cache.write_text(json.dumps(raw, ensure_ascii=False) + "\n", encoding="utf-8")
            records, stats = build_records(raw, source, args)
            if args.export_pcm:
                import numpy as np
                pcm_dir = LOCAL / "pcm" / name
                pcm_dir.mkdir(parents=True, exist_ok=True)
                # Only remove stale files belonging to this source, after successful inference.
                for path in pcm_dir.glob("[0-9][0-9][0-9][0-9][0-9][0-9].pcm"):
                    path.unlink()
                for record in records:
                    bounds = record["pcm"]
                    samples = audio[bounds["start_sample"]:bounds["end_sample"]]
                    pcm = np.clip(np.rint(samples * 32768), -32768, 32767).astype("<i2")
                    (pcm_dir / f"{record['utt']:06d}.pcm").write_bytes(pcm.tobytes())
            output = LOCAL / "utterances" / f"{name}.jsonl"
            output.parent.mkdir(parents=True, exist_ok=True)
            temporary = LOCAL / "segment" / f"{name}.jsonl.tmp"
            temporary.parent.mkdir(parents=True, exist_ok=True)
            with temporary.open("w", encoding="utf-8", newline="\n") as stream:
                for record in records:
                    validate_utterance(record)
                    stream.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
            output.with_suffix(".stats.json").write_text(
                json.dumps(stats, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
            temporary.replace(output)
            print(json.dumps({k: stats[k] for k in ("source", "inference_seconds", "real_time_factor", "utterance_count", "duration_seconds", "removed_counts")}), flush=True)
    finally:
        for handle in handles:
            handle.close()


if __name__ == "__main__":
    main()
