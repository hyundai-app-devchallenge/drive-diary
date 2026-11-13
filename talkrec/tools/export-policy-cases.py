"""Export invented T56 cases with Python gate outputs, never private transcripts.

Run with the reference venv's python -B, passing --reference <model-create>.
All writes go beneath this worktree's talkrec/src/test/resources/policy.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    args = parser.parse_args()
    sys.dont_write_bytecode = True
    sys.path.insert(0, str(args.reference.resolve()))
    from synth.policy_cases import generate
    from policy.gate import AppSignals, PolicyGate

    target = Path(__file__).resolve().parents[1] / 'src/test/resources/policy'
    target.mkdir(parents=True, exist_ok=True)
    gate = PolicyGate()
    rows = generate(56)
    assert len(rows) == 2140 and all(r['synthetic'] is True for r in rows)
    cases = [dict(id=r['id'], lines=r['lines'], expected=gate.gate(r['lines'])) for r in rows]
    # Explicit indexing, empty-line, app-signal and Unicode/code-point boundary probes, also invented.
    extra = [[], [''], ['   ', '볼륨 올려', '별을 찾았어'], ['녹음', '', '하지 마'],
             ['녹음', '하지', '마'], ['글레오', '우리 인형을 찾았어'],
             ['별을 찾았어', '음악 틀어 줘'], ['별을 찾았어', '음악 틀어 줘', ''],
             ['녹음하지마😀'], ['녹음하지마𐐀'], ['녹음하𐐀지마'],
             ['𐐀𐐁 ＡＢＣ İ ΟΣ ① family'], ['별' * 129, '볼륨 올려']]
    for i, lines in enumerate(extra):
        for playback, dominant in [(None, None), (True, None), (True, True), (False, False)]:
            signals = AppSignals(playback, dominant)
            cases.append(dict(id=f'edge:{i}:{playback}:{dominant}', lines=lines,
                              signals=dict(playback_active=playback, external_audio_dominant=dominant),
                              expected=gate.gate(lines, signals=signals)))
    result = dict(invented_only=True, seed=56, t56_count=len(rows), rules_sha256=gate.rules_sha256, cases=cases)
    (target / 'cases.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    # The common normalize vectors are constructed Unicode probes, not speech transcripts.
    raw = (args.reference / 'common/normalize-vectors.json').read_bytes()
    (target / 'normalize-vectors.json').write_bytes(raw)
    print(json.dumps(dict(t56=len(rows), edge=len(cases)-len(rows), total=len(cases),
                          rules_sha256=gate.rules_sha256,
                          cases_sha256=hashlib.sha256((target / 'cases.json').read_bytes()).hexdigest())))


if __name__ == '__main__':
    main()
