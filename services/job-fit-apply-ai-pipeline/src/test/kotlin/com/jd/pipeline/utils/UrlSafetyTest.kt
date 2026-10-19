package com.jd.pipeline.utils

import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlSafetyTest {
    /** Literal IPs resolve without DNS; names map through [names] so the test never touches the network. */
    private fun private(url: String, names: Map<String, String> = emptyMap()) =
        UrlSafety.isPrivateTarget(url) { host -> listOf(InetAddress.getByName(names[host] ?: host)) }

    @Test
    fun `internal hostnames are private without resolving`() {
        listOf("http://localhost:8765/api/jobs", "http://host.docker.internal:11436/v1", "http://metadata.google.internal/computeMetadata/v1",
            "https://bridge.lan/x", "http://printer.local/", "http://app.localhost/", "http://LOCALHOST./").forEach {
            assertTrue(UrlSafety.isPrivateTarget(it) { error("must not resolve $it") }, it)
        }
    }

    @Test
    fun `private, loopback, link-local, tailnet and unique-local addresses are private`() {
        listOf("http://127.0.0.1:8765/", "http://10.0.0.5/", "http://172.16.3.4/", "http://192.168.1.10/", "http://169.254.169.254/latest/meta-data/",
            "http://100.64.1.2/", "http://100.101.102.103:3030/", "http://0.0.0.0/", "http://[::1]/", "http://[fd7a:115c:a1e0::1]/", "http://[fe80::1]/")
            .forEach { assertTrue(private(it), it) }
    }

    @Test
    fun `public addresses and public names are allowed`() {
        listOf("https://8.8.8.8/", "http://100.128.0.1/", "https://[2606:4700::1111]/").forEach { assertFalse(private(it), it) }
        assertFalse(private("https://boards.greenhouse.io/acme/jobs/1", mapOf("boards.greenhouse.io" to "104.18.20.1")))
    }

    @Test
    fun `a public-looking name that resolves into the private network is private`() {
        assertTrue(private("https://jobs.evil.example/x", mapOf("jobs.evil.example" to "10.1.2.3")), "DNS pointing inward")
    }

    @Test
    fun `unresolvable or malformed URLs are not flagged - the fetch fails on its own`() {
        assertFalse(UrlSafety.isPrivateTarget("https://no-such-host.example/") { emptyList() })
        assertFalse(UrlSafety.isPrivateTarget("not a url"))
        assertFalse(UrlSafety.isPrivateTarget(""))
    }
}
