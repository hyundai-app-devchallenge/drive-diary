"""Pure hysteresis simulation and temporal episode metrics; see DESIGN.md §3."""

from dataclasses import asdict, dataclass
import math

from common.schema import EXCLUDES
from .metrics import probability


@dataclass(frozen=True)
class AssemblyConfig:
    tau_open: float = 0.5
    tau_keep: float = 0.3
    low_turns: int = 2
    silence_seconds: float = 20.0
    context_turns: int = 3
    break_on_clip: bool = True
    bridge_unlabeled: bool = False
    include_trailing_low: bool = False

    def __post_init__(self):
        probability(self.tau_open, "tau_open")
        probability(self.tau_keep, "tau_keep")
        if self.tau_keep > self.tau_open:
            raise ValueError("tau_keep must be <= tau_open")
        if type(self.low_turns) is not int or self.low_turns < 1:
            raise ValueError("low_turns must be a positive integer")
        if type(self.context_turns) is not int or self.context_turns < 0:
            raise ValueError("context_turns must be a nonnegative integer")
        if not math.isfinite(self.silence_seconds) or self.silence_seconds <= 0:
            raise ValueError("silence_seconds must be finite and positive")


def assemble_episodes(rows, config=AssemblyConfig(), score_key="score"):
    """Return fresh dictionaries; never mutate input or join different sources.

    Pending low turns join only if a keep turn follows. By default all trailing
    lows are trimmed at closure/EOF. Explicit boundary_before closes and clears
    pre-roll (route/microphone/session boundaries can be supplied this way).
    """
    rows = sorted((r for r in rows if not r.get("skip", False)),
                  key=lambda r: (r["source"], r["start"], r["end"], r["id"]))
    seen = set()
    episodes, history, active, pending = [], [], [], []
    previous = None

    def close():
        nonlocal active, pending
        members = active + pending if config.include_trailing_low else active
        if members:
            episodes.append(dict(source=members[0]["source"],
                                 start=min(r["start"] for r in members),
                                 end=max(r["end"] for r in members),
                                 ids=[r["id"] for r in members]))
        active, pending = [], []

    for row in rows:
        if row["id"] in seen:
            raise ValueError(f"duplicate id: {row['id']}")
        seen.add(row["id"])
        if any(type(row[k]) not in (int, float) or not math.isfinite(row[k])
               for k in ("start", "end")) or row["start"] < 0 or row["end"] <= row["start"]:
            raise ValueError(f"{row['id']}: timing must satisfy 0 <= start < end")
        score = probability(row[score_key], score_key)
        boundary = previous is not None and (
            row["source"] != previous["source"]
            or row["start"] - previous["end"] >= config.silence_seconds
            or row.get("boundary_before", False)
            or (config.break_on_clip and row.get("clip") != previous.get("clip"))
            or (not config.bridge_unlabeled and row.get("unlabeled_before", False)))
        if boundary:
            close()
            history = []
        if not active:
            if score >= config.tau_open:
                count = min(config.context_turns, row.get("context_count", config.context_turns))
                context = history[-count:] if count else []
                if "context_ids" in row:
                    context = [r for r in context if r["id"] in row["context_ids"]]
                active = context + [row]
        elif score >= config.tau_keep:
            active.extend(pending + [row])
            pending = []
        else:
            pending.append(row)
            if len(pending) >= config.low_turns:
                close()
        history.append(row)
        history = history[-config.context_turns:] if config.context_turns else []
        previous = row
    close()
    return episodes


def overlap(a, b):
    if a["source"] != b["source"]:
        return 0.0
    return max(0.0, min(a["end"], b["end"]) - max(a["start"], b["start"]))


def episode_metrics(gold, predicted, duration_seconds, overlap_fraction=0.5):
    probability(overlap_fraction, "overlap_fraction")
    if overlap_fraction == 0:
        raise ValueError("overlap_fraction must be > 0")
    if not math.isfinite(duration_seconds) or duration_seconds < 0:
        raise ValueError("duration_seconds must be finite and nonnegative")
    found = sum(any(overlap(g, p) > 0 and
                    overlap(g, p) / (g["end"] - g["start"]) >= overlap_fraction
                    for p in predicted) for g in gold)
    false = sum(not any(overlap(g, p) > 0 for g in gold) for p in predicted)
    return dict(gold_n=len(gold), predicted_n=len(predicted), found_n=found, false_n=false,
                recall=found / len(gold) if gold else None,
                false_per_hour=false * 3600 / duration_seconds if duration_seconds else None,
                duration_seconds=duration_seconds)


def exclusion_safety(rows, predicted):
    result = {}
    for flag in sorted(EXCLUDES):
        flagged = [r for r in rows if not r.get("skip", False) and flag in r["exclude"]]
        inside = sum(any(overlap(r, p) > 0 for p in predicted) for r in flagged)
        result[flag] = dict(n=len(flagged), inside_n=inside,
                            inside_share=inside / len(flagged) if flagged else None)
    return result


def evaluate_episodes(rows, duration_seconds, config=AssemblyConfig(), *,
                      positive_story=2, overlap_fraction=0.5, sweep=None,
                      false_per_hour_budget=3.0, recall_target=0.75):
    if not math.isfinite(false_per_hour_budget) or false_per_hour_budget < 0:
        raise ValueError("false_per_hour_budget must be finite and nonnegative")
    probability(recall_target, "recall_target")
    rows = [r for r in rows if not r.get("skip", False)]
    gold_rows = [dict(r, gold_score=float(r["story"] >= positive_story)) for r in rows]
    # Binary gold uses the same grouping settings, with binary thresholds.
    gold_config = AssemblyConfig(**(asdict(config) | {"tau_open": 1.0, "tau_keep": 1.0}))
    gold = assemble_episodes(gold_rows, gold_config, "gold_score")
    predicted = assemble_episodes(rows, config)
    current = episode_metrics(gold, predicted, duration_seconds, overlap_fraction)
    if sweep is None:
        sweep = [i / 20 for i in range(21)]
    sweep = sorted(set(sweep) | {config.tau_open})
    curve = []
    for tau in sweep:
        probability(tau, "sweep tau_open")
        # A sweep below the configured keep threshold lowers keep as necessary.
        settings = AssemblyConfig(**(asdict(config) | {
            "tau_open": tau, "tau_keep": min(config.tau_keep, tau)}))
        episodes = assemble_episodes(rows, settings)
        curve.append(dict(tau_open=tau, tau_keep=settings.tau_keep,
                          **episode_metrics(gold, episodes, duration_seconds, overlap_fraction)))
    feasible = [point for point in curve if point["recall"] is not None
                and point["false_per_hour"] is not None
                and point["false_per_hour"] <= false_per_hour_budget]
    best = max(feasible, key=lambda p: (p["recall"], -p["false_per_hour"], p["tau_open"]),
               default=None)
    return dict(**current, gold=gold, predicted=predicted, curve=curve,
                budget=dict(false_per_hour=false_per_hour_budget, recall_target=recall_target,
                            recall=best["recall"] if best else None,
                            tau_open=best["tau_open"] if best else None,
                            tau_keep=best["tau_keep"] if best else None,
                            meets_target=best["recall"] >= recall_target if best else None),
                exclusion_safety=exclusion_safety(rows, predicted))
