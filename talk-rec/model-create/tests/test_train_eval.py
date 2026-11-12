import json
import subprocess
import sys

import numpy as np

from train.data import EXCLUDES, write_rows
from train.run import evaluate_split, score_rows
from train.s0 import fit, predict


def test_score_format_accepted_by_real_eval_cli(tmp_path):
    snap, output = tmp_path / "ds", tmp_path / "r"
    snap.mkdir()
    output.mkdir()
    (snap / "timing").mkdir()
    labels = [dict(id=f"yt:toy#{i}", story=story, kind=["recall"] if story >= 2 else [],
                   exclude=[], skip=False, labeler="llm:toy", note="") for i, story in enumerate([0, 2])]
    windows = [dict(id=l["id"], source="yt:toy", split="dev", clip=0, context=[], current="fixture") for l in labels]
    examples = [dict(window=w) for w in windows]
    predictions = dict(binary=np.array([[.1], [.9]]), exclude=np.zeros((2, 5)))
    rows = list(score_rows(examples, predictions))
    assert set(rows[0]) == {"id", "story", "exclude"}
    assert set(rows[0]["exclude"]) == set(EXCLUDES)
    write_rows(output / "dev.scores.jsonl", rows)
    write_rows(snap / "dev.labels.jsonl", labels)
    write_rows(snap / "dev.windows.jsonl", windows)
    write_rows(snap / "timing/all.jsonl", [dict(source="yt:toy", utt=i, start=i*4, end=i*4+3) for i in range(2)])
    evaluate_split(snap, output, "dev")
    result = json.loads((output / "dev.eval.json").read_text())
    assert result["utterance"]["pr_auc"] == 1
    compared = subprocess.run([sys.executable, "-B", "-m", "eval.run", "compare", str(output / "dev.eval.json")], capture_output=True, text=True)
    assert compared.returncode == 0 and "utterance.pr_auc" in compared.stdout


def test_s0_all_targets_and_constant_rare_flags():
    rows = [dict(window=dict(context=[], current=text), label=dict(story=story, kind=["recall"] if story >= 2 else [], exclude=[]), weight=1)
            for text, story in [("abc abc", 0), ("abc", 1), ("def", 2), ("def def", 3)]]
    model = fit(rows, 0)
    pred = predict(model, [r["window"] for r in rows])
    assert len(pred) == 12  # binary + ordinal + 5 kind + 5 exclude
    assert pred["binary"][3] > pred["binary"][0]
    assert pred["ordinal"][3] > pred["ordinal"][0]
    assert pred["exclude:optout"].tolist() == [0]*4
