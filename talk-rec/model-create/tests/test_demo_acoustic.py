import math
import sys
from types import SimpleNamespace
import wave

import numpy as np
import pytest

from demo import acoustic as a


def sine(frequency=200, seconds=1, amplitude=.5, rate=a.RATE):
    return (amplitude * np.sin(2 * np.pi * frequency * np.arange(round(seconds * rate)) / rate)).astype(np.float32)


def fixture():
    return dict(id='s@0', source='s', block=0, start=2., end=5., utts=[0, 1, 2], turns=['', '', ''],
                audio=dict(path='unused', start=2., end=5.),
                turn_times=[dict(utt=0, start=2., end=3.), dict(utt=1, start=2.5, end=3.5), dict(utt=2, start=4., end=4.5)])


def test_rms_silence_and_sine():
    assert a.rms_db([]) == -120
    assert a.rms_db(np.zeros(a.RATE)) == -120
    assert a.rms_db(sine()) == pytest.approx(20 * math.log10(.5 / math.sqrt(2)), abs=1e-5)


@pytest.mark.parametrize('frequency', [80, 150, 220, 440, 550])
def test_pitch_sine(frequency):
    result = a.pitch(sine(frequency))
    assert result['f0_median'] == pytest.approx(frequency, rel=.01)
    assert result['f0_range'] < 1
    assert result['voiced_share'] == 1


def test_pitch_silence_short_and_mixed():
    assert a.pitch(np.zeros(a.RATE)) == dict(f0_median=None, f0_range=None, voiced_share=0)
    assert a.pitch(np.ones(20))['f0_median'] is None
    assert .45 < a.pitch(np.concatenate([sine(), np.zeros(a.RATE)]))['voiced_share'] < .55


def test_overlap_union_not_pairwise_double_count():
    assert a.overlap_share([(0, 3), (1, 4), (2, 4)], 0, 4) == .75
    assert a.overlap_share([(0, 1), (1, 2)], 0, 2) == 0
    assert a.overlap_share([(-1, 3), (1, 9)], 0, 2) == .5
    assert a.overlap_share([], 0, 0) == 0


@pytest.mark.parametrize('seconds,count', [(1, 1), (5, 1), (5.1, 2), (7.5, 2), (10, 3), (10.1, 4)])
def test_window_coverage(seconds, count):
    windows = a.window_ranges(seconds)
    assert len(windows) == count
    assert windows[0][0] == 0 and windows[-1][1] == seconds
    assert all(x[1] >= y[0] for x, y in zip(windows, windows[1:]))


def test_window_max_and_mean():
    windows = [dict(start=0, end=5, events={k: .2 for k in a.EVENT_LABELS}),
               dict(start=2.5, end=7.5, events={k: .8 for k in a.EVENT_LABELS})]
    maximum, mean = a.summarize_events(windows, 4, 6)
    assert maximum['laughter'] == .8
    assert mean['singing'] == .5
    assert a.summarize_events(windows, 8, 9)[0]['music'] == 0
    assert a.summarize_events(windows, 0, 2)[1]['speech'] == .2


def test_config_label_mapping_uses_names():
    names = list(dict.fromkeys(n for v in a.EVENT_LABELS.values() for n in v))
    config = {str(i + 100): n for i, n in enumerate(reversed(names))}
    mapping = a.resolve_labels(config)
    assert {config[str(i)] for i in mapping['laughter'].values()} == set(a.EVENT_LABELS['laughter'])
    with pytest.raises(ValueError, match='primary'):
        a.resolve_labels({})


def test_clustering_two_groups_unknown_and_bounds():
    x = [np.array([1., 0]), np.array([.99, .01]), None, np.array([0., 1]), np.array([.01, .99])]
    assert a.cluster_speakers(x, .1) == ['A', 'A', '?', 'B', 'B']
    assert a.cluster_speakers([None, None]) == ['?', '?']
    assert a.cluster_speakers([np.ones(2)]) == ['A']
    assert len(set(a.cluster_speakers(list(np.eye(8)), 0))) == 6
    assert len(set(a.cluster_speakers([np.ones(2)] * 4))) == 2
    with pytest.raises(ValueError, match='embedding'):
        a.cluster_speakers([np.zeros(2)])


def test_fake_analysis_formulas_and_short_turn():
    b = fixture()
    samples = np.concatenate([sine(seconds=1.5), np.zeros(round(.5 * a.RATE)), sine(seconds=1, amplitude=.1)])
    row, embeddings = a.analyze_block(b, samples, a.FakeEvents(), a.FakeSpeaker())
    assert row['turns'][0]['f0_median'] == pytest.approx(200, rel=.01)
    assert embeddings[-1] is None
    assert row['block']['overlap_share'] == pytest.approx(.5 / 3)
    assert row['block']['rms_std'] == pytest.approx(np.std([t['rms_db'] for t in row['turns']]))
    assert row['block']['rms_peak_jump'] == pytest.approx(0)
    assert len(row['windows']) == 1
    assert all(row['turns'][0]['events'][k] == 0 for k in a.EVENT_LABELS)


def test_source_wide_clustering_and_timing():
    b = fixture()
    rows, stats, clustering = a.analyze([b], a.FakeEvents(), a.FakeSpeaker(), decoder=lambda *_: sine(seconds=3))
    assert rows[0]['turns'][-1]['speaker'] == '?'
    assert rows[0]['block']['n_speakers'] == 2
    assert rows[0]['block']['speaker_turn_changes'] == 1
    assert stats[0]['id'] == b['id'] and stats[0]['seconds'] >= clustering >= 0


def test_rms_positive_jump_from_amplitude_ratio():
    b = fixture()
    b['turn_times'] = [dict(utt=0, start=2., end=3.), dict(utt=1, start=3., end=4.), dict(utt=2, start=4., end=5.)]
    samples = np.concatenate([sine(amplitude=.1), sine(amplitude=.2), sine(amplitude=.1)])
    row, _ = a.analyze_block(b, samples, a.FakeEvents(), a.FakeSpeaker())
    assert row['block']['rms_peak_jump'] == pytest.approx(20 * math.log10(2), abs=1e-5)
    assert row['block']['rms_std'] == pytest.approx(20 * math.log10(2) * math.sqrt(2) / 3, abs=1e-5)


def test_pyav_decode_resamples_stereo_and_seeks(tmp_path):
    path = tmp_path / 'tone.wav'
    rate = 48000
    signal = sine(seconds=2, rate=rate)
    stereo = np.column_stack([signal, signal])
    with wave.open(str(path), 'wb') as out:
        out.setnchannels(2)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes((stereo * 32767).astype('<i2').tobytes())
    result = a.decode_audio(path, .5, 1.5)
    assert result.shape == (a.RATE,)
    assert a.pitch(result)['f0_median'] == pytest.approx(200, rel=.01)
    assert a.rms_db(result) == pytest.approx(a.rms_db(signal), abs=.05)
    with pytest.raises(ValueError, match='not available'):
        a.decode_audio(path, 3, 4)


def test_ast_adapter_offline_cache_and_sigmoid(monkeypatch, tmp_path):
    import torch
    names = list(dict.fromkeys(n for group in a.EVENT_LABELS.values() for n in group))
    calls = []
    class Extractor:
        @classmethod
        def from_pretrained(cls, model, **options):
            calls.append((model, options))
            return cls()

        def __call__(self, samples, sampling_rate, return_tensors):
            assert sampling_rate == 16000 and return_tensors == 'pt'
            return {'input_values': torch.zeros(1, 10, 10)}
    class Model:
        config = SimpleNamespace(id2label=dict(enumerate(names)))

        @classmethod
        def from_pretrained(cls, model, **options):
            calls.append((model, options))
            return cls()

        def to(self, device):
            assert device == 'cpu'
            return self

        def eval(self):
            return self

        def __call__(self, **inputs):
            return SimpleNamespace(logits=torch.zeros(1, len(names)))
    monkeypatch.setitem(sys.modules, 'transformers', SimpleNamespace(ASTFeatureExtractor=Extractor, ASTForAudioClassification=Model))
    backend = a.ASTBackend(tmp_path, offline=True, device='cpu')
    assert backend.score(np.zeros(80000, dtype=np.float32)) == {key: .5 for key in a.EVENT_LABELS}
    assert all(options == dict(cache_dir=str(tmp_path), local_files_only=True) for _, options in calls)


def test_fetch_only_one_ast_weight_format(monkeypatch, tmp_path):
    from demo import fetch_models
    calls = []
    monkeypatch.setitem(sys.modules, 'huggingface_hub', SimpleNamespace(
        HfApi=lambda: SimpleNamespace(list_repo_files=lambda _: ['model.safetensors', 'pytorch_model.bin']),
        snapshot_download=lambda model, **options: calls.append((model, options))))
    fetch_models.main(['--cache', str(tmp_path)])
    assert len(calls) == 2
    assert calls[0][0] == a.AST_MODEL and calls[1][0] == a.SPEAKER_MODEL
    assert 'model.safetensors' in calls[0][1]['allow_patterns']
    assert 'pytorch_model.bin' not in calls[0][1]['allow_patterns']


def test_ecapa_local_loader_contract_without_speechbrain(monkeypatch, tmp_path):
    import torch
    downloaded, collected = [], []
    (tmp_path / 'hyperparams.yaml').write_text('', encoding='utf-8')
    (tmp_path / 'embedding_model.ckpt').touch()
    class Pretrainer:
        loadables = dict(embedding_model=object(), label_encoder=object())

        def set_collect_in(self, path):
            collected.append(path)

        def load_collected(self):
            # SpeechBrain 1.0.3 takes no device argument here.
            assert set(self.loadables) == {'embedding_model'}
    pretrainer = Pretrainer()
    hp = dict(pretrainer=pretrainer, modules={})
    class Encoder:
        def __init__(self, modules, hparams, run_opts):
            assert run_opts == {'device': 'cpu'}

        def encode_batch(self, samples):
            assert samples.shape == (1, 16000)
            return torch.tensor([[[1., 2., 3.]]])
    def snapshot(model, **options):
        downloaded.append((model, options))
        return str(tmp_path)
    monkeypatch.setitem(sys.modules, 'huggingface_hub', SimpleNamespace(snapshot_download=snapshot))
    monkeypatch.setitem(sys.modules, 'hyperpyyaml', SimpleNamespace(load_hyperpyyaml=lambda _: hp))
    monkeypatch.setitem(sys.modules, 'torchaudio', SimpleNamespace())
    monkeypatch.setitem(sys.modules, 'speechbrain.inference.speaker', SimpleNamespace(EncoderClassifier=Encoder))
    backend = a.ECAPABackend(tmp_path, offline=True, device='cpu')
    assert np.array_equal(backend.embed(sine()), [1, 2, 3])
    assert collected == [tmp_path]
    assert downloaded[0][1]['local_files_only'] is True
    assert downloaded[0][1]['cache_dir'] == str(tmp_path)
