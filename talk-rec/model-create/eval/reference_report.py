"""Binary reference metrics with deterministic source-cluster bootstrap."""
from __future__ import annotations

from collections import Counter, defaultdict
from itertools import combinations
import random

from . import real
from .reference import (REASONS, Store, load_blocks, locked, read_json,
                        write_json)
from pipeline.label import atomic_text, digest

SEED = 52
METRICS = ('recall', 'precision', 'f1', 'none_recall', 'none_precision', 'none_f1',
           'accuracy', 'kappa', 'keep_ratio')


def majority(repeats):
    grouped = defaultdict(list)
    for rows in repeats:
        for identity, row in rows.items():
            grouped[identity].append(row)
    result, ties, kind_ties = {}, 0, 0
    for identity, votes in grouped.items():
        keep_votes = [v for v in votes if v['verdict'] != 'none']
        keep = len(keep_votes) * 2 >= len(votes)
        tied = len(keep_votes) * 2 == len(votes)
        ties += tied
        reason = ''
        if keep:
            stories = sum(v['verdict'] == 'story' for v in keep_votes)
            kind_ties += stories * 2 == len(keep_votes)
            verdict = 'story' if stories * 2 >= len(keep_votes) else 'moment'
        else:
            verdict = 'none'
            counts = Counter(v['none_reason'] for v in votes if v['verdict'] == 'none')
            reason = max(REASONS, key=lambda value: counts[value])
        result[identity] = dict(verdict=verdict, none_reason=reason, votes=len(votes), tied=tied)
    return result, ties, kind_ties


def metrics(rows):
    """Rows contain truth/prediction booleans; zero denominators are undefined."""
    n = len(rows)
    tp = sum(r['truth'] and r['pred'] for r in rows)
    tn = sum(not r['truth'] and not r['pred'] for r in rows)
    fp = sum(not r['truth'] and r['pred'] for r in rows)
    fn = sum(r['truth'] and not r['pred'] for r in rows)
    def ratio(a, b):
        return a / b if b else None
    accuracy = ratio(tp + tn, n)
    chance = ((tp + fn) * (tp + fp) + (tn + fp) * (tn + fn)) / n ** 2 if n else 1
    return dict(n=n, tp=tp, tn=tn, fp=fp, fn=fn,
                recall=ratio(tp, tp + fn), precision=ratio(tp, tp + fp),
                f1=ratio(2 * tp, 2 * tp + fp + fn),
                none_recall=ratio(tn, tn + fp), none_precision=ratio(tn, tn + fn),
                none_f1=ratio(2 * tn, 2 * tn + fp + fn), accuracy=accuracy,
                kappa=(accuracy - chance) / (1 - chance) if chance < 1 else None,
                keep_ratio=ratio(tp + fp, n))


def percentile(values, p):
    if not values:
        return None
    values = sorted(values)
    index = (len(values) - 1) * p
    left = int(index)
    right = min(left + 1, len(values) - 1)
    return values[left] + (values[right] - values[left]) * (index - left)


def cluster_bootstrap(rows, count, statistic=metrics, keys=METRICS):
    clusters = defaultdict(list)
    for r in rows:
        clusters[r['source']].append(r)
    sources = sorted(clusters)
    samples, rng = {key: [] for key in keys}, random.Random(SEED)
    if sources:
        for _ in range(count):
            sample = [r for _ in sources for r in clusters[rng.choice(sources)]]
            values = statistic(sample)
            for key in keys:
                if values[key] is not None:
                    samples[key].append(values[key])
    return {key: dict(low=percentile(values, .025), high=percentile(values, .975),
                      defined_samples=len(values)) for key, values in samples.items()}


def stability_values(rows):
    count = len(rows)
    all_same = sum(len(set(r['binary'])) == 1 for r in rows)
    verdict_same = sum(len(set(r['verdicts'])) == 1 for r in rows)
    pairs = []
    if rows:
        for a, b in combinations(range(len(rows[0]['binary'])), 2):
            pairs.append(metrics([dict(truth=r['binary'][a], pred=r['binary'][b]) for r in rows])['kappa'])
    defined = [p for p in pairs if p is not None]
    return dict(all_same_ratio=all_same / count if count else None,
                verdict_all_same_ratio=verdict_same / count if count else None,
                mean_pairwise_kappa=sum(defined) / len(defined) if defined else None)


def stability(blocks, repeats, samples):
    complete = [b for b in blocks if all(b['id'] in r for r in repeats)]
    rows = [dict(source=b['source'], verdicts=[r[b['id']]['verdict'] for r in repeats],
                 binary=[r[b['id']]['verdict'] != 'none' for r in repeats]) for b in complete]
    # At least two repetitions are needed to measure fluctuation.
    keys = ('all_same_ratio', 'verdict_all_same_ratio', 'mean_pairwise_kappa')
    if len(repeats) < 2:
        return dict(complete_blocks=len(complete), **{k: None for k in keys}, pairs=[], ci95={})
    pairs = []
    for a, b in combinations(range(len(repeats)), 2):
        common = [x['id'] for x in blocks if x['id'] in repeats[a] and x['id'] in repeats[b]]
        value = metrics([dict(truth=repeats[a][i]['verdict'] != 'none',
                              pred=repeats[b][i]['verdict'] != 'none') for i in common])
        pairs.append(dict(a=a + 1, b=b + 1, n=len(common), kappa=value['kappa']))
    return dict(complete_blocks=len(complete), **stability_values(rows), pairs=pairs,
                ci95=cluster_bootstrap(rows, samples, stability_values, keys))


def operational(blocks, repeats, votes, calls):
    ids = {b['id'] for b in blocks}
    selected_calls = [c for c in calls if ids.intersection(c['ids'])]
    latest = {(c['repeat'], c['batch']): c for c in selected_calls}
    attempted = {(c['repeat'], i) for c in selected_calls for i in c['ids'] if i in ids}
    attempted_ids = {i for _, i in attempted}
    voted = [votes[i] for i in ids if i in votes]
    seconds = sum(c['seconds'] * len(ids.intersection(c['ids'])) / len(c['ids']) for c in selected_calls)
    usage = Counter()
    for c in selected_calls:
        usage.update(c['token_usage'])
    return dict(blocks=len(blocks), voted_blocks=sum(i in votes for i in ids),
                complete_blocks=sum(all(i in r for r in repeats) for i in ids),
                partial_vote_blocks=sum(i in votes and votes[i]['votes'] < len(repeats) for i in ids),
                missing_vote_blocks=sum(i not in votes for i in ids),
                ties=sum(votes[i]['tied'] for i in ids if i in votes),
                failed_batches=sum(c['exhausted'] for c in latest.values()),
                exhausted_batches_ever=sum(c['exhausted'] for c in selected_calls),
                pending_batches=sum(c['status'] in ('limit', 'interrupted') for c in latest.values()),
                attempted_block_repetitions=len(attempted), inference_seconds=seconds,
                attempted_blocks=len(attempted_ids),
                mean_seconds_per_block=seconds / len(attempted_ids) if attempted_ids else None,
                mean_seconds_per_block_repetition=seconds / len(attempted) if attempted else None,
                prediction_keep_ratio=sum(v['verdict'] != 'none' for v in voted) / len(voted) if voted else None,
                token_usage=dict(usage),
                none_reason=dict(Counter(votes[i]['none_reason'] for i in ids
                                         if i in votes and votes[i]['verdict'] == 'none')),
                repeat_none_reason={str(n): dict(Counter(r[i]['none_reason'] for i in ids
                                            if i in r and r[i]['verdict'] == 'none'))
                                    for n, r in enumerate(repeats, 1)})


def compare(root, directory, out, *, annotator='vizatae', split='dev', open_test=False,
            cast=None, bootstrap=2000):
    if bootstrap < 1:
        raise ValueError('bootstrap: configuration')
    # This is the mandatory path, including access logging BEFORE any test labels.
    real.human_name(annotator)
    blocks, labels = real.load_labels(root, directory, annotators=[annotator], split=split,
                                     open_test=open_test, command='library')
    with locked(out):
        config = read_json(out / 'config.json')
        if split not in (config['split'], 'all'):
            raise ValueError('split mismatch: config.json')
        judged = load_blocks(root, directory, config['split'])
        system = (root / 'prompts/judge.md').read_text(encoding='utf-8')
        if config['input_hash'] != digest(judged) or config['prompt_hash'] != digest(system):
            raise ValueError('signature mismatch: config.json')
        allowed = {b['id'] for b in judged}
        blocks = [b for b in blocks if b['id'] in allowed]
        calls = Store.read_calls(out)
        repeats = [{r['id']: r for c in calls if c['repeat'] == n for r in c['records']}
                   for n in range(1, config['repeat'] + 1)]
        votes, ties, kind_ties = majority(repeats)
        human = labels[annotator]
        selected = real.select_cast(blocks, real.read_cast(root), cast)
        cohorts = {'selected': selected}
        cohorts.update({c: [b for b in blocks if b['cast'] == c] for c in real.CASTS})
        report = dict(version=3, model=config['model'], annotator=annotator, access_split=split,
                      judged_split=config['split'], cast_filter=cast, primary_cast='child_family',
                      bootstrap=dict(unit='source', seed=SEED, samples=bootstrap, confidence=.95),
                      ties=ties, kind_ties=kind_ties, rows=[], by_scope={})
        for scope, bs in cohorts.items():
            stats = operational(bs, repeats, votes, calls)
            stats['human_judgments'] = sum(b['id'] in human for b in bs)
            stats['stability'] = stability(bs, repeats, bootstrap)
            report['by_scope'][scope] = stats
            for exclude in (False, True):
                common = [b for b in bs if b['id'] in human and b['id'] in votes
                          and (not exclude or not human[b['id']]['unsure'])]
                for method in ('majority', 'all_keep'):
                    rows = [dict(source=b['source'], truth=human[b['id']]['keep'] == 'keep',
                                 pred=method == 'all_keep' or votes[b['id']]['verdict'] != 'none')
                            for b in common]
                    report['rows'].append(dict(scope=scope, method=method,
                                               human_unsure='excluded' if exclude else 'included',
                                               **metrics(rows), ci95=cluster_bootstrap(rows, bootstrap)))
        common_ids = {b['id'] for b in selected} & set(human) & set(votes)
        report['disagreements'] = sorted(i for i in common_ids
                                        if (human[i]['keep'] == 'keep') != (votes[i]['verdict'] != 'none'))
        report['kind_disagreements'] = sorted(i for i in common_ids
                        if human[i]['keep'] == 'keep' and votes[i]['verdict'] != 'none'
                        and human[i]['kind'] != votes[i]['verdict'])
        write_json(out / 'comparison.json', report)
        atomic_text(out / 'comparison.md', markdown(report))
    return report


def markdown(report):
    def fmt(value):
        return '—' if value is None else f'{value:.3f}'
    def cell(row, key):
        value, ci = fmt(row[key]), row['ci95'][key]
        return value + (f" [{fmt(ci['low'])}, {fmt(ci['high'])}]" if ci['low'] is not None else '')
    lines = ['# 기준선 v3 비교', '',
             f"분할: {report['judged_split']} · 주 수치: child_family · 반복 다수결, 동률 keep.",
             '대괄호는 출처 단위 재표집 95% 구간(시드 52)이다. —는 정의할 수 없는 값이다.',
             '전부 남김은 기준선과 사람이 모두 판정한 같은 묶음에서 비교한다. unsure 제외는 사람 기준이다.',
             'selected는 --cast 선택이며, 구성별 표는 허용된 판정 분할 전체를 보여 준다.', '',
             '| 구성 | 방법 | 사람 unsure | n | keep 재현율 | 정밀도 | F1 | none 재현율 | 정밀도 | F1 | 정확도 | κ | 남길 비율 |',
             '|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for row in report['rows']:
        scope = row['scope'] + (' (주)' if row['scope'] == 'child_family' else '')
        method = '기준선' if row['method'] == 'majority' else '전부 남김'
        unsure = '포함' if row['human_unsure'] == 'included' else '제외'
        lines.append('| ' + ' | '.join([scope, method, unsure, str(row['n'])]
                                      + [cell(row, k) for k in METRICS]) + ' |')
    lines += ['', '| 구성 | 묶음 | 투표 있음 / 전회 완료 | 부분 투표 | 동률 | 실패 배치 | 전체 초/묶음 | 초/묶음·반복 | 전회 keep/none 일치 | 전회 verdict 일치 | 평균 쌍별 κ |',
              '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for scope, stats in report['by_scope'].items():
        st = stats['stability']
        cells = [scope, str(stats['blocks']), f"{stats['voted_blocks']} / {stats['complete_blocks']}",
                 str(stats['partial_vote_blocks']), str(stats['ties']), str(stats['failed_batches']),
                 fmt(stats['mean_seconds_per_block']), fmt(stats['mean_seconds_per_block_repetition'])]
        for key in ('all_same_ratio', 'verdict_all_same_ratio', 'mean_pairwise_kappa'):
            ci = st['ci95'].get(key, {})
            cells.append(fmt(st[key]) + (f" [{fmt(ci['low'])}, {fmt(ci['high'])}]" if ci.get('low') is not None else ''))
        lines.append('| ' + ' | '.join(cells) + ' |')
    lines += ['', '| 구성 | none_reason | 다수결 수 |', '|---|---|---:|']
    for scope, stats in report['by_scope'].items():
        for reason in REASONS:
            lines.append(f"| {scope} | {reason or '(빈 값)'} | {stats['none_reason'].get(reason, 0)} |")
    lines += ['', '시간은 재시도·실패 호출을 포함하고 한도 대기는 제외한다. 부분 투표는 성공한 반복만으로 집계한다.',
              '실패 배치는 마지막 호출이 재시도 소진인 배치 수다. 재개 후 성공하면 현재 실패 수에서 빠진다.',
              '흔들림의 평균 κ는 모든 반복이 완료된 공통 묶음을 쓴다. 각 쌍 자체의 공통 묶음 수·κ는 JSON에 있다.',
              '출처가 적으면 구간이 불안정하며, 출처 하나뿐이면 재표집 구간이 한 값으로 줄어든다.', '']
    return '\n'.join(lines)
