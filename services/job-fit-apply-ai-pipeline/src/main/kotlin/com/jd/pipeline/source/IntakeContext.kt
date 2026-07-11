package com.jd.pipeline.source

/**
 * Where a JDState came from. The pipeline reads only the marker; nodes that
 * care about email-specific fields downcast to IntakeContext.Email.
 */
sealed interface IntakeContext {
    val provenance: String

    data class Email(
        val emailId: String,
        val from: String,
        val subject: String,
        val rawBody: String,
        val htmlBody: String,
        val isRecruiter: Boolean,
        val isDigest: Boolean,
        val isInlineDigest: Boolean,
    ) : IntakeContext {
        override val provenance: String = "email"
    }

    data class Api(
        val board: String,
    ) : IntakeContext {
        override val provenance: String = "jsearch"
    }

    data class Synthetic(
        val label: String,
    ) : IntakeContext {
        override val provenance: String = "test"
    }
}
