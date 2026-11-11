"""Mechanical diagnostics, never a substitute for listening accuracy."""
import re

from pipeline.segment import distribution


def repetition_count(text):
    """Count disjoint runs of >=8 equal tokens or contiguous repeated syllables."""
    spans = []
    tokens = list(re.finditer(r"[^\W_]+", text.casefold()))
    i = 0
    while i < len(tokens):
        j = i + 1
        while j < len(tokens) and tokens[j][0] == tokens[i][0]:
            j += 1
        if j - i >= 8:
            spans.append((tokens[i].start(), tokens[j - 1].end()))
        i = j
    spans.extend((m.start(), m.end()) for m in re.finditer(r"([^\W_]+?)\1{7,}", text.casefold()))
    merged = []
    for start, end in sorted(spans):
        if merged and start < merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return len(merged)


def summarize(rows):
    line_chars = [len(line["text"]) for row in rows for line in row["lines"]]
    clip_chars = [sum(len(line["text"]) for line in row["lines"]) for row in rows]
    repeats = [repetition_count(" ".join(line["text"] for line in row["lines"])) for row in rows]
    return dict(repetition_runs=sum(repeats), clips_with_repetition=sum(n > 0 for n in repeats),
                line_count=len(line_chars), empty_clips=sum(n == 0 for n in clip_chars),
                line_chars={**distribution(line_chars), "total": sum(line_chars)},
                clip_chars={**distribution(clip_chars), "total": sum(clip_chars)})
