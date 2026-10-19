package com.jd.pipeline.client

/** Raised for an LLM/provider failure that is safe to retry from the durable bridge queue. */
open class TransientLlmFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Node caught a TransientLlmFailure but still needs to return a JDState (non-throwing nodes like
 * ScoreFitNode). Wrapping keeps isRetryable true so the processor handler can classify it, and
 * the original error message survives inside the transformer so downstream logs remain correct.
 */
class RetryableLlmError(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * Keeps retry classification at the processor/LLM boundary rather than treating every pipeline
 * exception as transient.  Wrapped exceptions are inspected because node frameworks commonly add
 * context while preserving the underlying provider failure.
 */
object TransientFailureClassifier {
    fun isRetryable(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .take(12)
        .any { cause ->
            cause is TransientLlmFailure || cause.message.orEmpty().let(::isRetryableMessage)
        }

    internal fun isRetryableMessage(message: String): Boolean {
        val normalized = message.lowercase()
        return Regex("\\bhttp\\s+(429|5\\d\\d)\\b").containsMatchIn(normalized) ||
            "request timed out" in normalized ||
            "timed out" in normalized ||
            "hard timeout" in normalized ||
            "timeout" in normalized && "llm" in normalized ||
            BROWSER_OUTAGE_MARKERS.any { it in normalized }
    }

    /**
     * The scraping browser (Steel) being down or losing its session mid-page. That is an outage
     * of ours, not a verdict on the job page, so the next attempt can succeed.
     */
    private val BROWSER_OUTAGE_MARKERS = listOf(
        "steel browser not available",
        "target page, context or browser has been closed",
    )
}