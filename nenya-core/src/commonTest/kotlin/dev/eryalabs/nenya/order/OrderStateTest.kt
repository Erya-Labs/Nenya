package dev.eryalabs.nenya.order

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * §11.1's vocabulary: the ten canonical tokens, the [OrderState.UNKNOWN] sink, and every way
 * the specification says a reader must *not* behave.
 *
 * The positive half is derived rather than transcribed — [Section11] parses §11.1's own
 * table out of `spec/NENYA-1.md` at test time — so a revision that renamed a state or
 * changed which states are terminal turns this file red instead of leaving a stale copy
 * agreeing with itself.
 *
 * The negative half is the reason this package exists. Four vocabularies overlap this one:
 * §11.1's own reserved tokens, GammaMarkets' status words, Nenya's public listing statuses,
 * and case variants of the ten. `cancelled` belongs to two of them with different meanings,
 * and it is precisely the token where the conflation looks correct and passes a careless
 * test.
 */
class OrderStateTest {

    // ---------------------------------------------------------------- the vocabulary itself

    /**
     * The enum is exhaustive over §11.1's table: no state missing, none invented. Set
     * equality in both directions, against a parse rather than against a list written here.
     */
    @JsName("the_enum_is_exhaustive_over_section_11_1_s_ten_states")
    @Test
    fun `the enum is exhaustive over section 11 1's ten states`() {
        val fromSpec = Section11.states.keys
        assertTrue(fromSpec.isNotEmpty(), "§11.1's state table parsed to no tokens")
        assertEquals(fromSpec, OrderState.entries.mapNotNull { it.token }.toSet())
    }

    /** Each canonical token reads to a state and writes back to the identical token. */
    @JsName("every_section_11_1_token_round_trips_through_the_codec")
    @Test
    fun `every section 11 1 token round-trips through the codec`() {
        for (token in Section11.states.keys) {
            val state = OrderStatusCodec.read(token)
            assertNotEquals(
                OrderState.UNKNOWN,
                state,
                "`$token` is a §11.1 state and must not read as the sink",
            )
            assertEquals(token, OrderStatusCodec.write(state), "`$token` did not round-trip")
        }
    }

    /** Which states are terminal is §11.1's decision, read from §11.1's own meaning cells. */
    @JsName("terminal_states_are_exactly_the_ones_section_11_1_marks_terminal")
    @Test
    fun `terminal states are exactly the ones section 11 1 marks terminal`() {
        val terminalInSpec = Section11.states.filterValues { it }.keys
        assertTrue(terminalInSpec.isNotEmpty(), "§11.1 marks no state terminal, which cannot be right")
        assertEquals(
            terminalInSpec,
            OrderState.entries.filter { it.isTerminal }.mapNotNull { it.token }.toSet(),
        )
    }

    @JsName("isrecognised_agrees_with_carrying_a_token")
    @Test
    fun `isRecognised agrees with carrying a token`() {
        for (state in OrderState.entries) {
            assertEquals(state.token != null, state.isRecognised, "$state")
        }
    }

    // ---------------------------------------------------------------- the unknown sink

    /**
     * §11.1: an unrecognised status MUST be treated as `unknown` and MUST NOT be mapped onto
     * the nearest known state. The two states it names explicitly are the two that would
     * matter — an unknown token read as `paid` or `settled` is an order reporting money it
     * never verified.
     */
    @JsName("an_unrecognised_token_reads_as_the_sink_and_never_as_the_nearest_known_state")
    @Test
    fun `an unrecognised token reads as the sink and never as the nearest known state`() {
        val strangers = listOf(
            "paidd", "pai", "settle", "settled ", " paid", "await_payment", "awaitingpayment",
            "commited", "unknown", "", "  ", "null", "0", "paid|settled", "🙂",
        )
        for (token in strangers) {
            val state = OrderStatusCodec.read(token)
            assertEquals(OrderState.UNKNOWN, state, "`$token` must read as the sink")
            assertNotEquals(OrderState.PAID, state, "§11.1: never `paid`")
            assertNotEquals(OrderState.SETTLED, state, "§11.1: never `settled`")
        }
    }

    /**
     * `unknown` is §11.1's *treatment* of an unrecognised value, not a token: there is
     * nothing to emit for it, and a sentinel string here would put an eleventh token within
     * reach of anything writing a `status` tag.
     */
    @JsName("the_sink_carries_no_token_and_cannot_be_emitted")
    @Test
    fun `the sink carries no token and cannot be emitted`() {
        assertNull(OrderState.UNKNOWN.token)
        assertFalse(OrderState.UNKNOWN.isRecognised)
        val thrown = assertFailsWith<OrderStateException> { OrderStatusCodec.write(OrderState.UNKNOWN) }
        assertEquals(OrderStateRejection.UNKNOWN_IS_NOT_EMITTABLE, thrown.reason)
    }

    /** The sink is not a terminal outcome. §11.1 marks four states terminal and this is not one. */
    @JsName("the_sink_is_not_marked_terminal")
    @Test
    fun `the sink is not marked terminal`() {
        assertFalse(OrderState.UNKNOWN.isTerminal)
    }

    // ---------------------------------------------------------------- reserved tokens

    /**
     * §11.1 reserves `held`, `refunded`, `in_review` and `resolved` for a future escrow
     * revision, and says a v1 implementation MUST NOT emit them. Both halves are asserted:
     * they read as the sink, and no constant of this package can produce one.
     *
     * The list is parsed out of §11.1 rather than written here, so a revision reserving a
     * fifth token extends this control automatically.
     */
    @JsName("every_reserved_token_reads_as_the_sink_and_is_emittable_by_nothing_here")
    @Test
    fun `every reserved token reads as the sink and is emittable by nothing here`() {
        val reserved = Section11.reservedTokens
        assertTrue(reserved.size >= 4, "§11.1 reserves at least four tokens; parsed $reserved")
        val emittable = OrderState.entries.mapNotNull { it.token }.toSet()
        for (token in reserved) {
            assertEquals(OrderState.UNKNOWN, OrderStatusCodec.read(token), "reserved `$token`")
            assertFalse(token in emittable, "§11.1 forbids a v1 implementation emitting `$token`")
        }
    }

    // ---------------------------------------------------------------- the conflation traps

    /**
     * §11.1: GammaMarkets' vocabulary is disjoint from this one except for `cancelled`, and
     * an implementation MUST NOT map the other four onto Nenya states.
     *
     * `cancelled` is the interesting line. It reads as the order state — §11.1 says it
     * carries the same meaning in both — which is exactly why the other four have to be
     * checked beside it: a codec that accepted the whole GammaMarkets vocabulary would pass
     * a test that only looked at `cancelled`.
     */
    @JsName("the_four_disjoint_gammamarkets_tokens_read_as_the_sink_and_cancelled_reads_as_the_order_state")
    @Test
    fun `the four disjoint GammaMarkets tokens read as the sink and cancelled reads as the order state`() {
        for (token in listOf("pending", "confirmed", "processing", "completed")) {
            assertEquals(OrderState.UNKNOWN, OrderStatusCodec.read(token), "GammaMarkets `$token`")
        }
        assertSame(OrderState.CANCELLED, OrderStatusCodec.read("cancelled"))
    }

    /**
     * §11.1: an implementation MUST NOT parse a listing `status` with the order-state codec.
     * This package can test that in one direction — the listing tokens this vocabulary does
     * not share must not decode here — and the shared token is the trap.
     *
     * `active`, `awarded` and `fulfilled` are the request vocabulary (§5.2, §5.3); `sold` is
     * the offer vocabulary. None is a Nenya order state. `cancelled` belongs to both with
     * different meanings — a cancelled listing is withdrawn from the board, a cancelled
     * order ended before `paid` — so reading it here yields the *order* state, and it is the
     * caller's business never to hand this codec a listing status in the first place.
     */
    @JsName("the_listing_vocabulary_does_not_decode_with_the_order_state_codec")
    @Test
    fun `the listing vocabulary does not decode with the order-state codec`() {
        for (token in listOf("active", "awarded", "fulfilled", "sold")) {
            assertEquals(
                OrderState.UNKNOWN,
                OrderStatusCodec.read(token),
                "listing `$token` is not an order state (§11.1)",
            )
        }
        assertSame(OrderState.CANCELLED, OrderStatusCodec.read("cancelled"))
    }

    /**
     * §11.1 calls its tokens canonical **lowercase** tokens, and §4.3's list of values that
     * are case-folded on the way in is exhaustive and does not include a status. So a
     * mixed-case token is not silently normalised into a state: folding it would be the
     * nearest-known-state mapping §11.1 forbids, arriving through the side door.
     */
    @JsName("a_token_of_the_wrong_case_is_not_normalised_into_a_state")
    @Test
    fun `a token of the wrong case is not normalised into a state`() {
        val variants = Section11.states.keys.flatMap { token ->
            listOf(token.uppercase(), token.replaceFirstChar { it.uppercase() })
        }.filter { it !in Section11.states.keys }
        assertTrue(variants.isNotEmpty(), "no case variants were generated, so nothing was tested")
        for (variant in variants) {
            assertEquals(
                OrderState.UNKNOWN,
                OrderStatusCodec.read(variant),
                "`$variant` differs from a canonical token only in case and must still read as the sink",
            )
        }
    }

    /** Surrounding whitespace is not trimmed away into a match, for the same reason. */
    @JsName("a_padded_token_is_not_trimmed_into_a_state")
    @Test
    fun `a padded token is not trimmed into a state`() {
        for (token in Section11.states.keys) {
            assertEquals(OrderState.UNKNOWN, OrderStatusCodec.read(" $token"))
            assertEquals(OrderState.UNKNOWN, OrderStatusCodec.read("$token "))
            assertEquals(OrderState.UNKNOWN, OrderStatusCodec.read("\t$token\n"))
        }
    }

    // ---------------------------------------------------------------- non-vacuity

    /**
     * The floor stated directly rather than left implicit: a reader that answered
     * [OrderState.UNKNOWN] for everything fails the round-trip above, and one that answered
     * some state for everything fails every control above. This pins both ends in one place
     * so neither degenerate reader can pass by coincidence.
     */
    @JsName("the_reader_is_neither_always_unknown_nor_never_unknown")
    @Test
    fun `the reader is neither always-unknown nor never-unknown`() {
        val recognised = Section11.states.keys.map { OrderStatusCodec.read(it) }
        assertTrue(recognised.none { it == OrderState.UNKNOWN }, "an always-unknown reader")
        assertTrue(
            recognised.toSet().size == Section11.states.size,
            "each §11.1 token must read to a distinct state; got ${recognised.toSet()}",
        )
        assertEquals(OrderState.UNKNOWN, OrderStatusCodec.read("definitely-not-a-nenya-status"))
    }
}
