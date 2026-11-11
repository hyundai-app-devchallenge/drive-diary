"""Small AudioSet taggers from the EfficientAT checkout (external code, MIT).

The checkout in .local/sound/EfficientAT is imported, never modified. Weights are
read from local files with weights_only=True; the repository's download path
(pretrained_name) is never used. Running this module executes external code, so
it is only started by a person, not by automated tooling.
"""
import contextlib
import csv
import os
import sys
import time
from pathlib import Path

import numpy as np

from .core import RATE, SoundModel

MODELS = {'mn04': (0.4, 'mn04_as_mAP_432.pt'), 'mn10': (1.0, 'mn10_as_mAP_471.pt')}
MODEL_RATE = 32000
PAD_SECONDS = 10


@contextlib.contextmanager
def _inside(repo):
    """The checkout reads 'metadata/...' relative to cwd at import time."""
    previous = os.getcwd()
    sys.path.insert(0, str(repo))
    os.chdir(repo)
    try:
        yield
    finally:
        os.chdir(previous)
        sys.path.remove(str(repo))


class EfficientATModel(SoundModel):
    def __init__(self, root, name, device=None):
        import torch
        if name not in MODELS:
            raise ValueError('unknown EfficientAT model')
        width, filename = MODELS[name]
        base = Path(root) / '.local/sound'
        repo, weights = base / 'EfficientAT', base / 'weights' / filename
        if not (repo / 'models/mn/model.py').is_file() or not weights.is_file():
            raise FileNotFoundError('EfficientAT checkout or weights missing under .local/sound')
        with _inside(repo):
            from models.mn.model import get_model
            from models.preprocess import AugmentMelSTFT
            # pretrained_name=None: no download. Defaults match the released *_as weights.
            self.model = get_model(width_mult=width, pretrained_name=None)
            self.mel = AugmentMelSTFT(n_mels=128, sr=MODEL_RATE, win_length=800, hopsize=320)
        state = torch.load(weights, map_location='cpu', weights_only=True)
        self.model.load_state_dict(state)
        with open(repo / 'metadata/class_labels_indices.csv', encoding='utf-8') as handle:
            self.labels = tuple(row[2] for row in list(csv.reader(handle))[1:])
        if len(self.labels) != 527 or len(set(self.labels)) != 527:
            raise ValueError('unexpected AudioSet label list')
        self.torch, self.name = torch, name
        self.device = device or ('cuda' if torch.cuda.is_available() else 'cpu')
        self.model.to(self.device).eval()
        self.mel.to(self.device).eval()

    def score_window(self, waveform, rate):
        import torchaudio
        torch = self.torch
        if rate != RATE:
            raise ValueError('expected 16000 Hz input')
        x = torch.from_numpy(np.asarray(waveform, dtype=np.float32))[None]
        x = torchaudio.functional.resample(x, rate, MODEL_RATE)
        seconds = x.shape[1] / MODEL_RATE
        # mn10 saturates (all labels 1.0) on inputs under ~2 s. Pad with silence to the
        # 10 s AudioSet length, as AST's extractor does, so both models see the same input.
        if x.shape[1] < PAD_SECONDS * MODEL_RATE:
            x = torch.nn.functional.pad(x, (0, PAD_SECONDS * MODEL_RATE - x.shape[1]))
        with torch.inference_mode():
            spec = self.mel(x.to(self.device))
            logits, _ = self.model(spec.unsqueeze(0))
            # Fail loudly instead of scoring a saturated row (every label near 1.0).
            if not bool(torch.isfinite(logits).all()) or float(logits.min()) > 15:
                raise RuntimeError(f'{self.name}: saturated logits on {self.device} '
                                   f'(input {seconds:.2f}s)')
            probabilities = logits.float().sigmoid().reshape(-1).cpu().numpy()
        return {name: float(probabilities[i]) for i, name in enumerate(self.labels)}

    def benchmark(self, repeats=5):
        """One CPU thread, one warmup, resample + mel + inference, 10 s input."""
        torch = self.torch
        threads, device = torch.get_num_threads(), self.device
        waveform = (.05 * np.sin(2 * np.pi * 220 * np.arange(10 * RATE) / RATE)).astype(np.float32)
        try:
            torch.set_num_threads(1)
            self.model.to('cpu')
            self.mel.to('cpu')
            self.device = 'cpu'
            self.score_window(waveform, RATE)
            times = []
            for _ in range(repeats):
                start = time.perf_counter()
                self.score_window(waveform, RATE)
                times.append((time.perf_counter() - start) * 1000)
            return {'parameters': sum(p.numel() for p in self.model.parameters()),
                    'parameter_bytes': sum(p.numel() * p.element_size() for p in self.model.parameters()),
                    'cpu_threads': 1, 'warmups': 1, 'repeats': repeats,
                    'window_seconds': 10, 'includes_feature_extraction': True,
                    'cpu_ms_median': float(np.median(times)), 'cpu_ms_runs': times}
        finally:
            self.model.to(device)
            self.mel.to(device)
            self.device = device
            torch.set_num_threads(threads)
