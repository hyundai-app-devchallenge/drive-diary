"""Small, deterministic rule gate. Returned line indices always refer to input."""
from dataclasses import dataclass
from functools import lru_cache
import json
from pathlib import Path
import re

from common.normalize import normalize


def compact(text):
    return normalize(text).replace(" ", "")


def without_coda(text):
    # Hangul arithmetic also works in Kotlin; no language-specific tokenizer.
    return "".join(chr(ord(c) - (ord(c) - 0xAC00) % 28)
                   if "가" <= c <= "힣" else c for c in text)


def one_edit(a, b):
    """Levenshtein distance <= 1, linear time and constant extra memory."""
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) > len(b):
        a, b = b, a
    i = j = errors = 0
    while i < len(a) and j < len(b):
        if a[i] == b[j]:
            i += 1
            j += 1
        else:
            errors += 1
            if errors > 1:
                return False
            if len(a) == len(b):
                i += 1
            j += 1
    return errors + len(b) - j <= 1


@dataclass(frozen=True)
class AppSignals:
    """App-supplied evidence, never inferred from text or playback alone.

    playback_active is context only. external_audio_dominant must describe this
    entire block (not merely overlapping playback). Unknown is the default.
    """
    playback_active: bool | None = None
    external_audio_dominant: bool | None = None

    def __post_init__(self):
        if any(v is not None and type(v) is not bool
               for v in (self.playback_active, self.external_audio_dominant)):
            raise ValueError("invalid app signal type")


class PolicyGate:
    def __init__(self, rules_path=None, *, wake_words=None):
        self.rules_path = Path(rules_path) if rules_path else Path(__file__).with_name("rules.json")
        raw = self.rules_path.read_bytes()
        import hashlib
        self.rules_sha256 = hashlib.sha256(raw).hexdigest()
        self.rules = json.loads(raw)
        self.patterns = [(r, re.compile(r["pattern"])) for r in self.rules["patterns"]]
        self.coda_patterns = [(r, re.compile(without_coda(r["pattern"])))
                              for r in self.rules["patterns"]]
        self.literals = [compact(t) for t in self.rules["optout_literals"]]
        self.folded = [without_coda(t) for t in self.literals]
        self.max_literal_chars = max(map(len, self.literals))
        words = self.rules["wake_words"] if wake_words is None else wake_words
        if not isinstance(words, (list, tuple)) or any(not isinstance(w, str) or not compact(w) for w in words):
            raise ValueError("invalid wake word configuration")
        self.wake_words = sorted({compact(w) for w in words}, key=lambda w: (-len(w), w))
        self.wake_tail = re.compile(without_coda(self.rules["wake_command_tail"]))

    def _match(self, text):
        reasons = []
        folded = without_coda(text)
        for patterns, value in ((self.patterns, text), (self.coda_patterns, folded)):
            for rule, pattern in patterns:
                match = pattern.search(value) if rule["mode"] == "search" else pattern.fullmatch(value)
                if match:
                    reasons.append((rule["category"], rule["id"]))
        if any(t in text for t in self.literals):
            reasons.append(("optout", "optout.literal"))
        elif any(t in folded for t in self.folded):
            reasons.append(("optout", "optout.coda"))
        # Only whole short spans receive approximate matching, and only optout.
        # Commands never use unconstrained fuzzy matching (e.g. a family request).
        minimum = self.rules["fuzzy_min_chars"]
        if len(text) >= minimum and len(text) <= self.max_literal_chars + 1:
            if any(len(t) >= minimum and one_edit(folded, t) for t in self.folded):
                reasons.append(("optout", "optout.approx"))
        for word in self.wake_words:
            wake = without_coda(word)
            if folded == wake:
                reasons.append(("command", "command.wake"))
            elif folded.startswith(wake):
                tail = folded[len(wake):]
                # Require an imperative ending even after a leading wake word;
                # otherwise ordinary discussion of its name is overblocked.
                if self.wake_tail.fullmatch(tail):
                    reasons.append(("command", "command.addressed"))
        return reasons

    def gate(self, lines, *, signals=None):
        if not isinstance(lines, list):
            raise ValueError("invalid input lines")
        texts = [r.get("text") if isinstance(r, dict) else r for r in lines]
        if any(not isinstance(t, str) for t in texts):
            raise ValueError("invalid line text type")
        if signals is not None and not isinstance(signals, AppSignals):
            raise ValueError("invalid app signals")
        normalized = [compact(t) for t in texts]
        active = {i for i, t in enumerate(normalized) if t}
        matched, reason_ids = set(), set()
        retro_delete = False
        # Match singleton first, then adjacent fragments. Empty lines are kept
        # in indexing but cannot bridge a span. Skip larger spans after a match
        # to avoid removing a neighbouring dialogue line with a wake invocation.
        for size in range(1, self.rules["max_join_lines"] + 1):
            for start in range(len(texts) - size + 1):
                indices = set(range(start, start + size))
                if not indices <= active:
                    continue
                text = "".join(normalized[start:start + size])
                if size > 1 and len(text) > self.rules["max_join_chars"]:
                    continue
                hits = self._match(text)
                if size > 1 and indices & matched:
                    # Always inspect optout across fragments, even if another
                    # policy matched a singleton in the same span.
                    hits = [h for h in hits if h[0] == "optout"]
                if not hits:
                    continue
                # Optout search can find a phrase embedded in conversation.
                # For other categories require the complete joined span.
                matched.update(indices)
                reason_ids.update(reason for _, reason in hits)
                retro_delete |= any(category == "optout" for category, _ in hits)
        blocked = retro_delete or bool(active and len(matched) / len(active) > self.rules["majority"])
        if signals and signals.external_audio_dominant is True:
            blocked = True
            matched.update(active)
            reason_ids.add("external.app_dominant")
        # A blocked block must never reach model/storage, including unmatched
        # fragments. For allowed blocks only matched input lines are removed.
        return dict(block="block" if blocked else "allow",
                    drop_lines=sorted(range(len(texts))) if blocked else sorted(matched),
                    retro_delete=retro_delete, reasons=sorted(reason_ids))


@lru_cache(maxsize=1)
def _default_gate():
    return PolicyGate()


def gate(lines, *, signals=None):
    return _default_gate().gate(lines, signals=signals)
