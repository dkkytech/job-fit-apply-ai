#!/usr/bin/env python3
"""Run-analyzer orchestrator.

Drains the bridge completed-event feed from a persisted, independent cursor, joins the
window with run_log.jsonl, computes deterministic metrics, asks the configured model to
judge health + scoring quality, and writes each finding as a self-contained task file.

Driven by run_analyzer.sh (config via the environment). Best-effort: never crash the
scheduled job. No batch is triggered — a "run" is simply the set of jobs completed since
the last cursor position (the poller feeds Gmail->bridge and the processor drains it
continuously).

Later phases layer on: rolling-baseline trend tracking (Phase 2), a deep scoring audit
(Phase 3), and a gated auto-fix -> living draft PR loop (Phase 4, --autofix).
"""

import json
import os
import sys
from pathlib import Path

from analyzer import audit, detectors, findings_ledger, history, notify, outcomes
from analyzer.bridge import BridgeClient
from analyzer.cursor import Cursor
from analyzer.findings import fingerprint, write_findings
from analyzer.llm import call_model
from analyzer.metrics import compute_metrics
from analyzer.sources import join_window, load_run_log

ENV = os.environ

RUN_LOG = Path(ENV["RUN_LOG"])
RUN_TS = ENV["RUN_TS"]
FINDINGS_DIR = Path(ENV["FINDINGS_DIR"])
CURSOR_FILE = Path(ENV.get("CURSOR_FILE", "state/cursor"))
METRICS_FILE = Path(ENV.get("METRICS_FILE", "state/last_metrics.json"))
HISTORY_FILE = Path(ENV.get("HISTORY_FILE", "state/metrics_history.jsonl"))
FINDINGS_LEDGER_FILE = Path(ENV.get("FINDINGS_LEDGER_FILE", "state/findings_ledger.jsonl"))
AUTOFIX_LEDGER_FILE = Path(ENV.get("LEDGER_FILE", "state/autofix_ledger.jsonl"))
BASELINE_WINDOW = int(ENV.get("RUN_ANALYZER_BASELINE_WINDOW", "10"))
RESOLVE_RUNS = int(ENV.get("RUN_ANALYZER_RESOLVE_RUNS", "3"))
RESOLVE_MIN_JOBS = int(ENV.get("RUN_ANALYZER_RESOLVE_MIN_JOBS", str(outcomes.MIN_RESOLVE_JOBS)))
SKILL = Path(ENV["SKILL_FILE"]).read_text()
PROMPT = Path(ENV["PROMPT_FILE"]).read_text()
MODEL = ENV.get("MODEL", "Qwen3.5-9B-OptiQ-4bit")
BRIDGE_URL = ENV.get("JD_BRIDGE_URL", "http://127.0.0.1:8765")
PROCESSOR_LOG_TAIL = ENV.get("PROCESSOR_LOG_TAIL", "")

MAX_RECORDS = 200  # cap tokens for very large windows
CONTEXT_N = int(ENV.get("RUN_ANALYZER_CONTEXT_N", "40"))


def assemble_window():
    """Decide whether to analyze now and, if so, build both windows.

    Returns (decision, report, since, last_seq, context) where decision is one of
    "cold" | "empty" | "analyze". Every non-empty cursor delta is analyzed immediately.
    """
    bridge = BridgeClient(BRIDGE_URL)
    cursor = Cursor(CURSOR_FILE)
    since = cursor.read()
    if since is None:  # cold start — seed at head, skip history
        head = bridge.head_seq()
        cursor.write(head)
        print(f"[analyze] cold start — seeding cursor at head seq {head} (skipping history)")
        return "cold", [], head, head, []

    # Report window = cursor-delta.
    completed, last_seq = bridge.drain(since)
    run_log_by_id = load_run_log(RUN_LOG)
    report = join_window(completed, run_log_by_id)[-MAX_RECORDS:]

    if not report:  # nothing new
        return "empty", [], since, since, []

    # Analyze every non-empty report window and consume it immediately.
    cursor.write(last_seq)

    # Rolling context window = last CONTEXT_N completed (already-seen included), read-only —
    # gives metrics/patterns/regressions a stable backdrop even when the report window is small.
    context = join_window(bridge.fetch_last(CONTEXT_N), run_log_by_id)
    return "analyze", report, since, last_seq, context


def build_user_prompt(metrics, ctx_metrics, prior, base, recs, context, det_findings):
    det = json.dumps([{"id": f["id"], "title": f["title"], "severity": f["severity"],
                       "category": f["category"]} for f in det_findings])
    return (
        f"{PROMPT}\n\n"
        f"DETERMINISTIC_FINDINGS (already detected by rules and INCLUDED in the output — do NOT "
        f"re-report these; only add NET-NEW issues the rules missed, and root-cause/label as needed):"
        f"\n{det}\n\n"
        f"THIS_BATCH_METRICS (the new jobs analyzed this run — pre-computed, verify/refine):\n{json.dumps(metrics)}\n\n"
        f"RECENT_CONTEXT_METRICS (over the last {len(context)} completed jobs — use these for "
        f"rates, per-board patterns, and regression judgement; the batch may be small):\n{json.dumps(ctx_metrics)}\n\n"
        f"PRIOR_METRICS (immediately previous run):\n{json.dumps(prior)}\n\n"
        f"ROLLING_BASELINE (job-weighted median over the last {BASELINE_WINDOW} runs, so small "
        f"windows count less — a metric moving off this median is a regression even if absolute "
        f"counts look fine):\n{json.dumps(base)}\n\n"
        f"RUN_REPORT ({len(recs)} NEW job record(s) this batch, one JSON per line — focus findings here):\n"
        + "\n".join(json.dumps(r) for r in recs)
        + f"\n\nCONTEXT_WINDOW ({len(context)} recent job record(s) for backdrop only, do not re-report old issues):\n"
        + "\n".join(json.dumps(r) for r in context)
        + f"\n\nPROCESSOR_LOG_TAIL (recent jobfit-processor log, for root-causing only):\n{PROCESSOR_LOG_TAIL}\n"
    )


def main():
    decision, recs, since, last_seq, context = assemble_window()

    # Cold start or empty window: nothing to analyze this tick. Exit cheaply — no LLM call.
    if decision != "analyze":
        if decision in ("cold", "empty"):
            print(f"[analyze] no new completed jobs since seq {since} — nothing to do")
        return 0

    metrics = compute_metrics(recs)
    ctx_metrics = compute_metrics(context) if context else metrics

    prior = {}
    if METRICS_FILE.exists():
        try:
            prior = json.loads(METRICS_FILE.read_text())
        except json.JSONDecodeError:
            prior = {}
    base = history.baseline(HISTORY_FILE, window=BASELINE_WINDOW)

    # Deterministic detectors (Phase B): rule findings for the unambiguous problem classes,
    # computed with no LLM so they survive a model outage and don't cost tokens.
    try:
        det_findings = detectors.detect(recs, metrics, base)
    except Exception as e:  # noqa: BLE001
        print(f"[analyze] detectors failed (non-fatal): {e}", file=sys.stderr)
        det_findings = []

    user = build_user_prompt(metrics, ctx_metrics, prior, base, recs, context, det_findings)

    model_ok = True
    try:
        raw = call_model(SKILL, user, model=MODEL)
        analysis = json.loads(raw)
        if not isinstance(analysis, dict):  # weak models sometimes return a bare list/scalar
            raise ValueError(f"model returned {type(analysis).__name__}, expected object")
    except Exception as e:  # noqa: BLE001 — never crash; keep the deterministic findings
        print(f"[analyze] model call/parse failed: {e}", file=sys.stderr)
        analysis = {"health": "unknown", "summary": f"Analyzer model unavailable: {e}",
                    "regressions": [], "findings": []}
        model_ok = False

    analysis.setdefault("metrics", metrics)
    analysis["cursor"] = {"from": since, "to": last_seq, "window_jobs": len(recs)}
    analysis["context_metrics"] = ctx_metrics
    analysis["context_jobs"] = len(context)
    analysis["baseline"] = base

    # Deep scoring audit (Phase 3): verify a triaged subset of scores against JD evidence.
    audit_findings = []
    try:
        audit_findings, verdicts = audit.run_audit(recs, MODEL)
        if verdicts:
            analysis["scoring_audit"] = verdicts
    except Exception as e:  # noqa: BLE001
        print(f"[analyze] scoring audit failed (non-fatal): {e}", file=sys.stderr)

    # Merge findings, deterministic first (authoritative), deduped by fingerprint.
    analysis["findings"] = _merge_findings(
        det_findings, analysis.get("findings", []) or [], audit_findings)

    findings = write_findings(analysis, FINDINGS_DIR, RUN_TS)
    _save_metrics(analysis.get("metrics", metrics))
    # Trend history: record our deterministic metrics (not the model's, which may drift).
    history.append(HISTORY_FILE, RUN_TS, since, last_seq, len(recs), metrics)

    # Phase C: classify findings vs the ledger and surface only what CHANGED.
    delta = findings_ledger.classify(findings, FINDINGS_LEDGER_FILE, RUN_TS)

    # Phase D: close the loop — did a merged autofix actually move its target metric?
    resolved, regressed = [], []
    try:
        outcomes.reconcile_merges(AUTOFIX_LEDGER_FILE)
        oc = outcomes.check_outcomes(
            findings_ledger.load(FINDINGS_LEDGER_FILE), AUTOFIX_LEDGER_FILE,
            HISTORY_FILE, k=RESOLVE_RUNS, min_jobs=RESOLVE_MIN_JOBS)
        resolved, regressed = oc["resolved"], oc["regressed"]
        if resolved:
            findings_ledger.mark_resolved(FINDINGS_LEDGER_FILE, resolved, RUN_TS)
    except Exception as e:  # noqa: BLE001 — outcome tracking must never sink the run
        print(f"[analyze] outcome check failed (non-fatal): {e}", file=sys.stderr)

    analysis["delta"] = {
        "new": [f["fingerprint"] for f in delta["new"]],
        "worsening": [f["fingerprint"] for f in delta["worsening"]],
        "unchanged": len(delta["unchanged"]),
        "resolved": resolved,
        "regressed": regressed,
    }
    # Re-persist analysis.json now that the delta block is attached.
    (FINDINGS_DIR / "analysis.json").write_text(json.dumps(analysis, indent=2))
    _notify_delta(delta, analysis, resolved, regressed)

    print(f"[analyze] health={analysis.get('health')} "
          f"batch={metrics['jobs']} context={len(context)} errors={metrics['errors']} "
          f"zero_score={metrics['zero_score']} thin_digest={metrics['thin_digest']} "
          f"seq={since}->{last_seq} findings={len(findings)} "
          f"new={len(delta['new'])} worsening={len(delta['worsening'])} unchanged={len(delta['unchanged'])} "
          f"resolved={len(resolved)} regressed={len(regressed)}")
    print(f"[analyze] {analysis.get('summary', '')}")
    for f in findings:
        print(f"  - [{f.get('severity')}] {f.get('title')}")
    return 0 if model_ok else 1


def _merge_findings(*groups):
    """Concatenate finding groups (deterministic first = authoritative), dedup by fingerprint."""
    seen, out = set(), []
    for group in groups:
        for f in group:
            fp = f.get("fingerprint") or fingerprint(f)
            if fp in seen:
                continue
            seen.add(fp)
            out.append(f)
    return out


def _notify_delta(delta, analysis, resolved=None, regressed=None):
    """Ping Discord/Telegram for what CHANGED — new/worsening/regressed/resolved (no-op when
    creds blank). Steady-state (only UNCHANGED findings) stays silent."""
    resolved, regressed = resolved or [], regressed or []
    new, worse = delta["new"], delta["worsening"]
    if not (new or worse or regressed or resolved):
        return
    lines = []
    if new:
        lines.append(f"🆕 {len(new)} new:")
        lines += [f"  • {findings_ledger.summarize(f)}" for f in new]
    if worse:
        lines.append(f"📈 {len(worse)} worsening:")
        lines += [f"  • {findings_ledger.summarize(f)}" for f in worse]
    if regressed:
        lines.append(f"⚠️ {len(regressed)} fixed-but-not-improved (metric didn't move after merge)")
    if resolved:
        lines.append(f"✅ {len(resolved)} resolved (metric recovered after a merged fix)")
    title = (f"run-analyzer [{analysis.get('health', '?')}]: "
             f"{len(new)} new, {len(worse)} worsening, {len(resolved)} resolved")
    try:
        notify.send(title, "\n".join(lines))
    except Exception as e:  # noqa: BLE001 — notification must never sink the run
        print(f"[analyze] delta notify failed (non-fatal): {e}", file=sys.stderr)


def _save_metrics(metrics):
    try:
        METRICS_FILE.parent.mkdir(parents=True, exist_ok=True)
        METRICS_FILE.write_text(json.dumps(metrics, indent=2))
    except OSError as e:
        print(f"[analyze] failed to persist metrics: {e}", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
