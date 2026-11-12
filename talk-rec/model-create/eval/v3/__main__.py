"""Numeric report regeneration, or one explicitly opened frozen-model test."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

from train.v3.models import offline_environment
from train.v3.run import safe_error, write_json


def frozen_test(output, *, open_test=False):
    if not open_test:
        raise ValueError('test locked')
    from train.v3.data import LABELS, load_dev
    from train.v3.models import load_saved
    from .metrics import evaluate
    from pipeline.label import atomic_text
    candidate = json.loads((output / 'candidate.json').read_text(encoding='utf-8'))
    weights = (output / candidate['weights']).resolve()
    if not weights.is_relative_to(output.resolve()):
        raise ValueError('invalid candidate location')
    for name, expected in candidate['artifact_hashes'].items():
        path = weights / name
        if path.parent != weights or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('frozen candidate mismatch')
    # Exclusive reservation precedes label access. Failed attempts also remain
    # recorded rather than silently reopening a held-out test.
    with (output / 'test-attempt.json').open('x', encoding='utf-8') as stream:
        json.dump(dict(model=candidate['model'], threshold=candidate['threshold']), stream)
    rows, usable, counts = load_dev(Path.cwd(), split='test', open_test=True)
    model = load_saved(weights, candidate['model'])
    probability, pred3 = model.predict([r.text for r in rows])
    prediction = [p >= candidate['threshold'] for p in probability]
    run = dict(probability=probability, prediction=prediction, pred3=pred3)
    result = dict(candidate=candidate, counts=counts, test=evaluate(rows, [run], usable))
    write_json(output / 'test-results.json', result)
    atomic_text(output / 'test-preds.jsonl', ''.join(json.dumps(dict(
        id=r.id, probability=probability[i], prediction='keep' if prediction[i] else 'none',
        gold='keep' if r.label else 'none', prediction3=LABELS[pred3[i]], gold3=LABELS[r.label])) + '\n'
        for i, r in enumerate(rows)))
    print(f'test complete blocks={len(rows)} sources={len({r.source for r in rows})}', flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('report', 'test'):
        command = commands.add_parser(name)
        command.add_argument('--run-name', required=True)
        if name == 'test':
            command.add_argument('--open-test', action='store_true')
    args = parser.parse_args(argv)
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', args.run_name):
        parser.error('invalid run name')
    offline_environment()
    output = Path.cwd() / '.local/train/v3' / args.run_name
    try:
        if args.command == 'test':
            frozen_test(output, open_test=args.open_test)
        else:
            from pipeline.label import atomic_text
            from .report import report
            results = json.loads((output / 'results.json').read_text(encoding='utf-8'))
            atomic_text(output / 'report.md', report(results))
    except Exception as error:
        print('eval v3 failed: ' + safe_error(error), file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
