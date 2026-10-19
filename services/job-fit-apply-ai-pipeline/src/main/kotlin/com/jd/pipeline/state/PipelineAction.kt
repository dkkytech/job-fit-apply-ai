package com.jd.pipeline.state

enum class PipelineAction {
    SKIP,
    TAILOR,

    /**
     * A fit worth a look, but below AUTO_TAILOR_MIN_SCORE: scored, tracked and pinged with a Tailor
     * button instead of being tailored up front. Tapping it queues a TAILOR_REQUEST for the job.
     */
    OFFER,

    /**
     * Would have been tailored, but the cloud LLM quota is used up (tailoring needs the strong cloud
     * models). Scored, tracked and pinged; the processor's release sweep queues it as a
     * TAILOR_REQUEST, best fits first, once the quota is back — or it expires after
     * DEFERRED_MAX_AGE_DAYS with a "tailor by hand" alert.
     */
    DEFERRED;

    /** Serialized form used for Supabase + log output. Must match the legacy strings. */
    fun asDbValue(): String = name.lowercase()
}
