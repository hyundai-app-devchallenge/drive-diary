"""Deterministic full fine-tuning, with modest GPU batches (DESIGN.md §9)."""

import os
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

import random
import time

import numpy as np
import torch
from torch.utils.data import DataLoader, Dataset, WeightedRandomSampler
from transformers import AutoConfig, AutoModel, AutoTokenizer, get_linear_schedule_with_warmup

from eval.metrics import binary_metrics
from .data import EXCLUDES, KINDS, build_input, drop_context, write_json
from .model import Student, assemble_loss, fit_temperature, flag_pos_weights


def seed_everything(seed, threads=1):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    torch.set_num_threads(threads)
    torch.use_deterministic_algorithms(True)
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False


class Examples(Dataset):
    def __init__(self, rows, tokenizer, config, training=False):
        self.rows, self.tokenizer, self.config = rows, tokenizer, config
        self.training, self.epoch = training, 0

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, index):
        r = self.rows[index]
        w, label = r["window"], r["label"]
        context = w["context"][-self.config["context_turns"]:] if self.config["context_turns"] else []
        if self.training and self.config["context_dropout"]:
            context = drop_context(context, self.config["seed"], self.epoch, index)
        item = build_input(context, w["current"], self.tokenizer, self.config["max_length"])
        targets = {"binary": [float(label["story"] >= 2)], "ordinal": [label["story"] / 3],
                   "kind": [float(k in label["kind"]) for k in KINDS],
                   "exclude": [float(k in label["exclude"]) for k in EXCLUDES]}
        return item, targets, r["weight"]


def collator(tokenizer, length):
    def collate(rows):
        # Fixed padding also supplies the tensor contract for future T15 export.
        inputs = tokenizer.pad([r[0] for r in rows], padding="max_length", max_length=length,
                               return_tensors="pt")
        targets = {k: torch.tensor([r[1][k] for r in rows], dtype=torch.float32) for k in rows[0][1]}
        return dict(inputs), targets, torch.tensor([r[2] for r in rows], dtype=torch.float32)
    return collate


@torch.no_grad()
def infer(model, loader, device):
    model.eval()
    values = {name: [] for name in model.heads}
    # fp32 evaluation avoids fp16 saturation/ties in primary ranking scores.
    for inputs, _, _ in loader:
        logits = model(**{k: v.to(device) for k, v in inputs.items()})
        for k, v in logits.items():
            values[k].append(v.detach().float().cpu().numpy())
    return {k: np.concatenate(v) if v else np.empty((0, 1 if k in ("binary", "ordinal") else 5))
            for k, v in values.items()}


def probabilities(logits, temperature=1.0):
    result = {}
    for name, x in logits.items():
        x = x.astype(np.float64) / (temperature if name == "binary" else 1.0)
        result[name] = 1 / (1 + np.exp(-np.clip(x, -60, 60)))
    return result


def train_s1(rows, dev, splits, config, output):
    seed_everything(config["seed"], config["cpu_threads"])
    if config["device"] == "cuda" and not torch.cuda.is_available():
        raise ValueError("CUDA requested but unavailable")
    device = torch.device(config["device"])
    tokenizer = AutoTokenizer.from_pretrained(config["encoder"], cache_dir=config["hf_cache"],
                                              revision=config["revision"])
    encoder = AutoModel.from_pretrained(config["encoder"], cache_dir=config["hf_cache"],
                                       revision=config["revision"], attn_implementation="eager")
    model = Student(encoder, config["head_dropout"]).to(device)
    data = Examples(rows, tokenizer, config, True)
    collate = collator(tokenizer, config["max_length"])
    generator = torch.Generator().manual_seed(config["seed"])
    sampler = None
    if config["balanced_sampling"]:
        ys = [int(r["label"]["story"] >= 2) for r in rows]
        counts = [ys.count(0), ys.count(1)]
        sampler = WeightedRandomSampler([1 / counts[y] for y in ys], len(rows),
                                         replacement=True, generator=generator)
    loader = DataLoader(data, batch_size=config["batch_size"], shuffle=sampler is None,
                        sampler=sampler, generator=generator, collate_fn=collate, num_workers=0)
    devloader = DataLoader(Examples(dev, tokenizer, config), batch_size=config["eval_batch_size"],
                           collate_fn=collate, num_workers=0)
    decay, no_decay = [], []
    for name, parameter in model.named_parameters():
        (no_decay if name.endswith("bias") or "LayerNorm.weight" in name else decay).append(parameter)
    optimizer = torch.optim.AdamW([{"params": decay, "weight_decay": config["weight_decay"]},
                                    {"params": no_decay, "weight_decay": 0.0}], lr=config["learning_rate"])
    total_steps = len(loader) * config["epochs"]
    scheduler = get_linear_schedule_with_warmup(optimizer, int(total_steps * config["warmup_ratio"]), total_steps)
    amp = config["mixed_precision"] and device.type == "cuda"
    scaler = torch.amp.GradScaler("cuda", enabled=amp)
    pos_weights = {k: v.to(device) for k, v in flag_pos_weights(rows, config["pos_weight_cap"]).items()}
    best, stale, history, best_epoch = -float("inf"), 0, [], None
    start = time.perf_counter()
    gold = [int(r["label"]["story"] >= 2) for r in dev]
    for epoch in range(config["epochs"]):
        data.epoch = epoch
        model.train()
        losses = []
        for inputs, targets, weights in loader:
            optimizer.zero_grad(set_to_none=True)
            inputs = {k: v.to(device) for k, v in inputs.items()}
            targets = {k: v.to(device) for k, v in targets.items()}
            with torch.autocast(device_type=device.type, dtype=torch.float16, enabled=amp):
                logits = model(**inputs)
                loss, _ = assemble_loss(logits, targets, weights.to(device), config["loss_weights"], pos_weights)
            if not torch.isfinite(loss):
                raise ValueError("non-finite training loss")
            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(model.parameters(), config["gradient_clip"])
            previous_scale = scaler.get_scale()
            scaler.step(optimizer)
            scaler.update()
            if scaler.get_scale() >= previous_scale:
                scheduler.step()
            losses.append(float(loss.detach()))
        logits = infer(model, devloader, device)
        ap = binary_metrics(gold, probabilities(logits)["binary"].ravel().tolist())["pr_auc"]
        # AP is undefined with zero positives: explicit dev-NLL fallback, reported.
        dev_nll = float(torch.nn.functional.binary_cross_entropy_with_logits(
            torch.tensor(logits["binary"].ravel()), torch.tensor(gold, dtype=torch.float32)))
        criterion = ap if ap is not None else -dev_nll
        entry = dict(epoch=epoch + 1, train_loss=float(np.mean(losses)), dev_pr_auc=ap,
                     dev_nll=dev_nll, elapsed_seconds=time.perf_counter() - start,
                     amp_scale=scaler.get_scale())
        history.append(entry)
        print(f"epoch={epoch+1} loss={entry['train_loss']:.6f} dev_ap={ap} seconds={entry['elapsed_seconds']:.1f}", flush=True)
        if criterion > best + config["min_delta"]:
            best, stale, best_epoch = criterion, 0, epoch + 1
            torch.save(model.state_dict(), output / "model.pt")
        else:
            stale += 1
        write_json(output / "history.json", history)
        if stale >= config["patience"]:
            break
    training_seconds = time.perf_counter() - start
    model.load_state_dict(torch.load(output / "model.pt", map_location=device, weights_only=True))
    dev_logits = infer(model, devloader, device)
    calibration = fit_temperature(dev_logits["binary"].ravel(), gold)
    model.encoder.config.save_pretrained(output)
    tokenizer.save_pretrained(output)
    write_json(output / "temperature.json", calibration)
    write_json(output / "student.json", dict(head_dropout=config["head_dropout"], kinds=KINDS,
               excludes=EXCLUDES, ordinal="sigmoid * 3", primary="sigmoid(logit / temperature)",
               context_turns=config["context_turns"], max_length=config["max_length"],
               normalization="common.normalize.normalize", truncation="pipeline.windows.truncate_tokens"))
    predictions = {}
    for split, examples in splits.items():
        dl = DataLoader(Examples(examples, tokenizer, config), batch_size=config["eval_batch_size"], collate_fn=collate)
        predictions[split] = probabilities(infer(model, dl, device), calibration["temperature"])
    info = dict(training_seconds=training_seconds, parameters=sum(p.numel() for p in model.parameters()),
                trainable_parameters=sum(p.numel() for p in model.parameters() if p.requires_grad),
                checkpoint_bytes=(output / "model.pt").stat().st_size, best_epoch=best_epoch,
                selection="dev_pr_auc" if any(gold) else "negative_dev_nll_no_positives",
                calibration=calibration, epochs_completed=len(history),
                pos_weights={k: v.cpu().tolist() for k, v in pos_weights.items()},
                device=str(device), gpu=torch.cuda.get_device_name(device) if device.type == "cuda" else None)
    return predictions, info


def load_student(directory, device="cpu"):
    """Reload checkpoint, tokenizer, input settings and temperature for T15."""
    import json
    from pathlib import Path
    directory = Path(directory)
    student = json.loads((directory / "student.json").read_text(encoding="utf-8"))
    encoder = AutoModel.from_config(AutoConfig.from_pretrained(directory), attn_implementation="eager")
    model = Student(encoder, student["head_dropout"])
    model.load_state_dict(torch.load(directory / "model.pt", map_location="cpu", weights_only=True))
    model.to(device).eval()
    temperature = json.loads((directory / "temperature.json").read_text(encoding="utf-8"))["temperature"]
    return model, AutoTokenizer.from_pretrained(directory), student, temperature
