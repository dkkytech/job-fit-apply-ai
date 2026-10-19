#!/usr/bin/env python3
"""tuner_tools.py — the two mechanical lookups the tuner previously did by reading
large payloads into model context.

WHY THIS EXISTS
  Section E read all four existing env files (33 KB / ~9,244 tokens) to rewrite them,
  and Section D.3 pulled a 45-column × 398-row leaderboard (~5,278 tokens) to decide
  between ~28 candidate models. Both are mechanical filters. See README-TOKENS.md.

SUBCOMMANDS
  values <profile>       Print the 9 model assignments from an env file. ~720 tok to read
                         instead of 9,244. PROFILE ∈ quality|local-quality|local-good-enough|recommended
  diff <profile> <json>  Compare an env file's values against a JSON {VAR: value} blob.
  models                 Print the installed oMLX models (one per line).
  cloud                  Print the ollama-cloud catalogue (one per line), authenticated.
  leaderboard <names...> Print only the leaderboard rows for the named models, trimming the
                         45-column table to the 6 columns that matter. Reads ~2k tok
                         instead of ~5.3k.
  leaderboard --agent <names...>
                         Same rows, agent view for JOBBOT_MODEL: agent + tool-calling
                         indexes, every agent/tool-use benchmark score the payload carries,
                         latency/throughput, price, context. Ranked by the agent index.

Everything prints plain text designed to be read directly, not re-parsed by a model.
"""
import argparse, json, os, re, sys, urllib.request

D = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # scripts/ -> env-llm-tuner/
FILES = {
    "quality":           ".env.quality",
    "local-quality":     ".env.local-llm-quality",
    "local-good-enough": ".env.local-llm-good-enough",
    "recommended":       ".env.recommended",
}
VARS = ["SCAN_MODEL", "SCRAPE_MODEL", "SCORE_MODEL", "RESUME_REASONING_MODEL",
        "SKILLS_MODEL", "COVER_LETTER_MODEL", "DRAFT_REPLY_MODEL", "RUN_ANALYZER_MODEL",
        "JOBBOT_MODEL"]

MLX_URL = os.environ.get("MLX_LOCAL_BASE_URL", "http://127.0.0.1:11436/v1")
MLX_KEY = os.environ.get("MLX_API_KEY", "11436")
LEADERBOARD = "https://llm-stats.com/leaderboards/open-llm-leaderboard"
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/153.0.0.0 Safari/537.36")


def parse_env(path):
    """Return ONLY var=value. Comment lines — the entire cost of the old approach — are skipped."""
    out = {}
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as fh:
        lines = fh.readlines()
    for line in lines:
        m = re.match(r"^([A-Z_]+)=(.*)$", line.rstrip("\n"))
        if m:
            out[m.group(1)] = m.group(2).strip()
    return out


def cmd_values(profile):
    path = os.path.join(D, FILES[profile])
    vals = parse_env(path)
    if not vals:
        print(f"missing/empty: {path}", file=sys.stderr)
        return 1
    for v in VARS:
        print(f"{v}={vals.get(v, '')}")
    missing = [v for v in VARS if not vals.get(v)]
    if missing:
        print(f"WARNING missing: {missing}", file=sys.stderr)
    return 0


def cmd_diff(profile, json_path):
    """Report only CHANGED vars — the tuner's actual question, in a few tokens."""
    cur = parse_env(os.path.join(D, FILES[profile]))
    want = json.load(open(json_path, encoding="utf-8"))
    changed = []
    for v in VARS:
        a, b = cur.get(v, ""), want.get(v, "")
        if a != b:
            changed.append((v, a, b))
    if not changed:
        print("no changes")
        return 0
    for v, a, b in changed:
        print(f"CHANGED {v}: {a} -> {b}")
    print(f"{len(changed)} changed, {len(VARS)-len(changed)} unchanged")
    return 0


def cmd_models():
    req = urllib.request.Request(MLX_URL.rstrip("/") + "/models",
                                 headers={"Authorization": f"Bearer {MLX_KEY}"})
    with urllib.request.urlopen(req, timeout=15) as r:
        d = json.load(r)
    for m in sorted(x.get("id", "") for x in d.get("data", [])):
        print(m)
    return 0


def cmd_cloud():
    key = os.environ.get("OLLAMA_API_KEY")
    if not key:
        # fall back to the Hermes .env, which is where the key lives on this machine
        p = os.path.expanduser("~/.hermes/.env")
        if os.path.exists(p):
            for line in open(p):
                if line.startswith("export OLLAMA_API_KEY="):
                    key = line.split("=", 1)[1].strip()
    if not key:
        print("no OLLAMA_API_KEY", file=sys.stderr)
        return 1
    req = urllib.request.Request("https://ollama.com/api/tags",
                                 headers={"Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(req, timeout=20) as r:
        d = json.load(r)
    for m in sorted(x.get("name", "") for x in d.get("models", [])):
        print(m)
    return 0


def _fetch_leaderboard_models():
    """The leaderboard is a client-rendered Next.js app: the served HTML contains ZERO
    table rows. The real data rides in `self.__next_f.push([1, "<json>"])` RSC chunks,
    so reassemble those and extract the model objects."""
    req = urllib.request.Request(LEADERBOARD, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        html = r.read().decode("utf-8", "replace")
    chunks = []
    for m in re.finditer(r'self\.__next_f\.push\(\[1,\s*"((?:[^"\\]|\\.)*)"\]\)', html, re.S):
        try:
            chunks.append(json.loads('"' + m.group(1) + '"'))
        except Exception:
            continue
    blob = "".join(chunks)
    models = []
    for m in re.finditer(r'\{"model_id":"(?:[^"\\]|\\.)*?".*?"index_healthcare":(?:null|[0-9.]+)\}', blob, re.S):
        try:
            models.append(json.loads(m.group(0)))
        except Exception:
            continue
    return models


# Agent / tool-use benchmark fields in the leaderboard payload (e.g. toolathlon_score,
# mcp_atlas_score, tau_bench_retail_score, terminal_bench_score). Matched by pattern rather
# than listed, so a benchmark llm-stats adds later is printed without a code change.
AGENT_SCORE = re.compile(r"^(tau|bfcl|terminal_bench|toolathlon|mcp|browsecomp|osworld|apex_agents)\w*_score$")


def _num(v):
    """A usable number: the payload uses null or -1 for 'not rated'."""
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) and v >= 0 else None


def _print_agent_rows(models, names):
    """JOBBOT_MODEL view: ranked by the agent index, with the tool-calling index and every
    agent benchmark the model has a score for. Scores are printed as llm-stats reports them."""
    fields = sorted({k for m in models for k in m if AGENT_SCORE.match(k)})
    ranked = [m for m in models if _num(m.get("index_agents")) is not None]
    ranked.sort(key=lambda m: -m["index_agents"])
    rank_of = {m.get("model_id"): i + 1 for i, m in enumerate(ranked)}

    def fmt(m):
        f = lambda v: f"{v:5.1f}" if _num(v) is not None else "    -"
        lat, tput = _num(m.get("latency")), _num(m.get("throughput"))
        scores = " ".join(f"{k[:-len('_score')]}={m[k]:.3g}" for k in fields if _num(m.get(k)) is not None)
        return (f"#{rank_of.get(m.get('model_id'), 0):>3} {str(m.get('name'))[:34]:34} "
                f"agent={f(m.get('index_agents'))} tool={f(m.get('index_tool_calling'))} "
                f"in=${m.get('input_price')} out=${m.get('output_price')} ctx={m.get('context')} "
                f"latency={'-' if lat is None else round(lat)} tput={'-' if tput is None else round(tput, 1)} "
                f"| {scores or 'no agent benchmark scores'}")

    wanted = [n.lower() for n in names]
    hits = [m for m in models if any(w in str(m.get("name", "")).lower() for w in wanted)]
    if not hits:
        print(f"no match for {names}; {len(models)} models available", file=sys.stderr)
        return 1
    for m in sorted(hits, key=lambda m: -(_num(m.get("index_agents")) or 0)):
        print(fmt(m))
    print("(agent benchmarks in payload: " + (", ".join(k[:-len('_score')] for k in fields) or "none") + ")")
    print(f"({len(models)} models indexed; {len(ranked)} with an agent index; {len(hits)} matched)")
    return 0


def cmd_leaderboard(names, agent=False):
    """Print only the 6 columns that matter, for only the named models.
    Replaces reading the whole 45-column table (~5,278 tok) with ~a few hundred."""
    try:
        models = _fetch_leaderboard_models()
    except Exception as e:
        print(f"leaderboard fetch failed: {e}", file=sys.stderr)
        return 1
    if not models:
        print("no models parsed (page structure may have changed)", file=sys.stderr)
        return 1
    if agent:
        return _print_agent_rows(models, names)
    # rank by the general index, matching the site's own ordering
    ranked = [m for m in models if isinstance(m.get("index_general"), (int, float))]
    ranked.sort(key=lambda m: -m["index_general"])
    rank_of = {m.get("model_id"): i + 1 for i, m in enumerate(ranked)}

    def fmt(m):
        g = m.get("index_general"); r = m.get("index_reasoning")
        c = m.get("index_code");    a = m.get("index_agents")
        f = lambda v: f"{v:5.1f}" if isinstance(v, (int, float)) else "    -"
        return (f"#{rank_of.get(m.get('model_id'), 0):>3} {str(m.get('name'))[:34]:34} "
                f"general={f(g)} reason={f(r)} code={f(c)} agent={f(a)} "
                f"in=${m.get('input_price')} out=${m.get('output_price')} "
                f"ctx={m.get('context')}")

    wanted = [n.lower() for n in names]
    hits = [m for m in models if any(w in str(m.get("name", "")).lower() for w in wanted)]
    if not hits:
        print(f"no match for {names}; {len(models)} models available", file=sys.stderr)
        print("available: " + ", ".join(sorted(str(m.get('name')) for m in models)[:40]), file=sys.stderr)
        return 1
    for m in sorted(hits, key=lambda m: -(m.get("index_general") or 0)):
        print(fmt(m))
    print(f"({len(models)} models indexed; {len(hits)} matched)")
    return 0


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or "").split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("values"); p.add_argument("profile", choices=sorted(FILES))
    p = sub.add_parser("diff");   p.add_argument("profile", choices=sorted(FILES)); p.add_argument("json")
    sub.add_parser("models")
    sub.add_parser("cloud")
    p = sub.add_parser("leaderboard"); p.add_argument("names", nargs="+")
    p.add_argument("--agent", action="store_true", help="agent/tool-calling view (JOBBOT_MODEL)")
    a = ap.parse_args()
    if a.cmd == "values":      return cmd_values(a.profile)
    if a.cmd == "diff":        return cmd_diff(a.profile, a.json)
    if a.cmd == "models":      return cmd_models()
    if a.cmd == "cloud":       return cmd_cloud()
    if a.cmd == "leaderboard": return cmd_leaderboard(a.names, a.agent)
    return 2


if __name__ == "__main__":
    sys.exit(main())
