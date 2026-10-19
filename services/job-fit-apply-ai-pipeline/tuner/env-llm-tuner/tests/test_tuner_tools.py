"""Offline tests for tuner_tools.py — no network, no oMLX. The leaderboard fetch is stubbed."""
import contextlib
import io
import os
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))
import tuner_tools as tt  # noqa: E402


MODELS = [
    {"model_id": "a", "name": "DeepSeek V4.1 Flash", "index_agents": 61.2, "index_tool_calling": 70.0,
     "tau2_retail_score": 0.71, "bfcl_v4_score": -1, "input_price": 0.1, "output_price": 0.4,
     "context": 128000, "latency": 0.8, "throughput": 90},
    {"model_id": "b", "name": "Big Agent Pro", "index_agents": 75.5, "index_tool_calling": None,
     "toolathlon_score": 0.4, "input_price": 2, "output_price": 8, "context": 256000},
    {"model_id": "c", "name": "No Agent Index", "index_agents": None, "mmlu_score": 0.9},
]


def run(fn, *args):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = fn(*args)
    return code, out.getvalue(), err.getvalue()


class AgentLeaderboardTest(unittest.TestCase):
    def test_jobbot_model_is_an_evaluated_variable(self):
        self.assertIn("JOBBOT_MODEL", tt.VARS)

    def test_agent_score_pattern_matches_agent_benchmarks_only(self):
        for k in ("tau2_retail_score", "bfcl_v4_score", "toolathlon_score", "mcp_atlas_score", "terminal_bench_2_score"):
            self.assertTrue(tt.AGENT_SCORE.match(k), k)
        for k in ("mmlu_score", "index_agents", "tau2_retail"):
            self.assertFalse(tt.AGENT_SCORE.match(k), k)

    def test_unrated_values_are_not_numbers(self):
        self.assertIsNone(tt._num(None))
        self.assertIsNone(tt._num(-1))
        self.assertIsNone(tt._num(True))
        self.assertEqual(0.5, tt._num(0.5))

    def test_agent_view_ranks_by_agent_index_and_prints_scores(self):
        with mock.patch.object(tt, "_fetch_leaderboard_models", return_value=MODELS):
            code, out, _ = run(tt.cmd_leaderboard, ["deepseek", "agent pro"], True)
        self.assertEqual(0, code)
        lines = out.splitlines()
        self.assertIn("Big Agent Pro", lines[0], "highest agent index first")
        self.assertIn("#  1", lines[0])
        self.assertIn("tau2_retail=0.71", lines[1])
        self.assertNotIn("bfcl_v4=", lines[1], "a -1 score means unrated and must not print")

    def test_agent_view_reports_no_match(self):
        with mock.patch.object(tt, "_fetch_leaderboard_models", return_value=MODELS):
            code, _, err = run(tt.cmd_leaderboard, ["nonexistent"], True)
        self.assertEqual(1, code)
        self.assertIn("no match", err)


class EnvFilesTest(unittest.TestCase):
    def test_parse_env_reads_jobbot_model_and_skips_comments(self):
        with tempfile.NamedTemporaryFile("w", suffix=".env", delete=False) as f:
            f.write("# JOBBOT_MODEL — agent\nJOBBOT_MODEL=deepseek-v4.1-flash:cloud\n#SCAN_MODEL=x\n")
        try:
            self.assertEqual({"JOBBOT_MODEL": "deepseek-v4.1-flash:cloud"}, tt.parse_env(f.name))
        finally:
            os.unlink(f.name)


if __name__ == "__main__":
    unittest.main()
