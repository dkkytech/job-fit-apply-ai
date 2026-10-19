#!/usr/bin/env python3
"""Generate the four commented .env.* files from picks.yaml.

Usage:
    python3 render_env.py [tuner_dir]
    python3 render_env.py --table     # print the Section F summary table from picks.yaml

Reads picks.yaml in the tuner dir and writes .env.quality,
.env.local-llm-quality, .env.local-llm-good-enough, .env.recommended.
Edit picks.yaml (challenger-vs-incumbent), then re-render — do not hand-edit
the .env.* files. A pick whose `verified` starts with "RESCREEN" is emitted as
a `# FIXME:` comment instead of a live assignment.
"""

import re
import sys
import textwrap
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("pyyaml required: pip install pyyaml")

VAR_ORDER = [
    "SCAN_MODEL",
    "SCRAPE_MODEL",
    "SCORE_MODEL",
    "RESUME_REASONING_MODEL",
    "SKILLS_MODEL",
    "COVER_LETTER_MODEL",
    "DRAFT_REPLY_MODEL",
    "RUN_ANALYZER_MODEL",
]

PROFILE_ORDER = ["quality", "local_quality", "local_good_enough", "recommended"]

# Vars on the worst-case hot path (one job reaching the tailoring subgraph).
HOT_PATH = [
    "SCAN_MODEL",
    "SCRAPE_MODEL",
    "SCORE_MODEL",
    "RESUME_REASONING_MODEL",
    "SKILLS_MODEL",
    "COVER_LETTER_MODEL",
    "DRAFT_REPLY_MODEL",
]


def is_rescreen(pick: dict) -> bool:
    return str(pick.get("verified", "")).startswith("RESCREEN")


def cell(pick: dict) -> str:
    if is_rescreen(pick):
        return f"RESCREEN ({pick['model']} retired)"
    return pick["model"]


def est_seconds(est: str):
    m = re.match(r"\s*~(\d+)s", est or "")
    return int(m.group(1)) if m else None


def render_table(data: dict) -> str:
    """Markdown summary table (Section F) generated from picks.yaml."""
    profiles = data["profiles"]
    vars_ = data["vars"]
    files = [profiles[k]["file"] for k in PROFILE_ORDER]
    lines = []
    lines.append("| Config Var | " + " | ".join(files) + " |")
    lines.append("|" + "|".join(["---"] * (len(files) + 1)) + "|")
    for var in VAR_ORDER:
        lines.append("| " + var + " | " + " | ".join(
            cell(vars_[var][k]) for k in PROFILE_ORDER) + " |")
    # distinct cloud models across all vars
    distinct = []
    for k in PROFILE_ORDER:
        seen = []
        for var in VAR_ORDER:
            pick = vars_[var][k]
            if is_rescreen(pick):
                continue
            m = pick["model"]
            if m.endswith(":ollama-cloud") and m not in seen:
                seen.append(m)
        if len(seen) > 3:
            note = f"{len(seen)} ({', '.join(seen)}) — exceeds 3-model cap by design"
        elif seen:
            note = f"{len(seen)} ({', '.join(seen)}) — within the ≤3 cap ✓"
        else:
            note = "0"
        distinct.append(note)
    lines.append("| Distinct cloud models | " + " | ".join(distinct) + " |")
    # hot-path time estimate
    times = []
    for k in PROFILE_ORDER:
        secs, blocked = 0, []
        for var in HOT_PATH:
            pick = vars_[var][k]
            s = est_seconds(pick["est"])
            if s is None:
                blocked.append(var)
            else:
                secs += s
        times.append(f"~{secs}s" + (f" (n/a: {', '.join(blocked)})" if blocked else ""))
    lines.append("| Est. hot-path time | " + " | ".join(times) + " |")
    return "\n".join(lines)

ENDPOINT_LINES = {
    "mlx": [
        "MLX_LOCAL_BASE_URL=http://127.0.0.1:11436/v1",
        "MLX_API_KEY=11436",
    ],
    "ollama_cloud": [
        "OLLAMA_CLOUD_BASE_URL=https://ollama.com",
        "OLLAMA_API_KEY=<your-ollama-cloud-key>",
    ],
}

WIDTH = 80


def header(title: str) -> str:
    """'# ── <title> ' padded with ─ to WIDTH chars; empty title = separator line."""
    if not title:
        return "# " + "─" * 77
    prefix = f"# ── {title} "
    return prefix + "─" * (WIDTH - len(prefix))


def wrap_comment(text: str, indent: str = "#   ", width: int = WIDTH) -> list:
    """Wrap comment body text to fit within width, prefixed per line."""
    return [indent + line for line in textwrap.wrap(text, width=width - len(indent))]


def render_profile(profile: dict, vars_: dict, generated: str) -> str:
    out: list[str] = []
    out.append(header(profile["file"]))
    out.extend(wrap_comment(profile["title"], indent="# "))
    out.append("#")
    out.append("# Approach:")
    out.extend(wrap_comment(profile["strategy"]))
    out.append("#")
    out.append(f"# Generated: {generated}")
    out.append(f"# Hardware (local files): {profile['hardware_note']}")
    out.append(f"# Cloud access (cloud files): {profile['cloud_access']}")
    out.append("#")
    out.append("# Catalogues fetched:")
    for cat in profile["catalogues"]:
        out.extend(wrap_comment(cat))
    out.append(header(""))
    out.append("")

    pname = profile_key(profile)
    for var in VAR_ORDER:
        pick = vars_[var][pname]
        out.append(header(var))
        out.append(f"# Model: {pick['model']}")
        out.append("# Why best / why good-enough:")
        out.extend(wrap_comment(pick["why"]))
        out.append(f"# Quality delta vs runner-up: {pick['delta']}")
        out.append(f"# Estimated node time: {pick['est']}")
        out.append(f"# Runner-up: {pick['runner_up']}")
        if str(pick.get("verified", "")).startswith("RESCREEN"):
            out.append(f"# FIXME: {var} NOT VERIFIED — {pick['verified']}")
            out.append(f"# ({pick.get('probe', '')})")
            out.append("")
            continue
        out.append(f"{var}={pick['model']}")
        out.append("")

    out.append(header("Backend endpoints"))
    for ep in profile["endpoints"]:
        out.extend(ENDPOINT_LINES[ep])
    out.append("")
    out.append(header("Notes"))
    for note in profile["notes"]:
        out.extend(wrap_comment(note, indent="# "))
    out.append("")
    return "\n".join(out)


# profile dict -> key lookup helper (set during main)
_PROFILE_KEYS: dict[int, str] = {}


def profile_key(profile: dict) -> str:
    return _PROFILE_KEYS[id(profile)]


def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "--table":
        data = yaml.safe_load(Path("picks.yaml").read_text(encoding="utf-8"))
        print(render_table(data))
        return
    tuner_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent
    picks_path = tuner_dir / "picks.yaml"
    data = yaml.safe_load(picks_path.read_text(encoding="utf-8"))

    generated = str(data.get("generated", ""))
    profiles = data["profiles"]
    vars_ = data["vars"]

    for key in PROFILE_ORDER:
        _PROFILE_KEYS[id(profiles[key])] = key

    # sanity: every var must have every profile
    for var in VAR_ORDER:
        missing = [k for k in PROFILE_ORDER if k not in vars_.get(var, {})]
        if missing:
            sys.exit(f"picks.yaml: {var} missing profiles: {missing}")

    for key in PROFILE_ORDER:
        profile = profiles[key]
        content = render_profile(profile, vars_, generated)
        dest = tuner_dir / profile["file"]
        dest.write_text(content, encoding="utf-8")
        print(f"wrote {dest} ({len(content)} chars)")


if __name__ == "__main__":
    main()
