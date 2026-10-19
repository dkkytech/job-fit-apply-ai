"""Pure logic for the jobbot_actions plugin — no Telegram or Hermes imports, so it unit-tests
without either. The plugin's __init__ adapts Telegram objects to these plain dicts.

The rules themselves (eligibility, expiry, idempotency, what each verb does) live in jobbot-mcp;
this side only recognises our callbacks, refuses strangers fast, forwards the tap, and turns the
answer back into Telegram calls.
"""
import json
import re
import urllib.error
import urllib.request

# <verb>:<completed_seq> on cards; send/cancel:<approval id> under a reply-draft preview.
CALLBACK_PATTERN = r"^(apply|reply|archive|undo|tailor|send|cancel|submit|discard|resume):(\d{1,18})$"
_CALLBACK = re.compile(CALLBACK_PATTERN)
OUR_VERBS = ("apply", "reply", "archive", "undo", "tailor", "send", "cancel", "submit", "discard", "resume")
# Tolerates the escaped quotes of a JSON result nested in another JSON string.
_APPROVAL_ID = re.compile(r'\\*"approval_id\\*"\s*:\s*(\d{1,18})')


def parse_callback(data):
    """('apply', 7663) for our callbacks, None for anything else (Hermes' own buttons included)."""
    m = _CALLBACK.match(data or "")
    if not m:
        return None
    seq = int(m.group(2))
    return (m.group(1), seq) if seq > 0 else None


def allowed_users(raw):
    """TELEGRAM_ALLOWED_USERS is a comma-separated id list; blank means nobody."""
    return {u.strip() for u in (raw or "").split(",") if u.strip()}


def is_allowed(user_id, allowed):
    return str(user_id) in allowed


def tap_payload(verb, seq, user_id, chat_id, message_id, message_date):
    return {
        "verb": verb,
        "seq": seq,
        "user_id": str(user_id),
        "chat_id": str(chat_id),
        "message_id": message_id,
        "message_date": message_date,
    }


def _is_our_action(button):
    return bool(button.get("callback_data")) and parse_callback(button["callback_data"]) is not None


def rebuild_keyboard(rows, action_row):
    """The card's keyboard after a tap.

    `rows` is the current inline keyboard as lists of {text, url?, callback_data?}. Link rows and
    any button that is not one of ours are kept as they are; our action buttons are replaced by
    `action_row` (dropped entirely when it is empty). Returns None when nothing should change.
    """
    if action_row is None:
        return None
    kept = []
    for row in rows or []:
        row = [b for b in row if not _is_our_action(b)]
        if row:
            kept.append(row)
    if action_row:
        kept.append([{"text": b["text"], "callback_data": b["callback_data"]} for b in action_row])
    return kept


class JobbotMcp:
    """jobbot-mcp's plugin endpoints. Blocking urllib — call it via asyncio.to_thread."""

    def __init__(self, base_url, token, timeout=15):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def _request(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            return json.loads(resp.read().decode() or "{}")

    def tap(self, payload):
        return self._request("POST", "/plugin/tap", payload)

    def status_text(self):
        return self._request("GET", "/plugin/status").get("text") or "No status."

    def outbox(self):
        """Messages jobbot-mcp wants posted (fill reviews, hand-offs, reminders)."""
        return self._request("GET", "/plugin/outbox")

    def outbox_photo(self, item_id):
        req = urllib.request.Request(f"{self.base_url}/plugin/outbox/{int(item_id)}/photo")
        req.add_header("Authorization", f"Bearer {self.token}")
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            return resp.read()

    def outbox_delivered(self, item_id):
        return self._request("POST", f"/plugin/outbox/{int(item_id)}/delivered", {})

    def accounts_text(self):
        return self._request("GET", "/plugin/accounts").get("text") or "No accounts."

    def approval(self, approval_id):
        """The preview text and Send/Cancel row for a pending approval."""
        return self._request("GET", f"/plugin/approval/{int(approval_id)}")


def approval_id_from_result(result):
    """The approval id in a request_send_approval tool result, however Hermes wrapped it."""
    m = _APPROVAL_ID.search(str(result or ""))
    return int(m.group(1)) if m else None


def is_send_approval_tool(tool_name):
    return (tool_name or "").endswith("request_send_approval")


UNAVAILABLE = "JobBot's backend is unavailable. Try again in a minute."


def caption_fits(text):
    """Telegram photo captions are capped at 1024 characters; longer text goes as its own message."""
    return len(text or "") <= 1024


def safe_status(client):
    try:
        return client.status_text()
    except (urllib.error.URLError, OSError, ValueError) as e:
        return f"{UNAVAILABLE} ({e.__class__.__name__})"
