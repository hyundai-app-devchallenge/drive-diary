"""S0: TinyStoryModel feature scheme, retrained on the shared snapshot (§6).

Unique character 2..4 grams with underscore word/boundary markers; training
document frequency >= 2; each known feature is 1/sqrt(known unique count).
The original 512-codepoint raw-input limit precedes common normalization.
"""

from collections import Counter
import math

import numpy as np
from scipy.sparse import csr_matrix
from sklearn.linear_model import LogisticRegression

from common.normalize import normalize
from .data import EXCLUDES


def features(text):
    text = "_" + normalize(text[:512]).replace(" ", "_") + "_"
    return {text[i:i + n] for n in (2, 3, 4) for i in range(len(text) - n + 1)}


def vectorize(samples, vocabulary):
    indices, data, indptr = [], [], [0]
    for sample in samples:
        known = sorted(vocabulary[f] for f in sample if f in vocabulary)
        indices.extend(known)
        data.extend([1 / math.sqrt(max(1, len(known)))] * len(known))
        indptr.append(len(indices))
    return csr_matrix((data, indices, indptr), shape=(len(samples), len(vocabulary)), dtype=np.float64)


def input_text(window, context_turns):
    turns = window["context"][-context_turns:] if context_turns else []
    return " ".join([*turns, window["current"]])


def fit(rows, context_turns, seed=42, c=1.0):
    samples = [features(input_text(r["window"], context_turns)) for r in rows]
    counts = Counter(f for fs in samples for f in fs)
    vocab = {f: i for i, f in enumerate(sorted(f for f, n in counts.items() if n >= 2))}
    if not vocab:
        raise ValueError("S0 has no features with training document frequency >= 2")
    x = vectorize(samples, vocab)
    weights = np.array([r["weight"] for r in rows])
    # Same labels/targets as S1; scalar soft ordinal BCE follows DESIGN.md §9.2.
    targets = {"binary": np.array([r["label"]["story"] >= 2 for r in rows], dtype=int),
               "ordinal": np.array([r["label"]["story"] / 3 for r in rows])}
    for field, names in (("kind", ("recall", "moment", "feeling", "promise", "family_fact")),
                         ("exclude", EXCLUDES)):
        for name in names:
            targets[f"{field}:{name}"] = np.array([name in r["label"][field] for r in rows], dtype=int)
    heads = {}
    for name, y in targets.items():
        if name == "ordinal":
            # A soft-target Bernoulli objective equals two weighted hard copies.
            from scipy.sparse import vstack
            xx, yy, ww = vstack([x, x]), np.r_[np.ones(len(y)), np.zeros(len(y))], np.r_[weights * y, weights * (1-y)]
        else:
            xx, yy, ww = x, y, weights
        support = set(yy[ww > 0])
        if len(support) < 2:
            heads[name] = float(np.average(y, weights=weights))
        else:
            clf = LogisticRegression(C=c, solver="liblinear", max_iter=1000, random_state=seed,
                                     tol=1e-8)
            clf.fit(xx, yy, sample_weight=ww)
            heads[name] = dict(coef=clf.coef_[0].tolist(), bias=float(clf.intercept_[0]))
    return dict(vocabulary=vocab, heads=heads, context_turns=context_turns,
                feature_scheme="unique_char_2_4_df2_known_sqrt_512_raw_codepoints")


def predict(model, windows):
    x = vectorize([features(input_text(w, model["context_turns"])) for w in windows], model["vocabulary"])
    result = {}
    for name, head in model["heads"].items():
        if isinstance(head, float):
            result[name] = np.full(len(windows), head)
        else:
            logits = np.asarray(x @ np.array(head["coef"]) + head["bias"])
            result[name] = 1 / (1 + np.exp(-np.clip(logits, -60, 60)))
    return result
