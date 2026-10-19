package com.jd.pipeline.nodes

import com.jd.pipeline.client.GatewayProvider
import com.jd.pipeline.client.SupabaseGateway
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.emailIntake

/**
 * Node: supabase_track
 *
 * Inserts the current job into the Supabase "tracks" table — or, on an on-demand tailor run
 * ([JDState.existingTrackId]), updates the row its OFFER wrote, so the job keeps one row.
 * Uses the shared SupabaseClient (Jackson serialization, no hand-rolled toJson).
 * Parses the real row id from the return=representation response and stores it in
 * trackId for downstream reference.
 */
class SupabaseTrackNode(
    private val supabase: SupabaseGateway = GatewayProvider.active
) : Node<JDState> {

    override fun process(input: JDState): JDState {
        println("[supabase_track] Tracking: ${input.roleTitle} @ ${input.company}")

        if (!supabase.isConfigured()) {
            return input.copy(error = "SUPABASE_URL not configured in .env")
        }

        return try {
            val record = buildRecord(input)
            val updated = input.existingTrackId?.let { supabase.update("tracks", record, "id", it.toString()) }
            // The OFFER's row can be gone (pruned, or tracking failed back then): insert a fresh one.
            val row = updated?.takeIf { it.path("id").asInt(0) > 0 } ?: supabase.insert("tracks", record)
            val id = row.path("id").asInt(0).takeIf { it > 0 }

            println("[supabase_track] Tracked successfully (id=${id ?: "unknown"})")

            input.copy(
                isSupabaseTracked = true,
                trackId = id
            )
        } catch (e: Exception) {
            System.err.println("[supabase_track] ERROR: ${e.message}")
            input.copy(error = "supabase_track: ${e.message}")
        }
    }

    private fun buildRecord(input: JDState): Map<String, Any?> = mapOf(
        "email_id"        to (input.emailIntake?.emailId ?: ""),
        "email_subject"   to (input.emailIntake?.subject ?: ""),
        "company"         to input.company,
        "role_title"      to input.roleTitle,
        "location"        to input.location,
        "job_url"         to input.jobUrl,
        "remote_policy"   to input.remotePolicy,
        "fit_score"       to input.fitScore,
        "pipeline_action" to input.pipelineAction.asDbValue(),
        "tech_stack"      to input.techStack,
        "strengths"       to input.strengths,
        "gaps"            to input.gaps,
        "red_flags"       to input.redFlags,
        "fit_reasoning"   to input.fitReasoning,
        "jd_text"         to input.jdText,
        "output_path"     to input.outputPath,
        "artifact_url"    to input.artifactUrl
    )
}
