"""Read-only inputs. Never include input values in exceptions or logs."""
from dataclasses import dataclass
import json
from pathlib import Path
import re

from sklearn.model_selection import train_test_split

LABELS = ("none", "story", "moment")


@dataclass
class Example:
    id: str
    scene: int
    text: str
    label: int


def input_text(lines):
    """Identical model input for synthetic turns and real STT lines.

    No speaker, timing, label, pattern, level or clean transcript is used.
    """
    if not isinstance(lines, list):
        raise ValueError("invalid input lines")
    texts = [line["text"] if isinstance(line, dict) else line for line in lines]
    if any(not isinstance(t, str) for t in texts):
        raise ValueError("invalid line text type")
    return "\n".join(t.strip() for t in texts)


def read_rows(path):
    with Path(path).open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def load_synth(directories):
    examples, skipped = [], 0
    for index, directory in enumerate(directories):
        directory = Path(directory)
        if not (directory / "blocks.jsonl").exists() or not (directory / "labels.jsonl").exists():
            skipped += 1
            continue
        meta = directory / "meta.json"
        if meta.exists() and json.loads(meta.read_text(encoding="utf-8")).get("complete") is False:
            skipped += 1
            continue
        labels = read_rows(directory / "labels.jsonl")
        by_id = {row["id"]: row for row in labels}
        blocks = read_rows(directory / "blocks.jsonl")
        if len(by_id) != len(labels) or len({b["id"] for b in blocks}) != len(blocks):
            raise ValueError("duplicate synthetic identities within directory")
        if {b["id"] for b in blocks} != set(by_id):
            raise ValueError("synthetic blocks/labels mismatch; retry after export completes")
        for block in blocks:
            match = re.fullmatch(r"synth:(\d+)", block["source"])
            if match is None:
                raise ValueError("invalid synthetic scene identity")
            examples.append(Example(f"s{index}/{block['id']}", int(match[1]),
                                    input_text(block["turns"]), LABELS.index(by_id[block["id"]]["verdict"])))
    if not examples:
        raise ValueError("no complete synthetic inputs")
    return examples, skipped


def split_scenes(examples, seed=43):
    # Card numbers are global across directories: repeated levels/exports cannot leak.
    labels = {}
    for row in examples:
        if row.scene in labels and labels[row.scene] != row.label:
            raise ValueError("conflicting labels for one synthetic scene")
        labels[row.scene] = row.label
    scenes = sorted(labels)
    train, val = train_test_split(scenes, test_size=.2, random_state=seed,
                                 stratify=[labels[s] for s in scenes])
    train, val = set(train), set(val)
    a = [r for r in examples if r.scene in train]
    b = [r for r in examples if r.scene in val]
    if set(r.label for r in a) != {0, 1, 2} or set(r.label for r in b) != {0, 1, 2}:
        raise ValueError("each synthetic split needs all three labels")
    return a, b


def load_real(judge_path, asr_path, config="pleos"):
    judge = json.loads(Path(judge_path).read_text(encoding="utf-8"))
    ids = judge["ids"]
    if not ids or len(ids) != len(set(ids)):
        raise ValueError("invalid judge identities")
    selected = [r for r in read_rows(asr_path) if r["config"] == config and r["id"] in set(ids)]
    by_id = {r["id"]: r for r in selected}
    if len(by_id) != len(selected) or set(by_id) != set(ids):
        raise ValueError("missing or duplicate judged ASR observations")
    examples = [Example(i, -1, input_text(by_id[i]["lines"]), LABELS.index(judge["human"][i])) for i in ids]
    gemini = [LABELS.index(judge["pred"]["pleos"][i]) for i in ids]
    return examples, gemini
