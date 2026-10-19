package com.jd.jobbot.templates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AlertTemplatesTest {
    @TempDir lateinit var dir: Path

    private fun vectors(): JsonObject {
        var d: Path? = Paths.get("").toAbsolutePath()
        while (d != null) {
            d.resolve("docker/jobbot/contract/alert_template_vectors.json").takeIf { Files.exists(it) }
                ?.let { return Json.parseToJsonElement(Files.readString(it)).jsonObject }
            d = d.parent
        }
        error("alert_template_vectors.json not found")
    }

    @TestFactory
    fun `shared contract with the notifier`(): List<DynamicTest> {
        val v = vectors()
        assertEquals(AlertTemplates.PLACEHOLDERS, v["placeholders"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(AlertTemplates.MAX_LENGTH, v["max_length"]!!.jsonPrimitive.int)
        assertEquals(AlertTemplates.ALLOWED_TAGS, v["allowed_tags"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val valid = v["valid"]!!.jsonArray.map { t -> DynamicTest.dynamicTest("valid") { assertNull(AlertTemplates.validate(t.jsonPrimitive.content)) } }
        val invalid = v["invalid"]!!.jsonArray.map { c ->
            DynamicTest.dynamicTest("invalid: ${c.jsonObject["why"]!!.jsonPrimitive.content}") {
                assertNotNull(AlertTemplates.validate(c.jsonObject["template"]!!.jsonPrimitive.content))
            }
        }
        val render = v["render"]!!.jsonArray.mapIndexed { i, c ->
            DynamicTest.dynamicTest("render $i") {
                val e = c.jsonObject["event"]!!.jsonObject
                fun s(k: String) = (e[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                fun list(k: String) = (e[k] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                    val values = AlertTemplates.Values(s("company")!!, s("title")!!, s("score")!!, s("action")!!, s("ref")!!, s("job_url"), s("report_url"),
                        location = s("location"), remotePolicy = s("remote_policy"), salary = s("salary"), source = s("source"),
                        strengths = list("strengths"), gaps = list("gaps"))
                assertEquals(c.jsonObject["expected"]!!.jsonPrimitive.content, AlertTemplates.render(c.jsonObject["template"]!!.jsonPrimitive.content, values))
            }
        }
        return valid + invalid + render
    }

    @Test
    fun `the built-in format is itself valid`() {
        assertNull(AlertTemplates.validate(AlertTemplates.BUILT_IN))
    }

    @Test
    fun `update keeps the previous version and revert restores it`() {
        val t = AlertTemplates(dir.resolve("high-fit.html"))
        assertNull(t.current())
        assertNull(t.update("A {ref}"))
        assertNull(t.update("B {ref}"))
        assertEquals("B {ref}", t.current())
        assertEquals("Reverted to the previous template.", t.revert())
        assertEquals("A {ref}", t.current())
    }

    @Test
    fun `an invalid template is never written`() {
        val t = AlertTemplates(dir.resolve("high-fit.html"))
        assertNull(t.update("A {ref}"))
        assertNotNull(t.update("<a href=x>{ref}</a>"))
        assertEquals("A {ref}", t.current())
    }

    @Test
    fun `reverting the first template returns to the built-in format`() {
        val t = AlertTemplates(dir.resolve("high-fit.html"))
        t.update("A {ref}")
        assertEquals("Reverted to the built-in format.", t.revert())
        assertNull(t.current())
    }
}
