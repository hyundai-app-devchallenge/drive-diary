"""Transcript-free agreement report, with paired A/B analysis and bootstrap CIs."""
from __future__ import annotations

import argparse
from glob import glob
from itertools import combinations
import json
import math
from pathlib import Path

import numpy as np

from .data import VERDICTS, atomic_text, finite, latest, read_jsonl


def divide(a, b):
    return a / b if b else None


def metrics(truth, predicted):
    if len(truth) != len(predicted):
        raise ValueError('truth/prediction length mismatch')
    matrix = [[0] * 3 for _ in range(3)]
    for a, b in zip(truth, predicted):
        matrix[VERDICTS.index(a)][VERDICTS.index(b)] += 1
    n = len(truth)
    accuracy = divide(sum(matrix[i][i] for i in range(3)), n)
    expected = sum(sum(matrix[i]) * sum(row[i] for row in matrix) for i in range(3)) / n ** 2 if n else 1
    kappa = (accuracy - expected) / (1 - expected) if n and expected < 1 else None
    result = {'n': n, 'accuracy': accuracy, 'kappa': kappa, 'confusion': matrix}
    for name, positives in [('keep', {'story', 'moment'}), ('story', {'story'})]:
        tp = sum(a in positives and b in positives for a, b in zip(truth, predicted))
        result[name + '_recall'] = divide(tp, sum(a in positives for a in truth))
        result[name + '_precision'] = divide(tp, sum(b in positives for b in predicted))
    return result


MEASURES = ('accuracy', 'kappa', 'keep_recall', 'keep_precision', 'story_recall', 'story_precision')


def bootstrap(truth, predicted, repeats=2000, seed=41):
    rng = np.random.default_rng(seed)
    values = {key: [] for key in MEASURES}
    for _ in range(repeats if truth else 0):
        indices = rng.integers(0, len(truth), len(truth))
        scores = metrics([truth[i] for i in indices], [predicted[i] for i in indices])
        for key in MEASURES:
            if scores[key] is not None:
                values[key].append(scores[key])
    return {key: {'low': float(np.quantile(v, .025)) if v else None,
                  'high': float(np.quantile(v, .975)) if v else None,
                  'valid': len(v), 'repeats': repeats} for key, v in values.items()}


def paired(truth, a, b, repeats=2000, seed=41):
    if len(truth) != len(a) or len(a) != len(b):
        raise ValueError('paired lengths mismatch')
    both = sum(x == y == z for x, y, z in zip(truth, a, b))
    only_a = sum(x == y and x != z for x, y, z in zip(truth, a, b))
    only_b = sum(x != y and x == z for x, y, z in zip(truth, a, b))
    neither = len(truth) - both - only_a - only_b
    discordant = only_a + only_b
    # Exact two-sided binomial McNemar, no asymptotic approximation for small n.
    p = min(1., 2 * sum(math.comb(discordant, i) for i in range(min(only_a, only_b) + 1)) / 2 ** discordant) if discordant else 1.
    differences = np.array([int(x == z) - int(x == y) for x, y, z in zip(truth, a, b)])
    rng = np.random.default_rng(seed)
    boots = [float(differences[rng.integers(0, len(truth), len(truth))].mean()) for _ in range(repeats)] if truth else []
    return dict(both=both, only_a=only_a, only_b=only_b, neither=neither, p=p,
                delta=float(differences.mean()) if truth else None,
                ci=[float(np.quantile(boots, .025)), float(np.quantile(boots, .975))] if boots else [None, None])


def mean_interval(values, repeats, seed):
    if not values:
        return None, [None, None]
    values = np.asarray(values)
    rng = np.random.default_rng(seed)
    means = values[rng.integers(0, len(values), (repeats, len(values)))].mean(axis=1)
    return float(values.mean()), [float(np.quantile(means, .025)), float(np.quantile(means, .975))]


def expand(patterns):
    paths = []
    for pattern in patterns:
        matches = sorted(glob(str(pattern)))
        if not matches:
            raise ValueError('input pattern matched no files')
        paths.extend(Path(p) for p in matches)
    return list(dict.fromkeys(paths))


def fmt(value):
    return 'N/A' if value is None else f'{value:.3f}'


def safe(value):
    # IDs, never transcript or human comments. Keep Markdown table cells intact.
    return str(value).replace('|', '\\|').replace('\n', ' ').replace('\r', ' ').replace('`', '')


def report(answer_paths, arm_paths, acoustic_path=None, repeats=2000, seed=41):
    if repeats <= 0:
        raise ValueError('bootstrap repeats must be positive')
    humans = {str(p): latest(p, (*VERDICTS, 'unsure')) for p in answer_paths}
    if not humans:
        raise ValueError('at least one human answer file required')
    # First file is the reference, rather than implicitly inventing consensus.
    reference = next(iter(humans.values()))
    truth = {k: v['verdict'] for k, v in reference.items() if v['verdict'] != 'unsure'}
    unsure = sum(v['verdict'] == 'unsure' for v in reference.values())
    arms = {}
    for path in arm_paths:
        rows = latest(path)
        arm_names = {row.get('arm') for row in rows.values()}
        if not rows or len(arm_names) != 1 or not arm_names <= {'text', 'acoustic', 'listen'}:
            raise ValueError('each arm file must contain one nonempty valid arm')
        name = next(iter(arm_names))
        if name in arms:
            raise ValueError('multiple files for one arm; compare separate runs separately')
        if any(not finite(r.get('seconds')) or r['seconds'] < 0 or not isinstance(r.get('model'), str) for r in rows.values()):
            raise ValueError('invalid arm timing/model')
        arms[name] = rows
    acoustic = {}
    preprocessing = {}
    if acoustic_path is not None and Path(acoustic_path).exists():
        acoustic = {r['id']: r for r in read_jsonl(acoustic_path)}
        stats_path = Path(acoustic_path).with_suffix('.stats.json')
        if stats_path.exists():
            stats = json.loads(stats_path.read_text(encoding='utf-8'))
            preprocessing = {r['id']: r['seconds'] for r in stats.get('blocks', [])}
            if any(not finite(v) or v < 0 for v in preprocessing.values()):
                raise ValueError('invalid acoustic preprocessing timing')
    lines = ['# T41 판정 방식 비교', '',
             f'기준 답: `{safe(Path(next(iter(humans))).name)}` (첫 파일). 유효 정답 {len(truth)}개, unsure 제외 {unsure}개.',
             '사람이 둘 이상이어도 첫 파일을 기준으로 하고 사람 간 일치도를 별도로 보고한다.', '',
             f'95% 구간: 묶음 단위 복원추출 percentile bootstrap {repeats}회, seed={seed}. '
             '약 60개 표본의 탐색적 비교다. 같은 영상의 묶음 간 상관은 반영하지 않아 구간이 좁을 수 있다. '
             '정의 불가능한 지표는 N/A로 두며 해당 bootstrap 반복을 제외한다. 구간은 모집단 성능을 보장하지 않는다.', '']
    for name, rows in arms.items():
        ids = sorted(truth.keys() & rows.keys())
        y, p = [truth[i] for i in ids], [rows[i]['verdict'] for i in ids]
        score, ci = metrics(y, p), bootstrap(y, p, repeats, seed)
        mean_seconds, seconds_ci = mean_interval([rows[i]['seconds'] for i in ids], repeats, seed)
        lines += [f'## {name}', '', f'모델: {", ".join(sorted({safe(r["model"]) for r in rows.values()}))}. '
                  f'평가 {len(ids)}개 / 정답 중 미판정 {len(truth.keys() - rows.keys())}개 / 정답 밖 출력 {len(rows.keys() - reference.keys())}개. '
                  f'묶음당 시간 {fmt(mean_seconds)}초; 95% [{fmt(seconds_ci[0])}, {fmt(seconds_ci[1])}] '
                  '(배치 호출·검증 재시도 시간을 균등 배분, 대기·음향 전처리 제외).', '',
                  '| 지표 | 값 | bootstrap 95% 구간 | 유효 반복 |', '| --- | ---: | --- | ---: |']
        labels = {'accuracy': '3분류 일치율', 'kappa': 'Cohen κ', 'keep_recall': '남길 것 재현율',
                  'keep_precision': '남길 것 정밀도', 'story_recall': 'story 재현율', 'story_precision': 'story 정밀도'}
        for key in MEASURES:
            interval = ci[key]
            lines.append(f'| {labels[key]} | {fmt(score[key])} | [{fmt(interval["low"])}, {fmt(interval["high"])}] | {interval["valid"]}/{repeats} |')
        if name == 'acoustic':
            timed_ids = [i for i in ids if i in preprocessing]
            combined, combined_ci = mean_interval([rows[i]['seconds'] + preprocessing[i] for i in timed_ids], repeats, seed)
            lines += ['', f'B 음향 전처리+판정: 시각 통계가 있는 {len(timed_ids)}/{len(ids)}개, 묶음당 {fmt(combined)}초; '
                      f'95% [{fmt(combined_ci[0])}, {fmt(combined_ci[1])}]. 모델 로딩은 제외한다. '
                      '음향 .stats.json이 없으면 합산 시간은 N/A다.']
        lines += ['', '혼동 행렬: 행=사람 정답, 열=방식 판정.', '', '| 정답 \\ 판정 | story | moment | none |', '| --- | ---: | ---: | ---: |']
        for label, counts in zip(VERDICTS, score['confusion']):
            lines.append(f'| {label} | ' + ' | '.join(map(str, counts)) + ' |')
        lines += ['', '불일치 목록(전사·note·사람 comment 제외):', '', '| id | 정답 | 방식 |', '| --- | --- | --- |']
        lines += [f'| {safe(i)} | {truth[i]} | {rows[i]["verdict"]} |' for i in ids if truth[i] != rows[i]['verdict']]
        if all(truth[i] == rows[i]['verdict'] for i in ids):
            lines.append('| — | — | — |')
        lines.append('')
    if 'text' in arms and 'acoustic' in arms:
        a, b = arms['text'], arms['acoustic']
        ids = sorted(truth.keys() & a.keys() & b.keys())
        y, pa, pb = [truth[i] for i in ids], [a[i]['verdict'] for i in ids], [b[i]['verdict'] for i in ids]
        pair = paired(y, pa, pb, repeats, seed)
        lines += ['## A(text) 대 B(acoustic)', '', f'동일 id {len(ids)}개만 비교. 정확 McNemar 양측 p={fmt(pair["p"])}.', '',
                  '| | B 맞음 | B 틀림 |', '| --- | ---: | ---: |',
                  f'| A 맞음 | {pair["both"]} | {pair["only_a"]} |', f'| A 틀림 | {pair["only_b"]} | {pair["neither"]} |', '',
                  f'B−A 일치율 차이 {fmt(pair["delta"])}; paired bootstrap 95% [{fmt(pair["ci"][0])}, {fmt(pair["ci"][1])}].', '']
        def detected(i):
            row = acoustic.get(i, {})
            return any(row.get('block', {}).get('events', {}).get(key, 0) >= row.get('threshold', .3) for key in ('laughter', 'singing'))
        subsets = [('정답 moment', [i for i in ids if truth[i] == 'moment'])]
        if acoustic:
            subsets += [('웃음·노래 감지', [i for i in ids if i in acoustic and detected(i)]),
                        ('정답 moment 또는 웃음·노래 감지', [i for i in ids if truth[i] == 'moment' or detected(i)])]
            lines.append(f'음향 레코드 누락: 공통 id 중 {sum(i not in acoustic for i in ids)}개. 누락은 감지 여부 미상이다.')
        else:
            lines.append('음향 파일 없음: 사건 감지 부분집합은 계산하지 않았다. --acoustic 파일을 제공해야 한다.')
        lines += ['', '| 부분집합 | n | A 일치율 [95%] | B 일치율 [95%] |', '| --- | ---: | --- | --- |']
        for label, sub in subsets:
            values = []
            for arm in (a, b):
                sy, sp = [truth[i] for i in sub], [arm[i]['verdict'] for i in sub]
                point, interval = metrics(sy, sp)['accuracy'], bootstrap(sy, sp, repeats, seed)['accuracy']
                values.append(f'{fmt(point)} [{fmt(interval["low"])}, {fmt(interval["high"])}]')
            lines.append(f'| {label} | {len(sub)} | {values[0]} | {values[1]} |')
        lines.append('')
    if len(humans) > 1:
        lines += ['## 사람 간 일치도', '', '| 답 파일 쌍 | 유효 공통 n | unsure 때문에 제외 n | κ [95%] |', '| --- | ---: | ---: | --- |']
        for (an, a), (bn, b) in combinations(humans.items(), 2):
            common = a.keys() & b.keys()
            ids = sorted(i for i in common if a[i]['verdict'] != 'unsure' and b[i]['verdict'] != 'unsure')
            y, p = [a[i]['verdict'] for i in ids], [b[i]['verdict'] for i in ids]
            value, interval = metrics(y, p)['kappa'], bootstrap(y, p, repeats, seed)['kappa']
            lines.append(f'| {safe(Path(an).name)} / {safe(Path(bn).name)} | {len(ids)} | {len(common) - len(ids)} | {fmt(value)} [{fmt(interval["low"])}, {fmt(interval["high"])}] |')
    lines += ['', 'fake 또는 fake-listen은 실행 경로 확인용 고정 판정이다. 실제 모델 품질 비교에서 제외해야 한다.', '']
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--answers', nargs='+', required=True)
    parser.add_argument('--arms', nargs='+', required=True)
    parser.add_argument('--acoustic', default='.local/demo/acoustic.jsonl')
    parser.add_argument('--out', default='.local/demo/report.md')
    parser.add_argument('--bootstrap', type=int, default=2000)
    parser.add_argument('--seed', type=int, default=41)
    args = parser.parse_args(argv)
    try:
        content = report(expand(args.answers), expand(args.arms), args.acoustic, args.bootstrap, args.seed)
        atomic_text(Path(args.out), content)
    except Exception as error:
        print(f'compare failed ({type(error).__name__}); check judgment files')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
