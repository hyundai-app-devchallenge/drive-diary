"""T60 Part A: dev-only int8 scores and text-free offline stitching metrics."""
import contextlib
import io
import json
from pathlib import Path
import sys

from .core import KINDS, aggregate, build_runs, content_chars, snapshot

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / '.local/eval/stitch'
BUNDLE = ROOT / '.local/export/m1'


def load_dev(root=ROOT):
    from demo.trial.data import input_text
    from eval import real
    from train.v3.export import checked_labels
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        value = real.load_labels(root, real.run_directory(root, 'v1'),
                                 annotators=['vizatae'], split='dev')
    blocks, labels = checked_labels(value)
    selected = []
    for block in blocks:
        if block['cast'] == 'unknown':
            continue
        human = labels['vizatae'].get(block['id'])
        text = input_text(block['lines'])
        selected.append(dict(id=block['id'], source=block['source'], start=block['start'],
                             end=block['end'], closing=block['closing'],
                             range_index=block.get('range_index'), text=text, chars=content_chars(text),
                             human=None if human is None else dict(keep=human['keep'], kind=human['kind'],
                                                                  policy=bool(human['policy']))))
    return selected, dict(unknown_cast_skipped=sum(b['cast'] == 'unknown' for b in blocks))


def score(blocks, bundle=BUNDLE):
    import numpy as np
    from demo.trial.models import load_tokenizer, token_batch
    from train.v3.export import MAX_LENGTH, session
    tokenizer = load_tokenizer(str(bundle), bundle)
    runtime = session(bundle / 'model.int8.onnx')
    for block in blocks:
        feed = {k: v.numpy() for k, v in token_batch(tokenizer, [block['text']], MAX_LENGTH, 'both').items()}
        probability, logits = runtime.run(None, feed)
        if (probability.shape != (1,) or logits.shape != (1, 3)
                or not np.isfinite(logits).all() or not np.isfinite(probability).all()
                or not 0 <= probability[0] <= 1):
            raise ValueError('invalid ONNX output')
        block.update(probability=float(probability[0]), model_kind=KINDS[int(logits[0].argmax())])


def parity(blocks, predictions):
    reference = {row['id']: row['int8'] for row in predictions if row['dataset'] == 'dev'}
    differences = [abs(b['probability'] - reference[b['id']]) for b in blocks if b['id'] in reference]
    return dict(overlap=len(differences), max_abs_diff=max(differences, default=None),
                tolerance=1e-6, passed=bool(differences) and max(differences) <= 1e-6)


def execute(root=ROOT):
    from tools.listen.storage import atomic_write, encode_lines
    from train.v3.export import offline
    offline()
    # Third-party diagnostics are quarantined just like the existing export gate.
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        blocks, loading = load_dev(root)
        bundle = Path(root) / '.local/export/m1'
        score(blocks, bundle)
    check = parity(blocks, json.loads((bundle / 'predictions.json').read_text(encoding='utf-8')))
    runs = build_runs(blocks)
    report = dict(schema_version=1, scope='dev_vizatae_known_cast', policy_flags_ignored=True,
                  loading=loading, int8_parity=check, **aggregate(runs))
    directory = Path(root) / '.local/eval/stitch'
    atomic_write(directory / 'measure.json',
                 (json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8'))
    atomic_write(directory / 'runs.jsonl', encode_lines(snapshot(runs)))
    return report


def print_report(report):
    print(f"blocks={report['counts']['blocks']} runs={report['runs']['count']} "
          f"policy_labeled={report['counts']['policy_labeled']} "
          f"int8_overlap={report['int8_parity']['overlap']} "
          f"max_abs_diff={report['int8_parity']['max_abs_diff']}")
    print('T_cont  recall  false_first  false_cont  holes  saved_seconds')
    for row in report['hysteresis']:
        recall = '-' if row['keep_recall'] is None else f"{row['keep_recall']:.4f}"
        print(f"{row['t_cont']:.3f}  {recall:>6}  {row['false_saves']['first']:11d}  "
              f"{row['false_saves']['continuation']:10d}  {row['holes']:5d}  {row['saved_seconds']:.1f}")


def main(argv=None):
    import argparse
    argparse.ArgumentParser(description=__doc__).parse_args(argv)
    try:
        report = execute()
        print_report(report)
        return 0 if report['int8_parity']['passed'] else 2
    except Exception:
        print('stitch_measure_failed', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
