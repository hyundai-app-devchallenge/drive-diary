# T22 conversation blocks (schema v2)

Run from `model-create` with the existing environment. No downloads or dependency
changes are needed. Tokenizer loading is local-only from `.local/hf`.

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.blocks
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.blocks --check
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.blocks --allow-prescreened --out-dir .local/blocks-pre/
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.blocks --allow-prescreened --out-dir .local/blocks-pre/ --check
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.blocks sweep --allow-prescreened --out-dir .local/blocks-pre/ --json .local/blocks-pre/sweep.json
New-Item -ItemType Directory -Force .local/blocks | Out-Null
.\.venv\Scripts\python.exe -X utf8 -B -m pytest --basetemp .local/blocks/pt -p no:cacheprovider -q --tb=no
```

`--utterances`, `--manifest`, `--approvals`, `--split-overrides`, and `--out-dir`
(alias `--output`) allow isolated snapshots. Default inputs are
`.local/utterances/*.jsonl`, `data/sources.tsv`, `data/approved.tsv`, and
`data/split-overrides.tsv`. The source field is authoritative; filenames are not
parsed. Utterances are validated, grouped by source and sorted by `(start, utt)`.
Duplicate source/utterance indices fail.

Only downloaded manifest sources with an `approve` row and a nonempty human
`decided_by` enter any split. Whitespace is stripped from the decider and
case-insensitive `claude` is treated as prescreening. Other sources and their
reasons appear in `stats.json.skipped_sources`. Approval columns are read by name;
both four-column and five-column (comment) headers work, including reordered
headers. Malformed, changed, or temporarily inaccessible files are retried once
after 50 ms, then fail closed. Each invocation uses one approval snapshot.

`--allow-prescreened` also admits Claude approvals for **distribution analysis
only, never training**. It requires an output directory different from
`.local/blocks` (resolved path comparison) even for sweep and check. Stats and
optional sweep JSON carry `prescreened_included: true`; prescreened sweep JSON
cannot be written inside the default block directory. A sweep without `--json`
writes no output files and does not create its output directory. The parent of
an optional JSON path must already exist.

Manifest splits are reused, never rehashed. As in v1, active downloaded sources
must have a valid split/channel, channels cannot span splits, all members of a
lowdensity channel must be in test, and stored splits must respect channel pins.
These checks retain v1's scope over downloaded sources even when approval would
exclude one. The tiny manifest/pin readers are independent copies: this module
imports neither `collect.py` nor `windows.py`.

Closing settings are `--silence 20`, `--max-seconds 90`, `--max-turns 12`;
`--prefix-gap 60` controls context expiry. Adding the next utterance closes the
current block if the adjacent gap is **>=** silence, the proposed block span is
**>** max-seconds, or the block already has max-turns turns. Equality at the length
limit is allowed. A single long utterance stays intact. Span uses the maximum end
timestamp minus the first start. Block numbering starts at zero per source and
IDs are `{source}@{block}`. Text is stored in full, without normalization or
truncation. `utts` contains original utterance indices, not list positions.

`gap_before` is the next block's first start minus the preceding block's **last
utterance** end. It is null for the first block. Prefix is the preceding block's
last two original turns (or its only turn), in order, empty for the first block or
a gap strictly greater than prefix-gap. It never crosses sources. The extra
`closed_by` metadata is mutually exclusive: silence wins ties, then length, then
turns; without a next utterance the reason is end_of_source. This attribution
order affects reason shares, not partition boundaries.

Numeric features:

| Field | Definition |
| --- | --- |
| n_turns | Number of original block utterances, before token fitting |
| seconds | Maximum end minus first start, in seconds |
| speech_share | Sum of utterance durations / seconds; zero for zero span |
| median_gap | Median of adjacent next-start minus previous-end inside the block; zero for one turn |
| gap_before | Raw gap capped above at 600 seconds; 600 for null/first block |

Overlapping timestamps are not silently clamped: speech_share may exceed one,
and gaps may be negative. Feature summaries use linear percentiles at `(n-1)*p`.
An empty distribution has null percentiles/max and zero shares.

`fit_tokens(prefix_token_lists, turn_token_lists, limit=320)` is pure. Inputs have
no special tokens. Its immutable result gives retained `prefix`, `turns`, original
`prefix_indices`, `turn_indices`, dropped counts and fallback/truncation flags.
The budget is `1 + sum(len(turn)+1)` across all retained prefix/block turns:
one CLS and one SEP per turn, no padding. It drops oldest prefix turns first,
then original block interior turns from the centre outward, alternating sides.
Centre ties remove left first: for six turns, removal order is 2,3,1,4; for seven,
3,2,4,1,5. Endpoints survive while any interior removal can suffice. If the two
endpoints alone still exceed the limit, the earliest is removed to retain the
latest. If that single turn still exceeds the budget, only its last `limit-2`
tokens survive. Limit 2 permits an empty content tuple between CLS/SEP; smaller
or noninteger limits and empty block inputs fail.

`model_input(prefix_texts, turns_texts, tokenizer, limit=320)` independently
normalizes each turn using `common.normalize.normalize`, encodes without special
tokens, calls fit_tokens, and assembles CLS/SEPs, attention_mask and token_type_ids.
Only block turns and their SEP tokens have segment 1; CLS/prefix have segment 0.
All stored turns and numeric features continue to describe the complete block.

`--token-limit` changes fitting and its branch counters. Stats always separately
report the original share over 320 and over the configured limit. Percentiles and
max also describe original input lengths, including prefix and special tokens.
`branches.unchanged` counts fitting-free blocks; `drop_prefix`, `drop_middle`,
`drop_earliest`, `truncate_last` count blocks triggering each branch and can
overlap. `prefix_turns_dropped`/`middle_turns_dropped` count removed whole turns.

Stats include per-split blocks/sources/channels, closing reason counts/shares,
feature distributions, token distributions and fitting counters, excluded
sources, settings, and consistency results. Generated dev/test channels below
10 generate warnings, not failures. JSONLs and stats individually use atomic
replace; the whole directory is not a transaction. Run one builder per output
directory and avoid consuming it until that build finishes.

`--check` is offline and imports no Transformers. It reconstructs the expected
partition and compares all emitted fields, identities, original indices/text,
prefixes, features, closing reasons, eligibility, manifest splits/pins, channel
separation, and utterance coverage. It catches overlap, loss and unresolved
indices, and returns 1 on errors. It reuses closing settings from saved stats;
the supplied approval mode must match the saved mode. With no saved stats it
uses CLI closing settings. Changes to input transcripts, manifest or approvals
require a rebuild. Checker output and stats contain no transcript text.

Sweep evaluates 10/20/30 silence x 60/90/120 length x 8/12/16 turns. Blocks/hour
uses the sum of eligible sources' manifest duration_s (including silence), falling
back to the last maximum utterance end if duration is absent. Single-turn and
closing reason shares use block counts as denominator. `closed_*_share` column
names distinguish closing attribution from the `silence` setting. Sweep leaves
built JSONLs/stats untouched. Full measured results and known test-suite issues
are recorded in `../docs/model-create/T22-blocks.md`.
