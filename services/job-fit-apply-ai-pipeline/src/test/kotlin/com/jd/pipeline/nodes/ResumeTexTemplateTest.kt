package com.jd.pipeline.nodes

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Guard tests for the shipped LaTeX résumé template (resume_template.tex.jinja).
 *
 * RenderResumePdfNodeTest stubs out jinja2 + tectonic, so nothing in the Kotlin suite
 * actually compiles the template — a layout regression there is invisible to CI. These
 * tests instead assert the preamble invariants that a real compile depends on.
 *
 * Both invariants come from a real bug: ragged2e's \RaggedRight uses a *finite* \rightskip
 * stretch, so with hyphenation disabled ([none]{hyphenat}) TeX accepts overfull lines rather
 * than breaking early — body text ran up to 41pt (~0.57in) into the right margin. `\hfuzz=1in`
 * then suppressed all 32 overfull warnings, so the build stayed silent. Plain \raggedright
 * uses infinite stretch and can never overfull.
 */
class ResumeTexTemplateTest {

    private val template: String =
        javaClass.getResourceAsStream("/resume/resume_template.tex.jinja")!!
            .readBytes().decodeToString()

    /** Strip full-line and trailing LaTeX comments so prose in comments can't satisfy an assertion. */
    private val templateCode: String =
        template.lineSequence()
            .map { line -> Regex("(?<!\\\\)%.*$").replace(line, "") }
            .joinToString("\n")

    @Test
    fun `body uses plain raggedright, not ragged2e RaggedRight`() {
        assertTrue(
            Regex("""\\raggedright\b""").containsMatchIn(templateCode),
            "template should set plain \\raggedright in the document body",
        )
        assertFalse(
            Regex("""\\RaggedRight\b""").containsMatchIn(templateCode),
            "ragged2e's \\RaggedRight has finite \\rightskip stretch and overfulls into the " +
                "right margin when hyphenation is disabled — use plain \\raggedright",
        )
    }

    @Test
    fun `ragged2e is not pulled in`() {
        assertFalse(
            templateCode.contains("ragged2e"),
            "ragged2e was only needed for \\RaggedRight; dropping it keeps \\RaggedRight " +
                "from being reintroduced by accident",
        )
    }

    @Test
    fun `hfuzz stays small enough that real overflow still warns`() {
        val match = Regex("""\\hfuzz\s*=\s*([0-9.]+)\s*(pt|in|cm|mm|em)""").find(templateCode)
        assertNotNull(match, "expected an \\hfuzz setting in the template preamble")

        val value = match!!.groupValues[1].toDouble()
        val points = when (val unit = match.groupValues[2]) {
            "pt" -> value
            "in" -> value * 72.27
            "cm" -> value * 28.45
            "mm" -> value * 2.845
            "em" -> value * 10.0 // 10pt base font
            else -> fail("unhandled \\hfuzz unit: $unit")
        }

        assertTrue(
            points <= MAX_HFUZZ_POINTS,
            "\\hfuzz is ${"%.1f".format(points)}pt — large values silence overfull-hbox " +
                "warnings and let text run into the margin unnoticed (must be <= ${MAX_HFUZZ_POINTS}pt)",
        )
    }

    @Test
    fun `hyphenation stays disabled to match the HTML preview wrapping`() {
        assertTrue(
            templateCode.contains("""\usepackage[none]{hyphenat}"""),
            "hyphenation is intentionally off so the PDF wraps like the HTML preview; if this " +
                "changes, re-check the \\raggedright/\\hfuzz invariants above",
        )
    }

    private companion object {
        /** A few points of slack is normal typesetting tolerance; an inch hides real bugs. */
        const val MAX_HFUZZ_POINTS = 5.0
    }
}
