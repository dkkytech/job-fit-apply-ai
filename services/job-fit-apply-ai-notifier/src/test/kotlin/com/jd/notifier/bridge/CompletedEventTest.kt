package com.jd.notifier.bridge

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@DisplayName("CompletedEvent (construction, defaults, field mapping)")
class CompletedEventTest {

    @Test
    @DisplayName("optional fields default to null/blank/zero")
    fun defaults() {
        val e = CompletedEvent(jobId = "j1")
        assertEquals("j1", e.jobId)
        assertEquals(0L, e.completedSeq)
        assertEquals("", e.status)
        assertNull(e.company)
        assertNull(e.roleTitle)
        assertNull(e.fitScore)
        assertNull(e.pipelineAction)
        assertNull(e.jobUrl)
        assertNull(e.artifactUrl)
        assertNull(e.error)
    }

    @Test
    @DisplayName("all fields are captured as constructed")
    fun fullConstruction() {
        val e = CompletedEvent(
            jobId = "j2", completedSeq = 42L, status = "done", company = "Acme",
            roleTitle = "SDET", fitScore = 91, pipelineAction = "tailor",
            jobUrl = "https://acme.co/j", artifactUrl = "http://markserv/x", error = null,
        )
        assertEquals("j2", e.jobId)
        assertEquals(42L, e.completedSeq)
        assertEquals("done", e.status)
        assertEquals("Acme", e.company)
        assertEquals("SDET", e.roleTitle)
        assertEquals(91, e.fitScore)
        assertEquals("tailor", e.pipelineAction)
        assertEquals("https://acme.co/j", e.jobUrl)
        assertEquals("http://markserv/x", e.artifactUrl)
    }

    @Test
    @DisplayName("equals/hashCode are structural (data class)")
    fun structuralEquality() {
        val a = CompletedEvent(jobId = "j3", completedSeq = 1L, company = "Acme")
        val b = CompletedEvent(jobId = "j3", completedSeq = 1L, company = "Acme")
        val c = CompletedEvent(jobId = "j3", completedSeq = 2L, company = "Acme")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    @DisplayName("copy() overrides only the given fields")
    fun copySemantics() {
        val original = CompletedEvent(jobId = "j4", completedSeq = 5L, company = "Acme", fitScore = 10)
        val updated = original.copy(fitScore = 99)
        assertEquals("j4", updated.jobId)
        assertEquals(5L, updated.completedSeq)
        assertEquals("Acme", updated.company)
        assertEquals(99, updated.fitScore)
        assertEquals(10, original.fitScore, "original unchanged")
    }

    @Test
    @DisplayName("toString includes key identifying fields")
    fun toStringIncludesFields() {
        val e = CompletedEvent(jobId = "j5", completedSeq = 7L, company = "Acme")
        val s = e.toString()
        assertEquals(true, s.contains("j5"))
        assertEquals(true, s.contains("Acme"))
    }

    @Test
    @DisplayName("action fields default to no email, not a recruiter, no label")
    fun actionFieldDefaults() {
        val e = CompletedEvent(jobId = "j1")
        assertNull(e.messageId)
        assertEquals(false, e.isRecruiter)
        assertNull(e.terminalLabel)
    }

    @Test
    @DisplayName("is_recruiter, message_id and terminal_label map from the bridge's wire names")
    fun actionFieldsFromWire() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val e = mapper.readValue(
            """{"job_id":"j","completed_seq":5,"message_id":"m1","is_recruiter":true,"terminal_label":"Recruiter_Response_Required"}""",
            CompletedEvent::class.java,
        )
        assertEquals("m1", e.messageId)
        assertEquals(true, e.isRecruiter)
        assertEquals("Recruiter_Response_Required", e.terminalLabel)
    }
}
