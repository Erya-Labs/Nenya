package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.settlement.PaymentMedium
import dev.eryalabs.nenya.settlement.PaymentWriter
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decision **P**'s proof, per kind, over a generated corpus: **build → decode → rebuild → the same
 * bytes**.
 *
 * ### Why this is the proof
 *
 * A writer and the decoder that reads the same kind are two halves of one wire contract, and the way
 * they come apart is silent: a tag emitted twice, an optional tag emitted when it should have been
 * omitted, a value canonicalised one way out and another way back in, a value the decode cannot see
 * at all. None of those throws. All of them change §4.1's event id, which is the hash of the tags
 * **in order**, so comparing the canonical serialisation of the first build with that of the second
 * is one assertion that covers every one.
 *
 * The second build is assembled from what the decode reports — see [RumorWriterFixtures.rebuild],
 * which names the three values no decode reports and why each is a rule rather than a convenience.
 * That is what makes this stronger than a self-comparison: a decoder that dropped a value would make
 * the rebuild differ, and a writer that emitted something its decoder cannot see would too.
 *
 * ### What a round trip cannot catch, said out loud
 *
 * A **symmetric** error is invisible to it, and `ListingWriterPropertyTest` measured that for T40
 * rather than assuming it: reverse two tags the writer emits itself and the decode reports them
 * reversed, the reconstruction drops the same two, and the rebuild reproduces the reversal byte for
 * byte — a fixed point of a wrong writer. Here the emission orders are the fixed point, so each is
 * asserted **positively** against the document's own worked examples in `RumorWriterTest`, where one
 * side of the comparison is `spec/NENYA-1.md` rather than this writer.
 *
 * ### The non-vacuity floors
 *
 * A round-trip property is the easiest kind to pass for the wrong reason, so five floors sit beside
 * it: every case is built and decoded; every shape is drawn; every optional tag of every message is
 * drawn present and absent; the corpus is not one message repeated; and §4.1's escaping cases appear
 * inside the values the writer emitted.
 */
class RumorWriterPropertyTest {

    private companion object {

        /**
         * 640 messages, against T41's floor of a corpus per kind.
         *
         * The corpus cycles eight shapes, so each is drawn 80 times — and each case draws every
         * optional tag of its shape independently, so the floors below are reachable rather than
         * hoped for.
         */
        const val CORPUS: Int = 640

        /**
         * The seven characters §4.1 gives a shortcut escape, by code point: quote, backslash,
         * backspace, form feed, newline, carriage return, tab.
         *
         * By code point rather than as a string literal so the set is unmistakable — and form feed
         * is the one that matters, because it is one of the seven *and* below `0x20`, so leaving it
         * out would count it under rule 2 and overstate what the corpus reached.
         */
        val SHORTCUT_ESCAPES: List<Int> = listOf(0x22, 0x5C, 0x08, 0x0C, 0x0A, 0x0D, 0x09)

        /** `0x7f`, which §4.1 emits verbatim because rule 3 covers everything at or above `0x20`. */
        const val DEL: Int = 0x7F
    }

    /**
     * The round trip, and the assertion the task is about: for every shape, the two emitted events
     * are byte-identical.
     *
     * One loop over all eight rather than eight tests, because the property is the same property and
     * the failure message names the shape and the index. The floors below are what keep the loop
     * from being satisfied by a corpus that never reached a branch.
     */
    @JsName("every_built_rumor_decodes_and_rebuilds_to_the_identical_event")
    @Test
    fun `every built rumor decodes and rebuilds to the identical event`() {
        val cases = RumorWriterFixtures.cases(CORPUS)
        assertEquals(CORPUS, cases.size)

        var built = 0
        val serialisations = mutableSetOf<String>()
        for (case in cases) {
            val first = RumorWriterFixtures.build(case)
            val second = RumorWriterFixtures.rebuild(case, first)
            RumorWriterFixtures.assertSameBytes(first, second, case)

            built++
            serialisations += first.canonicalSerialisation()
        }

        assertEquals(CORPUS, built, "a corpus the writer refused would round-trip vacuously")
        assertEquals(
            CORPUS,
            serialisations.size,
            "the corpus must be $CORPUS different messages, not one message $CORPUS times",
        )
    }

    /**
     * Every shape is drawn, in roughly equal numbers, and each builds the kind and `type` §7.4 gives
     * it.
     *
     * The second half is what makes the first worth asserting: a corpus that drew all eight labels
     * and built eight `kind:14`s would satisfy a count and prove nothing.
     */
    @JsName("the_corpus_covers_every_shape_s7_4_lets_v1_emit")
    @Test
    fun `the corpus covers every shape §7_4 lets v1 emit`() {
        val counts = mutableMapOf<RumorWriterFixtures.Shape, Int>()
        for (case in RumorWriterFixtures.cases(CORPUS)) {
            counts[case.shape] = (counts[case.shape] ?: 0) + 1
            val event = RumorWriterFixtures.build(case)
            assertEquals(expectedKind(case.shape), event.kind, "$case: §7.4's rumor kind")
            assertEquals(
                expectedType(case.shape),
                RumorWriterFixtures.tagValue(RumorWriterFixtures.attribute(event), ChannelVocabulary.TYPE),
                "$case: §7.4's `type` discriminator",
            )
        }

        assertEquals(
            RumorWriterFixtures.Shape.entries.toSet(),
            counts.keys,
            "every shape §7.4 lets Nenya v1 emit must be in the corpus",
        )
        for ((shape, count) in counts) {
            assertTrue(count > CORPUS / 16, "$shape was drawn only $count time(s) in $CORPUS")
        }
    }

    /**
     * Every optional tag of every message is drawn present **and** absent across the corpus.
     *
     * Derived from the writers' own emission orders minus the tags each message always carries, so a
     * tag added to a section and to a writer joins this floor without anybody editing a list here. A
     * tag that was always absent would leave the round trip above silent about it.
     */
    @JsName("the_corpus_draws_every_optional_tag_present_and_absent")
    @Test
    fun `the corpus draws every optional tag present and absent`() {
        val present = mutableSetOf<String>()
        val absent = mutableSetOf<String>()
        val optional = OPTIONAL_BY_SHAPE
        assertTrue(optional.isNotEmpty(), "there must be optional tags for this floor to be about")
        // Every shape, or the lookup below would skip one in silence and the floor would quietly be
        // about a subset of the vocabulary. Asserted rather than trusted, because the map used to
        // cover six of the eight and nothing said so.
        assertEquals(
            RumorWriterFixtures.Shape.entries.toSet(),
            optional.keys,
            "every shape §7.4 lets v1 emit needs an entry here, even an empty one",
        )

        for (case in RumorWriterFixtures.cases(CORPUS)) {
            val rows = optional.getValue(case.shape)
            val names = RumorWriterFixtures.build(case).tags.map { it[0] }.toSet()
            for (row in rows) {
                if (row in names) present += "${case.shape}.$row" else absent += "${case.shape}.$row"
            }
        }

        val every = optional.flatMap { (shape, rows) -> rows.map { "$shape.$it" } }.toSet()
        assertEquals(every, present, "these optional tags are never emitted by the corpus")
        assertEquals(every, absent, "these optional tags are always emitted, so absence was never seen")
    }

    /**
     * The three values no decode reports are present on the wire, counted rather than hoped for.
     *
     * [RumorWriterFixtures] carries each from the authored case in the rebuild, which is correct and
     * is also exactly the shape that could make the round trip silent about them: a writer that
     * emitted none of the three would round-trip every case and lose all three values. So each is
     * asserted positively here, which is the half the byte comparison cannot do.
     */
    @JsName("the_three_values_no_decode_reports_are_on_the_wire")
    @Test
    fun `the three values no decode reports are on the wire`() {
        var chatWithOrder = 0
        var chatWithout = 0
        var keys = 0
        var nonces = 0
        var receiptAmounts = 0
        var receiptWithout = 0

        for (case in RumorWriterFixtures.cases(CORPUS)) {
            val names = RumorWriterFixtures.build(case).tags.map { it[0] }
            when (case.shape) {
                RumorWriterFixtures.Shape.CHAT ->
                    if (ChannelVocabulary.ORDER in names) chatWithOrder++ else chatWithout++
                RumorWriterFixtures.Shape.RELEASE -> {
                    if (DeliveryTags.DECRYPTION_KEY in names) keys++
                    if (DeliveryTags.DECRYPTION_NONCE in names) nonces++
                }
                RumorWriterFixtures.Shape.RECEIPT ->
                    if (ChannelVocabulary.AMOUNT_MSAT in names) receiptAmounts++ else receiptWithout++
                else -> Unit
            }
        }

        assertTrue(chatWithOrder > 0, "§7.4's MAY was never exercised with an `order` tag present")
        assertTrue(chatWithout > 0, "§7.4's MAY was never exercised with it absent")
        assertTrue(keys > 0, "§10.2's `decryption-key` was never emitted")
        assertTrue(nonces > 0, "§10.2's `decryption-nonce` was never emitted")
        assertTrue(receiptAmounts > 0, "§9.2's `amount_msat` was never emitted on a receipt")
        assertTrue(receiptWithout > 0, "a receipt carrying no `amount_msat` was never built")
    }

    /**
     * The corpus reaches the shapes §8.1, §5.3, §7.5, §9.4 and §10.3 distinguish, counted rather
     * than hoped for.
     *
     * §8.1's three `fee` cases are the ones a writer collapses by accident — [FeeTerm.Absent] and a
     * stated zero agree on the number and disagree on the wire — and §9.4's three rails are the ones
     * that decide whether §9.2 check 2's reader runs at all. A corpus that drew only one of each
     * would make the round trip above pass over the branch that matters.
     */
    @JsName("the_corpus_reaches_the_arities_and_rails_the_document_distinguishes")
    @Test
    fun `the corpus reaches the arities and rails the document distinguishes`() {
        var absentFee = 0
        var statedZeroFee = 0
        var feeWithRecipient = 0
        var onePubkeyRef = 0
        var twoPubkeyRefs = 0
        var relayHint = 0
        var withoutRelayHint = 0
        var extensions = 0
        val media = mutableSetOf<PaymentMedium>()
        var fileTypePresent = 0
        var fileTypeAbsent = 0

        for (case in RumorWriterFixtures.cases(CORPUS)) {
            val tags = RumorWriterFixtures.build(case).tags
            val fee = tags.singleOrNull { it[0] == ChannelVocabulary.FEE }
            when {
                fee == null -> absentFee++
                fee.size == 2 -> statedZeroFee++
                else -> feeWithRecipient++
            }
            val refs = tags.filter { it[0] == ChannelVocabulary.COUNTERPARTY }
            if (refs.size == 1) onePubkeyRef++
            if (refs.size > 1) twoPubkeyRefs++
            for (ref in refs) if (ref.size == 3) relayHint++ else withoutRelayHint++
            if (case.envelope.extraTags.isNotEmpty()) extensions++
            if (case.shape == RumorWriterFixtures.Shape.RECEIPT) media += case.medium
            if (case.shape == RumorWriterFixtures.Shape.RELEASE) {
                if (tags.any { it[0] == DeliveryTags.FILE_TYPE }) fileTypePresent++ else fileTypeAbsent++
            }
        }

        assertTrue(absentFee > 0, "§8.1's absent term was never drawn")
        assertTrue(statedZeroFee > 0, "§8.1's stated two-element `fee` was never drawn")
        assertTrue(feeWithRecipient > 0, "§8.1's three-element form was never drawn")
        assertTrue(onePubkeyRef > 0, "a single `p` tag was never drawn")
        assertTrue(twoPubkeyRefs > 0, "§5.3's `0–n` cardinality on `p` was never exercised")
        assertTrue(relayHint > 0, "§5.3's three-element `p` was never drawn")
        assertTrue(withoutRelayHint > 0, "the two-element `p` was never drawn")
        assertTrue(extensions > 0, "§4.3's unknown-tag preservation was never exercised")
        assertEquals(
            setOf(PaymentMedium.LIGHTNING, PaymentMedium.BITCOIN, PaymentMedium.ECASH),
            media,
            "§9.4's three rails must all be drawn: only `lightning` runs check 2's preimage reader",
        )
        assertTrue(fileTypePresent > 0, "§10.3's `file-type` was never emitted")
        assertTrue(
            fileTypeAbsent > 0,
            "§10.3 requires the identity check not be skipped when `file-type` is absent, so a " +
                "release carrying none must be buildable and must round-trip",
        )
    }

    /**
     * §4.1's three escaping rules are reached, because the id the round trip compares is computed
     * over the escaped bytes.
     *
     * Asserted over the values the writers actually emitted. A corpus of plain ASCII would make the
     * round trip above prove nothing about §4.1 at all.
     */
    @JsName("the_corpus_puts_s4_1s_escaping_cases_inside_the_values_the_writers_emit")
    @Test
    fun `the corpus puts §4_1's escaping cases inside the values the writers emit`() {
        var withShortcut = 0
        var withControl = 0
        var withNonBmp = 0
        var withSlash = 0
        var withDel = 0

        for (case in RumorWriterFixtures.cases(CORPUS)) {
            val event = RumorWriterFixtures.build(case)
            val text = event.tags.flatten().joinToString("") + event.content
            if (text.any { it.code in SHORTCUT_ESCAPES }) withShortcut++
            // Rule 2's case: below 0x20 and not one of the seven.
            if (text.any { it.code < 0x20 && it.code !in SHORTCUT_ESCAPES }) withControl++
            if (text.any { it.isHighSurrogate() }) withNonBmp++
            if (text.any { it == '/' }) withSlash++
            if (text.any { it.code == DEL }) withDel++
        }

        assertTrue(withShortcut > 0, "§4.1's seven shortcut escapes were never reached")
        assertTrue(withControl > 0, "§4.1's six-character escape for a control character was not reached")
        assertTrue(withNonBmp > 0, "§4.1's UTF-8 rule for a non-BMP character was never reached")
        assertTrue(withSlash > 0, "the solidus, which §4.1 never escapes, was never reached")
        assertTrue(withDel > 0, "DEL, which §4.1 emits verbatim because it is above 0x20, was not reached")
    }

    /** §7.4's rumor kind for each shape, from T9's constants rather than written as numbers. */
    private fun expectedKind(shape: RumorWriterFixtures.Shape): Int = when (shape) {
        RumorWriterFixtures.Shape.CHAT -> NenyaKind.CHAT
        RumorWriterFixtures.Shape.RELEASE -> NenyaKind.FILE_MESSAGE
        RumorWriterFixtures.Shape.RECEIPT -> NenyaKind.RECEIPT
        else -> NenyaKind.ORDER_MESSAGE
    }

    /** §7.4's `type` value for each shape, as it appears in the tag, or `null` for a kind with none. */
    private fun expectedType(shape: RumorWriterFixtures.Shape): String? = when (shape) {
        RumorWriterFixtures.Shape.PROPOSAL -> OrderMessageKind.PROPOSAL.type.toString()
        RumorWriterFixtures.Shape.PAYMENT_REQUEST -> OrderMessageKind.PAYMENT_REQUEST.type.toString()
        RumorWriterFixtures.Shape.ORDER_UPDATE -> OrderMessageKind.STATUS_UPDATE.type.toString()
        RumorWriterFixtures.Shape.COMMITMENT -> OrderMessageKind.DELIVERY_COMMITMENT.type.toString()
        RumorWriterFixtures.Shape.PRIVATE_BID -> OrderMessageKind.PRIVATE_BID.type.toString()
        else -> null
    }

    /**
     * The optional tags of each shape, derived from the writers' own emission orders.
     *
     * A tag is optional for a shape when the shape's emission order names it and the decoder that
     * reads that shape does not require it. Both sides are published lists, so this map is a
     * function of them rather than a copy — a tag that becomes required joins the required set and
     * leaves this floor in the same commit.
     */
    private val OPTIONAL_BY_SHAPE: Map<RumorWriterFixtures.Shape, List<String>> = buildMap {
        // `spelledBy` rather than `PROPOSAL_TAG_ORDER`, and that is not a cosmetic difference:
        // §7.5's `amount` compatibility tag is inserted into the order dynamically by
        // `withAmountAfterAmountMsat`, so it appears in **no** published emission order and reading
        // the floor off one left the one MAY in §7.5 unasserted. The corpus draws it both ways; now
        // the floor says so.
        put(
            RumorWriterFixtures.Shape.PROPOSAL,
            RumorWriterFixtures.spelledBy(RumorWriterFixtures.Shape.PROPOSAL) -
                OrderProposal.requiredTags().toSet(),
        )
        put(
            RumorWriterFixtures.Shape.ORDER_UPDATE,
            RumorWriter.ORDER_UPDATE_TAG_ORDER -
                (AttributedRumor.requiredTags() + ChannelVocabulary.TYPE + ChannelVocabulary.STATUS)
                    .toSet(),
        )
        put(
            RumorWriterFixtures.Shape.COMMITMENT,
            RumorWriter.COMMITMENT_TAG_ORDER - DeliveryCommitmentMessage.requiredTags().toSet(),
        )
        put(
            RumorWriterFixtures.Shape.RELEASE,
            RumorWriter.RELEASE_TAG_ORDER - DeliverableReleaseMessage.requiredTags().toSet(),
        )
        // §6.1's `price` and `item` are required by this writer and the rest of its terms are not.
        put(
            RumorWriterFixtures.Shape.PRIVATE_BID,
            RumorWriter.PRIVATE_BID_TAG_ORDER -
                (
                    AttributedRumor.requiredTags() - ChannelVocabulary.ORDER +
                        listOf(ChannelVocabulary.TYPE, ChannelVocabulary.ITEM, ChannelVocabulary.PRICE)
                    ).toSet(),
        )
        // §7.4 puts a chat outside its required set entirely, so every tag it may carry is optional
        // — and `nenya` is the one the writer always emits, so it is not drawn absent.
        put(
            RumorWriterFixtures.Shape.CHAT,
            RumorWriter.CHAT_TAG_ORDER - setOf(ChannelVocabulary.VERSION),
        )
        // §8.6's `fee` — §8.4 point 3's pair, OPTIONAL on a `payee=provider` request — and §9.2's
        // `fee` and `amount_msat`, which §8.4 point 4 and §9.2 make optional on the provider side.
        // Both shapes belong here for the reason the loop above now asserts: a map covering six of
        // the eight shapes let `?: continue` skip §8.6's and §9.2's optional rows in silence, which
        // is the one way a derived floor stops being one.
        put(
            RumorWriterFixtures.Shape.PAYMENT_REQUEST,
            PaymentWriter.REQUEST_TAG_ORDER - PaymentWriter.requestRequiredTags().toSet(),
        )
        put(
            RumorWriterFixtures.Shape.RECEIPT,
            PaymentWriter.RECEIPT_TAG_ORDER - PaymentWriter.receiptRequiredTags().toSet(),
        )
    }
}
