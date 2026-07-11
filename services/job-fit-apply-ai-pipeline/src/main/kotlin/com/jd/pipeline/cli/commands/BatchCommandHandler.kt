package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.BatchNotificationService
import com.jd.pipeline.cli.Command
import com.jd.pipeline.cli.CreateDraftReply
import com.jd.pipeline.cli.EmailLabelingServiceImpl
import com.jd.pipeline.cli.ScoredJob
import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.JobStatusDto
import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.state.isDigest
import com.jd.pipeline.state.isInlineDigest
import com.jd.pipeline.state.isRecruiterEmail
import com.jd.pipeline.utils.NodeTimer
import java.io.File
import java.time.Instant

object BatchCommandHandler {
    fun run(
        cmd: Command.Batch,
        gmailTransport: GmailTransport = GmailTransport(),
        ingestionPipeline: IngestionPipeline = IngestionPipeline(),
        bridge: BridgeClient = BridgeClient(),
        labelingService: EmailLabelingServiceImpl = EmailLabelingServiceImpl(),
    ) {
        println("[INFO] Processing batch (max ${cmd.maxEmails} emails)...")
        val batchStartTime = Instant.now()
        NodeTimer.reset()

        try {
            val client = gmailTransport
            val emails = client.fetchJdEmails(cmd.maxEmails, cmd.debug)

            if (emails.isEmpty()) {
                println("[WARN] No emails found matching query.")
                return
            }

            // ── Submit pass ───────────────────────────────────────────────────
            // email state → list of (jobId, isRecruiter, emailIntakeId, company, roleTitle)
            data class Submission(
                val jobId: String,
                val isRecruiterEmail: Boolean,
                val emailId: String,
                val company: String,
                val roleTitle: String,
            )
            val emailSubmissions = mutableMapOf<JDState, List<Submission>>()

            for (emailState in emails) {
                val subject = emailState.emailIntake?.subject?.take(60) ?: ""
                println("\n[Ingesting] $subject")

                val ingState = try {
                    ingestionPipeline.invoke(emailState)
                } catch (e: Exception) {
                    System.err.println("[ingestion] ERROR for $subject: ${e.message}")
                    // Non-job or failed scan — label immediately without polling.
                    labelingService.applyLabeling(emailState.copy(error = e.message ?: "ingestion error"), client)
                    continue
                }

                val emailId = emailState.emailIntake?.emailId ?: continue
                val submissions = mutableListOf<Submission>()

                if (ingState.isDigest || ingState.isInlineDigest) {
                    // Each digest child is submitted as its own job.
                    for (childState in ingState.digestJobs.filter { it.isJobPosting }) {
                        try {
                            val record = ingestionPipeline.toJdRecord(childState)
                            val jobId  = bridge.submit(record)
                            submissions.add(Submission(jobId, false, emailId, record.company ?: "", record.roleTitle ?: ""))
                        } catch (e: Exception) {
                            System.err.println("[submit] Failed digest child for $emailId: ${e.message}")
                        }
                    }
                } else if (ingState.isJobPosting) {
                    try {
                        val record = ingestionPipeline.toJdRecord(ingState, idempotencyKey = emailId)
                        val jobId  = bridge.submit(record)
                        submissions.add(Submission(jobId, ingState.isRecruiterEmail, emailId, record.company ?: "", record.roleTitle ?: ""))
                    } catch (e: Exception) {
                        System.err.println("[submit] Failed submit for $emailId: ${e.message}")
                    }
                } else {
                    // Not a job posting — label immediately.
                    labelingService.applyLabeling(ingState, client)
                    continue
                }

                // Apply JD_Processing label before the polling loop.
                if (submissions.isNotEmpty()) {
                    try {
                        labelingService.applyProcessing(emailId, client)
                    } catch (e: Exception) {
                        System.err.println("[label] applyProcessing failed for $emailId: ${e.message}")
                    }
                }

                emailSubmissions[emailState] = submissions
            }

            // ── Polling pass ──────────────────────────────────────────────────
            var jobs      = 0
            var tailored  = 0
            var skipped   = 0
            var duplicate = 0
            val collectedJobs = mutableListOf<ScoredJob>()

            for ((emailState, submissions) in emailSubmissions) {
                val isDigestEmail = (emailState.emailIntake?.isDigest == true) ||
                                    (emailState.emailIntake?.isInlineDigest == true)

                if (submissions.isEmpty()) {
                    labelingService.applyLabeling(emailState, client)
                    continue
                }

                var anyError    = false
                var draftCreated = false

                for (sub in submissions) {
                    jobs++
                    val finalStatus: JobStatusDto = try {
                        bridge.pollUntilTerminal(sub.jobId)
                    } catch (e: Exception) {
                        System.err.println("[poll] Timeout/error for ${sub.jobId}: ${e.message}")
                        JobStatusDto(job_id = sub.jobId, status = "error", error = e.message)
                    }

                    when {
                        finalStatus.error != null -> { anyError = true; skipped++ }
                        finalStatus.pipeline_action?.uppercase() == "TAILOR" -> tailored++
                        else -> skipped++
                    }
                    collectedJobs += ScoredJob(
                        company        = sub.company,
                        roleTitle      = sub.roleTitle,
                        fitScore       = finalStatus.fit_score,
                        pipelineAction = finalStatus.pipeline_action,
                        error          = finalStatus.error,
                    )
                    if (finalStatus.status == "done" && finalStatus.pipeline_action?.uppercase() == "TAILOR" &&
                        !isDigestEmail && sub.isRecruiterEmail) {
                        duplicate += if (finalStatus.pipeline_action == "skip") 0 else 0 // counted above
                        // Download artifacts and create draft reply for recruiter emails.
                        draftCreated = tryCreateDraft(bridge, sub.jobId, emailState, client)
                    }
                }

                // Apply terminal label based on aggregate result.
                val labelState = emailState.copy(
                    isJobPosting            = true,
                    error                   = if (anyError) "One or more jobs failed" else "",
                    isRecruiterResponseRequired = draftCreated,
                )
                val labelResult = labelingService.applyLabeling(labelState, client)
                when (labelResult.labelApplied) {
                    "Recruiter_Response_Required" -> println("  ↳ Draft reply queued — labeled Recruiter_Response_Required, starred, kept unread")
                    "JD_Not_Found"               -> println("  ↳ JD_Not_Found — labeled, kept in inbox, marked unread")
                }
            }

            println("\n[Batch done] $jobs job(s) — $tailored tailored, $skipped skipped, $duplicate duplicate (bridge deduped separately)")

            BatchNotificationService().notify(
                BatchNotificationService.BatchSummary(
                    emailsProcessed = emails.size,
                    jobs            = jobs,
                    tailored        = tailored,
                    skipped         = skipped,
                    duplicate       = duplicate,
                    startTime       = batchStartTime,
                    scoredJobs      = collectedJobs,
                )
            )

            if (ingestionPipeline.batchLinkedInSessionExpired()) {
                println("[WARN] LinkedIn session expired — re-authenticate Chrome profile to enable LinkedIn scraping")
            }
            val blocked = ingestionPipeline.batchBlockedDomains()
            if (blocked.isNotEmpty()) {
                println("[WARN] Sites that blocked scraping this batch: ${blocked.joinToString(", ")}")
            }

        } catch (e: Exception) {
            System.err.println("[ERROR] ${e.message}")
        }
    }

    private fun tryCreateDraft(
        bridge: BridgeClient,
        jobId: String,
        emailState: JDState,
        @Suppress("UNUSED_PARAMETER") gmailClient: GmailTransport,
    ): Boolean {
        val intake = emailState.emailIntake ?: return false
        if (!intake.isRecruiter) return false

        return try {
            val tmpDir = createTempDir("bridge-artifacts-$jobId")
            val pdfFile = File(tmpDir, "resume.pdf")
            val clFile  = File(tmpDir, "cover_letter.txt")

            try {
                bridge.downloadArtifact(jobId, "resume.pdf", pdfFile)
            } catch (e: Exception) {
                System.err.println("[draft] No resume.pdf for $jobId: ${e.message}")
                return false
            }
            try {
                bridge.downloadArtifact(jobId, "cover_letter.txt", clFile)
            } catch (e: Exception) {
                // Cover letter is optional
            }

            val profile = com.jd.pipeline.state.JDState.loadCandidateProfile()
            val status  = bridge.getStatus(jobId)
            val result  = com.jd.pipeline.source.ProcessingResult(
                pipelineAction = status.pipeline_action ?: "TAILOR",
                fitScore       = status.fit_score ?: 0,
                strengths      = emptyList(),
                isDuplicate    = false,
                outputPath     = null,
                hasCoverLetter = clFile.exists(),
            )
            val draftId = CreateDraftReply.run(intake, result, pdfFile, clFile.takeIf { it.exists() }, profile)
            draftId != null
        } catch (e: Exception) {
            System.err.println("[draft] Failed for $jobId: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(prefix: String): File = kotlin.io.createTempDir(prefix)
}
