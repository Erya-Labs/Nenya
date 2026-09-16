package dev.eryalabs.nenya.tag

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The **write** half of §5.3's Encoding column, which read and write are asymmetric about on
 * purpose.
 *
 * §4.4 states the asymmetry itself: a price is *strict on write* — exactly the token `SAT`,
 * exactly `0|[1-9][0-9]*` — and *permissive on read*, accepting eight unit tokens and a decimal
 * `BTC` value. §4.3 says the same of hex, §5.3 of `t` tokens. So a suite that only exercises the
 * read side proves nothing about the bytes this library puts on a relay, and the one defect this
 * file was written for is exactly that shape: [TagWriter.fee] used to answer a `FeeTerm.Absent`
 * with the two-element `["fee", "0"]`, turning "the counterparty signed nothing" into "the
 * counterparty signed zero" on the one axis §8.4 demands byte-identity for.
 *
 * The strongest assertion here is the last one: a whole listing built from [TagWriter] output and
 * read back through [TagSet.read] must recover exactly the values it was written from. Write and
 * read then have to agree with each other rather than each with itself.
 */
class TagWriterTest {

    private fun refusal(block: () -> Unit): TagException = assertFailsWith<TagException>(block = block)

    private val request = TagContext.listing(NenyaKind.REQUEST)

    // ------------------------------------------------------------------------------ §4.4 price

    @Test
    fun `a price is written strictly, in satoshis, with exactly the SAT token`() {
        assertEquals(listOf("price", "50000", "SAT"), TagWriter.price(Msat.ofSat(50_000)))
        assertEquals(listOf("price", "0", "SAT"), TagWriter.price(Msat.ZERO))
        assertEquals(
            listOf("price", "2100000000000000", "SAT"),
            TagWriter.price(Msat.SUPPLY_CAP),
            "§4.4's bound is a legal amount, not a rejected one",
        )
        assertEquals("SAT", TagWriter.CANONICAL_UNIT, "§4.4 fixes the write token in exactly this case")
    }

    /** §4.4's "Never lossy" on the way out: an amount that is not whole satoshis is refused, not rounded. */
    @Test
    fun `a price that is not a whole number of satoshis is refused as lossy`() {
        val refused = refusal { TagWriter.price(Msat.ofMsat(1_500)) }

        assertEquals(TagRejection.LOSSY, refused.reason)
        assertEquals(
            listOf("price", "1", "SAT"),
            TagWriter.price(Msat.ofMsat(1_000)),
            "…and the millisatoshi immediately below it is written without complaint, so the " +
                "refusal is about the precision rather than about the unit",
        )
    }

    // -------------------------------------------------------------------------------- §8.1 fee

    /**
     * The defect this file exists for. §8.1's absence has no wire form: it is written by emitting
     * no `fee` tag, and the stated `["fee", "0"]` is a different, signed thing.
     */
    @Test
    fun `an absent fee term has no wire form and is refused rather than written`() {
        val refused = refusal { TagWriter.fee(FeeTerm.Absent) }

        assertEquals(TagRejection.ABSENT_FEE_TERM, refused.reason)
        assertEquals(
            listOf("fee", "0"),
            TagWriter.fee(FeeTerm.of(0)),
            "…while the stated zero is written, and the two must never produce the same bytes: " +
                "§8.4 requires the (bps, recipient) pair be reproduced byte-identically at four " +
                "points and aborts the order on any divergence",
        )
    }

    @Test
    fun `both fee arities are written and neither is written for the other's term`() {
        val recipient = TagFixtures.pubkeyFor(3)

        assertEquals(listOf("fee", "0"), TagWriter.fee(FeeTerm.of(0)))
        assertEquals(listOf("fee", "250", recipient), TagWriter.fee(FeeTerm.of(250), recipient))
        assertEquals(
            listOf("fee", "10000", recipient),
            TagWriter.fee(FeeTerm.of(FeeTerm.MAX_BASIS_POINTS), recipient),
            "§8.1 imposes no ceiling below 10000 and forbids refusing an in-range value for being large",
        )

        assertEquals(
            TagRejection.WRONG_ARITY,
            refusal { TagWriter.fee(FeeTerm.of(250)) }.reason,
            "§8.1 makes the recipient REQUIRED above zero basis points",
        )
        assertEquals(
            TagRejection.WRONG_ARITY,
            refusal { TagWriter.fee(FeeTerm.of(0), recipient) }.reason,
            "§8.1 requires it be OMITTED at zero",
        )
    }

    /** §4.3: lowercase hex on write, whatever case the caller is holding. */
    @Test
    fun `a fee recipient is written in lowercase hex`() {
        val uppercase = TagFixtures.uppercasePubkeys.first()

        assertEquals(
            listOf("fee", "250", uppercase.lowercase()),
            TagWriter.fee(FeeTerm.of(250), uppercase),
        )
        assertEquals(
            TagRejection.WRONG_LENGTH,
            refusal { TagWriter.fee(FeeTerm.of(250), uppercase.dropLast(1)) }.reason,
        )
    }

    // -------------------------------------------------------- §4.3 timestamps, §5.3 tokens, §4.5

    @Test
    fun `a timestamp is written as a non-negative decimal and a negative one is refused`() {
        assertEquals(listOf("expiration", "1759592000"), TagWriter.timestamp("expiration", 1_759_592_000L))
        assertEquals(listOf("published_at", "0"), TagWriter.timestamp("published_at", 0L))
        assertEquals(
            TagRejection.MALFORMED_TIMESTAMP,
            refusal { TagWriter.timestamp("expiration", -1L) }.reason,
        )
    }

    /**
     * §5.3: tokens MUST be lowercase with no whitespace, and implementations MUST lowercase on
     * write — because relay tag indexes are byte-exact, so `["t", "Nenya"]` is simply invisible to
     * the `{"#t": ["nenya"]}` filter §5.5 builds the whole board on.
     */
    @Test
    fun `a topic is lowercased on write and refused when it carries whitespace`() {
        assertEquals(listOf("t", "nenya"), TagWriter.topic("Nenya"))
        assertEquals(listOf("t", "ai-video"), TagWriter.topic("AI-Video"))
        assertEquals(TagRejection.EMPTY_VALUE, refusal { TagWriter.topic("") }.reason)
        assertEquals(
            TagRejection.MALFORMED_TOKEN,
            refusal { TagWriter.topic("ai video") }.reason,
            "a `t` token is not a number, so it does not get a number's rejection reason",
        )
    }

    @Test
    fun `the version and d tags are written as section 4 point 5 and 5 point 3 fix them`() {
        assertEquals(listOf("nenya", "1"), TagWriter.version(1))
        assertEquals(TagRejection.MALFORMED_NUMBER, refusal { TagWriter.version(-1) }.reason)
        assertEquals(listOf("d", "lighthouse-loop"), TagWriter.d("lighthouse-loop"))
        assertEquals(TagRejection.EMPTY_VALUE, refusal { TagWriter.d("") }.reason)
    }

    // ------------------------------------------------------------------------- the value types

    @Test
    fun `the value types write the arities section 5 point 3 gives them`() {
        val pubkey = TagFixtures.pubkeyFor(5)

        assertEquals(listOf("image", "https://e.invalid/a.png"), ImageRef("https://e.invalid/a.png").toTag())
        assertEquals(
            listOf("image", "https://e.invalid/a.png", "64x64"),
            ImageRef("https://e.invalid/a.png", "64x64").toTag(),
        )
        assertEquals(listOf("p", pubkey), PubkeyRef(pubkey).toTag())
        assertEquals(
            listOf("p", pubkey, "wss://relay.example.invalid"),
            PubkeyRef(pubkey, "wss://relay.example.invalid").toTag(),
        )
        assertEquals(
            listOf("item", "${NenyaKind.REQUEST}:$pubkey:d-value", "1"),
            ItemRef(Coordinate(NenyaKind.REQUEST, pubkey, "d-value")).toTag(),
        )
        assertEquals(
            TagRejection.MALFORMED_NUMBER,
            refusal { ItemRef(Coordinate(NenyaKind.REQUEST, pubkey, "d"), quantity = "2") }.reason,
        )
    }

    /** §4.3 again, on the constructors rather than on the parsers: uppercase in, lowercase out. */
    @Test
    fun `the value types normalise hex on construction`() {
        val uppercase = TagFixtures.uppercasePubkeys[2]

        assertEquals(uppercase.lowercase(), PubkeyRef(uppercase).pubkey)
        assertEquals(uppercase.lowercase(), Coordinate(NenyaKind.REQUEST, uppercase, "d").pubkey)
        assertEquals(
            TagRejection.NOT_HTTPS,
            refusal { ImageRef("http://e.invalid/a.png") }.reason,
        )
    }

    // ------------------------------------------------------------------- write, then read it back

    /**
     * The assertion that makes the rest of this file mean something: a listing assembled entirely
     * from [TagWriter] output, read back through the codec, recovers exactly the values it was
     * written from — and re-publishes byte-identically, so a client that authored an event can
     * hand it on without the codec rewriting its own bytes.
     */
    @Test
    fun `a listing written by the writer reads back with the same values`() {
        val recipient = TagFixtures.pubkeyFor(6)
        val counterparty = TagFixtures.pubkeyFor(7)
        val tags = listOf(
            TagWriter.d("lighthouse-loop-2026-09"),
            listOf("title", "30s looping animation"),
            TagWriter.price(Msat.ofSat(120_000)),
            TagWriter.topic("Nenya"),
            TagWriter.topic("WTB"),
            TagWriter.version(1),
            listOf("alt", "Nenya marketplace request"),
            TagWriter.fee(FeeTerm.of(250), recipient),
            TagWriter.timestamp("expiration", 1_759_592_000L),
            ImageRef("https://example.invalid/reference.jpg", "1280x720").toTag(),
            PubkeyRef(counterparty).toTag(),
        )

        val read = TagFixtures.read(tags, request)

        assertEquals("lighthouse-loop-2026-09", read.dValue)
        assertEquals(Msat.ofSat(120_000), read.price)
        assertEquals(listOf("nenya", "wtb"), read.topics, "the writer lowercased them")
        assertEquals(1, read.nenyaVersion)
        assertEquals(250, read.fee.basisPoints)
        assertEquals(recipient, read.feeRecipient)
        assertEquals(1_759_592_000L, read.expiration)
        assertEquals("1280x720", read.images.single().dimensions)
        assertEquals(counterparty, read.pubkeyRefs.single().pubkey)
        assertNull(read.pubkeyRefs.single().relay)
        assertEquals(tags, read.republish())
        assertTrue(read.unknownTags.isEmpty())
    }

    /**
     * And the absent-fee path written the way §8.1 says to write it: by emitting nothing. The
     * result reads back as [FeeTerm.Absent] and not as a stated zero, which is the round trip the
     * defect above broke.
     */
    @Test
    fun `omitting the fee tag round-trips as absent and not as a stated zero`() {
        val withoutFee = TagFixtures.minimalRequest()
        val withStatedZero = TagFixtures.minimalRequest().also { it += TagWriter.fee(FeeTerm.of(0)) }

        assertSame(FeeTerm.Absent, TagFixtures.read(withoutFee, request).fee)
        assertEquals(FeeTerm.of(0), TagFixtures.read(withStatedZero, request).fee)
    }
}
