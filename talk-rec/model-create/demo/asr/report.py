"""Aggregate blind picks and machine metrics; never include decoded text."""
import argparse
from collections import Counter
import json
from pathlib import Path

from tools.asrpick.store import read_picks
from tools.listen.storage import ROOT, atomic_write
from .data import approved, load_runs
from .metrics import summarize


def selection_counts(picks, names):
    counts = Counter()
    for row in picks:
        if isinstance(row["picked"], list):
            counts.update(row["picked"])
        else:
            counts[row["picked"]] += 1
    total = len(picks)
    return dict(total=total, all_bad=counts["all_bad"], similar=counts["similar"],
                configs={name: dict(count=counts[name], rate=counts[name] / total if total else 0) for name in names})


def number(value):
    return "미측정" if value is None else f"{value:.2f}"


def report(picks_path, out, *, root=ROOT):
    rows = approved(root)
    sources = {r["id"]: r["source"] for r in rows}
    directory = Path(root) / ".local/demo/asr"
    runs, paths = load_runs(directory, sources)
    names = sorted(runs)
    picks = list(read_picks(picks_path).values())
    for row in picks:
        if row["id"] not in sources or (isinstance(row["picked"], list) and set(row["picked"]) - set(names)):
            raise ValueError("선택 기록과 비교 결과의 표본/설정이 다릅니다.")
    overall = selection_counts(picks, names)
    per_source = {source: selection_counts([p for p in picks if sources[p["id"]] == source], names)
                  for source in sorted(set(sources.values()))}
    lines = ["# 전사 눈가림 비교 결과", "", f"승인 표본 {len(rows)}개, 최종 선택 {len(picks)}개.",
             f"전부 엉망 {overall['all_bad']}개, 비슷해서 못 고르겠음 {overall['similar']}개.", "",
             "비율의 분모는 해당 범위의 최종 선택 수(전부 엉망·비슷함 포함)입니다. "
             "동일 전사 후보를 선택하면 해당 설정 각각에 1표를 주므로 합계는 100%를 넘을 수 있습니다. "
             "재선택은 id별 마지막 기록만 반영합니다.", "",
             "| 설정 | 선택 횟수 | 선택 비율 |", "|---|---:|---:|"]
    for name in names:
        c = overall["configs"][name]
        lines.append(f"| {name} | {c['count']} | {c['rate']:.1%} |")
    lines.extend(["", "## 영상별", "", "| 영상 | 최종 선택 수 | 전부 엉망 | 비슷함 | 설정 | 선택 횟수 | 선택 비율 |",
                  "|---|---:|---:|---:|---|---:|---:|"])
    for source, summary in per_source.items():
        for name, c in summary["configs"].items():
            lines.append(f"| {source} | {summary['total']} | {summary['all_bad']} | {summary['similar']} | {name} | {c['count']} | {c['rate']:.1%} |")
    lines.extend(["", "## 기계 지표 (전체 승인 클립)", "", "정확도 지표가 아닙니다. 글자 수는 공백·문장부호를 포함하며 줄 구분 개행은 제외합니다. 반복은 8회 이상 연속한 동일 단어·음절의 겹치지 않는 구간 수입니다.", "",
                  "| 설정 | 반복 구간 | 반복 클립 | 빈 클립 | 줄 수 | 줄당 글자 min / median / p90 / max | 클립당 글자 min / median / p90 / max | 총 글자 |",
                  "|---|---:|---:|---:|---:|---|---|---:|"])
    stats = {}
    for name in names:
        metrics = summarize(list(runs[name].values()))
        def dist(field):
            return " / ".join(number(metrics[field][k]) for k in ("min", "median", "p90", "max"))
        lines.append(f"| {name} | {metrics['repetition_runs']} | {metrics['clips_with_repetition']} | {metrics['empty_clips']} | {metrics['line_count']} | {dist('line_chars')} | {dist('clip_chars')} | {metrics['clip_chars']['total']} |")
        path = paths[name].with_suffix(".stats.json")
        stats[name] = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        if stats[name] and (stats[name].get("config") != name or stats[name].get("clip_count") != len(rows)
                            or stats[name].get("metrics") != metrics):
            raise ValueError("stats와 전사 결과가 다릅니다. 해당 설정을 다시 실행하세요.")
    lines.extend(["", "## 소요 시간과 GPU", "", "| 설정 | 총 시간(초) | 모델 로드(초) | 전사(초) | GPU 최대 관측(MiB) | GPU 표본 수 |",
                  "|---|---:|---:|---:|---:|---:|"])
    for name in names:
        s = stats[name]
        lines.append(f"| {name} | {number(s.get('total_seconds'))} | {number(s.get('model_load_seconds'))} | {number(s.get('inference_seconds'))} | {number(s.get('gpu', {}).get('peak_mib'))} | {s.get('gpu', {}).get('samples', 0)} |")
    lines.extend(["", "총 시간은 모델 로드·전사·GPU 관측 종료를 포함하고 결과 파일 저장은 제외합니다. "
                  "GPU 값은 GPU 0의 nvidia-smi 장치 전체 메모리를 약 0.2초 간격으로 관측한 최댓값으로 다른 프로세스도 포함합니다. "
                  "짧은 피크는 놓칠 수 있으며, 관측 실패 또는 stats 누락은 미측정으로 표시합니다. "
                  "평균 로그 확률·무음 확률은 디코드 세그먼트 길이로 가중 평균하며, 세그먼트가 없으면 null입니다.", ""])
    atomic_write(out, "\n".join(lines).encode("utf-8"))
    return dict(overall=overall, per_source=per_source)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--picks", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args(argv)
    if not args.picks.is_file():
        parser.error("선택 기록 파일이 없습니다.")
    try:
        report(args.picks, args.out)
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(1, "보고서 생성 실패: 선택 기록·설정별 전체 결과·stats를 확인하세요.\n")
    print(f"보고서 저장: {args.out}")


if __name__ == "__main__":
    main()
