import math

import numpy as np
import pytest
import torch
from transformers import ElectraConfig, ElectraModel

from train.model import Student, assemble_loss, fit_temperature, flag_pos_weights


@pytest.fixture(autouse=True)
def cpu_threads():
    previous = torch.get_num_threads()
    torch.set_num_threads(1)
    yield
    torch.set_num_threads(previous)


def test_tiny_cpu_shared_encoder_four_heads_backward():
    model = Student(ElectraModel(ElectraConfig(vocab_size=24, embedding_size=8, hidden_size=16,
                        num_hidden_layers=1, num_attention_heads=2, intermediate_size=24)), dropout=0)
    logits = model(torch.tensor([[1, 2, 3]]), torch.ones(1, 3, dtype=torch.long), torch.tensor([[0, 1, 1]]))
    assert {k: tuple(v.shape) for k, v in logits.items()} == {"binary": (1, 1), "ordinal": (1, 1), "kind": (1, 5), "exclude": (1, 5)}
    sum(v.sum() for v in logits.values()).backward()
    assert model.encoder.embeddings.word_embeddings.weight.grad.abs().sum() > 0
    assert all(p.requires_grad for p in model.encoder.parameters())


def test_loss_hand_weighted_multitask_and_ordinal_soft_target():
    logits = {k: torch.zeros(2, n, requires_grad=True) for k, n in [("binary", 1), ("ordinal", 1), ("kind", 5), ("exclude", 5)]}
    targets = {k: torch.zeros_like(v) for k, v in logits.items()}
    targets["binary"][0] = 1
    targets["ordinal"][:] = torch.tensor([[1/3], [2/3]])
    targets["exclude"][0, 0] = 1
    weights = dict(binary=1, ordinal=.2, kind=.1, exclude=.2)
    loss, parts = assemble_loss(logits, targets, torch.tensor([3., 1.]), weights,
                                {"exclude": torch.tensor([5., 1., 1., 1., 1.])})
    # Row 0 exclusion BCE mean is (5+4)/5 log2; row 1 is log2.
    exclude = (3 * 9/5 + 1) / 4 * math.log(2)
    assert float(parts["exclude"].detach()) == pytest.approx(exclude)
    assert float(loss.detach()) == pytest.approx(1.3 * math.log(2) + .2 * exclude)
    loss.backward()
    assert logits["binary"].grad[0, 0] == pytest.approx(-.375)
    assert logits["ordinal"].grad[0, 0] > 0 and logits["ordinal"].grad[1, 0] < 0


def test_training_only_flag_weights_cap_and_absent():
    rows = [dict(label=dict(kind=["recall"] if i == 0 else [], exclude=[])) for i in range(100)]
    result = flag_pos_weights(rows, 7)
    assert result["kind"].tolist() == [7, 1, 1, 1, 1]
    assert result["exclude"].tolist() == [1]*5


def test_temperature_reduces_toy_nll_preserves_ranking():
    logits = [-8., -4., 4., 8.]
    result = fit_temperature(logits, [0, 1, 0, 1])
    assert 1 < result["temperature"] <= 20
    assert result["nll_after"] < result["nll_before"]
    assert np.argsort(logits).tolist() == np.argsort(np.array(logits)/result["temperature"]).tolist()
    assert result == fit_temperature(logits, [0, 1, 0, 1])


@pytest.mark.parametrize("logits,labels", [([], []), ([1, 2], [0, 0]), ([1, 2], [1, 1])])
def test_temperature_degenerate_dev(logits, labels):
    assert fit_temperature(logits, labels)["temperature"] == 1


def test_checkpoint_reload_on_cpu(tmp_path):
    from train.engine import load_student
    from train.data import write_json
    # A tiny locally constructed tokenizer; never touches HF network/cache.
    from transformers import BertTokenizerFast
    (tmp_path / "vocab.txt").write_text("[PAD]\n[UNK]\n[CLS]\n[SEP]\n[MASK]\na\nb\n", encoding="utf-8")
    tokenizer = BertTokenizerFast(vocab_file=str(tmp_path / "vocab.txt"))
    tokenizer.save_pretrained(tmp_path)
    encoder = ElectraModel(ElectraConfig(vocab_size=7, embedding_size=8, hidden_size=16,
                         num_hidden_layers=1, num_attention_heads=2, intermediate_size=24))
    model = Student(encoder, 0).eval()
    encoder.config.save_pretrained(tmp_path)
    torch.save(model.state_dict(), tmp_path / "model.pt")
    write_json(tmp_path / "student.json", dict(head_dropout=0))
    write_json(tmp_path / "temperature.json", dict(temperature=2))
    loaded, _, _, t = load_student(tmp_path)
    x = dict(input_ids=torch.tensor([[2, 5, 3]]), attention_mask=torch.ones(1, 3, dtype=torch.long), token_type_ids=torch.zeros(1, 3, dtype=torch.long))
    assert torch.equal(model(**x)["binary"], loaded(**x)["binary"])
    assert t == 2
