from copy import deepcopy

import pytest

from common.schema import (
    ASRS, EXCLUDES, KINDS, SPLITS,
    validate_label, validate_utterance, validate_window,
)

UTTERANCE = {
    "source": "yt:VIDEO_ID", "utt": 17, "start": 123.45, "end": 129.10,
    "text": "가족 여행", "asr": "whisper", "speaker": None,
}
WINDOW = {
    "id": "yt:VIDEO_ID#17", "source": "yt:VIDEO_ID", "split": "train",
    "clip": 3, "context": ["하나", "둘", "셋"], "current": "가족 여행",
}
LABEL = {
    "id": "yt:VIDEO_ID#17", "story": 2, "kind": ["recall"], "exclude": [],
    "skip": False, "labeler": "llm:model", "note": "추억이다",
}
SCHEMAS = [
    (validate_utterance, UTTERANCE), (validate_window, WINDOW),
    (validate_label, LABEL),
]


@pytest.mark.parametrize("validator,record", SCHEMAS)
def test_valid_record_is_not_modified(validator, record):
    before = deepcopy(record)
    validator(record)
    assert record == before


@pytest.mark.parametrize("validator,record", SCHEMAS)
@pytest.mark.parametrize("value", [None, [], "object", 1, True])
def test_requires_object(validator, record, value):
    with pytest.raises(ValueError):
        validator(value)


@pytest.mark.parametrize("validator,record,field", [
    (validator, record, field)
    for validator, record in SCHEMAS for field in record
])
def test_every_field_is_required(validator, record, field):
    invalid = deepcopy(record)
    del invalid[field]
    with pytest.raises(ValueError):
        validator(invalid)


@pytest.mark.parametrize("validator,record,field,value", [
    (validator, record, field, value)
    for validator, record in SCHEMAS for field, original in record.items()
    for value in [None, True, 1, 1.5, "text", [], {}]
    if type(value) not in (
        (int, float) if field in {"start", "end"}
        else (str, type(None)) if field == "speaker" else (type(original),)
    )
])
def test_rejects_wrong_field_types(validator, record, field, value):
    with pytest.raises(ValueError):
        validator({**record, field: value})


@pytest.mark.parametrize("changes", [
    {"utt": -1}, {"source": ""}, {"source": " \t"},
    {"start": -1}, {"end": -1}, {"end": 123}, {"asr": "other"},
    *[{field: value} for field in ("start", "end")
      for value in (float("inf"), float("-inf"), float("nan"))],
])
def test_invalid_utterance_values(changes):
    with pytest.raises(ValueError):
        validate_utterance({**UTTERANCE, **changes})


@pytest.mark.parametrize("asr", sorted(ASRS))
@pytest.mark.parametrize("speaker", [None, "speaker:1"])
def test_valid_utterance_boundaries(asr, speaker):
    validate_utterance({**UTTERANCE, "utt": 0, "start": 0, "end": 0,
                        "text": "", "asr": asr, "speaker": speaker})


@pytest.mark.parametrize("changes", [
    {"id": ""}, {"id": " "}, {"source": ""}, {"source": " "},
    {"id": "other#17"}, {"id": "yt:VIDEO_ID"},
    {"id": "yt:VIDEO_ID#-1"}, {"id": "yt:VIDEO_ID#1.5"},
    {"id": "yt:VIDEO_ID#١"}, {"id": "yt:VIDEO_ID#"},
    {"clip": -1}, {"split": "validation"},
    {"context": ["a"] * 4}, {"context": [1]}, {"context": [None]},
])
def test_invalid_window_values(changes):
    with pytest.raises(ValueError):
        validate_window({**WINDOW, **changes})


@pytest.mark.parametrize("split", sorted(SPLITS))
@pytest.mark.parametrize("size", range(4))
def test_valid_window_boundaries(split, size):
    validate_window({**WINDOW, "id": "yt:VIDEO_ID#0", "clip": 0,
                     "split": split, "context": [""] * size, "current": ""})


@pytest.mark.parametrize("changes", [
    {"id": ""}, {"id": " "}, {"story": -1}, {"story": 4},
    {"kind": ["unknown"]}, {"kind": [1]}, {"kind": ["recall", "recall"]},
    {"exclude": ["unknown"]}, {"exclude": [None]},
    {"exclude": ["command", "command"]},
    *[{"labeler": value} for value in
      ("", "model", "llm:", "human: ", "robot:model", ":model")],
])
def test_invalid_label_values(changes):
    with pytest.raises(ValueError):
        validate_label({**LABEL, **changes})


@pytest.mark.parametrize("story", [2, 3])
@pytest.mark.parametrize("kind", [[kind] for kind in sorted(KINDS)] + [sorted(KINDS)])
def test_candidate_requires_kind(story, kind):
    validate_label({**LABEL, "story": story, "kind": kind})
    with pytest.raises(ValueError):
        validate_label({**LABEL, "story": story, "kind": []})


@pytest.mark.parametrize("story", [0, 1])
@pytest.mark.parametrize("kind", [[], *[[kind] for kind in sorted(KINDS)],
                                  ["family_fact", "recall"]])
def test_non_candidate_kind_rules(story, kind):
    record = {**LABEL, "story": story, "kind": kind}
    if not kind or (story == 1 and kind == ["family_fact"]):
        validate_label(record)
    else:
        with pytest.raises(ValueError):
            validate_label(record)


@pytest.mark.parametrize("exclude", [[], *[[flag] for flag in sorted(EXCLUDES)],
                                     sorted(EXCLUDES)])
@pytest.mark.parametrize("story", range(4))
def test_exclusion_is_independent_of_story(story, exclude):
    validate_label({**LABEL, "story": story, "kind": ["moment"] if story >= 2 else [],
                    "exclude": exclude, "labeler": "human:검수자", "note": ""})


def test_skip_requires_zero_and_empty_tags():
    validate_label({**LABEL, "story": 0, "kind": [], "skip": True})
    invalid = [
        {"story": 1, "kind": []}, {"story": 1, "kind": ["family_fact"]},
        {"story": 2}, {"story": 3}, {"story": 0},
        *[{"story": 0, "kind": [], "exclude": [flag]} for flag in sorted(EXCLUDES)],
    ]
    for changes in invalid:
        with pytest.raises(ValueError):
            validate_label({**LABEL, "skip": True, **changes})
