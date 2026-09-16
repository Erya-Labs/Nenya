package dev.eryalabs.nenya.channel

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §7.4's vocabulary, held **equal** to the document rather than to the hand that modelled it.
 *
 * The same anchor `TagVocabularyTest` puts under §5.3's table and `TransitionTableTest` puts under
 * §11.2's: the rumor kinds, the `type` values and the required tag names are parsed out of
 * `spec/NENYA-1.md` at test time and compared in **both directions**. Containment would pass over a
 * model carrying two of the six types; equality cannot.
 *
 * Every count below is derived from the parse. Nothing here transcribes a number from the task or
 * from the prose — and where this file's own expectations and §7.4 disagree, §7.4 is the authority.
 */
class RumorVocabularyTest {

    /**
     * The parser control, run before anything consumes it: §7.4's two tables are adjacent inside one
     * section, and a parser that found the same one twice would make every equality below a
     * statement about the wrong table.
     */
    @JsName("s7_4_parses_to_two_different_tables_and_a_required_tag_block")
    @Test
    fun `§7-4 parses to two different tables and a required-tag block`() {
        val kinds = Section74.rumorKindRows
        val types = Section74.typeRows

        assertTrue(kinds.isNotEmpty(), "§7.4's rumor-kind table parsed to zero rows in ${Section74.specPath()}")
        assertTrue(types.isNotEmpty(), "§7.4's `type` table parsed to zero rows in ${Section74.specPath()}")
        assertEquals(
            RUMOR_KIND_COLUMNS,
            kinds.first().cells.size,
            "§7.4's rumor-kind table has two columns; this parse found ${kinds.first().cells.size}",
        )
        assertEquals(
            TYPE_COLUMNS,
            types.first().cells.size,
            "§7.4's `type` table has four columns; this parse found ${types.first().cells.size}",
        )
        assertNotEquals(
            kinds.first().cells.size,
            types.first().cells.size,
            "the two parses returned the same column count, so one of them found the other's table",
        )
        assertTrue(
            Section74.requiredTagNames.isNotEmpty(),
            "§7.4's fenced required-tags block parsed to zero names in ${Section74.specPath()}",
        )
    }

    /** The modelled rumor kinds are §7.4's first table, equal in both directions. */
    @JsName("the_modelled_rumor_kinds_are_exactly_the_ones_s7_4_tabulates")
    @Test
    fun `the modelled rumor kinds are exactly the ones §7-4 tabulates`() {
        val parsed = Section74.rumorKinds().toSet()
        val modelled = RumorKind.entries.map { it.kind }.toSet()

        assertTrue(parsed.isNotEmpty(), "an empty parse would make this equality \"empty equals empty\"")
        assertTrue(modelled.isNotEmpty())
        assertEquals(
            parsed,
            modelled,
            "§7.4's rumor-kind table in ${Section74.specPath()} and RumorKind must name the same " +
                "kinds — not a subset, which would silently ignore a message shape, and not a " +
                "superset, which would read one §7.4 does not define",
        )
    }

    /** The modelled `type` values are §7.4's second table, equal in both directions. */
    @JsName("the_modelled_type_values_are_exactly_the_ones_s7_4_tabulates")
    @Test
    fun `the modelled type values are exactly the ones §7-4 tabulates`() {
        val parsed = Section74.types().toSet()
        val modelled = OrderMessageKind.entries.mapNotNull { it.type }.toSet()

        assertTrue(parsed.isNotEmpty(), "an empty parse would make this equality \"empty equals empty\"")
        assertEquals(
            parsed,
            modelled,
            "§7.4's `type` table in ${Section74.specPath()} and OrderMessageKind must name the same " +
                "values; containment in either direction passes over a model carrying two of them",
        )
        // Derived from the parse rather than transcribed: the constants and the rows are the same
        // count only if no two constants share a number and none was dropped.
        assertEquals(parsed.size, modelled.size)
        assertEquals(Section74.types(), Section74.types().sorted(), "§7.4 tabulates its types in order")
    }

    /** The required set is §7.4's fenced block, comments stripped — equal, and in its order. */
    @JsName("the_required_tag_names_are_exactly_s7_4_s_fenced_block")
    @Test
    fun `the required tag names are exactly §7-4's fenced block`() {
        val parsed = Section74.requiredTagNames

        assertEquals(
            parsed,
            AttributedRumor.requiredTags(),
            "§7.4's required-tags block in ${Section74.specPath()} names $parsed; the decoder " +
                "enforces ${AttributedRumor.requiredTags()}",
        )
        assertEquals(parsed.toSet(), AttributedRumor.requiredTags().toSet())
        assertEquals(parsed.size, parsed.toSet().size, "§7.4 names each required tag once")
    }

    /**
     * The `type=4` row's Sender cell is an em dash, U+2014 — the trap that makes a careless parse
     * read the row as short, or an ASCII transcription read as a different string.
     */
    @JsName("the_reserved_type_s_sender_cell_is_an_em_dash_and_not_ascii")
    @Test
    fun `the reserved type's sender cell is an em dash and not ASCII`() {
        val reserved = OrderMessageKind.SHIPPING.type
            ?: throw AssertionError("§7.4's reserved type must carry a number")
        val row = Section74.typeRow(reserved)
        val sender = row.cells[SENDER_COLUMN]

        assertEquals(EM_DASH, sender, "line ${row.specLine} of ${Section74.specPath()}")
        assertTrue(sender.isNotEmpty(), "an empty cell would mean the row parsed one column short")
        assertTrue(sender.any { it.code > 0x7F }, "the cell is U+2014, not the ASCII hyphen")
    }

    /**
     * §7.4's sink is a treatment and not a value: it carries no `type` number, it is not in the
     * parsed set, and nothing in this package can emit one.
     */
    @JsName("the_unknown_sink_carries_no_wire_value_and_cannot_be_emitted")
    @Test
    fun `the unknown sink carries no wire value and cannot be emitted`() {
        assertNull(OrderMessageKind.UNKNOWN.type, "a sentinel number would be a `type` nobody assigned")
        assertFalse(OrderMessageKind.UNKNOWN in Section74.types().map { OrderMessageKind.of(it) })

        val refused = assertFailsWith<ChannelException> { ChannelTags.type(OrderMessageKind.UNKNOWN) }
        assertEquals(ChannelRejection.UNKNOWN_IS_NOT_EMITTABLE, refused.reason)
    }

    /**
     * §7.4 reserves `type=4`: Nenya v1 MUST NOT emit it. Readable — `ChannelCodecTest` proves that
     * — and refused here on the way out.
     */
    @JsName("the_reserved_type_is_refused_on_write")
    @Test
    fun `the reserved type is refused on write`() {
        val refused = assertFailsWith<ChannelException> { ChannelTags.type(OrderMessageKind.SHIPPING) }

        assertEquals(ChannelRejection.RESERVED_TYPE, refused.reason)
        assertEquals(ChannelTags.TYPE, refused.tag)
        // And every other type is emittable, so the refusal above is about `type=4` and not about
        // a writer that refuses everything.
        for (message in OrderMessageKind.entries.filter { it.emittable }) {
            assertEquals(listOf(ChannelTags.TYPE, message.type.toString()), ChannelTags.type(message))
        }
    }

    /** §7.4's own two flags, read off the document rather than off this package's opinion. */
    @JsName("only_the_private_bid_carries_no_order_id_and_only_it_and_the_commitment_are_version_disambiguated")
    @Test
    fun `only the private bid carries no order id, and only it and the commitment are version-disambiguated`() {
        assertEquals(
            setOf(OrderMessageKind.PRIVATE_BID),
            OrderMessageKind.entries.filterNot { it.carriesOrderId }.toSet(),
            "§7.4: `type=6` is the **only** `type` that carries no `order` tag",
        )
        assertEquals(
            setOf(OrderMessageKind.DELIVERY_COMMITMENT, OrderMessageKind.PRIVATE_BID),
            OrderMessageKind.entries.filter { it.versionDisambiguated }.toSet(),
            "§7.4's collision rule is stated over `type=5` and `type=6` and nothing else",
        )
        assertEquals(
            setOf(RumorKind.CHAT),
            RumorKind.entries.filterNot { it.carriesRequiredTags }.toSet(),
            "§7.4 puts `kind:14` outside the required-tag rule and nothing else",
        )
    }

    /**
     * §7.2's mode, as a shape rather than as a promise: there is one constant, so
     * "never as signature-verified" is not something this package can accidentally report.
     */
    @JsName("attribution_has_no_signature_verified_constant_to_return")
    @Test
    fun `attribution has no signature-verified constant to return`() {
        assertEquals(1, Attribution.entries.size, "a second constant is a new evidence claim")
        assertEquals(Attribution.AUTHENTICATED_BY_DECRYPTION, Attribution.entries.single())
        assertTrue(
            Attribution.entries.none { "SIGNATURE" in it.name },
            "§7.2: messages in this mode are reported as authenticated-by-decryption only, never " +
                "as signature-verified, and the way to keep that is to have no constant for it",
        )
    }

    private companion object {

        /** §7.4's first table: `Rumor kind` and `Meaning`. */
        const val RUMOR_KIND_COLUMNS: Int = 2

        /** §7.4's second table: `type`, `Name`, `Sender`, `Nenya use`. */
        const val TYPE_COLUMNS: Int = 4

        /** The `Sender` cell sits third in the `type` table. */
        const val SENDER_COLUMN: Int = 2

        /** U+2014, which is what §7.4 prints in the reserved row's Sender cell. */
        const val EM_DASH: String = "—"
    }
}
