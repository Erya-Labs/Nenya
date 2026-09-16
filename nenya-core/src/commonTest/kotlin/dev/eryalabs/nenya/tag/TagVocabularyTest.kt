package dev.eryalabs.nenya.tag

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §5.3's table, checked for **completeness** against the specification parsed at test time.
 *
 * This is the same proof `TransitionTableTest` makes for §11.2 and for the same reason. A test
 * that only asserts each modelled row is real still passes over a vocabulary carrying five of the
 * nineteen rows — and every later assertion in this package, and every rule T10 and T11 build on
 * top of it, is then measured against that truncated vocabulary. So the assertion is a **set
 * equality** in both directions: every row §5.3 prints is modelled, and nothing is modelled that
 * §5.3 does not print.
 *
 * The count falls out of the parse rather than being asserted against a header comment the same
 * implementer wrote, which would only prove the file agrees with itself.
 */
class TagVocabularyTest {

    @JsName("the_modelled_vocabulary_equals_the_table_parsed_out_of_section_5_point_3")
    @Test
    fun `the modelled vocabulary equals the table parsed out of section 5 point 3`() {
        val fromSpec = Section53.rows.map { Triple(it.tag, it.cardinality, it.requirement) }.toSet()
        val fromCodec = NenyaTags.ALL.map { Triple(it.name, it.cardinality.token, it.requirement.cell) }.toSet()

        assertEquals(
            fromSpec,
            fromCodec,
            "§5.3's (tag, cardinality, requirement) triples and this codec's must be equal, not " +
                "merely overlapping. Containment in either direction passes over a codec carrying " +
                "five of the rows, and T10 and T11 are measured against whatever this holds. Read " +
                "from ${Section53.specPath()}.",
        )
    }

    /**
     * The non-vacuity floor for the equality above: "empty equals empty" is a set equality too.
     */
    @JsName("the_spec_parse_and_the_codec_are_both_non_empty")
    @Test
    fun `the spec parse and the codec are both non-empty`() {
        assertTrue(Section53.rows.isNotEmpty(), "§5.3's row block parsed to zero rows")
        assertTrue(NenyaTags.ALL.isNotEmpty(), "the codec models no tags at all")
        assertEquals(
            Section53.rows.size,
            NenyaTags.ALL.size,
            "one row in §5.3 for one row here — a duplicate on either side would let the set " +
                "equality pass with different lengths",
        )
        assertEquals(
            NenyaTags.ALL.size,
            NenyaTags.NAMES.size,
            "two rows sharing a name would make every by-name lookup answer for one of them",
        )
    }

    /**
     * The parse's own negative control, and the sharpest one in this file.
     *
     * §5.3's `status` row carries **escaped pipes** inside its Notes cell — `` `active` \| `sold` ``
     * — twice over. A naive `split("|")` mis-columns that row and still produces cells, so every
     * assertion about it then tests the wrong text while the suite stays green. This asserts that
     * the escape-aware splitter recovers exactly six columns *and* that the naive one does not, so
     * the control cannot pass by accident on a row that has no escapes in it.
     */
    @JsName("the_row_parser_recovers_exactly_six_columns_for_the_status_row")
    @Test
    fun `the row parser recovers exactly six columns for the status row`() {
        val statusLine = Section53.block.single { (_, text) -> Section53.cells(text).firstOrNull() == "`status`" }.second

        assertEquals(
            Section53.COLUMNS,
            Section53.cells(statusLine).size,
            "the `status` row must parse to ${Section53.COLUMNS} columns",
        )
        assertNotEquals(
            Section53.COLUMNS,
            Section53.naiveCells(statusLine).size,
            "…and a split on every `|` must NOT, or this control proves nothing about the " +
                "escape handling it exists to check",
        )

        val row = Section53.row("status")
        assertEquals("0–1", row.cardinality, "the Card. cell is the third column, not a fragment of Notes")
        assertEquals("SHOULD", row.requirement)
        assertTrue(
            row.encoding.contains("[\"status\""),
            "the Encoding cell must be the `[\"status\", \"<value>\"]` form; got: ${row.encoding}",
        )
        assertTrue(
            row.notes.contains("active") && row.notes.contains("sold"),
            "the Notes cell must carry the offer vocabulary the escaped pipes live in",
        )
    }

    /** §5.3's Side column, which `item` alone answers `neither` to. */
    @JsName("the_modelled_sides_equal_the_table_s_side_column")
    @Test
    fun `the modelled sides equal the table's Side column`() {
        val fromSpec = Section53.rows.map { it.tag to it.side }.toSet()
        val fromCodec = NenyaTags.ALL.map { it.name to it.side.token }.toSet()

        assertEquals(fromSpec, fromCodec)
        assertEquals(
            setOf("item"),
            NenyaTags.ALL.filter { it.side == TagSide.NEITHER }.map { it.name }.toSet(),
            "§5.3 defines `item` here although it is not a listing tag, because it is the one tag " +
                "§6.1 and §7.5 need that the listing vocabulary does not otherwise supply",
        )
    }

    /**
     * "No encoding" is a case, not a parse failure: `g` and `location` carry an em dash in the
     * Encoding column, because §5.3 forbids emitting either at all and there is nothing to encode.
     */
    @JsName("the_rows_with_no_encoding_are_exactly_the_ones_the_table_prints_an_em_dash_for")
    @Test
    fun `the rows with no encoding are exactly the ones the table prints an em dash for`() {
        val emDashRows = Section53.rows.filter { it.encoding == "—" }.map { it.tag }.toSet()
        val modelledNone = NenyaTags.ALL.filter { it.encoding == TagEncoding.NONE }.map { it.name }.toSet()

        assertEquals(setOf("g", "location"), emDashRows, "read from ${Section53.specPath()}")
        assertEquals(emDashRows, modelledNone)
        for (name in emDashRows) {
            val spec = NenyaTags.byName(name)!!
            assertEquals(TagCardinality.ZERO, spec.cardinality)
            assertEquals(TagRequirement.MUST_NOT, spec.requirement.onRequests)
            assertEquals(TagRequirement.MUST_NOT, spec.requirement.onOffers)
        }
    }

    /**
     * The `alt` row's Requirement cell is conditional prose — `MUST on requests, SHOULD on offers`
     * — so Requirement cannot be a flat RFC-2119 enum. Rounding it to one side loses a MUST; the
     * per-listing-kind model is what the set equality above is actually checking.
     */
    @JsName("the_alt_row_is_conditional_prose_and_is_modelled_per_listing_kind")
    @Test
    fun `the alt row is conditional prose and is modelled per listing kind`() {
        val row = Section53.row("alt")
        assertEquals("MUST on requests, SHOULD on offers", row.requirement, "read from ${Section53.specPath()}")

        val spec = NenyaTags.byName("alt")!!
        assertEquals(row.requirement, spec.requirement.cell)
        assertEquals(TagRequirement.MUST, spec.requirement.on(TagContext.listing(NenyaKind.REQUEST)))
        assertEquals(TagRequirement.SHOULD, spec.requirement.on(TagContext.listing(NenyaKind.OFFER)))
        assertEquals(TagRequirement.SHOULD, spec.requirement.on(TagContext.listing(NenyaKind.OFFER_DRAFT)))
        assertEquals(
            1,
            NenyaTags.ALL.count { it.requirement.onRequests != it.requirement.onOffers },
            "`alt` is the only row whose requirement differs between the two listing sides; if a " +
                "revision adds a second, this model still holds but the claim in its KDoc does not",
        )
    }

    /**
     * The Card. column's two non-ASCII tokens, pinned by code point.
     *
     * The set equality above would already fail on an ASCII `0-1`, but it would fail with a
     * diff a reader has to stare at to see. This says which character it is.
     */
    @JsName("the_cardinality_tokens_carry_the_table_s_own_non_ascii_characters")
    @Test
    fun `the cardinality tokens carry the table's own non-ASCII characters`() {
        assertEquals(
            "0–n",
            Section53.row("image").cardinality,
            "§5.3's Card. column uses U+2013 EN DASH, not an ASCII hyphen",
        )
        assertEquals("≥2", Section53.row("t").cardinality, "and U+2265 for `t`")
        assertEquals("0–1", TagCardinality.ZERO_OR_ONE.token)
        assertEquals("0–n", TagCardinality.ZERO_OR_MORE.token)
        assertEquals("≥2", TagCardinality.TWO_OR_MORE.token)
    }

    /** §4.3's duplicate rule binds exactly the cardinalities §4.3 names: `1` and `0–1`. */
    @JsName("the_duplicate_rule_binds_exactly_the_cardinalities_section_4_point_3_names")
    @Test
    fun `the duplicate rule binds exactly the cardinalities section 4 point 3 names`() {
        assertEquals(
            setOf(TagCardinality.EXACTLY_ONE, TagCardinality.ZERO_OR_ONE),
            TagCardinality.entries.filter { it.singleOccurrence }.toSet(),
            "§4.3: \"For any tag this document marks with cardinality `1` or `0–1`, an event " +
                "carrying more than one occurrence MUST be rejected\"",
        )
    }
}
