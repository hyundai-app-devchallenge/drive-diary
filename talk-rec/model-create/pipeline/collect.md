# YouTube collection (T02 / T02b / T02c / T02d)

The target is unscripted Korean conversation among family, friends, or couples inside
a car. Discovery collects metadata only. Downloading requires an explicit approval.
Run these commands in PowerShell from `talk-rec/model-create`, using the existing
`.venv` and `../dev-tools/.local/deno/deno.exe`. No dependencies need changing.

```powershell
# Stage 1: no new audio; up to 150 new IDs across these two calls
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py discover --search-results 15 --max-candidates 115
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py expand --per-channel 5 --max-candidates 35
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py review-sheet
# Designer edits data/approved.tsv using .local/collect/review.tsv
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py apply-decisions
# Stage 2: only approved manifest IDs (do not run this for the T02b discovery task)
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py download --max-videos 10 --max-hours 5
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py --check
```

Legacy invocations without a command now default to `discover`. `download` never
searches. A missing approval file authorizes zero downloads.

For evaluation collection of **children + family inside a car** (DESIGN §1), use
`--min-duration 60` for both discovery and expansion. Evaluation uses only the
human-approved usable ranges, so the training-density eight-minute lower bound
is unnecessary. The discovery/expansion default remains **480 seconds**.

```powershell
.\.venv\Scripts\python.exe -m pipeline.collect discover --queries data/queries-kids.txt --min-duration 60
.\.venv\Scripts\python.exe -m pipeline.collect expand --channel-query "드라이브" --min-duration 60
```

## Inputs and manifest migration

`data/queries.txt` is UTF-8, one `search: QUERY`, `channel: URL`, or `video: URL` per
line. Blank lines and full-line `#` comments are ignored; inline comments are not
supported. Bare queries and YouTube URLs are accepted too. Prefer `/videos` for
channels. With no positional inputs, discover reads the default file. Specify
`--queries data/queries.txt` to combine a file with positional inputs.

```powershell
.\.venv\Scripts\python.exe -B pipeline/collect.py discover 'search: family drive' 'channel: https://www.youtube.com/@CHANNEL/videos' 'video: https://www.youtube.com/watch?v=VIDEO_ID'
.\.venv\Scripts\python.exe -B pipeline/collect.py discover --lowdensity-video VIDEO_ID
# Offline: backfill appended metadata fields and refilter existing candidates
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py refilter
```

`sources.tsv` keeps its original 13 columns and row order. Four columns are appended:
`description`, `channel_subscriber_count`, `view_count`, `query`. Description is the
first 300 characters after whitespace/control-character sanitizing. Unavailable
counts remain blank. Query records the first finding input, including its source
prefix; rediscovery through expansion preserves it. Old headers remain readable;
mutating commands save the extended header atomically. `--check` never migrates or
writes. Full metadata stays in `.local/collect/metadata-{id}.json`.

`refilter` uses only local metadata caches, preserves original metadata cells, and
applies automatic rejects to candidate rows. It recovers first-source provenance
from the default queries' old 5-result caches and current `--search-results` caches;
without a matching cache, query remains blank. Downloaded rows are preserved unless
manually rejected. Output `removed` counts unique videos; `rejected_by_rule` counts
all matches, so one video can count toward multiple rules.

`--search-results` limits results per input. `--max-candidates` limits newly recorded
unique IDs for this invocation, including automatically rejected IDs. Existing rows
do not consume that limit. Discovered IDs are recorded durably before full metadata
is fetched, then enriched in round-robin input order. Interrupted metadata work is
resumed on rerun. This stage never requests audio.

Search/channel results (including expansion and cached results) whose `id` is not
an 11-character YouTube video ID (`A-Z`, `a-z`, `0-9`, `_`, `-`) are skipped before
recording candidates or fetching full metadata. This includes playlists, channels,
and shelves. The `discover` and `expand` JSON summaries report `skipped_non_video`
(zero when none), summed across processed inputs/channels; skipped entries do not
consume candidate caps or count as failures. Explicit invalid `video:` inputs
still stop with exit 2.

## Automatic rejects

The designer-editable pattern table is **AUTO_REJECT_PATTERNS** in `collect.py`.
Each reason maps to a metadata field and a case-insensitive regex. It covers:

- `broadcaster_channel`: broadcaster/news channel names and official entertainment
  brands, including MBC, KBS, SBS, JTBC, tvN, YTN, Yonhap, Channel A, TV Chosun,
  ENA, Mnet, Korean news names, EBS, Diggle, Pick it, and Obunsunsak. The exact Korean
  spellings and all patterns are in the table.
- `compilation`: Korean compilation/legend/full-version/replay/highlight/Obunsunsak
  title markers, `.zip`, and `#shorts`.
- `music_or_performance`: Music/Korean music category, or the table's existing
  English/Korean music, playlist, concert, performance, cover, karaoke, BGM, OST,
  MV/M/V, instrumental, fancam, and related title patterns.

Other reason codes are `live_or_upcoming` (active/scheduled/post-live processing),
`live_archive` (completed streams), `premiere` (explicit premiere metadata or a
non-live video's release_timestamp), `duration_too_long` (over three hours),
`duration_too_short` (under eight minutes by default), `duration_unknown`,
`not_korean`, and `channel_id_missing`. Korean preference accepts `ko` language on
the video/audio formats or a Hangul syllable in the title. Title language alone is
only a heuristic. All matched reasons are appended to note, separated by semicolons.

`--min-duration`, `--max-duration`, `--allow-non-korean`, and `--allow-music` retain
existing filter controls. The absolute three-hour ceiling and live/premiere rejects
always apply. The old `--reject-live-archives` flag is accepted for compatibility;
archives are now always excluded. Approval does not bypass automatic rejects.
T40 exception, updated by T02d: a named human `approve` (nonempty `decided_by`,
not `claude`) uses a **60-second** minimum, or the explicit `--min-duration` if it
is lower. This common filter also applies to discovery/refilter and download.
All other rejection rules and download limits remain in force; Claude approvals
retain the original minimum (480 seconds by default).

To restore previous **short-only** rejections to the review candidates offline,
without requiring approval first:

```powershell
.\.venv\Scripts\python.exe -m pipeline.collect retry-short VIDEO_ID_01 VIDEO_ID_02
.\.venv\Scripts\python.exe -m pipeline.collect retry-short VIDEO_ID_01 --min-duration 30
```

`retry-short` requires explicit IDs, status `rejected`, only the rejection reason
`duration_too_short`, and a finite duration from `--min-duration` (default **60**)
through **10800** seconds inclusive. A human `reject` in `approved.tsv` or a
`manual:` rejection note blocks revival. Provenance and other non-rejection notes
are preserved; only the exact `duration_too_short` token is removed. It validates
**all IDs before any change**: if any fail, nothing is saved, every failing ID and
reason is printed to stderr and in summary JSON (`retried: []`, `errors`, `failed`),
and the command exits 2. Success prints JSON with `retried` IDs and `failed: 0`.
It only changes status to `candidate` and removes the short rejection note; it
does not download, grant approval, apply other decisions, or move audio. Review
and human approval are still needed for these short videos to download at the
normal discovery minimum. A lower custom revival threshold must also be supplied
to download via `--min-duration` when the video is shorter than 60 seconds.
Use `refilter --min-duration 60` when refiltering revived, unapproved candidates;
the default 480-second filter would reject them again.

The older, approval-required command remains available with its original rules:

```powershell
.\.venv\Scripts\python.exe -X utf8 -B -m pipeline.collect retry-short-approved zn4tDPT4RLQ
```

This requires explicit IDs, human approval, status `rejected`, the exact note
`duration_too_short`, and duration 180–10800 seconds. It validates all IDs before
changing only status to `candidate` and clearing that note. It neither downloads
nor applies unrelated decisions; an already revived row is rejected by this command.
The designer must still reject off-target idol/broadcast/solo/silent/outside-car
content that patterns cannot identify.

## Approval decisions and preserved rejected audio

`data/approved.tsv` has exactly these columns:
`video_id`, `decision`, `reason`, `decided_by`, `comment`. Decisions are `approve`,
`reject`, or `hold`. Legacy four-column files remain readable (empty comment).
All approval writes use five columns, sanitize whitespace/control characters,
and limit comments to 500 characters. Duplicate IDs, invalid decisions, and
missing cells stop with an error. Reading and `--check` never migrate or write.
`migrate_approvals(path)` explicitly migrates once, atomically and idempotently;
the review console runs it on startup.

`decided_by=claude` is a pre-screen opinion only. Any other nonempty reviewer
name denotes a human decision. `human_decision(row)` and
`is_human_approved(video_id, approvals)` expose that distinction for downstream
consumers. **T20 does not change collector download gating or `--check`; the
human approval training gate belongs to T21 and downstream pipeline tasks.**
The existing collector behavior below still uses the decision value alone.

From this directory, start the Korean local review console with
`.\.venv\Scripts\python.exe -m tools.review.serve --name user`, then open
`http://127.0.0.1:8766`. It immediately saves human approve/reject/hold with
`reason=human review` and a comment, replacing any previous row for that ID.
It never moves audio or changes the manifest; apply saved rejections with
`pipeline/collect.py apply-decisions`. Run only one approval writer at a time.
The console reads candidate rows at startup; restart it after `retry-short` to see revived videos.

- `approve`: authorizes downloading; pending videos remain candidate until fetched.
- `reject`: sets status rejected and records `manual:<reason>` in note.
- `hold` or unlisted: pending videos remain candidates and cannot download. Holds
  remain in the review sheet.

Existing downloaded rows retain their status/audio unless rejected. Previous rejects
are sticky if a decision is removed or changed to hold. Explicit approve reverses a
manual rejection. Automatic rejects remain subject to the automatic filters.

**Rejecting an already downloaded video moves its audio to
`.local/audio/_rejected/{video_id}.{ext}`; it never deletes the audio.** The manifest
row and downloaded_at are retained. Rejection is saved before moving, so rerunning
apply-decisions completes an interrupted move. Existing archived files are never
overwritten. Reapproving a manually rejected video restores its preserved audio to
the active directory for reuse. Automatic rejects use the same preservation policy
if an active file exists.

`apply-decisions` is offline. Other mutating commands also apply the decision list
before their work, except `mark-density` (review notes only) and `migrate-splits`
(split cells only), plus the two explicit retry commands (status/note only).
`--check` and `review-flags` are read-only. Use `--approvals PATH` for another list.
`migrate-splits` also bypasses approval decisions and audio movement.

## Review and channel expansion

`review-sheet` writes `.local/collect/review.tsv` for unlisted/held candidates, sorted
by channel. Columns are video_id, channel, title, duration_minutes, description,
query, url. Approved/rejected rows are omitted.

`expand --per-channel N` lists at most N uploads from each approved video's channel
`/videos` tab. The default is **5**; the hard cap is **20**. Values outside 1..20 are
errors. A channel with any manually rejected video is excluded even if it also has
an approved video. A rejected video itself can never seed expansion. Existing IDs
are not added twice, so actual additions may be below N. Automatic filters apply.
Note includes `expanded_from=<channel_id>`, preserving other notes and the original
query. Output `channels` reports new IDs per channel, including auto-rejected IDs.
The global `--max-candidates` limit also applies to expansion.

Repeat `--channel-query` to search within each eligible approved channel instead
of listing its latest uploads:

```powershell
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py expand --channel-query "드라이브" --channel-query "차 안" --per-channel 5 --max-candidates 35
```

Each term uses `https://www.youtube.com/channel/<id>/search?query=<q>`, with the
query UTF-8 percent-encoded. Each search requests at most N results; all terms
share the same N-new-ID cap per channel and the global candidate cap. Duplicate
IDs do not consume the cap; automatic rejects do. Approval-channel exclusions,
automatic filters, and provenance notes work as for ordinary expansion.

## Speech density and splitting

```powershell
.\.venv\Scripts\python.exe -B pipeline/collect.py mark-density --speech-threshold 0.15 --hangul-threshold 0.8 --min-utterances 10
.\.venv\Scripts\python.exe -B pipeline/collect.py review-flags
```

When present, `.local/utterances/yt_<id>.stats.json` is read without modification.
Fields audio_duration, vad_removed_seconds, utterance_count produce note values:

- `speech_share = (audio_duration - vad_removed_seconds) / audio_duration`
- `utterances_per_minute = utterance_count * 60 / audio_duration`
- `low_speech` only when speech_share is strictly below the threshold (default 0.15)
- `utterance_count` is also stored from the stats; `no_speech` is flagged when it
  is strictly below `--min-utterances` (default 10, a nonnegative integer)
- When `yt_<id>.jsonl` is present beside the stats, `hangul_share` is Hangul
  letters / (Hangul + Latin letters) across all utterance `text` fields. Unicode
  Hangul/Jamo and Latin letters (including accented letters) count; digits,
  punctuation and other scripts do not. `non_korean` is flagged strictly below
  `--hangul-threshold` (default 0.8, within 0..1).

Reruns replace these numbers and remove stale flags. Missing transcripts or text
with no Hangul/Latin letters have undefined Hangul share: no hangul_share number
or non_korean flag is recorded. Missing stats are skipped; invalid arithmetic
inputs or malformed transcript text cause an error. Inputs are never modified.
Flags only: no status or split changes, decision application, or audio movement.

`review-flags` prints JSON with a `sources` list of downloaded videos carrying
any of `low_speech`, `non_korean`, or `no_speech`. Each entry contains video_id,
flags, and available speech_share, hangul_share, utterance_count and
utterances_per_minute numbers. It contains no transcript text and writes nothing.
This is separate from the existing manual `lowdensity` marker. Use
`lowdensity video: URL` or `--lowdensity-video`; lowdensity search/channel inputs
mark all their results. Every row in a channel with any lowdensity video is test.
Other channels use SHA-256 of the UTF-8 channel ID, first eight bytes big endian,
modulo 100: **0..69 train, 70..84 dev, 85..99 test (70/15/15)**. This is a pure
function of channel ID; adding/removing other channels cannot change it. Unknown
channels have empty split. The common convention in TASKS.md predates T05b's ratio.

`data/split-overrides.tsv` has exactly `channel_id`, `split`, `reason` columns.
It starts with a header only. Before creating human gold labels, record the channel's
current split here with a nonempty reason. Pins take precedence over the hash for
all rows in that channel, including new videos. Duplicate channels (even matching
splits), invalid splits, empty channel/reason, or malformed rows are errors.
A lowdensity channel may only be pinned to test; conflicting pins fail rather than
silently moving it. Pins for channels not yet in the manifest are allowed.
Use `--split-overrides PATH` for an alternate file. A missing file means no pins
for compatibility with isolated fixtures/legacy inputs; preserve this file with gold.

After changing the hash rule or pins, explicitly migrate **every** manifest row,
including rejected/candidate/failed rows, without applying decisions or moving audio:

```powershell
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py migrate-splits
.\.venv\Scripts\python.exe -X utf8 -B pipeline/collect.py --check
```

Migration reports changed/total rows and is idempotent (unchanged bytes/timestamps
on rerun). Ordinary collector updates also recompute splits using pins and the
lowdensity rule. `--check` verifies the stored split against these rules and never
migrates. Window builds consume the stored split and reject disagreement with pins;
run migration before building after a pin change.

## Resumption, blocking, and checks

Download caps `--max-videos 10` / `--max-hours 5` apply to the entire approved manifest
cohort. Completed files reserve budget first, so repeated runs do not fetch a new
batch of ten. Failed attempts consume this run's budget. A video too long for the
remaining budget gets run_hours_cap in note; later videos can still fit. Candidate
and failed rows resume; rejected rows persist. Delete a video's local metadata cache
to refresh it. To reevaluate an automatic rejection, reset its status to candidate;
if it has archived audio, also reconcile its file location with its status.

Discovery caches `.local/collect/discovery-*` stabilize input order across reruns.
Change the query/result count or remove the relevant cache for a fresh ranking.
Partial audio is in `.local/collect/tmp/`, finished audio in `.local/audio/`.
Approved rows with complete audio recover their status without another download.
UTF-8 TSV changes are sanitized, fsynced, and atomically replaced; unchanged files
retain their bytes and timestamps. Run only one collector at a time.

yt-dlp uses `--ignore-config --no-js-runtimes --js-runtimes deno:<absolute-path>`.
Audio format is `bestaudio[language^=ko]/bestaudio`; `--delay 3` controls time between
download starts. Never use cookies, accounts, or proxies. Bot confirmation, required
sign-in, age restrictions, HTTP 403/429, and rate limits stop immediately with exit 2.
This also applies to blocking warnings with exit code zero. Details go to stderr
and `.local/collect/last-stderr.txt`. Blocks before a video ID do not create fake
rows. Success exits 0, individual failures 1, stopped errors 2, Ctrl-C 130.

`--check` is offline and read-only. It checks missing downloaded audio, orphan files,
status/location mismatches, invalid/empty files, duplicate audio, and channel splits.
Archived audio is valid only for rejected manifest rows and gets the same identity,
size, and duplicate checks. Rejected rows need not have audio if never downloaded.
Consistency errors exit 1.

```powershell
.\.venv\Scripts\python.exe -B -m pytest --basetemp .local/collect/pytest2 -p no:cacheprovider
```
