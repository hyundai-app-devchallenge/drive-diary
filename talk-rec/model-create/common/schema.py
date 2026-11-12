"""Validate decoded JSONL objects from TASKS.md; raise ValueError on failure.

Required fields are checked; additional metadata is allowed. Validation does
not modify records. Window text alone cannot verify the 20-second gap rule;
the window builder must check that against utterance timestamps (T05).
"""

import math

KINDS = frozenset({"recall", "moment", "feeling", "promise", "family_fact"})
EXCLUDES = frozenset({"command", "external", "sensitive", "optout", "garbled"})
ASRS = frozenset({"whisper", "human", "pleos"})
SPLITS = frozenset({"train", "dev", "test"})

# Ordered v2 vocabularies also define classifier output order.
KEEP_VALUES = (0, 1, 2)
TAGS_V2 = ("recall", "fun", "kid", "scenery", "plan", "family_news", "heart", "song")
EXCLUDES_V2 = ("optout", "sensitive", "command", "external", "garbled")


def _fields(record: object, fields: dict[str, type | tuple[type, ...]]) -> None:
    if not isinstance(record, dict):
        raise ValueError("record must be a JSON object")
    for name, types in fields.items():
        if name not in record or type(record[name]) not in (
            types if isinstance(types, tuple) else (types,)
        ):
            raise ValueError(f"{name}: missing field or invalid type")


def _nonempty(record: dict, *names: str) -> None:
    for name in names:
        if not record[name].strip():
            raise ValueError(f"{name}: must not be empty")


def _choices(values: list, allowed: frozenset[str] | tuple[str, ...], field: str) -> None:
    if any(type(value) is not str or value not in allowed for value in values):
        raise ValueError(f"{field}: unknown value")
    if len(values) != len(set(values)):
        raise ValueError(f"{field}: duplicate value")


def validate_utterance(record: dict) -> None:
    _fields(record, {
        "source": str, "utt": int, "start": (int, float), "end": (int, float),
        "text": str, "asr": str, "speaker": (str, type(None)),
    })
    _nonempty(record, "source")
    if record["utt"] < 0:
        raise ValueError("utt: must be nonnegative")
    for name in ("start", "end"):
        value = record[name]
        if value < 0 or (isinstance(value, float) and not math.isfinite(value)):
            raise ValueError(f"{name}: must be finite and nonnegative")
    if record["end"] < record["start"]:
        raise ValueError("end: must be at least start")
    if record["asr"] not in ASRS:
        raise ValueError("asr: unknown value")


def validate_window(record: dict) -> None:
    _fields(record, {
        "id": str, "source": str, "split": str, "clip": int,
        "context": list, "current": str,
    })
    _nonempty(record, "id", "source")
    if record["split"] not in SPLITS:
        raise ValueError("split: unknown value")
    if record["clip"] < 0:
        raise ValueError("clip: must be nonnegative")
    source, separator, utt = record["id"].rpartition("#")
    if source != record["source"] or not separator or not (
        utt.isascii() and utt.isdecimal()
    ):
        raise ValueError("id: expected <source>#<nonnegative utterance index>")
    if len(record["context"]) > 3 or any(
        type(turn) is not str for turn in record["context"]
    ):
        raise ValueError("context: must contain at most 3 text turns")


def validate_label(record: dict) -> None:
    _fields(record, {
        "id": str, "story": int, "kind": list, "exclude": list,
        "skip": bool, "labeler": str, "note": str,
    })
    _nonempty(record, "id")
    if not 0 <= record["story"] <= 3:
        raise ValueError("story: must be an integer in 0..3")
    _choices(record["kind"], KINDS, "kind")
    _choices(record["exclude"], EXCLUDES, "exclude")
    story, kind = record["story"], record["kind"]
    if story >= 2 and not kind:
        raise ValueError("kind: story >= 2 requires at least one kind")
    if story == 1 and kind not in ([], ["family_fact"]):
        raise ValueError("kind: story == 1 allows only family_fact or empty")
    if story == 0 and kind:
        raise ValueError("kind: story == 0 requires empty kind")
    if record["skip"] and (story != 0 or kind or record["exclude"]):
        raise ValueError("skip: requires story == 0 and empty kind and exclude")
    prefix, separator, name = record["labeler"].partition(":")
    if prefix not in {"llm", "human"} or not separator or not name.strip():
        raise ValueError("labeler: expected llm:<model> or human:<name>")


def _block_identity(identity: str) -> tuple[str, int]:
    source, separator, index = identity.rpartition("@")
    if not source.strip() or not separator or not (index.isascii() and index.isdecimal()):
        raise ValueError("id: expected <source>@<nonnegative block index>")
    return source, int(index)


def _finite(value: object, name: str, nonnegative: bool = False) -> None:
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError(f"{name}: expected finite number")
    if nonnegative and value < 0:
        raise ValueError(f"{name}: must be nonnegative")


def validate_block(record: dict) -> None:
    """Validate v2 structure; timestamp/text resolution belongs to blocks --check."""
    _fields(record, {
        "id": str, "source": str, "split": str, "block": int,
        "start": (int, float), "end": (int, float),
        "gap_before": (int, float, type(None)), "utts": list,
        "turns": list, "prefix": list, "features": dict,
    })
    _nonempty(record, "source")
    if _block_identity(record["id"]) != (record["source"], record["block"]) or record["block"] < 0:
        raise ValueError("id/block/source mismatch")
    if record["split"] not in SPLITS:
        raise ValueError("split: unknown value")
    for name in ("start", "end"):
        _finite(record[name], name, True)
    if record["end"] < record["start"]:
        raise ValueError("end: must be at least start")
    if record["gap_before"] is not None:
        _finite(record["gap_before"], "gap_before")
    if not record["utts"] or any(type(i) is not int or i < 0 for i in record["utts"]):
        raise ValueError("utts: expected nonempty nonnegative integer indices")
    if len(set(record["utts"])) != len(record["utts"]):
        raise ValueError("utts: duplicate index")
    if len(record["turns"]) != len(record["utts"]) or any(type(t) is not str for t in record["turns"]):
        raise ValueError("turns: expected one text per utt")
    if len(record["prefix"]) > 2 or any(type(t) is not str for t in record["prefix"]):
        raise ValueError("prefix: expected at most two text turns")
    features = record["features"]
    _fields(features, {"n_turns": int, "seconds": (int, float),
                      "speech_share": (int, float), "median_gap": (int, float),
                      "gap_before": (int, float)})
    for name in ("seconds", "speech_share", "median_gap", "gap_before"):
        _finite(features[name], f"features.{name}", name in {"seconds", "speech_share"})
    gap = 600 if record["gap_before"] is None else min(600, record["gap_before"])
    if (features["n_turns"] != len(record["utts"])
            or not math.isclose(features["seconds"], record["end"] - record["start"], abs_tol=1e-9)
            or features["gap_before"] != gap):
        raise ValueError("features: inconsistent n_turns/seconds/gap_before")


def validate_label_v2(record: dict) -> None:
    _fields(record, {"id": str, "keep": int, "tags": list, "exclude": list,
                     "skip": bool, "labeler": str, "note": str})
    _block_identity(record["id"])
    if record["keep"] not in KEEP_VALUES:
        raise ValueError("keep: expected 0, 1 or 2")
    _choices(record["tags"], TAGS_V2, "tags")
    _choices(record["exclude"], EXCLUDES_V2, "exclude")
    if record["keep"] == 2 and not record["tags"]:
        raise ValueError("keep 2 requires tags")
    if record["keep"] == 0 and record["tags"]:
        raise ValueError("keep 0 requires empty tags")
    if record["skip"] and (record["keep"] != 0 or record["tags"] or record["exclude"]):
        raise ValueError("skip requires keep 0 and empty tags/exclude")
    prefix, separator, name = record["labeler"].partition(":")
    if prefix not in {"llm", "human"} or not separator or not name.strip():
        raise ValueError("labeler: expected llm:<model> or human:<name>")
