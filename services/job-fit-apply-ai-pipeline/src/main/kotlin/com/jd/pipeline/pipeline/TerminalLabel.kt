package com.jd.pipeline.pipeline

import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isInlineDigest

/**
 * The Gmail terminal-label DECISION for a finished job, derived from the final state.
 *
 * Phase 1: the Processor computes this and puts it in its result; the Poller (the only
 * Gmail-touching service) APPLIES the label. Extracted from EmailLabelingService so the
 * decision is pure, testable, and Gmail-free. Order matters — first match wins.
 */
object TerminalLabel {
    const val JD_ERROR            = "JD_Error"
    const val JD_SCRAPE_FAILED    = "JD_Scrape_Failed"
    const val RECRUITER           = "Recruiter_Response_Required"
    const val JD_PROCESSED_DIGEST = "JD_Processed_Digest"
    const val JD_NOT_FOUND        = "JD_Not_Found"
    const val JD_PROCESSED        = "JD_Processed"
    const val APPLICATION_UPDATE  = "JD_Application_Update"

    fun forState(state: JDState): String = when {
        state.error.isNotEmpty()               -> JD_ERROR
        state.isApplicationUpdate              -> APPLICATION_UPDATE
        state.isRecruiterResponseRequired      -> RECRUITER
        state.isDigest || state.isInlineDigest -> JD_PROCESSED_DIGEST
        !state.isJobPosting                    -> JD_NOT_FOUND
        else                                   -> JD_PROCESSED
    }
}
