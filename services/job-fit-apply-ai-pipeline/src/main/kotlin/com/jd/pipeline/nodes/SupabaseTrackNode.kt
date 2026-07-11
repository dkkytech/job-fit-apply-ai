package com.jd.pipeline.nodes

import com.jd.pipeline.client.SupabaseClient
import com.jd.pipeline.config.Config
import com.jd.pipeline.state.JDState
import com.jd.pipeline.state.PipelineAction
import com.jd.pipeline.state.emailIntake

/**
 * Node: supabase_track
 *
 * Inserts the current job into the Supabase "tracks" table.
 * Uses the shared SupabaseClient (Jackson serialization, no hand-rolled toJson).
 * Parses the real row id from the return=representation response and stores it in
 * trackId for downstream reference.
 */
class SupabaseTrackNode : Node<JDState> {

    override fun process(input: JDState): JDState {
        println("[supabase_track] Tracking: ${input.roleTitle} @ ${input.company}")

        if (!SupabaseClient.isConfigured()) {
            return input.copy(error = "SUPABASE_URL not configured in .env")
        }

        return try {
            val record = buildRecord(input)
            val row = SupabaseClient.insert("tracks", record)
            val id = row.path("id").asInt(0).takeIf { it > 0 }

            val trackUrl = "${Config.SUPABASE_PROJECT_URL}/editor?schema=public&table=tracks"
            println("[supabase_track] Tracked successfully (id=${id ?: "unknown"})")

            input.copy(
                isSupabaseTracked = true,
                trackId = id,
                trackUrl = trackUrl
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
