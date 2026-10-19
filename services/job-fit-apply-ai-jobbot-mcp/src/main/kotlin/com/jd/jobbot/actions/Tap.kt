package com.jd.jobbot.actions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A button tap as the Hermes plugin forwards it. */
@Serializable
data class TapRequest(
    val verb: String,
    val seq: Long,
    @SerialName("user_id") val userId: String,
    @SerialName("chat_id") val chatId: String,
    /** The card's Telegram message id, for the audit record. */
    @SerialName("message_id") val messageId: Long? = null,
    /** The card's send time (Telegram `message.date`, epoch seconds) — the expiry clock. */
    @SerialName("message_date") val messageDate: Long? = null,
)

@Serializable
data class InlineButton(
    val text: String,
    @SerialName("callback_data") val callbackData: String,
)

/**
 * What the plugin should do in Telegram. Every field is optional except [outcome]:
 *  - [toast]: the answerCallbackQuery text (≤ 200 chars).
 *  - [reply]: a message to send as a reply to the card.
 *  - [actionRow]: replace the card's action-button row; `[]` removes it, null leaves it alone.
 *    URL buttons (View Report / Resume) are kept by the plugin.
 *  - [agentPrompt]: wake the agent with this text, in the user's chat, as a reply to the card.
 *  - [replyRow]: inline buttons on the [reply] message.
 */
@Serializable
data class TapResponse(
    val outcome: String,
    val toast: String? = null,
    val reply: String? = null,
    @SerialName("action_row") val actionRow: List<InlineButton>? = null,
    @SerialName("agent_prompt") val agentPrompt: String? = null,
    /** Buttons for the [reply] message itself (e.g. ✅ Send / ✖ Cancel under a draft preview). */
    @SerialName("reply_row") val replyRow: List<InlineButton>? = null,
)

/** Everything a verb handler needs about one validated tap. */
data class TapContext(
    val request: TapRequest,
    val event: JsonObject,
    val jobKey: String,
    val ref: String,
)

/** ✅ Send / ✖ Cancel on an approval preview. The tap's number is the approval id, not a seq. */
interface ApprovalHandler {
    fun send(id: Long, req: TapRequest): TapResponse
    fun cancel(id: Long, req: TapRequest): TapResponse

    fun idHandlers(): Map<String, IdVerbHandler> = mapOf("send" to IdVerbHandler(::send), "cancel" to IdVerbHandler(::cancel))
}

/** A button addressed by an action id (send:7, submit:12) rather than a job seq. */
fun interface IdVerbHandler {
    fun handle(id: Long, req: TapRequest): TapResponse
}

/** One verb's behavior. Handlers run only after the tap passed every shared check. */
fun interface VerbHandler {
    fun handle(ctx: TapContext, store: ActionStore): TapResponse
}

object Outcomes {
    const val DONE = "done"
    const val ALREADY = "already"
    const val NOT_IMPLEMENTED = "not_implemented"
    const val AGENT = "agent"
    const val AWAITING_APPROVAL = "awaiting_approval"
    const val DRY_RUN = "dry_run"
    const val EXPIRED = "expired"
    const val REFUSED = "refused"
    const val ERROR = "error"
}
