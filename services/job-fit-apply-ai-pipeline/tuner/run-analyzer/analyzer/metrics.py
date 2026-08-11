"""Deterministic aggregate metrics over a run window.

Computed in Python (not the model) so they are accurate regardless of model quality; the
LLM verifies/refines and roots-causes. Rate metrics (count/jobs) are included so trend
comparison across variable-size cursor windows stays meaningful (Phase 2).
"""


from analyzer.sources import is_thin_digest


def _has(rec, *subs):
    e = (rec.get("error") or "").lower()
    return any(s in e for s in subs)


def compute_metrics(recs):
    jobs = len(recs)
    scores = [r.get("score", 0) or 0 for r in recs]
    durs = sorted((r.get("durationMs", 0) or 0) for r in recs)
    p95 = durs[int(len(durs) * 0.95)] if durs else 0

    # Scrape transport mix — how each JD was obtained. Confirms the browser path (Steel/CDP)
    # is actually carrying LinkedIn/forced domains vs. plain HTTP doing the bulk of the work.
    scrape_paths = {}
    for r in recs:
        sp = r.get("scrapePath") or "unknown"
        scrape_paths[sp] = scrape_paths.get(sp, 0) + 1
    scrape_via_http = scrape_paths.get("http", 0)
    scrape_via_cdp = sum(v for k, v in scrape_paths.items() if k.startswith("cdp_"))

    errors = sum(1 for r in recs if r.get("error"))
    zero_score = sum(
        1 for r in recs if (r.get("score", 0) or 0) == 0 and not r.get("isDuplicate")
    )
    thin_digest = sum(1 for r in recs if is_thin_digest(r))

    m = {
        "jobs": jobs,
        "tailored": sum(1 for r in recs if (r.get("action") or "").upper() == "TAILOR"),
        "skipped": sum(1 for r in recs if (r.get("action") or "").upper() == "SKIP"),
        "errors": errors,
        "zero_score": zero_score,
        "thin_digest": thin_digest,
        "scrape_blocked": sum(1 for r in recs if _has(r, "block", "captcha", "403")),
        "scrape_paths": scrape_paths,
        "scrape_via_http": scrape_via_http,
        "scrape_via_cdp": scrape_via_cdp,
        "timeouts": sum(1 for r in recs if _has(r, "timed out", "timeout")),
        "ollama_oom": sum(1 for r in recs if _has(r, "unexpectedly stopped")),
        "run_log_missing": sum(1 for r in recs if r.get("run_log_missing")),
        "avg_score": round(sum(scores) / len(scores), 1) if scores else 0.0,
        "p95_duration_ms": p95,
    }
    # Rates (0.0 when no jobs) — comparable across window sizes for trend detection.
    denom = jobs or 1
    m["error_rate"] = round(errors / denom, 3)
    m["zero_score_rate"] = round(zero_score / denom, 3)
    m["thin_digest_rate"] = round(thin_digest / denom, 3)
    return m
