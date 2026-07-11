package com.jd.pipeline.source

enum class IngestionSource { EMAIL, JSEARCH, MANUAL }

data class JdRecord(
    val jdText: String,
    val company: String?,
    val roleTitle: String?,
    val location: String?,
    val jobUrl: String?,
    val source: IngestionSource,
    val idempotencyKey: String? = null,
    val intakeMeta: IntakeContext? = null,
)

data class ProcessingResult(
    val pipelineAction: String,
    val fitScore: Int,
    val strengths: List<String>,
    val isDuplicate: Boolean,
    val outputPath: String?,
    val hasCoverLetter: Boolean,
    val error: String? = null,
)
