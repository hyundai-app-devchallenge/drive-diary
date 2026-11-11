# T05: causal windows and clips

Run from `model-create` with the existing environment:

```powershell
.\.venv\Scripts\python.exe -B -m pipeline.windows
.\.venv\Scripts\python.exe -B -m pipeline.windows --check
.\.venv\Scripts\python.exe -B -m pytest --basetemp .local/windows/pytest -p no:cacheprovider
```

The builder snapshots every `.local/utterances/*.jsonl`, validates utterances,
and sorts each source by `(start, utt)`. The record's `source` field is authoritative;
filenames are not parsed. Duplicate utterance IDs fail. It reads `split`, `channel_id`
and `lowdensity` from `data/sources.tsv` by the `yt:` source's video ID. Only rows
with **status downloaded** produce windows. Other utterance sources appear in
`stats.json.skipped_sources` with `reason: status:<status>`; missing rows use
`no manifest row`. It does not recompute channel hashes. The collector's T05b
70/15/15 hash and `data/split-overrides.tsv` pins determine the stored split;
run `pipeline/collect.py migrate-splits` before building after rule/pin changes.
The builder and checker validate pins (default: beside the manifest, override with
`--split-overrides PATH`) and fail if stored splits disagree. Invalid active
manifest rows, channel overlap and any
lowdensity channel outside test fail rather than silently changing splits.

Every eligible utterance produces one schema-validated window with ID `{source}#{utt}`.
Context walks at most three adjacent previous utterances backwards, stopping at
the first gap strictly greater than 20 seconds, then reverses to chronological
order. Context crosses ordinary clip boundaries. It never crosses a >20s gap.
Windows preserve the complete original text, including punctuation and case.

Clips are numbered from zero independently per source. A >20s gap forces a new
clip. Within each resulting run, a remaining span <=300s becomes the last clip.
Otherwise, choose the cut after a whole utterance whose clip span is 180..300s
and whose following silence gap is largest. Ties prefer a span nearest 240s,
then the earlier cut. Span is maximum speech end minus first speech start;
silence between clips belongs to neither span. A short final remainder is allowed
only at the source end or immediately before a forced gap. Inputs that cannot
be partitioned into legal whole-utterance clips fail; T03's <=12s utterances and
<=20s within-run gaps provide legal cut candidates.

Token accounting uses `monologg/koelectra-small-v3-discriminator`, already cached
in `.local/hf`; loading uses `local_files_only=True` and never downloads. Each turn passes through `common.normalize.normalize`
and is encoded without automatic special tokens. Total length is
`2 + len(current_tokens) + sum(len(turn_tokens) + 1 for context turns)`: one CLS,
one SEP per context turn and one final SEP. No padding is counted. Percentiles
use linear interpolation at `(n-1)*p`; empty token distributions have null
percentiles/max and zero over-budget share. Counts of dropped turns are mutually
exclusive (exactly 0, 1, 2 or 3); current truncation can also occur after context drops.

Training can reuse the pure `truncate_tokens(context_ids, current_ids, max_tokens=160)`
function. Inputs and outputs contain no special tokens. It drops oldest context
turns until the full input fits, then keeps the **last** `max_tokens-2` current
tokens if necessary. It returns immutable token tuples and truncation metadata
without mutating inputs. `model_input(context_texts, current_text, tokenizer)`
combines normalization, encoding, truncation and special-token assembly. It returns
`input_ids`, `token_type_ids` (1 for current and its SEP, 0 elsewhere), and
`attention_mask`, without padding.

`stats.json` contains windows/sources/channels/clips per split, context lengths,
token percentiles, over-budget share and truncation counts, source inventory count,
skipped sources and the build's consistency result. Reports contain no transcript
text. Split JSONLs and stats each replace their predecessor via a temporary file;
run only one window builder at a time.

`splits` counts generated windows/sources/channels/clips (all downloaded).
`downloaded_splits` separately counts **all** downloaded manifest sources/channels,
including those without utterances/windows. Rejected/candidate/failed rows never
inflate either set of counts. `warnings` lists dev/test splits whose **generated
window** channel count is below `--min-channels` (default 10, nonnegative; 0 disables
warnings). Each entry gives split, reason `channels_below_minimum`, channels and
minimum. Warnings do not fail builds/checks; they indicate evaluation coverage.

`--check` imports no Transformers and requires no network or tokenizer cache.
It checks schema, unique IDs, utterance resolution, complete coverage of currently
available downloaded utterances, exact full text, expected context, clips, manifest
splits, channel overlap, and lowdensity restrictions. Missing files or any error
return exit code 1; success returns 0. New transcription files arriving after a
build cause missing-window errors until the builder is rerun. It never writes to
utterance, audio or segment directories. CLI overrides `--utterances`, `--manifest`
and `--output` (alias `--out-dir`) support isolated synthetic fixtures.

While a labeler reads `.local/windows`, build and check a separate snapshot:

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.windows --out-dir .local/windows-t05b/
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.windows --out-dir .local/windows-t05b/ --check
```

Migration does not rewrite existing window/label files. Old windows can retain
rejected sources and previous splits until deliberately rebuilt; coordinate that
replacement with the labeling run. Gold channels need pins before future migrations.
