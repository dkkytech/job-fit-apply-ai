"""Unit tests for the outcome loop (Phase D): merge reconciliation + metric recovery."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from analyzer import findings_ledger, history, outcomes  # noqa: E402

# History rows: hourly runs; a fix merges between r3 and r4.
RUNS = ["20260101_000000", "20260101_010000", "20260101_020000",
        "20260101_030000", "20260101_040000", "20260101_050000"]
MERGED_TS = "2026-01-01T02:30:00Z"


def _write_history(path, rates, window_jobs=10):
    """Full-size windows by default, so each side of the merge clears MIN_RESOLVE_JOBS."""
    for ts, rate in zip(RUNS, rates):
        history.append(path, ts, 0, 1, window_jobs,
                       {"zero_score_rate": rate, "error_rate": 0.0})


def _write_autofix(path, fp, status, merged_ts=None):
    rec = {"fingerprint": fp, "status": status, "pr_url": "http://pr/1"}
    if merged_ts:
        rec["merged_ts"] = merged_ts
    Path(path).write_text(json.dumps(rec) + "\n")


class TestParsing(unittest.TestCase):
    def test_run_ts_and_iso_epoch_order(self):
        e_run = outcomes._run_ts_epoch("20260101_030000")
        e_iso = outcomes._iso_epoch(MERGED_TS)  # 02:30
        self.assertIsNotNone(e_run)
        self.assertIsNotNone(e_iso)
        self.assertLess(e_iso, e_run)                 # merge (02:30) before r4 (03:00)

    def test_bad_timestamps_return_none(self):
        self.assertIsNone(outcomes._run_ts_epoch("not-a-ts"))
        self.assertIsNone(outcomes._iso_epoch(None))

    def test_mass_window_needs_both_runs_and_jobs(self):
        rows = [(0.1, 4), (0.2, 4), (0.3, 4)]              # 3 runs but only 12 jobs
        self.assertIsNone(outcomes._mass_window(rows, k=3, min_jobs=30))
        self.assertEqual(outcomes._mass_window(rows, k=3, min_jobs=12), rows)
        # Stops as soon as both thresholds are met, rather than consuming every run.
        big = [(0.1, 30), (0.2, 30), (0.3, 30), (0.4, 30)]
        self.assertEqual(len(outcomes._mass_window(big, k=3, min_jobs=30)), 3)

    def test_mass_window_from_end_takes_runs_nearest_the_merge(self):
        rows = [(0.9, 30), (0.1, 30), (0.2, 30)]
        picked = outcomes._mass_window(rows, k=1, min_jobs=30, from_end=True)
        self.assertEqual(picked, [(0.2, 30)])              # newest, not oldest


class TestCheckOutcomes(unittest.TestCase):
    def setUp(self):
        d = Path(tempfile.mkdtemp())
        self.hist = d / "history.jsonl"
        self.autofix = d / "autofix.jsonl"
        self.fl = {"fp1": {"fingerprint": "fp1", "category": "scoring", "status": "active"}}

    def test_resolved_when_metric_halves(self):
        _write_history(self.hist, [0.4, 0.4, 0.4, 0.1, 0.1, 0.1])   # halved after merge
        _write_autofix(self.autofix, "fp1", "pr_merged", MERGED_TS)
        oc = outcomes.check_outcomes(self.fl, self.autofix, self.hist, k=3)
        self.assertEqual(oc["resolved"], ["fp1"])
        self.assertEqual(oc["regressed"], [])

    def test_regressed_when_metric_unchanged(self):
        _write_history(self.hist, [0.4, 0.4, 0.4, 0.4, 0.4, 0.4])   # no improvement
        _write_autofix(self.autofix, "fp1", "pr_merged", MERGED_TS)
        oc = outcomes.check_outcomes(self.fl, self.autofix, self.hist, k=3)
        self.assertEqual(oc["regressed"], ["fp1"])
        self.assertEqual(oc["resolved"], [])

    def test_skip_when_not_enough_post_merge_runs(self):
        # only r4 exists after the merge -> < k, decide later
        for ts, rate in zip(RUNS[:4], [0.4, 0.4, 0.4, 0.1]):
            history.append(self.hist, ts, 0, 1, 5, {"zero_score_rate": rate})
        _write_autofix(self.autofix, "fp1", "pr_merged", MERGED_TS)
        oc = outcomes.check_outcomes(self.fl, self.autofix, self.hist, k=3)
        self.assertEqual(oc, {"resolved": [], "regressed": []})

    def test_open_pr_not_yet_evaluated(self):
        _write_history(self.hist, [0.4, 0.4, 0.4, 0.1, 0.1, 0.1])
        _write_autofix(self.autofix, "fp1", "pr_open")             # not merged
        oc = outcomes.check_outcomes(self.fl, self.autofix, self.hist, k=3)
        self.assertEqual(oc, {"resolved": [], "regressed": []})

    def test_already_resolved_skipped(self):
        _write_history(self.hist, [0.4, 0.4, 0.4, 0.1, 0.1, 0.1])
        _write_autofix(self.autofix, "fp1", "pr_merged", MERGED_TS)
        fl = {"fp1": {"fingerprint": "fp1", "category": "scoring", "status": "resolved"}}
        self.assertEqual(outcomes.check_outcomes(fl, self.autofix, self.hist, k=3)["resolved"], [])

    def test_tiny_post_merge_windows_do_not_auto_resolve(self):
        """Regression: three one-job windows must not retire a finding on 3 jobs of evidence.

        Removing the accumulate-until-N cadence gate made every non-empty cursor delta its
        own run, so `k` post-merge *runs* no longer implies a meaningful number of *jobs*.
        A one-job window's rate is quantized to 0.0/1.0, so three lucky jobs would otherwise
        clear IMPROVE_FACTOR outright.
        """
        for ts, rate in zip(RUNS[:3], [0.4, 0.4, 0.4]):        # 3 x 10 jobs @ 0.4 before
            history.append(self.hist, ts, 0, 1, 10, {"zero_score_rate": rate})
        for ts in RUNS[3:]:                                     # 3 x 1 job @ 0.0 after
            history.append(self.hist, ts, 0, 1, 1, {"zero_score_rate": 0.0})
        _write_autofix(self.autofix, "fp1", "pr_merged", MERGED_TS)

        oc = outcomes.check_outcomes(self.fl, self.autofix, self.hist, k=3)
        self.assertEqual(oc, {"resolved": [], "regressed": []})

    def test_resolves_once_post_merge_job_mass_arrives(self):
        """The same finding resolves normally once real windows accumulate."""
        for ts, rate in zip(RUNS[:3], [0.4, 0.4, 0.4]):
            history.append(self.hist, ts, 0, 1, 10, {"zero_score_rate": rate})
        for ts in RUNS[3:]:
            history.append(self.hist, ts, 0, 1, 10, {"zero_score_rate": 0.1})
        _write_autofix(self.autofix, "fp1", "pr_merged", MERGED_TS)

        oc = outcomes.check_outcomes(self.fl, self.autofix, self.hist, k=3)
        self.assertEqual(oc["resolved"], ["fp1"])

    def test_thin_pre_merge_history_also_defers(self):
        """A credible before-side is required too, not just an after-side."""
        for ts, rate in zip(RUNS[:3], [0.4, 0.4, 0.4]):        # only 3 jobs before the merge
            history.append(self.hist, ts, 0, 1, 1, {"zero_score_rate": rate})
        for ts in RUNS[3:]:
            history.append(self.hist, ts, 0, 1, 10, {"zero_score_rate": 0.1})
        _write_autofix(self.autofix, "fp1", "pr_merged", MERGED_TS)

        oc = outcomes.check_outcomes(self.fl, self.autofix, self.hist, k=3)
        self.assertEqual(oc, {"resolved": [], "regressed": []})

    def test_min_jobs_is_tunable(self):
        """Lowering the floor restores the old run-count-only behaviour for small setups."""
        for ts, rate in zip(RUNS[:3], [0.4, 0.4, 0.4]):
            history.append(self.hist, ts, 0, 1, 1, {"zero_score_rate": rate})
        for ts in RUNS[3:]:
            history.append(self.hist, ts, 0, 1, 1, {"zero_score_rate": 0.0})
        _write_autofix(self.autofix, "fp1", "pr_merged", MERGED_TS)

        self.assertEqual(
            outcomes.check_outcomes(self.fl, self.autofix, self.hist, k=3, min_jobs=3)["resolved"],
            ["fp1"])

    def test_scraping_finding_is_not_resolved_from_zero_hard_error_rate(self):
        # Scrape failures may deliberately fall back to email snippets, leaving hard errors at
        # zero even while the scraper is broken. This category needs a direct scrape-health metric.
        for ts in RUNS:
            history.append(self.hist, ts, 0, 1, 5, {"error_rate": 0.0})
        _write_autofix(self.autofix, "fp1", "pr_merged", MERGED_TS)
        fl = {"fp1": {"fingerprint": "fp1", "category": "scraping", "status": "active"}}

        self.assertEqual(
            outcomes.check_outcomes(fl, self.autofix, self.hist, k=3),
            {"resolved": [], "regressed": []},
        )


class TestReconcileMerges(unittest.TestCase):
    def setUp(self):
        self.autofix = Path(tempfile.mkdtemp()) / "autofix.jsonl"
        _write_autofix(self.autofix, "fp1", "pr_open")

    def test_no_op_without_gh(self):
        with mock.patch("shutil.which", return_value=None):
            outcomes.reconcile_merges(self.autofix)
        self.assertEqual(len(self.autofix.read_text().splitlines()), 1)  # unchanged

    def test_flips_merged_pr(self):
        proc = mock.Mock(returncode=0, stdout=json.dumps({"state": "MERGED", "mergedAt": MERGED_TS}))
        with mock.patch("shutil.which", return_value="/usr/bin/gh"), \
             mock.patch("analyzer.outcomes.subprocess.run", return_value=proc):
            outcomes.reconcile_merges(self.autofix)
        lines = [json.loads(x) for x in self.autofix.read_text().splitlines()]
        self.assertTrue(any(e.get("status") == "pr_merged" and e.get("merged_ts") == MERGED_TS
                            for e in lines))


class TestMarkResolved(unittest.TestCase):
    def test_resolved_record_supersedes_active(self):
        led = Path(tempfile.mkdtemp()) / "fl.jsonl"
        f = {"id": "x", "title": "X", "severity": "high", "category": "scoring",
             "affected_jobs": ["j"], "files": ["A.kt"]}
        findings_ledger.classify([f], led, "r1")
        fp = findings_ledger.fingerprint(f)
        findings_ledger.mark_resolved(led, [fp], "r2")
        self.assertEqual(findings_ledger.load(led)[fp]["status"], "resolved")


if __name__ == "__main__":
    unittest.main()
