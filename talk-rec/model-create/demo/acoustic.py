"""Local 16 kHz acoustic features; models are imported only by real backends."""
from __future__ import annotations

import argparse
from collections import defaultdict
import math
from pathlib import Path
import time

import numpy as np

from .data import atomic_text, jsonl, load_blocks, write_json

RATE = 16000
AST_MODEL = 'MIT/ast-finetuned-audioset-10-10-0.4593'
SPEAKER_MODEL = 'speechbrain/spkrec-ecapa-voxceleb'
# Exact AudioSet display names, resolved against config.id2label, never numeric IDs.
# Every available match is recorded in stats; a missing primary label is an error.
EVENT_LABELS = {
    'laughter': ('Laughter', 'Baby laughter', 'Giggle', 'Snicker', 'Belly laugh', 'Chuckle, chortle'),
    'singing': ('Singing', 'Choir', 'Yodeling', 'Chant', 'Mantra', 'Child singing', 'Synthetic singing', 'Rapping', 'Humming'),
    'music': ('Music',),
    'child_speech': ('Child speech, kid speaking',),
    'baby_cry': ('Baby cry, infant cry',),
    'shout': ('Shout', 'Bellow', 'Whoop', 'Yell', 'Children shouting', 'Screaming'),
    'applause': ('Applause', 'Cheering'),
    'vehicle': ('Vehicle', 'Engine', 'Engine starting', 'Idling', 'Accelerating, revving, vroom'),
    'speech': ('Speech',),
}


def resolve_labels(id2label):
    available = {name: int(i) for i, name in id2label.items()}
    resolved = {}
    for event, names in EVENT_LABELS.items():
        if names[0] not in available:
            raise ValueError(f'AST config missing primary label for {event}')
        resolved[event] = {name: available[name] for name in names if name in available}
    return resolved


def rms_db(samples):
    x = np.asarray(samples, dtype=np.float64)
    return float(20 * np.log10(max(1e-6, np.sqrt(np.mean(x * x))))) if len(x) else -120.0


def pitch(samples, rate=RATE):
    """40 ms frames/20 ms hops, normalized autocorrelation, 65–600 Hz.

    First strong local peak avoids choosing a later integer multiple. Silence
    gate -50 dBFS, correlation >= .6. This is a cue, not a pitch tracker for music.
    """
    x = np.asarray(samples, dtype=np.float64)
    size, hop = round(.04 * rate), round(.02 * rate)
    f0 = []
    total = 0
    lo, hi = math.ceil(rate / 600), math.floor(rate / 65)
    for start in range(0, max(0, len(x) - size + 1), hop):
        total += 1
        frame = x[start:start + size]
        if rms_db(frame) < -50:
            continue
        frame = frame - frame.mean()
        # Normalized lag correlation removes the triangular finite-frame bias.
        corr = np.array([np.dot(frame[:-lag], frame[lag:]) /
                         max(1e-12, np.linalg.norm(frame[:-lag]) * np.linalg.norm(frame[lag:]))
                         for lag in range(lo - 1, hi + 2)])
        peaks = [i for i in range(1, len(corr) - 1)
                 if corr[i] >= .6 and corr[i] > corr[i - 1] and corr[i] >= corr[i + 1]]
        if peaks:
            i = peaks[0]
            a, b, c = corr[i - 1:i + 2]
            adjust = .5 * (a - c) / (a - 2 * b + c) if abs(a - 2 * b + c) > 1e-12 else 0
            f0.append(rate / (lo - 1 + i + adjust))
    return {'f0_median': float(np.median(f0)) if f0 else None,
            'f0_range': float(max(f0) - min(f0)) if f0 else None,
            'voiced_share': len(f0) / total if total else 0.0}


def overlap_share(intervals, start, end):
    """Time with >= 2 active turns / block span; timestamp overlap only."""
    edges = defaultdict(int)
    for a, b in intervals:
        a, b = max(start, a), min(end, b)
        if b > a:
            edges[a] += 1
            edges[b] -= 1
    active, previous, overlap = 0, start, 0.0
    for at, change in sorted(edges.items()):
        if active >= 2:
            overlap += at - previous
        active += change
        previous = at
    return overlap / (end - start) if end > start else 0.0


def cluster_speakers(embeddings, threshold=.35, min_speakers=2, max_speakers=6):
    """Deterministic average-link cosine clustering, bounded to 2–6 if possible.

    None means a short/unusable turn. Letters follow first occurrence, not gender.
    """
    if not 1 <= min_speakers <= max_speakers <= 6 or not 0 <= threshold <= 2:
        raise ValueError('invalid speaker clustering settings')
    valid = [i for i, e in enumerate(embeddings) if e is not None]
    result = ['?'] * len(embeddings)
    if not valid:
        return result
    x = np.asarray([embeddings[i] for i in valid], dtype=np.float64)
    if x.ndim != 2 or not np.isfinite(x).all():
        raise ValueError('invalid speaker embedding')
    norms = np.linalg.norm(x, axis=1)
    if np.any(norms < 1e-12):
        raise ValueError('invalid speaker embedding')
    x /= norms[:, None]
    distances = np.clip(1 - x @ x.T, 0, 2)
    np.fill_diagonal(distances, np.inf)
    clusters = {i: [i] for i in range(len(valid))}
    minimum = min(min_speakers, len(valid))
    while len(clusters) > minimum:
        a, b = np.unravel_index(np.argmin(distances), distances.shape)
        distance = distances[a, b]
        if distance > threshold and len(clusters) <= max_speakers:
            break
        na, nb = len(clusters[a]), len(clusters[b])
        with np.errstate(invalid='ignore'):
            merged = (distances[a] * na + distances[b] * nb) / (na + nb)
        distances[a] = merged
        distances[:, a] = merged
        distances[b, :] = distances[:, b] = np.inf
        distances[a, a] = np.inf
        clusters[a] += clusters.pop(b)
    for label, cluster in enumerate(sorted(clusters.values(), key=min)):
        for index in cluster:
            result[valid[index]] = chr(ord('A') + label)
    return result


def decode_audio(path, start, end):
    """Seek then place resampled PCM by presentation timestamp (including gaps)."""
    import av
    count = round((end - start) * RATE)
    if start < 0 or count <= 0:
        raise ValueError('invalid audio interval')
    output = np.zeros(count, dtype=np.float32)
    covered = np.zeros(count, dtype=bool)
    with av.open(str(path)) as container:
        stream = container.streams.audio[0]
        container.seek(max(0, int(start / stream.time_base)), stream=stream, backward=True)
        # Keep channels until after resampling, then take their arithmetic mean.
        # FFmpeg's default stereo->mono matrix can add 3 dB for identical channels.
        resampler = av.AudioResampler(format='fltp', rate=RATE)

        def put(frame):
            if frame.pts is None:
                raise ValueError('audio frame has no timestamp')
            offset = round((float(frame.pts * frame.time_base) - start) * RATE)
            values = frame.to_ndarray().mean(axis=0)
            left, right = max(0, offset), min(count, offset + len(values))
            if right > left:
                output[left:right] = values[left - offset:right - offset]
                covered[left:right] = True

        for frame in container.decode(stream):
            for converted in resampler.resample(frame):
                put(converted)
            if frame.pts is not None and float(frame.pts * frame.time_base) > end + .1:
                break
        for converted in resampler.resample(None):
            put(converted)
    if not covered.any() or not covered[:min(count, 800)].any() or not covered[max(0, count - 800):].any():
        raise ValueError('requested audio interval not available')
    return output


def window_ranges(seconds):
    if seconds <= 5:
        return [(0.0, seconds)] if seconds > 0 else []
    return [(i * 2.5, min(seconds, i * 2.5 + 5)) for i in range(math.ceil((seconds - 5) / 2.5) + 1)]


def summarize_events(windows, start, end):
    selected = [w['events'] for w in windows if w['start'] < end and w['end'] > start]
    return ({key: max((row[key] for row in selected), default=0.0) for key in EVENT_LABELS},
            {key: float(np.mean([row[key] for row in selected])) if selected else 0.0 for key in EVENT_LABELS})


class ASTBackend:
    def __init__(self, cache, offline=False, device='cuda'):
        import torch
        from transformers import ASTFeatureExtractor, ASTForAudioClassification
        self.torch, self.device = torch, device
        options = dict(cache_dir=str(cache), local_files_only=offline)
        self.extractor = ASTFeatureExtractor.from_pretrained(AST_MODEL, **options)
        self.model = ASTForAudioClassification.from_pretrained(AST_MODEL, **options).to(device).eval()
        self.mapping = resolve_labels(self.model.config.id2label)

    def score(self, samples):
        inputs = self.extractor(samples, sampling_rate=RATE, return_tensors='pt')
        inputs = {k: v.to(self.device) for k, v in inputs.items()}
        with self.torch.inference_mode():
            probabilities = self.model(**inputs).logits.sigmoid()[0].cpu().numpy()
        return {key: float(max(probabilities[i] for i in labels.values())) for key, labels in self.mapping.items()}


class ECAPABackend:
    def __init__(self, cache, offline=False, device='cuda'):
        # Loading the downloaded local files directly avoids Windows symlinks,
        # remote custom.py execution, and SpeechBrain's secondary network fetches.
        import torch
        from huggingface_hub import snapshot_download
        from hyperpyyaml import load_hyperpyyaml
        # SpeechBrain 1.0.3 probes a removed torchaudio API at import time.
        # No torchaudio file I/O is used here (PyAV decodes into tensors).
        import torchaudio
        if not hasattr(torchaudio, 'list_audio_backends'):
            torchaudio.list_audio_backends = lambda: []
        from speechbrain.inference.speaker import EncoderClassifier
        snapshot = Path(snapshot_download(SPEAKER_MODEL, cache_dir=str(cache), local_files_only=offline,
                                         allow_patterns=['hyperparams.yaml', '*.ckpt']))
        with (snapshot / 'hyperparams.yaml').open(encoding='utf-8') as stream:
            hp = load_hyperpyyaml(stream)
        hp['pretrainer'].loadables = {k: v for k, v in hp['pretrainer'].loadables.items() if k != 'label_encoder'}
        # Only standard, explicitly named ECAPA checkpoint files are loaded.
        for key in hp['pretrainer'].loadables:
            checkpoint = snapshot / f'{key}.ckpt'
            if not checkpoint.is_file():
                raise ValueError('missing ECAPA checkpoint')
        hp['pretrainer'].set_collect_in(snapshot)
        hp['pretrainer'].load_collected()
        self.encoder = EncoderClassifier(modules=hp['modules'], hparams=hp, run_opts={'device': device})
        self.torch, self.device = torch, device

    def embed(self, samples):
        with self.torch.inference_mode():
            value = self.encoder.encode_batch(self.torch.from_numpy(samples.copy()).unsqueeze(0).to(self.device))
        return value.detach().cpu().numpy().reshape(-1)


class FakeEvents:
    mapping = {k: {v[0]: i} for i, (k, v) in enumerate(EVENT_LABELS.items())}

    def score(self, samples):
        return {key: 0.0 for key in EVENT_LABELS}


class FakeSpeaker:
    def embed(self, samples):
        return np.array([1.0, 0.0])


def analyze_block(block, samples, events_backend, speaker_backend, threshold=.3):
    duration = block['end'] - block['start']
    if len(samples) != round(duration * RATE):
        raise ValueError('PCM/block duration mismatch')
    windows = []
    for start, end in window_ranges(duration):
        clip = samples[round(start * RATE):round(end * RATE)]
        clip = np.pad(clip, (0, max(0, 5 * RATE - len(clip))))
        scores = events_backend.score(clip)
        if set(scores) != set(EVENT_LABELS) or any(not np.isfinite(v) or not 0 <= v <= 1 for v in scores.values()):
            raise ValueError('invalid acoustic event score')
        windows.append({'start': block['start'] + start, 'end': block['start'] + end, 'events': scores})
    turns, embeddings = [], []
    for timing in block['turn_times']:
        start, end = timing['start'], timing['end']
        clip = samples[round((start - block['start']) * RATE):round((end - block['start']) * RATE)]
        maximum, mean = summarize_events(windows, start, end)
        turns.append(dict(utt=timing['utt'], speaker='?', events=maximum, events_mean=mean,
                          rms_db=rms_db(clip), **pitch(clip)))
        embeddings.append(speaker_backend.embed(clip) if end - start >= .6 else None)
    maximum, mean = summarize_events(windows, block['start'], block['end'])
    levels = [t['rms_db'] for t in turns]
    summary = dict(events=maximum, events_mean=mean, n_speakers=0, speaker_turn_changes=0,
                   rms_std=float(np.std(levels)) if levels else 0.0,
                   rms_peak_jump=max([0.0] + [b - a for a, b in zip(levels, levels[1:])]),
                   overlap_share=overlap_share([(t['start'], t['end']) for t in block['turn_times']], block['start'], block['end']))
    return dict(id=block['id'], threshold=threshold, turns=turns, block=summary, windows=windows), embeddings


def analyze(blocks, events_backend, speaker_backend, decoder=decode_audio, threshold=.3, speaker_threshold=.35):
    results, by_source, seen, timings = [], defaultdict(list), {}, []
    for block in blocks:
        started = time.perf_counter()
        audio = block['audio']
        if not math.isclose(audio['end'] - audio['start'], block['end'] - block['start'], abs_tol=1 / RATE):
            raise ValueError('audio/block span mismatch')
        samples = decoder(audio['path'], audio['start'], audio['end'])
        record, embeddings = analyze_block(block, samples, events_backend, speaker_backend, threshold)
        results.append(record)
        for row, embedding in zip(record['turns'], embeddings):
            key = (block['source'], row['utt'])
            if key not in seen:
                seen[key] = len(by_source[block['source']])
                by_source[block['source']].append((key, embedding))
        timings.append({'id': block['id'], 'seconds': time.perf_counter() - started})
    cluster_started = time.perf_counter()
    speakers = {}
    for group in by_source.values():
        labels = cluster_speakers([embedding for _, embedding in group], speaker_threshold)
        speakers.update((key, label) for (key, _), label in zip(group, labels))
    for block, record in zip(blocks, results):
        for turn in record['turns']:
            turn['speaker'] = speakers[(block['source'], turn['utt'])]
        labels = [t['speaker'] for t in record['turns']]
        record['block']['n_speakers'] = len(set(labels) - {'?'})
        record['block']['speaker_turn_changes'] = sum(a != b and '?' not in (a, b) for a, b in zip(labels, labels[1:]))
    cluster_seconds = time.perf_counter() - cluster_started
    for row in timings:
        row['seconds'] += cluster_seconds / len(timings)
    return results, timings, cluster_seconds


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sample', default='.local/demo/sample.jsonl')
    parser.add_argument('--out', default='.local/demo/acoustic.jsonl')
    parser.add_argument('--utterances', default='.local/utterances')
    parser.add_argument('--cache', default='.local/hf')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--threshold', type=float, default=.3)
    parser.add_argument('--speaker-threshold', type=float, default=.35)
    parser.add_argument('--device', default='cuda')
    parser.add_argument('--backend', choices=['local', 'fake'], default='local')
    args = parser.parse_args(argv)
    if not 0 < args.threshold <= 1 or not 0 <= args.speaker_threshold <= 2:
        parser.error('threshold outside allowed range')
    try:
        blocks = load_blocks(args.sample, args.utterances)
        started = time.perf_counter()
        torch = None
        if args.backend == 'fake':
            events, speaker = FakeEvents(), FakeSpeaker()
        else:
            import torch
            if args.device.startswith('cuda'):
                torch.cuda.reset_peak_memory_stats(args.device)
            events = ASTBackend(args.cache, args.offline, args.device)
            speaker = ECAPABackend(args.cache, args.offline, args.device)
        loading_seconds = time.perf_counter() - started
        results, timings, clustering = analyze(blocks, events, speaker, threshold=args.threshold, speaker_threshold=args.speaker_threshold)
        atomic_text(Path(args.out), jsonl(results))
        memory = torch.cuda.max_memory_allocated(args.device) if torch and args.device.startswith('cuda') else 0
        write_json(Path(args.out).with_suffix('.stats.json'), dict(
            backend=args.backend, models=[AST_MODEL, SPEAKER_MODEL], sample_rate=RATE,
            event_label_mapping=events.mapping, unmatched_aliases={k: [v for v in names if v not in events.mapping[k]] for k, names in EVENT_LABELS.items()},
            threshold=args.threshold, speaker_threshold=args.speaker_threshold,
            window_seconds=5, hop_seconds=2.5, loading_seconds=loading_seconds,
            clustering_seconds=clustering, gpu_peak_allocated_bytes=memory, blocks=timings))
    except Exception as error:
        # Third-party exceptions can contain paths, ASR input, or model output.
        print(f'acoustic failed ({type(error).__name__}); check local models, dependencies and input contracts')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
