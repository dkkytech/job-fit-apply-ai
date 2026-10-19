package com.jd.jobbot.jobs

import com.jd.jobbot.support.FakeBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class JobIdentityTest {
    @Test
    fun `two completions of the same posting share an identity despite tracking params`() {
        val a = FakeBridge.event(1, jobUrl = "https://www.Acme.co/jobs/42/?utm_source=jobalert&utm_medium=email&id=9")
        val b = FakeBridge.event(2, jobUrl = "https://acme.co/jobs/42?id=9&gclid=xyz")
        assertEquals(JobIdentity.of(a), JobIdentity.of(b))
    }

    @Test
    fun `meaningful query parameters still distinguish postings`() {
        val a = FakeBridge.event(1, jobUrl = "https://boards.example/view?jobId=1")
        val b = FakeBridge.event(2, jobUrl = "https://boards.example/view?jobId=2")
        assertNotEquals(JobIdentity.of(a), JobIdentity.of(b))
    }

    @Test
    fun `falls back to the source email, then company and title`() {
        assertEquals("msg:m1", JobIdentity.of(FakeBridge.event(1, jobUrl = null, messageId = "m1")))
        assertEquals("co:acme|staff sdet", JobIdentity.of(FakeBridge.event(1, jobUrl = null)))
    }
}
