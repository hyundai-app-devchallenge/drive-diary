"""Only synthetic placeholder tokens; never load models or real transcripts."""
from copy import deepcopy
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from demo.asr import configs, fetch, run
from demo.asr.data import approved, clip_path
from demo.asr.metrics import repetition_count, summarize
from pipeline import segment
from tools.listen.storage import atomic_write, clip_name, encode_lines, read_jsonl


def fixture_root(root, count=3):
    rows = []
    for i, source in enumerate(["yt:00000000001", "yt:IOlxH67P1JM", "yt:zn4tDPT4RLQ"][:count]):
        rows.append(dict(id=source+"@0", source=source, start=10, end=40,
                         audio=dict(path=f".local/audio/{source[3:]}.webm", start=10, end=40), origin="approved"))
    excluded = dict(rows[0], id=rows[0]["source"]+"@1", origin="prescreened")
    atomic_write(root / ".local/demo/sample.jsonl", encode_lines(rows+[excluded]))
    for row in rows:
        atomic_write(root / ".local/demo/clips" / clip_name(row["id"]), bytes(range(100)))
    return rows


def word(start, end, text=" X", probability=.95):
    return dict(start=start, end=end, text=text, probability=probability)


def raw():
    return dict(audio_duration=40., audio_samples=640000, inference_seconds=.5, vad_removed=[],
                decoding=dict(segment.DECODING), segments=[dict(start=1., end=3., text=" X Y", avg_logprob=-.2,
                no_speech_prob=.1, words=[word(1., 1.5), word(2., 3., " Y")])])


def test_config_table():
    assert configs.validate_configs() is configs.CONFIGS
    assert len(configs.CONFIGS) == 4
    turbo = configs.CONFIGS["turbo-base"]
    large = configs.CONFIGS["large-v3-base"]
    assert turbo.model == segment.MODEL and large.model == configs.LARGE_REPO
    for k, v in segment.DECODING.items():
        assert turbo.decoding()[k] == large.decoding()[k] == v
    assert turbo.decoding()["vad_parameters"] == dict(threshold=.5, min_speech_duration_ms=0, min_silence_duration_ms=1000, speech_pad_ms=300)
    long = configs.CONFIGS["large-v3-long"].decoding()
    assert long["language"] == "ko" and long["beam_size"] == 5
    assert long["word_timestamps"] and long["condition_on_previous_text"]
    assert long["vad_filter"] is False and long["chunk_length"] == 30
    assert long["temperature"] == [0, .2, .4] and "initial_prompt" not in long
    assert configs.CONFIGS["large-v3-long-prompt"].decoding() == dict(long, initial_prompt=configs.PROMPT)
    long["temperature"].append(.8)
    assert configs.CONFIGS["large-v3-long"].decoding()["temperature"] == [0, .2, .4]


@pytest.mark.parametrize("table", [{"bad/name": configs.Config(Path("x"))}, {"x": configs.Config("untrusted/repo")},
    {"x": configs.Config(Path("x"), "other")}, {"x": configs.Config(Path("x"), "base", "prompt")}])
def test_reject_bad_configs(table):
    with pytest.raises(ValueError):
        configs.validate_configs(table)


def test_fetch_only_allowlisted_assets(tmp_path):
    calls = []
    fetch.fetch(tmp_path, lambda **kw: calls.append(kw))
    assert len(calls) == 1 and calls[0]["repo_id"] == configs.LARGE_REPO
    assert calls[0]["cache_dir"] == str(tmp_path)
    assert "model.bin" in calls[0]["allow_patterns"]
    assert not any(".py" in x for x in calls[0]["allow_patterns"])
    assert "trust_remote_code" not in calls[0]


def test_local_model_validation(tmp_path):
    c = configs.Config(tmp_path)
    with pytest.raises(ValueError):
        configs.model_path(c)
    for name in ("model.bin", "config.json", "tokenizer.json"):
        (tmp_path / name).write_bytes(b"synthetic")
    assert configs.model_path(c) == tmp_path.resolve()


def test_long_gap_and_no_cap():
    words = [word(0, 1), word(1.79, 2), word(2.8, 3), word(3.1, 38)]
    lines = run.long_lines(words)
    assert [(l["start"], l["end"]) for l in lines] == [(0, 2), (2.8, 38)]
    assert run.long_lines([]) == []
    with pytest.raises(ValueError):
        run.long_lines([word(1, 2), word(0, 1)])


def test_baseline_reproduces_grouping_and_filters():
    r = raw()
    r["segments"][0]["words"] = [word(i*2, i*2+.5, f" T{i}") for i in range(10)]
    args = segment.parser().parse_args(["clip.wav"])
    expected, _ = segment.build_records(r, "asr-clip", args)
    result = run.to_result("yt:00000000001@0", "turbo-base", configs.CONFIGS["turbo-base"], r, .1)
    assert result["lines"] == [{k: e[k] for k in ("start", "end", "text")} for e in expected]
    r["segments"][0]["no_speech_prob"] = .9
    r["segments"][0]["avg_logprob"] = -2
    assert not run.to_result(result["id"], "turbo-base", configs.CONFIGS["turbo-base"], r, 0)["lines"]


@pytest.mark.parametrize("text,count", [("x "*7, 0), ("x "*8, 1), ("token "*16, 1), ("가"*8, 1),
    ("가"*7, 0), ("가"*8+" Y "+"Z "*8, 2), ("a,b,a,b,"*8, 0), ("", 0), ("가가가가가가가가 "*8, 1), ("가나"*8, 1)])
def test_repetition(text, count):
    assert repetition_count(text) == count


def test_confidence_and_empty_long():
    r = raw()
    r["segments"].append(dict(r["segments"][0], start=3., end=9., avg_logprob=-.6, no_speech_prob=.3,
                               words=[word(3, 9, " Z")]))
    result = run.to_result("yt:00000000001@0", "large-v3-long", configs.CONFIGS["large-v3-long"], r, .4)
    assert result["avg_logprob"] == pytest.approx(-.5)
    assert result["no_speech_prob"] == pytest.approx(.25)
    r["segments"] = []
    result = run.to_result(result["id"], result["config"], configs.CONFIGS[result["config"]], r, 0)
    assert result["avg_logprob"] is None and result["lines"] == []
    r = raw(); r["segments"][0]["words"] = []
    with pytest.raises(ValueError):
        run.to_result(result["id"], result["config"], configs.CONFIGS[result["config"]], r, 0)


class FakeMemory:
    def __enter__(self): return self
    def __exit__(self, *_): pass
    def stats(self): return dict(peak_mib=123, samples=2)


@pytest.mark.parametrize("name", list(configs.CONFIGS))
def test_runner_fake_only(tmp_path, name, capsys):
    fixture_root(tmp_path)
    calls = []
    class Backend:
        def __init__(self, c): calls.append(c)
        def __call__(self, path, c):
            calls.append(path)
            return raw()
        def close(self): calls.append("closed")
    out = tmp_path / ".local/demo/asr" / f"{name}.jsonl"
    stats = run.run(name, out, root=tmp_path, backend_factory=Backend, sampler_factory=FakeMemory)
    records = read_jsonl(out)
    assert len(records) == 3 and calls[-1] == "closed"
    assert stats["gpu"]["peak_mib"] == 123 and stats["metrics"]["clip_chars"]["total"] == 9
    assert stats["model_load_seconds"] >= 0 and stats["total_seconds"] >= stats["inference_seconds"]
    assert json.loads(out.with_suffix(".stats.json").read_text(encoding="utf-8"))["clip_count"] == 3
    assert not list(out.parent.glob("*.tmp"))
    log = capsys.readouterr().out
    assert raw()["segments"][0]["text"] not in log


def test_failed_run_keeps_previous_and_closes(tmp_path):
    fixture_root(tmp_path)
    closed = []
    class Backend:
        def __init__(self, c): pass
        def __call__(self, *_): raise RuntimeError("synthetic failure")
        def close(self): closed.append(True)
    out = tmp_path / "result.jsonl"
    out.write_bytes(b"previous")
    with pytest.raises(RuntimeError):
        run.run("turbo-base", out, root=tmp_path, backend_factory=Backend, sampler_factory=FakeMemory)
    assert out.read_bytes() == b"previous" and closed == [True]


def test_approved_only_and_clip_name(tmp_path):
    rows = fixture_root(tmp_path)
    assert len(approved(tmp_path)) == 3
    assert clip_path(tmp_path, rows[0]["id"]).name == "yt_00000000001@0.wav"
    with pytest.raises(ValueError):
        clip_path(tmp_path, "../../outside")


@pytest.mark.parametrize("name", list(configs.CONFIGS))
def test_whisper_adapter_with_fake_modules(tmp_path, monkeypatch, name):
    """Exercise lazy backend wiring without importing CT2/FW or loading any model."""
    import sys
    calls = {}
    handle = SimpleNamespace(close=lambda: calls.update(closed=True))
    monkeypatch.setattr(segment, "cuda_dll_directories", lambda: [handle])
    monkeypatch.setattr(run, "model_path", lambda config: tmp_path)
    monkeypatch.setitem(sys.modules, "ctranslate2", SimpleNamespace(get_cuda_device_count=lambda: 1,
        get_supported_compute_types=lambda device: {"float16"}))
    class FakeModel:
        def __init__(self, path, **kwargs): calls["init"] = (path, kwargs)
        def transcribe(self, audio, **kwargs):
            calls["decode"] = kwargs
            s = raw()["segments"][0]
            words = [SimpleNamespace(start=w["start"], end=w["end"], word=w["text"], probability=w["probability"]) for w in s["words"]]
            return iter([SimpleNamespace(**dict(s, words=words))]), None
    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=FakeModel))
    monkeypatch.setitem(sys.modules, "faster_whisper.audio", SimpleNamespace(decode_audio=lambda path, sampling_rate: [0]*640000))
    def fake_pipeline(model, audio, args):
        calls["baseline"] = vars(args)
        return raw()
    monkeypatch.setattr(segment, "transcribe", fake_pipeline)
    backend = run.WhisperBackend(configs.CONFIGS[name])
    try:
        result = backend(tmp_path / "clip.wav", configs.CONFIGS[name])
        assert result["segments"] and result["audio_duration"] == 40
        assert calls["init"][1] == dict(device="cuda", device_index=0, compute_type="float16", local_files_only=True)
        if configs.CONFIGS[name].mode == "base":
            assert calls["baseline"]["max_seconds"] == 12 and calls["baseline"]["vad_threshold"] == .5
        else:
            assert calls["decode"] == configs.CONFIGS[name].decoding()
    finally:
        backend.close()
    assert calls["closed"]


def test_gpu_sampler_fake_process(monkeypatch):
    from demo.asr import gpu
    calls = []
    values = iter(["100", "250", "200"])
    def fake_process(args, **kwargs):
        calls.append(args)
        return SimpleNamespace(stdout=next(values))
    monkeypatch.setattr(gpu.subprocess, "run", fake_process)
    sampler = gpu.MemorySampler()
    for _ in range(3): sampler.sample()
    assert sampler.stats()["peak_mib"] == 250 and sampler.stats()["samples"] == 3
    assert "--id=0" in calls[0] and "--query-gpu=memory.used" in calls[0]
    monkeypatch.setattr(gpu.subprocess, "run", lambda *a, **kw: SimpleNamespace(stdout="N/A"))
    unknown = gpu.MemorySampler()
    unknown.sample()
    assert unknown.stats()["peak_mib"] is None and unknown.stats()["status"] == "unavailable"
