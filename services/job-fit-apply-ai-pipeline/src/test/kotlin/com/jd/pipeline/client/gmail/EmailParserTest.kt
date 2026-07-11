package com.jd.pipeline.client.gmail

import com.google.api.services.gmail.model.Message
import com.google.api.services.gmail.model.MessagePart
import com.google.api.services.gmail.model.MessagePartBody
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("EmailParser")
class EmailParserTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun encode(text: String): String =
        Base64.getUrlEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun simplePart(mimeType: String, body: String): MessagePart = MessagePart().apply {
        this.mimeType = mimeType
        this.body = MessagePartBody().apply { data = encode(body) }
        this.parts = null
    }

    private fun multipartMessage(parts: List<MessagePart>, mimeType: String = "multipart/alternative"): Message {
        val payload = MessagePart().apply {
            this.mimeType = mimeType
            this.body = MessagePartBody()
            this.parts = parts
        }
        return Message().apply { this.payload = payload }
    }

    // ── Plain text extraction ─────────────────────────────────────────────────

    @Nested
    @DisplayName("plain text extraction")
    inner class PlainText {

        @Test
        @DisplayName("extracts plain text from text/plain payload")
        fun extractsPlainText() {
            val msg = Message().apply {
                payload = simplePart("text/plain", "Hello World")
            }
            val result = EmailParser.parse(msg)
            assertEquals("Hello World", result.plainText)
        }

        @Test
        @DisplayName("extracts plain text from multipart/alternative with text/plain first")
        fun extractsFromMultipartAlternative() {
            val msg = multipartMessage(listOf(
                simplePart("text/plain", "Plain text content"),
                simplePart("text/html",  "<p>HTML content</p>"),
            ))
            val result = EmailParser.parse(msg)
            assertEquals("Plain text content", result.plainText)
        }

        @Test
        @DisplayName("falls back to html body as text when no plain part")
        fun fallsBackToHtml() {
            val msg = Message().apply {
                payload = simplePart("text/html", "<b>Job at Acme</b>")
            }
            val result = EmailParser.parse(msg)
            // HTML is cleaned to plain text
            assertTrue(result.plainText.contains("Job at Acme"))
            assertFalse(result.plainText.contains("<b>"))
        }

        @Test
        @DisplayName("returns empty string for null payload")
        fun nullPayload() {
            val msg = Message().apply { payload = null }
            val result = EmailParser.parse(msg)
            assertEquals("", result.plainText)
        }
    }

    // ── HTML body collection ──────────────────────────────────────────────────

    @Nested
    @DisplayName("HTML body collection")
    inner class HtmlBodies {

        @Test
        @DisplayName("collects HTML body from text/html part")
        fun collectsHtmlBody() {
            val msg = Message().apply {
                payload = simplePart("text/html", "<p>Hello</p>")
            }
            val result = EmailParser.parse(msg)
            assertEquals(1, result.htmlBodies.size)
            assertTrue(result.htmlBodies[0].contains("<p>Hello</p>"))
        }

        @Test
        @DisplayName("collects multiple HTML parts from multipart/mixed")
        fun collectsMultipleHtmlParts() {
            val msg = multipartMessage(
                listOf(
                    simplePart("text/html", "<p>Part 1</p>"),
                    simplePart("text/html", "<p>Part 2</p>"),
                ),
                mimeType = "multipart/mixed"
            )
            val result = EmailParser.parse(msg)
            assertEquals(2, result.htmlBodies.size)
        }

        @Test
        @DisplayName("returns empty htmlBodies for pure plain-text message")
        fun noneForPlainText() {
            val msg = Message().apply {
                payload = simplePart("text/plain", "No HTML here")
            }
            val result = EmailParser.parse(msg)
            assertTrue(result.htmlBodies.isEmpty())
        }
    }

    // ── Script extraction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("script extraction")
    inner class ScriptExtraction {

        @Test
        @DisplayName("extracts inline script content")
        fun inlineScript() {
            val html = "<html><script>var x = 1;</script></html>"
            val msg = Message().apply {
                payload = simplePart("text/html", html)
            }
            val result = EmailParser.parse(msg)
            assertEquals(1, result.inlineScripts.size)
            assertEquals("var x = 1;", result.inlineScripts[0])
        }

        @Test
        @DisplayName("extracts external script src URL")
        fun externalScript() {
            val html = """<html><script src="https://cdn.example.com/track.js"></script></html>"""
            val msg = Message().apply {
                payload = simplePart("text/html", html)
            }
            val result = EmailParser.parse(msg)
            assertEquals(1, result.scriptUrls.size)
            assertEquals("https://cdn.example.com/track.js", result.scriptUrls[0])
        }

        @Test
        @DisplayName("returns empty lists when no scripts present")
        fun noScripts() {
            val msg = Message().apply {
                payload = simplePart("text/plain", "No scripts here")
            }
            val result = EmailParser.parse(msg)
            assertTrue(result.inlineScripts.isEmpty())
            assertTrue(result.scriptUrls.isEmpty())
        }
    }

    // ── Part summary ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("part summary")
    inner class PartSummary {

        @Test
        @DisplayName("summary includes mimeType of top-level payload")
        fun includesMimeType() {
            val msg = Message().apply {
                payload = simplePart("text/plain", "Hello")
            }
            val result = EmailParser.parse(msg)
            assertTrue(result.partSummary.contains("text/plain"))
        }

        @Test
        @DisplayName("summary includes child parts for multipart messages")
        fun includesChildParts() {
            val msg = multipartMessage(listOf(
                simplePart("text/plain", "Plain"),
                simplePart("text/html", "<p>Html</p>"),
            ))
            val result = EmailParser.parse(msg)
            assertTrue(result.partSummary.contains("multipart/alternative"))
            assertTrue(result.partSummary.contains("text/plain"))
            assertTrue(result.partSummary.contains("text/html"))
        }
    }
}
