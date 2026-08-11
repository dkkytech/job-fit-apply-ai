"""Assemble the run window: the bridge completed-feed is the spine; run_log.jsonl enriches.

The bridge feed defines window membership (the jobs that went terminal since the cursor).
run_log.jsonl (written by the processor, utils/RunReport.kt) adds fields the feed lacks —
`jdTextLen` (the thin-JD signal), `durationMs`, `isDigest`, `board`, `source`, `isDuplicate`.
We left-join on job_id == jobId. A bridge job with no run_log match is still analyzed and
flagged `run_log_missing` (itself a possible finding).

Output records keep the run_log-style field names the metrics + skill already reference, so
nothing downstream changes when the spine switched from run_log to the bridge feed.
"""

import json
from pathlib import Path

# A digest-derived JD shorter than this was scored on a summary, not a real posting.
THIN_JD_CHARS = 400


def is_thin_digest(rec):
    """True when [rec] is a digest CHILD whose JD is too thin to be a real posting.

    Excludes digest parents. A parent exists only to fan its children out as their own jobs; its
    jdText is the digest summary (or empty) by construction, so counting it would report a thin-JD
    problem for every digest email received.

    `pipelineRan` is what separates them: a parent goes terminal during the processor's scan/scrape
    resolve and never reaches ProcessingPipeline, while a child is re-enqueued as its own job and
    does. Nothing else in the record distinguishes them —

      * the terminal label does not: a child inherits the parent's `isDigest` intake and
        TerminalLabel.forState checks isDigest first, so BOTH read JD_Processed_Digest;
      * `hasJobUrl` does not: a single-job digest copies the child's URL onto the parent
        (DigestParseHelpers.applyDigestSummary);
      * the action/score shape does not: score_fit now SKIPs a stub JD at score 0, which is exactly
        the parent's shape too.

    So a line without `pipelineRan` (written before this field existed) falls back to a non-zero
    score — the one unambiguous proof it reached score_fit — and under-reports rather than raising a
    spurious per-board finding that could reach the autofix loop.
    """
    if not rec.get("isDigest"):
        return False
    if (rec.get("jdTextLen", 0) or 0) >= THIN_JD_CHARS:
        return False
    pipeline_ran = rec.get("pipelineRan")
    if pipeline_ran is not None:
        return bool(pipeline_ran)
    # Legacy line: a non-zero score is the only proof it reached score_fit as a child.
    return (rec.get("score", 0) or 0) > 0


def load_run_log(path: Path):
    """run_log.jsonl -> {jobId: last record}. Best-effort; skips malformed lines."""
    by_id = {}
    p = Path(path)
    if not p.exists():
        return by_id
    for line in p.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError:
            continue
        jid = rec.get("jobId")
        if jid:
            by_id[jid] = rec  # keep last occurrence
    return by_id


def join_window(completed, run_log_by_id):
    """Join bridge CompletedJob records (spine) with run_log enrichment.

    `completed` — list of bridge feed dicts (snake_case).
    `run_log_by_id` — dict from load_run_log().
    Returns a list of enriched dicts using run_log-style keys.
    """
    out = []
    for c in completed:
        jid = c.get("job_id")
        r = run_log_by_id.get(jid, {})
        job_url = c.get("job_url") or r.get("jobUrl")
        rec = {
            # identity / provenance
            "jobId": jid,
            "completed_seq": c.get("completed_seq"),
            "ts": r.get("ts"),
            "status": c.get("status"),               # done | error (bridge terminal)
            # Bridge feed first; the run_log copy (the processor's own decision) backfills a job
            # whose bridge row never got one, so is_thin_digest can still tell parent from child.
            "terminal_label": c.get("terminal_label") or r.get("terminalLabel"),
            "message_id": c.get("message_id"),
            "job_url": job_url,
            "artifact_url": c.get("artifact_url"),
            # identity of the posting
            "company": c.get("company") or r.get("company"),
            "roleTitle": c.get("role_title") or r.get("roleTitle"),
            "source": r.get("source"),               # run_log only
            "board": r.get("board"),                 # run_log only (derived)
            # classification
            "isDigest": bool(r.get("isDigest", False)),
            # None when the run_log line predates the field — is_thin_digest falls back on that.
            "pipelineRan": r.get("pipelineRan"),
            "isRecruiter": bool(c.get("is_recruiter", r.get("isRecruiter", False))),
            "isDuplicate": bool(r.get("isDuplicate", False)),
            # outcome
            "action": c.get("pipeline_action") or r.get("action"),
            "score": c.get("fit_score") if c.get("fit_score") is not None else r.get("score", 0),
            "error": c.get("error") or r.get("error"),
            # signals
            "jdTextLen": r.get("jdTextLen", 0),
            "scrapePath": r.get("scrapePath"),       # run_log only — http vs cdp_* transport
            "hasJobUrl": bool(job_url),
            "outputPath": r.get("outputPath"),
            "durationMs": r.get("durationMs", 0),
            # provenance flag
            "run_log_missing": jid not in run_log_by_id,
        }
        out.append(rec)
    return out
