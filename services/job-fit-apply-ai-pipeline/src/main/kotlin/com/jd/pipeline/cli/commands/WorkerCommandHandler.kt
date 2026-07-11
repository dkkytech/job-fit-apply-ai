package com.jd.pipeline.cli.commands

import com.jd.pipeline.client.BridgeClient
import com.jd.pipeline.pipeline.ProcessingPipeline
import com.jd.pipeline.source.ProcessingResult
import com.jd.pipeline.state.PipelineAction

object WorkerCommandHandler {

    fun run(
        bridge: BridgeClient = BridgeClient(),
        pipeline: ProcessingPipeline = ProcessingPipeline(),
    ) {
        println("[worker] Starting — polling ${System.getenv("JD_BRIDGE_URL") ?: "http://127.0.0.1:8765"}")

        while (true) {
            val claimed = try {
                bridge.claim()
            } catch (e: Exception) {
                System.err.println("[worker] claim() failed: ${e.message} — retrying in 5s")
                Thread.sleep(5_000)
                continue
            }

            if (claimed == null) {
                Thread.sleep(2_000)
                continue
            }

            println("[worker] Processing job ${claimed.jobId} — ${claimed.jdRecord.roleTitle} @ ${claimed.jdRecord.company}")

            val result: ProcessingResult = try {
                pipeline.invoke(claimed.jdRecord)
            } catch (e: Exception) {
                System.err.println("[worker] pipeline threw for ${claimed.jobId}: ${e.message}")
                ProcessingResult(
                    pipelineAction = PipelineAction.SKIP.name,
                    fitScore       = 0,
                    strengths      = emptyList(),
                    isDuplicate    = false,
                    outputPath     = null,
                    hasCoverLetter = false,
                    error          = e.message,
                )
            }

            try {
                val files = buildList {
                    result.outputPath?.let { dir ->
                        java.io.File(dir).listFiles { f -> f.extension == "pdf" }
                            ?.firstOrNull()
                            ?.let { add(it) }
                        val cl = java.io.File(dir, "cover_letter.txt")
                        if (cl.exists()) add(cl)
                    }
                }
                if (files.isNotEmpty()) bridge.uploadArtifacts(claimed.jobId, files)
                bridge.postResult(claimed.jobId, result)
                println("[worker] Job ${claimed.jobId} complete — ${result.pipelineAction}, score=${result.fitScore}")
            } catch (e: Exception) {
                System.err.println("[worker] Failed to post result for ${claimed.jobId}: ${e.message}")
                runCatching {
                    bridge.postResult(claimed.jobId, result.copy(error = "Failed to post result: ${e.message}"))
                }
            }
        }
    }
}
