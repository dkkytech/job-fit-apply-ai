"""Close the loop: finding → fix → outcome (Phase D).

When the gated autofix loop opens a PR for a finding, it records the fingerprint in the
autofix ledger (`pr_open`). This module (1) reconciles those against GitHub — flipping merged
PRs to `pr_merged` with a merge timestamp — and (2) checks whether the finding's *tracked
metric* actually recovered after the fix landed, comparing the median over the K runs before
vs after the merge in metrics_history. A recovered metric auto-retires the finding (marked
`resolved` in the findings ledger); a fix that merged but didn't move the metric is flagged
`regressed`. Best-effort throughout — never sink the run.
"""

import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from analyzer import history

# Which metric each finding category should move once fixed (rate/badness metrics: lower is better).
# Only map categories with a direct, trustworthy metric. Scraping and infrastructure failures can
# degrade into an intentional fallback (email snippets) without increasing hard error_rate, so
# evaluating them against error_rate would falsely declare a live outage resolved.
CATEGORY_METRIC = {
    "scoring": "zero_score_rate",
    "digest": "thin_digest_rate",
    "tailoring": "error_rate",
    "config": "error_rate",
}
IMPROVE_FACTOR = 0.5   # metric must at least halve (or hit 0) to count as resolved
NO_CHANGE_FACTOR = 0.9  # >= 90% of the before-median after K runs -> the fix didn't help


def _latest_by_fp(ledger_path: Path):
    """Latest autofix-ledger record per fingerprint (append-only; last line wins)."""
    p = Path(ledger_path)
    if not p.exists():
        return {}
    out = {}
    for line in p.read_text().splitlines():
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        if e.get("fingerprint"):
            out[e["fingerprint"]] = e
    return out


def _append(ledger_path: Path, rec):
    try:
        p = Path(ledger_path)
        p.parent.mkdir(parents=True, exist_ok=True)
        with p.open("a") as fh:
            fh.write(json.dumps(rec) + "\n")
    except OSError:
        pass


def _run_ts_epoch(ts):
    """Parse a history run_ts ('%Y%m%d_%H%M%S') to epoch seconds; None if unparseable."""
    try:
        return datetime.strptime(ts, "%Y%m%d_%H%M%S").replace(tzinfo=timezone.utc).timestamp()
    except (ValueError, TypeError):
        return None


def _iso_epoch(iso):
    """Parse a GitHub ISO timestamp (…Z) to epoch seconds; None if unparseable."""
    try:
        return datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp()
    except (ValueError, TypeError, AttributeError):
        return None


def _median(vals):
    vals = sorted(v for v in vals if isinstance(v, (int, float)))
    if not vals:
        return None
    n = len(vals)
    return vals[n // 2] if n % 2 else (vals[n // 2 - 1] + vals[n // 2]) / 2


def reconcile_merges(ledger_path: Path, gh_bin="gh"):
    """Flip pr_open autofix entries whose PR has merged to pr_merged (+ merged_ts). Best-effort."""
    import shutil
    if shutil.which(gh_bin) is None:
        return
    for fp, e in _latest_by_fp(ledger_path).items():
        if e.get("status") != "pr_open" or not e.get("pr_url"):
            continue
        try:
            p = subprocess.run([gh_bin, "pr", "view", e["pr_url"], "--json", "state,mergedAt"],
                               capture_output=True, text=True, timeout=20)
            if p.returncode != 0:
                continue
            info = json.loads(p.stdout or "{}")
        except (subprocess.SubprocessError, OSError, json.JSONDecodeError):
            continue
        if info.get("state") == "MERGED":
            _append(ledger_path, {"fingerprint": fp, "finding_id": e.get("finding_id"),
                                  "pr_url": e["pr_url"], "status": "pr_merged",
                                  "merged_ts": info.get("mergedAt")})


def check_outcomes(findings_ledger_load, autofix_ledger_path, history_path, k=3):
    """Return {"resolved": [fp...], "regressed": [fp...]} for merged fixes with enough post-merge data.

    `findings_ledger_load` is a {fingerprint: entry} dict (from findings_ledger.load) used for the
    finding's category and current status.
    """
    resolved, regressed = [], []
    autofix = _latest_by_fp(autofix_ledger_path)
    hist = history.read_all(history_path)
    for fp, e in autofix.items():
        if e.get("status") != "pr_merged":
            continue
        fentry = findings_ledger_load.get(fp) or {}
        if fentry.get("status") == "resolved":
            continue  # already retired
        metric = CATEGORY_METRIC.get(fentry.get("category", ""))
        # Do not auto-resolve a category until it has a direct, category-specific recovery
        # signal. In particular, scraper/infra fallbacks may keep error_rate at zero.
        if metric is None:
            continue
        merged_ep = _iso_epoch(e.get("merged_ts"))
        if merged_ep is None:
            continue
        before, after = [], []
        for h in hist:
            ep = _run_ts_epoch(h.get("run_ts"))
            val = (h.get("metrics") or {}).get(metric)
            if ep is None or val is None:
                continue
            (before if ep < merged_ep else after).append(val)
        if len(after) < k:
            continue  # not enough post-merge runs yet — decide later
        bmed = _median(before[-k:])
        amed = _median(after[:k])
        if bmed is None or amed is None:
            continue
        if amed <= bmed * IMPROVE_FACTOR or amed == 0:
            resolved.append(fp)
        elif amed >= bmed * NO_CHANGE_FACTOR:
            regressed.append(fp)
    return {"resolved": resolved, "regressed": regressed}
