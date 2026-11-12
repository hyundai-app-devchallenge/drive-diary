"""End-to-end local commands with empty synthetic ASR and generated PCM."""
import json
import wave

import numpy as np

from demo import acoustic, compare, run_arm
from demo.data import atomic_text, jsonl, read_jsonl


def test_fake_cli_end_to_end_and_resume(tmp_path):
    audio = tmp_path / 'fixture.wav'
    with wave.open(str(audio), 'wb') as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(16000)
        out.writeframes(np.zeros(16000 * 3, dtype='<i2').tobytes())
    sample = tmp_path / 'sample.jsonl'
    utterances = tmp_path / 'utterances'
    atomic_text(sample, jsonl([dict(id='s@0', source='s', block=0, start=0, end=3, gap_before=None,
                                  utts=[2, 4], turns=['', ''], prefix=[], origin='synthetic',
                                  audio=dict(path=str(audio), start=0, end=3))]))
    atomic_text(utterances / 's.jsonl', jsonl([dict(source='s', utt=i, start=start, end=end, text='')
                                           for i, start, end in [(2, 0, 1), (4, 1.5, 2.5)]]))
    common = ['--sample', str(sample), '--utterances', str(utterances)]
    events = tmp_path / 'acoustic.jsonl'
    assert acoustic.main([*common, '--out', str(events), '--offline', '--backend', 'fake']) == 0
    stats = json.loads(events.with_suffix('.stats.json').read_text(encoding='utf-8'))
    assert stats['backend'] == 'fake' and stats['gpu_peak_allocated_bytes'] == 0
    assert stats['blocks'][0]['seconds'] >= 0
    for arm in ['text', 'acoustic', 'listen']:
        output = tmp_path / 'arms' / f'{arm}.jsonl'
        args = [*common, '--arm', arm, '--backend', 'fake', '--out', str(output), '--acoustic', str(events)]
        assert run_arm.main(args) == 0
        before = output.read_bytes()
        assert run_arm.main(args) == 0
        assert output.read_bytes() == before
        assert read_jsonl(output)[0]['verdict'] == 'none'
    gold = tmp_path / 'answers.jsonl'
    atomic_text(gold, jsonl([dict(id='s@0', verdict='none')]))
    report = tmp_path / 'report.md'
    assert compare.main(['--answers', str(gold), '--arms', str(tmp_path / 'arms' / '*.jsonl'),
                         '--acoustic', str(events), '--out', str(report), '--bootstrap', '20']) == 0
    assert 'A(text) 대 B(acoustic)' in report.read_text(encoding='utf-8')
    assert run_arm.main([*common, '--arm', 'listen', '--backend', 'agy', '--out', str(tmp_path / 'rejected.jsonl')]) == 1
