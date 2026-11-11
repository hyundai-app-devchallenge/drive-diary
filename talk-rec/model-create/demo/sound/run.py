"""Offline T48 experiment: python -m demo.sound.run --out .local/demo/sound/t48."""
from __future__ import annotations

import argparse
from collections import Counter
import csv
import json
import os
from pathlib import Path
import platform
import time

import numpy as np

from demo.acoustic import AST_MODEL, decode_audio
from demo.asr.data import clip_path, write_json
from tools.listen.storage import atomic_write, encode_lines
from .core import (CLASS_GROUP, GROUPS, RATE, TARGETS, calibrate_thresholds,
                   classification_metrics, mix_snr, rates, real_metrics, resolve_groups)
from .model import ASTModel

SNRS = (10, 5, 0, -5)


def read_rows(path):
    with Path(path).open(encoding='utf-8') as stream:
        return [json.loads(line) for line in stream if line.strip()]


def load_real(root):
    # Whitelist fields immediately: transcripts and reviewer comments never leave input.
    answers = {row['id']: row['verdict'] for row in read_rows(root / '.local/demo/answers/vizatae.jsonl')}
    selected = []
    seen = set()
    for row in read_rows(root / '.local/demo/sample.jsonl'):
        block_id = row['id']
        if block_id in seen:
            raise ValueError('duplicate sample id')
        seen.add(block_id)
        if block_id not in answers:
            continue
        if answers[block_id] not in ('story', 'moment', 'none', 'unsure') or row['origin'] not in ('approved', 'prescreened'):
            raise ValueError('invalid verdict or origin')
        selected.append({'id': block_id, 'origin': row['origin'], 'verdict': answers[block_id]})
    if not selected:
        raise ValueError('no human-labeled clips')
    return selected


def load_events(root, limit, rng):
    directory = root / '.local/sound/nonspeech7k/test/test'
    with (root / '.local/sound/nonspeech7k/test.csv').open(encoding='utf-8-sig', newline='') as stream:
        source = list(csv.DictReader(stream))
    selected = []
    for name in sorted({r['Classname'] for r in source}):
        rows = sorted([r for r in source if r['Classname'] == name], key=lambda r: r['Filename'])
        if limit:
            indices = sorted(rng.choice(len(rows), min(limit, len(rows)), replace=False).tolist())
            rows = [rows[i] for i in indices]
        for row in rows:
            filename = row['Filename']
            if Path(filename).name != filename or not (directory / filename).is_file():
                raise ValueError('invalid or missing event file')
            selected.append({'id': filename, 'file_id': row['File_ID'], 'class': name,
                             'path': directory / filename, 'csv_seconds': float(row['Durationin ms']) / 1000})
    return selected, dict(Counter(r['Classname'] for r in source))


def audio_duration(path):
    import av
    with av.open(str(path)) as container:
        stream = container.streams.audio[0]
        duration = float(stream.duration * stream.time_base) if stream.duration is not None else container.duration / 1e6
    if not np.isfinite(duration) or duration <= 0:
        raise ValueError('invalid audio duration')
    return duration


def decode_file(path):
    # All PCM decoding goes through the project's timestamp-aware PyAV decoder.
    return decode_audio(path, 0, audio_duration(path))


def background_segment(pool, count, rng):
    eligible = sorted(key for key, waveform in pool.items() if len(waveform) >= count)
    if not eligible:
        raise ValueError('no background long enough for event')
    block_id = eligible[int(rng.integers(len(eligible)))]
    waveform = pool[block_id]
    start = int(rng.integers(len(waveform) - count + 1))
    return waveform[start:start + count], {'id': block_id, 'start_sample': start, 'samples': count}


def maximum(summary):
    return {group: summary[group]['max'] for group in GROUPS}


def distribution(rows):
    return {group: {name: float(value) for name, value in zip(
        ('min', 'median', 'p90', 'p95', 'max'),
        np.quantile([row[group] for row in rows], [0, .5, .9, .95, 1]))} for group in GROUPS}


def fmt(value, percent=False):
    if value is None:
        return 'N/A'
    return f'{100 * value:.1f}%' if percent else f'{value:.4f}'


def render_report(result):
    a, b, c = result['clean'], result['mixed'], result['real']
    bg = result['background']
    lines = ['# T48 소리 사건 시험', '',
             f"{result['model']}, seed={result['seed']}, limit/class={result['limit_per_class'] or '전체'}, device={result['device']}.",
             f"Nonspeech7k {a['n']}개(대상 {a['n_target']}개), 실제 판정 클립 {result['real_n']}개.", '',
             '## A/B: 세 사건 강제 분류', '',
             '| 조건 | 정확도 | laugh–shout AUROC | laugh–cry AUROC | shout–cry AUROC | 기타 소리: 사건 하나 이상 오경보 |',
             '|---|---:|---:|---:|---:|---:|']
    for name, metrics in [('clean', a), *[(f'{snr} dB', b[str(snr)]) for snr in SNRS]]:
        pair = metrics['pairwise_auroc_score_difference']
        lines.append(f"| {name} | {fmt(metrics['accuracy'], True)} | {fmt(pair['laugh_vs_shout'])} | {fmt(pair['laugh_vs_cry'])} | {fmt(pair['shout_vs_cry'])} | {fmt(metrics['other_any_target_false_alarm'], True)} |")
    lines += ['', 'AUROC는 두 그룹 점수의 차이를 사용. 혼동 행렬은 실제 행 × 예측 열(laugh, shout, cry).', '']
    for name, metrics in [('clean', a), *[(f'{s} dB', b[str(s)]) for s in SNRS]]:
        lines += [f'### {name}', '', '| 실제 | laugh | shout | cry |', '|---|---:|---:|---:|']
        lines += [f"| {g} | {' | '.join(map(str, row))} |" for g, row in zip(TARGETS, metrics['confusion'])]
    lines += ['', '## 배경 오경보 5% 보정', '',
              f"none 클립 보정/평가={len(bg['calibration_ids'])}/{len(bg['evaluation_ids'])}, 구간 각 {bg['n_per_split']}개. 문턱값보다 **클 때** 검출.",
              '5%는 그룹별 보정 표본에서의 비율이다. 별도 평가 배경에서 5%가 보장되거나 사건 전체 오경보가 5%인 것은 아니다.', '',
              '| 그룹 | 문턱값 | 보정 FPR | 별도 배경 FPR | 보정 중앙값 | 보정 p95 | 평가 중앙값 | 평가 p95 |',
              '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    for g in GROUPS:
        lines.append(f"| {g} | {fmt(bg['thresholds'][g])} | {fmt(bg['calibration_false_alarm'][g], True)} | {fmt(bg['evaluation_false_alarm'][g], True)} | {fmt(bg['calibration_distribution'][g]['median'])} | {fmt(bg['calibration_distribution'][g]['p95'])} | {fmt(bg['evaluation_distribution'][g]['median'])} | {fmt(bg['evaluation_distribution'][g]['p95'])} |")
    lines += ['', '## B: 문턱값 적용 후 검출·오인', '',
              '| SNR | 실제 | n | 해당 그룹 검출률 | 사건 하나 이상 검출률 | 검출된 것 중 오분류 | laugh↔shout 오인 |',
              '|---|---|---:|---:|---:|---:|---:|']
    for snr in SNRS:
        for g in TARGETS:
            d = b[str(snr)]['detection'][g]
            opposite = {'laugh': 'shout', 'shout': 'laugh'}.get(g)
            cross = d['predicted_among_detected'][opposite] if opposite else None
            lines.append(f"| {snr} | {g} | {d['n']} | {fmt(d['own_threshold_recall'], True)} | {fmt(d['any_target_recall'], True)} | {fmt(d['wrong_label_among_detected'], True)} | {fmt(cross, True)} |")
    lines += ['', '검출된 것의 이름은 문턱값을 넘은 세 그룹 중 원점수 최대로 정한다. 오인 분모는 해당 실제 클래스에서 사건 하나 이상 검출된 수. 검출 0개는 N/A.', '',
              '## 기타 클래스 오경보율', '',
              '| 조건 | 클래스 | n | laugh | shout | cry | sing | speech |',
              '|---|---|---:|---:|---:|---:|---:|---:|']
    for name, metrics in [('clean', a), *[(f'{s} dB', b[str(s)]) for s in SNRS]]:
        for cls, row in metrics['other_false_alarm_by_class'].items():
            lines.append(f"| {name} | {cls} | {row['n']} | " + ' | '.join(fmt(row['rates'][g], True) for g in GROUPS) + ' |')
    lines += ['', '## C: 실제 클립 (소표본 탐색)', '',
              '| 판정 | n | 그룹 | max의 중앙값 | max의 최대 | mean의 중앙값 | mean의 최대 |',
              '|---|---:|---|---:|---:|---:|---:|']
    for verdict, row in c['by_verdict'].items():
        for g, stats in row['groups'].items():
            lines.append(f"| {verdict} | {row['n']} | {g} | {fmt(stats['max']['median'])} | {fmt(stats['max']['max'])} | {fmt(stats['mean']['median'])} | {fmt(stats['mean']['max'])} |")
    lines += ['', '| 이진 판정 (unsure 제외) | n | 양성 n | laugh AUROC | sing AUROC | max(laugh,sing) AUROC |',
              '|---|---:|---:|---:|---:|---:|']
    for name, row in c['binary_tasks_excluding_unsure'].items():
        lines.append(f"| {name} | {row['n']} | {row['n_positive']} | " + ' | '.join(fmt(row['auroc'][g]) for g in ('laugh', 'sing', 'laugh_or_sing')) + ' |')
    bench = result['benchmark']
    lines += ['', '## 크기·속도', '',
              f"파라미터 {bench['parameters']:,}개, 파라미터 {bench['parameter_bytes'] / 1e6:.1f} MB. CPU 1스레드 10초 창(특징 추출 포함): {bench['cpu_ms_median']:.1f} ms 중앙값, {bench['repeats']}회, warmup 1회.",
              f"실험 경과 {result['elapsed_seconds']:.1f}초. CPU: {result['environment']['cpu']}; torch {result['environment']['torch']}.", '',
              '## 해석·출처', '',
              '- Nonspeech7k는 별도 녹음된 소리라 실제 차 안 사건과 다르다. 합성 혼합은 실제 차의 잔향·마이크·동시발화를 재현하지 않는다.',
              '- none은 장면 저장 판정이며 무사건 라벨이 아니다. 배경에 이미 웃음·노래 등이 있을 수 있다. 구간은 독립 표본이 아니며 같은 원본 영상이 두 분할에 걸칠 수 있다.',
              '- 창 max는 긴 클립에서 오경보 기회가 증가한다. 짧은 사건 길이로 보정한 문턱값은 90초 묶음 FPR을 보장하지 않는다.',
              '- 실제 울음·고함 장면의 정답 데이터가 없어 저장 취소 정책의 안전성을 입증할 수 없다. C는 소표본으로 일반화 금지.',
              '- 노래는 이 데이터셋의 정답 클래스가 아니므로 A/B에서 노래 검출 성능은 검증하지 않는다.',
              '- AST는 차량 탑재용 크기가 아니며 큰 모델의 참고 상한이다. AUROC는 운영 문턱값 성능과 다르다.',
              '- Nonspeech7k: Zenodo DOI 10.5281/zenodo.6967442, CC BY 4.0. 사용한 test 분할을 평가 목적으로 혼합했다. 원본 CSV의 File_ID가 같은 변형은 독립 표본이 아니다.',
              '- AST: MIT/ast-finetuned-audioset-10-10-0.4593 (AudioSet 527 labels), 로컬 캐시만 사용. 프로젝트 기존 AST 로더·PyAV 디코더 재사용.',
              '- 전사 및 판정 코멘트를 출력하지 않는다. scores.jsonl에는 id/origin/verdict/그룹 max·mean만 기록한다.', '']
    return '\n'.join(lines)


def experiment(args):
    started = time.perf_counter()
    root, out = Path(args.root).resolve(), Path(args.out).resolve()
    rng_select, rng_bg, rng_mix = [np.random.default_rng(s) for s in np.random.SeedSequence(args.seed).spawn(3)]
    real = load_real(root)
    events, available = load_events(root, args.limit, rng_select)
    none_ids = sorted(r['id'] for r in real if r['verdict'] == 'none')
    if len(none_ids) < 2:
        raise ValueError('need at least two none background clips')
    permutation = rng_bg.permutation(len(none_ids))
    mid = len(none_ids) // 2
    calibration_ids = [none_ids[i] for i in permutation[:mid]]
    evaluation_ids = [none_ids[i] for i in permutation[mid:]]
    print(f'inputs: events={len(events)}, real={len(real)}, backgrounds={len(none_ids)}', flush=True)
    if args.model == 'ast':
        model = ASTModel(root / '.local/hf', args.device)
    else:
        from .efficientat import EfficientATModel
        model = EfficientATModel(root, args.model, args.device)
    benchmark = model.benchmark(args.benchmark_repeats)
    print(f"model: parameters={benchmark['parameters']}, cpu_ms={benchmark['cpu_ms_median']:.1f}", flush=True)
    # Decode only approved/prescreened files with a human verdict; metadata is safe.
    backgrounds = {}
    real_scores = []
    for index, row in enumerate(real, 1):
        waveform = decode_file(clip_path(root, row['id']))
        if row['verdict'] == 'none':
            backgrounds[row['id']] = waveform
        real_scores.append({**row, 'groups': model.summarize(waveform)})
        print(f'C {index}/{len(real)} id={row["id"]}', flush=True)
    atomic_write(out / 'scores.jsonl', encode_lines(real_scores))
    # Actual WAV duration, not rounded CSV duration, determines mixing intervals.
    event_audio = [(row, decode_file(row['path'])) for row in events]
    target_lengths = [len(waveform) for row, waveform in event_audio if row['class'] in CLASS_GROUP]
    bg_scores, bg_intervals = {}, {}
    for split, ids in [('calibration', calibration_ids), ('evaluation', evaluation_ids)]:
        pool = {key: backgrounds[key] for key in ids}
        bg_scores[split], bg_intervals[split] = [], []
        for index in range(args.background_windows):
            count = target_lengths[int(rng_bg.integers(len(target_lengths)))]
            waveform, interval = background_segment(pool, count, rng_bg)
            bg_scores[split].append(maximum(model.summarize(waveform)))
            bg_intervals[split].append(interval)
            if (index + 1) % 25 == 0:
                print(f'background {split} {index + 1}/{args.background_windows}', flush=True)
    thresholds = calibrate_thresholds(bg_scores['calibration'])
    clean, mixtures, mixture_intervals = [], {str(s): [] for s in SNRS}, []
    for index, (row, waveform) in enumerate(event_audio, 1):
        safe = {key: row[key] for key in ('id', 'file_id', 'class')}
        clean.append({**safe, 'scores': maximum(model.summarize(waveform))})
        background, interval = background_segment({key: backgrounds[key] for key in evaluation_ids}, len(waveform), rng_mix)
        mixture_intervals.append({'event_id': row['id'], **interval})
        for snr in SNRS:
            mixed, _, _ = mix_snr(waveform, background, snr)
            mixtures[str(snr)].append({**safe, 'scores': maximum(model.summarize(mixed))})
        if index % 10 == 0 or index == len(events):
            print(f'A/B {index}/{len(events)}', flush=True)
    import torch
    result = {
        'schema_version': 1, 'model': AST_MODEL if args.model == 'ast' else f'EfficientAT/{args.model}', 'device': model.device,
        'seed': args.seed, 'limit_per_class': args.limit, 'window_seconds': 10, 'hop_seconds': 5,
        'sample_rate': RATE, 'available_classes': available,
        'selected_classes': dict(Counter(r['class'] for r in events)),
        'label_mapping': resolve_groups(model.labels),
        'missing_aliases': {g: [n for n in names if n not in model.labels] for g, names in GROUPS.items()},
        'benchmark': benchmark, 'environment': {'python': platform.python_version(), 'torch': torch.__version__,
                                              'cpu': platform.processor(), 'cuda': torch.cuda.get_device_name() if model.device.startswith('cuda') else None},
        'background': {'target_fpr': .05, 'comparison_strict_gt': True,
                       'n_per_split': args.background_windows,
                       'calibration_ids': calibration_ids, 'evaluation_ids': evaluation_ids,
                       'thresholds': thresholds,
                       'calibration_distribution': distribution(bg_scores['calibration']),
                       'evaluation_distribution': distribution(bg_scores['evaluation']),
                       'calibration_false_alarm': rates(bg_scores['calibration'], thresholds),
                       'evaluation_false_alarm': rates(bg_scores['evaluation'], thresholds),
                       'scores': bg_scores, 'intervals': bg_intervals},
        'clean': classification_metrics(clean, thresholds),
        'mixed': {s: classification_metrics(rows, thresholds) for s, rows in mixtures.items()},
        'real_n': len(real_scores), 'real': real_metrics(real_scores),
        'predictions': {'clean': clean, 'mixed': mixtures, 'mixture_background_intervals': mixture_intervals},
        'elapsed_seconds': time.perf_counter() - started,
    }
    write_json(out / 'results.json', result)
    atomic_write(out / 'report.md', render_report(result).encode('utf-8'))
    print(f"done: events={len(events)}, real={len(real)}, elapsed_seconds={result['elapsed_seconds']:.1f}", flush=True)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', default='.')
    parser.add_argument('--out', required=True)
    parser.add_argument('--limit', type=int, default=0, help='maximum files per Nonspeech7k class; 0=all; C always all')
    parser.add_argument('--seed', type=int, default=48)
    parser.add_argument('--background-windows', type=int, default=200, help='random segments per calibration/evaluation split')
    parser.add_argument('--benchmark-repeats', type=int, default=5)
    parser.add_argument('--device', choices=('cuda', 'cpu'), default=None)
    parser.add_argument('--model', choices=('ast', 'mn04', 'mn10'), default='ast')
    args = parser.parse_args(argv)
    if args.limit < 0 or args.background_windows < 1 or args.benchmark_repeats < 1 or args.seed < 0:
        parser.error('limit/seed must be nonnegative; windows/repeats must be positive')
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
    try:
        experiment(args)
    except Exception as error:
        # Exception text from external libraries may include private input fields.
        import traceback
        where = ' < '.join(f'{Path(f.filename).name}:{f.lineno}' for f in traceback.extract_tb(error.__traceback__)[-4:][::-1])
        print(f'sound failed ({type(error).__name__} at {where}); check local model cache, audio and metadata', flush=True)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
