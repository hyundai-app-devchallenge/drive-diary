"""Verify saved T03 output and write a reproducible, text-free spot-check table."""

import argparse
import json
from pathlib import Path
import random

from common.schema import validate_utterance
from pipeline.segment import LOCAL, RATE, distribution, pcm_boundaries, source_name


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("jsonl", type=Path)
    cli.add_argument("--baseline", type=Path)
    cli.add_argument("--seed", type=int, default=20261110)
    args = cli.parse_args()
    records = [json.loads(line) for line in args.jsonl.read_text(encoding="utf-8").splitlines()]
    stats = json.loads(args.jsonl.with_suffix(".stats.json").read_text(encoding="utf-8"))
    gap_limit = stats["parameters"]["gap_seconds"]
    word_pairs = 0
    pcm_matches = {}
    for index, record in enumerate(records):
        validate_utterance(record)
        assert record["utt"] == index
        assert record["source"] == stats["source"]
        assert record["end"] - record["start"] <= 12.0 + 1e-9, f"over 12 s: {index}"
        assert index == 0 or record["start"] >= records[index - 1]["start"]
        words = record["words"]
        assert record["start"] == words[0]["start"]
        assert record["end"] == max(w["end"] for w in words)
        assert record["text"] == "".join(w["text"] for w in words).strip()
        for before, after in zip(words, words[1:]):
            assert after["start"] >= before["start"]
            assert after["start"] - before["end"] < gap_limit - 1e-9, f"internal gap: {index}"
            word_pairs += 1
        if "pcm" in record:
            bounds = record["pcm"]
            first, last = pcm_boundaries(record["start"], record["end"],
                                         round(stats["audio_duration"] * RATE),
                                         stats["parameters"]["tail_seconds"])
            assert (bounds["start_sample"], bounds["end_sample"]) == (first, last)
            assert (bounds["start"], bounds["end"]) == (first / RATE, last / RATE)
            assert bounds["sample_rate"] == RATE
            pcm = LOCAL / "pcm" / source_name(record["source"]) / f"{index:06d}.pcm"
            pcm_matches[index] = pcm.stat().st_size == 2 * (last - first)
            assert pcm_matches[index], f"PCM byte count: {index}"
            assert first <= round(record["start"] * RATE) <= round(record["end"] * RATE) <= last
            assert last - first <= 12 * RATE
    selected = sorted(random.Random(args.seed).sample(range(len(records)), min(20, len(records))))
    lines = ["id\tutt\tstart\tend\tduration\tchars\tpcm_matches"]
    checks = []
    for index in selected:
        record = records[index]
        row = dict(utt=index, start=record["start"], end=record["end"],
                   duration=record["end"] - record["start"], chars=len(record["text"]),
                   pcm_matches=pcm_matches.get(index))
        checks.append(row)
        lines.append(f"{record['source']}#{index}\t{index}\t{row['start']:.3f}\t{row['end']:.3f}\t{row['duration']:.3f}\t{row['chars']}\t{row['pcm_matches']}")
    result = dict(source=stats["source"], seed=args.seed, utterance_count=len(records),
                  duration_seconds=distribution([r["end"] - r["start"] for r in records]),
                  assertions=dict(no_utterance_over_12_seconds=True,
                                  no_internal_gap_at_or_above_threshold=True,
                                  word_pairs_checked=word_pairs, pcm_files_checked=len(pcm_matches)),
                  spotcheck=checks, listened=False)
    if args.baseline:
        old = json.loads(args.baseline.read_text(encoding="utf-8"))
        segments = [s for clip in old["clips"] for s in clip["segments"]]
        result["baseline"] = dict(count=len(segments),
                                  duration_seconds=distribution([s["end"] - s["start"] for s in segments]))
    scratch = LOCAL / "segment"
    scratch.mkdir(parents=True, exist_ok=True)
    (scratch / "spotcheck.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (scratch / "verification.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
