"""Offline, safetensors-only M1 and the trial's independent M0 heads."""
import json
import os
from pathlib import Path
import pickle

import numpy as np

from demo.trial.models import (EncoderModel as TrialEncoder, LinearModel,
                               block_model, load_tokenizer, token_batch)

MODEL = 'monologg/koelectra-small-v3-discriminator'


def offline_environment():
    for key in ('OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'NUMEXPR_NUM_THREADS'):
        os.environ[key] = '1'
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1',
                      TOKENIZERS_PARALLELISM='false', CUBLAS_WORKSPACE_CONFIG=':4096:8')


def safe_cache(cache):
    """Use an already cached safe revision; never convert .bin or download.

    HF may cache the upstream main config/tokenizer separately from the
    safetensors conversion revision. AutoModel receives the explicit safe
    directory and the cached upstream config; there is no unsafe fallback.
    """
    base = Path(cache) / ('models--' + MODEL.replace('/', '--'))
    main = (base / 'refs/main').read_text(encoding='utf-8').strip()
    config = base / 'snapshots' / main
    if not (config / 'config.json').is_file() or not (config / 'vocab.txt').is_file():
        raise FileNotFoundError('offline model configuration missing')
    candidates = sorted((base / 'snapshots').glob('*/model.safetensors'))
    if not candidates:
        raise FileNotFoundError('offline safetensors missing')
    safe = next((p for p in candidates if p.parent == config), candidates[0])
    # glob never includes HF .no_exist markers.
    return config.resolve(), safe.parent.resolve()


class EncoderModel(TrialEncoder):
    def __init__(self, seed, cache, max_length=256, truncation='both', device='cuda'):
        offline_environment()
        import torch
        from transformers import AutoConfig, AutoModel, set_seed
        config_path, weight_path = safe_cache(cache)
        set_seed(seed)
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)
        torch.backends.cudnn.benchmark = False
        torch.backends.cudnn.deterministic = True
        torch.backends.cuda.matmul.allow_tf32 = False
        torch.backends.cudnn.allow_tf32 = False
        if device == 'cuda' and not torch.cuda.is_available():
            raise RuntimeError('CUDA unavailable')
        options = dict(local_files_only=True, trust_remote_code=False)
        self.tokenizer = load_tokenizer(str(config_path), cache)
        config = AutoConfig.from_pretrained(config_path, **options)
        encoder = AutoModel.from_pretrained(weight_path, config=config, use_safetensors=True, **options)
        self.torch, self.seed = torch, seed
        self.model = block_model(encoder).to(device)
        self.device, self.max_length, self.truncation = device, max_length, truncation
        self.cache_revisions = dict(config=config_path.name, safetensors=weight_path.name)

    def fit(self, texts, labels, epochs=3, learning_rate=3e-5, batch_size=8):
        # Cache synthetic tokenization only in memory; optimization is unchanged.
        import time
        from demo.trial.models import train_step
        torch = self.torch
        encoded = token_batch(self.tokenizer, texts, self.max_length, self.truncation)
        targets = torch.tensor(labels)
        optimizer = torch.optim.AdamW(self.model.parameters(), lr=learning_rate, weight_decay=.01)
        generator = np.random.default_rng(self.seed)
        for epoch in range(epochs):
            start = time.perf_counter()
            order = generator.permutation(len(texts))
            losses = []
            for offset in range(0, len(order), batch_size):
                ix = torch.tensor(order[offset:offset + batch_size])
                inputs = {k: v[ix].to(self.device) for k, v in encoded.items()}
                losses.append(train_step(self.model, inputs, targets[ix].to(self.device), optimizer, .2))
            print(f'epoch={epoch + 1} loss={np.mean(losses):.6f} seconds={time.perf_counter()-start:.1f}', flush=True)
        del optimizer

    def save(self, directory):
        from safetensors.torch import save_file
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        save_file({k: v.detach().cpu().contiguous() for k, v in self.model.state_dict().items()},
                  str(directory / 'model.safetensors'))
        self.model.encoder.config.save_pretrained(directory)
        self.tokenizer.save_pretrained(directory)
        (directory / 'architecture.json').write_text(json.dumps(dict(
            model=MODEL, seed=self.seed, max_length=self.max_length, truncation=self.truncation,
            aux_weight=.2, labels=['none', 'story', 'moment'], cache_revisions=self.cache_revisions), indent=2), encoding='utf-8')


def load_saved(directory, name, device='cpu'):
    """Load locally produced artifacts. Test evaluation never fits anything."""
    directory = Path(directory)
    if name == 'M0':
        with (directory / 'model.pkl').open('rb') as stream:
            return pickle.load(stream)
    offline_environment()
    import torch
    from safetensors.torch import load_file
    from transformers import AutoConfig, AutoModel
    settings = json.loads((directory / 'architecture.json').read_text(encoding='utf-8'))
    obj = EncoderModel.__new__(EncoderModel)
    obj.torch = torch
    obj.tokenizer = load_tokenizer(str(directory), directory)
    config = AutoConfig.from_pretrained(directory, local_files_only=True, trust_remote_code=False)
    obj.model = block_model(AutoModel.from_config(config, trust_remote_code=False))
    obj.model.load_state_dict(load_file(str(directory / 'model.safetensors')), strict=True)
    obj.model.to(device).eval()
    obj.device, obj.seed = device, settings['seed']
    obj.max_length, obj.truncation = settings['max_length'], settings['truncation']
    obj.cache_revisions = settings['cache_revisions']
    return obj


def benchmark(model, repeats=15):
    # Same invented input, padding, warmup and median as demo/trial.
    from threadpoolctl import threadpool_limits
    from demo.trial.models import benchmark as trial_benchmark
    with threadpool_limits(limits=1):
        return trial_benchmark(model, repeats)
