"""Human realism/agreement report and synthetic-vs-real block distributions."""
import argparse
from collections import defaultdict
import glob
import json
from pathlib import Path
from statistics import mean, median

from common.schema import validate_block
from demo.pleos_profile import distribution
from synth.io import read_jsonl, write_text
from synth.patterns import LABELS


def percent(numerator, denominator):
    return f'{100 * numerator / denominator:.1f}%' if denominator else '—'


def realism(rows):
    counts = {v: sum(r['real'] == v for r in rows) for v in ('yes', 'no', 'maybe')}
    return dict(n=len(rows), **counts, rate=counts['yes'] / len(rows) if rows else None)


def metrics(reviews, labels):
    joined = []
    for row in reviews:
        if row['id'] not in labels or row.get('real') not in ('yes', 'no', 'maybe') or row.get('verdict') not in LABELS:
            raise ValueError('invalid review or missing intended label')
        joined.append(dict(row, intended=labels[row['id']]))
    grouped = {}
    for field in ('verdict', 'pattern', 'level', 'negative', 'paired'):
        groups = defaultdict(list)
        for row in joined:
            value = (bool(row['intended'].get('pair_id')) if field == 'paired' else
                     row['intended'].get(field, False))
            groups[str(value)].append(row)
        grouped[field] = {k: realism(v) for k, v in sorted(groups.items())}
    matrix = {expected: {actual: 0 for actual in LABELS} for expected in LABELS}
    for row in joined:
        matrix[row['intended']['verdict']][row['verdict']] += 1
    correct = sum(matrix[k][k] for k in LABELS)
    return dict(overall=realism(joined), groups=grouped, matrix=matrix, correct=correct,
                agreement=correct / len(joined) if joined else None,
                no=[r for r in joined if r['real'] == 'no'],
                opened=sum(bool(r.get('opened_clean')) for r in joined),
                revealed=sum(bool(r.get('revealed_label')) for r in joined))


def quantile(values, p):
    if not values:
        return None
    values = sorted(values)
    location = (len(values) - 1) * p
    left = int(location)
    right = min(left + 1, len(values) - 1)
    return values[left] + (values[right] - values[left]) * (location-left)


def distributions(blocks):
    values = defaultdict(list)
    for b in blocks:
        validate_block(b)
        for field in ('n_turns', 'seconds', 'speech_share', 'median_gap'):
            values[field].append(b['features'][field])
        values['chars_per_turn'].extend(len(t) for t in b['turns'])
    return {field: dict(n=len(values[field]), mean=mean(values[field]) if values[field] else None,
                        median=median(values[field]) if values[field] else None,
                        p10=quantile(values[field], .1), p90=quantile(values[field], .9))
            for field in ('n_turns', 'seconds', 'chars_per_turn', 'speech_share', 'median_gap')}


def cell(value):
    return str(value).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('|', '\\|').replace('\r', '').replace('\n', '<br>')


def build_report(reviews, labels, synthetic, actual, profile=None, degradation=None):
    stats = metrics(reviews, labels)
    n = stats['overall']['n']
    lines = ['# 합성 대화 데모 검토', '',
             'demo_only: true. 이 보고서는 학습 데이터 승인이나 모델 성능 평가가 아닙니다.', '',
             f"판정 {n}건 / 고유 묶음 {len({r['id'] for r in reviews})}개. 실제 같음 비율은 yes / (yes + no + maybe)입니다.",
             '여러 검토자 파일을 주면 같은 묶음의 각 사람 판정을 별도 관측으로 집계합니다.', '',
             f"원본을 연 판정 {stats['opened']}건, 의도 라벨을 공개한 판정 {stats['revealed']}건(선입견 가능).", '',
             '## 실제 같음', '', '| 구분 | 값 | 판정 수 | 실제 같음 | 어색함 | 애매 | 실제 같음 비율 |',
             '|---|---|---:|---:|---:|---:|---:|']
    def append(group, value, row):
        lines.append(f"| {group} | {cell(value)} | {row['n']} | {row['yes']} | {row['no']} | {row['maybe']} | {percent(row['yes'], row['n'])} |")
    append('전체', '전체', stats['overall'])
    for field, name in (('verdict', '의도 라벨'), ('pattern', '패턴'), ('level', '열화 강도'),
                        ('negative', '부정'), ('paired', '대립쌍')):
        for value, row in stats['groups'][field].items():
            append(name, value, row)
    lines += ['', '## 사람 판정과 의도 라벨', '', f"일치율 {percent(stats['correct'], n)} ({stats['correct']}/{n}).", '',
              '| 의도 라벨 ↓ / 사람 판정 → | story | moment | none |', '|---|---:|---:|---:|']
    for label in LABELS:
        lines.append('| ' + label + ' | ' + ' | '.join(str(stats['matrix'][label][p]) for p in LABELS) + ' |')
    lines += ['', '## 어색함 코멘트', '', '| id | pattern | 코멘트 |', '|---|---|---|']
    for row in stats['no']:
        lines.append(f"| {cell(row['id'])} | {cell(row['intended']['pattern'])} | {cell(row.get('comment', '') or '(없음)')} |")
    if not stats['no']:
        lines.append('| — | — | 해당 판정 없음 |')
    lines += ['', '## 합성·실제 묶음 분포 비교', '',
              f'합성 {len(synthetic)}개, 실제 {len(actual)}개. 합성은 검토 표본이 아닌 전체 내보낸 묶음을 비교합니다.',
              '실제 파일은 분포 참고용으로만 읽으며 학습 입력으로 합치지 않습니다. 실제 파일이 없으면 —로 표시합니다.',
              '글자 수는 공백 포함 턴별 길이이며, 다른 지표는 묶음별 통계입니다. p10/p90은 선형 보간합니다.', '',
              '| 지표 | 데이터 | 표본 수 | 평균 | 중앙값 | p10 | p90 |', '|---|---|---:|---:|---:|---:|---:|']
    for title, blocks in (('합성', synthetic), ('실제', actual)):
        for field, row in distributions(blocks).items():
            numbers = ['—' if row[k] is None else f'{row[k]:.3f}' for k in ('mean', 'median', 'p10', 'p90')]
            lines.append(f"| {field} | {title} | {row['n']} | " + ' | '.join(numbers) + ' |')
    if profile:
        target = profile.get('summary', profile)
        measured = degradation or {}
        lengths = distribution([len(t) for b in synthetic for t in b['turns']])
        line_count = sum(len(b['turns']) for b in synthetic)
        pauses = distribution([b['start']-a['end'] for block in synthetic
                               for a, b in zip(block['timing'], block['timing'][1:])])
        def number(value):
            return '—' if value is None else f'{value:.3f}'
        lines += ['', '## Pleos 프로필과 합성 출력', '',
                  '줄 길이는 공백 포함입니다. 표본 크기와 원본 발화 구조가 달라 총 줄 수는 일치 목표가 아닙니다.',
                  '줄 수 비율의 분모는 실제에서는 참조 STT 줄, 합성에서는 깨끗한 화자 발화입니다.',
                  '참조 STT는 정답이 아니며, 짧은 답변 누락률은 작은 표본의 추정치입니다.', '',
                  '| 지표 | Pleos 프로필 | 합성 |', '|---|---:|---:|']
        comparisons = [('묶음 수', target.get('clips'), len(synthetic)),
                       ('줄 수', target['line_count'], line_count),
                       ('줄 수 비율', target['line_count_ratio'], line_count/measured['input_line_count']
                        if measured.get('input_line_count') else None)]
        comparisons.extend((f'줄 글자 수 {k}', target['chars_per_line'][k], lengths[k])
                           for k in ('min', 'median', 'p90', 'max'))
        comparisons.extend((f'줄 사이 간격(초) {k}', target.get('pause_seconds', {}).get(k), pauses[k])
                           for k in ('min', 'median', 'p90', 'max'))
        comparisons += [('짧은 답변 수', target.get('short_reply_count'), measured.get('short_reply_count')),
                        ('짧은 답변 누락률', target.get('short_reply_drop_rate'),
                         measured.get('short_reply_dropped', 0)/measured['short_reply_count']
                         if measured.get('short_reply_count') else None),
                        ('전체 발화 삭제 수', None, measured.get('drop_count'))]
        for title, expected, observed in comparisons:
            lines.append(f'| {title} | {number(expected)} | {number(observed)} |')
    return '\n'.join(lines) + '\n'


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--skim', nargs='+', default=[])
    p.add_argument('--labels')
    p.add_argument('--blocks')
    p.add_argument('--real-glob', default='.local/blocks/*.jsonl')
    p.add_argument('--out', default='.local/synth/demo/report.md')
    p.add_argument('--profile', default='.local/demo/pleos/profile.json')
    args = p.parse_args(argv)
    try:
        directory = Path(args.skim[0]).resolve().parent.parent if args.skim else (
            Path(args.blocks).resolve().parent if args.blocks else Path(args.out).resolve().parent)
        labels_path = Path(args.labels) if args.labels else directory / 'labels.jsonl'
        blocks_path = Path(args.blocks) if args.blocks else directory / 'blocks.jsonl'
        labels = read_jsonl(labels_path)
        if len({r['id'] for r in labels}) != len(labels):
            raise ValueError('duplicate label ids')
        reviews = []
        for path in args.skim:
            rows = read_jsonl(path)
            if len({r['id'] for r in rows}) != len(rows):
                raise ValueError('duplicate reviewer ids')
            reviews.extend(rows)
        real = [b for path in sorted(glob.glob(args.real_glob)) for b in read_jsonl(path)
                if not b.get('demo_only') and not b.get('source', '').startswith('synth:')]
        profile = json.loads(Path(args.profile).read_text(encoding='utf-8')) if Path(args.profile).exists() else None
        meta = blocks_path.parent / 'meta.json'
        metadata = json.loads(meta.read_text(encoding='utf-8')) if meta.exists() else {}
        degradation = metadata.get('stats')
        profile = profile if profile is not None else metadata.get('profile')
        text = build_report(reviews, {r['id']: r for r in labels}, read_jsonl(blocks_path), real, profile, degradation)
        write_text(args.out, text)
    except (ValueError, KeyError, OSError):
        p.exit(2, '보고서 생성 실패: 검토·라벨·묶음 경로와 형식을 확인하세요.\n')
    print(f'보고서 저장: {args.out}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
