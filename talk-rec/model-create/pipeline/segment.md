# T03: word-based utterances

Run from `model-create` in PowerShell; no environment activation is needed:

```powershell
.\.venv\Scripts\python.exe -m pipeline.segment ..\dev-tools\.local\samples\hcn_hz3E1f0.webm --source yt:hcn_hz3E1f0 --export-pcm
.\.venv\Scripts\python.exe -m pipeline.segment .local\audio --export-pcm
.\.venv\Scripts\python.exe -m pipeline.segment_verify .local\utterances\yt_hcn_hz3E1f0.jsonl --baseline ..\dev-tools\.local\reviews\hcn_hz3E1f0-full.json
.\.venv\Scripts\python.exe -m pytest
```

A file requires `--source`; a directory maps each supported media filename stem
to `yt:{stem}` (nonrecursive, one file per source). Existing JSONL sources are
skipped, even if PCM was not exported previously. Use `--force --export-pcm` to
regenerate them. `--force --reuse-transcript` reprocesses the saved Whisper cache
without inference; its input path, size, modification time, model path, decoding
settings and VAD threshold must match. Ordinary `--force` reruns Whisper.

Only the read-only local CTranslate2 model at
`dev-tools/.local/models/whisper-turbo` is loaded, on CUDA float16. There is no
download or CPU fallback. Windows DLL handles for this environment's NVIDIA
cuBLAS/cuDNN/NVRTC bins are held through inference, as in `dev-tools/transcribe.py`.

Decoding uses Korean transcription, word timestamps, beam size 5, temperature 0,
and `condition_on_previous_text=False`. Deterministic beam decoding avoids
sampling retries; resetting text context reduces repetition loops. Compression
ratio 2.4 and log probability -1.0 are retained as decoder quality thresholds;
at temperature 0 there is no stochastic fallback. Silero `vad_filter=True`,
threshold 0.5, minimum speech duration 0 ms, minimum silence 1000 ms and padding
300 ms screen non-speech without a minimum utterance-length filter. This can
remove music-only regions while retaining short backchannels. It is a speech
detector, not a music classifier: music under conversation remains, and singing
may remain. VAD intervals are audited separately using identical options; Whisper
restores word times to the original audio timeline. VAD does not define utterances.

`no_speech_threshold=None` and `hallucination_silence_threshold=None` disable
Whisper's opaque internal removals. No-speech filtering is instead explicit and
audited below. Standard punctuation attachment and token suppression defaults
are retained; no prompt, normalization or repetition penalty is added.

Words are ordered by start time. Close before the next word when its start minus
the previous word's end is **>= `--gap-seconds` (1.0)**, including equality.
Close at the last whole word whose end fits within **`--max-seconds` (12.0)** of
the first word's start; start the next utterance with the next word. A 1 ns
tolerance handles floating point equality. Overlaps are retained and the largest
word end defines the utterance end. Whisper's default segments are never used as
utterances.

With PCM export, the word-span cap is `min(max_seconds, 11.7)` to reserve the full
300 ms pre-roll within the device's 12 s buffer. This may split one word earlier
than transcript-only mode. Tail is `--tail-seconds` (0.2), reduced to available
space and source duration. All kept speech remains inside the PCM. JSONL `start`
and `end` delimit speech; `pcm.start`, `pcm.end`, `start_sample`, `end_sample`
record the actual padded, sample-aligned export interval, with an exclusive end.
Rounding uses Python `round(seconds * 16000)`. PCM is mono 16 kHz, signed 16-bit
little endian, without a WAV header. A request for over 12 s is rejected.

Simple filters, applied in this order (one primary reason per removed item):

| Stats reason | Rule / parameter |
| --- | --- |
| `vad_non_speech` | Complement of padded Silero speech intervals; regions and removed seconds logged. `--vad-threshold` defaults to 0.5. These are suspected non-speech, not confirmed music. |
| `segment_no_speech` | Decoder no-speech probability >= `--no-speech-probability` (0.6) **and** average log probability < `--no-speech-logprob` (-1.0). Removes that decoder segment's words before regrouping. |
| `missing_word_times` | Nonempty decoder text without word timestamps is discarded, never substituted with a default segment. |
| `invalid_word_time` | Nonfinite, negative, reversed duration or out-of-audio word timestamps. Zero-duration words are retained verbatim, since Whisper sometimes assigns these to ordinary short responses. They cannot conceal a gap; stats count those retained. An isolated zero-duration word exports its pre-roll/tail audio without inventing a speech timestamp. |
| `long_word_artifact` | Word duration > `--max-word-seconds` (3.0), or the effective utterance cap if smaller. Discard the word and log its original time/text; do not shorten it or invent a timestamp. Regrouping exposes any gap it had hidden. This conservative artifact rule can lose a genuinely elongated word. |
| `non_speech_marker` | Empty text, music glyphs, or only bracketed/parenthesized music, silence, noise or applause labels. Laughter labels remain. |
| `korean_closing` | Whole utterance consists only of common Korean closing phrases: remove at `--closing-repeats` (2) or more, or mean word probability < `--closing-probability` (0.5). Also remove subtitle-credit prefixes (`자막 제공/제작/번역`, `한글 자막`, `subtitles by`, `amara.org`). A confident single thank-you remains. |
| `repeated_token` | One identical whitespace/punctuation-separated token repeated >= `--repeat-count` (6); Korean laughter syllables and ㅋ/ㅎ are exempt. |
| `low_confidence` | Mean word probability < `--min-probability` (0.2). No minimum duration, character count or word count; ordinary short responses and laughter have no special exclusion. |

Output is `.local/utterances/{source}.jsonl`, replacing `:` with `_` in filenames.
Every record is validated with `common.schema.validate_utterance`. Text joins raw
Whisper word strings, stripping only outer whitespace. Extra fields preserve word
times/text/probabilities and mean probability for auditing. PCM paths are
`.local/pcm/{same_sanitized_source}/{utt:06d}.pcm`. IDs restart at zero after
filtering and increase with time. A forced PCM run removes stale six-digit PCM
files for that source; transcript-only runs do not touch previously exported PCM.

Sibling `.stats.json` records durations, inference time, inference/audio real-time
factor, utterance count, interpolated min/median/p90/max duration, median character
count, options and every removed item with its reason and original times/text
where available. Removal counts mix explicitly labelled units: VAD regions,
decoder segments, words, or candidate utterances; they should not be summed as
utterances. Inference timing includes both VAD audit and transcription, excluding
model loading, media decoding, postprocessing and PCM writes. Original decoder
segments and words are cached only in `.local/segment/{source}.whisper.json`.

The verifier checks every schema, ID, duration, within-utterance word gap and PCM
file length against JSONL sample boundaries. It writes a text-free 20-item sample
with seed 20261110 to `.local/segment/spotcheck.tsv` and full numerical results to
`.local/segment/verification.json`. It compares the old default segmentation when
`--baseline` is supplied. These checks cannot verify speech alignment by ear;
listening and human review remain necessary to assess acoustic correctness and
false removals.
