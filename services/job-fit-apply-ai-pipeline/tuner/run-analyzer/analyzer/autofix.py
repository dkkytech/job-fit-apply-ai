"""Gated auto-fix -> living draft PR -> notify.

DEFAULT OFF (needs RUN_ANALYZER_AUTOFIX=1). Runs on the slow (daily) cadence, decoupled from
hourly analysis. Maintains ONE long-lived draft PR that accumulates fixes rather than opening a
new PR each run. Never merges, never touches main, drafts only.

Cheap dedup — no LLM diff review: each fix commit carries an `Analyzer-Fingerprint: <fp>` git
trailer; "what does the open PR already cover?" is a `git log` read of those trailers. Findings
whose fingerprint is already on the branch (or in the ledger) are skipped.

Safety: isolated git worktree; per-commit `./gradlew test` gate with revert-on-fail; dirty-tree
abort; at most one open analyzer PR; RUN_ANALYZER_AUTOFIX_DRYRUN stops before push/PR/notify.
"""

import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

from analyzer import notify
from analyzer.findings import fingerprint

ENV = os.environ
ARMED = ENV.get("RUN_ANALYZER_AUTOFIX") == "1"
DRYRUN = ENV.get("RUN_ANALYZER_AUTOFIX_DRYRUN") == "1"
SEVERITY = ENV.get("RUN_ANALYZER_AUTOFIX_SEVERITY", "high")
MAX_FIXES = int(ENV.get("RUN_ANALYZER_AUTOFIX_MAX_FIXES", "5"))
LOOKBACK_H = float(ENV.get("RUN_ANALYZER_AUTOFIX_LOOKBACK_HOURS", "26"))
AGENT_TIMEOUT = int(ENV.get("RUN_ANALYZER_AUTOFIX_AGENT_TIMEOUT", "1200"))
BASE_BRANCH = ENV.get("RUN_ANALYZER_AUTOFIX_BASE", "main")
BRANCH_PREFIX = ENV.get("RUN_ANALYZER_AUTOFIX_BRANCH_PREFIX", "analyzer/autofix")
CLAUDE_BIN = ENV.get("CLAUDE_BIN", "claude")
GH_BIN = ENV.get("GH_BIN", "gh")
GRADLE_TEST = ENV.get("RUN_ANALYZER_AUTOFIX_GATE", "cd services/job-fit-apply-ai-pipeline && ./gradlew test")

ANALYZER_DIR = Path(ENV.get("ANALYZER_DIR", ".")).resolve()
FINDINGS_ROOT = ANALYZER_DIR / "findings"
LEDGER_FILE = Path(ENV.get("LEDGER_FILE", ANALYZER_DIR / "state" / "autofix_ledger.jsonl"))
RUN_TS = ENV.get("RUN_TS", str(int(time.time())))

SEV_ORDER = {"low": 0, "medium": 1, "high": 2}
TRAILER = "Analyzer-Fingerprint"


def log(msg):
    print(f"[autofix] {msg}")


# ── git helpers ─────────────────────────────────────────────────────────────────────
def git(args, cwd, check=True, timeout=120):
    p = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {p.stderr.strip()}")
    return p


def repo_root():
    return git(["rev-parse", "--show-toplevel"], cwd=str(ANALYZER_DIR)).stdout.strip()


def tree_clean(cwd):
    return git(["status", "--porcelain"], cwd=cwd).stdout.strip() == ""


def applied_fingerprints(branch, cwd):
    """Fingerprints already on `branch` (read from commit trailers) — cheap, no LLM."""
    p = git(["log", f"origin/{BASE_BRANCH}..{branch}", f"--grep=^{TRAILER}:",
             "--pretty=%b"], cwd=cwd, check=False)
    if p.returncode != 0:
        return set()
    return set(re.findall(rf"^{TRAILER}:\s*(\S+)", p.stdout, re.MULTILINE))


# ── findings collection ─────────────────────────────────────────────────────────────
def collect_findings():
    """Qualifying findings from recent analysis.json files, deduped by fingerprint."""
    cutoff = time.time() - LOOKBACK_H * 3600
    min_sev = SEV_ORDER.get(SEVERITY, 2)
    by_fp = {}
    for path in sorted(glob.glob(str(FINDINGS_ROOT / "*" / "analysis.json")), key=os.path.getmtime):
        if os.path.getmtime(path) < cutoff:
            continue
        try:
            analysis = json.loads(Path(path).read_text())
        except (json.JSONDecodeError, OSError):
            continue
        for f in analysis.get("findings", []) or []:
            if SEV_ORDER.get(f.get("severity", "low"), 0) < min_sev:
                continue
            if not (f.get("agent_prompt") or "").strip() or not (f.get("files") or []):
                continue
            fp = f.get("fingerprint") or fingerprint(f)
            f["fingerprint"] = fp
            by_fp[fp] = f  # newest wins (glob sorted by mtime ascending)
    return list(by_fp.values())


# ── ledger ──────────────────────────────────────────────────────────────────────────
def ledger_append(entry):
    try:
        LEDGER_FILE.parent.mkdir(parents=True, exist_ok=True)
        with LEDGER_FILE.open("a") as fh:
            fh.write(json.dumps(entry) + "\n")
    except OSError:
        pass


def ledger_open_fingerprints():
    if not LEDGER_FILE.exists():
        return set()
    fps = set()
    for line in LEDGER_FILE.read_text().splitlines():
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        if e.get("status") == "pr_open":
            fps.add(e.get("fingerprint"))
    return fps


# ── the coding agent + gate ─────────────────────────────────────────────────────────
AGENT_GUARDRAILS = (
    "\n\n---\nYou are running headless in an ISOLATED git worktree on a throwaway fix branch. "
    "Make ONLY the code changes needed to fix the issue described above. Do NOT run git, do NOT "
    "commit, push, or open PRs, and do NOT modify unrelated files. When done, stop."
)


def run_agent(prompt, cwd):
    """Invoke the headless coding agent to apply one fix. Returns True on clean exit."""
    try:
        p = subprocess.run(
            [CLAUDE_BIN, "-p", prompt + AGENT_GUARDRAILS,
             "--permission-mode", "bypassPermissions"],
            cwd=cwd, capture_output=True, text=True, timeout=AGENT_TIMEOUT,
        )
        if p.returncode != 0:
            log(f"agent exited {p.returncode}: {p.stderr.strip()[:200]}")
        return p.returncode == 0
    except subprocess.TimeoutExpired:
        log(f"agent timed out after {AGENT_TIMEOUT}s")
        return False
    except OSError as e:
        log(f"agent invocation failed ({CLAUDE_BIN}): {e}")
        return False


def run_gate(cwd):
    """Acceptance gate. Returns True if it passes."""
    p = subprocess.run(GRADLE_TEST, cwd=cwd, shell=True,
                       capture_output=True, text=True, timeout=3600)
    if p.returncode != 0:
        log(f"gate failed: {(p.stdout + p.stderr).strip()[-300:]}")
    return p.returncode == 0


# ── main loop ───────────────────────────────────────────────────────────────────────
def main():
    if not ARMED:
        log("disabled (set RUN_ANALYZER_AUTOFIX=1 to arm) — nothing to do")
        return 0
    if shutil.which(CLAUDE_BIN) is None or shutil.which(GH_BIN) is None:
        log(f"missing dependency: need '{CLAUDE_BIN}' and '{GH_BIN}' on PATH — skipping")
        return 0

    findings = collect_findings()
    if not findings:
        log("no qualifying findings in the lookback window — nothing to do")
        return 0

    root = repo_root()
    if not tree_clean(root):
        log("repo working tree is dirty — refusing to run (never operate on a dirty tree)")
        return 0

    git(["fetch", "origin", BASE_BRANCH], cwd=root, check=False)

    open_pr = notify.find_open_analyzer_pr()
    existing_branch = open_pr.get("headRefName") if open_pr else None
    branch = existing_branch or f"{BRANCH_PREFIX}-{RUN_TS}"

    # Dedup: skip fingerprints already on the branch or recorded open in the ledger.
    already = ledger_open_fingerprints()
    worktree = Path(ENV.get("TMPDIR", "/tmp")) / f"jd-autofix-{RUN_TS}"
    _cleanup_worktree(root, worktree)

    try:
        if existing_branch:
            git(["fetch", "origin", existing_branch], cwd=root, check=False)
            git(["worktree", "add", str(worktree), branch], cwd=root)
            # keep the living branch current with main; skip (don't abort) on conflict
            r = git(["rebase", f"origin/{BASE_BRANCH}"], cwd=str(worktree), check=False)
            if r.returncode != 0:
                git(["rebase", "--abort"], cwd=str(worktree), check=False)
                log("rebase on main conflicted — continuing on the existing branch without rebase")
            already |= applied_fingerprints(branch, str(worktree))
        else:
            git(["worktree", "add", "-b", branch, str(worktree), f"origin/{BASE_BRANCH}"], cwd=root)

        todo = [f for f in findings if f["fingerprint"] not in already][:MAX_FIXES]
        if not todo:
            log(f"all {len(findings)} qualifying finding(s) already covered by the open PR — nothing new")
            return 0
        log(f"{len(todo)} new finding(s) to fix on branch {branch} (dryrun={DRYRUN})")

        applied = _apply_fixes(todo, worktree)
        if not applied:
            log("no fix survived the gate — no PR opened")
            _notify_failure(todo)
            return 0

        if DRYRUN:
            log(f"DRYRUN — would push {branch} and open/update a draft PR with {len(applied)} fix(es):")
            for f in applied:
                log(f"  - {f['fingerprint']} {f.get('title')}")
            return 0

        pr = _push_and_pr(applied, branch, worktree, root, existing_branch, open_pr)
        for f in applied:
            ledger_append({"fingerprint": f["fingerprint"], "finding_id": f.get("id"),
                           "branch": branch, "pr_url": (pr or {}).get("url"),
                           "status": "pr_open", "ts": RUN_TS})
        _notify_success(applied, pr, bool(existing_branch))
        return 0
    finally:
        _cleanup_worktree(root, worktree)


def _apply_fixes(todo, worktree):
    """Apply each fix as its own commit; revert commits that don't change files or fail the gate."""
    applied = []
    for f in todo:
        fp = f["fingerprint"]
        log(f"applying {fp}: {f.get('title')}")
        if not run_agent(f["agent_prompt"], str(worktree)):
            continue
        if tree_clean(str(worktree)):
            log(f"  agent made no changes for {fp} — skipping")
            continue
        git(["add", "-A"], cwd=str(worktree))
        msg = (f"fix: {f.get('title', 'analyzer finding')}\n\n"
               f"Auto-generated by run-analyzer for finding {f.get('id')} ({f.get('severity')}). "
               f"Review before merge.\n\n{TRAILER}: {fp}")
        git(["commit", "-m", msg], cwd=str(worktree))
        if not run_gate(str(worktree)):
            log(f"  gate failed for {fp} — reverting the commit")
            git(["reset", "--hard", "HEAD~1"], cwd=str(worktree))
            continue
        applied.append(f)
    return applied


def _push_and_pr(applied, branch, worktree, root, existing_branch, open_pr):
    git(["push", "-u", "origin", branch, "--force-with-lease"], cwd=str(worktree))
    if existing_branch and open_pr:
        log(f"updated existing draft PR #{open_pr.get('number')} with {len(applied)} new fix(es)")
        return open_pr
    body = _pr_body(applied)
    p = subprocess.run(
        [GH_BIN, "pr", "create", "--draft", "--base", BASE_BRANCH, "--head", branch,
         "--title", f"run-analyzer: {len(applied)} auto-fix(es) for review", "--body", body],
        cwd=root, capture_output=True, text=True, timeout=60,
    )
    url = (p.stdout or "").strip().splitlines()[-1] if p.returncode == 0 and p.stdout.strip() else None
    if not url:
        log(f"gh pr create failed: {p.stderr.strip()[:200]}")
        return None
    log(f"opened draft PR: {url}")
    # Resolve the PR number for the ledger/notify.
    return notify.find_open_analyzer_pr() or {"url": url}


def _pr_body(applied):
    lines = ["Auto-generated by **run-analyzer**. Draft — **review before merge; never auto-merged.**",
             "", "Each fix is a separate commit gated by the acceptance check.", "", "## Fixes"]
    for f in applied:
        lines.append(f"- **{f.get('title')}** (`{f.get('severity')}`, {f.get('category', '?')}) "
                     f"— fingerprint `{f['fingerprint']}`")
        for e in (f.get("evidence") or [])[:3]:
            lines.append(f"  - {e}")
    return "\n".join(lines)


def _notify_success(applied, pr, updated):
    verb = "updated" if updated else "opened"
    titles = "; ".join(f.get("title", "?") for f in applied)
    notify.send(
        f"run-analyzer {verb} a draft PR with {len(applied)} fix(es)",
        f"Fixes: {titles}", pr=pr,
    )


def _notify_failure(todo):
    titles = "; ".join(f.get("title", "?") for f in todo)
    notify.send(
        "run-analyzer: auto-fix attempt failed",
        f"No fix passed the gate for: {titles}. Left as task files for manual review.",
    )


def _cleanup_worktree(root, worktree):
    if Path(worktree).exists():
        git(["worktree", "remove", "--force", str(worktree)], cwd=root, check=False)
        shutil.rmtree(worktree, ignore_errors=True)
    git(["worktree", "prune"], cwd=root, check=False)


if __name__ == "__main__":
    sys.exit(main())
