"""Transcribe local speech samples and retain timestamps for manual review."""

import argparse
import json
import math
import os
from pathlib import Path
import sys
import sysconfig
from time import perf_counter


ROOT = Path(__file__).resolve().parent
LOCAL = ROOT / ".local"
SAMPLE_RATE = 16_000


def cuda_dll_directories():
    nvidia = Path(sysconfig.get_path("platlib")) / "nvidia"
    directories = [
        nvidia / package / "bin" for package in ("cublas", "cudnn", "cuda_nvrtc")
    ]
    present = [str(directory) for directory in directories if directory.is_dir()]
    os.environ["PATH"] = os.pathsep.join([*present, os.environ.get("PATH", "")])
    return [os.add_dll_directory(directory) for directory in present]


def srt_timestamp(seconds):
    minutes, milliseconds = divmod(round(seconds * 1000), 60_000)
    hours, minutes = divmod(minutes, 60)
    seconds, milliseconds = divmod(milliseconds, 1000)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d},{milliseconds:03d}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("media", type=Path)
    parser.add_argument("--output", type=Path, required=True, help="Output JSON path. Also writes a sibling SRT.")
    parser.add_argument("--clip", nargs=2, type=float, action="append", metavar=("START", "END"),
                        help="Seconds in the source. Repeat to review several ranges in one run.")
    parser.add_argument("--language", default="ko", help="Language code, or auto for language detection.")
    parser.add_argument("--device", choices=("cuda", "cpu"), default="cuda")
    parser.add_argument("--model", help="faster-whisper model name or a local model directory.")
    parser.add_argument("--no-vad", action="store_true", help="Include audio removed by voice activity detection.")
    args = parser.parse_args()

    if not args.media.is_file():
        parser.error(f"media not found: {args.media}")
    if args.output.suffix.lower() != ".json":
        parser.error("--output must end in .json")
    output_paths = (args.output, args.output.with_suffix(".srt"))
    if any(path.exists() for path in output_paths):
        parser.error("output already exists; choose a new --output path")

    dll_handles = cuda_dll_directories() if args.device == "cuda" and os.name == "nt" else []
    try:
        from faster_whisper import WhisperModel
        from faster_whisper.audio import decode_audio

        audio = decode_audio(str(args.media), sampling_rate=SAMPLE_RATE)
        duration = len(audio) / SAMPLE_RATE
        clips = args.clip or [(0, duration)]
        for start, end in clips:
            if not (math.isfinite(start) and math.isfinite(end) and 0 <= start < end <= duration):
                parser.error(f"invalid clip {start:g}–{end:g}; media is {duration:.2f} seconds")
        clips = sorted(clips)
        if any(current[0] < previous[1] for previous, current in zip(clips, clips[1:])):
            parser.error("clips must not overlap")

        model_path = LOCAL / "models" / "whisper-turbo"
        model_source = args.model or (str(model_path) if (model_path / "model.bin").is_file() else "turbo")
        compute_type = "float16" if args.device == "cuda" else "int8"
        model = WhisperModel(model_source, device=args.device, compute_type=compute_type,
                             download_root=str(LOCAL / "models"))

        result_clips = []
        srt_blocks = []
        transcription_start = perf_counter()
        for start, end in clips:
            samples = audio[round(start * SAMPLE_RATE):round(end * SAMPLE_RATE)]
            segments, info = model.transcribe(
                samples,
                language=None if args.language == "auto" else args.language,
                task="transcribe",
                beam_size=5,
                condition_on_previous_text=False,
                vad_filter=not args.no_vad,
            )
            rows = []
            for segment in segments:
                content = segment.text.strip()
                if not content:
                    continue
                row = {
                    "start": round(start + segment.start, 3),
                    "end": round(min(end, start + segment.end), 3),
                    "text": content,
                    "avg_logprob": round(segment.avg_logprob, 3),
                    "no_speech_prob": round(segment.no_speech_prob, 3),
                }
                rows.append(row)
                timestamps = f"{srt_timestamp(row['start'])} --> {srt_timestamp(row['end'])}"
                srt_blocks.append(f"{len(srt_blocks) + 1}\n{timestamps}\n{content}\n")
            result_clips.append({"start": start, "end": end, "language": info.language, "segments": rows})
            print(f"{start:g}–{end:g}s: {len(rows)} segments", flush=True)

        elapsed = round(perf_counter() - transcription_start, 2)
        result = {
            "source_file": args.media.name,
            "model": args.model or "large-v3-turbo",
            "device": args.device,
            "compute_type": compute_type,
            "vad_enabled": not args.no_vad,
            "inference_seconds": elapsed,
            "clips": result_clips,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        output_paths[1].write_text("\n".join(srt_blocks), encoding="utf-8")
        print(f"Transcription completed in {elapsed:.2f}s on {args.device}/{compute_type}.")
    finally:
        for handle in dll_handles:
            handle.close()


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
