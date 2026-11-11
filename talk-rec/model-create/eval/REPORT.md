# T14 implementation report

Implemented the model-independent evaluation harness. All requested completion
criteria passed. No commits, branches, pushes, dependency changes, or edits to
pipeline/common/data/source utterances were made.

## Files created

- `eval/__init__.py`: package marker.
- `eval/io.py`: validated JSONL/TSV readers, timing joins, skip handling and audio exposure.
- `eval/metrics.py`: utterance metrics, per-flag metrics, slices and minimal pairs.
- `eval/episodes.py`: pure configurable hysteresis, gold assembly, matching, sweep and safety.
- `eval/run.py`: evaluation CLI, JSON/Markdown reporting and side-by-side compare.
- `eval/README.md`: formats, every metric definition, parameters, DESIGN.md citations and assumptions.
- `eval/REPORT.md`: this report.
- `tests/test_eval_metrics.py`: 14 hand-computable metric/edge-case tests.
- `tests/test_eval_episodes.py`: 13 assembly/episode/safety tests.
- `tests/test_eval_cli.py`: 19 join/validation/CLI tests, including fixture generation.

## Commands and results

Commands were run from `talk-rec/model-create` with the existing venv:

```powershell
.\.venv\Scripts\python.exe -c "import numpy, sklearn, pytest; print(numpy.__version__, sklearn.__version__, pytest.__version__)"
.\.venv\Scripts\python.exe -m pytest tests/test_eval_metrics.py tests/test_eval_episodes.py tests/test_eval_cli.py --basetemp .local/eval/pytest -p no:cacheprovider
New-Item -ItemType Directory -Force .local/eval | Out-Null
.\.venv\Scripts\python.exe -m pytest tests/test_eval_metrics.py tests/test_eval_episodes.py tests/test_eval_cli.py --basetemp .local/eval/pytest -p no:cacheprovider
.\.venv\Scripts\python.exe -m pytest --basetemp .local/eval/pytest -p no:cacheprovider
```

Installed versions: numpy 2.2.6, scikit-learn 1.7.2, pytest 8.4.2.
Read-only inspection used `rg`, `Get-Content`, `Get-ChildItem`, and `git status`;
all source edits were made with `apply_patch`.

The initial targeted run passed 27 tests and had 18 setup errors because
`.local/eval` did not yet exist. After creating that parent, 44 tests passed and
one test fixture failed because it removed a label still referenced by its pair
file. The fixture was corrected. A further context/skip regression test was
added. The final **full suite passed: 508 tests in 13.25 seconds**, including
**46 evaluation tests** and the concurrent windows tests available at run time.

The CLI test runs these subprocesses using `sys.executable` (the same venv):

```text
python -m eval.run --labels <fixture>/labels.jsonl --scores <fixture>/scores.jsonl --windows <fixture>/windows.jsonl --utterances-dir <fixture>/utterances --pairs <fixture>/pairs.jsonl --meta <fixture>/meta.tsv --context-turns 0 --sweep .8 .9 --out <fixture>/result.json
python -m eval.run compare <fixture>/result.json <fixture>/second.json
```

Both exited 0. JSON and Markdown were created and checked; comparison contained
both result columns and slice metrics. Fixture artifacts are under the specified
`.local/eval/pytest` temporary tree. A missing-scores CLI run also verified exit 2,
the missing count, and no traceback.

Hand-computed checks include AP 5/6 with tied scores, ROC-AUC 7/8, joined fixture
AP 34/45 and ROC-AUC 5/6, precision/recall/F1 2/3, best F1 3/4 at threshold 0.2,
episode recall 1/2, one false episode in 80 seconds = 45/hour, budget recall 1/2
at opening threshold 0.9 with zero false episodes, sensitive inclusion share 1,
command inclusion share 0, and pair accuracy 1/2 with a tied pair counted wrong.
These are arithmetic fixture results, not model-performance claims.

## Assumptions for designer confirmation

Full definitions and corresponding switches appear in
[README.md](README.md#assumptions-for-designer-confirmation), and each generated
result includes an `assumptions` list. Sections consulted and choices made:

1. **DESIGN.md §3:** opening/keep values are unspecified: configurable 0.5/0.3.
2. **§3:** buffer recoverable low-score dips; trim trailing low turns unless configured otherwise.
3. **§3/§5:** infer preceding timing IDs from supplied context length; without context, use up to three eligible preceding evaluated turns. Never substitute older turns for skipped context members.
4. **§3/§8:** reset at clip changes and intervening unlabeled turns by default; both are configurable. External route/microphone/session boundaries require explicit `boundary_before` input.
5. **§10:** matching uses one prediction's overlap / gold duration >= configurable 0.5; any positive overlap prevents counting a prediction as false. No one-to-one matching or pooled overlaps.
6. **§8/§10:** timing exposure uses [0,max non-skipped end] per selected source, subtracting known skipped interval unions. `evaluated_span` is configurable. Unknown trailing audio cannot be inferred; sparse labels can understate false/hour.
7. **§10:** sweep defaults to 0..1 by 0.05 plus the operating point, clamping keep to opening. Gold stays fixed. Select the best measured feasible recall; ties favor fewer false episodes then higher opening threshold; no interpolation; no feasible point gives null.
8. **§10:** grouped-score AP, half-credit ROC ties, zero undefined classification ratios, null undefined ranking/episode/pair rates, highest-threshold best-F1 ties, strict pair wins, and configurable minimum positive support of five.
9. **§4/§5/§10:** safety measures assembly inclusion before storage filtering/optout rollback. Gold story episodes retain excluded story-positive turns. Partial exclude score coverage is explicit; combined rule/model exclusion policy is not certified.
10. **§10:** every run is provisional. ASR, baseline and quantization deltas need separate comparable runs; synthetic test fixtures demonstrate arithmetic only.

No requested done criterion remains failing. The real-data performance criteria
in DESIGN.md cannot be established from the hand-authored fixtures.
