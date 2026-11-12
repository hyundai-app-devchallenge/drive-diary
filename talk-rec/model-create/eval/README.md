# T14 evaluation harness

Model-independent, offline Python evaluation. It reads labels and scalar scores;
it never loads a model, audio, or a pipeline module. Uses the existing Python 3.12
venv and installed scikit-learn; no dependency installation is needed.

The contracts are [TASKS.md, 공통 규약 and T14](../TASKS.md),
[common/schema.py](../common/schema.py), and [rubric.md](../rubric.md).
[DESIGN.md §3 (문제 정의)](../DESIGN.md#3-문제-정의) defines current-turn
classification and episode hysteresis; [§4](../DESIGN.md#4-라벨-체계) defines
`story >= 2` positives and separates story from exclusions;
[§5](../DESIGN.md#5-온디바이스-추론-구조) describes context and policy signals;
[§10](../DESIGN.md#10-평가와-통과-기준) sets episode recall **>= 0.75 at <= 3
false episodes/hour**, and requires slice reporting. Every result is marked
provisional, as §10 requires until D recordings establish final performance.

## Running

From `talk-rec/model-create`, in PowerShell:

```powershell
.\.venv\Scripts\python.exe -m eval.run --labels labels.jsonl --scores scores.jsonl --windows windows.jsonl --utterances-dir .local/utterances --pairs pairs.jsonl --meta slices.tsv --out .local/eval/result.json
.\.venv\Scripts\python.exe -m eval.run compare .local/eval/s0.json .local/eval/s1.json
New-Item -ItemType Directory -Force .local/eval | Out-Null
.\.venv\Scripts\python.exe -m pytest --basetemp .local/eval/pytest -p no:cacheprovider
```

`--pairs` and `--meta` are optional. `--out NAME` writes `NAME.json` and
`NAME.md`; `.json` is preserved when supplied. Parent directories are created.
`compare` prints a Markdown table to stdout with one column per result, including
all scalar metrics, slices, coverage, assumptions' configuration, and input paths.
It does not calculate statistical significance or assert that datasets/settings
are comparable. Missing metrics in a result are shown as `null`. Episode lists,
the sweep array, and the textual assumptions are available in the original files.

## Input formats and joins

Files are UTF-8 (a UTF-8 BOM is accepted). JSONL blank lines are ignored. Each
JSONL row must be an object. Duplicate IDs are errors, including skipped rows
and unused score/window rows. Malformed rows include the file and line in the
error. Invalid configuration, non-finite or out-of-range scores, missing input
files, and missing required IDs produce CLI exit code 2 with an error message.
Missing evaluated scores report the missing **count and IDs**, not an inner join.

Labels:

```jsonl
{"id":"yt:example#0","story":0,"kind":[],"exclude":["command"],"skip":false,"labeler":"human:tester"}
{"id":"yt:example#1","story":2,"kind":["recall"],"exclude":[],"skip":false,"labeler":"human:tester","note":"Directly recalled experience"}
```

`note` is optional here (defaults to an empty string for common validation).
The remaining fields obey `common.schema.validate_label`, including kind/story
consistency, known flags, and `human:<name>` / `llm:<model>` labeler syntax.
`kind` is one or more of `recall`, `moment`, `feeling`, `promise`, `family_fact`
when story is positive; the precise common-schema restrictions still apply.
Flags are `command`, `external`, `sensitive`, `optout`, `garbled`.

Scores:

```jsonl
{"id":"yt:example#0","story":0.1,"exclude":{"command":0.98}}
{"id":"yt:example#1","story":0.9}
```

`story` and each supplied exclusion probability must be finite numbers in [0,1]
(booleans are rejected). Exclusion scores may be absent or cover only some rows
or flags. Each flag's metrics use only rows where that flag has a score, with
`coverage_n` and `missing_n` reported explicitly. Unknown flag names are errors.

Windows:

```jsonl
{"id":"yt:example#0","source":"yt:example","split":"test","clip":0}
{"id":"yt:example#1","source":"yt:example","split":"test","clip":0,"context":["earlier turn"],"current":"current turn"}
```

`id` is `<source>#<nonnegative utterance index>`, as in the common schema.
`source`, `split` (`train`, `dev`, `test`) and nonnegative integer `clip` are
required. Full common windows are accepted; `context` and `current` are optional
for this timing-only consumer. Context texts are never copied to outputs.
`context` length, if supplied, identifies the immediately preceding timing rows
eligible for opening pre-roll; skipped/unlabeled members are removed without
replacing them with older turns. Optional boolean
`boundary_before: true` closes the previous episode and clears context before
this turn, representing a route change, microphone stop, or session boundary
from DESIGN.md §3/§5. Without event information, those events cannot be inferred.

Timing directory (searched recursively for `*.jsonl`):

```jsonl
{"source":"yt:example","utt":0,"start":0.0,"end":3.0,"text":"...","asr":"pleos","speaker":null}
{"source":"yt:example","utt":1,"start":4.0,"end":8.0,"text":"...","asr":"pleos","speaker":null}
```

Only `source`, `utt`, `start`, `end` are consumed. No dependency on the filename
when `source` is explicit. Minimal `{"start":0,"end":3}` rows are also accepted:
source is inferred from a matching source filename (`yt_example.jsonl` on
Windows, replacing `:` with `_`, or the literal source name), and `utt` defaults
to the zero-based nonblank record index. Explicit fields take precedence;
ambiguous inferred source names are errors. Repeated timing IDs across files
are errors. Evaluated timings require finite `0 <= start < end` seconds. All
timing rows in a selected source must satisfy this; unrelated explicit sources
are ignored. Timing texts, ASR names, and speaker fields are not required and
are not included in results. Supply ASR slice categories through the TSV.

Optional metadata TSV:

```tsv
id	language	asr kind
yt:example#0	ko	pleos
yt:example#1	ko-en	whisper
```

Headers must be unique and include `id`. Every other column is treated as a
categorical slice, including spaces in its name. Duplicate metadata IDs and
malformed column counts are errors. Missing rows or blank values become
`(missing)`. Reserved display values `(missing)` and `(none)` should not be
used as real metadata categories. Unused metadata IDs are ignored.

Optional minimal pairs:

```jsonl
{"pair":"p001","positive":"yt:example#1","negative":"yt:example#0"}
```

Pair identifiers must be unique. Both member IDs must have retained labels and
scores; otherwise an error reports the count and IDs. A pair with either member
marked `skip=true` is removed in full, before checking the other member.

`skip=true` labels are removed from utterance metrics, assembly and pre-roll,
gold episodes, safety denominators, pairs and slices. They require neither
scores nor windows nor timing. Their known timing intervals are removed from
audio exposure. Skipped turns do not count toward low-turn closure or create
an unlabeled boundary. Extra scores/windows are validated but do not become
evaluation samples. Unlabeled timing rows never become negatives or context.
Fully annotate the measured audio for meaningful false-per-hour results:
unlabeled audio contributes exposure under the default duration convention.

## Every metric definition

The default positive signal is `label.story >= 2` (DESIGN.md §4; rubric.md §3).
`--positive-story {1,2,3}` changes the cutoff everywhere, including gold episodes
and slice positive counts. Exclude labels do not change this signal (DESIGN.md
§4, rubric.md §2/§5). The classification threshold uses `score >= threshold`.

| Output | Definition |
| --- | --- |
| `n`, `positives`, `negatives` | Number of retained rows, binary-positive rows, and binary-negative rows in this group. |
| `reliable` | `positives >= min_positives` (default 5). This is a support warning, not a confidence interval; ranking metrics can still be undefined. |
| `pr_auc` | **Average precision**, not trapezoidal PR area: sum over descending distinct score thresholds of `(recall_i - recall_previous) * precision_i`. Equal scores enter as a group. No positives gives `null`. All-positive nonempty groups give 1. |
| `roc_auc` | Probability a uniformly selected positive scores above a uniformly selected negative, with ties receiving half credit. No positive or no negative gives `null`. |
| `threshold` | Configured utterance decision threshold, default 0.5, independent of episode thresholds. |
| `tp`, `fp`, `fn`, `tn` | At `threshold`: positive/predicted-positive, negative/predicted-positive, positive/predicted-negative, negative/predicted-negative counts. |
| `precision` | `tp / (tp + fp)`; zero when denominator is zero. |
| `recall` (utterance) | `tp / (tp + fn)`; zero when denominator is zero. |
| `f1` | `2*tp / (2*tp + fp + fn)`; zero when denominator is zero. |
| `best_f1`, `best_f1_threshold` | Maximum F1 across every distinct observed score threshold; ties select the **highest** threshold. An empty group gives 0 and `null`. With no positives but nonempty scores, F1 is 0 and the highest observed score wins the tie. This is descriptive tuning on the supplied data, not an independent validation result. |
| `utterance.exclude.<flag>` | The same binary metric bundle, with gold `flag in label.exclude` and that flag's score. Includes `coverage_n` (available scored rows) and `missing_n` (retained group rows without this flag score). The requested per-flag PR-AUC is its `pr_auc`. |
| `gold_n`, `predicted_n` | Counts of episodes assembled from the binary gold signal and story scores, respectively. |
| `found_n` | Gold episodes individually overlapped by at least one predicted episode for at least `overlap_fraction` of **gold duration** (default 0.5). Overlap must be positive. |
| `recall` (episode) | `found_n / gold_n`; `null` if no gold episodes. |
| `false_n` | Predicted episodes with **zero positive-duration overlap** with every gold episode in their source. Touching endpoints have zero overlap. A small positive overlap prevents a false episode even if it does not satisfy recall matching. |
| `duration_seconds` | Sum of known audio extent across evaluated sources, using the convention below and removing skipped interval unions. |
| `false_per_hour` | `false_n * 3600 / duration_seconds`; `null` for zero exposure. |
| `curve` | Episode metric bundle at each swept opening threshold. Gold episodes are held fixed. This is a collection of measured points; hysteresis need not produce a monotonic curve. |
| `budget.recall` | Highest observed recall among curve points with false/hour <= budget (default **3**, DESIGN.md §10). Ties choose lower false/hour, then higher opening threshold. No interpolation. No feasible defined point gives `null`. |
| `budget.tau_open`, `budget.tau_keep` | Thresholds of the selected feasible point; `null` if none exists. |
| `budget.meets_target` | Whether the selected recall is >= target (default **0.75**, DESIGN.md §10); `null` when undefined. This is only the provisional episode criterion. |
| `exclusion_safety.<flag>.n`, `inside_n`, `inside_share` | Flagged retained gold utterances, those with any positive-duration temporal overlap with a predicted episode, and `inside_n / n`. No flagged rows gives `null` share. Each flag is counted independently, whether the utterance is story-positive or negative. |
| `minimal_pairs.n`, `correct`, `skipped`, `accuracy` | Retained pairs, pairs with `score(positive) > score(negative)`, pairs removed for skip, and `correct / n`. Ties are wrong. No retained pairs gives `null` accuracy. |
| `counts.labels_n`, `evaluated_n`, `skipped_n` | All input label rows, retained label rows, skipped label rows. |
| `counts.sources_n`, `timing_n` | Sources represented by retained labels; all timing rows loaded for those sources (including skipped/unlabeled timing rows, for input auditing). These are provenance counts, not metric samples. |

Episode overlap is `max(0, min(end_a,end_b) - max(start_a,start_b))` seconds,
and is always zero across different sources. Episode intervals are half-open
temporal envelopes `[start,end)`; silence and internal low/skip gaps inside an
envelope remain elapsed time, while no skipped text/ID becomes a member.
Recall uses one predicted interval at a time, without summing intersections or
one-to-one assignment. A long prediction can find multiple gold episodes.

Safety is measured **before storage policy filtering**, not after applying
predicted exclusion scores. DESIGN.md §3/§4/§5 requires filtering excluded text
and retroactive optout discard for actual storage. This harness diagnoses raw
assembly exposure; it does not implement a storage engine or infer rule-gate
outputs. It therefore does not claim the separate §10 **0.95 command/optout
exclusion recall** criterion. Available flag classification recall is reported
in `utterance.exclude`, but combined rule/model policy needs its own evaluation.

## Assembly and configurable conventions

`eval.episodes.assemble_episodes(rows, config)` is pure: it sorts fresh references,
does not mutate rows, and returns new episode dictionaries (`source`, `start`,
`end`, member `ids`). Join results contain label fields plus `source`, `start`,
`end`, `score`, `split`, `clip`, and optional boundary/context fields. API callers
can also supply `skip`, `context_count`, `context_ids`, `boundary_before`,
`unlabeled_before`. `context_ids` restricts pre-roll to those IDs.

1. Sort by source, start, end, id. Never carry state across sources.
2. Close and reset context on source change, gap **>= 20 seconds** (current
   start minus previous end), explicit boundary, or the optional clip/unlabeled
   boundaries. The 20-second rule comes from DESIGN.md §3.
3. Open on `score >= tau_open`, including up to the preceding three eligible
   evaluated turns from that context (DESIGN.md §3/§5). A supplied window context
   length identifies preceding timing rows; skipped/unlabeled IDs are removed
   without substituting older turns. Absent text uses the configured count. Boundaries clear
   the buffer. Context can overlap an earlier episode; this is not deduplicated.
4. While open, `score >= tau_keep` extends the episode and resets low-turn count.
   Thus a dip between keep and open remains open. Lower turns are buffered: if a
   keep turn follows before closure, the intervening low turns join the envelope.
5. Close after **two consecutive** below-keep turns (DESIGN.md §3). By default
   pending trailing low turns are omitted at closure, a boundary, or EOF. A
   single pending low at EOF is also omitted. Optionally retain trailing lows.
6. Gold assembly runs the identical rules on `float(story >= positive_story)`,
   with opening/keep thresholds both 1. Binary gold does not inherit swept score
   thresholds. It uses the same pre-roll, low-turn count, gap and boundary rules.

| CLI option | Default | Meaning / contract |
| --- | --- | --- |
| `--threshold` | 0.5 | Utterance P/R/F1 threshold in [0,1]. |
| `--positive-story` | 2 | Label cutoff, 1/2/3. DESIGN.md §4 defines default 2. |
| `--tau-open`, `--tau-keep` | 0.5, 0.3 | Opening/keep score thresholds; `0 <= keep <= open <= 1`. DESIGN.md §3 gives their ordering, no numeric values. |
| `--low-turns` | 2 | Consecutive below-keep turns that close (positive integer), DESIGN.md §3. |
| `--silence-seconds` | 20 | Gap that closes (finite, >0), DESIGN.md §3. |
| `--context-turns` | 3 | Maximum opening pre-roll (nonnegative integer); DESIGN.md §3/§5. Zero disables pre-roll for diagnostics. |
| `--no-break-on-clip` | off | Default resets at clip changes; this option permits continuation. |
| `--bridge-unlabeled` | off | Default resets when timing shows an unannotated intervening turn. This option permits continuation; it still does not add unlabeled turns. |
| `--include-trailing-low` | off | Include buffered trailing low turns on closure/EOF. |
| `--overlap-fraction` | 0.5 | Required fraction of gold duration, in (0,1]. |
| `--duration-mode` | source | `source`: [0,max non-skipped timing end] per selected source; `evaluated_span`: [min evaluated start,max evaluated end] per source. Both include internal silence and subtract skipped interval unions clipped to the extent. |
| `--sweep` | 0, .05, ..., 1 | Space-separated opening thresholds; operating `tau_open` is always included. At each point, keep is `min(configured keep, swept open)`. |
| `--false-per-hour-budget` | 3 | Finite nonnegative budget, DESIGN.md §10 default. |
| `--recall-target` | 0.75 | Recall target in [0,1], DESIGN.md §10 default. |
| `--min-positives` | 5 | Positive support needed for a reliable marker (integer >=1). |

## Slices and outputs

Every utterance-level metric above, including available exclude-score metrics,
is recomputed within every slice. `kind` is multi-membership; a row with multiple
kinds participates in each, so slice counts do not necessarily sum to the total.
`(none)` contains kind-empty rows. All common kinds and splits are emitted even
when empty. Labeler type is the prefix `human` or `llm`, not a person's/model's
name. TSV columns appear as separate `meta:<column>` dimensions. Missing values
form a `(missing)` group. Metadata categories that occur only in skip rows are
removed. A slice below `min_positives` is retained with `n`, `positives`, all
defined metrics and `reliable=false`. An empty-positive group has AP/ROC `null`,
recall/F1 zero, and is not reliable. Reliability does not suppress any values.

The versioned JSON (`format_version: 1`) contains `inputs`, `counts`, `config`,
`provisional`, `assumptions`, `utterance`, `episodes` (including intervals, curve,
budget and safety), `minimal_pairs`, and `slices`. JSON never emits NaN/Infinity.
The companion Markdown contains tables for counts, every utterance and slice
metric, exclusions, episode metrics, budget, pairs, sweep, configuration and
assumptions. No raw transcript text is exported.

## Assumptions for designer confirmation

These are also recorded in every JSON and Markdown report, with the DESIGN.md
section consulted. Contract numbers are distinguished from implementation choices:

- **§3:** numeric opening/keep thresholds are unstated: use configurable 0.5/0.3.
- **§3:** closing low-turn membership is unstated: trim trailing lows, buffer
  recoverable dips; expose `include_trailing_low`.
- **§3/§5:** inputs need not contain context IDs. Infer preceding timing IDs from
  supplied context length; without context use available preceding evaluated
  turns. Expose `context_turns`. Missing, skipped or out-of-boundary turns are
  never imported or replaced by older turns when context is supplied.
- **§3/§8:** partial annotations and clip boundaries are unspecified: reset on
  clip changes/unlabeled intervening turns; expose both continuation switches.
  Route/microphone events require `boundary_before`; absent events are unknown.
- **§10:** matching is unspecified: use a single predicted overlap / gold
  duration >= configurable 0.5, and any positive overlap for false-episode tests.
- **§8/§10:** precise source duration is unavailable in the requested input:
  use known timing extent with silence, remove known skip intervals, expose
  `duration_mode`. Audio after the last transcript cannot be counted. Sparse
  annotations can understate false/hour; this is an exposure convention, not a
  claim that unlabeled intervals contain no gold episodes.
- **§10:** sweep and operating-point selection are unspecified: use the grid and
  keep clamping above, hold gold fixed, select an observed feasible point without
  interpolation, and return `null` if none is feasible. A score of exactly 1
  may prevent an empty-prediction point; no artificial threshold >1 is added.
- **§10:** metric ties, zero denominators and support cutoffs are unspecified:
  use grouped-score AP, half-credit ROC ties, zero classification ratios,
  `null` undefined ranking/episode/pair rates, highest-threshold F1 ties, strict
  pair wins, and configurable `min_positives=5`.
- **§4/§5/§10:** the requested safety metric means raw temporal inclusion before
  storage filtering/optout rollback. Gold story episodes retain story-positive
  excluded rows. Partial exclusion score coverage is allowed and counted.
- **§10:** results remain provisional. Synthetic fixture results validate
  arithmetic only; ASR/quantization/baseline comparisons require separate runs.
  The harness does not infer significance or dataset provenance.

## Validation

Tests in `tests/test_eval*.py` use hand-authored fixtures and explicit expected
values, never scikit-learn as an oracle. Examples include tied-score AP **5/6**
and ROC **7/8**, a joined fixture AP **34/45** and ROC **5/6**, hysteresis dip
recovery/closure, source isolation, exact 20-second silence closure, skip rows,
empty-positive slices, partial flag coverage, pair ties, missing/duplicate IDs,
no positives, empty input, JSON/Markdown CLI output, and two-result comparison.
