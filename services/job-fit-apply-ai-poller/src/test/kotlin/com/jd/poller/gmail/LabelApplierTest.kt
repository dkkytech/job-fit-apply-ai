package com.jd.poller.gmail

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@DisplayName("LabelApplierTest")
class LabelApplierTest {

    private fun gmail() = mock<GmailClient> {
        on { getOrCreateLabel(any()) } doReturn "lbl-id"
        // Return a distinct id per label so clearError vs clearProcessing are distinguishable.
        on { findLabelId(any()) } doAnswer { (it.arguments[0] as String) + "-id" }
    }

    @Test
    @DisplayName("JD_Processed → label, archive, clear JD_Error + Processing; no star/unread")
    fun processed() {
        val g = gmail()
        LabelApplier.apply(g, "m1", TerminalLabels.JD_PROCESSED)

        verify(g).getOrCreateLabel(TerminalLabels.JD_PROCESSED)
        verify(g).labelEmail("m1", "lbl-id")
        verify(g).archiveEmail("m1")
        verify(g).findLabelId(TerminalLabels.JD_ERROR)       // clearErrorLabel
        verify(g).findLabelId(TerminalLabels.PROCESSING)  // clearProcessingLabel
        verify(g, never()).starEmail(any())
        verify(g, never()).markUnread(any())
    }

    @Test
    @DisplayName("Recruiter → label, star, mark-unread, clear error + processing; no archive")
    fun recruiter() {
        val g = gmail()
        LabelApplier.apply(g, "m2", TerminalLabels.RECRUITER)

        verify(g).getOrCreateLabel(TerminalLabels.RECRUITER)
        verify(g).starEmail("m2")
        verify(g).markUnread("m2")
        verify(g).findLabelId(TerminalLabels.JD_ERROR)
        verify(g).findLabelId(TerminalLabels.PROCESSING)
        verify(g, never()).archiveEmail(any())
    }

    @Test
    @DisplayName("JD_Error → label + mark-unread; does NOT clear a JD_Error label")
    fun error() {
        val g = gmail()
        LabelApplier.apply(g, "m3", TerminalLabels.JD_ERROR)

        verify(g).getOrCreateLabel(TerminalLabels.JD_ERROR)
        verify(g).markUnread("m3")
        verify(g, never()).archiveEmail(any())
        verify(g, never()).starEmail(any())
        // JD_Error branch skips clearErrorLabel, but still clears the in-flight label.
        verify(g, never()).findLabelId(TerminalLabels.JD_ERROR)
        verify(g).findLabelId(TerminalLabels.PROCESSING)
    }

    @Test
    @DisplayName("JD_Scrape_Failed → label + mark-unread; does not archive")
    fun scrapeFailed() {
        val g = gmail()
        LabelApplier.apply(g, "m-scrape", TerminalLabels.JD_SCRAPE_FAILED)

        verify(g).getOrCreateLabel(TerminalLabels.JD_SCRAPE_FAILED)
        verify(g).markUnread("m-scrape")
        verify(g, never()).archiveEmail(any())
        verify(g).findLabelId(TerminalLabels.JD_ERROR)
        verify(g).findLabelId(TerminalLabels.PROCESSING)
    }

    @Test
    @DisplayName("JD_Application_Update → label + mark-unread; stays in the inbox, not starred")
    fun applicationUpdate() {
        val g = gmail()
        LabelApplier.apply(g, "m-interview", TerminalLabels.APPLICATION_UPDATE)

        verify(g).getOrCreateLabel(TerminalLabels.APPLICATION_UPDATE)
        verify(g).markUnread("m-interview")
        verify(g, never()).archiveEmail(any())
        verify(g, never()).starEmail(any())
        verify(g).findLabelId(TerminalLabels.JD_ERROR)
        verify(g).findLabelId(TerminalLabels.PROCESSING)
    }

    @Test
    @DisplayName("intake query excludes JD_Application_Update so labelled emails are not re-submitted")
    fun intakeQueryExcludesApplicationUpdate() {
        kotlin.test.assertTrue(
            com.jd.poller.config.PollerConfig.GMAIL_SEARCH_QUERY.contains("-label:JD_Application_Update")
        )
    }

    @Test
    @DisplayName("JD_Processed_Digest → label + archive")
    fun digest() {
        val g = gmail()
        LabelApplier.apply(g, "m4", TerminalLabels.JD_PROCESSED_DIGEST)
        verify(g).getOrCreateLabel(TerminalLabels.JD_PROCESSED_DIGEST)
        verify(g).archiveEmail("m4")
        verify(g, never()).markUnread(any())
    }

    @Test
    @DisplayName("JD_Not_Found → label + mark-unread")
    fun notFound() {
        val g = gmail()
        LabelApplier.apply(g, "m5", TerminalLabels.JD_NOT_FOUND)
        verify(g).getOrCreateLabel(TerminalLabels.JD_NOT_FOUND)
        verify(g).markUnread("m5")
        verify(g, never()).archiveEmail(any())
    }

    @Test
    @DisplayName("always clears the in-flight Processing label")
    fun alwaysClearsProcessing() {
        val g = gmail()
        LabelApplier.apply(g, "m6", TerminalLabels.JD_PROCESSED)
        verify(g).applyLabels(eq("m6"), eq(emptyList()), eq(listOf(TerminalLabels.PROCESSING + "-id")))
    }

    @Test
    @DisplayName("blank label falls back to JD_Not_Found (no unread) so the email exits intake; still clears in-flight state")
    fun blankLabel() {
        val g = gmail()
        LabelApplier.apply(g, "m7", "")
        // Blank = Processor made no decision (not-a-job / digest parent). Must still apply a
        // query-excluding terminal label, or the email loops forever with Processing.
        verify(g).getOrCreateLabel(TerminalLabels.JD_NOT_FOUND)
        verify(g).labelEmail("m7", "lbl-id")
        verify(g, never()).markUnread(any())     // a not-a-job email needs no attention
        verify(g, never()).archiveEmail(any())
        verify(g).findLabelId(TerminalLabels.PROCESSING)  // in-flight label still cleared
    }

    @Test
    @DisplayName("unknown non-blank label is applied verbatim and clears in-flight state")
    fun unknownLabel() {
        val g = gmail()
        LabelApplier.apply(g, "m8", "Some_Custom_Label")
        verify(g).getOrCreateLabel("Some_Custom_Label")
        verify(g).findLabelId(TerminalLabels.PROCESSING)
    }
}
