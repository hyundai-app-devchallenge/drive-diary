# T06 CLI silver labeler

Run from `talk-rec/model-create` with `.\.venv\Scripts\python.exe -B -m pipeline.label`.
No package/dependency changes are needed. The inputs are read only. Outputs and
dry-run prompts stay under `.local/labels/`.

The system and user templates are extracted directly from `prompts/labeler.md`;
the complete rubric is substituted without rewriting it. Timing comes from the
authoritative utterance records, including the actual preceding utterance at clip
and chunk boundaries. The first utterance in a source has gap `+0`. Gaps retain
float precision so a gap just over 20 seconds stays over 20 seconds. The source
kind is `youtube` only for `yt:` sources. Calls contain at most 40 turns; subsequent
chunks repeat 3 turns and introduce up to 37 turns. The repeated labels are
validated but discarded. Earlier chunks own their labels even if later chunks
disagree. A failed chunk does not prevent later chunks from running; its missing
labels are filled when it succeeds on a later invocation.

`--backend codex|agy|command|fake` defaults to codex. `--effort` defaults to high.
The Codex default model is **gpt-6-luna**, verified on 2026-11-10 in the installed
`C:/Users/ip936/.codex/models_cache.json` (`slug: gpt-6-luna`, display name
`GPT-6-Luna`), not inferred from a nickname. `codex.cmd --help`,
`codex.cmd exec --help`, and `codex.cmd debug models --help` were inspected.
The normal `codex` PowerShell command resolves to a script blocked by the local
execution policy; the backend resolves the installed exe/cmd launcher. For the
npm cmd shim, it invokes the same installed `bin/codex.js` via Node directly,
preserving quoted TOML arguments without cmd.exe reinterpreting them.

The exact Codex argument vector after the executable is:

```text
exec
--model gpt-6-luna
--config model_reasoning_effort="high"
--sandbox read-only
--skip-git-repo-check
--ephemeral
--ignore-user-config
--ignore-rules
--config web_search="disabled"
--config developer_instructions="Label only the supplied input. Do not use tools, read files, or access the network. Return only the requested JSON array."
--color never
--json
--cd <empty temporary working directory>
--output-last-message <temporary sibling final.txt>
-
```

The model/effort arguments come from flags. The complete prompt goes on stdin;
only `final.txt` is treated as an answer. JSON event output supplies numeric token
usage and is otherwise discarded. Tool-use events cause rejection. No bypass
flags are used. The temporary directory and final response are removed afterward.
The relevant public flag descriptions are also documented in the
[official OpenAI CLI reference](https://learn.chatgpt.com/docs/developer-commands?surface=cli).

For another subscription CLI, supply an argv template (shell operators are not
interpreted). `{model}` and `{effort}` are replaced inside individual arguments.
Use an absolute executable path if it is not on PATH. The working directory is
temporary, so paths in arguments should also be absolute.

```powershell
.\.venv\Scripts\python.exe -B -m pipeline.label --backend command --model MODEL_ID --effort medium --command 'C:\path\cli.exe --model {model} --effort {effort}' --limit-clips 2
```

The generic command backend uses UTF-8 subprocess pipes. Python child processes
also receive `PYTHONIOENCODING=utf-8` and `PYTHONUTF8=1`.

## agy backend

Use `--backend agy --model gemini-3.8-flash --effort medium`. The agy default
model is `gemini-3.8-flash`; specify medium explicitly because the shared effort
default remains high. The backend appends `-low`, `-medium`, or `-high` to the
model ID. An existing effort suffix wins over `--effort`, without appending it
again or passing a second effort flag. The base model and effective effort are
used in the labeler and output name: `llm:agy:gemini-3.8-flash:medium` and
`.local/labels/agy-gemini-3.8-flash-medium[-TAG]/`.

`--agy-path` overrides the executable. Its default is
`%LOCALAPPDATA%\agy\bin\agy.exe` when that file exists, otherwise PATH lookup
for `agy.exe`/`agy`. (An install run from inside the Codex app lands in the
Codex package's `LocalCache` instead; copy it to the path above.)
Installed agy 1.5.4's `--help` was inspected locally. The exact subprocess argv
for the configuration above is this list (the first element is the resolved
executable and the last element contains the entire multiline prompt):

```python
[
    agy_path,
    '--model', 'gemini-3.8-flash-medium',
    '--output-format', 'json',
    '--sandbox',
    '--print=' + prompt,
]
```

The prompt is joined without rewriting the templates:

```text
--- BEGIN SYSTEM PROMPT ---
<system prompt including rubric>
--- END SYSTEM PROMPT ---

--- BEGIN USER MESSAGE ---
<rendered user message, including any retry feedback>
--- END USER MESSAGE ---
```

The call uses `shell=False`, `stdin=DEVNULL`, binary stdout/stderr capture, and an
empty temporary working directory removed afterward. `--print=<prompt>` is one
argv element; stdin prompts are not supported by this CLI. No permission bypass
flag is passed. Both output streams decode with strict UTF-8 first, then the
system ANSI code page (`GetACP()` on Windows, e.g. cp949), with no replacement
decoding. Undecodable bytes or U+FFFD (including in the parsed response) fail the
chunk through the normal retry path. Fixed synthetic UTF-8 and cp949 byte fixtures cover
the fallback. A single JSON object, `status=SUCCESS`, and a nonempty string
`response` are required. Successful agy responses normally omit `denied_actions`;
an explicit empty list is also accepted. A nonempty list or any non-list value
is a backend error. Usage-limit messages in
responses, error envelopes, or stderr enter the existing exit-3/wait path.
Numeric token usage, including thinking/cache tokens, is accumulated in
`run.jsonl` even for rejected envelopes.

Before launching, the backend measures Python's actual Windows quoting of the
whole argv in UTF-16 units, including the terminating NUL. Anything over 32,000
units is rejected without truncation (Windows' hard limit is 32,767). This check
also applies to longer retry prompts. `--dry-run` reports the largest selected
prompt in characters and the largest argv in UTF-16 units without launching agy.
On 2026-11-10 the current inputs measured:

| Selection | Prompts | Largest prompt characters | Largest argv UTF-16 units |
| --- | ---: | ---: | ---: |
| train, first 50 clips | 53 | 9,902 | 10,240 |
| all train | 96 | 9,902 | 10,240 |
| all dev | 1 | 7,096 | 7,434 |
| all test | 9 | 9,997 | 10,335 |

The two-clip sandbox smoke used `--run-tag sandbox-smoke --retries 0 --timeout 30`.
Both calls timed out at 30.047 seconds; pipeline exit was 1, with no responses or
labels. Logs are in `.local/labels/agy-gemini-3.8-flash-medium-sandbox-smoke/`.
A separate synthetic probe with `PYTHONUTF8=1`/`PYTHONIOENCODING=utf-8` timed out
after 40 seconds. Another probe successfully set input/output console code pages
to 65001 in a separate hidden child console, but agy still timed out after 30
seconds. Neither established a working UTF-8 emission setting, so the backend
uses the strict fallback chain and does not change the parent console.

Run the following in an ordinary PowerShell session outside the sandbox. These
use the same sorted train selection and 50-clip limit as the existing codex
`first50` run; keep its windows/utterance inputs unchanged. The 50-clip agy call
resumes the smoke directory and skips completed chunks. Wait for codex `first50`
to finish before comparing; do not modify its directory.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
.\.venv\Scripts\python.exe -B -m pipeline.label --backend agy --model gemini-3.8-flash --effort medium --split train --limit-clips 50 --run-tag first50 --dry-run
.\.venv\Scripts\python.exe -B -m pipeline.label --backend agy --model gemini-3.8-flash --effort medium --split train --limit-clips 2 --run-tag first50
if ($LASTEXITCODE -ne 0) { throw 'agy smoke incomplete; resolve the CLI environment or limit, then resume the same command.' }
.\.venv\Scripts\python.exe -B -m pipeline.label --backend agy --model gemini-3.8-flash --effort medium --split train --limit-clips 50 --run-tag first50
if ($LASTEXITCODE -ne 0) { throw 'agy first50 incomplete; resume the same command.' }
.\.venv\Scripts\python.exe -B -m pipeline.label_compare .local/labels/codex-gpt-6-luna-high-first50 .local/labels/agy-gemini-3.8-flash-medium-first50
```

The designer must verify successful inference with `--sandbox`, zero denied
actions, Korean response integrity (UTF-8 or the actual system ANSI code page),
and all five usage counters in `run.jsonl`. If a console/locale setting produces
strict UTF-8 reliably outside, verify it with binary capture before making it the
backend default. Live 2/50-clip success and comparison metrics remain unmeasured
here; the command-template backend is not a substitute for the agy backend.

Other flags: `--split train|dev|test`, `--source SOURCE`, `--limit-clips N`,
`--concurrency N` (default 1, parallel clips, sequential chunks within each clip),
`--retries N` (default 2 additional attempts), `--timeout SECONDS` (default 600),
`--wait-on-limit MINUTES`, `--run-tag TAG`, and `--dry-run`. `--windows` and
`--utterances` allow isolated input fixtures. Clips are selected in sorted
`(source, clip)` order before applying the limit. Dry run writes prompts to
`<output>/prompts/` and never constructs or calls a backend.

Each output directory is named `<backend>-<model>-<effort>[-<run-tag>]`, with
unsafe path characters percent-encoded. Source JSONL filenames also percent-encode
unsafe characters (`yt:fixture` becomes `yt%3Afixture.jsonl`), necessary on Windows.
For a YouTube source `yt:<id>`, the label file is `yt%3A<id>.jsonl` while its
utterance input is `yt_<id>.jsonl`. Resolve records by their source/utterance IDs,
not by matching these basenames. Existing percent-encoded label files remain
readable and keep their names; no output migration or renaming is needed.
The reserved source names `run` and `unclear` have their first character encoded.
Labels have exactly the common fields, including
`labeler=llm:<backend>:<model>:<effort>`, and pass `validate_label`.

An OS lock prevents two processes from writing the same output directory. Each
chunk's `.chunks/<hash>.json` is an authoritative atomic transaction containing
labels and transcript-free execution metadata. Per-source JSONLs, `unclear.jsonl`
and `run.jsonl` are atomic materialized views. If interrupted between transaction
and view updates, the next invocation rebuilds the views before skipping finished
chunks. Temporary files left by a forcibly killed process are ignored. Never
delete `.chunks` to resume. Configuration and prompt hashes prevent accidental
mixing; changed input for an existing chunk requires a new run tag. Failed and
limited chunks are retried on the next invocation; successful chunks are skipped.
Counters and time from earlier failed invocations accumulate in each chunk's log.
An input change affecting an already completed chunk is rejected before any new
CLI call for that chunk. Use a new run tag for changed data/rubric/CLI configuration.

`run.jsonl` contains one entry per attempted chunk: `chunk_id`, total/new turn
counts, attempts, wall seconds, cumulative prompt characters, numeric token usage
if reported, validation/backend/limit error counts, status, sanitized error, and
corpus skip warning IDs. It never contains prompt, response, or transcript text.
Notes are stored only in label artifacts, not execution logs. Prompt files are
written only for explicit dry runs. Normal stdout/stderr contains IDs/counts only;
raw CLI errors are never forwarded because they may echo transcripts.

Exit codes are 0 success, 1 exhausted chunk attempts, 2 invalid input/configuration
or locked output, 3 CLI usage/subscription/rate limit, and 130 interruption.
On a limit, in-flight calls may finish and commit; subsequent chunks do not start.
The printed resume instruction uses the same command. With `--wait-on-limit`, the
limited call waits and retries without consuming validation retries. Waiting is
interruptible. A corpus skip is accepted with a warning, as specified by the prompt.

Comparison:

```powershell
.\.venv\Scripts\python.exe -B -m pipeline.label_compare A_DIR B_DIR
```

This uses shared IDs only, excludes the execution log and unclear sidecar, checks
schema/duplicates, ignores labeler/note differences, and treats flag arrays as
sets. JSON output includes full label exact agreement, story exact agreement,
binary story>=2 agreement and Cohen kappa, quadratic story kappa, agreement for
every kind/exclude flag and skip, and IDs whose story differs by at least 2.
Compare an initial run against its rerun directory for self consistency. Empty or
constant-marginal kappas are null (undefined); exact agreement is still reported.
The hand-computed test has A=[0,1,2,3], B=[0,3,2,1]: binary kappa=0,
quadratic weighted kappa=0.2 (observed squared disagreement=2, expected=2.5).

## Earlier codex sandbox execution

This records the earlier codex experiment, before the ongoing codex `first50`
run. Its active output directory was not inspected or changed in this follow-up.

The sandbox smoke test attempted two real train clips. Both CLI processes exited
with code 1 before inference because the home directory was unavailable
(`Could not find home directory`; sanitized log: `home directory unavailable`).
No sandbox/auth/network workaround was attempted. No real labels were produced,
and the 50-clip double run, real format-error count, self consistency, seconds per
clip and 2,000-clip projection could not be measured here.

Run these in an ordinary PowerShell session outside the Codex sandbox. Stop after
any unexpected failure; proceed from the smoke test to the 50-clip runs only after
the smoke exit code is zero. The first 50-clip run includes the smoke clips and
skips their completed chunks. The second directory is separate.

```powershell
Set-Location C:\github\drive-diary\talk-rec\model-create
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

.\.venv\Scripts\python.exe -B -m pipeline.label --backend codex --model gpt-6-luna --effort high --split train --limit-clips 2 --run-tag first50
if ($LASTEXITCODE -ne 0) { throw 'Smoke test failed; resume the same command after resolving the CLI environment or usage limit.' }
.\.venv\Scripts\python.exe -B -m pipeline.label --backend codex --model gpt-6-luna --effort high --split train --limit-clips 50 --run-tag first50
if ($LASTEXITCODE -ne 0) { throw 'First run incomplete; resume the same command.' }
.\.venv\Scripts\python.exe -B -m pipeline.label --backend codex --model gpt-6-luna --effort high --split train --limit-clips 50 --run-tag second50
if ($LASTEXITCODE -ne 0) { throw 'Second run incomplete; resume the same command.' }
.\.venv\Scripts\python.exe -B -m pipeline.label_compare .local/labels/codex-gpt-6-luna-high-first50 .local/labels/codex-gpt-6-luna-high-second50
```

To compute timing and format-error counts after those runs (concurrency 1), run
this UTF-8 Python script through the same PowerShell session:

```powershell
@'
import json
from pathlib import Path
for tag in ('first50', 'second50'):
    path = Path('.local/labels') / ('codex-gpt-6-luna-high-' + tag) / 'run.jsonl'
    rows = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]
    clips = {row['chunk_id'].rsplit('/chunk:', 1)[0] for row in rows}
    seconds = sum(row['wall_seconds'] for row in rows)
    print(json.dumps(dict(run=tag, clips=len(clips), failed_chunks=sum(r['status'] != 'ok' for r in rows),
        format_errors=sum(r['validation_errors'] for r in rows),
        seconds_per_clip=seconds/len(clips) if clips else None,
        projected_hours_2000=seconds/len(clips)*2000/3600 if clips else None)))
'@ | .\.venv\Scripts\python.exe -B -
```

Time includes retries/CLI startup and configured limit waits; concurrency changes
the relationship between summed per-clip time and elapsed run time. A projection
does not predict future subscription limits.

## Verification commands

The label and agy test fixtures use Windows extended paths (`\\?\`) for their synthetic
roots. An external, deeply nested pytest `--basetemp` can push the full SHA-256
prompt filename past Windows' legacy path limit: `os.replace` then fails and main
returns 2. Its exception names the shorter temporary file first. This is unrelated
to source-name percent encoding or a project path guard.

```powershell
.\.venv\Scripts\python.exe -B -m pytest tests/test_label.py tests/test_label_agy.py tests/test_label_compare.py tests/test_gold_http.py tests/test_gold_agreement.py --basetemp .local/labels/pytest2 -p no:cacheprovider -q --tb=short
.\.venv\Scripts\python.exe -B -m pytest tests/test_collect.py --basetemp .local/labels/pytest2 -p no:cacheprovider -q --tb=short
```

The HTTP repetition used this command 30 times, serially, from a Python
`subprocess.run` loop with binary stdout/stderr capture and no retries/sleeps:

```powershell
.\.venv\Scripts\python.exe -B -m pytest tests/test_gold_http.py --basetemp .local/labels/pytest2 -p no:cacheprovider -q --tb=short
```

Final follow-up verification on 2026-11-10:

- All `tests/test_label*.py` and `tests/test_gold*.py`: **189 passed in 3.82s**.
  This includes 51 agy tests covering argv/effort, binary decoding fixtures,
  envelope errors, limits/wait/resume, usage journals, and command-length guards.
- `tests/test_gold_http.py`: **30 consecutive runs, 37 passed per run, zero
  failures** (1,110 test executions), without client retries or sleeps. Log:
  `.local/labels/gold-http-final-30.log`.
- HTTP diagnosis: removing pre-existing helper retries exposed failures in all
  30 baseline file runs. Tracing showed full declared-body reads and successful
  response writes even on reset requests. Draining bodies alone and immediate
  half-close followed by draining still failed (24/30 file runs). Waiting for
  peer completion *before* server socket shutdown eliminated the reproduced
  close race. The server now bounds that drain to 64 KiB/2 seconds, consumes
  declared application bodies up to 64 KiB before all GET/POST responses, uses a
  5-second read timeout, preserves byte-accurate Content-Length, and sends only
  one response if a socket write fails. Regression tests cover split bodies,
  early rejections, oversized/truncated bodies, framing, and shutdown order.
- `tests/test_collect.py`: **34 passed in 0.84s** at verification time; collection
  files were not edited. This is a snapshot while the other task may be active.
- Real agy inference and UTF-8-console probes timed out as described above.
  No real 50-clip agy run or comparison was attempted in the sandbox.
- No dependency changes, commits, branches, pushes, transcript output, collection
  edits, or writes under the active codex label directories were made.
