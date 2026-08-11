"""Deterministic detectors — rule-based findings, no LLM.

The unambiguous problem classes (OOM, timeouts, a board's scrapes blocked, thin digests,
run_log gaps, TAILOR-after-error, rich-JD-scored-0) are fully detectable from the joined
window with zero tokens. Emitting them here — with stable canonical ids so the findings
ledger dedups them cleanly — means the LLM only spends its budget on net-new/ambiguous cases
and root-cause narration, not on re-deriving what a rule already knows.

Each detector returns finding dicts in the same shape as the model/audit findings
(id/title/severity/category/evidence/affected_jobs/proposed_fix/files/agent_prompt), so they
flow through write_findings + the ledger identically. Follows the aggregation precedent in
audit._findings_from_verdicts (one finding per root cause; per-board grouping).
"""

from analyzer.findings import slugify
from analyzer.sources import is_thin_digest

RICH_JD_CHARS = 1200
PROC_HANDLER = "services/job-fit-apply-ai-pipeline/src/main/kotlin/com/jd/pipeline/cli/commands/ProcessorCommandHandler.kt"
RUN_REPORT_KT = "services/job-fit-apply-ai-pipeline/src/main/kotlin/com/jd/pipeline/utils/RunReport.kt"
SCORE_FIT_KT = "services/job-fit-apply-ai-pipeline/src/main/kotlin/com/jd/pipeline/nodes/ScoreFitNode.kt"
COMPOSE = "docker-compose.yml"


def _err(rec, *subs):
    e = (rec.get("error") or "").lower()
    return any(s in e for s in subs)


def _ids(recs, limit=10):
    return [r.get("jobId") for r in recs][:limit]


def _board(rec):
    return rec.get("board") or "unknown"


def detect(recs, metrics=None, baseline=None):
    """Return the list of deterministic findings for this window's records."""
    out = []
    for fn in (_oom, _timeouts, _run_log_missing, _tailor_after_error,
               _rich_jd_scored_zero):
        out.extend(fn(recs))
    out.extend(_per_board_scrape_blocked(recs))
    out.extend(_per_board_thin_digest(recs))
    return out


# ── infra ─────────────────────────────────────────────────────────────────────────
def _oom(recs):
    hit = [r for r in recs if _err(r, "unexpectedly stopped")]
    if not hit:
        return []
    return [{
        "id": "ollama-oom",
        "title": "Model runner OOM / crash (jobs failed with 'unexpectedly stopped')",
        "severity": "high",
        "category": "infra",
        "evidence": [f"{r.get('jobId')} — {(r.get('error') or '')[:80]}" for r in hit[:5]],
        "affected_jobs": _ids(hit),
        "proposed_fix": "The model runner exceeded its working set and was killed. Lower the "
                        "model's memory footprint or the JVM/container heap caps, or reduce "
                        "concurrent model calls so the Metal working set isn't exceeded.",
        "files": [COMPOSE],
        "agent_prompt": _p(
            "Symptom: pipeline jobs are failing with 'model runner has unexpectedly stopped' "
            "(an OOM/crash of the local model runner).",
            hit,
            "Investigate the model-runner memory config and concurrency. Check the JVM heap caps "
            f"and per-model memory in {COMPOSE}, and the local-LLM max-concurrency setting. Reduce "
            "the working set so the runner isn't killed under load.",
            "no job in a fresh run fails with 'unexpectedly stopped'."),
    }]


def _timeouts(recs):
    hit = [r for r in recs if _err(r, "timed out", "timeout")]
    if not hit:
        return []
    sev = "high" if len(hit) >= max(3, len(recs) // 3) else "medium"
    return [{
        "id": "model-timeouts",
        "title": "Model/latency timeouts on some jobs",
        "severity": sev,
        "category": "infra",
        "evidence": [f"{r.get('jobId')} — {(r.get('error') or '')[:80]}" for r in hit[:5]],
        "affected_jobs": _ids(hit),
        "proposed_fix": "Jobs are timing out on a model call. Raise the client timeout, or "
                        "route the slow node to a faster model / reduce contention.",
        "files": ["services/job-fit-apply-ai-pipeline/src/main/kotlin/com/jd/pipeline/client/LlmClient.kt"],
        "agent_prompt": _p(
            "Symptom: pipeline jobs are failing with 'request timed out' on a model call.",
            hit,
            "Check the LlmClient/node timeouts and the model each node uses; raise the timeout or "
            "route the slow node to a faster model, and confirm no contention from concurrent calls.",
            "the affected node completes within its timeout on a re-run."),
    }]


def _run_log_missing(recs):
    hit = [r for r in recs if r.get("run_log_missing")]
    # Only a finding if a meaningful share is missing (a stray one isn't systemic).
    if len(hit) < max(3, len(recs) // 4):
        return []
    return [{
        "id": "run-log-missing",
        "title": "Processor not writing run_log for some terminal jobs",
        "severity": "medium",
        "category": "infra",
        "evidence": [f"{len(hit)}/{len(recs)} jobs missing a run_log record",
                     *[f"jobId {j}" for j in _ids(hit, 5)]],
        "affected_jobs": _ids(hit),
        "proposed_fix": "The bridge saw these jobs go terminal but the processor wrote no "
                        "run_log.jsonl record, so jdTextLen/durationMs/outputPath are lost "
                        "(blinding the analyzer). Ensure RunReport.record runs on every terminal "
                        "path (including skips/errors), not just the happy path.",
        "files": [PROC_HANDLER, RUN_REPORT_KT],
        "agent_prompt": _p(
            "Symptom: the bridge records jobs as terminal but the processor writes no "
            "output/runs/run_log.jsonl record for a large share of them, so the analyzer loses "
            "jdTextLen/durationMs/outputPath.",
            hit,
            f"In {PROC_HANDLER}, ensure RunReport.record ({RUN_REPORT_KT}) is called on EVERY "
            "terminal outcome (done, skip, and error), not only the tailored path.",
            "every terminal job in a fresh run has a matching run_log.jsonl line."),
    }]


# ── tailoring ───────────────────────────────────────────────────────────────────────
def _tailor_after_error(recs):
    hit = [r for r in recs if (r.get("action") or "").upper() == "TAILOR" and r.get("error")]
    if not hit:
        return []
    return [{
        "id": "tailor-after-error",
        "title": "Tailoring/render failed after a positive score",
        "severity": "medium",
        "category": "tailoring",
        "evidence": [f"{r.get('jobId')} — score={r.get('score')} err={(r.get('error') or '')[:60]}"
                     for r in hit[:5]],
        "affected_jobs": _ids(hit),
        "proposed_fix": "These jobs scored high enough to TAILOR but then errored during "
                        "tailoring/render, so no artifact was produced. Root-cause the tailor "
                        "subgraph failure for the shared error signature.",
        "files": ["services/job-fit-apply-ai-pipeline/src/main/kotlin/com/jd/pipeline/nodes/tailor"],
        "agent_prompt": _p(
            "Symptom: jobs with action=TAILOR (a positive fit score) failed with an error during "
            "tailoring/render, producing no résumé artifact.",
            hit,
            "Root-cause the tailor subgraph failure for the shared error signature and fix it so a "
            "positive-score job reliably produces its artifact.",
            "an affected job re-runs to a rendered artifact with no error."),
    }]


# ── scoring ─────────────────────────────────────────────────────────────────────────
def _rich_jd_scored_zero(recs):
    # Only jobs whose JD was ACTUALLY fetched (scrapePath http/cdp_*) count as "rich". A digest
    # child whose scrape failed falls back to the email snippet with an empty scrapePath — that
    # text can clear RICH_JD_CHARS yet isn't a real JD, so scoring it 0 is correct, not a bug.
    # Requiring a scrapePath keeps this from firing "scoring bug → ScoreFitNode" every time the
    # browser backend (Steel/CDP) is down — that's an infra fault, caught by other detectors.
    hit = [r for r in recs
           if (r.get("score", 0) or 0) == 0 and not r.get("isDuplicate")
           and (r.get("jdTextLen", 0) or 0) >= RICH_JD_CHARS
           and (r.get("scrapePath") or "").strip()]
    if not hit:
        return []
    return [{
        "id": "rich-jd-scored-zero",
        "title": "Rich JDs scored 0 (likely scoring/extraction bug)",
        "severity": "high" if len(hit) >= 2 else "medium",
        "category": "scoring",
        "evidence": [f"{r.get('jobId')} — score=0, jdTextLen={r.get('jdTextLen')}" for r in hit[:5]],
        "affected_jobs": _ids(hit),
        "proposed_fix": "A substantial JD scoring exactly 0 is almost never a genuine non-fit; "
                        "it points at an extraction/parse gap feeding the scorer or the rubric "
                        "collapsing to 0. Compare the JD the scorer saw against the raw page.",
        "files": [SCORE_FIT_KT],
        "agent_prompt": _p(
            "Symptom: job(s) with a large job_description.txt (rich JD) received a fit score of "
            "exactly 0, which is almost never a real non-fit.",
            hit,
            f"In {SCORE_FIT_KT}, determine why a rich JD collapses to score 0 — an extraction/parse "
            "gap, a misfiring hard gate, or the scoring prompt returning 0 on a formatting issue. "
            "Compare score_fit.txt and job_description.txt against the raw page for an affected job.",
            "re-scoring an affected JD yields a score > 0 consistent with its content."),
    }]


# ── per-board grouped ─────────────────────────────────────────────────────────────────
def _per_board_scrape_blocked(recs):
    by_board = {}
    for r in recs:
        if _err(r, "block", "captcha", "403"):
            by_board.setdefault(_board(r), []).append(r)
    out = []
    for board, hit in by_board.items():
        bslug = slugify(board)
        out.append({
            "id": f"scrape-blocked-{bslug}",
            "title": f"Scrapes blocked on {board}",
            "severity": "high" if len(hit) >= 3 else "medium",
            "category": "scraping",
            "evidence": [f"board={board}: {len(hit)} blocked",
                         *[f"jobId {j}" for j in _ids(hit, 4)]],
            "affected_jobs": _ids(hit),
            "proposed_fix": f"{board} is returning bot-block/captcha/403 for these scrapes, so "
                            "the JD falls back to a thin summary. Adjust the scrape path for this "
                            "board (headers/session/CDP) or route it through the JSON-LD extractor.",
            "files": ["services/job-fit-apply-ai-pipeline/src/main/kotlin/com/jd/pipeline/scrape"],
            "agent_prompt": _p(
                f"Symptom: scrapes on {board} are being blocked (bot-block/captcha/403), so those "
                "jobs get a thin JD.",
                hit,
                f"Improve the {board} scrape path (headers/session reuse/CDP, or a JSON-LD "
                "extraction fallback) so the full JD is captured instead of a blocked/thin page.",
                f"a fresh {board} scrape returns a full JD, not a block page."),
        })
    return out


def _per_board_thin_digest(recs):
    by_board = {}
    for r in recs:
        if is_thin_digest(r):
            by_board.setdefault(_board(r), []).append(r)
    out = []
    for board, hit in by_board.items():
        if len(hit) < 2:  # a single thin digest isn't a pattern
            continue
        bslug = slugify(board)
        out.append({
            "id": f"thin-digest-{bslug}",
            "title": f"{board} digests scored on thin JDs",
            "severity": "medium",
            "category": "digest",
            "evidence": [f"board={board}: {len(hit)} thin digests (<400 chars)",
                         *[f"jobId {r.get('jobId')} jdTextLen={r.get('jdTextLen')}" for r in hit[:4]]],
            "affected_jobs": _ids(hit),
            "proposed_fix": f"Digest jobs from {board} are being scored on a thin summary because "
                            "the per-job scrape failed/was blocked. Ensure the digest split scrapes "
                            "each job's real posting before scoring.",
            "files": ["services/job-fit-apply-ai-pipeline/src/main/kotlin/com/jd/pipeline/scrape"],
            "agent_prompt": _p(
                f"Symptom: {board} digest jobs are scored on a thin JD (<400 chars) because the "
                "per-posting scrape didn't yield the full description.",
                hit,
                f"Ensure the digest-split path fetches each {board} posting's full JD before scoring "
                "(handle the block/thin-page case), so scores aren't computed on a summary.",
                f"a {board} digest job is scored on a full JD, not a <400-char summary."),
        })
    return out


def _p(symptom, hit, action, acceptance):
    jobs = ", ".join(r.get("jobId") for r in hit[:10] if r.get("jobId"))
    return (f"{symptom}\n\nAffected jobIds: {jobs}.\n\n{action}\n\n"
            f"Acceptance check: {acceptance} Run `./gradlew test`.")
