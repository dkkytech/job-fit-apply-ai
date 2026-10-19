package com.jd.poller.gmail

/** The terminal Gmail label names the Processor emits (mirrors pipeline TerminalLabel). */
object TerminalLabels {
    const val JD_ERROR            = "JD_Error"
    const val JD_SCRAPE_FAILED    = "JD_Scrape_Failed"
    const val RECRUITER           = "Recruiter_Response_Required"
    const val JD_PROCESSED_DIGEST = "JD_Processed_Digest"
    const val JD_NOT_FOUND        = "JD_Not_Found"
    const val JD_PROCESSED        = "JD_Processed"
    const val APPLICATION_UPDATE  = "JD_Application_Update"
    const val PROCESSING          = "Processing"      // in-flight, applied at intake (before we know it's a JD), cleared here
}

/**
 * Applies a completed job's terminal label to its Gmail message, replicating the side effects of
 * the old EmailLabelingService (archive / star / mark-unread) keyed by the label name. The
 * in-flight Processing label is always cleared. Pure orchestration over [GmailClient] — the
 * decision (which label) was already made by the Processor, except a blank/null decision falls
 * back to JD_Not_Found so the email always reaches a state the intake query excludes.
 */
object LabelApplier {

    fun apply(client: GmailClient, messageId: String, terminalLabel: String) {
        when (terminalLabel) {
            TerminalLabels.JD_ERROR -> {
                label(client, messageId, TerminalLabels.JD_ERROR)
                client.markUnread(messageId)
                // Note: JD_Error intentionally does NOT clear a prior JD_Error.
            }
            TerminalLabels.JD_SCRAPE_FAILED -> {
                label(client, messageId, TerminalLabels.JD_SCRAPE_FAILED)
                client.markUnread(messageId)
                clearErrorLabel(client, messageId)
            }
            TerminalLabels.RECRUITER -> {
                label(client, messageId, TerminalLabels.RECRUITER)
                client.starEmail(messageId)
                client.markUnread(messageId)
                clearErrorLabel(client, messageId)
            }
            TerminalLabels.JD_PROCESSED_DIGEST -> {
                label(client, messageId, TerminalLabels.JD_PROCESSED_DIGEST)
                client.archiveEmail(messageId)
                clearErrorLabel(client, messageId)
            }
            TerminalLabels.JD_NOT_FOUND -> {
                label(client, messageId, TerminalLabels.JD_NOT_FOUND)
                client.markUnread(messageId)
                clearErrorLabel(client, messageId)
            }
            // Interview / status mail for a role in progress: needs the user's attention, so it stays
            // in the inbox, unread — but it is not a posting and gets no resume.
            TerminalLabels.APPLICATION_UPDATE -> {
                label(client, messageId, TerminalLabels.APPLICATION_UPDATE)
                client.markUnread(messageId)
                clearErrorLabel(client, messageId)
            }
            TerminalLabels.JD_PROCESSED -> {
                label(client, messageId, TerminalLabels.JD_PROCESSED)
                client.archiveEmail(messageId)
                clearErrorLabel(client, messageId)
            }
            else -> {
                // A blank/null terminal label means the Processor finished the email WITHOUT a
                // labeling decision — a scanned "not a job" email, a digest parent, or an early
                // skip (ProcessorCommandHandler.skipResult emits no terminal_label). Fall back to
                // JD_Not_Found so the email leaves the intake query's match set. Without a terminal
                // label the email keeps matching the query, is re-submitted (deduped to the already
                // written-back job) and re-labeled Processing every intake pass — but that job is
                // no longer in the completed feed, so Processing is never cleared again and the
                // label sticks forever. An unknown NON-blank label is applied verbatim. Unlike a JD
                // link we tried and failed to scrape, a not-a-job email needs no attention, so this
                // path deliberately does not mark it unread.
                label(client, messageId, terminalLabel.ifBlank { TerminalLabels.JD_NOT_FOUND })
                clearErrorLabel(client, messageId)
            }
        }
        clearProcessingLabel(client, messageId)
    }

    private fun label(client: GmailClient, messageId: String, name: String) {
        client.labelEmail(messageId, client.getOrCreateLabel(name))
    }

    private fun clearErrorLabel(client: GmailClient, messageId: String) {
        client.findLabelId(TerminalLabels.JD_ERROR)?.let {
            client.applyLabels(messageId, addLabels = emptyList(), removeLabels = listOf(it))
        }
    }

    private fun clearProcessingLabel(client: GmailClient, messageId: String) {
        client.findLabelId(TerminalLabels.PROCESSING)?.let {
            client.applyLabels(messageId, addLabels = emptyList(), removeLabels = listOf(it))
        }
    }
}
