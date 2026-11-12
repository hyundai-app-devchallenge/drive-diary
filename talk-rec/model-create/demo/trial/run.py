"""Offline T46 block trial: python -m demo.trial.run --models ngram shape."""
import argparse
from datetime import datetime
import json
import multiprocessing as mp
import os
from pathlib import Path
import re
import sys
import time

ENCODERS = ("monologg/koelectra-small-v3-discriminator", "klue/roberta-small",
            "alphaedge-ai/mmBERT-small-kor-32768")


def worker(connection, name, seed, splits, options, weights):
    """One fresh process per model/seed makes peak RSS comparable across runs."""
    try:
        from threadpoolctl import threadpool_limits
        from .models import EncoderModel, LinearModel, benchmark, peak_rss_mb
        from .metrics import select_threshold
        with threadpool_limits(limits=1):
            start = time.perf_counter()
            model = (LinearModel(name, seed) if name in ("ngram", "shape") else EncoderModel(
                name, seed, options["cache"], options["max_length"], options["truncation"], options["device"]))
            texts = [r.text for r in splits["train"]]
            labels = [r.label for r in splits["train"]]
            if name in ("ngram", "shape"):
                model.fit(texts, labels)
            else:
                model.fit(texts, labels, options["epochs"], options["learning_rate"], options["batch_size"])
            training_seconds = time.perf_counter() - start
            predictions = {}
            for split, rows in splits.items():
                probability, pred3 = model.predict([r.text for r in rows])
                predictions[split] = dict(probability=probability, pred3=pred3)
            threshold = select_threshold([r.label for r in splits["val"]], predictions["val"]["probability"])
            for entry in predictions.values():
                entry["threshold"] = threshold
            latency = benchmark(model, options["repeats"])
            storage = model.storage_bytes()
            if weights is not None:
                model.save(Path(weights))
                numeric_json(Path(weights) / "trial.json", dict(
                    model=name, seed=seed, threshold=threshold, labels=["none", "story", "moment"],
                    max_length=options["max_length"], truncation=options["truncation"],
                    input_separator="\n", aux_loss_weight=.2))
            info = dict(parameters=model.parameters(), storage_mb=storage / 1_000_000,
                        cpu_ms=latency, peak_rss_mb=peak_rss_mb(), training_seconds=training_seconds,
                        benchmark_tokens=0 if name in ("ngram", "shape") else options["max_length"],
                        benchmark_repeats=options["repeats"], cpu_threads=1)
            connection.send(dict(predictions=predictions, info=info))
    except Exception as error:
        # Exception text/tracebacks can contain transcript contents. Only emit type.
        import traceback
        where = " < ".join(f"{Path(f.filename).name}:{f.lineno}" for f in traceback.extract_tb(error.__traceback__)[-4:][::-1])
        connection.send(dict(error=f"{type(error).__name__} at {where}"))
    finally:
        connection.close()


def isolated(name, seed, splits, options, weights):
    ctx = mp.get_context("spawn")
    parent, child = ctx.Pipe(duplex=False)
    process = ctx.Process(target=worker, args=(child, name, seed, splits, options, weights))
    process.start()
    child.close()
    try:
        while not parent.poll(1):
            if not process.is_alive():
                raise RuntimeError("trial worker exited")
        result = parent.recv()
    finally:
        parent.close()
        process.join()
    if "error" in result:
        raise RuntimeError(f"trial worker failed ({result['error']}); check inputs or local model cache")
    if process.exitcode:
        raise RuntimeError("trial worker exited unsuccessfully")
    return result


def numeric_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def report(results, args):
    def cell(value):
        if value["mean"] is None:
            return "—"
        text = f"{value['mean']:.3f} ± {value['std']:.3f}"
        if "ci_low" in value and value["ci_low"] is not None:
            text += f" [{value['ci_low']:.3f}, {value['ci_high']:.3f}]"
        return text
    n = results["counts"]["real"]
    lines = ["# T46 묶음 시험 학습", "", f"실제 평가 n={n}: 표본이 작아 95% 구간이 넓음. 배포 성능 추정용이 아님.",
             "평균 ± 학습 시드 표준편차, 대괄호는 실제 묶음 bootstrap 95% 구간(시드 평균 기준).", "",
             f"분할 시드 {args.split_seed}; 학습 시드 {', '.join(map(str, args.seeds))}; "
             f"epochs={args.epochs}, lr={args.learning_rate}, max_length={args.max_length}, truncation={args.truncation}.",
             "합성 장면별 80/20 층화. 반복 카드 번호는 디렉터리를 넘어 같은 분할에 배치.",
             "문턱값은 합성 val balanced accuracy 최대화(동률: 0.5에 가까운 값, 이어서 낮은 값). 실제에서는 고정.", ""]
    for split in ("val", "real"):
        lines += [f"## {split}" + (f" (n={n}, 넓은 구간 주의)" if split == "real" else ""), "",
                  "| 모델 | 정확도 | keep 재현율 | keep 정밀도 | AUROC | 보조 3분류 정확도 | 문턱값 |",
                  "|---|---:|---:|---:|---:|---:|---:|"]
        for name, entry in results["models"].items():
            if split not in entry:
                continue
            row = entry[split]
            lines.append("| " + name.replace("|", "\\|") + " | " + " | ".join(
                cell(row[k]) for k in ("accuracy", "keep_recall", "keep_precision", "auroc", "accuracy3"))
                         + " | " + cell(entry["threshold"]) + " |")
        lines += ["", "혼동 행렬: 실제 행 none/keep, 예측 열 none/keep. 각 수는 시드 평균 ± SD.", "",
                  "| 모델 | TN (none→none) | FP (none→keep) | FN (keep→none) | TP (keep→keep) |",
                  "|---|---:|---:|---:|---:|"]
        for name, entry in results["models"].items():
            if split in entry:
                lines.append("| " + name.replace("|", "\\|") + " | " + " | ".join(
                    cell(entry[split][k]) for k in ("tn", "fp", "fn", "tp")) + " |")
        lines.append("")
    lines += ["## 크기와 속도", "", "| 모델 | 파라미터 | 저장 MB | CPU ms/묶음 | 프로세스 peak RSS MB | 학습 초 |",
              "|---|---:|---:|---:|---:|---:|"]
    for name, entry in results["models"].items():
        if "resources" in entry:
            lines.append("| " + name.replace("|", "\\|") + " | " + " | ".join(cell(entry["resources"][k]) for k in
                         ("parameters", "storage_mb", "cpu_ms", "peak_rss_mb", "training_seconds")) + " |")
    lines += ["", "CPU는 단일 스레드, batch=1, 준비 3회 후 반복 중앙값. 토큰화/특징 변환 포함.",
              f"인코더는 {args.max_length} 토큰으로 패딩한 창작 입력. ngram/shape는 같은 창작 글 전체(토큰 제한 해당 없음).",
              "저장 크기: 선형 모델은 특징 추출기 포함 pickle, 인코더는 두 head 포함 FP32 torch state archive(tokenizer/config 제외).",
              "RSS는 격리 프로세스에서 로드·학습·CPU 측정 전체의 최대치이며 추론 전용/차량 RSS와 다름. MB=10^6 bytes.",
              "앱 전체 400MB·CPU 약 3% 예산은 이 수치만으로 통과 판정할 수 없음. 기기와 호출 빈도로 별도 측정 필요.",
              "Gemini 기준선은 judge pred[pleos]의 hard label(keep=1, none=0). AUROC도 hard score 기준이며 확률 AUROC가 아님.",
              "all_keep에는 story/moment 구분이 없어 3분류 정확도를 정의하지 않음.",
              "AUROC는 두 클래스가 있을 때만 정의. 단일 클래스 bootstrap 표본은 해당 지표에서 제외(ci_valid에 횟수 기록).",
              "예측 keep이 없을 때 정밀도는 0. 보조 3분류 예측은 별도 head/회귀의 argmax로 이진 문턱과 독립적.",
              "합성→실제 차이, 사람 판정의 주관성, 적은 실제 표본을 고려해야 함. 실제 평가로 모델/문턱을 반복 선택하면 추가 누수가 생김.", ""]
    return "\n".join(lines)


def execute(args):
    # Set before spawning/importing numerical libraries. No downloads under any path.
    for key in ("OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS", "NUMEXPR_NUM_THREADS"):
        os.environ[key] = "1"
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    from .data import LABELS, load_real, load_synth, split_scenes
    from .metrics import aggregate, metrics
    import numpy as np
    synth, skipped = load_synth(args.synth_dir)
    train, val = split_scenes(synth, args.split_seed)
    real, gemini = load_real(args.judge, args.asr, args.asr_config)
    splits = dict(train=train, val=val, real=real)
    output = Path(".local/demo/trial") / args.run_name
    output.mkdir(parents=True, exist_ok=False)
    results = dict(counts={**{k: len(v) for k, v in splits.items()}, "skipped_synth_dirs": skipped,
                          "train_scenes": len({r.scene for r in train}), "val_scenes": len({r.scene for r in val}),
                          "real_keep": sum(r.label != 0 for r in real)},
                   settings=dict(split_seed=args.split_seed, seeds=args.seeds, epochs=args.epochs,
                                 learning_rate=args.learning_rate, max_length=args.max_length,
                                 truncation_code={"both": 0, "head": 1, "tail": 2}[args.truncation],
                                 bootstrap=args.bootstrap, bootstrap_seed=1046), models={})
    predictions = []
    gold = {k: [r.label for r in rows] for k, rows in splits.items()}
    options = vars(args)
    for model_index, name in enumerate(args.models):
        runs, resources, per_seed = {k: [] for k in splits}, [], {}
        for seed in args.seeds:
            print(f"model={model_index + 1}/{len(args.models)} seed={seed} start", flush=True)
            weights = str(output / "weights" / f"m{model_index}" / f"s{seed}") if args.save_weights else None
            result = isolated(name, seed, splits, options, weights)
            resources.append(result["info"])
            per_seed[str(seed)] = dict(threshold=result["predictions"]["val"]["threshold"], resources=result["info"])
            for split, entries in result["predictions"].items():
                runs[split].append(entries)
                per_seed[str(seed)][split] = metrics(gold[split], entries["probability"], entries["threshold"], entries["pred3"])
                for row, probability, pred3 in zip(splits[split], entries["probability"], entries["pred3"]):
                    predictions.append(dict(model=name, seed=seed, split=split, id=row.id,
                                            probability=probability, prediction="keep" if probability >= entries["threshold"] else "none",
                                            gold="keep" if row.label else "none", prediction3=LABELS[pred3], gold3=LABELS[row.label]))
            print(f"model={model_index + 1} seed={seed} done", flush=True)
            # Preserve reviewable predictions if a later model/cache fails.
            (output / "preds.jsonl").write_text("".join(
                json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n" for row in predictions), encoding="utf-8")
        def stats(values):
            values = [v for v in values if v is not None]
            return dict(mean=float(np.mean(values)) if values else None,
                        std=float(np.std(values, ddof=1)) if len(values) > 1 else (0. if values else None))
        entry = {k: aggregate(gold[k], runs[k], args.bootstrap if k == "real" else 0) for k in ("val", "real")}
        entry.update(per_seed=per_seed, threshold=stats([r["threshold"] for r in runs["val"]]),
                     resources={k: stats([r[k] for r in resources]) for k in resources[0]})
        results["models"][name] = entry
    for name in ("all_keep", "gemini_pleos"):
        entry = dict(threshold=dict(mean=.5, std=0.))
        for split in (("val", "real") if name == "all_keep" else ("real",)):
            p = [1.] * len(splits[split]) if name == "all_keep" else [float(x != 0) for x in gemini]
            entry[split] = aggregate(gold[split], [dict(probability=p, threshold=.5,
                                     pred3=None if name == "all_keep" else gemini)], args.bootstrap if split == "real" else 0)
            for i, row in enumerate(splits[split]):
                predictions.append(dict(model=name, seed=None, split=split, id=row.id, probability=p[i],
                                        prediction="keep" if p[i] else "none", gold="keep" if row.label else "none",
                                        prediction3=None if name == "all_keep" else LABELS[gemini[i]], gold3=LABELS[row.label]))
        results["models"][name] = entry
    numeric_json(output / "results.json", results)
    (output / "preds.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False, allow_nan=False) + "\n" for row in predictions), encoding="utf-8")
    (output / "report.md").write_text(report(results, args), encoding="utf-8")
    print(f"train={len(train)} val={len(val)} real={len(real)} skipped={skipped}; report={output / 'report.md'}", flush=True)
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models", nargs="+", default=["ngram", "shape", *ENCODERS])
    parser.add_argument("--synth-dir", action="append")
    parser.add_argument("--judge", default=".local/demo/pleos/judge.json")
    parser.add_argument("--asr", default=".local/demo/asr/pleos.jsonl")
    parser.add_argument("--asr-config", default="pleos")
    parser.add_argument("--cache", default=".local/hf")
    parser.add_argument("--run-name", default=datetime.now().strftime("t46-%Y%m%d-%H%M%S"))
    parser.add_argument("--seeds", nargs="+", type=int, default=[43, 44, 45])
    parser.add_argument("--split-seed", type=int, default=43)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--learning-rate", type=float, default=3e-5)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--max-length", type=int, default=256)
    parser.add_argument("--truncation", choices=["both", "head", "tail"], default="both")
    parser.add_argument("--device", choices=["auto", "cpu", "cuda"], default="auto")
    parser.add_argument("--bootstrap", type=int, default=2000)
    parser.add_argument("--repeats", type=int, default=15)
    parser.add_argument("--save-weights", action="store_true")
    args = parser.parse_args(argv)
    args.synth_dir = args.synth_dir or [".local/synth/demo2", ".local/synth/demo3"]
    if (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", args.run_name) or len(set(args.models)) != len(args.models)
            or set(args.models) & {"all_keep", "gemini_pleos"} or len(set(args.seeds)) != len(args.seeds)
            or any(s < 0 or s >= 2**32 for s in [*args.seeds, args.split_seed])
            or args.epochs < 1 or args.batch_size < 1 or not 8 <= args.max_length <= 256
            or not 0 < args.learning_rate < 1 or args.bootstrap < 1 or args.repeats < 1):
        parser.error("invalid trial options")
    try:
        execute(args)
    except Exception as error:
        print(f"trial failed ({type(error).__name__}: {error if 'trial worker failed' in str(error) else ''}); check input completeness, unique run name and offline cache", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
