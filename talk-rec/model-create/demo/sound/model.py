"""AST only, using the project's existing offline loader and feature extractor."""
import time

import numpy as np

from demo.acoustic import ASTBackend as ExistingASTBackend
from .core import RATE, SoundModel


class ASTModel(SoundModel):
    def __init__(self, cache, device=None):
        import torch
        self.device = device or ('cuda' if torch.cuda.is_available() else 'cpu')
        self.backend = ExistingASTBackend(cache, offline=True, device=self.device)
        self.id2label = {int(i): name for i, name in self.backend.model.config.id2label.items()}
        self.labels = tuple(self.id2label.values())

    def score_window(self, waveform, rate):
        if rate != RATE:
            raise ValueError('AST requires 16000 Hz; decode with demo.acoustic.decode_audio')
        b = self.backend
        inputs = b.extractor(waveform, sampling_rate=rate, return_tensors='pt')
        inputs = {key: value.to(b.device) for key, value in inputs.items()}
        with b.torch.inference_mode():
            probabilities = b.model(**inputs).logits.sigmoid()[0].cpu().numpy()
        return {name: float(probabilities[i]) for i, name in self.id2label.items()}

    def benchmark(self, repeats=5):
        """One CPU thread, one warmup, extraction + inference, 10 s input."""
        b = self.backend
        previous_threads = b.torch.get_num_threads()
        previous_device = b.device
        waveform = (.05 * np.sin(2 * np.pi * 220 * np.arange(10 * RATE) / RATE)).astype(np.float32)
        try:
            b.torch.set_num_threads(1)
            b.model.to('cpu')
            b.device = 'cpu'
            self.score_window(waveform, RATE)
            times = []
            for _ in range(repeats):
                start = time.perf_counter()
                self.score_window(waveform, RATE)
                times.append((time.perf_counter() - start) * 1000)
            return {'parameters': sum(p.numel() for p in b.model.parameters()),
                    'parameter_bytes': sum(p.numel() * p.element_size() for p in b.model.parameters()),
                    'cpu_threads': 1, 'warmups': 1, 'repeats': repeats,
                    'window_seconds': 10, 'includes_feature_extraction': True,
                    'cpu_ms_median': float(np.median(times)), 'cpu_ms_runs': times}
        finally:
            b.model.to(previous_device)
            b.device = previous_device
            b.torch.set_num_threads(previous_threads)
