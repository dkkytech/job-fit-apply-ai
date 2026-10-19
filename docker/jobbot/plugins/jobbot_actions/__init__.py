"""jobbot_actions — JFAA card buttons and /jdstatus for the JobBot Hermes gateway.

Telegram allows one update reader per bot token, and JobBot's gateway is it: taps on the cards the
JFAA notifier sends arrive here. Hermes' core dispatcher ignores callback data it does not own, so
this plugin registers its own handler, scoped to `<verb>:<seq>`, in an earlier handler group.
"""
import asyncio
import logging
import os

from . import core

logger = logging.getLogger(__name__)

_client = None
_allowed = set()
_home_chat = None
_bot = None
_loop = None


def _settings():
    return (
        os.environ.get("JOBBOT_MCP_URL", "http://jobbot-mcp:8790"),
        os.environ.get("JOBBOT_MCP_TOKEN", ""),
        os.environ.get("TELEGRAM_ALLOWED_USERS", ""),
        os.environ.get("TELEGRAM_HOME_CHANNEL", ""),
    )


def _keyboard_rows(message):
    markup = getattr(message, "reply_markup", None)
    rows = []
    for row in getattr(markup, "inline_keyboard", None) or []:
        rows.append([
            {k: v for k, v in (("text", b.text), ("url", b.url), ("callback_data", b.callback_data)) if v}
            for b in row
        ])
    return rows


def _markup(rows):
    from telegram import InlineKeyboardButton, InlineKeyboardMarkup

    return InlineKeyboardMarkup([
        [InlineKeyboardButton(b["text"], url=b.get("url"), callback_data=b.get("callback_data")) for b in row]
        for row in rows
    ])


async def _wake_agent(adapter, query, message, prompt):
    """Hand a validated tap to the agent as a message from the user, replying to the card."""
    try:
        from gateway.platforms.event import MessageEvent
    except ImportError:  # older layout
        from gateway.platforms.base import MessageEvent
    user = query.from_user
    source = adapter.build_source(
        chat_id=str(message.chat_id), chat_type="dm",
        user_id=str(user.id), user_name=getattr(user, "first_name", None),
    )
    event = MessageEvent(
        text=prompt,
        source=source,
        user_id=str(user.id),
        user_name=getattr(user, "first_name", None),
        reply_to_message_id=str(message.message_id),
        reply_to_text=message.text or message.caption or "",
        reply_to_is_own_message=True,
        allow_gateway_control=False,
        metadata={"jobbot_action": True},
    )
    await adapter.handle_message(event)


def _wire(application, adapter):
    from telegram.ext import ApplicationHandlerStop, CallbackQueryHandler

    global _bot, _loop
    _bot = application.bot
    try:
        _loop = asyncio.get_running_loop()
    except RuntimeError:
        _loop = None

    async def on_button(update, context):
        query = update.callback_query
        parsed = core.parse_callback(query.data)
        if parsed is None:
            return  # not ours — let Hermes' own handlers see it
        verb, seq = parsed
        message = query.message
        if not core.is_allowed(query.from_user.id, _allowed):
            await query.answer("Not authorized.")
            raise ApplicationHandlerStop
        payload = core.tap_payload(
            verb, seq, query.from_user.id, message.chat_id, message.message_id,
            int(message.date.timestamp()) if getattr(message, "date", None) else None,
        )
        try:
            result = await asyncio.to_thread(_client.tap, payload)
        except Exception as e:  # unreachable backend, bad JSON, timeout
            logger.warning("jobbot-mcp tap failed for %s:%s: %s", verb, seq, e)
            await query.answer(core.UNAVAILABLE)
            raise ApplicationHandlerStop
        logger.info("tap %s:%s -> %s", verb, seq, result.get("outcome"))
        await query.answer(result.get("toast") or None)
        rows = core.rebuild_keyboard(_keyboard_rows(message), result.get("action_row"))
        if rows is not None:
            try:
                await query.edit_message_reply_markup(reply_markup=_markup(rows) if rows else None)
            except Exception as e:
                logger.warning("could not edit card keyboard: %s", e)
        if result.get("reply"):
            row = result.get("reply_row")
            await message.reply_text(result["reply"], reply_markup=_markup([row]) if row else None)
        if result.get("agent_prompt"):
            await _wake_agent(adapter, query, message, result["agent_prompt"])
        raise ApplicationHandlerStop

    # Group -1 runs before Hermes' catch-all callback handler (group 0); ApplicationHandlerStop
    # keeps our taps from reaching it, and non-matching callbacks never enter this handler.
    application.add_handler(CallbackQueryHandler(on_button, pattern=core.CALLBACK_PATTERN), group=-1)
    if _home_chat and _loop is not None:
        _loop.create_task(_pump_outbox(application.bot))
    logger.info("jobbot_actions: card-button handler registered")


def _schedule(coro):
    """Run a bot coroutine on the gateway's loop from whatever thread a hook fires in."""
    try:
        running = asyncio.get_running_loop()
    except RuntimeError:
        running = None
    if _loop is None:
        coro.close()
        logger.warning("jobbot_actions: no gateway loop yet; cannot post")
        return
    if running is _loop:
        _loop.create_task(coro)
    else:
        asyncio.run_coroutine_threadsafe(coro, _loop)


def _after_tool(tool_name=None, args=None, result=None, task_id=None, **kwargs):
    """When the model asks for send approval, post the exact preview with ✅ Send / ✖ Cancel."""
    if not core.is_send_approval_tool(tool_name):
        return
    approval_id = core.approval_id_from_result(result)
    if approval_id is None or not _home_chat or _bot is None:
        logger.warning("jobbot_actions: approval not posted (id=%s, chat=%s)", approval_id, bool(_home_chat))
        return
    try:
        prompt = _client.approval(approval_id)
    except Exception as e:
        logger.warning("jobbot_actions: could not fetch approval %s: %s", approval_id, e)
        return
    row = prompt.get("reply_row")
    _schedule(_bot.send_message(chat_id=int(_home_chat), text=prompt.get("reply") or "Draft ready.",
                                reply_markup=_markup([row]) if row else None))


async def _post_outbox_item(bot, chat_id, item):
    """One outbox item: a photo (caption if it fits) and/or text, with its buttons on the text."""
    row = item.get("buttons") or []
    markup = _markup([row]) if row else None
    text = item.get("text") or ""
    if item.get("has_photo"):
        photo = await asyncio.to_thread(_client.outbox_photo, item["id"])
        if core.caption_fits(text):
            await bot.send_photo(chat_id=chat_id, photo=photo, caption=text, reply_markup=markup)
            return
        await bot.send_photo(chat_id=chat_id, photo=photo)
    await bot.send_message(chat_id=chat_id, text=text[:4096], reply_markup=markup)


async def _pump_outbox(bot, interval=5.0):
    """Deliver jobbot-mcp's outbox to the home chat, forever. One bad item never blocks the rest."""
    while True:
        try:
            items = await asyncio.to_thread(_client.outbox)
            for item in items:
                try:
                    await _post_outbox_item(bot, int(_home_chat), item)
                except Exception as e:
                    logger.warning("jobbot_actions: outbox item %s not posted: %s", item.get("id"), e)
                await asyncio.to_thread(_client.outbox_delivered, item["id"])
        except Exception as e:
            logger.debug("jobbot_actions: outbox poll failed: %s", e)
        await asyncio.sleep(interval)


async def _jdaccounts(raw_args=""):
    try:
        return await asyncio.to_thread(_client.accounts_text)
    except Exception as e:
        return f"{core.UNAVAILABLE} ({e.__class__.__name__})"


async def _jdstatus(raw_args=""):
    return await asyncio.to_thread(core.safe_status, _client)


def register(ctx):
    global _client, _allowed, _home_chat
    url, token, allowed, home = _settings()
    _client = core.JobbotMcp(url, token)
    _allowed = core.allowed_users(allowed)
    _home_chat = home.strip() or None
    if not token:
        logger.error("jobbot_actions: JOBBOT_MCP_TOKEN is blank; every tap will fail")
    ctx.register_telegram_handler(_wire)
    ctx.register_command("jdstatus", handler=_jdstatus, description="JobBot status: recent actions and errors")
    ctx.register_hook("post_tool_call", _after_tool)
    ctx.register_command("jdaccounts", handler=_jdaccounts, description="Site accounts JobBot created (no passwords)")
