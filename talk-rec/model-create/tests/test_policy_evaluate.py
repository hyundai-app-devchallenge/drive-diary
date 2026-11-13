"""Synthetic generation, split integrity, evaluation and privacy contract."""
from collections import Counter
import json

from common.normalize import normalize
from policy import PolicyGate
from policy.evaluate import evaluate, main
from synth.policy_cases import COMMANDS, EXTERNAL, HELD_OUT_OPTOUT, generate


def test_seed_reproducibility_split_and_corruption():
    a = generate(56)
    assert a == generate(56)
    assert a != generate(57)
    assert len({r["id"] for r in a}) == len(a)
    bases = {}
    for row in a:
        assert row["synthetic"] is True
        assert bases.setdefault(row["base_id"], row["split"]) == row["split"]
        assert row["lines"] and all(isinstance(t, str) and t for t in row["lines"])
    rules = PolicyGate().rules
    assert len(rules["optout_literals"]) >= 40
    for dev, held in ((rules["optout_literals"], HELD_OUT_OPTOUT),
                      (COMMANDS["dev"], COMMANDS["held_out"]), (EXTERNAL["dev"], EXTERNAL["held_out"])):
        assert not {normalize(t).replace(" ", "") for t in dev} & {normalize(t).replace(" ", "") for t in held}
    assert Counter(r["variant"] for r in a)["pleos2_3"] > 0
    mixed = [r for r in a if r["category"] == "mixed"]
    assert mixed and all(r["expected"]["drop_lines"][0] == 2 for r in mixed)


def test_evaluation_metrics_have_correct_denominators():
    rows = [dict(id="synthetic-a", base_id="a", split="dev", category="optout", variant="clean", level=0,
                 lines=["녹음하지 마"], expected=dict(block="block", drop_lines=[0], retro_delete=True)),
            dict(id="synthetic-b", base_id="b", split="dev", category="mixed", variant="clean", level=0,
                 lines=["모래성 컸지", "볼륨 올려", "응 멋졌어"],
                 expected=dict(block="allow", drop_lines=[1], retro_delete=False))]
    result, detail = evaluate(rows, PolicyGate(), repeats=1)
    assert result["criteria"]["dev"]["pass"] is True
    assert result["policy"]["dev"]["n"] == 1
    assert result["grouped"]["dev/mixed"]["drop_exact_rate"] == 1
    assert result["grouped"]["dev/mixed"]["drop_precision"] == 1
    assert result["timing"]["n"] == 2 and result["timing"]["mean_ms"] > 0
    assert "모래성" not in json.dumps([result, detail], ensure_ascii=False)


def test_cli_synthetic_only_and_no_text_output(tmp_path, capsys):
    cases = tmp_path / "cases.jsonl"
    t53 = tmp_path / "blocks.jsonl"
    out = tmp_path / "out"
    row = dict(id="invented", base_id="invented", split="dev", category="optout", variant="clean", level=0,
               lines=["녹음하지 마"], expected=dict(block="block", drop_lines=[0], retro_delete=True), synthetic=True)
    cases.write_text(json.dumps(row, ensure_ascii=False) + "\n", encoding="utf-8")
    t53.write_text(json.dumps(dict(id="synth:1@0", source="synth:1", demo_only=True,
                                  level=0, turns=["창작 비밀 문장"]), ensure_ascii=False) + "\n", encoding="utf-8")
    args = ["--cases", str(cases), "--t53", str(t53), "--out-dir", str(out), "--repeats", "1"]
    assert main(args) == 0
    r = json.loads((out / "results.json").read_text(encoding="utf-8"))
    assert r["counts"]["total"] == 2
    assert len(r["inputs"]["cases_sha256"]) == 64
    for path in out.iterdir():
        text = path.read_text(encoding="utf-8")
        assert "창작 비밀 문장" not in text and "녹음하지 마" not in text
    t53.write_text(json.dumps(dict(source="yt:invented", turns=["창작 비밀 문장"])), encoding="utf-8")
    assert main(args) == 2
    assert "창작 비밀 문장" not in capsys.readouterr().out
