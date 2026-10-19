"""Unit tests for health_summary.py — run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import io
import json
import os
import subprocess
import sys
import unittest
from contextlib import redirect_stdout
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import health_summary  # noqa: E402

HOUR = health_summary.HOUR_MS
DAY = 24 * HOUR
NOW = 1_800_000_000_000
TOOLS = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(TOOLS, "..", "..", ".."))


def emp(company, listed=10, queued=0, error=None):
    return {"company": company, "ats": "greenhouse", "listed": listed, "queued": queued, "error": error}


def health(ran_ago_ms=HOUR, employers=(), failing=(), empty=()):
    return {"ran_at": NOW - ran_ago_ms, "employers": list(employers), "failing": list(failing), "empty": list(empty)}


class SummarizeTest(unittest.TestCase):
    def test_a_recent_healthy_run_is_one_ok_line_with_its_counts(self):
        h = health(ran_ago_ms=3 * HOUR, employers=[emp("Acme", queued=2), emp("Initech", queued=1), emp("Hooli")])
        self.assertEqual(
            [("ok", "watchlist last run 3h ago: 3 feeds polled, 3 new postings queued")],
            health_summary.summarize(h, NOW, DAY),
        )

    def test_one_late_pass_is_not_a_stuck_gate(self):
        (level, _), = health_summary.summarize(health(ran_ago_ms=2 * DAY), NOW, DAY)
        self.assertEqual("ok", level, "exactly two intervals is still on the edge of normal")

    def test_two_missed_passes_warn_with_where_to_look(self):
        (level, msg), = health_summary.summarize(health(ran_ago_ms=2 * DAY + HOUR), NOW, DAY, "test-watchlist")
        self.assertEqual("warn", level)
        self.assertIn("49h ago (expected every 24h)", msg)
        self.assertIn("docker logs test-watchlist", msg)

    def test_the_staleness_threshold_follows_the_interval(self):
        twice_daily = 12 * HOUR
        (level, _), = health_summary.summarize(health(ran_ago_ms=25 * HOUR), NOW, twice_daily)
        self.assertEqual("warn", level)

    def test_failing_and_empty_feeds_are_listed_from_the_report_itself(self):
        # The employer rows would say otherwise; RunHealth's own lists (which honor may_be_empty) win.
        h = health(employers=[emp("Acme"), emp("Initech", listed=0), emp("Hooli", listed=0)],
                   failing=["Acme"], empty=["Initech"])
        findings = health_summary.summarize(h, NOW, DAY)
        self.assertEqual(["ok", "warn", "warn"], [lvl for lvl, _ in findings])
        self.assertIn("feeds failing (1/3: Acme)", findings[1][1])
        self.assertIn("listing no jobs (1/3: Initech)", findings[2][1])
        self.assertNotIn("Hooli", findings[2][1])

    def test_long_lists_are_truncated(self):
        many = ["Co%d" % i for i in range(12)]
        _, (_, msg) = health_summary.summarize(health(employers=[emp(c) for c in many], failing=many), NOW, DAY)
        self.assertIn("12/12: Co0, Co1, Co2, Co3, Co4, Co5, Co6, Co7 …", msg)
        self.assertNotIn("Co8", msg)

    def test_a_report_without_a_run_time_still_reports_feeds(self):
        h = {"employers": [emp("Acme")], "failing": ["Acme"], "empty": []}
        self.assertEqual(["warn"], [lvl for lvl, _ in health_summary.summarize(h, NOW, DAY)])


class MainTest(unittest.TestCase):
    def run_main(self, stdin, *args):
        out = io.StringIO()
        with mock.patch("sys.stdin", io.StringIO(stdin)), mock.patch("time.time", return_value=NOW / 1000), redirect_stdout(out):
            code = health_summary.main(list(args))
        return code, out.getvalue()

    def test_prints_tab_separated_level_and_message(self):
        code, out = self.run_main(json.dumps(health(ran_ago_ms=30 * HOUR, failing=["Acme"], employers=[emp("Acme")])),
                                  "--interval-ms", str(12 * HOUR), "--container", "x-watchlist")
        self.assertEqual(0, code)
        lines = out.splitlines()
        self.assertEqual(2, len(lines))
        self.assertTrue(lines[0].startswith("warn\twatchlist last full run 30h ago (expected every 12h)  →  docker logs x-watchlist"))
        self.assertTrue(lines[1].startswith("warn\twatchlist feeds failing"))

    def test_no_or_unreadable_report_prints_nothing(self):
        for stdin in ("", "{half", "[]", "null"):
            self.assertEqual((0, ""), self.run_main(stdin), stdin)


class DoctorWiringTest(unittest.TestCase):
    """doctor.sh runs this script by path from the repo root and maps each level to ok/warn."""

    def test_doctor_calls_this_script_and_passes_the_interval(self):
        with open(os.path.join(REPO, "scripts", "doctor.sh"), encoding="utf-8") as f:
            doctor = f.read()
        self.assertIn("services/job-fit-apply-ai-watchlist/tools/health_summary.py", doctor)
        self.assertIn('--interval-ms "${WATCHLIST_INTERVAL_MS:-86400000}"', doctor)

    def test_the_doctor_loop_turns_lines_into_ok_and_warn_calls(self):
        # Run the doctor's own loop (extracted between its markers) against canned script output.
        with open(os.path.join(REPO, "scripts", "doctor.sh"), encoding="utf-8") as f:
            doctor = f.read()
        start = doctor.index("# >>> watchlist-health")
        end = doctor.index("# <<< watchlist-health")
        loop = doctor[start:end]
        script = (
            'ok(){ echo "OK $1"; }; warn(){ echo "WARN $1"; WARNS=$((WARNS+1)); }; WARNS=0; P=jobfit\n'
            'docker(){ printf %s \'{"ran_at": 1, "employers": [{"company": "Acme"}, {"company": "Initech"}], "failing": ["Acme"], "empty": ["Initech"]}\'; }\n'
            + loop.replace("services/job-fit-apply-ai-watchlist/tools/health_summary.py", os.path.join(TOOLS, "health_summary.py"))
            + '\necho "WARNS=$WARNS"\n'
        )
        out = subprocess.run(["bash", "-c", script], capture_output=True, text=True, check=True).stdout.splitlines()
        self.assertEqual("WARNS=3", out[-1], out)
        self.assertTrue(any(l.startswith("WARN watchlist last full run") for l in out), out)
        self.assertTrue(any(l.startswith("WARN watchlist feeds failing (1/2: Acme)") for l in out), out)


if __name__ == "__main__":
    unittest.main()
