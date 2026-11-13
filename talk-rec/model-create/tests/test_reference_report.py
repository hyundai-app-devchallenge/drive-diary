"""All reports use invented inputs and temporary human judgment files."""
import json

import pytest

from eval import real, reference as r, reference_report as report
from pipeline.label import BackendError, FakeBackend, UsageLimit
from test_reference import answer, blocks


def test_majority_binary_before_kind_and_keep_tie():
    def row(verdict, reason=''):
        return dict(verdict=verdict, none_reason=reason, unsure=False)
    repeats = [dict(a=row('story'), b=row('none', 'not_family'), c=row('none')),
               dict(a=row('moment'), b=row('none', 'negative'), c=row('moment')),
               dict(a=row('none'), b=row('story'))]
    votes, ties, kind_ties = report.majority(repeats)
    assert votes['a']['verdict'] == 'story'  # Combined keep wins, then kind tie -> story.
    assert votes['b']['verdict'] == 'none' and votes['b']['none_reason'] == 'not_family'
    assert votes['c']['verdict'] == 'moment' and votes['c']['tied']
    assert votes['c']['votes'] == 2 and ties == kind_ties == 1
    assert report.majority([]) == ({}, 0, 0)


def test_metrics_hand_calculation():
    rows = [dict(truth=t, pred=p) for t, p in [(True, True), (True, False), (False, True), (False, False)]]
    result = report.metrics(rows)
    assert result['n'] == 4
    assert [result[k] for k in ('tp', 'tn', 'fp', 'fn')] == [1] * 4
    assert all(result[k] == .5 for k in report.METRICS if k != 'kappa')
    assert result['kappa'] == 0
    assert report.metrics([dict(truth=True, pred=True)])['kappa'] is None
    assert all(report.metrics([])[k] is None for k in report.METRICS)
    all_keep = report.metrics([dict(truth=t, pred=True) for t in [True, True, False]])
    assert all_keep['recall'] == 1 and all_keep['precision'] == 2 / 3
    assert all_keep['f1'] == .8 and all_keep['none_recall'] == 0


def test_bootstrap_resamples_entire_sources_deterministically():
    rows = [dict(source=s, truth=True, pred=p) for s, p in [('a', True), ('a', True), ('b', False), ('b', False)]]
    sampled = []
    def check(sample):
        assert len(sample) == 4
        assert sum(r['source'] == 'a' for r in sample) in (0, 2, 4)
        sampled.append(sample)
        return report.metrics(sample)
    first = report.cluster_bootstrap(rows, 100, check)
    assert first == report.cluster_bootstrap(rows, 100)
    assert len(sampled) == 100
    assert first['recall']['low'] == 0 and first['recall']['high'] == 1
    assert first['kappa']['defined_samples'] < 100
    assert report.cluster_bootstrap([], 10)['accuracy']['low'] is None


def setup(tmp_path):
    (tmp_path / 'data').mkdir()
    (tmp_path / 'data/real-split.tsv').write_text(
        'video_id\tsplit\nabcdefghijk\tdev\nABCDEFGHIJK\tdev\nlmnopqrstuv\ttest\n', encoding='utf-8')
    (tmp_path / 'data/real-cast.tsv').write_text(
        'video_id\tcast\tby\tnote\nabcdefghijk\tchild_family\tperson\tPRIVATE_COMMENT\n'
        'ABCDEFGHIJK\tadult_family\tperson\tPRIVATE_COMMENT\n', encoding='utf-8')
    (tmp_path / 'prompts').mkdir()
    (tmp_path / 'prompts/judge.md').write_text('가상의 지시문', encoding='utf-8')
    directory = real.run_directory(tmp_path, 'v1')
    bs = blocks(2) + blocks(2, 'ABCDEFGHIJK') + blocks(1, 'lmnopqrstuv')
    real.write_objects(directory / 'blocks.jsonl', bs)
    humans = [dict(id=b['id'], keep='keep' if i < 2 else 'none', kind='story' if i < 2 else '',
                   none_reason='', unsure=i == 1, policy=[], comment='PRIVATE_COMMENT',
                   listened=True, labeler='human:vizatae', saved_at='2026-11-12') for i, b in enumerate(bs)]
    real.write_objects(real.judgment_path(directory, 'vizatae'), humans)
    return directory, bs


def judge(tmp_path, directory, bs, *, split='dev', repeat=3, fake=None):
    selected = r.load_blocks(tmp_path, directory, split)
    out = r.output_directory(tmp_path, directory, 'invented-' + split)
    if fake is None:
        response = json.loads(answer(2))
        response[1]['verdict'] = 'none'
        fake = FakeBackend([json.dumps(response) if batch[0]['source'] == 'yt:abcdefghijk'
                            else answer(len(batch), 'none')
                            for _ in range(repeat) for batch in r.batches(selected)])
    assert r.run(selected, fake, out, system='가상의 지시문', repeat=repeat, split=split) == 0
    return out


def test_compare_metrics_unsure_casts_baseline_output_and_no_transcripts(tmp_path):
    directory, bs = setup(tmp_path)
    out = judge(tmp_path, directory, bs)
    result = report.compare(tmp_path, directory, out, bootstrap=100)
    row = next(x for x in result['rows'] if x['scope'] == 'child_family'
               and x['method'] == 'majority' and x['human_unsure'] == 'included')
    assert row['n'] == 2 and row['recall'] == .5 and row['precision'] == 1
    assert row['accuracy'] == .5 and row['keep_ratio'] == .5
    certain = next(x for x in result['rows'] if x['scope'] == 'child_family'
                   and x['method'] == 'majority' and x['human_unsure'] == 'excluded')
    assert certain['n'] == 1 and certain['accuracy'] == 1
    baseline = next(x for x in result['rows'] if x['scope'] == 'selected'
                    and x['method'] == 'all_keep' and x['human_unsure'] == 'included')
    assert baseline['n'] == 4 and baseline['recall'] == 1 and baseline['precision'] == .5
    assert result['disagreements'] == [bs[1]['id']]
    assert result['by_scope']['selected']['stability']['all_same_ratio'] == 1
    assert result['by_scope']['selected']['stability']['mean_pairwise_kappa'] == 1
    assert result['by_scope']['selected']['none_reason'] == {'': 3}
    assert result['by_scope']['selected']['failed_batches'] == 0
    assert result['by_scope']['selected']['complete_blocks'] == 4
    assert not (directory / 'test-access.log').exists()
    text = (out / 'comparison.md').read_text(encoding='utf-8')
    assert '전부 남김' in text and 'child_family (주)' in text and '95%' in text
    assert all(b['id'] not in text for b in bs)
    for p in (out / 'comparison.json', out / 'comparison.md'):
        saved = p.read_text(encoding='utf-8')
        assert 'PRIVATE_COMMENT' not in saved and bs[0]['lines'][0]['text'] not in saved
    assert json.loads((out / 'comparison.json').read_text(encoding='utf-8')) == result
    again = report.compare(tmp_path, directory, out, cast=['child_family'], bootstrap=100)
    assert again['by_scope']['selected']['blocks'] == 2
    assert again['by_scope']['adult_family']['blocks'] == 2


def test_test_gate_before_human_reader_and_access_log_before_read(tmp_path, monkeypatch):
    directory, bs = setup(tmp_path)
    out = judge(tmp_path, directory, bs, split='test', repeat=1, fake=FakeBackend([answer(1, 'none')]))
    original, observed = real._read_judgments, []
    def spy(*args):
        assert (directory / 'test-access.log').exists()
        observed.append(args[2])
        return original(*args)
    monkeypatch.setattr(real, '_read_judgments', spy)
    with pytest.raises(ValueError, match='open-test'):
        report.compare(tmp_path, directory, out, split='test', bootstrap=10)
    assert not observed and not (directory / 'test-access.log').exists()
    result = report.compare(tmp_path, directory, out, split='test', open_test=True, bootstrap=10)
    assert observed == [{bs[-1]['id']}]
    assert result['by_scope']['selected']['blocks'] == 1
    assert '\tlibrary\tsplit=test' in (directory / 'test-access.log').read_text(encoding='utf-8')
    assert result['by_scope']['selected']['stability']['all_same_ratio'] is None


def test_cli_open_test_default_matches_real_all_semantics(tmp_path, monkeypatch):
    directory, bs = setup(tmp_path)
    out = judge(tmp_path, directory, bs, split='test', repeat=1, fake=FakeBackend([answer(1)]))
    monkeypatch.setattr(r, 'ROOT', tmp_path)
    assert r.main(['compare', '--run', 'v1', '--out-name', out.name, '--open-test', '--bootstrap', '10']) == 0
    value = json.loads((out / 'comparison.json').read_text(encoding='utf-8'))
    assert value['access_split'] == 'all'
    assert 'split=all' in (directory / 'test-access.log').read_text(encoding='utf-8')


def test_locked_cast_filter_and_log_failure_never_read_labels(tmp_path, monkeypatch):
    directory, bs = setup(tmp_path)
    out = judge(tmp_path, directory, bs, split='test', repeat=1, fake=FakeBackend([answer(1)]))
    monkeypatch.setattr(real, '_read_judgments', lambda *args: pytest.fail('read before gate'))
    with pytest.raises(ValueError):
        report.compare(tmp_path, directory, out, split='test', cast=['child_family'], bootstrap=10)
    def denied(*args):
        raise OSError('private')
    monkeypatch.setattr(real, 'atomic_write', denied)
    with pytest.raises(OSError):
        report.compare(tmp_path, directory, out, split='test', open_test=True, bootstrap=10)


def test_dev_ignores_invalid_test_human_judgment_via_load_labels(tmp_path, monkeypatch):
    directory, bs = setup(tmp_path)
    out = judge(tmp_path, directory, bs)
    rows = real.read_objects(real.judgment_path(directory, 'vizatae'))
    rows[-1] = dict(id=bs[-1]['id'], keep='invalid-test')
    real.write_objects(real.judgment_path(directory, 'vizatae'), rows)
    original, calls = real.load_labels, []
    def spy(*args, **kwargs):
        calls.append(kwargs)
        return original(*args, **kwargs)
    monkeypatch.setattr(real, 'load_labels', spy)
    report.compare(tmp_path, directory, out, bootstrap=10)
    assert calls == [dict(annotators=['vizatae'], split='dev', open_test=False, command='library')]


def test_partial_missing_votes_failed_counts_time_and_recovery(tmp_path):
    directory, bs = setup(tmp_path)
    selected = r.load_blocks(tmp_path, directory, 'dev')
    out = r.output_directory(tmp_path, directory, 'partial')
    fake = FakeBackend([answer(2), BackendError('private'), UsageLimit('private')])
    ticks = iter(range(30))
    assert r.run(selected, fake, out, system='가상의 지시문', retries=0, clock=lambda: next(ticks)) == 3
    result = report.compare(tmp_path, directory, out, bootstrap=10)
    stats = result['by_scope']['selected']
    assert stats['failed_batches'] == 1 and stats['pending_batches'] == 1
    assert stats['partial_vote_blocks'] == stats['missing_vote_blocks'] == 2
    assert stats['mean_seconds_per_block'] == .75
    assert stats['mean_seconds_per_block_repetition'] == .5
    assert stats['stability']['complete_blocks'] == 0
    # Resume repairs failures; no exhausted batch remains in the current count.
    assert r.run(selected, FakeBackend([answer(2)] * 5), out, system='가상의 지시문', retries=0) == 0
    result = report.compare(tmp_path, directory, out, bootstrap=10)
    assert result['by_scope']['selected']['failed_batches'] == 0
    assert result['by_scope']['selected']['exhausted_batches_ever'] == 1


@pytest.mark.parametrize('change', ['input', 'prompt'])
def test_comparison_rejects_stale_signature(tmp_path, change):
    directory, bs = setup(tmp_path)
    out = judge(tmp_path, directory, bs)
    if change == 'input':
        bs[0]['lines'][0]['text'] = '바뀐 가상 자료'
        real.write_objects(directory / 'blocks.jsonl', bs)
    else:
        (tmp_path / 'prompts/judge.md').write_text('바뀐 지시문', encoding='utf-8')
    with pytest.raises(ValueError, match='signature'):
        report.compare(tmp_path, directory, out, bootstrap=10)


def test_stability_binary_vs_verdict_and_pairwise_kappa():
    bs = blocks(2)
    repeats = [{bs[0]['id']: dict(verdict=a), bs[1]['id']: dict(verdict=b)}
               for a, b in [('story', 'none'), ('moment', 'none'), ('story', 'story')]]
    value = report.stability(bs, repeats, 20)
    assert value['all_same_ratio'] == .5 and value['verdict_all_same_ratio'] == 0
    assert [p['n'] for p in value['pairs']] == [2, 2, 2]
    assert value['mean_pairwise_kappa'] == pytest.approx(1 / 3)
