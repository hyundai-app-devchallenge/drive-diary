"""S1 shared full encoder and four heads; DESIGN.md §6, §9."""

import math

import torch
from torch import nn
from torch.nn import functional as F

from .data import EXCLUDES, KINDS


class Student(nn.Module):
    def __init__(self, encoder, dropout=0.1):
        super().__init__()
        self.encoder = encoder
        self.dropout = nn.Dropout(dropout)
        hidden = encoder.config.hidden_size
        self.heads = nn.ModuleDict({
            "binary": nn.Linear(hidden, 1), "ordinal": nn.Linear(hidden, 1),
            "kind": nn.Linear(hidden, len(KINDS)), "exclude": nn.Linear(hidden, len(EXCLUDES)),
        })

    def forward(self, input_ids, attention_mask, token_type_ids):
        hidden = self.encoder(input_ids=input_ids, attention_mask=attention_mask,
                              token_type_ids=token_type_ids).last_hidden_state[:, 0]
        return {name: head(self.dropout(hidden)) for name, head in self.heads.items()}


def flag_pos_weights(rows, cap=20.0):
    """Training-only neg/pos counts, capped; absent flags get weight 1."""
    result = {}
    for field, names in (("kind", KINDS), ("exclude", EXCLUDES)):
        counts = [sum(name in r["label"][field] for r in rows) for name in names]
        result[field] = torch.tensor([min(cap, max(1.0, (len(rows) - p) / p)) if p else 1.0
                                      for p in counts], dtype=torch.float32)
    return result


def assemble_loss(logits, targets, sample_weights, task_weights, pos_weights):
    """Per-example head means then weighted mean; ordinal BCE target=story/3.

    The primary binary head follows the T11 request / DESIGN.md §4. A separate
    scalar ordinal auxiliary preserves DESIGN.md §9.2's soft BCE target.
    Exclusions never mask or alter story supervision (§4).
    """
    losses = {}
    for name, values in logits.items():
        loss = F.binary_cross_entropy_with_logits(
            values.float(), targets[name].float(), reduction="none",
            pos_weight=pos_weights.get(name))
        per_row = loss.reshape(len(sample_weights), -1).mean(dim=1)
        losses[name] = (per_row * sample_weights).sum() / sample_weights.sum()
    return sum(task_weights[name] * loss for name, loss in losses.items()), losses


def fit_temperature(logits, labels, max_iter=80):
    """Unweighted dev NLL minimization; no decision threshold tuning (§9.5).

    Bound T to [0.05, 20]. Degenerate dev labels keep identity calibration.
    Return numeric diagnostics, never text. Double precision is CPU-only here.
    """
    x = torch.as_tensor(logits, dtype=torch.float64).flatten().detach().cpu()
    y = torch.as_tensor(labels, dtype=torch.float64).flatten().detach().cpu()
    if len(x) != len(y) or not torch.isfinite(x).all() or not torch.isfinite(y).all():
        raise ValueError("temperature inputs must have equal length and finite values")
    if not torch.all((y == 0) | (y == 1)):
        raise ValueError("temperature labels must be binary")
    if len(x) == 0 or len(torch.unique(y)) < 2:
        return dict(temperature=1.0, reason="empty_or_single_class_dev", n=len(x))
    log_t = torch.zeros((), dtype=torch.float64, requires_grad=True)
    optimizer = torch.optim.LBFGS([log_t], lr=0.25, max_iter=max_iter,
                                 tolerance_grad=1e-10, line_search_fn="strong_wolfe")

    def closure():
        optimizer.zero_grad()
        loss = F.binary_cross_entropy_with_logits(x / log_t.clamp(math.log(0.05), math.log(20)).exp(), y)
        loss.backward()
        return loss

    before = float(F.binary_cross_entropy_with_logits(x, y))
    optimizer.step(closure)
    temperature = float(log_t.detach().clamp(math.log(0.05), math.log(20)).exp())
    after = float(F.binary_cross_entropy_with_logits(x / temperature, y))
    if after > before:
        temperature, after = 1.0, before
    return dict(temperature=temperature, n=len(x), nll_before=before, nll_after=after,
                reason="dev_nll")
