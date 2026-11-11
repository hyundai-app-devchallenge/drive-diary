"""Small block classifiers. Transformers are imported only for encoder trials."""
import io
import pickle
from pathlib import Path
import time

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.preprocessing import StandardScaler


def shape(texts):
    rows = []
    for text in texts:
        lengths = [len(line) for line in text.split("\n")]
        rows.append([len(lengths), sum(lengths), np.median(lengths), np.mean(lengths),
                     np.std(lengths), min(lengths), max(lengths)])
    return np.asarray(rows, dtype=np.float64)


class LinearModel:
    def __init__(self, name, seed):
        self.name = name
        self.features = (TfidfVectorizer(analyzer="char", ngram_range=(1, 3), sublinear_tf=True,
                                         max_features=100000) if name == "ngram" else StandardScaler())
        self.binary = LogisticRegression(max_iter=2000, random_state=seed)
        self.aux = LogisticRegression(max_iter=2000, random_state=seed)

    def transform(self, texts, fit=False):
        values = texts if self.name == "ngram" else shape(texts)
        return self.features.fit_transform(values) if fit else self.features.transform(values)

    def fit(self, texts, labels):
        x = self.transform(texts, fit=True)
        self.binary.fit(x, np.asarray(labels) != 0)
        self.aux.fit(x, labels)

    def predict(self, texts):
        x = self.transform(texts)
        return self.binary.predict_proba(x)[:, 1].tolist(), self.aux.predict(x).tolist()

    def parameters(self):
        return int(sum(m.coef_.size + m.intercept_.size for m in (self.binary, self.aux)))

    def storage_bytes(self):
        return len(pickle.dumps(self, protocol=5))

    def save(self, directory):
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "model.pkl").write_bytes(pickle.dumps(self, protocol=5))


def token_batch(tokenizer, texts, max_length=256, truncation="both"):
    """Preserve tokenizer-defined special tokens while truncating content tokens.

    Use the serialized fast-tokenizer postprocessor too: the generic fast class
    does not implement build_inputs_with_special_tokens like BertTokenizer does.
    """
    import torch
    rows = []
    for text in texts:
        encoded = tokenizer(text, add_special_tokens=True, truncation=False,
                            return_special_tokens_mask=True, verbose=False)
        mask = encoded["special_tokens_mask"]
        content = [i for i, special in enumerate(mask) if not special]
        budget = max_length - sum(mask)
        if budget < 2:
            raise ValueError("token budget too small")
        if len(content) > budget:
            if truncation == "head":
                content = content[:budget]
            elif truncation == "tail":
                content = content[-budget:]
            else:
                left = (budget + 1) // 2
                content = content[:left] + content[-(budget - left):]
        positions = set(content) | {i for i, special in enumerate(mask) if special}
        ids = [value for i, value in enumerate(encoded["input_ids"]) if i in positions]
        rows.append(dict(input_ids=ids, attention_mask=[1] * len(ids)))
    result = tokenizer.pad(rows, padding="max_length", max_length=max_length, return_tensors="pt")
    # All examples are one segment. BERT/Electra default token types to zero;
    # ModernBERT does not accept token_type_ids.
    return {k: v for k, v in result.items() if k in ("input_ids", "attention_mask") and isinstance(v, torch.Tensor)}


def load_tokenizer(name, cache):
    from transformers import AutoTokenizer, PreTrainedTokenizerFast
    options = dict(cache_dir=str(cache), local_files_only=True, trust_remote_code=False)
    try:
        return AutoTokenizer.from_pretrained(name, **options)
    except ValueError as error:
        # Transformers 5 exports this generic class name; 4.57 can read the
        # same tokenizer.json via its built-in fast backend, without remote code.
        if "Tokenizer class TokenizersBackend does not exist" not in str(error):
            raise
        return PreTrainedTokenizerFast.from_pretrained(name, **options)


def compatible_config(config):
    """Translate the ModernBERT v5 RoPE fields consumed by the v4 implementation."""
    if config.model_type == "modernbert":
        parameters = getattr(config, "rope_parameters", None)
        if parameters and hasattr(config, "global_rope_theta"):
            full, local = parameters["full_attention"], parameters["sliding_attention"]
            if full.get("rope_type", "default") != "default" or local.get("rope_type", "default") != "default":
                raise ValueError("unsupported ModernBERT rotary scaling on this transformers version")
            config.global_rope_theta = full["rope_theta"]
            config.local_rope_theta = local["rope_theta"]
            layer_types = getattr(config, "layer_types", None)
            expected = ["full_attention" if i % config.global_attn_every_n_layers == 0 else "sliding_attention"
                        for i in range(config.num_hidden_layers)]
            if layer_types is not None and layer_types != expected:
                raise ValueError("unsupported ModernBERT attention layout on this transformers version")
        # Avoid optional compilation/FlashAttention dependencies in this small trial.
        config.reference_compile = False
    return config


def block_model(encoder):
    """Independent binary + auxiliary three-class heads, unlike utterance T11."""
    import torch
    from torch import nn
    class BlockModel(nn.Module):
        def __init__(self):
            super().__init__()
            self.encoder = encoder
            self.dropout = nn.Dropout(.1)
            self.binary = nn.Linear(encoder.config.hidden_size, 1)
            self.aux = nn.Linear(encoder.config.hidden_size, 3)

        def forward(self, **inputs):
            hidden = self.dropout(self.encoder(**inputs, return_dict=True).last_hidden_state[:, 0])
            return self.binary(hidden).squeeze(-1), self.aux(hidden)
    return BlockModel()


def train_step(model, inputs, labels, optimizer, aux_weight=.2):
    import torch
    model.train()
    optimizer.zero_grad(set_to_none=True)
    binary, aux = model(**inputs)
    loss = (torch.nn.functional.binary_cross_entropy_with_logits(binary, (labels != 0).float())
            + aux_weight * torch.nn.functional.cross_entropy(aux, labels))
    if not torch.isfinite(loss):
        raise ValueError("nonfinite training loss")
    loss.backward()
    torch.nn.utils.clip_grad_norm_(model.parameters(), 1.)
    optimizer.step()
    return float(loss.detach())


class ByteCounter(io.RawIOBase):
    """Measure torch archive bytes without keeping a second copy or writing weights."""
    def __init__(self):
        self.size = 0

    def write(self, data):
        self.size += len(data)
        return len(data)

    def flush(self):
        pass


class EncoderModel:
    def __init__(self, name, seed, cache, max_length, truncation, device):
        import torch
        from transformers import AutoConfig, AutoModel, set_seed
        set_seed(seed)
        torch.set_num_threads(1)
        self.torch = torch
        self.tokenizer = load_tokenizer(name, cache)
        config = compatible_config(AutoConfig.from_pretrained(name, cache_dir=str(cache),
                                   local_files_only=True, trust_remote_code=False))
        extra = {"attn_implementation": "sdpa"} if config.model_type == "modernbert" else {}
        encoder = AutoModel.from_pretrained(name, cache_dir=str(cache), local_files_only=True,
                                           trust_remote_code=False, config=config, **extra)
        self.model = block_model(encoder)
        self.max_length, self.truncation = max_length, truncation
        self.device = ("cuda" if torch.cuda.is_available() else "cpu") if device == "auto" else device
        self.model.to(self.device)
        self.seed = seed

    def encode(self, texts):
        return {k: v.to(self.device) for k, v in token_batch(
            self.tokenizer, texts, self.max_length, self.truncation).items()}

    def fit(self, texts, labels, epochs=3, learning_rate=3e-5, batch_size=8):
        torch = self.torch
        optimizer = torch.optim.AdamW(self.model.parameters(), lr=learning_rate, weight_decay=.01)
        generator = np.random.default_rng(self.seed)
        for epoch in range(epochs):
            order = generator.permutation(len(texts))
            losses = []
            for offset in range(0, len(order), batch_size):
                ix = order[offset:offset + batch_size]
                inputs = self.encode([texts[i] for i in ix])
                target = torch.tensor([labels[i] for i in ix], device=self.device)
                losses.append(train_step(self.model, inputs, target, optimizer))
            print(f"epoch={epoch + 1} loss={np.mean(losses):.6f}", flush=True)
        del optimizer

    def predict(self, texts, batch_size=16):
        probability, pred3 = [], []
        self.model.eval()
        with self.torch.inference_mode():
            for offset in range(0, len(texts), batch_size):
                binary, aux = self.model(**self.encode(texts[offset:offset + batch_size]))
                probability.extend(binary.sigmoid().cpu().tolist())
                pred3.extend(aux.argmax(-1).cpu().tolist())
        return probability, pred3

    def cpu(self):
        self.model.to("cpu").eval()
        self.device = "cpu"

    def parameters(self):
        return sum(p.numel() for p in self.model.parameters())

    def storage_bytes(self):
        counter = ByteCounter()
        self.torch.save(self.model.state_dict(), counter)
        # Deployment also needs tokenizer and config; report those separately in docs.
        return counter.size

    def save(self, directory):
        directory.mkdir(parents=True, exist_ok=True)
        self.torch.save(self.model.state_dict(), directory / "model.pt")
        self.model.encoder.config.save_pretrained(directory)
        self.tokenizer.save_pretrained(directory)


def benchmark(model, repeats=15):
    # Invented content only. Encoders pad to exactly max_length (default 256).
    text = ("오늘 바람이 시원해\n다음에 같이 산책 가자\n" * 40)
    if isinstance(model, EncoderModel):
        model.cpu()
    for _ in range(3):
        model.predict([text])
    times = []
    for _ in range(repeats):
        start = time.perf_counter()
        model.predict([text])
        times.append((time.perf_counter() - start) * 1000)
    return float(np.median(times))


def peak_rss_mb():
    import os
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes
        class Counters(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
                (n, ctypes.c_size_t) for n in ("PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage",
                "PagefileUsage", "PeakPagefileUsage")]
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.GetCurrentProcess.restype = wintypes.HANDLE
        psapi = ctypes.WinDLL("psapi", use_last_error=True)
        psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
        counters = Counters()
        counters.cb = ctypes.sizeof(counters)
        if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(counters), counters.cb):
            return None
        return counters.PeakWorkingSetSize / 1_000_000
    import resource
    import sys
    size = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return size / (1_000_000 if sys.platform == "darwin" else 1000)
