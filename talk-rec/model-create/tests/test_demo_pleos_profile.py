"""Invented inputs only; never read or snapshot local transcript files."""
import json

import pytest

from demo.pleos_profile import (aligned_segments, build_profile, distribution,
                                edits, main, repeated_segments)
from synth.io import write_jsonl


def run(lines, number=0):
    return dict(id=f'yt:IOlxH67P1JM@{number}', config='fixture', lines=lines,
                seconds=10, avg_logprob=None, no_speech_prob=None)


def line(text, start=0, end=1):
    return dict(text=text, start=start, end=end)


def numeric_tree(value):
    if isinstance(value, dict):
        return all(numeric_tree(v) for v in value.values())
    if isinstance(value, list):
        return all(numeric_tree(v) for v in value)
    return value is None or type(value) in (int, float)


def test_alignment_does_not_duplicate_reference_for_chopped_lines():
    p = [line('가나', 0, 1), line('다라', 1, 2)]
    r = [line('가나다라', 0, 2)]
    result = build_profile([run(p)], [run(r)])
    summary = result['summary']
    assert summary['line_count_ratio'] == 2
    assert summary['char_alignment']['reference_chars'] == 4
    assert summary['char_alignment']['mismatch_rate'] == 0
    assert len(result['clips'][0]['segments']) == 1
    assert numeric_tree(result)


def test_edit_accounting_and_unmatched_time_segments():
    assert edits('가나다', '가마') == dict(reference_chars=3, pleos_chars=2,
                                       substitution=1, deletion=1, insertion=0)
    assert edits('가', '가나다')['insertion'] == 2
    segments = aligned_segments([line('마바', 3, 4)], [line('가나다', 0, 1)])
    assert sum(s['deletion'] for s in segments) == 3
    assert sum(s['insertion'] for s in segments) == 2


def test_short_reply_requires_text_correspondence_and_time_overlap():
    p = [run([line('응', 0, 1), line('다른 말', 2, 3)])]
    r = [run([line('응', 0, 1), line('네', 2, 3), line('어', 4, 5)])]
    summary = build_profile(p, r)['summary']
    assert summary['short_reply_count'] == 3
    assert summary['short_reply_dropped'] == 2
    assert summary['short_reply_drop_rate'] == pytest.approx(2/3)


def test_pairing_duplicates_empty_and_distributions():
    p, r = run([line('가나다라')]), run([line('가나다라')])
    result = build_profile([p, run([], 1)], [r, run([], 2)])
    assert result['pleos_unpaired'] == result['reference_unpaired'] == 1
    with pytest.raises(ValueError):
        build_profile([p, p], [r])
    with pytest.raises(ValueError):
        build_profile([], [r])
    assert distribution([1, 5, 9]) == dict(n=3, min=1, median=5, p90=8.2, max=9)
    assert distribution([])['median'] is None
    assert repeated_segments([line('가나다라'), line('가나다라')]) == 1
    assert repeated_segments([line('가나 가나 가나')]) == 1
    assert repeated_segments([line('아니 아니 저기')]) == 0


def test_cli_numeric_only_and_sanitized_errors(tmp_path, capsys):
    p, r, out = [tmp_path / n for n in ('p.jsonl', 'r.jsonl', 'profile.json')]
    write_jsonl(p, [run([line('숫자로만 남길 허구 발화')])])
    write_jsonl(r, [run([line('숫자로만 남길 허구 발화')])])
    args = ['--pleos', str(p), '--ref', str(r), '--out', str(out)]
    assert main(args) == 0
    assert numeric_tree(json.loads(out.read_text(encoding='utf-8')))
    assert numeric_tree(json.loads(capsys.readouterr().out))
    p.write_text('PRIVATE invalid', encoding='utf-8')
    with pytest.raises(SystemExit) as error:
        main(args)
    assert error.value.code == 2
    assert 'PRIVATE' not in capsys.readouterr().err
