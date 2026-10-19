package com.jd.jobbot.gmail

import com.jd.jobbot.support.FakeHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Paths
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GmailClientTest {
    private lateinit var api: FakeHttp
    private var tokens = 0

    private val auth = object : GmailAuth(Paths.get("/x"), Paths.get("/y")) {
        override fun token(forceRefresh: Boolean): String {
            if (forceRefresh || tokens == 0) tokens++
            return "tok-$tokens"
        }
    }

    @BeforeTest fun start() { api = FakeHttp() }
    @AfterTest fun stop() = api.close()

    private fun client() = GmailClient(auth, baseUrl = api.url)

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test
    fun `labels reads labelIds with the bearer token`() {
        api.on("GET /messages/m1") { FakeHttp.Resp(200, """{"id":"m1","labelIds":["INBOX","STARRED"]}""") }
        assertEquals(listOf("INBOX", "STARRED"), client().labels("m1"))
        assertEquals("Bearer tok-1", api.requests.single().headers["authorization"])
    }

    @Test
    fun `modify sends exactly the labels to add and remove`() {
        api.on("POST /messages/m1/modify") { FakeHttp.Resp(200, """{"id":"m1","labelIds":["STARRED"]}""") }
        assertEquals(listOf("STARRED"), client().modify("m1", remove = listOf("INBOX")))
        val body = Json.parseToJsonElement(api.requests.single().body).jsonObject
        assertEquals(listOf("INBOX"), body["removeLabelIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptyList(), body["addLabelIds"]!!.jsonArray.toList())
    }

    @Test
    fun `trash and spam can never be applied`() {
        assertFailsWith<IllegalArgumentException> { client().modify("m1", add = listOf("TRASH")) }
        assertFailsWith<IllegalArgumentException> { client().modify("m1", remove = listOf("SPAM")) }
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `a 401 retries once with a fresh token`() {
        var calls = 0
        api.on("GET /messages/m1") { calls++; if (calls == 1) FakeHttp.Resp(401, "{}") else FakeHttp.Resp(200, """{"labelIds":[]}""") }
        client().labels("m1")
        assertEquals(listOf("Bearer tok-1", "Bearer tok-2"), api.requests.map { it.headers["authorization"] })
    }

    @Test
    fun `other errors surface with their status`() {
        api.on("GET /messages/m1") { FakeHttp.Resp(404, "{}") }
        assertEquals(404, assertFailsWith<GmailClient.GmailException> { client().labels("m1") }.status)
    }

    @Test
    fun `message parses headers and prefers the plain-text part`() {
        api.on("GET /messages/m1") {
            FakeHttp.Resp(200, """
              {"id":"m1","threadId":"t1","labelIds":["INBOX"],"snippet":"hi",
               "payload":{"mimeType":"multipart/alternative",
                 "headers":[{"name":"From","value":"Rec <r@x.com>"},{"name":"Subject","value":"Role"}],
                 "parts":[{"mimeType":"text/html","body":{"data":"${b64("<p>html</p>")}"}},
                          {"mimeType":"text/plain","body":{"data":"${b64("Plain body")}"}}]}}
            """.trimIndent())
        }
        val m = client().message("m1")
        assertEquals("Rec <r@x.com>", m.from)
        assertEquals("Role", m.subject)
        assertEquals("t1", m.threadId)
        assertEquals("Plain body", m.body)
    }

    @Test
    fun `html-only mail is stripped to text`() {
        api.on("GET /messages/m2") {
            FakeHttp.Resp(200, """{"id":"m2","payload":{"mimeType":"text/html","headers":[],"body":{"data":"${b64("<div>Hello<br>there &amp; you</div><script>x()</script>")}"}}}""")
        }
        assertEquals("Hello\nthere & you", client().message("m2").body)
    }

    @Test
    fun `base64url bodies decode padded, unpadded, wrapped or in the standard alphabet`() {
        val text = "Héllo — wörld??>>"
        val std = Base64.getEncoder().encodeToString(text.toByteArray())
        val url = Base64.getUrlEncoder().encodeToString(text.toByteArray())
        listOf(url, url.trimEnd('='), url.chunked(8).joinToString("\r\n"), std).forEach {
            assertEquals(text, String(GmailClient.decodeBase64Url(it)), it)
        }
    }
}
