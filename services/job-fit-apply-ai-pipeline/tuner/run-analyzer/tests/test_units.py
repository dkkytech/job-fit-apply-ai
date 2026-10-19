"""Unit tests for the analyzer's pure logic (no network, no LLM, no bridge).

Run from the run-analyzer dir:  python3 -m unittest discover tests
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

# Make the `analyzer` package importable regardless of cwd.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from analyzer import history, metrics  # noqa: E402
from analyzer.audit import (  # noqa: E402
    _findings_from_verdicts,
    grounding_check,
    parse_evidence,
    select_candidates,
)
from analyzer.findings import fingerprint, slugify  # noqa: E402
from analyzer.sources import join_window  # noqa: E402


class TestMetrics(unittest.TestCase):
    def _recs(self):
        return [
            {"action": "TAILOR", "score": 80, "durationMs": 1000},
            {"action": "SKIP", "score": 0, "durationMs": 2000},
            {"action": "SKIP", "score": 0, "isDuplicate": True, "durationMs": 500},
            {"action": "SKIP", "score": 40, "isDigest": True, "jdTextLen": 100, "durationMs": 300},
            {"action": "TAILOR", "score": 55, "error": "request timed out", "durationMs": 9000},
            {"action": "SKIP", "score": 0, "error": "model runner has unexpectedly stopped"},
            {"action": "SKIP", "score": 10, "error": "bot-blocked (captcha)"},
        ]

    def test_counts_and_rates(self):
        m = metrics.compute_metrics(self._recs())
        self.assertEqual(m["jobs"], 7)
        self.assertEqual(m["tailored"], 2)
        self.assertEqual(m["skipped"], 5)
        self.assertEqual(m["errors"], 3)
        # zero_score excludes the duplicate (job 3)
        self.assertEqual(m["zero_score"], 2)
        self.assertEqual(m["thin_digest"], 1)     # digest + jdTextLen<400
        self.assertEqual(m["timeouts"], 1)
        self.assertEqual(m["ollama_oom"], 1)
        self.assertEqual(m["scrape_blocked"], 1)
        self.assertAlmostEqual(m["error_rate"], round(3 / 7, 3))
        self.assertAlmostEqual(m["zero_score_rate"], round(2 / 7, 3))

    def test_empty_window_safe(self):
        m = metrics.compute_metrics([])
        self.assertEqual(m["jobs"], 0)
        self.assertEqual(m["avg_score"], 0.0)
        self.assertEqual(m["error_rate"], 0.0)  # no divide-by-zero


class TestHistory(unittest.TestCase):
    def test_append_and_median_baseline(self):
        d = Path(tempfile.mkdtemp()) / "hist.jsonl"
        for i, er in enumerate([0.0, 0.1, 0.2]):
            history.append(d, f"r{i}", i, i + 1, 5, {"error_rate": er, "avg_score": 50})
        self.assertEqual(len(d.read_text().splitlines()), 3)
        base = history.baseline(d, window=10)
        self.assertEqual(base["runs"], 3)
        self.assertAlmostEqual(base["median"]["error_rate"], 0.1)   # median of 0,.1,.2
        self.assertAlmostEqual(base["last"]["error_rate"], 0.2)

    def test_baseline_empty_when_no_history(self):
        d = Path(tempfile.mkdtemp()) / "none.jsonl"
        self.assertEqual(history.baseline(d), {})


class TestWeightedMedian(unittest.TestCase):
    def test_matches_plain_median_when_weights_are_equal(self):
        for vals in ([0.0, 0.1, 0.2], [0.0, 0.1, 0.2, 0.3], [0.4], [1.0, 0.0]):
            self.assertEqual(history.weighted_median([(v, 7) for v in vals]),
                             history._median(vals), msg=str(vals))

    def test_no_usable_mass(self):
        self.assertIsNone(history.weighted_median([]))
        self.assertIsNone(history.weighted_median([(0.5, 0)]))     # zero-weight run
        self.assertIsNone(history.weighted_median([(None, 5)]))

    def test_heavy_window_outvotes_light_one(self):
        # One 1-job window at 1.0 must not drag the median up against a 20-job window at 0.0.
        self.assertEqual(history.weighted_median([(1.0, 1), (0.0, 20)]), 0.0)
        # ...and symmetrically, mass on the high side wins.
        self.assertEqual(history.weighted_median([(1.0, 20), (0.0, 1)]), 1.0)


class TestBaselineWeighting(unittest.TestCase):
    def test_many_tiny_windows_cannot_outvote_the_job_mass(self):
        """Regression: three 1-job windows must not outvote two full windows.

        Removing the cadence gate made tiny windows common, and a 1-job window's rate is
        quantized to 0.0/1.0. Counting runs (the old behaviour) lets 3 jobs decide the trend
        against 40; counting jobs does not. The plain median over these same values is 1.0,
        so this case fails under equal weighting.
        """
        d = Path(tempfile.mkdtemp()) / "hist.jsonl"
        for i in range(2):                                      # 2 healthy 20-job windows
            history.append(d, f"big{i}", i, i + 1, 20, {"error_rate": 0.0})
        for i in range(3):                                      # 3 one-job windows, all errored
            history.append(d, f"tiny{i}", i, i + 1, 1, {"error_rate": 1.0})

        self.assertEqual(history._median([0.0, 0.0, 1.0, 1.0, 1.0]), 1.0)   # equal-weight
        base = history.baseline(d, window=10)
        self.assertEqual(base["median"]["error_rate"], 0.0)                 # job-weighted
        self.assertEqual(base["jobs"], 43)
        self.assertEqual(base["runs"], 5)

    def test_rows_without_window_jobs_still_score(self):
        """History written before window_jobs existed must not vanish from the baseline."""
        d = Path(tempfile.mkdtemp()) / "legacy.jsonl"
        d.write_text("\n".join(
            json.dumps({"run_ts": f"r{i}", "metrics": {"error_rate": r}})
            for i, r in enumerate([0.0, 0.2, 0.4])) + "\n")
        base = history.baseline(d, window=10)
        self.assertEqual(base["median"]["error_rate"], 0.2)
        self.assertEqual(base["jobs"], 3)                  # each legacy row counts as one job


class TestFingerprint(unittest.TestCase):
    def test_stable_across_files_and_jobs(self):
        a = {"id": "run-log-missing", "category": "infra", "files": ["A.kt"], "affected_jobs": ["j1"]}
        b = {"id": "run-log-missing", "category": "infra", "files": ["B.kt", "C.kt"], "affected_jobs": ["j2", "j3"]}
        self.assertEqual(fingerprint(a), fingerprint(b))  # files/jobs excluded from identity

    def test_differs_by_id_and_category(self):
        base = {"id": "x", "category": "scoring"}
        self.assertNotEqual(fingerprint(base), fingerprint({"id": "y", "category": "scoring"}))
        self.assertNotEqual(fingerprint(base), fingerprint({"id": "x", "category": "scraping"}))

    def test_slugify(self):
        self.assertEqual(slugify("All Jobs Scored 0!"), "all-jobs-scored-0")
        self.assertEqual(slugify(""), "finding")


class TestSourcesJoin(unittest.TestCase):
    def test_bridge_spine_with_run_log_enrichment(self):
        completed = [{"job_id": "j1", "completed_seq": 5, "status": "done",
                      "company": "Acme", "fit_score": 70, "pipeline_action": "TAILOR",
                      "job_url": "http://x"}]
        run_log = {"j1": {"jobId": "j1", "jdTextLen": 1800, "durationMs": 1234,
                          "isDigest": False, "board": "linkedin.com", "outputPath": "/out/j1"}}
        rec = join_window(completed, run_log)[0]
        self.assertEqual(rec["jobId"], "j1")
        self.assertEqual(rec["score"], 70)          # from bridge fit_score
        self.assertEqual(rec["action"], "TAILOR")
        self.assertEqual(rec["jdTextLen"], 1800)    # enrichment from run_log
        self.assertEqual(rec["board"], "linkedin.com")
        self.assertFalse(rec["run_log_missing"])

    def test_missing_run_log_flagged(self):
        rec = join_window([{"job_id": "j9", "completed_seq": 1, "status": "done"}], {})[0]
        self.assertTrue(rec["run_log_missing"])
        self.assertEqual(rec["jdTextLen"], 0)


class TestAuditPure(unittest.TestCase):
    SCORE_FIT = (
        "Fit Score: 0\nPipeline Action: skip\n\nReasoning:\nStrong infra.\n\n"
        "Strengths:\n"
        '- Deep Kubernetes [\"operate Kubernetes in production for 5+ years\"]\n'
        '- Python [\"Python is required\"]\n\n'
        "Gaps:\n"
        '- No Rust [\"Rust preferred\"]\n'
    )
    JD = "We operate Kubernetes in production for 5+ years. Rust preferred. This is a Java shop."

    def test_parse_and_grounding(self):
        items = parse_evidence(self.SCORE_FIT)
        self.assertEqual(len(items), 3)
        g = grounding_check(items, self.JD)
        # k8s + Rust quotes appear in JD; "Python is required" does not.
        self.assertEqual(g["grounded_strengths"], 1)
        self.assertEqual(g["ungrounded_claims"], 1)
        self.assertEqual(g["total_claims"], 3)

    def test_select_candidates_triage_and_cap(self):
        recs = [{"jobId": f"z{i}", "score": 0, "action": "SKIP"} for i in range(20)]
        picked = select_candidates(recs)
        self.assertLessEqual(len(picked), 8)         # AUDIT_MAX cap
        self.assertTrue(all(r["score"] == 0 for r in picked))

    def test_findings_from_verdicts_aggregation(self):
        verdicts = [
            {"job_id": "j1", "verdict": "too_low", "cause": "scoring_bug", "confidence": "high",
             "assigned_score": 0, "grounded_strengths": 3, "jd_chars": 2000, "reason": "rich JD 0"},
            {"job_id": "j2", "verdict": "justified", "confidence": "high"},
        ]
        fs = _findings_from_verdicts(verdicts)
        self.assertTrue(any(f["id"] == "scoring-zeroed-real-fits" for f in fs))
        # justified verdict must not produce a finding
        self.assertNotIn("j2", [j for f in fs for j in f["affected_jobs"]])


if __name__ == "__main__":
    unittest.main()
