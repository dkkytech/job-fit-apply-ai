"""Append-only metrics history + rolling baseline for regression detection.

Replaces the single-snapshot `last_metrics.json` (kept for one release as a shim). Each
non-empty run appends one line; the baseline is the per-metric median over the last N runs
(median is robust to a single outlier run). The LLM is given both the immediate prior run
and the rolling median so "regression" means a move off the trend, not just vs one run.

Rates (error_rate, zero_score_rate, thin_digest_rate) are the comparable signals across
variable-size cursor windows; raw counts vary with window size and are for evidence only.

The median is weighted by each run's `window_jobs`. A run's rate is an estimate whose
precision scales with the number of jobs behind it: a 1-job window reports a rate quantized
to 0.0 or 1.0, and must not sway the trend as much as a 13-job window. Weighting is a strict
generalization — with uniform window sizes it reduces to the plain median.
"""

import json
from pathlib import Path

# Metrics worth trending. Rates first (comparable across window sizes), then stable gauges.
BASELINE_KEYS = [
    "error_rate", "zero_score_rate", "thin_digest_rate",
    "avg_score", "p95_duration_ms",
]


def append(history_file: Path, run_ts, cursor_from, cursor_to, window_jobs, metrics):
    """Append one run's metrics. Best-effort; never raises."""
    rec = {
        "run_ts": run_ts,
        "cursor_from": cursor_from,
        "cursor_to": cursor_to,
        "window_jobs": window_jobs,
        "metrics": metrics,
    }
    try:
        p = Path(history_file)
        p.parent.mkdir(parents=True, exist_ok=True)
        with p.open("a") as f:
            f.write(json.dumps(rec) + "\n")
    except OSError:
        pass


def read_all(history_file: Path):
    """All recorded run rows, oldest→newest. Public accessor for outcome correlation."""
    return _read(history_file)


def _read(history_file: Path):
    p = Path(history_file)
    if not p.exists():
        return []
    out = []
    for line in p.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return out


def _median(vals):
    vals = sorted(v for v in vals if isinstance(v, (int, float)))
    n = len(vals)
    if n == 0:
        return None
    mid = n // 2
    return vals[mid] if n % 2 else round((vals[mid - 1] + vals[mid]) / 2, 3)


def weighted_median(pairs):
    """Job-weighted median of [(value, weight), ...]; None when there is no usable mass.

    Weights are job counts. Identical to `_median` when every weight is equal, so history
    written before variable-size windows existed is scored exactly as it was before.
    """
    pts = sorted((float(v), float(w)) for v, w in pairs
                 if isinstance(v, (int, float)) and isinstance(w, (int, float)) and w > 0)
    if not pts:
        return None
    half = sum(w for _, w in pts) / 2.0
    cum = 0.0
    for i, (v, w) in enumerate(pts):
        cum += w
        if cum > half:
            return v
        if cum == half:  # exact split — average across the boundary, as _median does
            return round((v + (pts[i + 1][0] if i + 1 < len(pts) else v)) / 2, 3)
    return pts[-1][0]


def _jobs(row):
    """A history row's job mass; rows predating the field count as one job."""
    n = row.get("window_jobs")
    return n if isinstance(n, (int, float)) and n > 0 else 1


def baseline(history_file: Path, window: int = 10):
    """Per-metric job-weighted median over the last `window` recorded runs.

    Returns {"runs": k, "jobs": J, "window": N, "median": {metric: value, ...},
             "last": {metric: value, ...}} — or {} when there is no history yet.
    """
    hist = _read(history_file)
    if not hist:
        return {}
    recent = hist[-window:]
    med = {}
    for k in BASELINE_KEYS:
        pairs = [(h.get("metrics", {}).get(k), _jobs(h)) for h in recent]
        m = weighted_median([(v, w) for v, w in pairs if v is not None])
        if m is not None:
            med[k] = m
    last = {k: recent[-1].get("metrics", {}).get(k) for k in BASELINE_KEYS}
    return {"runs": len(recent), "jobs": sum(_jobs(h) for h in recent),
            "window": window, "median": med, "last": last}
