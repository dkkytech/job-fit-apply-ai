#!/usr/bin/env python3
"""Validate production and E2E Compose data-root mount contracts."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "docker-compose.yml"
E2E = ROOT / "docker-compose.e2e.yml"


def compose_config(*files: Path, environment: dict[str, str]) -> dict:
    env = os.environ.copy()
    for name in (
        "COMPOSE_ENV_FILES",
        "COMPOSE_PROFILES",
        "JFAA_DATA_ROOT",
        "JD_PIPELINE_OUTPUT_HOST",
        "JD_PIPELINE_STATE_HOST",
        "JD_WATCHLIST_STATE_HOST",
        "PIPELINE_ENV_FILE",
        "CONTAINER_PREFIX",
        "E2E_STATE_DIR",
        # The browser's credentials: a developer's shell must not leak into the assertions.
        "JOBBOT_BROWSER_PASSWORD", "JOBBOT_BROWSER_USER", "APPLY_BROWSER_PASSWORD", "APPLY_BROWSER_USER",
    ):
        env.pop(name, None)
    env.update(environment)

    with tempfile.NamedTemporaryFile(mode="w", suffix=".env") as env_file:
        env_file.flush()
        command = ["docker", "compose", "--env-file", env_file.name]
        for path in files:
            command.extend(("-f", str(path)))
        command.extend(("config", "--format", "json"))

        result = subprocess.run(
            command,
            cwd=ROOT,
            env=env,
            text=True,
            capture_output=True,
        )
    if result.returncode != 0:
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        raise AssertionError(f"Compose config failed with exit {result.returncode}")
    return json.loads(result.stdout)


def mounts(config: dict, service: str) -> dict[str, dict]:
    return {
        volume["target"]: volume
        for volume in config["services"][service].get("volumes", [])
    }


def assert_mount(
    config: dict,
    service: str,
    target: str,
    source: Path,
    *,
    read_only: bool,
) -> None:
    volume = mounts(config, service)[target]
    actual_source = Path(volume["source"])
    assert actual_source == source, (
        f"{service}:{target} source was {actual_source}, expected {source}"
    )
    assert bool(volume.get("read_only", False)) is read_only, (
        f"{service}:{target} read_only was {volume.get('read_only', False)}, "
        f"expected {read_only}"
    )


def assert_markserv_excludes_state(config: dict, state_source: Path) -> None:
    markserv_mounts = mounts(config, "markserv")
    assert "/app/state" not in markserv_mounts, "Markserv must not mount Processor state"
    actual_sources = {Path(volume["source"]) for volume in markserv_mounts.values()}
    assert state_source not in actual_sources, (
        f"Markserv must not expose Pipeline state source {state_source}"
    )


def test_portable_fallback() -> None:
    home = Path(os.environ["HOME"])
    root = home / ".local/share/jfaa"
    config = compose_config(BASE, environment={})
    assert_mount(config, "processor", "/app/output", root / "pipeline-output", read_only=False)
    assert_mount(config, "processor", "/app/state", root / "pipeline-state", read_only=False)
    assert_mount(config, "markserv", "/data", root / "pipeline-output", read_only=True)
    assert_markserv_excludes_state(config, root / "pipeline-state")


def test_root_with_spaces() -> None:
    root = Path("/tmp/JFAA Application Support")
    config = compose_config(BASE, environment={"JFAA_DATA_ROOT": str(root)})
    assert_mount(config, "processor", "/app/output", root / "pipeline-output", read_only=False)
    assert_mount(config, "processor", "/app/state", root / "pipeline-state", read_only=False)
    assert_mount(config, "markserv", "/data", root / "pipeline-output", read_only=True)
    assert_markserv_excludes_state(config, root / "pipeline-state")


def test_independent_overrides() -> None:
    root = Path("/tmp/production-root-sentinel")
    output = Path("/tmp/custom pipeline output")
    state = Path("/tmp/custom pipeline state")
    config = compose_config(
        BASE,
        environment={
            "JFAA_DATA_ROOT": str(root),
            "JD_PIPELINE_OUTPUT_HOST": str(output),
            "JD_PIPELINE_STATE_HOST": str(state),
        },
    )
    assert_mount(config, "processor", "/app/output", output, read_only=False)
    assert_mount(config, "processor", "/app/state", state, read_only=False)
    assert_mount(config, "markserv", "/data", output, read_only=True)
    assert_markserv_excludes_state(config, state)


def test_harness_is_isolated_from_repository_dotenv() -> None:
    """The suite must not read a developer's real .env.

    compose_config() always passes an empty --env-file, which overrides COMPOSE_ENV_FILES and
    the default .env. This asserts that isolation holds — NOT that the repo .env is inert.
    Setting JFAA_DATA_ROOT there is the supported way to configure a host.
    """
    home = Path(os.environ["HOME"])
    fallback = home / ".local/share/jfaa"
    with tempfile.TemporaryDirectory() as directory:
        production_env = Path(directory) / "production.env"
        production_env.write_text(
            "JFAA_DATA_ROOT=/tmp/production-root-from-dotenv\n"
            "JD_PIPELINE_OUTPUT_HOST=/tmp/production-output-from-dotenv\n"
            "JD_PIPELINE_STATE_HOST=/tmp/production-state-from-dotenv\n"
        )
        config = compose_config(
            BASE,
            environment={"COMPOSE_ENV_FILES": str(production_env)},
        )

    assert_mount(config, "processor", "/app/output", fallback / "pipeline-output", read_only=False)
    assert_mount(config, "processor", "/app/state", fallback / "pipeline-state", read_only=False)
    assert_mount(config, "markserv", "/data", fallback / "pipeline-output", read_only=True)
    assert_markserv_excludes_state(config, fallback / "pipeline-state")


def test_documented_exact_mirror_helper() -> None:
    guide = (ROOT / "docs/data-root-migration.md").read_text()
    blocks = re.findall(r"```bash\n(.*?)```", guide, re.DOTALL)
    required = ("mirror_validate()", "mirror_preview()", "mirror_apply()")
    matches = [b for b in blocks if all(fn in b for fn in required)]
    assert len(matches) == 1, (
        f"expected exactly one bash block defining {required}, found {len(matches)}"
    )
    helper = matches[0]

    with tempfile.TemporaryDirectory() as directory:
        base = Path(directory)
        source = base / "source"
        destination = base / "destination"
        source.mkdir()
        destination.mkdir()

        source_collision = source / "collision.txt"
        destination_collision = destination / "collision.txt"
        source_collision.write_text("SOURCE")
        destination_collision.write_text("TARGET")
        timestamp = 1_700_000_000
        os.utime(source_collision, (timestamp, timestamp))
        os.utime(destination_collision, (timestamp, timestamp))
        (destination / "stale.txt").write_text("stale")
        root_link = base / "root-link"
        root_link.symlink_to("/")

        env = os.environ.copy()
        env.update(
            {
                "TEST_SOURCE": str(source),
                "TEST_DESTINATION": str(destination),
                "TEST_ROOT_LINK": str(root_link),
            }
        )

        def run(command: str) -> subprocess.CompletedProcess[str]:
            return subprocess.run(
                ["bash", "-c", f"set -euo pipefail\n{helper}\n{command}"],
                cwd=ROOT,
                env=env,
                text=True,
                capture_output=True,
            )

        preview = run('mirror_preview "$TEST_SOURCE" "$TEST_DESTINATION"')
        assert preview.returncode == 0, preview.stderr
        assert "collision.txt" in preview.stdout
        assert "*deleting" in preview.stdout and "stale.txt" in preview.stdout

        applied = run('mirror_apply "$TEST_SOURCE" "$TEST_DESTINATION"')
        assert applied.returncode == 0, applied.stderr
        verification = run('mirror_preview "$TEST_SOURCE" "$TEST_DESTINATION"')
        assert verification.returncode == 0, verification.stderr
        assert verification.stdout == "", verification.stdout
        assert destination_collision.read_text() == "SOURCE"
        assert not (destination / "stale.txt").exists()

        for invalid in (
            'mirror_validate / "$TEST_DESTINATION"',
            'mirror_validate "$TEST_SOURCE" "$TEST_SOURCE"',
            'mirror_validate "$TEST_SOURCE" "$TEST_ROOT_LINK"',
        ):
            assert run(invalid).returncode != 0, f"unsafe pair accepted: {invalid}"


def test_e2e_overlay_replaces_production_sources() -> None:
    sentinel_root = Path("/tmp/production-root-must-not-leak")
    sentinel_output = Path("/tmp/production-output-must-not-leak")
    sentinel_state = Path("/tmp/production-state-must-not-leak")
    config = compose_config(
        BASE,
        E2E,
        environment={
            "JFAA_DATA_ROOT": str(sentinel_root),
            "JD_PIPELINE_OUTPUT_HOST": str(sentinel_output),
            "JD_PIPELINE_STATE_HOST": str(sentinel_state),
        },
    )
    assert_mount(config, "processor", "/app/output", ROOT / ".e2e/output", read_only=False)
    assert_mount(config, "processor", "/app/state", ROOT / ".e2e/state", read_only=False)
    assert_mount(config, "markserv", "/data", ROOT / ".e2e/output", read_only=True)
    assert_markserv_excludes_state(config, ROOT / ".e2e/state")

    rendered = json.dumps(config)
    for sentinel in (sentinel_root, sentinel_output, sentinel_state):
        assert str(sentinel) not in rendered, f"production path leaked into E2E config: {sentinel}"


def test_pipeline_env_file_override() -> None:
    """PIPELINE_ENV_FILE moves the processor's /app/.env mount; the default is the repo copy.

    This is the per-instance A/B lever from #51 — ARTIFACT_BASE_URL must differ per
    instance and cannot come from compose `environment:` (blank-override hazard), so the
    dotenv mount source itself is what a second instance repoints.
    """
    default = compose_config(BASE, environment={})
    assert_mount(
        default,
        "processor",
        "/app/.env",
        ROOT / "services/job-fit-apply-ai-pipeline/.env",
        read_only=True,
    )

    override = Path("/tmp/instance pipeline.env")
    config = compose_config(BASE, environment={"PIPELINE_ENV_FILE": str(override)})
    assert_mount(config, "processor", "/app/.env", override, read_only=True)


def test_instance_identity_parameterization() -> None:
    """CONTAINER_PREFIX/STEEL_PORT default to prod values; profiles gate the intake pair.

    Prod byte-compatibility: with no variables set, every container_name is jobfit-* and
    steel publishes host port 3000 — and poller/jsearch/watchlist exist only when COMPOSE_PROFILES
    activates `intake` (prod's .env sets it; a test instance leaves it empty).
    """
    prod = compose_config(BASE, environment={"COMPOSE_PROFILES": "intake"})
    services = prod["services"]
    for name in ("db", "bridge", "frontend", "markserv", "poller", "jsearch", "watchlist", "steel", "processor", "notifier"):
        actual = services[name]["container_name"]
        assert actual == f"jobfit-{name}", f"{name} container_name was {actual}"
    steel_ports = {p["published"] for p in services["steel"]["ports"]}
    assert "3000" in steel_ports, f"steel must publish 3000 by default, got {steel_ports}"

    test_instance = compose_config(
        BASE,
        environment={"CONTAINER_PREFIX": "jobfit-test", "STEEL_PORT": "23000"},
    )
    services = test_instance["services"]
    assert "poller" not in services, "poller must not exist without the intake profile"
    assert "jsearch" not in services, "jsearch must not exist without the intake profile"
    assert "watchlist" not in services, "watchlist must not exist without the intake profile"
    assert services["bridge"]["container_name"] == "jobfit-test-bridge"
    steel_ports = {p["published"] for p in services["steel"]["ports"]}
    assert "23000" in steel_ports, f"STEEL_PORT must move the host port, got {steel_ports}"


def test_e2e_state_dir_moves_all_slice_state() -> None:
    """E2E_STATE_DIR relocates every slice bind-mount at once (the source-slice mechanism).

    The multi-instance scenarios (#56 sc. 9/10) run a second slice from ./.e2e-src; a
    mount that ignores the variable would silently share state between the two slices.
    """
    config = compose_config(BASE, E2E, environment={"E2E_STATE_DIR": "./.e2e-src"})
    src = ROOT / ".e2e-src"
    assert_mount(config, "bridge", "/data", src / "bridge-store", read_only=False)
    assert_mount(config, "markserv", "/data", src / "output", read_only=True)
    assert_mount(config, "processor", "/app/output", src / "output", read_only=False)
    assert_mount(config, "processor", "/app/state", src / "state", read_only=False)
    assert_mount(config, "processor", "/app/.env", src / "pipeline.env", read_only=True)
    assert_mount(config, "notifier", "/state", src / "notifier-state", read_only=False)

    rendered = json.dumps(config)
    # Trailing separator on purpose: ".e2e-src/…" must not match the ".e2e/…" needle.
    assert f"{ROOT / '.e2e'}/" not in rendered, "a default-.e2e path leaked past E2E_STATE_DIR"



def test_jobbot_profile_is_isolated() -> None:
    """The jobbot profile: state under the data root, JFAA data read-only, and network isolation.

    The agent container (jobbot) may only reach jobbot-mcp: it sits on the `jobbot` network
    alone, so bridge/db/steel do not resolve from it. jobbot-mcp straddles both networks and is
    the agent's only door into JFAA. Neither publishes a host port. Without the profile, neither
    service exists, so a test instance or the e2e slice never starts them.
    """
    root = Path("/tmp/jfaa jobbot root")
    config = compose_config(BASE, environment={"COMPOSE_PROFILES": "jobbot", "JFAA_DATA_ROOT": str(root)})
    services = config["services"]
    assert services["jobbot"]["container_name"] == "jobfit-jobbot"
    assert services["jobbot-mcp"]["container_name"] == "jobfit-jobbot-mcp"

    assert_mount(config, "jobbot", "/opt/data", root / "jobbot", read_only=False)
    assert_mount(config, "jobbot-mcp", "/state", root / "jobbot-mcp-state", read_only=False)
    assert_mount(config, "jobbot-mcp", "/jfaa/pipeline-output", root / "pipeline-output", read_only=True)
    assert_mount(config, "jobbot-mcp", "/poller-secrets", root / "poller-secrets", read_only=True)
    for target in ("/jfaa/profile/resume.yaml", "/jfaa/profile/candidate_profile.yaml"):
        assert mounts(config, "jobbot-mcp")[target].get("read_only") is True, f"{target} must be read-only"
    assert set(mounts(config, "jobbot")) == {"/opt/data"}, "the agent container mounts nothing but its own home"

    assert set(services["jobbot"]["networks"]) == {"jobbot"}, services["jobbot"]["networks"]
    assert set(services["jobbot-mcp"]["networks"]) == {"default", "jobbot", "jobbot-browser"}, services["jobbot-mcp"]["networks"]
    assert_mount(config, "jobbot-mcp", "/secrets", root / "jobbot-secrets", read_only=False)
    assert_mount(config, "jobbot-mcp", "/templates", root / "jobbot-templates", read_only=False)
    assert_mount(config, "notifier", "/templates", root / "jobbot-templates", read_only=True)
    for name in ("jobbot", "jobbot-mcp"):
        assert not services[name].get("ports"), f"{name} must not publish ports"

    plain = compose_config(BASE, environment={"COMPOSE_PROFILES": "intake"})["services"]
    assert "jobbot" not in plain and "jobbot-mcp" not in plain, "jobbot services need the jobbot profile"
    assert "jobbot-browser" not in plain, "JobBot's browser needs the jobbot-browser profile"

    applied = compose_config(BASE, environment={"COMPOSE_PROFILES": "jobbot,jobbot-browser", "JFAA_DATA_ROOT": str(root)})
    browser = applied["services"]["jobbot-browser"]
    assert set(browser["networks"]) == {"jobbot-browser"}, "CDP must be reachable from jobbot-mcp only — never the agent"
    assert "jobbot-browser" in applied["services"]["jobbot-mcp"]["networks"], "jobbot-mcp drives the browser"
    assert "jobbot-browser" not in applied["services"]["jobbot"]["networks"], "the agent never reaches CDP"
    assert_mount(applied, "jobbot-browser", "/config", root / "jobbot-browser", read_only=False)
    published = {(p.get("host_ip"), p["published"], p["target"]) for p in browser["ports"]}
    assert published == {("127.0.0.1", "3200", 3000)}, f"only the viewer, only on loopback: {published}"
    assert browser["environment"]["PASSWORD"] == "", "no password configured here"

    # The old names still work, so a not-yet-renamed .env keeps its browser and password.
    legacy = compose_config(BASE, environment={"COMPOSE_PROFILES": "jobbot,apply", "JFAA_DATA_ROOT": str(root),
                                               "APPLY_BROWSER_PASSWORD": "old-secret", "APPLY_BROWSER_USER": "olduser"})
    old = legacy["services"]["jobbot-browser"]["environment"]
    assert (old["PASSWORD"], old["CUSTOM_USER"]) == ("old-secret", "olduser"), old
    renamed = compose_config(BASE, environment={"COMPOSE_PROFILES": "jobbot-browser", "JFAA_DATA_ROOT": str(root),
                                                "JOBBOT_BROWSER_PASSWORD": "new-secret", "APPLY_BROWSER_PASSWORD": "old-secret"})
    assert renamed["services"]["jobbot-browser"]["environment"]["PASSWORD"] == "new-secret", "the new name wins"

    e2e = compose_config(BASE, E2E, environment={})["services"]
    assert "jobbot" not in e2e and "jobbot-mcp" not in e2e, "the e2e slice must never start JobBot"


def test_watchlist_state_follows_data_root() -> None:
    """The watchlist's last-run gate and health file live under the data root (prod: the
    /state volume doctor.sh reads), and JD_WATCHLIST_STATE_HOST overrides it on its own.
    A test slice never polls employer feeds, and never touches production watchlist state.
    """
    root = Path("/tmp/JFAA Application Support")
    config = compose_config(BASE, environment={"COMPOSE_PROFILES": "intake", "JFAA_DATA_ROOT": str(root)})
    assert_mount(config, "watchlist", "/state", root / "watchlist-state", read_only=False)

    override = Path("/tmp/custom watchlist state")
    config = compose_config(
        BASE,
        environment={"COMPOSE_PROFILES": "intake", "JFAA_DATA_ROOT": str(root), "JD_WATCHLIST_STATE_HOST": str(override)},
    )
    assert_mount(config, "watchlist", "/state", override, read_only=False)

    # Stale-feed alerts need the same channels as the processor's AlertService.
    env = config["services"]["watchlist"].get("environment", {})
    for name in ("TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID", "DISCORD_BOT_TOKEN", "DISCORD_CHANNEL_ID"):
        assert name in env, f"watchlist must receive {name} for stale-feed alerts"

    # The e2e slice runs with COMPOSE_PROFILES pinned empty (Makefile E2E_ENV) — that, not the
    # overlay's e2e-disabled profile (Compose unions profile lists), keeps it from polling feeds.
    e2e = compose_config(BASE, E2E, environment={"COMPOSE_PROFILES": ""})["services"]
    assert "watchlist" not in e2e, "the e2e slice must never poll employer feeds"
    # And if someone does enable it there, its state stays inside the slice, not the data root.
    e2e_intake = compose_config(BASE, E2E, environment={"COMPOSE_PROFILES": "intake", "JFAA_DATA_ROOT": str(root)})
    assert_mount(e2e_intake, "watchlist", "/state", ROOT / ".e2e/watchlist-state", read_only=False)


def test_e2e_prepare_scores_through_fake_cloud_unless_real_llm() -> None:
    """The e2e pipeline dotenv routes score through a fake *cloud* model with a local fallback.

    That is what lets the quota→local-fallback scenario run end to end. Under REAL_LLM=1 there
    is no fake to play the cloud, so the lines must be absent (score stays on the local default),
    and the cloud base URL must follow E2E_FAKE_LLM_PORT like MLX_LOCAL_BASE_URL does.
    """
    def prepare(**extra: str) -> str:
        with tempfile.TemporaryDirectory() as tmp:
            env = {k: v for k, v in os.environ.items() if not k.startswith("E2E_")}
            env.update({"E2E_STATE_DIR": tmp, **extra})
            subprocess.run([str(ROOT / "scripts/e2e-ci-prepare.sh")], cwd=ROOT, env=env, check=True, capture_output=True, text=True)
            return (Path(tmp) / "pipeline.env").read_text()

    fake = prepare(E2E_FAKE_LLM_PORT="24567")
    assert "ARTIFACT_BASE_URL=" in fake, fake
    assert "SCORE_MODEL=e2e-cloud-score:cloud" in fake, fake
    assert "DEEPSEEK_BASE_URL=http://host.docker.internal:24567" in fake, fake
    assert "LOCAL_FALLBACK_MODEL=e2e-local-fallback" in fake, fake
    assert "CLOUD_QUOTA_RECHECK_MINUTES=0" in fake, fake
    assert "DEEPSEEK_BASE_URL=http://host.docker.internal:21436" in prepare(), "default fake port"

    real = prepare(E2E_REAL_LLM="1")
    assert "ARTIFACT_BASE_URL=" in real, real
    for key in ("SCORE_MODEL", "DEEPSEEK_BASE_URL", "LOCAL_FALLBACK_MODEL"):
        assert key not in real, f"{key} must not be set under REAL_LLM=1: {real}"

    makefile = (ROOT / "Makefile").read_text()
    assert "E2E_REAL_LLM=$(REAL_LLM) E2E_FAKE_LLM_PORT=$(E2E_FAKE_LLM_PORT) ./scripts/e2e-ci-prepare.sh --fresh" in makefile, \
        "make e2e-up must tell the prepare script whether a fake LLM is in play"


def main() -> None:
    tests = (
        test_portable_fallback,
        test_root_with_spaces,
        test_independent_overrides,
        test_harness_is_isolated_from_repository_dotenv,
        test_documented_exact_mirror_helper,
        test_e2e_overlay_replaces_production_sources,
        test_jobbot_profile_is_isolated,
        test_pipeline_env_file_override,
        test_instance_identity_parameterization,
        test_e2e_state_dir_moves_all_slice_state,
        test_watchlist_state_follows_data_root,
        test_e2e_prepare_scores_through_fake_cloud_unless_real_llm,
    )
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
    print(f"PASS {len(tests)} Compose data-root contract tests")


if __name__ == "__main__":
    main()
