"""Offline T58: fixed M1/s43 export, numeric-only parity and deployment bundle.

Run with python -X utf8 -B -m train.v3.export. Never stringify private values.
"""
import argparse
import contextlib
import gc
import hashlib
import importlib.metadata
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

import numpy as np

RUN = Path('.local/train/v3/t55-v3-20261112')
OUTPUT = Path('.local/export/m1')
LABELS = ['none', 'story', 'moment']
MAX_LENGTH = 256
OPSET = 17


def offline():
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1',
                      TOKENIZERS_PARALLELISM='false', OMP_NUM_THREADS='1', MKL_NUM_THREADS='1')


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')


def checked_labels(value):
    """Check the actual (blocks, annotator->id->label) tuple before any use/output."""
    if not isinstance(value, tuple) or len(value) != 2:
        raise TypeError('invalid labels return container')
    blocks, labels = value
    if not isinstance(blocks, list) or not isinstance(labels, dict):
        raise TypeError('invalid labels return members')
    if not all(isinstance(b, dict) and isinstance(b.get('id'), str)
               and isinstance(b.get('lines'), list) for b in blocks):
        raise TypeError('invalid block schema')
    if not isinstance(labels.get('vizatae'), dict) or not all(
        isinstance(k, str) and isinstance(v, dict) for k, v in labels['vizatae'].items()):
        raise TypeError('invalid annotation schema')
    return blocks, labels


def load_private_dev(root):
    from demo.trial.data import input_text
    from eval import real
    from .data import RealExample
    # Quarantine legacy warnings/exceptions: never emit returned values or stderr.
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        value = real.load_labels(root, real.run_directory(root, 'v1'), annotators=['vizatae'], split='dev')
    blocks, labels = checked_labels(value)
    rows = []
    for block in blocks:
        human = labels['vizatae'].get(block['id'])
        if block['cast'] == 'unknown' or human is None:
            continue
        label = LABELS.index(human['kind']) if human['keep'] == 'keep' else 0
        rows.append(RealExample(block['id'], block['source'], input_text(block['lines']),
                               label, float(block['seconds']), block['cast'], human['unsure']))
    # Keep private strings only in memory, including lines for the final leak audit.
    private = [r.text for r in rows if r.text]
    private += [line['text'] for b in blocks for line in b['lines']
                if isinstance(line, dict) and isinstance(line.get('text'), str)]
    return rows, private


def session(path):
    import onnxruntime as ort
    options = ort.SessionOptions()
    options.intra_op_num_threads = options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.log_severity_level = 3
    return ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])


def export_onnx(model, tokenizer, path, max_length=MAX_LENGTH):
    import onnx
    import torch
    from demo.trial.models import token_batch

    class Outputs(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.block = model

        def forward(self, input_ids, attention_mask):
            keep, kind = self.block(input_ids=input_ids, attention_mask=attention_mask)
            return keep.sigmoid(), kind

    model.cpu().eval()
    inputs = token_batch(tokenizer, ['오늘 구름이 토끼를 닮았어'], max_length, 'both')
    torch.onnx.export(Outputs().eval(), (inputs['input_ids'], inputs['attention_mask']), str(path),
                      input_names=['input_ids', 'attention_mask'], output_names=['keep_probability', 'kind_logits'],
                      dynamic_axes={k: {0: 'batch'} for k in
                                    ('input_ids', 'attention_mask', 'keep_probability', 'kind_logits')},
                      opset_version=OPSET, dynamo=False, external_data=False)
    onnx.checker.check_model(str(path))


def quantize(source, target, embeddings=True):
    import onnx
    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(str(source), str(target), weight_type=QuantType.QInt8, per_channel=True,
                     op_types_to_quantize=['MatMul', 'Gemm'] + (['Gather'] if embeddings else []),
                     extra_options={'MatMulConstBOnly': True})
    graph = onnx.load(str(target))
    # ORT 1.23 Gather uses activation dtype UINT8 even with weight_type=QInt8.
    # Re-encode each constant Gather table and its zero point as signed bytes:
    # (u - z) == ((u - 128) - (z - 128)). Scales and real values stay identical.
    # Keep dynamic MatMul activations UINT8, as required by the CPU kernels.
    if embeddings:
        initializers = {t.name: t for t in graph.graph.initializer}
        for node in graph.graph.node:
            if node.op_type != 'DequantizeLinear' or len(node.input) < 3:
                continue
            zero = initializers.get(node.input[2])
            gather = next((n for n in graph.graph.node if n.op_type == 'Gather'
                           and n.output[0] == node.input[0]), None)
            weight = initializers.get(gather.input[0]) if gather else None
            if zero is None or weight is None or weight.data_type != onnx.TensorProto.UINT8:
                continue
            for tensor in (weight, zero):
                array = onnx.numpy_helper.to_array(tensor).astype(np.int16) - 128
                tensor.CopyFrom(onnx.numpy_helper.from_array(array.astype(np.int8), tensor.name))
        onnx.save(graph, str(target))
    onnx.checker.check_model(str(target))
    if not any(t.data_type == onnx.TensorProto.INT8 for t in graph.graph.initializer):
        raise ValueError('quantization produced no int8 weights')


def infer(model, ort_sessions, tokenizer, texts, *, max_length=MAX_LENGTH, progress=False):
    import torch
    from demo.trial.models import token_batch
    values = {name: [] for name in ['torch', *ort_sessions]}
    model.eval()
    with torch.inference_mode():
        for i, text in enumerate(texts):
            inputs = token_batch(tokenizer, [text], max_length, 'both')
            keep, _ = model(**inputs)
            values['torch'].append(float(keep.sigmoid()[0]))
            feed = {k: v.numpy() for k, v in inputs.items()}
            for name, runtime in ort_sessions.items():
                probability, logits = runtime.run(None, feed)
                if probability.shape != (1,) or logits.shape != (1, 3) or not np.isfinite(logits).all():
                    raise ValueError('invalid ONNX outputs')
                values[name].append(float(probability[0]))
            if progress and (i + 1) % 100 == 0:
                print(f'compared={i+1}/{len(texts)}', flush=True)
    if not all(np.isfinite(p).all() and np.all((np.asarray(p) >= 0) & (np.asarray(p) <= 1)) for p in values.values()):
        raise ValueError('invalid probabilities')
    return values


def compare(ids, reference, other, threshold, other_threshold=None):
    a, b = np.asarray(reference, dtype=float), np.asarray(other, dtype=float)
    if a.shape != b.shape or a.shape != (len(ids),) or not len(ids) or not np.isfinite([a, b]).all():
        raise ValueError('invalid comparison arrays')
    changed = (a >= threshold) != (b >= (threshold if other_threshold is None else other_threshold))
    return dict(max_abs=float(np.abs(a-b).max()), mean_abs=float(np.abs(a-b).mean()),
                agreement=float((~changed).mean()), flipped_count=int(changed.sum()),
                flipped_ids=[identity for identity, flip in zip(ids, changed) if flip])


def primary_metrics(rows, probability, threshold):
    from eval.v3.metrics import values
    ix = [i for i, r in enumerate(rows) if r.cast == 'child_family']
    p = np.asarray(probability)[ix]
    metrics = values([rows[i].label for i in ix], p, p >= threshold, None,
                     [rows[i].seconds for i in ix], 1)
    return {k: metrics[k] for k in ('keep_recall', 'none_specificity', 'accuracy', 'auroc')}


def rss_bytes():
    """Current process working set (RSS), not the lifetime high water mark."""
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        class Counters(ctypes.Structure):
            _fields_ = [('cb', wintypes.DWORD), ('faults', wintypes.DWORD)] + [
                (n, ctypes.c_size_t) for n in ('peak', 'rss', 'qp', 'q', 'qnp', 'qn', 'page', 'peakpage')]
        kernel, psapi = ctypes.WinDLL('kernel32'), ctypes.WinDLL('psapi')
        kernel.GetCurrentProcess.restype = wintypes.HANDLE
        psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
        c = Counters()
        c.cb = ctypes.sizeof(c)
        if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(c), c.cb):
            raise OSError('RSS unavailable')
        return c.rss
    return int(Path('/proc/self/statm').read_text().split()[1]) * os.sysconf('SC_PAGE_SIZE')


def benchmark_worker(path, directory, repeats=15):
    from demo.trial.models import load_tokenizer, token_batch
    import torch
    torch.set_num_threads(1)
    tokenizer = load_tokenizer(str(directory), directory)
    text = '오늘 바람이 시원해\n다음에 같이 산책 가자\n' * 40
    def encode():
        return {k: v.numpy() for k, v in token_batch(tokenizer, [text], MAX_LENGTH, 'both').items()}
    feed = encode()
    gc.collect()
    before = rss_bytes()
    runtime = session(path)
    after_load = rss_bytes()
    def measure(tokenize):
        times = []
        for i in range(3 + repeats):
            start = time.perf_counter()
            runtime.run(None, encode() if tokenize else feed)
            elapsed = (time.perf_counter() - start) * 1000
            if i >= 3:
                times.append(elapsed)
        return dict(median_ms=float(np.median(times)), samples_ms=times)
    excluded = measure(False)
    included = measure(True)
    return dict(mb=Path(path).stat().st_size / 1e6, inference=excluded, tokenization_included=included,
                rss_before_mb=before / 1e6, rss_after_load_mb=after_load / 1e6,
                rss_load_delta_mb=(after_load-before)/1e6,
                rss_after_warm_delta_mb=(rss_bytes()-before)/1e6,
                threads=1, warmup=3, repeats=repeats)


def benchmark_isolated(path, directory):
    result = subprocess.run([sys.executable, '-X', 'utf8', '-B', '-m', 'train.v3.export',
                             '--benchmark-model', str(path), '--output', str(directory)],
                            capture_output=True, text=True, encoding='utf-8', check=False)
    if result.returncode:
        raise RuntimeError('isolated benchmark failed')
    return json.loads(result.stdout)


# All twenty conversations are newly invented; never populate this from real data.
GOLDEN_LINES = [
    ['저 구름 토끼 같아', '토끼가 우리 차 따라오네'],
    ['어제 만든 종이배 기억나', '응 비눗방울 바다로 갔잖아'],
    ['엄마 오늘 내가 신발끈 혼자 묶었어', '와 기다리던 날이네'],
    ['다음 신호에서 오른쪽이야', '알겠어'],
    ['아빠 달은 왜 낮에도 보여', '햇빛 받으며 우리랑 산책하나 봐'],
    ['할머니 주려고 밤송이 그렸어', '가시 대신 하트가 있네'],
    ['내 양말 두 짝 색이 달라', '오늘은 무지개 팀이네'],
    ['물병 어디 있어', '옆 주머니에 있어'],
    ['지난번 바다에서 모래성 무너졌지', '그래도 조개 지붕은 지켰어'],
    ['동생 자니까 조용히 노래할게', '입으로만 부르는 노래구나'],
    ['오늘 유치원에서 친구가 손 잡아줬어', '마음이 따뜻했겠다'],
    ['아빠 운전 끝나면 내가 어깨 주물러 줄게', '그 말만으로 힘이 난다'],
    ['과자 지금 먹어도 돼', '도착해서 손 씻고 먹자'],
    ['우리가 만든 별자리 이름은 뭐야', '세 식구 웃음자리'],
    ['또 장난감 던지면 안 돼', '알았어 이제 안 던질게'],
    ['내가 크면 엄마보다 키 커질까', '그때는 네가 우산 들어줘'],
    ['Ｈｅｌｌｏ 엄마 ABC abc', '안녕 Café 카페'],
    ['비 오는 창문에 길이 생겼어', '빗방울들이 경주하는 길이네'],
    ['할아버지가 가르쳐 준 휘파람 해볼까', '아직 바람 소리만 나도 멋져'],
    ['오늘 가족 탐험대 출발합니다', '대장은 꼬마 선장님이야'] +
    ['별을 세며 다 같이 만든 작은 우주 이야기를 이어 가자'] * 40 + ['마지막 별 이름은 우리 집이야'],
]


def golden_file(tokenizer, runtime, path):
    from demo.trial.data import input_text
    from demo.trial.models import token_batch
    rows = []
    for i, lines in enumerate(GOLDEN_LINES):
        text = input_text(lines)
        feed = {k: v.numpy() for k, v in token_batch(tokenizer, [text], MAX_LENGTH, 'both').items()}
        p, kind = runtime.run(None, feed)
        rows.append(dict(id=f'invented-{i+1:02}', lines=lines, input_text=text,
                         input_ids=feed['input_ids'][0].tolist(), attention_mask=feed['attention_mask'][0].tolist(),
                         keep_probability=float(p[0]), kind_logits=kind[0].tolist()))
    write_json(path, dict(schema_version=1, invented_only=True, max_length=MAX_LENGTH,
                         recommended_probability_abs_tolerance=1e-4, examples=rows))


def bundle_settings(weights, output):
    for name in ('vocab.txt', 'tokenizer.json', 'tokenizer_config.json', 'special_tokens_map.json'):
        shutil.copyfile(weights / name, output / name)
    tokenizer = json.loads((weights / 'tokenizer.json').read_text(encoding='utf-8'))
    write_json(output / 'tokenizer_settings.json', dict(
        max_length=MAX_LENGTH, padding='right to 256 with PAD=0; attention_mask=1 for kept tokens, else 0',
        truncation='both: preserve special-token positions; content budget=256-number of special tokens; '
                   'if over budget keep first ceil(budget/2) and last floor(budget/2) content tokens in order',
        ordinary_special_tokens=dict(cls=2, sep=3, pad=0, unk=1, mask=4),
        normalizer=tokenizer['normalizer'], pre_tokenizer=tokenizer['pre_tokenizer'],
        post_processor=tokenizer['post_processor'], do_lower_case=False, nfkc=False,
        common_normalize_applied=False, wordpiece=dict(continuing_subword_prefix='##', max_input_chars_per_word=100),
        input_text='Take ordered lines as strings or line.text; strip leading/trailing Unicode whitespace '
                   'from each; retain empty lines; join with U+000A LF, no final separator. '
                   'Do not add speakers, timestamps, labels or other metadata.',
        token_type_ids='not an input; every token belongs to segment zero',
        tensor_dtype='int64', input_shape=['batch', 256],
        outputs=dict(keep_probability=['batch'], kind_logits=['batch', 3])))
    write_json(output / 'labels.json', dict(kind_logits=LABELS, keep_probability='P(keep); independent binary head'))


def audit_private(paths, private):
    """Exact joined inputs + substantial individual lines; never disclose matches."""
    needles = {s for s in private if len(s.strip()) >= 20}
    for path in paths:
        raw = Path(path).read_bytes()
        encoding = 'utf-16' if raw.startswith((b'\xff\xfe', b'\xfe\xff')) else 'utf-8-sig'
        data = raw.decode(encoding, errors='ignore')
        # Check both literal Unicode and JSON-escaped representations.
        if any(s in data or json.dumps(s, ensure_ascii=True)[1:-1] in data
               or json.dumps(s, ensure_ascii=False)[1:-1] in data for s in needles):
            raise ValueError('private transcript audit failed')
    return dict(files_checked=len(paths), private_fragments_checked=len(needles), matches=0,
                method='exact joined inputs and individual lines >=20 codepoints, Unicode and JSON escaped')


def audit_files(output, private):
    repo = Path.cwd().parents[1]
    candidates = []
    excluded = {'.local', '.git', '.venv', 'node_modules', 'build', '.gradle'}
    for directory, dirs, files in os.walk(repo):
        dirs[:] = [d for d in dirs if d not in excluded]
        candidates.extend(Path(directory) / f for f in files
                          if Path(f).suffix in ('.py', '.md', '.json', '.jsonl', '.tsv', '.txt', '.log'))
    candidates += [p for p in Path(output).iterdir() if p.suffix in ('.json', '.log', '.txt')]
    return audit_private(candidates, private)


def execute(output=OUTPUT):
    from .models import load_saved
    from .data import load_synth, split_scenes, stratified_subset
    from eval.v3.metrics import select_threshold
    import torch
    torch.set_num_threads(1)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    results = json.loads((RUN / 'results.json').read_text(encoding='utf-8'))
    threshold = results['models']['M1']['2978']['per_seed']['43']['threshold']
    weights = RUN / 'M1/2978/s43/weights'
    architecture = json.loads((weights / 'architecture.json').read_text(encoding='utf-8'))
    if architecture['seed'] != 43 or architecture['max_length'] != 256 or architecture['truncation'] != 'both':
        raise ValueError('fixed candidate settings mismatch')
    model = load_saved(weights, 'M1', device='cpu')
    fp32 = output / 'model.fp32.onnx'
    print('export M1/2978/seed43 opset=17', flush=True)
    export_onnx(model.model, model.tokenizer, fp32)
    paths = dict(fp32=fp32, int8=output / 'model.int8.onnx', int8_fp32_embeddings=output / 'model.int8-fp32-embeddings.onnx')
    for name in ('int8', 'int8_fp32_embeddings'):
        quantize(fp32, paths[name], embeddings=name == 'int8')
    bundle_settings(weights, output)
    synth, skipped = load_synth(['.local/synth/t53'])
    if skipped:
        raise ValueError('synthetic input incomplete')
    _, val = split_scenes(stratified_subset(synth, 2978, 55), results['settings']['split_seed'])
    rows, private = load_private_dev(Path.cwd())
    if len(val) != 596 or len(rows) != 77 or sum(r.cast == 'child_family' for r in rows) != 37:
        raise ValueError('T55 split counts mismatch')
    runtimes = {name: session(path) for name, path in paths.items()}
    report = dict(candidate=dict(model='M1', size=2978, seed=43, weights=str(weights), torch_threshold=threshold,
                                safetensors_sha256=hashlib.sha256((weights / 'model.safetensors').read_bytes()).hexdigest()),
                  opset=OPSET, versions={k: importlib.metadata.version(k) for k in
                    ('torch', 'transformers', 'tokenizers', 'onnx', 'onnxruntime', 'numpy', 'safetensors')},
                  datasets={}, resources={}, selected_variant='int8',
                  quantization=dict(method='onnxruntime.quantization.quantize_dynamic', weight_type='QInt8',
                                    per_channel=True, operators=['MatMul', 'Gemm', 'Gather'],
                                    matmul_const_b_only=True, embedding_storage='signed int8; lossless UINT8 offset conversion',
                                    dynamic_activations='UINT8', fp32_remaining='biases, normalization, nonlinearities, attention MatMul'),
                  selection_rule='Full int8 weights including embeddings fixed for deployment; fp32 embeddings is a comparison only')
    numeric = []
    for name, current in (('synthetic_val', val), ('dev', rows)):
        print(f'compare {name} n={len(current)}', flush=True)
        ids = [r.id for r in current]
        predictions = infer(model.model, runtimes, model.tokenizer, [r.text for r in current], progress=True)
        # Confirm split identities and frozen model reproduction against T55 numeric artifacts.
        frozen = json.loads((weights.parent / 'numbers.json').read_text(encoding='utf-8'))
        saved_ids = [p['id'] for line in (RUN / 'preds.jsonl').read_text(encoding='utf-8').splitlines()
                     if (p := json.loads(line))['model'] == 'M1' and p['size'] == 2978
                     and p['seed'] == 43 and p['mode'] == name]
        if saved_ids != ids or (name == 'synthetic_val' and frozen['val_gold'] != [r.label for r in current]):
            raise ValueError('T55 split identity mismatch')
        expected = frozen['val' if name == 'synthetic_val' else 'dev']['probability']
        original = compare(ids, expected, predictions['torch'], threshold)
        if original['max_abs'] > 1e-4:
            raise ValueError('T55 original reproduction mismatch')
        entry = dict(n=len(ids), t55_gpu_vs_torch_cpu=original,
                     comparisons={variant: compare(ids, predictions['torch'], predictions[variant], threshold)
                                  for variant in runtimes},
                     fp32_vs_int8=compare(ids, predictions['fp32'], predictions['int8'], threshold))
        if name == 'dev':
            ix = [i for i, r in enumerate(rows) if r.cast == 'child_family']
            int8_threshold = select_threshold([rows[i].label for i in ix], [predictions['int8'][i] for i in ix])
            entry['child_family'] = {variant: primary_metrics(rows, p, threshold) for variant, p in predictions.items()}
            original_metrics = entry['child_family']['torch']
            entry['child_family_delta'] = {variant: {k: metrics[k] - original_metrics[k] for k in metrics}
                                             for variant, metrics in entry['child_family'].items()}
            entry['int8_threshold'] = int8_threshold
            entry['int8_recalibrated_metrics'] = primary_metrics(rows, predictions['int8'], int8_threshold)
            entry['int8_recalibrated_vs_torch'] = compare(ids, predictions['torch'], predictions['int8'], threshold, int8_threshold)
        report['datasets'][name] = entry
        for i, identity in enumerate(ids):
            numeric.append(dict(dataset=name, id=identity, **{k: p[i] for k, p in predictions.items()}))
    t = report['datasets']['dev']['int8_threshold']
    write_json(output / 'threshold.json', dict(threshold=t, torch_threshold=threshold,
               comparison='keep_probability >= threshold', calibrated_on='child_family_dev', n=37,
               target_keep_recall=.85, unsure_included=True,
               rule='eval.v3.metrics.select_threshold: feasible recall>=.85; max specificity, max recall, '
                    'closest to .5, lower threshold; fallback max recall then specificity', model='model.int8.onnx'))
    # Also compare validation at the recalibrated deployment threshold.
    val_numbers = [r for r in numeric if r['dataset'] == 'synthetic_val']
    report['datasets']['synthetic_val']['int8_recalibrated_vs_torch'] = compare(
        [r['id'] for r in val_numbers], [r['torch'] for r in val_numbers], [r['int8'] for r in val_numbers], threshold, t)
    golden_file(model.tokenizer, runtimes['int8'], output / 'golden.json')
    del runtimes, model
    gc.collect()
    for name, path in paths.items():
        print(f'benchmark {name}', flush=True)
        report['resources'][name] = benchmark_isolated(path, output)
    write_json(output / 'predictions.json', numeric)
    deployment_files = ['model.int8.onnx', 'vocab.txt', 'tokenizer.json', 'tokenizer_config.json',
                        'special_tokens_map.json', 'tokenizer_settings.json', 'threshold.json', 'labels.json', 'golden.json']
    write_json(output / 'manifest.json', dict(model='M1', size=2978, seed=43, opset=OPSET,
               sha256={name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in deployment_files}))
    write_json(output / 'results.json', report)
    # Exclude private source directories, inspect all repository text plus output text.
    report['privacy_audit'] = audit_files(output, private)
    write_json(output / 'results.json', report)
    print(f'complete dev=77 val=596 int8_threshold={t:.17g} privacy_matches=0', flush=True)
    return report


def main(argv=None):
    offline()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=OUTPUT)
    parser.add_argument('--benchmark-model', type=Path)
    parser.add_argument('--audit-only', action='store_true')
    args = parser.parse_args(argv)
    try:
        if args.audit_only:
            _, private = load_private_dev(Path.cwd())
            result = audit_files(args.output, private)
            report_path = args.output / 'results.json'
            report = json.loads(report_path.read_text(encoding='utf-8'))
            report['privacy_audit'] = result
            write_json(report_path, report)
            print(json.dumps(result))
        elif args.benchmark_model:
            print(json.dumps(benchmark_worker(args.benchmark_model, args.output), allow_nan=False))
        else:
            execute(args.output)
    except Exception as error:
        from .run import safe_error
        print('T58 failed: ' + safe_error(error), file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
