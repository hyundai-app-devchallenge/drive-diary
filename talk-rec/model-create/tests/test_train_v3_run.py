"""Small invented end-to-end runs, offline safe weights and locked test CLI."""
import hashlib
import json
from pathlib import Path

import numpy as np
import pytest

from train.v3 import models
from train.v3.data import RealExample
from train.v3.run import main


class FakeEncoder(models.LinearModel):
    def __init__(self, seed, cache):
        super().__init__('ngram', seed)
        self.cache_revisions = dict(config='invented', safetensors='invented')


def test_safe_cache_requires_real_safetensors(tmp_path):
    base = tmp_path / ('models--' + models.MODEL.replace('/', '--'))
    (base / 'refs').mkdir(parents=True)
    (base / 'refs/main').write_text('mainhash')
    config = base / 'snapshots/mainhash'
    config.mkdir(parents=True)
    (config / 'config.json').write_text('{}')
    (config / 'vocab.txt').write_text('invented')
    (config / 'pytorch_model.bin').write_bytes(b'never-load')
    marker = base / '.no_exist/other/model.safetensors'
    marker.parent.mkdir(parents=True)
    marker.write_bytes(b'')
    with pytest.raises(FileNotFoundError):
        models.safe_cache(tmp_path)
    safe = base / 'snapshots/safehash'
    safe.mkdir()
    (safe / 'model.safetensors').write_bytes(b'invented-test-placeholder')
    assert models.safe_cache(tmp_path) == (config.resolve(), safe.resolve())


def test_tiny_encoder_reproducible_safe_round_trip(tmp_path, monkeypatch):
    import torch
    from transformers import BertConfig, BertModel, BertTokenizer, AutoModel
    from safetensors.torch import load_file
    vocabulary = ['[PAD]', '[UNK]', '[CLS]', '[SEP]', '[MASK]', 'a', 'b', 'c']
    (tmp_path / 'vocab.txt').write_text('\n'.join(vocabulary), encoding='utf-8')
    BertTokenizer(vocab_file=str(tmp_path / 'vocab.txt')).save_pretrained(tmp_path)
    config = BertConfig(vocab_size=len(vocabulary), hidden_size=12, num_hidden_layers=1,
                        num_attention_heads=2, intermediate_size=16, max_position_embeddings=256)
    torch.manual_seed(1)
    BertModel(config).save_pretrained(tmp_path, safe_serialization=True)
    monkeypatch.setattr(models, 'safe_cache', lambda cache: (tmp_path, tmp_path))
    original = AutoModel.from_pretrained
    calls = []
    def wrapped(*args, **kwargs):
        calls.append(kwargs)
        return original(*args, **kwargs)
    monkeypatch.setattr(AutoModel, 'from_pretrained', wrapped)
    saved = []
    for repeat in range(2):
        model = models.EncoderModel(43, tmp_path, max_length=16, device='cpu')
        model.fit(['a b', 'b c', 'c a'], [0, 1, 2], epochs=1, batch_size=3)
        probability, kinds = model.predict(['a b', 'b c'])
        directory = tmp_path / f'saved{repeat}'
        model.save(directory)
        saved.append(load_file(str(directory / 'model.safetensors')))
        restored = models.load_saved(directory, 'M1')
        assert restored.predict(['a b', 'b c']) == (probability, kinds)
        assert not (directory / 'model.pt').exists()
    assert all(torch.equal(saved[0][k], saved[1][k]) for k in saved[0])
    assert all(c['use_safetensors'] and c['local_files_only'] and not c['trust_remote_code'] for c in calls)


def test_m0_reproducibility_and_frozen_restore(tmp_path):
    models.offline_environment()
    texts, labels = ['창작 길 안내', '창작 바다 추억', '창작 토끼 구름'] * 4, [0, 1, 2] * 4
    outputs = []
    for _ in range(2):
        model = models.LinearModel('ngram', 43)
        model.fit(texts, labels)
        outputs.append(model.predict(texts))
    assert outputs[0] == outputs[1]
    model.save(tmp_path)
    assert models.load_saved(tmp_path, 'M0').predict(texts) == outputs[0]


def fake_run(tmp_path, monkeypatch):
    from train.v3 import data
    from synth.io import write_jsonl
    monkeypatch.chdir(tmp_path)
    synth = tmp_path / '.local/synth/t53'
    synth.mkdir(parents=True)
    phrases = ['가상 길 안내 오른쪽', '가상 어제 바다 추억 이야기', '가상 구름 토끼 감탄']
    blocks, labels = [], []
    for i in range(60):
        identity = f'synth:{i}@0'
        blocks.append(dict(id=identity, source=f'synth:{i}', turns=[phrases[i % 3]]))
        labels.append(dict(id=identity, verdict=data.LABELS[i % 3], pair_id=None))
    write_jsonl(synth / 'blocks.jsonl', blocks)
    write_jsonl(synth / 'labels.jsonl', labels)
    write_jsonl(synth / 'cards.jsonl', [dict(card=i) for i in range(60)])
    rows = [RealExample(f'fake-{i}', f'source-{i//3}', 'PRIVATE_INVENTED_PHRASE', i % 3,
                        10, 'child_family' if i < 6 else 'friends', i == 1) for i in range(9)]
    usable = {r.source: 60 for r in rows}
    def load_dev(root, *args, **kwargs):
        assert kwargs.get('split', 'dev') == 'dev'
        assert not kwargs.get('open_test', False)
        return rows, usable, dict(unknown_cast_blocks=1)
    monkeypatch.setattr(data, 'load_dev', load_dev)
    for n in (1, 2, 3):
        write_jsonl(tmp_path / f'.local/real/v1/reference/reference-v3-dev/r{n}/predictions.jsonl',
                    [dict(id=r.id, verdict=data.LABELS[r.label], none_reason='') for r in rows])
    monkeypatch.setattr(models, 'EncoderModel', FakeEncoder)
    monkeypatch.setattr(models, 'safe_cache', lambda cache: (synth, synth))
    return rows


def test_small_end_to_end_resume_and_numeric_privacy(tmp_path, monkeypatch, capsys):
    fake_run(tmp_path, monkeypatch)
    args = ['--run-name', 'fake', '--sizes', '30', '60', '--seeds', '43', '44', '45', '--bootstrap', '10', '--repeats', '1']
    assert main(args) == 0
    output = tmp_path / '.local/train/v3/fake'
    before = json.loads((output / 'results.json').read_text(encoding='utf-8'))
    assert before['full_size'] == 60 and before['selection']['model'] == 'M0'
    assert before['baselines']['gemini']['child_family']['included']['metrics']['auroc']['mean'] is None
    preds = [json.loads(line) for line in (output / 'preds.jsonl').read_text(encoding='utf-8').splitlines()]
    assert preds and all('text' not in row and 'source' not in row for row in preds)
    for path in output.rglob('*'):
        if path.suffix in ('.json', '.jsonl', '.md'):
            assert 'PRIVATE_INVENTED' not in path.read_text(encoding='utf-8')
    assert 'PRIVATE_INVENTED' not in capsys.readouterr().out
    monkeypatch.setattr('train.v3.run.train_one', lambda *a: pytest.fail('resume must not retrain'))
    assert main(args + ['--resume']) == 0
    after = json.loads((output / 'results.json').read_text(encoding='utf-8'))
    assert before['models'] == after['models']
    assert not (output / 'test-attempt.json').exists()


def test_cli_exception_never_echoes_input(tmp_path, monkeypatch, capsys):
    fake_run(tmp_path, monkeypatch)
    path = tmp_path / '.local/synth/t53/labels.jsonl'
    path.write_text('{"id": "PRIVATE_INVENTED_SECRET", broken', encoding='utf-8')
    assert main(['--run-name', 'bad', '--models', 'M0']) == 2
    captured = capsys.readouterr()
    assert 'PRIVATE_INVENTED' not in captured.err
    assert 'JSONDecodeError at' in captured.err


def test_frozen_test_requires_flag_and_runs_once_on_fake_data(tmp_path, monkeypatch):
    from eval.v3.__main__ import frozen_test
    from train.v3 import data
    from eval.v3 import metrics
    output = tmp_path / 'run'
    weights = output / 'weights'
    weights.mkdir(parents=True)
    (weights / 'model.pkl').write_bytes(b'invented-test-placeholder')
    (output / 'candidate.json').write_text(json.dumps(dict(model='M0', weights='weights', threshold=.95,
        artifact_hashes={'model.pkl': hashlib.sha256(b'invented-test-placeholder').hexdigest()})), encoding='utf-8')
    calls = []
    def load_dev(root, **kwargs):
        calls.append(kwargs)
        return [RealExample('fake', 'source', 'PRIVATE_INVENTED', 1, 20, 'child_family', False)], {'source': 60}, {}
    monkeypatch.setattr(data, 'load_dev', load_dev)
    class FakeModel:
        def predict(self, texts):
            return [.9], [1]
    monkeypatch.setattr(models, 'load_saved', lambda *a: FakeModel())
    original = metrics.evaluate
    monkeypatch.setattr(metrics, 'evaluate', lambda rows, runs, usable: original(rows, runs, usable, bootstrap=3))
    with pytest.raises(ValueError):
        frozen_test(output)
    assert calls == [] and not (output / 'test-attempt.json').exists()
    frozen_test(output, open_test=True)
    assert calls == [dict(split='test', open_test=True)]
    pred = json.loads((output / 'test-preds.jsonl').read_text())
    assert pred['prediction'] == 'none'  # fixed .95, never refitted on test
    with pytest.raises(FileExistsError):
        frozen_test(output, open_test=True)
    assert len(calls) == 1
