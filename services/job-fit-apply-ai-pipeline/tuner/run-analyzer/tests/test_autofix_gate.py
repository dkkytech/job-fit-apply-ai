"""Regression test for the autofix acceptance-gate path.

The gate used to default to a bare "./gradlew test" run from the repo root — but gradlew lives in
the pipeline service, not the root, so the gate could never find it and every fix was reverted.
The default must cd into the pipeline service (which owns gradlew) before invoking gradlew, while an
explicit RUN_ANALYZER_AUTOFIX_GATE override still wins.
"""

import importlib
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


class AutofixGateDefaultTest(unittest.TestCase):
    def _reload_autofix(self):
        # reload() re-executes the module body against the current environment, so the module-level
        # GRADLE_TEST constant is recomputed from RUN_ANALYZER_AUTOFIX_GATE as it stands in this test.
        from analyzer import autofix

        return importlib.reload(autofix)

    def test_default_gate_cds_into_pipeline_before_gradlew(self):
        saved = os.environ.pop("RUN_ANALYZER_AUTOFIX_GATE", None)
        try:
            gate = self._reload_autofix().GRADLE_TEST
            self.assertIn("cd services/job-fit-apply-ai-pipeline", gate)
            self.assertIn("./gradlew test", gate)
        finally:
            if saved is not None:
                os.environ["RUN_ANALYZER_AUTOFIX_GATE"] = saved

    def test_env_override_still_wins(self):
        saved = os.environ.get("RUN_ANALYZER_AUTOFIX_GATE")
        os.environ["RUN_ANALYZER_AUTOFIX_GATE"] = "custom gate cmd"
        try:
            self.assertEqual(self._reload_autofix().GRADLE_TEST, "custom gate cmd")
        finally:
            if saved is None:
                os.environ.pop("RUN_ANALYZER_AUTOFIX_GATE", None)
            else:
                os.environ["RUN_ANALYZER_AUTOFIX_GATE"] = saved


if __name__ == "__main__":
    unittest.main()
