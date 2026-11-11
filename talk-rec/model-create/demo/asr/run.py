"""Run one config on approved pre-cut clips. No download, no CPU fallback."""
import argparse
import math
from pathlib import Path
from time import perf_counter

from pipeline import segment as baseline
from tools.listen.storage import ROOT, atomic_write, encode_lines
from .configs import CONFIGS, model_path, validate_configs
from .data import approved, clip_path, validate_result, write_json
from .gpu import MemorySampler
from .metrics import summarize


def long_lines(words, gap=0.8):
    groups, current = [], []
    previous = -1.0
    for word in words:
        start, end = word["start"], word["end"]
        if not (math.isfinite(start) and math.isfinite(end) and 0 <= start <= end and start >= previous):
            raise ValueError("단어 타임스탬프 오류")
        previous = start
        if current and start - max(w["end"] for w in current) >= gap - 1e-9:
            groups.append(current)
            current = []
        current.append(word)
    if current:
        groups.append(current)
    return [dict(start=g[0]["start"], end=max(w["end"] for w in g),
                 text="".join(w["text"] for w in g).strip()) for g in groups]


def to_result(block_id, name, config, raw, seconds):
    if config.mode == "base":
        # Same defaults, cleaning, rejection, 1s gaps, and 12s grouping as pipeline CLI.
        args = baseline.parser().parse_args(["clip.wav"])
        records, _ = baseline.build_records(raw, "asr-clip", args)
        lines = [{k: r[k] for k in ("start", "end", "text")} for r in records]
    else:
        if any(s["text"].strip() and not s["words"] for s in raw["segments"]):
            raise ValueError("long 설정의 단어 타임스탬프가 없습니다.")
        lines = long_lines([w for s in raw["segments"] for w in s["words"]])
    # Duration-weighted segment confidence, including segments baseline filters out.
    weights = [max(0, s["end"] - s["start"]) for s in raw["segments"]]
    denominator = sum(weights)
    means = {k: sum(s[k] * w for s, w in zip(raw["segments"], weights)) / denominator
             if denominator else None for k in ("avg_logprob", "no_speech_prob")}
    return validate_result(dict(id=block_id, config=name, lines=lines, seconds=seconds, **means))


class WhisperBackend:
    def __init__(self, config):
        self.handles = baseline.cuda_dll_directories()
        try:
            import ctranslate2
            from faster_whisper import WhisperModel
            if ctranslate2.get_cuda_device_count() < 1 or "float16" not in ctranslate2.get_supported_compute_types("cuda"):
                raise ValueError("CUDA float16을 사용할 수 없습니다.")
            self.path = model_path(config)
            self.model = WhisperModel(str(self.path), device="cuda", device_index=0,
                                      compute_type="float16", local_files_only=True)
        except Exception:
            self.close()
            raise

    def __call__(self, path, config):
        from faster_whisper.audio import decode_audio
        audio = decode_audio(str(path), sampling_rate=baseline.RATE)
        if not len(audio):
            raise ValueError("빈 오디오 클립입니다.")
        if config.mode == "base":
            return baseline.transcribe(self.model, audio, baseline.parser().parse_args(["clip.wav"]))
        segments, _ = self.model.transcribe(audio, **config.decoding())
        rows = [dict(start=float(s.start), end=float(s.end), text=s.text,
                     avg_logprob=float(s.avg_logprob), no_speech_prob=float(s.no_speech_prob),
                     words=[dict(start=float(w.start), end=float(w.end), text=w.word,
                                 probability=float(w.probability)) for w in s.words or []]) for s in segments]
        return dict(audio_duration=len(audio) / baseline.RATE, audio_samples=len(audio), segments=rows,
                    vad_removed=[])

    def close(self):
        self.model = None
        for handle in self.handles:
            handle.close()
        self.handles = []


def run(name, out, *, root=ROOT, backend_factory=WhisperBackend, sampler_factory=MemorySampler):
    validate_configs()
    config = CONFIGS[name]
    rows = approved(root)
    clips = [(row, clip_path(root, row["id"])) for row in rows]
    started = perf_counter()
    results = []
    with sampler_factory() as memory:
        load_start = perf_counter()
        backend = backend_factory(config)
        load_seconds = perf_counter() - load_start
        try:
            for i, (row, path) in enumerate(clips, 1):
                clip_start = perf_counter()
                raw = backend(path, config)
                result = to_result(row["id"], name, config, raw, perf_counter() - clip_start)
                results.append(result)
                print(f"{name}: {i}/{len(clips)} {row['id']} {result['seconds']:.3f}s", flush=True)
        finally:
            backend.close()
    stats = dict(config=name, model=str(config.model), resolved_model=str(getattr(backend, "path", config.model)),
                 decoding=config.decoding(), mode=config.mode, clip_count=len(results),
                 total_seconds=perf_counter() - started, model_load_seconds=load_seconds,
                 inference_seconds=sum(r["seconds"] for r in results), gpu=memory.stats(),
                 metrics=summarize(results), timestamp_basis="seconds from padded WAV start")
    # Publish only complete results; atomic_write removes its sibling temp on every exit.
    out = Path(out)
    atomic_write(out, encode_lines(results))
    write_json(out.with_suffix(".stats.json"), stats)
    return stats


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", choices=list(CONFIGS), required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        stats = run(args.config, args.out)
    except Exception:
        # Third party exceptions can contain decoded text; keep CLI logs text-free.
        parser.exit(1, "ASR 실행 실패: 클립·로컬 모델·CUDA·결과 경로를 확인하세요. 모델 다운로드는 fetch로 별도 실행하세요.\n")
    print(f"완료: {stats['clip_count']}개, 총 {stats['total_seconds']:.2f}초, GPU {stats['gpu']['peak_mib']} MiB")


if __name__ == "__main__":
    main()
