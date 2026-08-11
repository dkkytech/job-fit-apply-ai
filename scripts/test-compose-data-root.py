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
        "PIPELINE_ENV_FILE",
        "CONTAINER_PREFIX",
        "E2E_STATE_DIR",
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
    steel publishes host port 3000 — and poller/jsearch exist only when COMPOSE_PROFILES
    activates `intake` (prod's .env sets it; a test instance leaves it empty).
    """
    prod = compose_config(BASE, environment={"COMPOSE_PROFILES": "intake"})
    services = prod["services"]
    for name in ("db", "bridge", "frontend", "markserv", "poller", "jsearch", "steel", "processor", "notifier"):
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


def main() -> None:
    tests = (
        test_portable_fallback,
        test_root_with_spaces,
        test_independent_overrides,
        test_harness_is_isolated_from_repository_dotenv,
        test_documented_exact_mirror_helper,
        test_e2e_overlay_replaces_production_sources,
        test_pipeline_env_file_override,
        test_instance_identity_parameterization,
        test_e2e_state_dir_moves_all_slice_state,
    )
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
    print(f"PASS {len(tests)} Compose data-root contract tests")


if __name__ == "__main__":
    main()
