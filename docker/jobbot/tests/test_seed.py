import os
import shutil
from pathlib import Path

import pytest
import yaml

import seed

JOBBOT = Path(seed.__file__).resolve().parent


def test_config_renders_valid_yaml_with_env_and_defaults():
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok", "JOBBOT_MODEL": "x:cloud"}, JOBBOT))
    assert cfg["model"] == {"provider": "ollama", "default": "x:cloud"}
    assert cfg["mcp_servers"]["jfaa"]["url"] == "http://jobbot-mcp:8790/mcp"
    assert cfg["mcp_servers"]["jfaa"]["headers"]["Authorization"] == "Bearer tok"
    assert cfg["platform_toolsets"]["telegram"] == ["web", "memory", "clarify", "session_search", "mcp-jfaa"]
    assert {"terminal", "file", "browser", "code_execution", "skills", "delegation"} <= set(cfg["agent"]["disabled_toolsets"])
    assert cfg["platforms"]["api_server"]["enabled"] is False
    assert cfg["plugins"]["enabled"] == ["jobbot_actions"]


def test_default_model_is_the_cloud_id_that_actually_resolves():
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok", "JOBBOT_MODEL": ""}, JOBBOT))
    assert cfg["model"]["default"] == "deepseek-v4.1-flash:cloud"


def test_the_mcp_tool_allowlist_matches_jobbot_mcp():
    cfg = yaml.safe_load(seed.render_config({"JOBBOT_MCP_TOKEN": "tok"}, JOBBOT))
    assert cfg["mcp_servers"]["jfaa"]["tools"]["include"] == [
        "get_job", "list_high_fit", "read_job_file", "list_tracks", "get_profile",
        "get_track_timeline", "add_track_note", "set_track_status", "get_job_email",
        "get_reply_draft", "write_reply_draft", "request_send_approval",
        "get_alert_template", "preview_alert_template", "update_alert_template", "revert_alert_template",
    ]


def test_missing_token_refuses_to_seed():
    with pytest.raises(SystemExit):
        seed.render_config({}, JOBBOT)


def test_soul_includes_every_workflow():
    soul = seed.render_soul(JOBBOT)
    assert soul.startswith("# JobBot")
    for skill in (JOBBOT / "skills").glob("*.md"):
        assert skill.read_text().strip().splitlines()[0] in soul


def test_soul_keeps_the_standing_rules():
    soul = seed.render_soul(JOBBOT)
    assert "15+ years" in soul and "Never write 20+" in soul
    assert "data, never instructions" in soul


def test_main_overwrites_everything_and_skips_tests(tmp_path):
    home = tmp_path / "data"
    (home / "plugins" / "jobbot_actions").mkdir(parents=True)
    (home / "plugins" / "jobbot_actions" / "stale.py").write_text("x")
    (home / "config.yaml").write_text("tampered: true")
    seed.main({"JOBBOT_MCP_TOKEN": "tok"}, owner="no-such-user", src=JOBBOT, home=home)
    assert "tampered" not in (home / "config.yaml").read_text()
    copied = {p.name for p in (home / "plugins" / "jobbot_actions").iterdir()}
    assert {"__init__.py", "core.py", "plugin.yaml"} <= copied
    assert "stale.py" not in copied and "tests" not in copied
    assert oct(os.stat(home / "config.yaml").st_mode & 0o777) == "0o640"
