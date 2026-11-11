"""All speech in these tests is invented. Never read .local demo transcripts."""
import json
from pathlib import Path

import numpy as np
import pytest

from demo.trial.data import Example, input_text, load_real, load_synth, split_scenes
from demo.trial.metrics import aggregate, metrics, select_threshold
from demo.trial.run import main


def jsonl(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")


@pytest.fixture
def invented(tmp_path):
    synth = tmp_path / "synth"
    labels = ("none", "story", "moment")
    phrases = ("창작 전용 표식 길 안내 오른쪽 가자", "창작 전용 표식 어제 바다에서 조개를 찾았어", "창작 전용 표식 지금 구름이 토끼 같아")
    blocks, verdicts = [], []
    for i in range(30):
        label = i % 3
        blocks.append(dict(id=f"synth:{i + 1}@0", source=f"synth:{i + 1}",
                           turns=[phrases[label], f"창작 숫자 {i}"]))
        verdicts.append(dict(id=blocks[-1]["id"], verdict=labels[label], pattern="unused", boundary=False, level=i % 3))
    jsonl(synth / "blocks.jsonl", blocks)
    jsonl(synth / "labels.jsonl", verdicts)
    judge = tmp_path / "judge.json"
    human = {f"invented-{i}": labels[i % 3] for i in range(6)}
    judge.write_text(json.dumps(dict(ids=list(human), human=human, pred=dict(pleos=human))), encoding="utf-8")
    asr = tmp_path / "asr.jsonl"
    jsonl(asr, [dict(id=i, config="pleos", lines=[dict(start=0, end=1, text=phrases[labels.index(label)])])
                for i, label in human.items()])
    return synth, judge, asr


def test_shared_input_and_group_split(invented, tmp_path):
    synth, judge, asr = invented
    assert input_text([" 가상 줄 ", "둘"]) == input_text([{"text": " 가상 줄 "}, {"text": "둘"}]) == "가상 줄\n둘"
    a, skipped = load_synth([synth, synth, tmp_path / "absent"])
    assert skipped == 1 and len(a) == 60
    train, val = split_scenes(a)
    assert len(train) == 48 and len(val) == 12
    assert not {r.scene for r in train} & {r.scene for r in val}
    assert [r.id for r in val] == [r.id for r in split_scenes(a)[1]]
    assert set(r.label for r in val) == {0, 1, 2}
    real, gemini = load_real(judge, asr)
    assert len(real) == len(gemini) == 6
    jsonl(asr, [dict(id=r.id, config="pleos", lines=[]) for r in real] * 2)
    with pytest.raises(ValueError, match="duplicate"):
        load_real(judge, asr)


def test_conflicting_scene_labels_rejected():
    with pytest.raises(ValueError, match="conflicting"):
        split_scenes([Example("a", 1, "창작", 0), Example("b", 1, "창작", 1)])


def test_metrics_threshold_and_bootstrap():
    y, p = [0, 0, 1, 2], [.1, .3, .7, .9]
    t = select_threshold(y, p)
    m = metrics(y, p, t, [0, 0, 1, 2])
    assert m == dict(accuracy=1., keep_recall=1., keep_precision=1., auroc=1., accuracy3=1., tn=2, fp=0, fn=0, tp=2)
    assert metrics(y, [1.] * 4)["auroc"] == .5
    assert metrics([1, 2], [.5, .5])["auroc"] is None
    assert metrics(y, [0.] * 4)["keep_precision"] == 0
    runs = [dict(probability=p, threshold=t, pred3=[0, 0, 1, 2])]
    a = aggregate(y, runs, bootstrap=100)
    assert a == aggregate(y, runs, bootstrap=100)
    assert a["accuracy"]["ci_low"] == a["accuracy"]["ci_high"] == 1
    assert 0 < a["auroc"]["ci_valid"] < 100


def test_ngram_shape_end_to_end(invented, tmp_path, monkeypatch):
    synth, judge, asr = invented
    monkeypatch.chdir(tmp_path)
    assert main(["--models", "ngram", "shape", "--synth-dir", str(synth), "--synth-dir", str(tmp_path / "missing"),
                 "--judge", str(judge), "--asr", str(asr), "--run-name", "fake-test", "--bootstrap", "30",
                 "--repeats", "2"]) == 0
    output = tmp_path / ".local/demo/trial/fake-test"
    assert not (output / "weights").exists()
    results = json.loads((output / "results.json").read_text(encoding="utf-8"))
    assert results["counts"]["skipped_synth_dirs"] == 1
    assert results["counts"]["real"] == 6
    assert set(results["models"]) == {"ngram", "shape", "all_keep", "gemini_pleos"}
    def numbers_only(item):
        if isinstance(item, dict):
            for value in item.values(): numbers_only(value)
        elif isinstance(item, list):
            for value in item: numbers_only(value)
        else:
            assert item is None or type(item) in (int, float)
    numbers_only(results)
    preds = [json.loads(line) for line in (output / "preds.jsonl").read_text(encoding="utf-8").splitlines()]
    for row in preds:
        assert "text" not in row
        if row["model"] in ("ngram", "shape"):
            t = results["models"][row["model"]]["per_seed"][str(row["seed"])]["threshold"]
            assert row["prediction"] == ("keep" if row["probability"] >= t else "none")
    assert {r["seed"] for r in preds if r["model"] == "ngram"} == {43, 44, 45}
    for path in output.iterdir():
        assert "창작 전용 표식" not in path.read_text(encoding="utf-8")
    assert "넓은 구간" in (output / "report.md").read_text(encoding="utf-8")


def test_encoder_random_tiny_one_step(tmp_path, monkeypatch):
    import torch
    from transformers import AutoConfig, AutoModel, AutoTokenizer, BertConfig, BertModel, BertTokenizer
    from demo.trial.models import EncoderModel, token_batch, benchmark
    vocabulary = ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "a", "b", "c", "d", "e", "f"]
    vocab = tmp_path / "vocab.txt"
    vocab.write_text("\n".join(vocabulary), encoding="utf-8")
    tokenizer = BertTokenizer(vocab_file=str(vocab))
    config = BertConfig(vocab_size=len(vocabulary), hidden_size=12, num_hidden_layers=1,
                        num_attention_heads=2, intermediate_size=16, max_position_embeddings=256)
    calls = []
    def local_tokenizer(*args, **kwargs):
        calls.append(kwargs)
        return tokenizer
    def local_encoder(*args, **kwargs):
        calls.append(kwargs)
        return BertModel(config)
    monkeypatch.setattr(AutoTokenizer, "from_pretrained", local_tokenizer)
    monkeypatch.setattr(AutoModel, "from_pretrained", local_encoder)
    monkeypatch.setattr(AutoConfig, "from_pretrained", lambda *a, **kw: config)
    model = EncoderModel("invented/tiny", 43, tmp_path, 256, "both", "cpu")
    before = model.model.binary.weight.detach().clone()
    model.fit(["a b c", "d e f", "b c d"], [0, 1, 2], epochs=1, batch_size=3)
    assert not torch.equal(before, model.model.binary.weight.detach())
    probability, pred3 = model.predict(["a b"])
    assert len(probability) == len(pred3) == 1 and 0 <= probability[0] <= 1
    assert all(k["local_files_only"] is True and k["trust_remote_code"] is False for k in calls)
    assert model.storage_bytes() > 0 and model.parameters() > 0
    assert benchmark(model, repeats=2) > 0
    model.save(tmp_path / "saved")
    assert (tmp_path / "saved/model.pt").exists()
    text = "a b c d e f"
    expected = {"head": ["a", "b", "c", "d"], "tail": ["c", "d", "e", "f"], "both": ["a", "b", "e", "f"]}
    for mode, tokens in expected.items():
        batch = token_batch(tokenizer, [text], max_length=6, truncation=mode)
        assert batch["input_ids"].shape == (1, 6)
        assert batch["input_ids"][0, 1:-1].tolist() == tokenizer.convert_tokens_to_ids(tokens)


def test_generic_fast_v5_tokenizer_and_modernbert_cpu_step(tmp_path):
    import torch
    from tokenizers import Tokenizer, models, pre_tokenizers, processors
    from transformers import PreTrainedTokenizerFast, ModernBertConfig, ModernBertModel
    from demo.trial.models import load_tokenizer, compatible_config, token_batch, block_model, train_step
    backend = Tokenizer(models.WordLevel({"[PAD]": 0, "[UNK]": 1, "[CLS]": 2, "[SEP]": 3,
                                          "a": 4, "b": 5, "c": 6, "d": 7, "e": 8, "f": 9}, unk_token="[UNK]"))
    backend.pre_tokenizer = pre_tokenizers.Whitespace()
    backend.post_processor = processors.TemplateProcessing(single="[CLS] $A [SEP]",
                                                           special_tokens=[("[CLS]", 2), ("[SEP]", 3)])
    tokenizer = PreTrainedTokenizerFast(tokenizer_object=backend, pad_token="[PAD]", unk_token="[UNK]")
    tokenizer.save_pretrained(tmp_path)
    path = tmp_path / "tokenizer_config.json"
    config_json = json.loads(path.read_text(encoding="utf-8"))
    config_json["tokenizer_class"] = "TokenizersBackend"
    path.write_text(json.dumps(config_json), encoding="utf-8")
    tokenizer = load_tokenizer(str(tmp_path), tmp_path)
    batch = token_batch(tokenizer, ["a b c d e f"], max_length=6, truncation="both")
    assert batch["input_ids"].tolist() == [[2, 4, 5, 8, 9, 3]]
    assert batch["attention_mask"].tolist() == [[1] * 6]
    config = ModernBertConfig(vocab_size=10, hidden_size=16, intermediate_size=24,
                             num_hidden_layers=2, num_attention_heads=2, max_position_embeddings=256,
                             pad_token_id=0, bos_token_id=2, eos_token_id=3, cls_token_id=2, sep_token_id=3,
                             rope_parameters={"full_attention": {"rope_type": "default", "rope_theta": 160000},
                                              "sliding_attention": {"rope_type": "default", "rope_theta": 160000}})
    config = compatible_config(config)
    assert config.local_rope_theta == config.global_rope_theta == 160000
    config._attn_implementation = "sdpa"
    model = block_model(ModernBertModel(config))
    optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3)
    assert np.isfinite(train_step(model, batch, torch.tensor([1]), optimizer))


def test_cli_privacy_on_malformed_inputs(tmp_path, capsys):
    bad = tmp_path / "bad"
    jsonl(bad / "blocks.jsonl", [dict(id="x", source="synth:1", turns=["창작 비밀 문장"])])
    jsonl(bad / "labels.jsonl", [dict(id="x", verdict="창작 비밀 문장")])
    assert main(["--synth-dir", str(bad), "--models", "ngram", "--run-name", "invalid"]) == 2
    assert "창작 비밀 문장" not in capsys.readouterr().err
