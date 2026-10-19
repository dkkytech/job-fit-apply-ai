"""Contract tests for the committed run-analyzer LaunchAgent schedules."""

import plistlib
import unittest
from pathlib import Path


PIPELINE_DIR = Path(__file__).resolve().parents[3]
AUTOFIX_PLIST = PIPELINE_DIR / "scripts" / "com.jd.run-analyzer-autofix.plist"


class AutofixLaunchdScheduleTest(unittest.TestCase):
    def setUp(self):
        with AUTOFIX_PLIST.open("rb") as fh:
            self.plist = plistlib.load(fh)

    def test_runs_at_0700_and_1900(self):
        intervals = self.plist["StartCalendarInterval"]
        actual = sorted((entry["Hour"], entry["Minute"]) for entry in intervals)
        self.assertEqual(actual, [(7, 0), (19, 0)])

    def test_deployed_agent_is_armed(self):
        self.assertEqual(self.plist["EnvironmentVariables"]["RUN_ANALYZER_AUTOFIX"], "1")


if __name__ == "__main__":
    unittest.main()
