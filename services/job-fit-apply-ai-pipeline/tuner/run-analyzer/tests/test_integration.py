"""Integration tests: the cadence gate (assemble_window) and the full main() flow.

Hermetic — a fake bridge and a stubbed model, no network / LLM / live services. analyze.py
reads required config from the environment at import time, so we set it before importing.
"""

import importlib
import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_RA = _HERE.parent
sys.path.insert(0, str(_RA))

_TMP = tempfile.mkdtemp()
os.environ.update({
    "RUN_LOG": f"{_TMP}/run_log.jsonl",
    "RUN_TS": "testrun",
    "FINDINGS_DIR": f"{_TMP}/findings/testrun",
    "SKILL_FILE": str(_RA / "RUN_ANALYZER_SKILL.md"),
    "PROMPT_FILE": str(_RA / "PROMPT.md"),
    "CURSOR_FILE": f"{_TMP}/cursor",
    "PENDING_FILE": f"{_TMP}/pending",
    "METRICS_FILE": f"{_TMP}/last_metrics.json",
    "HISTORY_FILE": f"{_TMP}/history.jsonl",
    "FINDINGS_LEDGER_FILE": f"{_TMP}/findings_ledger.jsonl",
    "RUN_ANALYZER_AUDIT_MAX": "0",   # skip the deep audit (needs files/DB)
    "JD_BRIDGE_URL": "http://fake",
})
# Ensure notifications stay a no-op, regardless of credentials inherited from the invoking shell.
for _k in (
    "DISCORD_BOT_TOKEN",
    "DISCORD_CHANNEL_ID",
    "RUN_ANALYZER_TELEGRAM_BOT_TOKEN",
    "RUN_ANALYZER_TELEGRAM_CHAT_ID",
    "TELEGRAM_BOT_TOKEN",
    "TELEGRAM_CHAT_ID",
):
    os.environ.pop(_k, None)

from analyzer import notify as _notify  # noqa: E402
importlib.reload(_notify)
import analyze  # noqa: E402
analyze = importlib.reload(analyze)

assert analyze.notify.TELEGRAM_TOKEN == "", "integration tests must disable Telegram notifications"
assert analyze.notify.TELEGRAM_CHAT == "", "integration tests must disable Telegram notifications"


def _job(seq):
    return {"job_id": f"j{seq}", "completed_seq": seq, "status": "done",
            "company": "Acme", "fit_score": 60, "pipeline_action": "SKIP",
            "job_url": f"http://x/{seq}"}


class FakeBridge:
    head = 100
    records = []

    def head_seq(self):
        return FakeBridge.head

    def drain(self, since, on_page=None):
        recs = [r for r in FakeBridge.records if r["completed_seq"] > since]
        last = max([r["completed_seq"] for r in recs], default=since)
        return recs, last

    def fetch_completed(self, since, limit=200, all=True):
        return [r for r in FakeBridge.records if r["completed_seq"] > since][:limit]

    def fetch_last(self, n):
        return FakeBridge.records[-int(n):]


class GateTest(unittest.TestCase):
    def setUp(self):
        analyze.BridgeClient = lambda *a, **k: FakeBridge()
        analyze.MIN_BATCH = 3
        analyze.MAX_DEFER_HOURS = 6
        analyze.CONTEXT_N = 40
        FakeBridge.head = 100
        FakeBridge.records = [_job(s) for s in range(91, 101)]  # seq 91..100
        for f in ("cursor", "pending"):
            Path(f"{_TMP}/{f}").unlink(missing_ok=True)

    def _decision(self):
        return analyze.assemble_window()[0]

    def test_cold_start_seeds_and_skips(self):
        self.assertEqual(self._decision(), "cold")
        self.assertEqual(Path(f"{_TMP}/cursor").read_text(), "100")

    def test_empty_when_cursor_at_head(self):
        Path(f"{_TMP}/cursor").write_text("100")
        self.assertEqual(self._decision(), "empty")

    def test_defer_small_fresh_batch(self):
        Path(f"{_TMP}/cursor").write_text("98")   # 2 new (99,100) < MIN_BATCH 3
        self.assertEqual(self._decision(), "defer")
        self.assertEqual(Path(f"{_TMP}/cursor").read_text(), "98")   # cursor NOT advanced
        self.assertTrue(Path(f"{_TMP}/pending").exists())            # marker set

    def test_analyze_when_batch_reaches_min(self):
        Path(f"{_TMP}/cursor").write_text("97")   # 3 new (98,99,100) == MIN_BATCH
        decision, report, since, last, ctx = analyze.assemble_window()
        self.assertEqual(decision, "analyze")
        self.assertEqual(len(report), 3)
        self.assertEqual(Path(f"{_TMP}/cursor").read_text(), "100")  # advanced
        self.assertFalse(Path(f"{_TMP}/pending").exists())           # cleared
        self.assertEqual(len(ctx), 10)                               # context window (last N)

    def test_force_by_time_past_max_defer(self):
        Path(f"{_TMP}/cursor").write_text("98")                       # 2 new < MIN_BATCH
        Path(f"{_TMP}/pending").write_text(repr(time.time() - 7 * 3600))  # waited 7h > 6h
        self.assertEqual(self._decision(), "analyze")


class MainFlowTest(unittest.TestCase):
    STUB = json.dumps({
        "health": "degraded", "summary": "test run",
        "findings": [{
            "id": "run-log-missing", "title": "Restore run_log writing",
            "severity": "medium", "category": "infra", "evidence": ["e"],
            "affected_jobs": ["j1"], "proposed_fix": "x", "files": ["A.kt"],
            "agent_prompt": "p",
        }],
    })

    def setUp(self):
        analyze.BridgeClient = lambda *a, **k: FakeBridge()
        analyze.call_model = lambda *a, **k: self.STUB
        analyze.MIN_BATCH = 2
        FakeBridge.head = 100
        FakeBridge.records = [_job(s) for s in range(91, 101)]
        for f in ("cursor", "pending", "history.jsonl", "findings_ledger.jsonl"):
            Path(f"{_TMP}/{f}").unlink(missing_ok=True)

    def _run(self, run_ts):
        Path(f"{_TMP}/cursor").write_text("97")     # 3 new >= MIN_BATCH -> analyze
        Path(f"{_TMP}/pending").unlink(missing_ok=True)
        analyze.RUN_TS = run_ts
        analyze.FINDINGS_DIR = Path(f"{_TMP}/findings/{run_ts}")
        rc = analyze.main()
        analysis = json.loads((analyze.FINDINGS_DIR / "analysis.json").read_text())
        return rc, analysis

    def test_full_run_writes_delta_ledger_history(self):
        rc, analysis = self._run("run1")
        self.assertEqual(rc, 0)
        self.assertEqual(analysis["health"], "degraded")
        self.assertIn("delta", analysis)
        self.assertEqual(len(analysis["delta"]["new"]), 1)          # finding is NEW
        self.assertEqual(analysis["context_jobs"], 10)
        self.assertEqual(analysis["cursor"], {"from": 97, "to": 100, "window_jobs": 3})
        self.assertTrue(Path(f"{_TMP}/findings_ledger.jsonl").exists())
        self.assertEqual(len(Path(f"{_TMP}/history.jsonl").read_text().splitlines()), 1)

    def test_repeat_finding_deduped_across_runs(self):
        self._run("run1")
        _, analysis2 = self._run("run2")
        # same canonical id -> UNCHANGED (suppressed), not NEW
        self.assertEqual(analysis2["delta"]["new"], [])
        self.assertEqual(analysis2["delta"]["unchanged"], 1)

    def test_non_dict_model_response_degrades(self):
        analyze.call_model = lambda *a, **k: "[1, 2, 3]"   # weak model returns a list
        rc, analysis = self._run("runbad")
        self.assertEqual(rc, 1)
        self.assertEqual(analysis["health"], "unknown")     # fallback, no crash

    def test_deterministic_findings_survive_model_outage(self):
        def boom(*a, **k):
            raise RuntimeError("model down")
        analyze.call_model = boom
        rc, analysis = self._run("outage")
        self.assertEqual(rc, 1)                              # model unavailable
        self.assertEqual(analysis["health"], "unknown")
        # run_log is empty in tests -> every job flags run_log_missing -> a rule finding fires
        # with no LLM involved.
        self.assertIn("run-log-missing", [f["id"] for f in analysis["findings"]])


if __name__ == "__main__":
    unittest.main()
