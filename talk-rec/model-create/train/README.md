# T11 S0 / S1 training

Run from `talk-rec/model-create` with the existing `.venv`. No installation or
dependency edits. Raw snapshots, scores and checkpoints remain under `.local/`.

```powershell
New-Item -ItemType Directory -Force .local/train | Out-Null
.\.venv\Scripts\python.exe -B -m pytest tests/test_train_data.py tests/test_train_model.py tests/test_train_eval.py tests/test_train_sweep.py tests/test_train_report.py --basetemp .local/train/pt -p no:cacheprovider -q
.\.venv\Scripts\python.exe -B -m train.run --config train/configs/s1c3.json
.\.venv\Scripts\python.exe -B -m train.sweep --list train/configs/all.json --seeds 1 2 3 --snapshot .local/train/dsb --root .local/train/ms
.\.venv\Scripts\python.exe -B -m train.report --root .local/train/ms --baseline s1c3 --channel-run s1c3 --out .local/train/ms/report
```

Single runs retain the original no-overwrite behavior unless `--resume` is used.
Sweep runs use short directories such as `.local/train/ms/s1c3/s1`, independently
of legacy single-seed results. A complete run is skipped only when its resolved
config, actual data fingerprint, snapshot digest and recorded output files match.
Otherwise that seed directory is replaced and trained again. `run.json` is written
last as the completion marker. Code changes alone do not invalidate reuse: choose
a fresh `--root` after training implementation changes.
`--configs FILE FILE ...` also accepts an explicit experiment list. The sweep
stops on the first failed child, including CUDA OOM; no automatic retry loop.

| Experiment | Configs (each supports the same single-run command) |
| --- | --- |
| S0 vs S1, context 3 / 160 tokens | `s0c0.json`, `s0c3.json`, `s1c3.json` |
| Context 0 / 1 / 3 | `s1c0.json`, `s1c1.json`, `s1c3.json` |
| Length 160 / 256 | `s1c3.json`, `s1l256.json` |
| Training channels 25 / 50 / 100% | `s1p25.json`, `s1p50.json`, `s1c3.json` |
| Seed reproducibility | `s1c3.json`, `s1repeat.json` |

`base.json` contains every hyperparameter, fixed model revision and data path,
plus `seeds: [1, 2, 3]`. CLI `--seeds` overrides this list; configs without a list
fall back to their scalar `seed`. A single `train.run` still uses scalar `seed`
(or `--seed`). The full list contains nine configs, including the legacy
`s1repeat` reproducibility control: 27 seed runs.
Learning subsets use hash-ranked **labeled training channels**, ceil rounding,
and nested selection. Unlabeled channels cannot supply training examples; both
the full available-channel count and retained-channel count are reported. All
learning-curve runs have identical dev. Seeds and sampling order are fixed;
context dropout deterministically removes a random 0..3 positions per example
per epoch. Training uses balanced primary sampling with replacement; gold and
labeler weights enter the loss, not a second sampling multiplier.

Single `train.run` freezes a snapshot at `.local/train/ds1`; subsequent single
runs verify its hashes and reuse it. The multi-seed sweep hashes current inputs
(including added/deleted label files) and freezes a content-addressed sibling,
e.g. `dsb-<12 hex>`, once per data configuration for the entire sweep. Existing
snapshots are retained. Running the same sweep after live labels/windows/splits
change selects a new snapshot and replaces stale seed results. This prevents
ongoing labeling/split edits from changing comparisons within one sweep.
Snapshot creation fails if an input changes during reading. It
checks channel overlap, but does not regenerate windows or recompute splits.
Windows are authoritative for split membership; the manifest supplies channel
IDs. After labels/splits change, pick a **new** snapshot and new run names:

```powershell
.\.venv\Scripts\python.exe -B -m train.run --config train/configs/s1c3.json --snapshot .local/train/ds2 --name s1new
.\.venv\Scripts\python.exe -B -m train.run --config train/configs/s1c3.json --snapshot .local/train/ds3 --name s1multi --silver .local/labels/LABELER_A 1 --silver .local/labels/LABELER_B 0.5 --gold-weight 3
```

For a whole new sweep, reuse the command above: `--snapshot` is a prefix for
content-addressed snapshots in the sweep, and an exact frozen path in `train.run`.
A snapshot cannot be reused with different data settings.
`--prepare-only` freezes inputs and reports counts without training. Label
filenames are irrelevant to joins; percent-encoded `yt%3A*.jsonl` work. Reserved
`run.jsonl` / `unclear.jsonl` and `.chunks` are excluded. Each silver labeler
contributes its own weighted training row. Gold overrides **all** silver rows
on the same ID, including gold `skip`. Evaluation selects the highest configured
silver weight, with the first config entry breaking ties; gold always wins.
All selected `skip` rows and all missing-label windows are excluded and counted.

S1 follows DESIGN.md §3 input and §6 full fine-tuning. It imports
`pipeline.windows.truncate_tokens`, normalizes each turn with the common helper,
and uses context segment 0 / current plus final SEP segment 1. Input length
includes special tokens. No local truncation fallback exists. S0 reproduces the
old unique 2..4-gram binary feature scheme, underscore boundaries/spaces,
document frequency >=2, sqrt known-feature scaling, 512 raw-codepoint cap.
It uses deterministic L2 logistic fitting instead of the old fixture SGD loop;
no synthetic fixtures are added to real training. Context concatenates oldest
to newest then current, so the legacy 512-character cap can cut current text.
S0 has independently fitted heads on the same four target families.

DESIGN.md §4 defines primary `story>=2`, independent of exclusions. The explicit
T11 request adds a dominant binary head; DESIGN.md §9.2's soft `story/3` BCE
is retained as a scalar ordinal auxiliary (predicted score in 0..3). Kind and
exclude have five BCE outputs each. Head losses average flags, then apply sample
weights and configurable head weights. Training-only flag neg/pos weights cap
at 20 and floor at 1; unsupported flags use 1 and are reported as unsupported
through label support. Gold weight defaults to 3. AdamW, linear warmup/decay,
fp16 AMP, gradient clipping, dev-AP early stopping and CPU dev-NLL temperature
calibration follow §9. Numeric settings are implementation assumptions. Empty
dev is an error; no-positive dev explicitly falls back to dev NLL selection.
Single-class dev leaves calibration at identity. Temperature is bounded
0.05..20; no threshold is fitted. Generic Pleos augmentation / consistency loss
depends on T10 and is outside this T11 implementation.

`model.pt`, HF `config.json`, tokenizer files, `student.json` and
`temperature.json` form the S1 checkpoint. `train.engine.load_student(path)`
reloads all four heads for T15. `resolved.json`, `history.json`, `run.json` record
configuration, best epoch, calibration, parameter/file counts, versions,
training time, coverage, snapshot/code hashes and the actual eval commands.
Training time includes per-epoch validation/checkpointing; it excludes model
download/load, calibration and final T14 calls. S0 timing includes its imports.

Each run writes `dev.scores.jsonl` and invokes `python -m eval.run` to produce
`dev.eval.json` / `.md`; test equivalents exist only for retained labeled test
rows. Scores contain `id`, calibrated `story`, all five `exclude` probabilities.
T14 computes all metrics, including selection AP; training never reimplements
them. `train.report` reads representative metrics directly from `dev.eval.json`;
only channel bootstrap and per-channel AP use saved scores with the existing
harness AP function. The sweep automatically creates `report.md` and `report.json`.
Its `sweep.json` records exact membership, excluding stale experiments or seeds
from previous commands. Report generation can be repeated without training. Episode
pre-roll stays at the T14 default 3 for all variants to isolate model input.

DESIGN.md §8 requires >=10 dev and >=10 test channels and final Pleos results;
§10 requires provisional reporting until D recordings exist. Current silver
Whisper results on sparse labels cannot establish passage. T14 best-F1 and
recall at <=3 false/hour are descriptive dev sweeps, not adopted thresholds.
Unlabeled audio contributes exposure under T14 defaults and can understate
false/hour. Always report labeled/total, skipped, retained, positives, channels,
and test availability. Do not infer a learning curve from one channel subset
per fraction or generalize from a handful of dev channels.

CUDA deterministic algorithms, eager attention, no TF32, fixed CPU thread count,
zero data-loader workers and per-process seeds support same-machine reruns.
The sweep sets `PYTHONHASHSEED` before process start; data order never depends
on hash iteration. Numerical tolerance is measured in the Korean task report;
cross-GPU/PyTorch-version bitwise reproducibility is not assumed. GPU batches
default to 8. On OOM, inspect competing Whisper use and rerun explicitly with
a new name/config; the pipeline does not silently shrink batches.

## Reading the seed report

Rows are experiment names, never file paths. PR-AUC, best-F1, episode recall at
the harness false/hour budget and training seconds show mean ± **sample SD**
(ddof=1) over seeds. JSON records defined/total seed counts; undefined values are
not zero-filled. One seed has undefined SD. Coverage is labeled/total windows,
including skip; evaluated rows exclude skip/missing. JSON preserves complete
train/dev/test coverage and per-seed fingerprints. Every run prints actual
ID/label/weight/channel/split fingerprint, training counts and coverage before
training or skipping. Window content/timing changes additionally invalidate
reuse through the immutable snapshot digest.

For PR-AUC only, the report draws dev channels with replacement **1,000 times**
with fixed RNG seed **1729**. Each draw keeps every row of each selected channel,
including repeated channels, and computes AP separately for each fitted seed
before averaging. Identical channel draws are shared across all runs/seeds.
The 2.5/97.5 percentiles form the interval; paired differences subtract the
baseline's same-draw mean, with exactly matching seed sets required. Win share
is the fraction of defined draws with strict positive difference, **not a p-value**.
Draws without positives are omitted and counted. Dev with fewer than ten channels
always emits **UNRELIABLE INTERVAL**. These conditional channel intervals do not
combine training uncertainty, correct multiple comparisons or establish passage.

`--baseline` defaults to `s1c3` (S1/context 3/length 160). `--channel-run` chooses
one experiment for channel AP mean ± SD and positive-rate breakdown; channel IDs
are SHA-256 shortened to 12 hex characters. No channel titles or transcript text
enter the report or the new `dev.targets.jsonl` score companion.
The learning-curve table explicitly lists `s1p25`, `s1p50`, `s1c3` as 25/50/100%,
including actual training-row mean ± SD. Missing points say `not run`. Subsets
vary by seed, are nested within seed, and reflect incomplete label coverage as
well as channel composition. Mixed snapshots, dev memberships, harness settings
or within-experiment hyperparameters are rejected.

Tiny real smoke only (two configs × two seeds; does not run the full sweep):

```powershell
.\.venv\Scripts\python.exe -B -m train.sweep --configs train/configs/s0c0.json train/configs/s1c3.json --seeds 1 2 --epochs 1 --max-train-rows 32 --snapshot .local/train/bds --root .local/train/bs
.\.venv\Scripts\python.exe -B -m train.report --root .local/train/bs --baseline s1c3 --channel-run s1c3 --out .local/train/bs/report
.\.venv\Scripts\python.exe -B -m pytest --basetemp .local/train/pt -p no:cacheprovider -q --tb=short
```

`--max-train-rows` caps rows after channel selection (does not cap dev/test) and
marks reports **SMOKE ONLY**. Omit it and `--epochs 1` for full experiments.
Use separate roots for smoke and full runs.
