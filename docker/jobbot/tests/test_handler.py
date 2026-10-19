"""The plugin's Telegram glue, driven with stand-ins for python-telegram-bot and Hermes so it runs
without either installed. Covers the paths a real tap takes through on_button."""
import asyncio
import sys
import types
from datetime import datetime, timezone

import pytest


class ApplicationHandlerStop(Exception):
    pass


class CallbackQueryHandler:
    def __init__(self, callback, pattern=None):
        self.callback, self.pattern = callback, pattern


class InlineKeyboardButton:
    def __init__(self, text, url=None, callback_data=None):
        self.text, self.url, self.callback_data = text, url, callback_data


class InlineKeyboardMarkup:
    def __init__(self, inline_keyboard):
        self.inline_keyboard = inline_keyboard


class MessageEvent:
    def __init__(self, **kw):
        self.__dict__.update(kw)


@pytest.fixture(autouse=True)
def fake_libs(monkeypatch):
    telegram = types.ModuleType("telegram")
    telegram.InlineKeyboardButton, telegram.InlineKeyboardMarkup = InlineKeyboardButton, InlineKeyboardMarkup
    ext = types.ModuleType("telegram.ext")
    ext.ApplicationHandlerStop, ext.CallbackQueryHandler = ApplicationHandlerStop, CallbackQueryHandler
    event_mod = types.ModuleType("gateway.platforms.event")
    event_mod.MessageEvent = MessageEvent
    for name, mod in {
        "telegram": telegram, "telegram.ext": ext, "gateway": types.ModuleType("gateway"),
        "gateway.platforms": types.ModuleType("gateway.platforms"), "gateway.platforms.event": event_mod,
    }.items():
        monkeypatch.setitem(sys.modules, name, mod)


class Recorder:
    def __init__(self):
        self.calls = []

    def __call__(self, name, ret=None):
        async def fn(*args, **kwargs):
            self.calls.append((name, args, kwargs))
            return ret
        return fn


class FakeClient:
    def __init__(self, result=None, error=None):
        self.result, self.error, self.payloads = result, error, []

    def tap(self, payload):
        self.payloads.append(payload)
        if self.error:
            raise self.error
        return self.result


class FakeBot:
    def __init__(self):
        self.sent = []
        self.photos = []

    async def send_message(self, **kw):
        self.sent.append(kw)

    async def send_photo(self, **kw):
        self.photos.append(kw)


class FakeAdapter:
    def __init__(self, rec):
        self.rec = rec
        self.handle_message = rec("handle_message")

    def build_source(self, **kw):
        return {"source": kw}


def wire(monkeypatch, client, allowed="8679792351"):
    import jobbot_actions as plugin
    monkeypatch.setenv("JOBBOT_MCP_TOKEN", "tok")
    monkeypatch.setenv("TELEGRAM_ALLOWED_USERS", allowed)
    monkeypatch.setenv("TELEGRAM_HOME_CHANNEL", "8679792351")
    registered = {}

    class Ctx:
        def register_telegram_handler(self, factory):
            registered["factory"] = factory

        def register_command(self, name, handler, description=""):
            registered[name] = handler

        def register_hook(self, name, fn):
            registered["hook:" + name] = fn

    plugin.register(Ctx())
    plugin._client = client
    handlers = []

    class App:
        bot = FakeBot()

        def add_handler(self, handler, group=0):
            handlers.append((handler, group))

    rec = Recorder()
    adapter = FakeAdapter(rec)
    registered["factory"](App(), adapter)
    (handler, group), = handlers
    return handler, group, rec, registered


def tap(rec, data="apply:7663", user=8679792351):
    message = types.SimpleNamespace(
        chat_id=8679792351, message_id=55, date=datetime(2026, 10, 1, tzinfo=timezone.utc),
        text="High-fit: Acme — Staff SDET — 80\n#J7663", caption=None,
        reply_markup=InlineKeyboardMarkup([
            [InlineKeyboardButton("View Report", url="http://h/r.md")],
            [InlineKeyboardButton("Apply", callback_data="apply:7663"),
             InlineKeyboardButton("Archive", callback_data="archive:7663")],
        ]),
        reply_text=rec("reply_text"),
    )
    query = types.SimpleNamespace(
        data=data, from_user=types.SimpleNamespace(id=user, first_name="Richard"), message=message,
        answer=rec("answer"), edit_message_reply_markup=rec("edit_markup"),
    )
    return types.SimpleNamespace(callback_query=query)


def run(handler, update):
    return asyncio.run(handler.callback(update, None))


def test_handler_is_scoped_and_runs_before_hermes(monkeypatch):
    handler, group, _, registered = wire(monkeypatch, FakeClient({}))
    assert handler.pattern == r"^(apply|reply|archive|undo|tailor|send|cancel|submit|discard|resume):(\d{1,18})$"
    assert "jdaccounts" in registered
    assert group < 0
    assert "jdstatus" in registered


def test_apply_tap_forwards_and_replies_not_implemented(monkeypatch):
    client = FakeClient({"outcome": "not_implemented", "reply": "Not implemented yet"})
    handler, _, rec, _ = wire(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec))
    assert client.payloads == [{"verb": "apply", "seq": 7663, "user_id": "8679792351", "chat_id": "8679792351",
                                "message_id": 55, "message_date": 1790812800}]
    names = [c[0] for c in rec.calls]
    assert names == ["answer", "reply_text"], names
    assert rec.calls[1][1] == ("Not implemented yet",)


def test_strangers_are_refused_without_reaching_the_backend(monkeypatch):
    client = FakeClient({})
    handler, _, rec, _ = wire(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec, user=1))
    assert client.payloads == []
    assert rec.calls == [("answer", ("Not authorized.",), {})]


def test_foreign_callbacks_pass_through(monkeypatch):
    client = FakeClient({})
    handler, _, rec, _ = wire(monkeypatch, client)
    assert run(handler, tap(rec, data="ea:once:3")) is None
    assert rec.calls == [] and client.payloads == []


def test_backend_failure_answers_unavailable(monkeypatch):
    import jobbot_actions.core as core
    handler, _, rec, _ = wire(monkeypatch, FakeClient(error=OSError("refused")))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec))
    assert rec.calls == [("answer", (core.UNAVAILABLE,), {})]


def test_action_row_edits_the_keyboard_keeping_links(monkeypatch):
    result = {"outcome": "done", "toast": "Archived.", "action_row": [{"text": "Undo", "callback_data": "undo:7663"}]}
    handler, _, rec, _ = wire(monkeypatch, FakeClient(result))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec, data="archive:7663"))
    edit = next(c for c in rec.calls if c[0] == "edit_markup")
    rows = edit[2]["reply_markup"].inline_keyboard
    assert [[b.text for b in r] for r in rows] == [["View Report"], ["Undo"]]


def test_empty_action_row_removes_the_keyboard_when_nothing_else_is_left(monkeypatch):
    handler, _, rec, _ = wire(monkeypatch, FakeClient({"outcome": "expired", "toast": "x", "action_row": []}))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec))
    edit = next(c for c in rec.calls if c[0] == "edit_markup")
    assert [[b.text for b in r] for r in edit[2]["reply_markup"].inline_keyboard] == [["View Report"]]


def test_agent_prompt_wakes_the_agent_as_a_reply_to_the_card(monkeypatch):
    result = {"outcome": "agent", "agent_prompt": "[JobBot action] Reply on #J7663"}
    handler, _, rec, _ = wire(monkeypatch, FakeClient(result))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec, data="reply:7663"))
    (_, (event,), _), = [c for c in rec.calls if c[0] == "handle_message"]
    assert event.text == "[JobBot action] Reply on #J7663"
    assert event.reply_to_message_id == "55"
    assert "#J7663" in event.reply_to_text
    assert event.allow_gateway_control is False
    assert event.source == {"source": {"chat_id": "8679792351", "chat_type": "dm", "user_id": "8679792351", "user_name": "Richard"}}


def test_send_and_cancel_taps_are_forwarded_with_the_approval_id(monkeypatch):
    client = FakeClient({"outcome": "done", "toast": "Sent.", "reply": "Sent to r@x.com.", "action_row": []})
    handler, _, rec, _ = wire(monkeypatch, client)
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec, data="send:12"))
    assert client.payloads[0]["verb"] == "send" and client.payloads[0]["seq"] == 12


def test_a_reply_with_buttons_carries_its_own_keyboard(monkeypatch):
    result = {"outcome": "awaiting_approval", "reply": "Reply draft for #J7663 …",
              "reply_row": [{"text": "✅ Send", "callback_data": "send:4"}, {"text": "✖ Cancel", "callback_data": "cancel:4"}]}
    handler, _, rec, _ = wire(monkeypatch, FakeClient(result))
    with pytest.raises(ApplicationHandlerStop):
        run(handler, tap(rec, data="reply:7663"))
    (_, args, kwargs), = [c for c in rec.calls if c[0] == "reply_text"]
    assert args == ("Reply draft for #J7663 …",)
    assert [b.callback_data for b in kwargs["reply_markup"].inline_keyboard[0]] == ["send:4", "cancel:4"]


def test_request_send_approval_posts_the_preview_with_send_and_cancel(monkeypatch):
    import threading
    import jobbot_actions as plugin

    class Approvals(FakeClient):
        def approval(self, approval_id):
            assert approval_id == 7
            return {"reply": "Reply draft for #J1 — tap ✅ Send", "reply_row": [{"text": "✅ Send", "callback_data": "send:7"}]}

    _, _, _, registered = wire(monkeypatch, Approvals({}))
    loop = asyncio.new_event_loop()
    t = threading.Thread(target=loop.run_forever, daemon=True)
    t.start()
    plugin._loop = loop
    try:
        hook = registered["hook:post_tool_call"]
        hook(tool_name="mcp__jfaa__get_job", args={}, result='{"approval_id": 7}', task_id="t")
        hook(tool_name="mcp__jfaa__request_send_approval", args={"ref": "#J1"},
             result='{"approval_id": 7, "status": "awaiting"}', task_id="t")
        import time
        for _ in range(50):
            if plugin._bot.sent:
                break
            time.sleep(0.02)
        (sent,) = plugin._bot.sent
        assert sent["chat_id"] == 8679792351
        assert sent["text"].startswith("Reply draft for #J1")
        assert [b.callback_data for b in sent["reply_markup"].inline_keyboard[0]] == ["send:7"]
    finally:
        loop.call_soon_threadsafe(loop.stop)


class OutboxClient(FakeClient):
    def __init__(self, items):
        super().__init__({})
        self.items, self.acked = items, []

    def outbox_photo(self, item_id):
        return b"PNG" + str(item_id).encode()


def test_outbox_items_are_posted_with_photo_caption_and_buttons(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    review = {"id": 3, "text": "Ready to submit #J9", "has_photo": True,
              "buttons": [{"text": "✅ Submit", "callback_data": "submit:3"}, {"text": "✖ Discard", "callback_data": "discard:3"}]}
    asyncio.run(plugin._post_outbox_item(bot, 8679792351, review))
    (photo,) = bot.photos
    assert photo["caption"] == "Ready to submit #J9" and photo["photo"] == b"PNG3"
    assert [b.callback_data for b in photo["reply_markup"].inline_keyboard[0]] == ["submit:3", "discard:3"]
    assert bot.sent == []


def test_long_outbox_text_goes_as_a_message_after_the_photo(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    item = {"id": 4, "text": "x" * 2000, "has_photo": True, "buttons": [{"text": "▶ Continue", "callback_data": "resume:4"}]}
    asyncio.run(plugin._post_outbox_item(bot, 1, item))
    assert "caption" not in bot.photos[0]
    assert bot.sent[0]["text"] == "x" * 2000
    assert bot.sent[0]["reply_markup"].inline_keyboard[0][0].callback_data == "resume:4"


def test_text_only_items_are_plain_messages(monkeypatch):
    import jobbot_actions as plugin
    wire(monkeypatch, FakeClient({}))
    plugin._client = OutboxClient([])
    bot = FakeBot()
    asyncio.run(plugin._post_outbox_item(bot, 1, {"id": 5, "text": "Couldn't fill #J9", "has_photo": False, "buttons": []}))
    assert bot.photos == [] and bot.sent[0]["text"] == "Couldn't fill #J9" and bot.sent[0]["reply_markup"] is None

