"""Summarize the watchlist's last run (watchlist-health.json on stdin) for scripts/doctor.sh.

Prints one `<ok|warn>\t<message>` line per finding. Feeds rot quietly (rebrands, ATS moves), and a
stuck run gate keeps the container healthy while nothing is polled, so the doctor surfaces both.

    docker exec jobfit-watchlist cat /state/watchlist-health.json \
      | python3 -I tools/health_summary.py --interval-ms 86400000 --container jobfit-watchlist
"""
import argparse
import json
import sys
import time

HOUR_MS = 3_600_000
MAX_NAMES = 8


def names(companies):
    return ", ".join(companies[:MAX_NAMES]) + (" …" if len(companies) > MAX_NAMES else "")


def summarize(health, now_ms, interval_ms, container="jobfit-watchlist"):
    """The doctor's findings as (level, message) pairs, in display order."""
    employers = health.get("employers", [])
    findings = []

    ran_at = health.get("ran_at")
    if ran_at:
        age_h = (now_ms - ran_at) / HOUR_MS
        # One late pass is a restart or a slow feed; two missed passes is a stuck gate.
        if now_ms - ran_at > 2 * interval_ms:
            findings.append(("warn", "watchlist last full run %.0fh ago (expected every %.0fh)  →  docker logs %s"
                             % (age_h, interval_ms / HOUR_MS, container)))
        else:
            queued = sum(e.get("queued", 0) for e in employers)
            findings.append(("ok", "watchlist last run %.0fh ago: %d feeds polled, %d new postings queued"
                             % (age_h, len(employers), queued)))

    # RunHealth writes these lists itself, so the rule (may_be_empty included) lives in one place.
    failing = health.get("failing", [])
    empty = health.get("empty", [])
    if failing:
        findings.append(("warn", "watchlist feeds failing (%d/%d: %s)  →  re-check those employers.yaml entries"
                         % (len(failing), len(employers), names(failing))))
    if empty:
        findings.append(("warn", "watchlist feeds listing no jobs (%d/%d: %s)  →  re-check the feed, or mark may_be_empty"
                         % (len(empty), len(employers), names(empty))))
    return findings


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--interval-ms", type=int, default=86_400_000)
    parser.add_argument("--container", default="jobfit-watchlist")
    args = parser.parse_args(argv)
    try:
        health = json.load(sys.stdin)
    except ValueError:
        return 0  # no run yet, or a half-written file: nothing to report
    if not isinstance(health, dict):
        return 0
    for level, message in summarize(health, int(time.time() * 1000), args.interval_ms, args.container):
        print("%s\t%s" % (level, message))
    return 0


if __name__ == "__main__":
    sys.exit(main())
