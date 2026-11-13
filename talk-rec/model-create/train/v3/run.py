"""python -m train.v3.run --run-name t55-v3 (synthetic train, real dev only)."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import traceback

from .models import offline_environment

offline_environment()


def write_json(path, value):
    from pipeline.label import atomic_text
    atomic_text(Path(path), json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n')


def safe_error(error):
    frames = traceback.extract_tb(error.__traceback__)[-4:]
    return type(error).__name__ + ' at ' + ' < '.join(f'{Path(f.filename).name}:{f.lineno}' for f in reversed(frames))


def reference_labels(directory, rows):
    from eval.reference_report import majority
    from eval import real
    repeats = []
    ids = {r.id for r in rows}
    for i in (1, 2, 3):
        values = real.read_objects(Path(directory) / f'r{i}/predictions.jsonl')
        by_id = {r['id']: r for r in values}
        if len(by_id) != len(values) or not ids <= by_id.keys():
            raise ValueError('reference identities incomplete')
        repeats.append({k: by_id[k] for k in ids})
    voted, _, _ = majority(repeats)
    return [voted[r.id]['verdict'] for r in rows]


def score_run(real_rows, probability, pred3):
    import numpy as np
    from eval.v3.metrics import held_out, select_threshold
    primary = [i for i, r in enumerate(real_rows) if r.cast == 'child_family']
    threshold = select_threshold([real_rows[i].label for i in primary], [probability[i] for i in primary])
    prediction = (np.asarray(probability) >= threshold).tolist()
    cross, folds = held_out([real_rows[i] for i in primary], [probability[i] for i in primary])
    loso = list(prediction)
    for i, value in zip(primary, cross):
        loso[i] = value
    return dict(threshold=threshold, folds=folds,
                dev=dict(probability=probability, prediction=prediction, pred3=pred3),
                loso=dict(probability=probability, prediction=loso, pred3=pred3))


def train_one(name, seed, train, val, rows, directory, args):
    from threadpoolctl import threadpool_limits
    from .models import EncoderModel, LinearModel, benchmark
    start = time.perf_counter()
    with threadpool_limits(limits=1):
        model = LinearModel('ngram', seed) if name == 'M0' else EncoderModel(seed, args.cache)
        if name == 'M0':
            model.fit([r.text for r in train], [r.label for r in train])
        else:
            model.fit([r.text for r in train], [r.label for r in train])
        training_seconds = time.perf_counter() - start
        vp, v3 = model.predict([r.text for r in val])
        probability, pred3 = model.predict([r.text for r in rows])
        result = score_run(rows, probability, pred3)
        result['val'] = dict(probability=vp, pred3=v3)
        result['val_gold'] = [r.label for r in val]
        latency = benchmark(model, args.repeats)
        weights = directory / 'weights'
        model.save(weights)
        result['resources'] = dict(training_seconds=training_seconds, cpu_ms=latency,
                                  storage_mb=sum(p.stat().st_size for p in weights.rglob('*') if p.is_file()) / 1e6,
                                  parameters=model.parameters())
        if name == 'M1':
            result['cache_revisions'] = model.cache_revisions
        result['seed'] = seed
        write_json(weights / 'threshold.json', dict(threshold=result['threshold'], calibrated_on='child_family_dev'))
    del model
    import gc
    gc.collect()
    if name == 'M1':
        import torch
        torch.cuda.empty_cache()
    return result


def summarize(raw, rows, usable, bootstrap):
    import numpy as np
    from eval.v3.metrics import evaluate, stats
    output = {mode: evaluate(rows, [r[mode] for r in raw], usable, bootstrap) for mode in ('dev', 'loso')}
    def accuracy(p, gold, threshold):
        return float(np.mean((np.asarray(p) >= threshold) == (np.asarray(gold) != 0)))
    half = [accuracy(r['val']['probability'], r['val_gold'], .5) for r in raw]
    calibrated = [accuracy(r['val']['probability'], r['val_gold'], r['threshold']) for r in raw]
    primary = [i for i, r in enumerate(rows) if r.cast == 'child_family']
    dev = [float(np.mean([r['dev']['prediction'][i] == (rows[i].label != 0) for i in primary])) for r in raw]
    output['validation'] = dict(accuracy_at_half=stats(half), accuracy_at_dev=stats(calibrated),
                                gap_pp=stats([(a-b)*100 for a, b in zip(calibrated, dev)]))
    for key in ('cluster_ci95', 'block_ci95'):
        ci = output['dev']['child_family']['included']['metrics']['accuracy'][key]
        mean_val = float(np.mean(calibrated))
        output['validation']['gap_pp'][key] = dict(
            low=(mean_val-ci['high'])*100 if ci['high'] is not None else None,
            high=(mean_val-ci['low'])*100 if ci['low'] is not None else None, defined=ci['defined'])
    output['resources'] = {k: stats([r['resources'][k] for r in raw]) for k in raw[0]['resources']}
    output['threshold'] = stats([r['threshold'] for r in raw])
    output['per_seed'] = {str(r['seed']): dict(threshold=r['threshold'], fold_thresholds=r['folds'],
                                             resources=r['resources'], cache_revisions=r.get('cache_revisions')) for r in raw}
    return output


def execute(args):
    from .data import LABELS, counts, load_dev, load_synth, split_scenes, stratified_subset
    from .models import safe_cache
    from eval.v3.metrics import choose_model, evaluate, selection_rule
    from eval.v3.report import report
    if 'M1' in args.models:
        safe_cache(args.cache)  # fail before any training if safe weights are unavailable
    started = time.perf_counter()
    root = Path.cwd()
    output = root / '.local/train/v3' / args.run_name
    rows, usable, real_counts = load_dev(root)
    synth, skipped = load_synth([args.synth_dir])
    if skipped:
        raise ValueError('incomplete synthetic export')
    sizes = args.sizes or [400, 1000, len({r.scene for r in synth})]
    if len(set(sizes)) != len(sizes):
        raise ValueError('duplicate sizes')
    reference = root / '.local/real/v1/reference/reference-v3-dev'
    gemini = reference_labels(reference, rows)
    settings = dict(seeds=args.seeds, subset_seed=55, split_seed=43, epochs=3,
                    learning_rate=3e-5, batch_size=8, max_length=256, truncation='both',
                    bootstrap=args.bootstrap, repeats=args.repeats, models=args.models, sizes=sizes,
                    verify_repeat=args.verify_repeat)
    signature = hashlib.sha256(json.dumps(dict(settings=settings,
        synth_hashes={k: hashlib.sha256((Path(args.synth_dir) / f'{k}.jsonl').read_bytes()).hexdigest()
                      for k in ('blocks', 'labels', 'cards')},
        # Hash real inputs privately, never serialize their content.
        dev=[(r.id, r.text, r.label, r.cast, r.unsure, r.seconds) for r in rows], usable=usable,
        gemini=gemini), ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    if args.resume:
        previous = json.loads((output / 'manifest.json').read_text(encoding='utf-8'))
        if previous['signature'] != signature:
            raise ValueError('resume signature mismatch')
    else:
        output.mkdir(parents=True, exist_ok=False)
        write_json(output / 'manifest.json', dict(signature=signature, settings=settings))
    results = dict(settings=settings, full_size=max(sizes), real_counts=real_counts,
                   synthetic_counts={}, models={}, baselines={}, reproducibility={}, command=args.command)
    all_predictions = []
    def predictions(name, size, seed, mode, current_rows, run):
        for i, row in enumerate(current_rows):
            all_predictions.append(dict(model=name, size=size, seed=seed, mode=mode, id=row.id,
                probability=run['probability'][i], prediction='keep' if run['prediction'][i] else 'none',
                gold='keep' if row.label else 'none',
                prediction3=LABELS[run['pred3'][i]] if run.get('pred3') is not None else None,
                gold3=LABELS[row.label]))
    for name in args.models:
        results['models'][name] = {}
        for size in sizes:
            selected = stratified_subset(synth, size)
            train, val = split_scenes(selected, 43)
            results['synthetic_counts'][str(size)] = dict(selected=counts(selected), train=counts(train), val=counts(val))
            raw = []
            for seed in args.seeds:
                directory = output / name / str(size) / f's{seed}'
                checkpoint = directory / 'numbers.json'
                if args.resume and checkpoint.exists():
                    entry = json.loads(checkpoint.read_text(encoding='utf-8'))
                else:
                    print(f'model={name} size={size} seed={seed} start train={len(train)} val={len(val)}', flush=True)
                    directory.mkdir(parents=True, exist_ok=True)
                    entry = train_one(name, seed, train, val, rows, directory, args)
                    write_json(checkpoint, entry)
                    print(f'model={name} size={size} seed={seed} done seconds={entry["resources"]["training_seconds"]:.1f}', flush=True)
                raw.append(entry)
                for mode in ('dev', 'loso'):
                    predictions(name, size, seed, mode, rows, entry[mode])
                val_run = dict(probability=entry['val']['probability'], pred3=entry['val']['pred3'],
                               prediction=[p >= entry['threshold'] for p in entry['val']['probability']])
                predictions(name, size, seed, 'synthetic_val', val, val_run)
                if args.verify_repeat and size == sizes[0] and seed == args.seeds[0]:
                    repeated_dir = output / 'reproducibility' / name
                    repeated_file = repeated_dir / 'numbers.json'
                    if args.resume and repeated_file.exists():
                        repeated = json.loads(repeated_file.read_text(encoding='utf-8'))
                    else:
                        print(f'model={name} same-seed repeat start', flush=True)
                        repeated_dir.mkdir(parents=True, exist_ok=True)
                        repeated = train_one(name, seed, train, val, rows, repeated_dir, args)
                        write_json(repeated_file, repeated)
                    prediction_equal = all(repeated[k] == entry[k] for k in ('dev', 'loso', 'val', 'threshold'))
                    artifact_equal = all(hashlib.sha256(p.read_bytes()).digest() == hashlib.sha256(
                        (repeated_dir / 'weights' / p.name).read_bytes()).digest()
                        for p in (directory / 'weights').iterdir() if p.suffix in ('.safetensors', '.pkl'))
                    results['reproducibility'][name] = dict(seed=seed, size=size,
                        prediction_equal=prediction_equal, weight_bytes_equal=artifact_equal)
                    if not prediction_equal or not artifact_equal:
                        raise ValueError('same-seed reproducibility mismatch')
                    print(f'model={name} same-seed repeat identical', flush=True)
            print(f'model={name} size={size} bootstrap start', flush=True)
            results['models'][name][str(size)] = summarize(raw, rows, usable, args.bootstrap)
            write_json(output / 'partial-results.json', results)
    for name in ('all_keep', 'gemini'):
        labels = [LABELS.index(v) for v in gemini] if name == 'gemini' else None
        p = [float(v != 0) for v in labels] if labels is not None else [1.] * len(rows)
        entry = dict(probability=p, prediction=[bool(v) for v in p], pred3=labels)
        results['baselines'][name] = evaluate(rows, [entry], usable, args.bootstrap, hard=True)
        predictions(name, max(sizes), None, 'dev', rows, entry)
    full = str(max(sizes))
    if set(args.models) == {'M0', 'M1'}:
        comparison = [results['models'][n][full]['loso']['child_family']['included']['metrics'] for n in ('M0', 'M1')]
        choice = choose_model(*comparison)
        rule_result = selection_rule(*comparison)
    else:
        choice = args.models[0]
        rule_result = f'단일 모델 실행: {choice}. M0/M1 비교 선택은 수행하지 않았다.'
    results['selection'] = dict(model=choice, metric='LOSO keep_recall cluster CI95',
                                rule_result=rule_result)
    candidate_seed = 43 if 43 in args.seeds else args.seeds[0]
    chosen = results['models'][choice][full]['per_seed'][str(candidate_seed)]
    weights = output / choice / full / f's{candidate_seed}' / 'weights'
    candidate = dict(model=choice, size=int(full), seed=candidate_seed, threshold=chosen['threshold'],
                     weights=str(weights.relative_to(output)), calibrated_on='child_family_dev',
                     artifact_hashes={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in weights.iterdir() if p.is_file()})
    write_json(output / 'candidate.json', candidate)
    results['elapsed_seconds'] = time.perf_counter() - started
    results['candidate'] = candidate
    write_json(output / 'results.json', results)
    from pipeline.label import atomic_text
    atomic_text(output / 'preds.jsonl', ''.join(json.dumps(p, ensure_ascii=False, allow_nan=False) + '\n' for p in all_predictions))
    atomic_text(output / 'report.md', report(results))
    print(f'complete runs={len(args.models)*len(sizes)*len(args.seeds)} seconds={results["elapsed_seconds"]:.1f} selected={choice}', flush=True)
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-name', default=datetime.now().strftime('t55-%Y%m%d-%H%M%S'))
    parser.add_argument('--synth-dir', default='.local/synth/t53')
    parser.add_argument('--cache', default='.local/hf')
    parser.add_argument('--models', nargs='+', choices=['M0', 'M1'], default=['M0', 'M1'])
    parser.add_argument('--seeds', nargs='+', type=int, default=[43, 44, 45])
    parser.add_argument('--sizes', nargs='+', type=int)
    parser.add_argument('--bootstrap', type=int, default=2000)
    parser.add_argument('--repeats', type=int, default=15)
    parser.add_argument('--resume', action='store_true')
    parser.add_argument('--verify-repeat', action='store_true')
    args = parser.parse_args(argv)
    if (not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', args.run_name)
        or len(set(args.models)) != len(args.models) or len(set(args.seeds)) != len(args.seeds)
        or any(not 0 <= seed < 2**32 for seed in args.seeds) or args.bootstrap < 1 or args.repeats < 1):
        parser.error('invalid v3 options')
    args.command = '.\\.venv\\Scripts\\python.exe -X utf8 -B -m train.v3.run ' + ' '.join(argv if argv is not None else sys.argv[1:])
    try:
        execute(args)
    except Exception as error:
        print('v3 failed: ' + safe_error(error), file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
