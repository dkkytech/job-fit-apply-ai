package com.jd.pipeline.client

import com.jd.pipeline.config.Config

/**
 * A cloud provider refused a call because the account's usage quota is used up (Ollama Cloud's
 * session/weekly limits). Still a [TransientLlmFailure] — so a node without a local fallback keeps
 * today's behaviour (the bridge retries it) — but distinct, so the ingest/score lane can switch to
 * a local model instead of failing every job until the quota resets.
 */
class CloudQuotaExhausted(message: String) : TransientLlmFailure(message)

/**
 * Process-wide view of whether the cloud LLM quota is exhausted. Once a quota refusal is seen,
 * fallback-enabled callers ([FallbackLlmCaller]) skip the cloud entirely until [recheckMs] has
 * passed; the first call after that tries the cloud again (a natural probe), and any successful
 * cloud response marks it healthy. [onExhausted]/[onRecovered] fire once per transition.
 */
class CloudQuota(
    private val recheckMs: Long = Config.CLOUD_QUOTA_RECHECK_MINUTES * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onExhausted: (String) -> Unit = {},
    private val onRecovered: () -> Unit = {},
) {
    @Volatile private var exhaustedUntilMs = 0L
    @Volatile private var exhausted = false

    /** True while callers should not even try the cloud. */
    fun skipCloud(): Boolean = exhausted && clock() < exhaustedUntilMs

    /**
     * True from a quota refusal until the next cloud success — past the recheck window too, since
     * nothing has shown the quota is back. Tailoring is deferred while this holds.
     */
    fun isExhausted(): Boolean = exhausted

    @Synchronized
    fun markExhausted(reason: String) {
        val first = !exhausted
        exhausted = true
        exhaustedUntilMs = clock() + recheckMs
        if (first) onExhausted(reason)
    }

    @Synchronized
    fun markHealthy() {
        if (!exhausted) return
        exhausted = false
        exhaustedUntilMs = 0L
        onRecovered()
    }

    companion object {
        /**
         * Phrases a provider uses for a used-up allowance (Ollama Cloud: "you've reached your weekly
         * usage limit … upgrade"). Deliberately specific: a bare "limit … exceeded" is how an
         * ordinary rate limit reads, and that one is worth retrying.
         */
        private val QUOTA_PHRASES = Regex(
            """\b(quota|usage limits?|usage caps?|weekly|daily|hourly|monthly|session limits?|subscription|credits?|insufficient balance|billing|upgrade)\b""",
            RegexOption.IGNORE_CASE,
        )
        /** Short-term throttling and request errors — retryable or not, never a used-up quota. */
        private val NOT_QUOTA = Regex("""concurren|rate[ -]?limit|too many requests|per (minute|second)|context""", RegexOption.IGNORE_CASE)

        /** Does this HTTP error from a cloud backend mean "your quota is used up"? 402 always does. */
        fun isQuotaResponse(status: Int, body: String): Boolean = when (status) {
            402 -> true
            403, 429 -> QUOTA_PHRASES.containsMatchIn(body) && !NOT_QUOTA.containsMatchIn(body)
            else -> false
        }

        /** The processor's shared instance; alerts once on exhaustion and once on recovery. */
        val shared: CloudQuota by lazy {
            CloudQuota(
                onExhausted = { reason ->
                    System.err.println("[cloud_quota] Cloud LLM quota exhausted — ingest/score fall back to '${Config.LOCAL_FALLBACK_MODEL}': $reason")
                    runCatching {
                        AlertService().send(
                            Alert(
                                Severity.WARN, "Cloud LLM quota exhausted",
                                if (Config.LOCAL_FALLBACK_MODEL.isNotBlank())
                                    "Scanning and scoring continue on the local model ${Config.LOCAL_FALLBACK_MODEL}. Tailoring needs the cloud " +
                                        "models: fits are deferred and tailored best-first once the quota resets. " +
                                        "Rechecking every ${Config.CLOUD_QUOTA_RECHECK_MINUTES} min."
                                else "No LOCAL_FALLBACK_MODEL is set, so jobs fail until the quota resets.",
                            ),
                            dedupKey = "cloud-quota-exhausted",
                        )
                    }
                },
                onRecovered = {
                    System.err.println("[cloud_quota] Cloud LLM quota available again")
                    runCatching {
                        AlertService().send(Alert(Severity.INFO, "Cloud LLM quota available again", "Scoring and tailoring are back on the cloud models; deferred fits are being tailored, best first."))
                    }
                },
            )
        }
    }
}

/**
 * Cloud first, local on quota exhaustion — for the ingest/score lane only (scan, digest, scrape,
 * score_fit), where a mid-size local model is good enough and the alternative is no intake at all.
 * Tailoring nodes never get this wrapper: they need the strong cloud models.
 */
class FallbackLlmCaller(
    private val primary: LlmCaller,
    private val fallback: LlmCaller,
    private val quota: CloudQuota = CloudQuota.shared,
    private val label: String = "",
) : LlmCaller {
    override fun call(prompt: String): String {
        if (quota.skipCloud()) return fallback.call(prompt)
        return try {
            primary.call(prompt)
        } catch (e: CloudQuotaExhausted) {
            quota.markExhausted(e.message ?: "quota exhausted")
            System.err.println("[llm_client] $label: cloud quota exhausted — using the local fallback model")
            fallback.call(prompt)
        }
    }
}
