"""T07 kappas checked against explicit hand calculations, no sklearn dependency."""

import json

import pytest

from tools.gold.agreement import agreement, compare, load_labels, main, markdown


def row(identifier, story=0, **changes):
    return {"id": identifier, "story": story, "kind": ["recall"] if story >= 2 else [],
            "exclude": [], "skip": False, "labeler": "human:fixture", "note": "", **changes}


def test_binary_hand_computed():
    # 4/6 agree; marginal positives 3/6 and 5/6 -> expected agreement 1/2.
    # kappa = (2/3 - 1/2)/(1 - 1/2) = 1/3.
    result = agreement([0, 0, 0, 1, 1, 1], [0, 1, 1, 1, 1, 1])
    assert result["n"] == 6
    assert result["exact"] == pytest.approx(2 / 3)
    assert result["kappa"] == pytest.approx(1 / 3)


def test_quadratic_hand_computed():
    # A=[0,1,2,3], B=[0,2,2,3]. Observed squared distance sum=1.
    # Cross-marginal squared sum=(0+4+4+9)+(1+1+1+4)
    #                              +(4+0+0+1)+(9+1+1+0)=40.
    # expected mean distance=40/16=2.5; observed mean=1/4.
    # kappa=1-(1/4)/(40/16)=0.9; exact=3/4.
    result = agreement([0, 1, 2, 3], [0, 2, 2, 3], quadratic=True)
    assert result["exact"] == 0.75
    assert result["kappa"] == pytest.approx(0.9)


def test_quadratic_uses_distance_not_just_category_mismatch():
    # Uniform marginals; expected squared sum=40, observed sum=20.
    # kappa = 1 - (20/4)/(40/16) = -1.
    assert agreement([0, 1, 2, 3], [3, 2, 1, 0], quadratic=True)["kappa"] == -1
    assert agreement([0, 1, 2, 3], [3, 2, 1, 0])["kappa"] == pytest.approx(-1 / 3)


@pytest.mark.parametrize("quadratic", [False, True])
def test_zero_variance_and_empty(quadratic):
    assert agreement([], [], quadratic=quadratic) == {"n": 0, "exact": None, "kappa": None}
    assert agreement([0, 0], [0, 0], quadratic=quadratic) == {"n": 2, "exact": 1, "kappa": None}
    assert agreement([0, 0], [1, 1], quadratic=quadratic)["kappa"] == 0
    assert agreement([0, 0], [0, 1], quadratic=quadratic)["kappa"] == 0
    with pytest.raises(ValueError):
        agreement([0], [])


def test_shared_ids_skip_and_flags_hand_computed():
    a = {str(i): row(str(i), score) for i, score in enumerate([0, 1, 2, 3, 0, 0])}
    b = {str(i): row(str(i), score, labeler="llm:fixture") for i, score in enumerate([0, 2, 2, 3, 0, 0])}
    a["4"]["skip"] = True
    b["5"]["skip"] = True
    a["4"]["exclude"] = []
    a["6"] = row("6", 3)  # Not shared.
    # command A positives=2, B positives=2 over n=6. Two disagreements.
    # exact=4/6, expected=(2*2+4*4)/36=5/9, kappa=1/4.
    for identifier in ("0", "1"):
        a[identifier]["exclude"] = ["command"]
    for identifier in ("0", "2"):
        b[identifier]["exclude"] = ["command"]
    result = compare(a, b)
    assert (result["shared_n"], result["story_n"], result["skipped_either"], result["skipped_both"], result["skipped_only_a"], result["skipped_only_b"]) == (6, 4, 2, 0, 1, 1)
    metrics = result["metrics"]
    # binary A=[0,0,1,1], B=[0,1,1,1], expected=.5 -> kappa=.5.
    assert metrics["story>=2"] == {"n": 4, "exact": 0.75, "kappa": 0.5}
    assert metrics["story:0-3 (quadratic)"]["kappa"] == pytest.approx(0.9)
    assert metrics["exclude:command"]["kappa"] == pytest.approx(0.25)
    # recall: 5/6 agreement, expected=1/2 -> kappa=2/3.
    assert metrics["kind:recall"]["kappa"] == pytest.approx(2 / 3)
    # skip: 4/6 agreement, expected=(1+25)/36=13/18 -> kappa=-1/5.
    assert metrics["skip"]["kappa"] == pytest.approx(-0.2)
    assert metrics["exclude:external"]["kappa"] is None
    assert len(metrics) == 13


def test_all_skipped_and_disjoint():
    both = {"a": row("a", skip=True)}
    assert compare(both, both)["metrics"]["story>=2"]["n"] == 0
    result = compare(both, {"b": row("b")})
    assert result["shared_n"] == 0
    assert all(m["kappa"] is None and m["exact"] is None for m in result["metrics"].values())


def test_cli_markdown_json_and_three_pairs(tmp_path, capsys):
    files = [tmp_path / (name + ".jsonl") for name in ("human", "llm", "third")]
    for path in files:
        path.write_text(json.dumps(row("a", labeler="llm:fixture", metadata="allowed")) + "\n", encoding="utf-8")
    main([str(p) for p in files])
    output = capsys.readouterr().out
    assert "| exact agreement | kappa |" in output
    assert "n/a" in output
    main([str(p) for p in files] + ["--json"])
    decoded = json.loads(capsys.readouterr().out)
    assert len(decoded["pairs"]) == 3
    assert decoded["pairs"][0]["metrics"]["story>=2"]["kappa"] is None
    assert "null" not in output
    assert "\\|" in markdown([{**decoded["pairs"][0], "a": "a|b"}])


def test_bad_input_and_duplicates(tmp_path):
    path = tmp_path / "labels.jsonl"
    line = json.dumps(row("a")) + "\n"
    path.write_text(line * 2, encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate"):
        load_labels(path)
    path.write_text(json.dumps(row("a", story=2, kind=[])), encoding="utf-8")
    with pytest.raises(ValueError, match="kind"):
        load_labels(path)
    with pytest.raises(SystemExit):
        main([str(path)])
