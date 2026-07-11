package com.jd.pipeline.testutils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Mock Supabase client for integration testing.
 * Simulates all Supabase database operations without connecting to real DB.
 */
class MockSupabaseClient {

    private val mapper = ObjectMapper()

    // Simulated database state
    private val jobs = mutableListOf<MutableMap<String, Any?>>()
    private val resumeArtifacts = mutableListOf<MutableMap<String, Any?>>()
    private val tracks = mutableListOf<MutableMap<String, Any?>>()

    var insertCalls = mutableListOf<Pair<String, Map<String, Any?>>>()
    var patchCalls = mutableListOf<Triple<String, Map<String, Any?>, String>>()
    var queryCalls = mutableListOf<Pair<String, Map<String, String>>>()

    var shouldFailInsert = false
    var shouldFailPatch = false
    var shouldFailQuery = false
    var failMessage = "Mock failure"

    fun isConfigured(): Boolean = true

    fun reset() {
        jobs.clear()
        resumeArtifacts.clear()
        tracks.clear()
        insertCalls.clear()
        patchCalls.clear()
        queryCalls.clear()
        shouldFailInsert = false
        shouldFailPatch = false
        shouldFailQuery = false
    }

    fun addJob(job: Map<String, Any?>) {
        jobs.add(job.toMutableMap())
    }

    fun addTrack(track: Map<String, Any?>) {
        tracks.add(track.toMutableMap())
    }

    fun insert(table: String, record: Map<String, Any?>): JsonNode {
        if (shouldFailInsert) throw RuntimeException(failMessage)
        insertCalls.add(table to record)

        val id = (100..9999).random()
        val newRecord = record.toMutableMap()
        newRecord["id"] = id

        when (table) {
            "jobs" -> jobs.add(newRecord)
            "resume_artifacts" -> resumeArtifacts.add(newRecord)
            "tracks" -> tracks.add(newRecord)
        }

        return mapper.readTree(mapper.writeValueAsString(newRecord))
    }

    fun patch(table: String, updates: Map<String, Any?>, filterCol: String, filterVal: String) {
        if (shouldFailPatch) throw RuntimeException(failMessage)
        patchCalls.add(Triple(table, updates, "$filterCol=eq.$filterVal"))

        val targetList = when (table) {
            "jobs" -> jobs
            "resume_artifacts" -> resumeArtifacts
            "tracks" -> tracks
            else -> return
        }

        targetList.forEachIndexed { index, record ->
            val encoded = filterVal.replace("%20", " ").replace("%2F", "/")
            if (record[filterCol]?.toString()?.equals(encoded) == true ||
                record[filterCol]?.toString() == encoded) {
                targetList[index] = record.apply { putAll(updates) }
            }
        }
    }

    fun query(table: String, filters: Map<String, String>, select: String = "*", limit: Int = 10): List<JsonNode> {
        if (shouldFailQuery) throw RuntimeException(failMessage)
        queryCalls.add(table to filters)

        val targetList = when (table) {
            "jobs" -> jobs.toList()
            "resume_artifacts" -> resumeArtifacts.toList()
            "tracks" -> tracks.toList()
            else -> emptyList()
        }

        val filtered = targetList.filter { record ->
            filters.all { (col, expr) ->
                val dotIdx = expr.indexOf('.')
                val op = if (dotIdx >= 0) expr.substring(0, dotIdx) else "eq"
                val value = if (dotIdx >= 0) expr.substring(dotIdx + 1) else expr

                val recordValue = record[col]?.toString() ?: ""
                when (op) {
                    "eq" -> recordValue.equals(value, ignoreCase = true)
                    "ilike" -> recordValue.contains(value.replace("%", ""), ignoreCase = true)
                    "gte" -> recordValue >= value
                    else -> recordValue.equals(value, ignoreCase = true)
                }
            }
        }

        return filtered.take(limit).map { mapper.valueToTree(it) }
    }

    fun getJobCount() = jobs.size
    fun getArtifactCount() = resumeArtifacts.size
    fun getTrackCount() = tracks.size

    /**
     * Find existing track by (company, role_title, location) for duplicate detection.
     */
    fun findExistingTrack(company: String, roleTitle: String, location: String): JsonNode? {
        return tracks.find { track ->
            track["company"]?.toString()?.equals(company, ignoreCase = true) == true &&
            track["role_title"]?.toString()?.equals(roleTitle, ignoreCase = true) == true &&
            track["location"]?.toString()?.equals(location, ignoreCase = true) == true
        }?.let { mapper.valueToTree(it) }
    }
}