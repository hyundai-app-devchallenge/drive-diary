"""Compare story classifiers on synthetic dialogues with a fixed train/dev/test split."""

import argparse
import csv
import json
import math
import re
import time
import unicodedata
from collections import Counter
from pathlib import Path

import numpy as np


HERE = Path(__file__).resolve().parent
OLD_MODEL = HERE.parent / "src/main/assets/feature2/story-model.tsv"
THRESHOLDS = (0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70)
# Only words from train/dev; held-out scenes are evaluated without augmentation.
LOANWORDS = {
    "road trip": "로드트립", "karaoke": "가라오케", "camping": "캠핑", "taxi": "택시",
    "concert": "콘서트", "selfie": "셀피", "video call": "비디오콜", "audition": "오디션",
    "picnic": "피크닉", "surprise party": "서프라이즈 파티", "gps": "지피에스",
    "elevator": "엘리베이터", "marathon": "마라톤", "graduation gown": "그래듀에이션 가운",
    "pizza": "피자",
}


def ngrams(text):
    text = unicodedata.normalize("NFKC", text[:512]).lower()
    letters = "".join(c if c.isalnum() else " " for c in text)
    text = "_" + "_".join(letters.split()) + "_"
    return {text[i:i + n] for n in range(2, 5) for i in range(len(text) - n + 1)}


def sparse_matrix(features, vocab):
    vectors = np.zeros((len(features), len(vocab)))
    for row, sample in zip(vectors, features):
        indices = [vocab[feature] for feature in sample if feature in vocab]
        row[indices] = 1 / math.sqrt(max(1, len(indices)))
    return vectors


def sigmoid(logits):
    return 1 / (1 + np.exp(-np.clip(logits, -60, 60)))


def fit(vectors, labels):
    weights = np.zeros(vectors.shape[1])
    bias = 0.0
    for _ in range(500):
        error = sigmoid(vectors @ weights + bias) - labels
        weights -= 2 * (vectors.T @ error / len(labels) + 0.01 * weights)
        bias -= 2 * error.mean()
    return weights, bias


def counts(scores, labels, threshold):
    pred = scores >= threshold
    return tuple(int(np.sum(mask)) for mask in (
        pred & labels, pred & ~labels, ~pred & labels, ~pred & ~labels
    ))


def threshold_on_dev(scores, labels):
    def priority(threshold):
        tp, fp, fn, _ = counts(scores, labels, threshold)
        return (5 * tp / max(1, 5 * tp + 4 * fp + fn), -fp, threshold)

    return max(THRESHOLDS, key=priority)


def embeddings(texts, model_path, tokenizer_path):
    import onnxruntime as ort
    from tokenizers import Tokenizer

    tokenizer = Tokenizer.from_file(str(tokenizer_path))
    tokenizer.enable_truncation(max_length=128)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    start = time.perf_counter()
    session = ort.InferenceSession(str(model_path), sess_options=options,
                                   providers=["CPUExecutionProvider"])
    print(f"Encoder load {(time.perf_counter() - start) * 1000:.0f}ms")
    vectors, ids, times = [], [], []
    for text in texts:
        start = time.perf_counter()
        tokens = tokenizer.encode(text).ids
        row = np.asarray([tokens], dtype=np.int64)
        inputs = {"input_ids": row, "attention_mask": np.ones_like(row),
                  "token_type_ids": np.zeros_like(row)}
        states = session.run(None, {name.name: inputs[name.name]
                                    for name in session.get_inputs()})[0][0]
        pooled = states.mean(axis=0)
        vectors.append(pooled / max(np.linalg.norm(pooled), 1e-12))
        ids.append(tokens)
        times.append((time.perf_counter() - start) * 1000)
    print(f"Host encoder p50/p95 {np.percentile(times[1:], [50, 95]).round(2).tolist()}ms")
    return np.asarray(vectors), ids


def asr_variants(row):
    normalized = re.sub(r"[^\w\s]", "", row["text"]).lower()
    if row["lang"] != "mix":
        return {normalized}
    rendered = normalized
    omitted = normalized
    for english, korean in LOANWORDS.items():
        rendered = rendered.replace(english, korean)
        omitted = omitted.replace(english, "")
    return {normalized, rendered, " ".join(omitted.split())}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--encoder", type=Path)
    parser.add_argument("--tokenizer", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--stt", type=Path, help="Optional id<TAB>transcript rows")
    parser.add_argument("--augment-asr", action="store_true")
    parser.add_argument("--context", action="store_true", help="Measure preceding + current text vs current only")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with (HERE / "story-benchmark.tsv").open(encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))
    if args.augment_asr:
        for row in list(rows):
            if row["split"] in ("train", "dev"):
                rows.extend({**row, "text": text} for text in sorted(asr_variants(row))
                            if text != row["text"])
    if args.stt:
        transcripts = dict(line.split("\t", 1) for line in
                           args.stt.read_text(encoding="utf-8-sig").splitlines() if "\t" in line)
        for row in list(rows):
            if row["id"] in transcripts:
                rows.append({**row, "split": "stt", "text": transcripts[row["id"]]})
    if args.context:
        with (HERE / "story-context.tsv").open(encoding="utf-8", newline="") as source:
            for row in csv.DictReader(source, delimiter="\t"):
                rows.append({**row, "split": "last-turn", "text": row["current"]})
                rows.append({**row, "split": "two-turns",
                             "text": row["previous"] + " " + row["current"]})
    labels = np.asarray([row["label"] == "1" for row in rows])
    train, dev, test, stt = (np.asarray([row["split"] == name for row in rows])
                             for name in ("train", "dev", "test", "stt"))
    language = np.asarray([row["lang"] for row in rows])
    features = [ngrams(row["text"]) for row in rows]
    old_entries = dict(line.split("\t", 1) for line in OLD_MODEL.read_text(encoding="utf-8").splitlines())
    old_bias = float(old_entries.pop("bias"))
    old_scores = sigmoid(sparse_matrix(features, {key: i for i, key in enumerate(old_entries)})
                         @ np.asarray(list(old_entries.values()), dtype=float) + old_bias)
    freq = Counter(feature for sample, selected in zip(features, train) if selected for feature in sample)
    vocab = {feature: i for i, feature in enumerate(sorted(key for key, count in freq.items() if count >= 2))}
    sparse = sparse_matrix(features, vocab)
    weights, bias = fit(sparse[train], labels[train])
    sparse_scores = sigmoid(sparse @ weights + bias)
    threshold = threshold_on_dev(sparse_scores[dev], labels[dev])
    with (args.output / "story-model-mixed.tsv").open("w", encoding="utf-8", newline="\n") as output:
        output.write(f"bias\t{bias:.10g}\n")
        output.write(f"threshold\t{threshold}\n")
        for term, i in vocab.items():
            output.write(f"{term}\t{weights[i]:.10g}\n")
    models = {"old": (old_scores, 0.65), "sparse": (sparse_scores, threshold)}
    if args.encoder:
        vectors, token_ids = embeddings([row["text"] for row in rows], args.encoder, args.tokenizer)
        weights, bias = fit(vectors[train], labels[train])
        scores = sigmoid(vectors @ weights + bias)
        threshold = threshold_on_dev(scores[dev], labels[dev])
        models["encoder"] = (scores, threshold)
        # Pre-tokenized evaluation isolates encoder load/inference on Android.
        with (args.output / "encoder-bench.tsv").open("w", encoding="utf-8", newline="\n") as output:
            output.write(f"{bias:.10g}\t{threshold}\n")
            output.write(",".join(f"{weight:.10g}" for weight in weights) + "\n")
            for row, tokens, score in zip(rows, token_ids, scores):
                if row["split"] == "test":
                    output.write(f"{row['id']}\t{row['label']}\t{score:.8f}\t"
                                 + ",".join(map(str, tokens)) + "\n")
    for name, (scores, threshold) in models.items():
        result = {"model": name, "threshold": threshold, "dev": counts(scores[dev], labels[dev], threshold)}
        for group in ("all", "ko", "mix", "en"):
            mask = test if group == "all" else test & (language == group)
            result[group] = counts(scores[mask], labels[mask], threshold)
        if stt.any():
            result["stt"] = counts(scores[stt], labels[stt], threshold)
        if args.context:
            for split in ("last-turn", "two-turns"):
                mask = np.asarray([row["split"] == split for row in rows])
                result[split] = counts(scores[mask], labels[mask], threshold)
        print(json.dumps(result), "(TP, FP, FN, TN)")
    with (args.output / "scores.tsv").open("w", encoding="utf-8", newline="") as output:
        writer = csv.writer(output, delimiter="\t", lineterminator="\n")
        writer.writerow(["split", "id", "lang", "label", *models, "text"])
        for i, row in enumerate(rows):
            if not train[i]:
                writer.writerow([row[key] for key in ("split", "id", "lang", "label")]
                                + [f"{scores[i]:.6f}" for scores, _ in models.values()] + [row["text"]])


if __name__ == "__main__":
    main()
