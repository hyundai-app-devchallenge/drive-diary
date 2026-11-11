"""T48 tests use synthetic PCM and fake scores, never pretrained weights."""
import csv
import json
from types import SimpleNamespace
import wave

import numpy as np
import pytest

from demo.sound import core as c
from demo.sound import run
from demo.sound import model as ast
from tools.listen.storage import clip_name


def sine(frequency=220, seconds=1, amplitude=.2, rate=c.RATE):
    return (amplitude * np.sin(2 * np.pi * frequency * np.arange(round(seconds * rate)) / rate)).astype(np.float32)


def scores(**values):
    return {g: values.get(g, .01) for g in c.GROUPS}


@pytest.mark.parametrize('snr', [10, 5, 0, -5])
def test_mix_power_and_common_peak_scaling(snr):
    event, background = sine(amplitude=2), sine(440, amplitude=1.5)
    mixed, scaled, noise = c.mix_snr(event, background, snr)
    assert 10 * np.log10(np.mean(scaled.astype(float) ** 2) / np.mean(noise.astype(float) ** 2)) == pytest.approx(snr, abs=1e-5)
    assert mixed == pytest.approx(scaled + noise, abs=1e-6)
    assert np.max(np.abs(mixed)) <= 1.000001
    assert c.mix_snr(event, background, snr)[0] == pytest.approx(mixed)


@pytest.mark.parametrize('a,b', [(np.zeros(5), np.ones(5)), (np.ones(5), np.zeros(5)), (np.ones(5), np.ones(4))])
def test_mix_undefined_snr(a, b):
    with pytest.raises(ValueError):
        c.mix_snr(a, b, 0)


@pytest.mark.parametrize('seconds,expected', [
    (.1, [(0, .1)]), (10, [(0, 10)]), (10.1, [(0, 10), (5, 10.1)]),
    (15, [(0, 10), (5, 15)]), (16, [(0, 10), (5, 15), (10, 16)]),
])
def test_windows(seconds, expected):
    assert c.window_ranges(round(seconds * c.RATE)) == [(round(a * c.RATE), round(b * c.RATE)) for a, b in expected]


class Fake(c.SoundModel):
    labels = ('Laughter', 'Giggle', 'Screaming', 'Crying, sobbing', 'Singing', 'Speech')

    def __init__(self, *args):
        self.device = 'cpu'

    def score_window(self, waveform, rate):
        return {name: min(.9, len(waveform) / (20 * rate)) for name in self.labels}

    def benchmark(self, repeats):
        return dict(parameters=1, parameter_bytes=4, cpu_threads=1, warmups=1,
                    repeats=repeats, cpu_ms_median=1, cpu_ms_runs=[1] * repeats,
                    window_seconds=10, includes_feature_extraction=True)


def test_mapping_skips_absent_aliases_and_empty_groups():
    mapping = c.resolve_groups(('Giggle', 'Speech', 'unrelated'))
    assert mapping['laugh'] == ['Giggle']
    assert mapping['shout'] == []
    fake = Fake()
    fake.labels = ('Giggle', 'unrelated')
    summary = fake.summarize(sine())
    assert summary['laugh'] == {'max': .05, 'mean': .05}
    assert summary['cry'] == {'max': 0, 'mean': 0}


def test_window_label_interface_and_group_max_then_mean():
    fake = Fake()
    assert fake.score_windows(sine(seconds=16), c.RATE)['Giggle'] == .5
    summary = fake.summarize(sine(seconds=16))
    assert summary['laugh']['max'] == .5
    assert summary['laugh']['mean'] == pytest.approx((.5 + .5 + .3) / 3)
    class Alternating(Fake):
        def score_window(self, waveform, rate):
            return {name: (.9 if name == ('Giggle' if len(waveform) < 10 * rate else 'Laughter') else .1)
                    for name in self.labels}
    assert Alternating().summarize(sine(seconds=16))['laugh'] == {'max': .9, 'mean': .9}


def test_empty_and_nonfinite_waveform():
    for signal in [[], [np.nan], [[1]]]:
        with pytest.raises(ValueError):
            Fake().summarize(signal)


def test_threshold_strict_comparison_ties_and_small_samples():
    calibration = [scores(**{g: i / 20 for g in c.GROUPS}) for i in range(20)]
    thresholds = c.calibrate_thresholds(calibration)
    assert thresholds['laugh'] == .9
    assert c.rates(calibration, thresholds)['laugh'] == .05
    assert c.rates([scores(laugh=.9)], thresholds)['laugh'] == 0
    tied = [scores(laugh=.4)] * 100
    assert c.rates(tied, c.calibrate_thresholds(tied))['laugh'] == 0
    small = calibration[:3]
    assert c.rates(small, c.calibrate_thresholds(small))['laugh'] == 0
    with pytest.raises(ValueError):
        c.calibrate_thresholds([])


def test_confusion_auc_detection_denominators_and_false_alarm():
    thresholds = dict.fromkeys(c.GROUPS, .5)
    rows = [
        {'class': 'laugh', 'scores': scores(laugh=.4, shout=.8)},
        {'class': 'screaming', 'scores': scores(laugh=.9, shout=.85)},
        {'class': 'crying', 'scores': scores(cry=.3)},
        {'class': 'cough', 'scores': scores(laugh=.7)},
        {'class': 'breath', 'scores': scores()},
    ]
    m = c.classification_metrics(rows, thresholds)
    assert m['confusion'] == [[0, 1, 0], [1, 0, 0], [0, 0, 1]]
    assert m['accuracy'] == pytest.approx(1 / 3)
    assert m['gated_confusion'] == [[0, 1, 0, 0], [1, 0, 0, 0], [0, 0, 0, 1]]
    assert m['detection']['laugh']['own_threshold_recall'] == 0
    assert m['detection']['laugh']['any_target_recall'] == 1
    assert m['detection']['laugh']['predicted_among_detected']['shout'] == 1
    assert m['detection']['shout']['predicted_among_detected']['laugh'] == 1
    assert m['detection']['cry']['wrong_label_among_detected'] is None
    assert m['pairwise_auroc_score_difference']['laugh_vs_shout'] == 0
    assert m['other_false_alarm']['laugh'] == .5
    assert m['other_false_alarm_by_class']['cough']['rates']['laugh'] == 1
    assert c.classification_metrics([], thresholds)['accuracy'] is None


def test_real_metrics_excludes_unsure_but_summarizes_it():
    rows = [{'id': str(i), 'verdict': v, 'groups': {g: {'max': p, 'mean': p / 2} for g in c.GROUPS}}
            for i, (v, p) in enumerate([('moment', .9), ('story', .6), ('none', .1), ('unsure', 1)])]
    m = c.real_metrics(rows)
    assert m['by_verdict']['unsure']['n'] == 1
    assert m['binary_tasks_excluding_unsure']['moment_vs_rest']['n'] == 3
    assert m['binary_tasks_excluding_unsure']['keep_vs_none']['auroc']['laugh_or_sing'] == 1
    assert c.auc([True], [.5]) is None


def write_wav(path, seconds=1):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), 'wb') as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(c.RATE)
        f.writeframes((sine(seconds=seconds) * 32767).astype('<i2').tobytes())


def write_jsonl(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(''.join(json.dumps(r) + '\n' for r in rows), encoding='utf-8')


def test_cli_synthetic_audio_last_verdict_privacy_and_all_real(tmp_path, monkeypatch):
    ids = [f'yt:abcdefghijk@{i}' for i in range(4)]
    private = 'PRIVATE_TRANSCRIPT_SENTINEL'
    samples = [{'id': key, 'origin': 'approved', 'turns': [private]} for key in ids]
    write_jsonl(tmp_path / '.local/demo/sample.jsonl', samples)
    answers = [{'id': key, 'verdict': verdict, 'comment': private}
               for key, verdict in zip(ids, ['unsure', 'none', 'moment', 'story'])]
    answers.append({'id': ids[0], 'verdict': 'none', 'comment': private})
    write_jsonl(tmp_path / '.local/demo/answers/vizatae.jsonl', answers)
    for key in ids:
        write_wav(tmp_path / '.local/demo/clips' / clip_name(key), 2)
    directory = tmp_path / '.local/sound/nonspeech7k'
    rows = []
    for name in ['laugh', 'screaming', 'crying', 'cough']:
        for index in range(2):
            filename = f'{name}{index}.wav'
            rows.append({'Filename': filename, 'Classname': name, 'File_ID': f'{name}{index}', 'Durationin ms': '1000'})
            write_wav(directory / 'test/test' / filename)
    with (directory / 'test.csv').open('w', encoding='utf-8', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    monkeypatch.setattr(run, 'ASTModel', Fake)
    out = tmp_path / 'out'
    assert run.main(['--root', str(tmp_path), '--out', str(out), '--limit', '1', '--background-windows', '20']) == 0
    result = json.loads((out / 'results.json').read_text(encoding='utf-8'))
    assert result['clean']['n'] == 4
    assert result['real_n'] == 4  # --limit never truncates C
    assert len(result['background']['calibration_ids']) == 1
    assert set(result['background']['calibration_ids']).isdisjoint(result['background']['evaluation_ids'])
    assert set(result['mixed']) == {'10', '5', '0', '-5'}
    real = [json.loads(line) for line in (out / 'scores.jsonl').read_text().splitlines()]
    assert real[0]['verdict'] == 'none'
    assert set(real[0]) == {'id', 'origin', 'verdict', 'groups'}
    for path in out.iterdir():
        assert private not in path.read_text(encoding='utf-8')
    first = result['predictions']['mixture_background_intervals']
    assert run.main(['--root', str(tmp_path), '--out', str(out), '--limit', '1', '--background-windows', '20']) == 0
    again = json.loads((out / 'results.json').read_text(encoding='utf-8'))
    assert first == again['predictions']['mixture_background_intervals']


def test_background_reproducible_duration_matched_and_no_padding():
    pool = {'id2': sine(seconds=2), 'id1': sine(seconds=3)}
    a, meta = run.background_segment(pool, c.RATE, np.random.default_rng(48))
    b, other = run.background_segment(pool, c.RATE, np.random.default_rng(48))
    assert len(a) == c.RATE and meta == other
    np.testing.assert_array_equal(a, b)
    with pytest.raises(ValueError):
        run.background_segment(pool, 4 * c.RATE, np.random.default_rng(48))


def test_ast_adapter_reuses_offline_loader_and_returns_all_label_sigmoids(monkeypatch, tmp_path):
    import torch
    calls = []
    class Extractor:
        def __call__(self, waveform, **kwargs):
            calls.append(kwargs)
            return {'input_values': torch.zeros(1, 2, 2)}
    class Model:
        config = SimpleNamespace(id2label={'2': 'Screaming', '0': 'Speech', '1': 'Giggle'})
        def __call__(self, **inputs):
            return SimpleNamespace(logits=torch.tensor([[-2., 0., 2.]]))
    def loader(cache, **kwargs):
        assert cache == tmp_path
        assert kwargs == {'offline': True, 'device': 'cpu'}
        return SimpleNamespace(extractor=Extractor(), model=Model(), torch=torch, device='cpu')
    monkeypatch.setattr(ast, 'ExistingASTBackend', loader)
    backend = ast.ASTModel(tmp_path, 'cpu')
    result = backend.score_windows(sine(), c.RATE)
    assert result['Giggle'] == .5
    assert result['Screaming'] == pytest.approx(torch.sigmoid(torch.tensor(2.)).item())
    assert result['Speech'] == pytest.approx(torch.sigmoid(torch.tensor(-2.)).item())
    assert calls == [{'sampling_rate': 16000, 'return_tensors': 'pt'}]
    with pytest.raises(ValueError, match='16000'):
        backend.score_windows(sine(), 8000)
