package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.VendoredTestFiles
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * §13 says "Implementations MUST surface these residual risks", and the library has no screen, so the
 * integration guide must name every one of them for the client to show.
 *
 * [residualRisks] reads the clauses from the vendored specification: the paragraph under [HEADING]
 * that opens [MARKER], whitespace collapsed, ending `.`, its clauses separated by `; `. That the guide's
 * `## What Nenya does not check` bullets are exactly these clauses is checked in jvmTest by
 * `IntegrationGuideTest`, which reads the guide from disk; a common test cannot.
 *
 * [residualRisks] takes the specification as text, so every control runs on text built here from the
 * live specification.
 */
class ResidualRisksTest {

    internal companion object {

        const val HEADING: String = "## 13. Security considerations"

        const val MARKER: String = "Implementations MUST surface these residual risks: "

        /**
         * The residual-risk clauses of [spec]'s §13, in order, whitespace collapsed and the final `.` removed.
         *
         * Refuses rather than guesses: no §13 or two of them, no paragraph opening [MARKER] or two, a
         * paragraph not ending `.`, an empty clause or a repeated one each throws.
         */
        fun residualRisks(spec: String): List<String> {
            val lines = spec.lines()
            val starts = lines.indices.filter { lines[it].trimEnd() == HEADING }
            require(starts.size == 1) { "expected one \"$HEADING\" section, found ${starts.size}" }
            val section = lines.drop(starts.single() + 1).takeWhile { !it.startsWith("## ") }
            val paragraphs = section.joinToString("\n").split(Regex("""\n\s*\n"""))
                .map { it.split(Regex("""\s+""")).filter { word -> word.isNotEmpty() }.joinToString(" ") }
            val sentences = paragraphs.filter { it.startsWith(MARKER) }
            require(sentences.size == 1) { "expected one paragraph opening \"$MARKER\", found ${sentences.size}" }
            val body = sentences.single().removePrefix(MARKER)
            require(body.endsWith(".")) { "the residual risks must end \".\"" }
            val clauses = body.removeSuffix(".").split("; ")
            require(clauses.none { it.isBlank() }) { "an empty clause in the residual risks" }
            require(clauses.size == clauses.toSet().size) { "a clause repeated in the residual risks" }
            return clauses
        }

        val vendored: String by lazy { VendoredTestFiles.text("spec/NENYA-1.md") }
    }

    @JsName("section_13_names_its_five_residual_risks")
    @Test
    fun `section 13 names its five residual risks`() {
        val risks = residualRisks(vendored)
        assertEquals(
            listOf(
                "a paid provider can withhold the key (§11.4)",
                "a buyer can receive the deliverable without paying the fee (§8)",
                "a blob host can disappear between commitment and download",
                "a partitioned relay set can hide an acceptance",
                "a `kind:5` deletion is advisory",
            ),
            risks,
        )
    }

    /** The vendored specification with its residual-risk paragraph's first line passed through [edit], which must change it. */
    private fun withSentence(edit: (String) -> String): String {
        val line = vendored.lines().single { it.startsWith(MARKER) }
        val edited = edit(line)
        assertNotEquals(line, edited, "the control must change the line")
        return vendored.replace(line, edited)
    }

    @JsName("a_clause_added_to_section_13_is_read")
    @Test
    fun `a clause added to section 13 is read`() {
        val added = withSentence { it.replace(MARKER, "${MARKER}a mint can refuse to redeem; ") }
        val risks = residualRisks(added)
        assertEquals(6, risks.size)
        assertNotEquals(residualRisks(vendored).toSet(), risks.toSet())
    }

    @JsName("a_section_13_the_parser_cannot_read_is_refused_never_guessed")
    @Test
    fun `a section 13 the parser cannot read is refused, never guessed`() {
        assertFailsWith<IllegalArgumentException>("no MUST surface sentence") {
            residualRisks(withSentence { it.replace("MUST surface", "should mention") })
        }
        assertFailsWith<IllegalArgumentException>("no §13") { residualRisks(vendored.replace(HEADING, "## 13. Threats")) }
        assertFailsWith<IllegalArgumentException>("two §13s") { residualRisks(vendored.replace(HEADING, "$HEADING\n\n$HEADING")) }
        assertFailsWith<IllegalArgumentException>("no final period") {
            residualRisks(vendored.replace("deletion is advisory.", "deletion is advisory"))
        }
        assertFailsWith<IllegalArgumentException>("an empty clause") {
            residualRisks(withSentence { it.replace(MARKER, "$MARKER; ") })
        }
        assertFailsWith<IllegalArgumentException>("a repeated clause") {
            residualRisks(withSentence { it.replace(MARKER, "${MARKER}a paid provider can withhold the key (§11.4); ") })
        }
    }
}
