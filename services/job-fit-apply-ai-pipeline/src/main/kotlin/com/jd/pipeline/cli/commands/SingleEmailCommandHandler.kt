package com.jd.pipeline.cli.commands

import com.jd.pipeline.cli.CliOutput
import com.jd.pipeline.cli.Command
import com.jd.pipeline.cli.CreateDraftReply
import com.jd.pipeline.cli.EmailLabelingServiceImpl
import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.client.gmail.GmailTransport
import com.jd.pipeline.pipeline.IngestionPipeline
import com.jd.pipeline.source.ProcessingResult
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.emailIntake
import com.jd.pipeline.state.isRecruiterEmail
import java.io.File

object SingleEmailCommandHandler {
    fun run(cmd: Command.SingleEmail) {
        val emailValue = cmd.subject
        if (emailValue.isEmpty()) {
            println("[ERROR] --email: email subject is null or empty")
            return
        }
        println("[INFO] Fetching email with subject: $emailValue")

        try {
            val client = GmailTransport()
            val emailState = client.fetchEmailBySubject(emailValue, cmd.debug)

            if (emailState == null) {
                println("[ERROR] No email found matching subject: $emailValue")
                return
            }

            val ingestionPipeline = IngestionPipeline()
            val bridge            = BridgeClient()
            val labelingService   = EmailLabelingServiceImpl()
            val emailId           = emailState.emailIntake?.emailId ?: ""

            // 1. Ingestion: scan → scrape → save
            val ingState = try {
                ingestionPipeline.invoke(emailState)
            } catch (e: Exception) {
                System.err.println("[ingestion] ERROR: ${e.message}")
                labelingService.applyLabeling(emailState.copy(error = e.message ?: "ingestion error"), client)
                return
            }

            if (!ingState.isJobPosting) {
                println("  ↳ Not a job posting — skipped")
                labelingService.applyLabeling(ingState, client)
                return
            }

            // 2. Submit to bridge
            val record = ingestionPipeline.toJdRecord(ingState, idempotencyKey = emailId)
            val jobId = try {
                bridge.submit(record)
            } catch (e: Exception) {
                System.err.println("[submit] ERROR: ${e.message}")
                labelingService.applyLabeling(ingState.copy(error = e.message ?: "submit failed"), client)
                return
            }

            // 3. Apply JD_Processing label while worker runs
            runCatching { labelingService.applyProcessing(emailId, client) }
            println("  ↳ Submitted job $jobId — waiting for worker...")

            // 4. Poll until terminal
            val finalStatus = try {
                bridge.pollUntilTerminal(jobId)
            } catch (e: Exception) {
                System.err.println("[poll] Timeout/error for $jobId: ${e.message}")
                labelingService.applyLabeling(ingState.copy(error = e.message ?: "poll timeout"), client)
                return
            }

            println("  ↳ Job $jobId done — ${finalStatus.pipeline_action}, score=${finalStatus.fit_score}")

            // 5. Recruiter: download artifacts and create draft reply
            var draftCreated = false
            if (finalStatus.status == "done" && ingState.isRecruiterEmail) {
                val intake = emailState.emailIntake
                if (intake != null) {
                    try {
                        val tmpDir  = createTempDir("single-email-$jobId")
                        val pdfFile = File(tmpDir, "resume.pdf")
                        val clFile  = File(tmpDir, "cover_letter.txt")
                        bridge.downloadArtifact(jobId, "resume.pdf", pdfFile)
                        runCatching { bridge.downloadArtifact(jobId, "cover_letter.txt", clFile) }
                        val profile = JDState.loadCandidateProfile()
                        val result = ProcessingResult(
                            pipelineAction = finalStatus.pipeline_action ?: "TAILOR",
                            fitScore       = finalStatus.fit_score ?: 0,
                            strengths      = emptyList(),
                            isDuplicate    = false,
                            outputPath     = null,
                            hasCoverLetter = clFile.exists(),
                        )
                        val draftId = CreateDraftReply.run(intake, result, pdfFile, clFile.takeIf { it.exists() }, profile)
                        draftCreated = draftId != null
                    } catch (e: Exception) {
                        System.err.println("[draft] Failed: ${e.message}")
                    }
                }
            }

            // 6. Apply terminal label
            val labelState = ingState.copy(
                error                       = finalStatus.error ?: "",
                isRecruiterResponseRequired = draftCreated,
            )
            val labelResult = labelingService.applyLabeling(labelState, client)

            when (labelResult.labelApplied) {
                "Recruiter_Response_Required" -> println("[INFO] Draft reply queued — labeled Recruiter_Response_Required, starred, kept unread.")
                "JD_Not_Found"               -> println("[INFO] JD_Not_Found — labeled, kept in inbox, marked unread.")
                else                         -> println("[INFO] Labeled and archived.")
            }

        } catch (e: Exception) {
            System.err.println("[ERROR] ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(prefix: String): File = kotlin.io.createTempDir(prefix)
}
