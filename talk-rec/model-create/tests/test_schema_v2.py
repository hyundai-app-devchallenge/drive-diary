from copy import deepcopy

import pytest

from common.schema import KEEP_VALUES, TAGS_V2, EXCLUDES_V2, validate_block, validate_label_v2

BLOCK = dict(id="yt:v@0", source="yt:v", split="train", block=0, start=0, end=5,
             gap_before=None, utts=[2, 4], turns=["a", "b"], prefix=[],
             features=dict(n_turns=2, seconds=5, speech_share=.8, median_gap=1, gap_before=600))
LABEL = dict(id="yt:v@0", keep=2, tags=["fun"], exclude=[], skip=False, labeler="llm:model", note="")
SCHEMAS = [(validate_block, BLOCK), (validate_label_v2, LABEL)]


def test_ordered_vocabularies():
    assert KEEP_VALUES == (0, 1, 2)
    assert TAGS_V2 == ("recall", "fun", "kid", "scenery", "plan", "family_news", "heart", "song")
    assert EXCLUDES_V2 == ("optout", "sensitive", "command", "external", "garbled")


@pytest.mark.parametrize("validator,record", SCHEMAS)
def test_valid_immutable(validator, record):
    before = deepcopy(record)
    validator(record)
    assert record == before
    validator({**record, "metadata": "allowed"})


@pytest.mark.parametrize("validator,record", SCHEMAS)
@pytest.mark.parametrize("value", [None, [], "object", 1, True])
def test_requires_object(validator, record, value):
    with pytest.raises(ValueError):
        validator(value)


@pytest.mark.parametrize("validator,record,field", [(v, r, f) for v, r in SCHEMAS for f in r])
def test_required_fields(validator, record, field):
    changed = deepcopy(record)
    del changed[field]
    with pytest.raises(ValueError):
        validator(changed)


@pytest.mark.parametrize("validator,record,field,value", [
    (v, r, f, value) for v, r in SCHEMAS for f, old in r.items()
    for value in [None, True, 1, 1.5, "x", [], {}]
    if type(value) not in ((int, float) if f in {"start", "end"} else
                           (int, float, type(None)) if f == "gap_before" else (type(old),))
])
def test_field_types(validator, record, field, value):
    with pytest.raises(ValueError):
        validator({**record, field: value})


@pytest.mark.parametrize("identity", ["", " ", "yt:v#0", "@0", " @0", "yt:v@-1", "yt:v@1.5", "yt:v@١", "yt:v@"])
@pytest.mark.parametrize("validator,record", SCHEMAS)
def test_identity(validator, record, identity):
    with pytest.raises(ValueError):
        validator({**record, "id": identity})


@pytest.mark.parametrize("changes", [
    dict(source="other"), dict(block=1), dict(block=-1), dict(split="other"),
    dict(start=-1), dict(end=-1), dict(start=6), dict(utts=[]), dict(utts=[1, 1]),
    dict(utts=[True, 2]), dict(utts=[-1, 2]), dict(turns=["a"]), dict(turns=[1, "a"]),
    dict(prefix=["a", "b", "c"]), dict(prefix=[None]),
    *[{key: value} for key in ("start", "end", "gap_before") for value in (float("inf"), float("nan"))],
])
def test_block_constraints(changes):
    with pytest.raises(ValueError):
        validate_block({**BLOCK, **changes})


@pytest.mark.parametrize("field", BLOCK["features"])
def test_features_required_and_finite(field):
    changed = deepcopy(BLOCK)
    del changed["features"][field]
    with pytest.raises(ValueError):
        validate_block(changed)
    for value in (True, "0", None, float("inf"), float("nan")):
        changed = deepcopy(BLOCK)
        changed["features"][field] = value
        with pytest.raises(ValueError):
            validate_block(changed)


@pytest.mark.parametrize("field,value", [("n_turns", 1), ("n_turns", 2.0), ("seconds", -1),
                                         ("seconds", 6), ("gap_before", 0), ("speech_share", -1)])
def test_inconsistent_features(field, value):
    changed = deepcopy(BLOCK)
    changed["features"][field] = value
    with pytest.raises(ValueError):
        validate_block(changed)


def test_valid_block_raw_gap_and_cap():
    for gap in (0, 60, 600, 700, -1):
        changed = deepcopy(BLOCK)
        changed["gap_before"] = gap
        changed["features"]["gap_before"] = min(gap, 600)
        validate_block(changed)


@pytest.mark.parametrize("changes", [
    dict(keep=-1), dict(keep=3), dict(tags=[]), dict(keep=0), dict(tags=["moment"]),
    dict(tags=["fun", "fun"]), dict(tags=[1]), dict(exclude=["other"]),
    dict(exclude=["optout", "optout"]), dict(exclude=[None]),
    *[dict(labeler=v) for v in ("", "llm", "llm:", "human: ", "robot:x", ":x")],
    dict(skip=True), dict(skip=True, keep=0, tags=[], exclude=["optout"]),
    dict(skip=True, keep=1, tags=[]),
])
def test_label_constraints(changes):
    with pytest.raises(ValueError):
        validate_label_v2({**LABEL, **changes})


@pytest.mark.parametrize("keep,tags", [(0, []), (1, []), *[(k, [t]) for k in (1, 2) for t in TAGS_V2]])
@pytest.mark.parametrize("exclude", [[], *[[v] for v in EXCLUDES_V2], list(EXCLUDES_V2)])
def test_keep_tags_exclude_independence(keep, tags, exclude):
    validate_label_v2({**LABEL, "keep": keep, "tags": tags, "exclude": exclude, "labeler": "human:person"})


def test_skip_valid_and_all_tags():
    validate_label_v2({**LABEL, "keep": 0, "tags": [], "skip": True})
    validate_label_v2({**LABEL, "tags": list(TAGS_V2), "labeler": "llm:backend:model:effort"})
