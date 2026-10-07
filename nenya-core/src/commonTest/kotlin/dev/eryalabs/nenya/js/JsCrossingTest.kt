package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.listing.AuthoredListing
import dev.eryalabs.nenya.listing.ListingWriter
import dev.eryalabs.nenya.listing.ListingWriterFixtures
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.settlement.PaymentMedium
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The crossing itself: every number that can exceed 2⁵³ survives the boundary exactly, and the three
 * refusals this boundary owns are the only ones it can ever report.
 *
 * ### What the exactness proof is about
 *
 * A JavaScript `number` is an IEEE-754 double and holds integers exactly only to 2⁵³ − 1. §4.4's
 * supply cap is 233 times larger and `Long.MAX_VALUE` — reachable by §8.3's `total_msat` and by a
 * `created_at` a stranger chose — is larger still. Decision **P** answers that by carrying every such
 * value as an exact decimal string, and this file is where that is checked at the three values T42
 * names: zero, the supply cap, and `Long.MAX_VALUE`.
 *
 * **The supply cap alone is not a sufficient control, and that is the trap worth writing down.**
 * 2 100 000 000 000 000 000 is a multiple of 2⁸, so it is exactly representable as a `Double` and
 * survives a round trip through one by luck; `Long.MAX_VALUE` survives because the conversion back
 * saturates onto it. The values **one step below** each are where the low bits are lost. So [ANCHORS]
 * brackets each anchor rather than sitting on it, and `JsSurfaceTest` carries the arithmetic
 * demonstration of why, on the JVM, where `Double` behaviour is the platform's own.
 *
 * ### The three refusals, and why they are not a second rulebook
 *
 * [JsCrossing] reports that what crossed cannot be turned into a Kotlin value at all — `"12x"` is not
 * a decimal string, so there is no amount to hand `Msat.ofMsat`. Decision **P** forbids throwing, so
 * it is returned as a value, under a vocabulary name no section of NENYA-1 uses. The set is pinned
 * here and again by reflection in `JsSurfaceTest`: a fourth refusal appearing unnoticed is how a
 * translation layer grows into a second opinion about the protocol.
 */
class JsCrossingTest {

    private companion object {

        /**
         * Pinned so a failure is reproducible, and distinct from the writers' seeds so this sweep is its
         * own run rather than a replay of theirs.
         */
        const val SEED: Long = 20261008L

        /** Sampled values for the property below. */
        const val SAMPLES: Int = 10_000

        /** 2⁵³ − 1: the largest integer a JavaScript `number` holds exactly. */
        const val EXACT_NUMBER_LIMIT: Long = 9_007_199_254_740_991L

        /** The values T42 names, each bracketed by its neighbour. See this class's note. */
        val ANCHORS: List<Long> = listOf(
            0L,
            1L,
            Msat.MSAT_PER_SAT,
            Msat.MSAT_PER_BTC,
            Msat.SUPPLY_CAP_MSAT - 1L,
            Msat.SUPPLY_CAP_MSAT,
            Msat.SUPPLY_CAP_MSAT + 1L,
            Long.MAX_VALUE - 1L,
            Long.MAX_VALUE,
        )

        /** [base] with [price] and nothing else changed. */
        fun priced(base: AuthoredListing, price: Msat): AuthoredListing = AuthoredListing(
            authorPubkey = base.authorPubkey,
            createdAt = base.createdAt,
            dValue = base.dValue,
            title = base.title,
            price = price,
            alt = base.alt,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Exactness.
    // -----------------------------------------------------------------------------------------

    /** Every anchor crosses as its exact decimal form and reads back as itself. */
    @Test
    @JsName("every_anchor_value_survives_the_decimal_crossing_exactly")
    fun `every anchor value survives the decimal crossing exactly`() {
        for (value in ANCHORS) {
            val text = JsDecimal.of(value)
            assertEquals(
                value.toString(),
                text,
                "the crossing must write a number as its exact decimal form and nothing else",
            )
            assertEquals(
                value,
                JsDecimal.toLongOrNull(text),
                "$value did not survive the crossing; a lost low bit here is a money bug one layer up",
            )
            // Through the two named readers the entry points actually call, not only through the codec.
            assertEquals(value, crossedAmount("price", text), "§8.2's amount crossing lost $value")
            assertEquals(value, crossedSeconds("created_at", text), "§4.1's timestamp crossing lost $value")
        }
        // Neighbours stay distinct across the boundary. A crossing that rounded would collapse these
        // pairs onto one value while every assertion above still passed on the anchors themselves.
        for (value in listOf(Msat.SUPPLY_CAP_MSAT, Long.MAX_VALUE)) {
            assertNotEquals(
                JsDecimal.of(value),
                JsDecimal.of(value - 1L),
                "$value and ${value - 1L} must not cross as the same string",
            )
            assertNotEquals(
                crossedAmount("price", JsDecimal.of(value)),
                crossedAmount("price", JsDecimal.of(value - 1L)),
                "$value and ${value - 1L} must not read back as the same amount",
            )
        }
    }

    /**
     * The same exactness over sampled values, so the anchors cannot be a list of special cases the codec
     * happens to handle.
     *
     * `JdkRandom`'s algorithm is the JDK's specified one and is identical on the JVM and JavaScript, so
     * this run is the same wherever it is re-run; a reviewer can change [SEED] and every assertion must
     * still hold.
     */
    @Test
    @JsName("the_decimal_crossing_round_trips_sampled_values_exactly")
    fun `the decimal crossing round-trips sampled values exactly`() {
        val random = JdkRandom(SEED)
        var outsideExactRange = 0
        repeat(SAMPLES) {
            // The whole 64-bit range, both signs: a timestamp and a byte count are non-negative, but
            // the codec is one function and `Long.MIN_VALUE` is the value a sign-handling bug loses.
            val value = random.nextLong()
            assertEquals(value, JsDecimal.toLongOrNull(JsDecimal.of(value)), "$value did not round-trip")
            if (value > EXACT_NUMBER_LIMIT || value < -EXACT_NUMBER_LIMIT) outsideExactRange++
        }
        assertTrue(
            outsideExactRange > SAMPLES / 2,
            "only $outsideExactRange of $SAMPLES samples were outside the exact `number` range, so " +
                "this sweep is not about the values the decimal rule exists for",
        )
        assertEquals(Long.MIN_VALUE, JsDecimal.toLongOrNull(JsDecimal.of(Long.MIN_VALUE)))
    }

    /**
     * A `created_at` at `Long.MAX_VALUE` read back off an event, so the exactness is proved where a page
     * would actually see it rather than only through the codec.
     *
     * `JsWireEvent` is the one crossing that *reads* a `created_at` out, and it needs no writer: the
     * event is constructed directly, which is what lets this reach a timestamp no conformant listing
     * would carry.
     */
    @Test
    @JsName("a_created_at_at_Long_MAX_VALUE_crosses_out_of_an_event_exactly")
    fun `a created_at at Long MAX_VALUE crosses out of an event exactly`() {
        for (seconds in listOf(0L, EXACT_NUMBER_LIMIT + 1L, Long.MAX_VALUE - 1L, Long.MAX_VALUE)) {
            val event = WireEvent(
                TagFixtures.pubkeyFor(0),
                seconds,
                NenyaKind.OFFER,
                listOf(listOf("d", "exactness")),
                "",
            )
            assertEquals(
                seconds,
                JsDecimal.toLongOrNull(JsWireEvent(event).createdAtSeconds),
                "§4.1's created_at crossed out of the event as something other than $seconds",
            )
        }
    }

    /**
     * Money at §4.4's supply cap, through a real entry point, agreeing with `ListingWriter` either way.
     *
     * This is the end-to-end form of the rule, and the case T42's mutation turns red: a facade that
     * rounded a money value through a `Double` would still agree with the library at the cap — which is
     * exactly representable — and would disagree one satoshi below it. So all three are built, each is
     * compared with the library's own answer, and the three must produce three *different* events.
     *
     * The sub-cap case steps down by a whole satoshi rather than by a millisatoshi: §5.3's `price` row
     * is satoshi-denominated and `TagWriter.price` refuses a part-satoshi rather than rounding it, which
     * is §4.4's rule for that row. It is still far outside the exact `number` range, which is what the
     * control is about.
     */
    @Test
    @JsName("a_price_at_and_below_the_supply_cap_builds_the_same_listing_on_both_sides")
    fun `a price at and below the supply cap builds the same listing on both sides`() {
        val base = ListingWriterFixtures.minimal(NenyaKind.OFFER)
        val prices = listOf(
            Msat.ofMsat(0L),
            Msat.ofMsat(Msat.SUPPLY_CAP_MSAT - Msat.MSAT_PER_SAT),
            Msat.ofMsat(Msat.SUPPLY_CAP_MSAT),
        )
        assertTrue(
            prices[1].millisatoshis > EXACT_NUMBER_LIMIT,
            "the sub-cap price must be outside the range a JavaScript number holds exactly",
        )

        val serialised = mutableSetOf<String>()
        for (price in prices) {
            val what = "an offer priced at ${price.millisatoshis} msat"
            val authored = priced(base, price)
            val answer = buildListingOffer(JsBoundaryFixtures.crossed(authored))
            JsBoundaryFixtures.assertSameAnswer(
                answer,
                JsBoundaryFixtures.answerOf(ListingWriter.offer(authored)),
                what,
            )
            assertTrue(
                answer.ok,
                "$what must build; a shared refusal would make this a control about nothing: " +
                    "${answer.reason} ${answer.detail}",
            )
            val event = answer.event ?: fail("$what: a built answer with no event")
            val json = event.unsignedJson()
            assertTrue(json.ok, "$what: the crossing refused to serialise it: ${json.reason}")
            serialised += json.value ?: fail("$what: an ok JsText with no value")
        }
        assertEquals(
            prices.size,
            serialised.size,
            "the ${prices.size} prices crossed as ${serialised.size} distinct event(s); a boundary that " +
                "rounded them together would satisfy every comparison above",
        )
    }

    // -----------------------------------------------------------------------------------------
    // Strictness on read.
    // -----------------------------------------------------------------------------------------

    /**
     * The crossing reads exactly the form it writes, and nothing else.
     *
     * Narrower than §4.4's read rule for a price on the wire, and deliberately: both ends of this codec
     * are code in this repository, so a permissive reader could only hide a bug in the writer.
     */
    @Test
    @JsName("the_decimal_crossing_reads_only_the_canonical_form_it_writes")
    fun `the decimal crossing reads only the canonical form it writes`() {
        val refused = listOf(
            "", "-", "+1", " 1", "1 ", "007", "-0", "-007", "0x10", "1e3", "1.0", "1_000",
            // Arabic-Indic digits, which `toLong()` accepts and §4.3 does not.
            "١", "1٠",
            // One past `Long.MAX_VALUE`, which has no `Long` to be.
            "9223372036854775808",
        )
        for (text in refused) {
            assertNull(
                JsDecimal.toLongOrNull(text),
                "`$text` is not the form the crossing writes and must not be read as a number",
            )
        }
        // The canonical forms it does read, so the list above is not satisfied by a reader that refuses
        // everything.
        for (value in ANCHORS + listOf(Long.MIN_VALUE, -1L)) {
            assertEquals(value, JsDecimal.toLongOrNull(JsDecimal.of(value)), "$value must still read")
        }
    }

    /** §4.3's hex, read strictly: an even number of lowercase digits and nothing else. */
    @Test
    @JsName("the_hex_crossing_reads_only_strict_lowercase_hex_of_even_length")
    fun `the hex crossing reads only strict lowercase hex of even length`() {
        for (text in listOf("0", "abc", "0g", "AB", "aB", " 00", "00 ", "0x00")) {
            assertNull(JsHex.toBytesOrNull(text), "`$text` is not strict lowercase hex of even length")
        }
        assertEquals(0, JsHex.toBytesOrNull("")?.size, "no bytes is a legal answer for no digits")
        val bytes = JsHex.toBytesOrNull("00ff10") ?: fail("lowercase hex of even length must read")
        assertEquals(listOf(0, 255, 16), bytes.map { it.toInt() and 0xff }, "the digits decoded wrongly")
    }

    // -----------------------------------------------------------------------------------------
    // The three refusals this boundary owns.
    // -----------------------------------------------------------------------------------------

    /**
     * Each of [JsCrossing]'s three reasons is reached through a real entry point, reported as a value,
     * and tells the caller it came from the crossing rather than from NENYA-1.
     *
     * Reached through the published functions rather than by constructing a [JsCrossing]: the claim is
     * that a malformed crossing is *returned*, and decision **P** forbids throwing, so a test that built
     * the exception itself would not have proved the entry point catches it.
     */
    @Test
    @JsName("the_three_crossing_refusals_are_returned_as_values_under_their_own_vocabulary")
    fun `the three crossing refusals are returned as values under their own vocabulary`() {
        val crossed = JsBoundaryFixtures.crossed(ListingWriterFixtures.minimal(NenyaKind.OFFER))
        val reasons = mutableSetOf<String>()

        // MALFORMED_DECIMAL: a price that is not a decimal string. There is no amount to hand
        // `Msat.ofMsat`, so there is nothing for §4.4 to refuse and nothing to guess.
        val malformed = buildListingOffer(
            JsAuthoredListing(
                authorPubkey = crossed.authorPubkey,
                createdAtSeconds = crossed.createdAtSeconds,
                dValue = crossed.dValue,
                title = crossed.title,
                priceMsat = "12x",
                alt = crossed.alt,
            ),
        )
        assertFalse(malformed.ok, "`12x` is not a millisatoshi amount")
        assertEquals(JsCrossing.MALFORMED_DECIMAL, malformed.reason)
        assertEquals(JsCrossing.VOCABULARY, malformed.reasonVocabulary)
        assertEquals("price", malformed.tag, "the diagnostic must name the field being read")
        reasons += JsCrossing.MALFORMED_DECIMAL

        // WRONG_ROW_ARITY: §5.3 gives an `image` row one element or two, and this one crossed with three.
        val badRow = buildListingOffer(
            JsAuthoredListing(
                authorPubkey = crossed.authorPubkey,
                createdAtSeconds = crossed.createdAtSeconds,
                dValue = crossed.dValue,
                title = crossed.title,
                priceMsat = crossed.priceMsat,
                images = arrayOf(arrayOf("https://example.invalid/a.png", "64x64", "extra")),
                alt = crossed.alt,
            ),
        )
        assertFalse(badRow.ok, "§5.3 gives no three-element `image` row")
        assertEquals(JsCrossing.WRONG_ROW_ARITY, badRow.reason)
        assertEquals(JsCrossing.VOCABULARY, badRow.reasonVocabulary)
        assertEquals("image", badRow.tag)
        reasons += JsCrossing.WRONG_ROW_ARITY

        // UNKNOWN_PAYEE_TOKEN: §8.6 prints two tokens and this is neither, so there is no `Payee` to be
        // — and defaulting to `provider` would put a payee on the wire the caller never named. Spoils
        // exactly one field of an otherwise legal §8.6 request.
        val request = RumorWriterFixtures.cases(64)
            .first { it.shape == RumorWriterFixtures.Shape.PAYMENT_REQUEST }
        val unknownPayee = buildPaymentRequest(
            envelope = JsBoundaryFixtures.crossed(request.envelope),
            orderIdHex = (request.order ?: fail("the case must carry an order id")).toHex(),
            payeeToken = "treasury",
            invoice = (request.invoice ?: fail("the case must carry an invoice")).text,
            feeRecipient = request.feeRecipient,
            terms = request.terms?.let { JsBoundaryFixtures.crossed(it) },
        )
        assertFalse(unknownPayee.ok, "`treasury` is not one of §8.6's two payee tokens")
        assertEquals(JsCrossing.UNKNOWN_PAYEE_TOKEN, unknownPayee.reason)
        assertEquals(JsCrossing.VOCABULARY, unknownPayee.reasonVocabulary)
        assertEquals("payee", unknownPayee.tag)
        reasons += JsCrossing.UNKNOWN_PAYEE_TOKEN

        assertEquals(
            JsCrossing.REASONS,
            reasons,
            "every reason this vocabulary can carry must be reached by this test; an unreached one is a " +
                "refusal a page can receive that nothing here has seen",
        )
        // And the tokens the entry point does accept, so the refusal above is the token and not the call.
        for (payee in Payee.entries) {
            assertEquals(payee, crossedPayee(payee.token), "§8.6's own token must cross to its own `Payee`")
        }
    }

    /**
     * §9.4's `lightning` token exists, because two exported entry points dereference it in a **default
     * argument**.
     *
     * `buildPaymentRequest` and `buildReceipt` both default `mediumToken` to
     * `PaymentMedium.LIGHTNING.token!!`. A default argument is evaluated at the call site, *before*
     * `jsBuild`'s `try` is entered, so a revision that made `LIGHTNING.token` null would turn both
     * functions into throwers — the one shape decision **P** forbids, in the two places no catch ladder
     * can reach. The `!!` is correct today and this is what keeps it so.
     */
    @Test
    @JsName("section_9_4s_lightning_token_exists_because_two_entry_points_default_to_it")
    fun `section 9 4's lightning token exists because two entry points default to it`() {
        assertNotNull(
            PaymentMedium.LIGHTNING.token,
            "§9.4 names a `lightning` token and `buildPaymentRequest`/`buildReceipt` default to it " +
                "with `!!`, outside any catch ladder",
        )
        assertEquals(
            PaymentMedium.LIGHTNING,
            PaymentMedium.of(PaymentMedium.LIGHTNING.token!!),
            "and it must round-trip, since the entry points hand that token straight back to `of`",
        )
    }

    /**
     * Nothing a page hands a well-formed crossing can produce a [JsCrossing] at all.
     *
     * The companion half of the rule that class records: the three refusals are about the *form* of a
     * crossing, so a corpus built from the decimal strings the crossing itself writes must never see
     * one. `JsFacadeEqualityTest` sweeps the whole corpus; this asserts the consequence directly,
     * because it is the sentence a reader of [JsCrossing] needs checked.
     */
    @Test
    @JsName("a_well_formed_crossing_never_produces_a_crossing_refusal")
    fun `a well-formed crossing never produces a crossing refusal`() {
        val cases = ListingWriterFixtures.cases(60)
        for (case in cases) {
            val answer = buildListingOffer(JsBoundaryFixtures.crossed(case.listing))
            if (answer.ok) continue
            assertNotEquals(
                JsCrossing.VOCABULARY,
                answer.reasonVocabulary,
                "listing #${case.index} was well-formed on the boundary's own terms and the crossing " +
                    "still refused it as ${answer.reason}: ${answer.detail}",
            )
        }
        assertEquals(60, cases.size, "the sweep is not the corpus it is about")
    }
}
