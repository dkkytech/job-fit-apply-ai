import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from jobbot_actions import core


@pytest.mark.parametrize("data,expected", [
    ("apply:7663", ("apply", 7663)),
    ("reply:1", ("reply", 1)),
    ("archive:42", ("archive", 42)),
    ("undo:42", ("undo", 42)),
    ("tailor:7720", ("tailor", 7720)),
    ("send:3", ("send", 3)),
    ("cancel:3", ("cancel", 3)),
    ("submit:4", ("submit", 4)),
    ("discard:4", ("discard", 4)),
    ("resume:4", ("resume", 4)),
])
def test_parses_our_callbacks(data, expected):
    assert core.parse_callback(data) == expected


@pytest.mark.parametrize("data", [
    None, "", "apply", "apply:", "apply:0", "apply:-1", "apply:12a", "apply:12 ", " apply:12",
    "Apply:12", "Tailor:12", "tailor:", "delete:12", "ea:once:3", "cl:9:1", "mp:openai", "apply:1234567890123456789",
    "Apply: Acme / Staff SDET #deadbeef",
])
def test_ignores_everything_else(data):
    assert core.parse_callback(data) is None


def test_allowlist_is_exact_and_blank_means_nobody():
    allowed = core.allowed_users(" 8679792351, 42 ,")
    assert core.is_allowed(8679792351, allowed)
    assert core.is_allowed("42", allowed)
    assert not core.is_allowed(867979235, allowed)
    assert core.allowed_users("") == set()


LINKS = [{"text": "View Report", "url": "http://h/r.md"}, {"text": "View Resume", "url": "http://h/r.pdf"}]
ACTIONS = [{"text": "Apply", "callback_data": "apply:9"}, {"text": "Archive", "callback_data": "archive:9"}]


def test_keyboard_unchanged_when_backend_says_nothing():
    assert core.rebuild_keyboard([LINKS, ACTIONS], None) is None


def test_keyboard_replaces_our_actions_and_keeps_links():
    undo = [{"text": "Undo", "callback_data": "undo:9"}]
    assert core.rebuild_keyboard([LINKS, ACTIONS], undo) == [LINKS, undo]


def test_keyboard_drops_the_action_row_when_told_to():
    assert core.rebuild_keyboard([LINKS, ACTIONS], []) == [LINKS]
    assert core.rebuild_keyboard([ACTIONS], []) == []


def test_keyboard_keeps_buttons_that_are_not_ours():
    foreign = [{"text": "Allow", "callback_data": "ea:once:1"}, {"text": "Apply", "callback_data": "apply:9"}]
    assert core.rebuild_keyboard([foreign], []) == [[{"text": "Allow", "callback_data": "ea:once:1"}]]


class _Backend(BaseHTTPRequestHandler):
    seen = []

    def _reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        type(self).seen.append((self.path, self.headers.get("Authorization"), body))
        self._reply(200, {"outcome": "not_implemented", "reply": "Not implemented yet"})

    def do_GET(self):
        type(self).seen.append((self.path, self.headers.get("Authorization"), None))
        self._reply(200, {"text": "JobBot status\nMode: live"})

    def log_message(self, *a):
        pass


@pytest.fixture
def backend():
    _Backend.seen = []
    server = HTTPServer(("127.0.0.1", 0), _Backend)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


def test_tap_round_trip_carries_the_token_and_payload(backend):
    client = core.JobbotMcp(backend, "tok")
    payload = core.tap_payload("apply", 9, 8679792351, 8679792351, 5, 1790000000)
    assert client.tap(payload)["reply"] == "Not implemented yet"
    path, auth, body = _Backend.seen[-1]
    assert (path, auth) == ("/plugin/tap", "Bearer tok")
    assert body == {"verb": "apply", "seq": 9, "user_id": "8679792351", "chat_id": "8679792351",
                    "message_id": 5, "message_date": 1790000000}


def test_status_text(backend):
    assert core.safe_status(core.JobbotMcp(backend, "tok")).startswith("JobBot status")


def test_status_when_backend_is_down():
    assert core.safe_status(core.JobbotMcp("http://127.0.0.1:9", "tok", timeout=2)).startswith(core.UNAVAILABLE)


@pytest.mark.parametrize("result,expected", [
    ('{"approval_id": 12, "status": "awaiting"}', 12),
    ('[{"type":"text","text":"{\\n  \\"approval_id\\": 5\\n}"}]', 5),
    ({"content": '{"approval_id":9}'}, 9),
    ("no id here", None),
    (None, None),
])
def test_approval_id_is_found_in_tool_results(result, expected):
    assert core.approval_id_from_result(result) == expected


def test_only_the_approval_tool_triggers_posting():
    assert core.is_send_approval_tool("mcp__jfaa__request_send_approval")
    assert not core.is_send_approval_tool("mcp__jfaa__write_reply_draft")
    assert not core.is_send_approval_tool(None)

