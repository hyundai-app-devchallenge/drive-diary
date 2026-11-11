"""Compare shared label ids without loading or emitting transcript text."""
import argparse
import json
from pathlib import Path
import sys

from common.schema import EXCLUDES, KINDS, validate_label
from pipeline.label import read_jsonl


def load_labels(directory):
    directory = Path(directory)
    if not directory.is_dir():
        raise ValueError('label directory does not exist')
    result = {}
    for path in sorted(directory.glob('*.jsonl')):
        if path.name in {'run.jsonl', 'unclear.jsonl'}:
            continue
        for record in read_jsonl(path):
            validate_label(record)
            if record['id'] in result:
                raise ValueError('duplicate label id')
            result[record['id']] = record
    return result


def kappa(a, b, categories, quadratic=False):
    if not a:
        return None
    n = len(a)
    def weight(x, y):
        return (x - y) ** 2 if quadratic else int(x != y)
    observed = sum(weight(x, y) for x, y in zip(a, b)) / n
    expected = sum(weight(x, y) * a.count(x) * b.count(y)
                   for x in categories for y in categories) / (n * n)
    # Degenerate marginals have no chance variance; kappa is undefined.
    return None if expected == 0 else 1 - observed / expected


def compare(a, b):
    ids = sorted(a.keys() & b.keys())
    n = len(ids)
    def agreement(predicate):
        return sum(predicate(a[i], b[i]) for i in ids) / n if n else None
    left, right = [a[i]['story'] for i in ids], [b[i]['story'] for i in ids]
    binary_a, binary_b = [int(x >= 2) for x in left], [int(x >= 2) for x in right]
    return dict(
        shared_ids=n, only_a=len(a.keys() - b.keys()), only_b=len(b.keys() - a.keys()),
        exact_agreement=agreement(lambda x, y: all(x[f] == y[f] if f in {'story', 'skip'}
                                                  else set(x[f]) == set(y[f])
                                                  for f in ('story', 'kind', 'exclude', 'skip'))),
        story_exact_agreement=agreement(lambda x, y: x['story'] == y['story']),
        binary_story_agreement=agreement(lambda x, y: (x['story'] >= 2) == (y['story'] >= 2)),
        binary_story_kappa=kappa(binary_a, binary_b, range(2)),
        story_quadratic_weighted_kappa=kappa(left, right, range(4), quadratic=True),
        kind={flag: agreement(lambda x, y, f=flag: (f in x['kind']) == (f in y['kind'])) for flag in sorted(KINDS)},
        exclude={flag: agreement(lambda x, y, f=flag: (f in x['exclude']) == (f in y['exclude'])) for flag in sorted(EXCLUDES)},
        skip_agreement=agreement(lambda x, y: x['skip'] == y['skip']),
        story_difference_ge_2=[i for i in ids if abs(a[i]['story'] - b[i]['story']) >= 2],
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('a_dir', type=Path)
    parser.add_argument('b_dir', type=Path)
    args = parser.parse_args(argv)
    try:
        report = compare(load_labels(args.a_dir), load_labels(args.b_dir))
    except (OSError, ValueError):
        print('Invalid label directory/schema or duplicate ids.', file=sys.stderr)
        return 2
    print(json.dumps(report, ensure_ascii=True, indent=2, allow_nan=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
