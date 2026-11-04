package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.VendoredTestFiles
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §16.2: "Implementations MUST surface `OPEN-4` through `OPEN-7` as configuration or as an explicit
 * "unsupported" state, never as a silent default, and MUST document the value used." [Capabilities.OPEN_ITEMS]
 * is that surface.
 *
 * [openRange] reads the range out of that sentence and [openHeadings] the `#### `OPEN-n`` headings under
 * §16.2; [problems] holds a list of ids to both. Every helper takes the specification as text, so each
 * control runs on text built here from the live specification.
 */
class OpenItemsTest {

    internal companion object {

        const val HEADING: String = "### 16.2 Still open"

        private val RANGE = Regex("""MUST surface `OPEN-(\d+)` through `OPEN-(\d+)`""")

        private val OPEN_HEADING = Regex("""^#### `(OPEN-\d+)`""")

        private val NUMBERED_HEADING = Regex("""^#{2,3} (\d+(?:\.\d+)?)\.? """)

        /** The lines of [spec]'s section [number] (`9.4`, `16.2`), up to the next `##` or `###` heading. Exactly one or it throws. */
        fun section(spec: String, number: String): List<String> {
            val lines = spec.lines()
            val starts = lines.indices.filter { NUMBERED_HEADING.find(lines[it])?.groupValues?.get(1) == number }
            require(starts.size == 1) { "expected one §$number heading, found ${starts.size}" }
            return lines.drop(starts.single() + 1).takeWhile { !it.startsWith("## ") && !it.startsWith("### ") }
        }

        /** The ids §16.2's MUST-surface sentence names, from the first to the last inclusive. One such sentence or it throws. */
        fun openRange(spec: String): List<String> {
            require(spec.lines().count { it.trimEnd() == HEADING } == 1) { "expected one \"$HEADING\"" }
            val text = section(spec, "16.2").joinToString(" ").split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
            val matches = RANGE.findAll(text).toList()
            require(matches.size == 1) { "expected one MUST-surface range in §16.2, found ${matches.size}" }
            val (first, last) = matches.single().destructured
            require(first.toInt() <= last.toInt()) { "an empty range: OPEN-$first through OPEN-$last" }
            return (first.toInt()..last.toInt()).map { "OPEN-$it" }
        }

        /** The `OPEN-n` of every `####` heading under §16.2, in order. */
        fun openHeadings(spec: String): List<String> =
            section(spec, "16.2").mapNotNull { OPEN_HEADING.find(it)?.groupValues?.get(1) }

        /** Everything wrong with [ids] as §16.2's surface: not exactly the range, in order, or naming an id with no heading. */
        fun problems(ids: List<String>, spec: String): List<String> {
            val out = mutableListOf<String>()
            val range = openRange(spec)
            if (ids != range) out += "the surface publishes $ids, §16.2's sentence names $range"
            val headings = openHeadings(spec)
            for (id in ids) if (id !in headings) out += "$id has no heading under §16.2"
            return out
        }

        val vendored: String by lazy { VendoredTestFiles.text("spec/NENYA-1.md") }

        val publishedIds: List<String> get() = Capabilities.OPEN_ITEMS.map { it.id }
    }

    @JsName("s16_2_names_open_4_through_open_7")
    @Test
    fun `§16_2 names OPEN-4 through OPEN-7`() {
        assertEquals(listOf("OPEN-4", "OPEN-5", "OPEN-6", "OPEN-7"), openRange(vendored), "the parser control")
        assertTrue(openHeadings(vendored).containsAll(openRange(vendored)), "every id in the range has its heading")
    }

    @JsName("the_surface_publishes_exactly_the_open_items_s16_2_requires")
    @Test
    fun `the surface publishes exactly the open items §16_2 requires`() {
        assertEquals(emptyList(), problems(publishedIds, vendored))
    }

    @JsName("every_open_item_is_unsupported_and_each_section_it_cites_names_it")
    @Test
    fun `every open item is unsupported, and each section it cites names it`() {
        assertEquals(listOf(OpenItemStatus.UNSUPPORTED), OpenItemStatus.entries.toList(), "no value is picked for an open item")
        for (open in Capabilities.OPEN_ITEMS) {
            assertEquals(OpenItemStatus.UNSUPPORTED, open.status, open.id)
            assertTrue(open.note.isNotBlank(), "${open.id} documents no value used")
            assertTrue(open.specSections.isNotEmpty(), "${open.id} cites no section")
            for (number in open.specSections) {
                assertTrue(
                    "`${open.id}`" in section(vendored, number).joinToString("\n"),
                    "${open.id} cites §$number, which does not name it",
                )
            }
        }
        // The anchor discriminates: §9.4 names OPEN-4 and not OPEN-5.
        assertTrue("`OPEN-5`" !in section(vendored, "9.4").joinToString("\n"))
    }

    @JsName("a_range_edited_to_open_8_fails")
    @Test
    fun `a range edited to OPEN-8 fails`() {
        val edited = vendored.replace(Regex("""through\s+`OPEN-7`"""), "through `OPEN-8`")
        assertNotEquals(vendored, edited, "the control must change the sentence")
        assertEquals(listOf("OPEN-4", "OPEN-5", "OPEN-6", "OPEN-7", "OPEN-8"), openRange(edited))
        assertTrue(problems(publishedIds, edited).isNotEmpty())
    }

    @JsName("an_open_item_removed_from_the_surface_fails")
    @Test
    fun `an open item removed from the surface fails`() {
        assertTrue(problems(publishedIds - "OPEN-5", vendored).isNotEmpty())
        assertTrue(problems(publishedIds.reversed(), vendored).isNotEmpty(), "§16.2's order")
    }

    @JsName("an_id_with_no_heading_under_s16_2_fails")
    @Test
    fun `an id with no heading under §16_2 fails`() {
        val edited = vendored.replace("#### `OPEN-6`", "#### Reputation")
        assertNotEquals(vendored, edited, "the control must change the heading")
        assertEquals(listOf("OPEN-6 has no heading under §16.2"), problems(publishedIds, edited))
    }

    @JsName("a_s16_2_the_parser_cannot_read_is_refused_never_guessed")
    @Test
    fun `a §16_2 the parser cannot read is refused, never guessed`() {
        assertFailsWith<IllegalArgumentException>("no MUST surface sentence") {
            openRange(vendored.replace("MUST surface `OPEN-4`", "should mention `OPEN-4`"))
        }
        assertFailsWith<IllegalArgumentException>("no §16.2") { openRange(vendored.replace(HEADING, "### Still open")) }
        assertFailsWith<IllegalArgumentException>("two §16.2s") { openRange(vendored.replace(HEADING, "$HEADING\n\n$HEADING")) }
    }
}
