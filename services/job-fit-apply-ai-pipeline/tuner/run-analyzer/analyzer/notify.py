"""Discord/Telegram notifications — replicates pipeline/.../client/NotificationClient.kt.

Raw HTTP, no SDK. No-op when the relevant credentials are blank (matching the Kotlin
behavior). Every message can carry the open analyzer PR's URL so any ping links straight
to where fixes are accumulating.
"""

import json
import os
import subprocess
import urllib.request

ENV = os.environ


def _credential(primary, legacy, env=None):
    source = ENV if env is None else env
    return source.get(primary, "").strip() or source.get(legacy, "").strip()


DISCORD_TOKEN = ENV.get("DISCORD_BOT_TOKEN", "")
DISCORD_CHANNEL = ENV.get("DISCORD_CHANNEL_ID", "")
TELEGRAM_TOKEN = _credential("RUN_ANALYZER_TELEGRAM_BOT_TOKEN", "TELEGRAM_BOT_TOKEN")
TELEGRAM_CHAT = _credential("RUN_ANALYZER_TELEGRAM_CHAT_ID", "TELEGRAM_CHAT_ID")
GH_BIN = ENV.get("GH_BIN", "gh")
BRANCH_PREFIX = ENV.get("RUN_ANALYZER_AUTOFIX_BRANCH_PREFIX", "analyzer/autofix")


def _post(url, data, headers):
    try:
        req = urllib.request.Request(url, data=json.dumps(data).encode(), headers=headers)
        with urllib.request.urlopen(req, timeout=20) as resp:
            resp.read()
        return True
    except Exception as e:  # noqa: BLE001 — notification failure must never sink the run
        import sys
        print(f"[notify] post to {url.split('//')[-1].split('/')[0]} failed: {e}", file=sys.stderr)
        return False


def find_open_analyzer_pr():
    """The open analyzer PR (branch prefixed BRANCH_PREFIX), or None. Never raises."""
    try:
        p = subprocess.run(
            [GH_BIN, "pr", "list", "--state", "open", "--limit", "50",
             "--json", "number,url,headRefName"],
            capture_output=True, text=True, timeout=20,
        )
        if p.returncode != 0:
            return None
        for pr in json.loads(p.stdout or "[]"):
            if (pr.get("headRefName") or "").startswith(BRANCH_PREFIX):
                return pr
    except Exception:  # noqa: BLE001
        return None
    return None


def send(title, message, pr=None, include_open_pr=True):
    """Send `title`/`message` to Discord + Telegram. Appends the open analyzer PR URL.

    `pr` — an explicit PR dict {number,url} to reference. If omitted and include_open_pr,
    look one up so hourly analysis pings also link the living PR. No-op when creds blank.
    """
    if pr is None and include_open_pr:
        pr = find_open_analyzer_pr()
    pr_line = ""
    if pr and pr.get("url"):
        pr_line = f"\nPR #{pr.get('number', '?')}: {pr['url']}"

    sent = False
    if DISCORD_TOKEN and DISCORD_CHANNEL:
        content = f"**{title}**\n{message}{pr_line}"
        sent |= _post(
            f"https://discord.com/api/v10/channels/{DISCORD_CHANNEL}/messages",
            {"content": content[:1990]},
            {"Authorization": f"Bot {DISCORD_TOKEN}", "Content-Type": "application/json"},
        )
    if TELEGRAM_TOKEN and TELEGRAM_CHAT:
        text = f"<b>{title}</b>\n{message}{pr_line}"
        sent |= _post(
            f"https://api.telegram.org/bot{TELEGRAM_TOKEN}/sendMessage",
            {"chat_id": TELEGRAM_CHAT, "text": text[:4090], "parse_mode": "HTML"},
            {"Content-Type": "application/json"},
        )
    return sent
