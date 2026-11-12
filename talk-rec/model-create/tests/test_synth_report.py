import pytest

from synth.degrade import to_block
from synth.io import write_jsonl
from synth.report import build_report, distributions, main, metrics, quantile


def fixture():
    labels = {
        'synth:1@0': dict(verdict='story', pattern='kid_recount', level=0),
        'synth:2@0': dict(verdict='moment', pattern='song', level=1),
        'synth:3@0': dict(verdict='none', pattern='driving_only', level=2),
    }
    reviews = [dict(id='synth:1@0', real='yes', verdict='story', comment='', opened_clean=False, revealed_label=False),
               dict(id='synth:2@0', real='no', verdict='none', comment='끊김이 어색함 | <예시>', opened_clean=True),
               dict(id='synth:3@0', real='maybe', verdict='none', comment='')]
    block = to_block(1, [dict(t=0, dur=2, text='본문은 보고서에 쓰지 않음'), dict(t=3, dur=1, text='둘')], 0)
    return reviews, labels, block


def test_report_exact_rates_matrix_and_comment_privacy():
    reviews, labels, block = fixture()
    stats = metrics(reviews, labels)
    assert stats['overall']['rate'] == 1/3
    assert stats['agreement'] == 2/3
    assert stats['matrix']['moment']['none'] == 1
    assert stats['groups']['level']['1']['rate'] == 0
    assert stats['groups']['verdict']['story']['rate'] == 1
    assert stats['groups']['pattern']['driving_only']['maybe'] == 1
    text = build_report(reviews, labels, [block], [])
    assert '33.3%' in text and '66.7%' in text
    assert 'synth:2@0' in text and '끊김이 어색함 \\| &lt;예시&gt;' in text
    assert block['turns'][0] not in text
    assert '실제 0개' in text


def test_distribution_hand_computed():
    _, _, block = fixture()
    stats = distributions([block])
    assert stats['n_turns']['mean'] == 2
    assert stats['seconds']['median'] == 4
    assert stats['speech_share']['p90'] == .75
    assert stats['median_gap']['mean'] == 1
    assert stats['chars_per_turn']['mean'] == sum(len(t) for t in block['turns'])/2
    assert quantile([0, 10], .1) == 1
    assert distributions([])['seconds']['n'] == 0
    assert distributions([])['seconds']['p90'] is None


def test_cli_reads_real_blocks_and_infers_paths(tmp_path):
    reviews, labels, block = fixture()
    skim = tmp_path / 'skim/tester.jsonl'
    write_jsonl(skim, reviews)
    write_jsonl(tmp_path / 'labels.jsonl', [dict(id=k, **v) for k, v in labels.items()])
    write_jsonl(tmp_path / 'blocks.jsonl', [block])
    real = dict(block, id='yt:fixture@0', source='yt:fixture', demo_only=False)
    write_jsonl(tmp_path / 'real/test.jsonl', [real, block])
    out = tmp_path / 'report.md'
    assert main(['--skim', str(skim), '--real-glob', str(tmp_path / 'real/*.jsonl'), '--out', str(out)]) == 0
    text = out.read_text(encoding='utf-8')
    assert '실제 1개' in text
    assert '0.750' in text


def test_empty_reviews_and_unknown_id():
    _, labels, _ = fixture()
    assert metrics([], labels)['agreement'] is None
    with pytest.raises(ValueError):
        metrics([dict(id='unknown', real='yes', verdict='story')], labels)
