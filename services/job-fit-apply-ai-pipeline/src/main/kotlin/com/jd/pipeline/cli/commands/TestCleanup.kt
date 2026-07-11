package com.jd.pipeline.cli.commands

import com.jd.pipeline.client.SupabaseClient
import com.jd.pipeline.nodes.CheckDuplicateNode

/**
 * Shared cleanup logic for test handlers that write to Supabase.
 * Removes the test record from the tracks table and resets the
 * in-memory duplicate-detection fallback set.
 */
object TestCleanup {
    fun removeTestRecord(emailId: String) {
        if (SupabaseClient.isConfigured()) {
            try {
                SupabaseClient.delete("tracks", "email_id", emailId)
                println("[CLEANUP] Removed test record from tracks table")
            } catch (e: Exception) {
                System.err.println("[CLEANUP] Failed to remove test record: ${e.message}")
            }
        }
        CheckDuplicateNode.resetFallback()
    }
}
