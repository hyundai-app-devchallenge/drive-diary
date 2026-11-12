"""Tiny random safe model + invented inputs exercise the entire T58 path."""
import json

import numpy as np
import pytest

from train.v3 import export


@pytest.fixture
def tiny(tmp_path):
    export.offline()
    import torch
    from transformers import ElectraConfig, ElectraModel, BertTokenizerFast
    from demo.trial.models import block_model
    from train.v3.models import EncoderModel, load_saved
    torch.set_num_threads(1)
    torch.manual_seed(43)
    vocabulary = ['[PAD]', '[UNK]', '[CLS]', '[SEP]', '[MASK]', '가', '나', '다', 'A', 'a']
    (tmp_path / 'vocab.txt').write_text('\n'.join(vocabulary) + '\n', encoding='utf-8')
    tokenizer = BertTokenizerFast(vocab_file=str(tmp_path / 'vocab.txt'), do_lower_case=False)
    config = ElectraConfig(vocab_size=len(vocabulary), embedding_size=8, hidden_size=12,
                           num_hidden_layers=1, num_attention_heads=2, intermediate_size=16,
                           max_position_embeddings=256)
    obj = EncoderModel.__new__(EncoderModel)
    obj.tokenizer, obj.model = tokenizer, block_model(ElectraModel(config)).eval()
    obj.seed, obj.max_length, obj.truncation = 43, 256, 'both'
    obj.cache_revisions = dict(config='invented', safetensors='invented')
    weights = tmp_path / 'weights'
    obj.save(weights)
    restored = load_saved(weights, 'M1')
    return restored, weights


def test_tiny_safe_export_quantization_parity_and_bundle(tiny, tmp_path):
    import onnx
    import torch
    from demo.trial.models import token_batch
    model, weights = tiny
    fp32, int8, mixed = [tmp_path / n for n in ('fp32.onnx', 'int8.onnx', 'mixed.onnx')]
    export.export_onnx(model.model, model.tokenizer, fp32)
    export.quantize(fp32, int8)
    export.quantize(fp32, mixed, embeddings=False)
    for tensor in onnx.load(str(int8)).graph.initializer:
        if 'embeddings' in tensor.name and tensor.name.endswith('weight_quantized'):
            assert tensor.data_type == onnx.TensorProto.INT8
    from onnxruntime.quantization import quantize_dynamic, QuantType
    unsigned = tmp_path / 'unsigned.onnx'
    quantize_dynamic(str(fp32), str(unsigned), weight_type=QuantType.QInt8, per_channel=True,
                     op_types_to_quantize=['MatMul', 'Gemm', 'Gather'],
                     extra_options={'MatMulConstBOnly': True})
    runtimes = {k: export.session(p) for k, p in [('fp32', fp32), ('int8', int8), ('mixed', mixed)]}
    texts = ['가 나 다', 'A a', '나 ' * 400]
    p = export.infer(model.model, runtimes, model.tokenizer, texts)
    unsigned_p = export.infer(model.model, {'unsigned': export.session(unsigned)}, model.tokenizer, texts)
    assert p['int8'] == unsigned_p['unsigned']  # Signed table conversion preserves probabilities.
    assert export.compare(['a', 'b', 'c'], p['torch'], p['fp32'], .5)['max_abs'] < 1e-6
    for name in ('int8', 'mixed'):
        assert export.compare(['a', 'b', 'c'], p['torch'], p[name], .5)['max_abs'] < .01
    # Dynamic batch and both output heads, including auxiliary logits.
    inputs = token_batch(model.tokenizer, texts[:2])
    with torch.inference_mode():
        keep, kind = model.model(**inputs)
    actual_keep, actual_kind = runtimes['fp32'].run(None, {k: v.numpy() for k, v in inputs.items()})
    np.testing.assert_allclose(actual_keep, keep.sigmoid().numpy(), atol=1e-6)
    np.testing.assert_allclose(actual_kind, kind.numpy(), atol=1e-6)
    bundle = tmp_path / 'bundle'
    bundle.mkdir()
    export.bundle_settings(weights, bundle)
    export.golden_file(model.tokenizer, runtimes['int8'], bundle / 'golden.json')
    golden = json.loads((bundle / 'golden.json').read_text(encoding='utf-8'))
    assert len(golden['examples']) == 20 and golden['invented_only']
    for row in golden['examples']:
        assert len(row['input_ids']) == len(row['attention_mask']) == 256
        got = runtimes['int8'].run(None, {k: np.array([row[k]], dtype=np.int64)
                                        for k in ('input_ids', 'attention_mask')})[0][0]
        assert float(got) == row['keep_probability']
    assert golden['examples'][-1]['attention_mask'] == [1] * 256
    settings = json.loads((bundle / 'tokenizer_settings.json').read_text())
    assert settings['nfkc'] is settings['do_lower_case'] is False


def test_both_truncation_keeps_exact_ends_and_lf(tiny):
    from demo.trial.models import token_batch
    from demo.trial.data import input_text
    model, _ = tiny
    text = input_text(['  A 가  ', '', {'text': ' 나 a\n'}])
    assert text == 'A 가\n\n나 a'
    ids = token_batch(model.tokenizer, ['가 ' * 200 + '나 ' * 200])['input_ids'][0].tolist()
    assert ids == [2] + [5] * 127 + [6] * 127 + [3]
    assert token_batch(model.tokenizer, ['A a'])['input_ids'][0][:4].tolist() == [2, 8, 9, 3]


def test_threshold_flips_and_recalibration():
    from train.v3.data import RealExample
    from eval.v3.metrics import select_threshold
    rows = [RealExample(f'fake-{i}', 'fake-source', 'PRIVATE_FAKE', label, 1, 'child_family', False)
            for i, label in enumerate([1, 2, 0, 0])]
    original, quantized, threshold = [.9, .8, .6, .1], [.88, .79, .61, .12], .8
    report = export.compare([r.id for r in rows], original, quantized, threshold)
    assert report['flipped_ids'] == ['fake-1'] and report['agreement'] == .75
    calibrated = select_threshold([r.label for r in rows], quantized)
    assert calibrated == .79
    assert export.compare([r.id for r in rows], original, quantized, threshold, calibrated)['agreement'] == 1
    assert export.primary_metrics(rows, quantized, calibrated) == dict(
        keep_recall=1, none_specificity=1, accuracy=1, auroc=1)


def test_return_type_check_and_privacy_errors(tmp_path, monkeypatch, capsys):
    secret = 'PRIVATE_INVENTED_TRANSCRIPT_SENTINEL'
    for value in ([secret], ([], {secret: secret}), ({secret: secret}, {})):
        with pytest.raises(TypeError) as exc:
            export.checked_labels(value)
        assert secret not in str(exc.value)
    assert export.checked_labels(([], {'vizatae': {}})) == ([], {'vizatae': {}})
    path = tmp_path / 'report.json'
    path.write_text('{}')
    assert export.audit_private([path], [secret])['matches'] == 0
    path.write_text(secret)
    with pytest.raises(ValueError, match='private transcript audit failed'):
        export.audit_private([path], [secret])
    path.write_text(secret, encoding='utf-16')
    with pytest.raises(ValueError, match='private transcript audit failed'):
        export.audit_private([path], [secret])
    multiline = '개인문장확인용가상가족대화\n' * 3
    path.write_text(json.dumps({'text': multiline}, ensure_ascii=False), encoding='utf-8')
    with pytest.raises(ValueError, match='private transcript audit failed'):
        export.audit_private([path], [multiline])
    def fail(*args):
        raise ValueError(secret)
    monkeypatch.setattr(export, 'execute', fail)
    assert export.main(['--output', str(tmp_path)]) == 2
    captured = capsys.readouterr()
    assert secret not in captured.out + captured.err and 'ValueError at' in captured.err
