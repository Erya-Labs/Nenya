package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.NenyaProtocol
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The half of T41's proof that the round trip cannot make: one side of every comparison here is
 * `spec/NENYA-1.md` or a decoder's own published list, rather than this writer.
 *
 * `RumorWriterPropertyTest` proves build → decode → rebuild is byte-identical over a generated
 * corpus, and names what that cannot catch: a **symmetric** error. The reconstruction drops the
 * names the writer spells, so a writer that emitted its rows in the wrong order would produce a
 * fixed point of itself. Two families of assertion close that, and they are the reason this file
 * exists:
 *
 * 1. **The emission orders against the document.** Each of the four messages §7 and §10 print whole
 *    has its order held equal to the tag names *that example* prints, parsed out of the
 *    specification at test time by `Section75` and `Section10`. The three messages the document
 *    prints no example for carry their order with the clause it was modelled on, and are held equal
 *    to the set of tags their decoder requires or accepts, so neither can go stale in silence.
 * 2. **The required sets against the decoders.** For every kind, each tag the decoder requires is
 *    removed from a built event in turn and the decoder's refusal is asserted to name *that* tag —
 *    a proof over a published list rather than over hand-written controls that could silently stop
 *    covering an eighth. That is what holds this writer's `required` lists equal to what the
 *    decoders really demand.
 *
 * Then the negative controls, each asserting its **reason** as data (`reason` and `tag`) rather
 * than by message wording.
 */
class RumorWriterTest {

    private companion object {

        /** A generated author, counterparty and order, so no control writes an encoded value out. */
        fun envelope(
            index: Int = 0,
            extraTags: List<List<String>> = emptyList(),
            nenyaVersion: Int = NenyaProtocol.VERSION,
            authorPubkey: String = RumorWriterFixtures.pubkeyFor(index),
            createdAt: Long = RumorWriterFixtures.CREATED_AT,
            counterparties: Int = 1,
        ): RumorEnvelope = RumorEnvelope(
            authorPubkey = authorPubkey,
            createdAt = createdAt,
            counterparties = List(counterparties) {
                dev.eryalabs.nenya.tag.PubkeyRef(RumorWriterFixtures.pubkeyFor(index + 1 + it))
            },
            nenyaVersion = nenyaVersion,
            extraTags = extraTags,
        )

        /** §7.5's terms with no fee at all — §8.1's zero-fee case, which needs no recipient. */
        fun plainTerms(): OrderTerms = OrderTerms.of(Msat.ofSat(50_000L), FeeTerm.Absent)

        fun item() = dev.eryalabs.nenya.tag.ItemRef(
            dev.eryalabs.nenya.tag.Coordinate.parse(
                dev.eryalabs.nenya.tag.TagFixtures.coordinate(index = 11),
            ),
        )

        fun commitment() = dev.eryalabs.nenya.delivery.DeliverableCommitment(
            RumorWriterFixtures.hashFor("served"),
            RumorWriterFixtures.hashFor("plaintext"),
            "video/mp4",
            18_342_912L,
        )

        fun release() = dev.eryalabs.nenya.delivery.DeliverableRelease(
            RumorWriterFixtures.hashFor("served"),
            RumorWriterFixtures.hashFor("plaintext"),
            "video/mp4",
            18_342_912L,
        )

        const val URL: String = "https://blob.example.invalid/9a7f"

        /**
         * 44 characters of standard base64, which decodes to exactly §10.2's 32 bytes.
         *
         * Not an encoded value somebody typed out in the sense the queue forbids: it carries no key,
         * no hash and no invoice, and nothing reads it as one. It is a *shape* — one padding
         * character in the position standard base64 puts it — and it exists so the control can state
         * both halves of §10.2's asymmetry: the decoder accepts this spelling and the writer refuses
         * to emit it.
         */
        val BASE64_KEY: String = "A".repeat(43) + "="
    }

    // -------------------------------------------------------------------------------------------
    // 1. The emission orders, against the document.
    // -------------------------------------------------------------------------------------------

    /**
     * §7.5's worked proposal prints nine tags, and [RumorWriter.PROPOSAL_TAG_ORDER] is their order.
     *
     * The document is one side of this comparison, which is what makes it a control rather than the
     * writer agreeing with itself. `Section75` fails loudly if the fence or the count changes.
     */
    @JsName("the_proposal_emission_order_is_the_one_s7_5s_worked_example_prints")
    @Test
    fun `the proposal emission order is the one §7_5's worked example prints`() {
        assertEquals(
            Section75.exampleTagNames,
            RumorWriter.PROPOSAL_TAG_ORDER,
            "§4.1 hashes the tags in order, so §7.5's own example is what fixes this writer's",
        )
    }

    /** §10.1's worked commitment prints ten tags, and [RumorWriter.COMMITMENT_TAG_ORDER] is their order. */
    @JsName("the_commitment_emission_order_is_the_one_s10_1s_worked_example_prints")
    @Test
    fun `the commitment emission order is the one §10_1's worked example prints`() {
        assertEquals(Section10.commitmentTagNames, RumorWriter.COMMITMENT_TAG_ORDER)
    }

    /**
     * §10.3's worked release prints ten tags, and [RumorWriter.RELEASE_TAG_ORDER] is their order —
     * which puts `p` **first** and `nenya` last, the opposite way round from §10.1's.
     *
     * The second assertion is the point: the two orders really are different in the document, which
     * is the clearest evidence that §7.4 fixes none and that a writer has to choose per section
     * rather than invent one order for every rumor.
     */
    @JsName("the_release_emission_order_is_the_one_s10_3s_worked_example_prints")
    @Test
    fun `the release emission order is the one §10_3's worked example prints`() {
        assertEquals(Section10.releaseTagNames, RumorWriter.RELEASE_TAG_ORDER)
        assertFalse(
            RumorWriter.RELEASE_TAG_ORDER == RumorWriter.COMMITMENT_TAG_ORDER,
            "§10.1 and §10.3 print `nenya` and `p` at opposite ends; a writer with one order for " +
                "both would disagree with one of the two examples",
        )
    }

    /**
     * The three messages the document prints no worked example for carry an order whose **set** is
     * held to what their decoder requires or accepts.
     *
     * The order itself is this writer's — each constant's KDoc says what it was modelled on — and
     * what can still be held to something outside this file is the set: every tag the decoder for
     * that message requires must be in the emission order, or the writer could never satisfy it.
     * That is the half that would go stale in silence.
     */
    @JsName("the_orders_with_no_worked_example_carry_every_tag_their_decoder_requires")
    @Test
    fun `the orders with no worked example carry every tag their decoder requires`() {
        // §7.4's three plus `type` plus §11.1's `status`, which `OrderStatusMessage.decode`
        // requires by name and which §5.2's "absent means active" must not supply.
        for (name in AttributedRumor.requiredTags() + ChannelVocabulary.TYPE + ChannelVocabulary.STATUS) {
            assertTrue(
                name in RumorWriter.ORDER_UPDATE_TAG_ORDER,
                "`$name` is required on a type=3 and this writer could never emit it",
            )
        }
        // §6.1's own list, minus the `order` tag §7.4 forbids there.
        for (name in listOf(ChannelVocabulary.ITEM, ChannelVocabulary.PRICE, ChannelVocabulary.VERSION)) {
            assertTrue(name in RumorWriter.PRIVATE_BID_TAG_ORDER, "§6.1 gives a bid `$name`")
        }
        assertFalse(
            ChannelVocabulary.ORDER in RumorWriter.PRIVATE_BID_TAG_ORDER,
            "§7.4: a type=6 is the one kind:16 that MUST NOT carry an `order` tag, and the way to " +
                "keep that is to have no position in the emission order for one",
        )
        // §7.4 puts a kind:14 outside its required set, so the only claim about a chat's order is
        // that it has a position for the `order` tag §7.4 says it MAY carry.
        assertTrue(ChannelVocabulary.ORDER in RumorWriter.CHAT_TAG_ORDER)
    }

    // -------------------------------------------------------------------------------------------
    // 2. The required sets, against the decoders.
    // -------------------------------------------------------------------------------------------

    /**
     * For every tag a decoder requires, removing it from a built event makes **that decoder** refuse
     * it naming **that tag**.
     *
     * A loop over a published list rather than hand-written controls. It is the assertion that holds
     * this writer's `required` lists equal to what the decoders really demand: a tag the writer
     * thinks is required and the decoder does not would make the removal pass, and a tag the decoder
     * requires and the writer does not emit would make the build fail before the removal.
     */
    @JsName("removing_any_required_tag_makes_the_matching_decoder_refuse_naming_it")
    @Test
    fun `removing any required tag makes the matching decoder refuse naming it`() {
        var checked = 0
        for (shape in RumorWriterFixtures.Shape.entries) {
            val required = requiredFor(shape) ?: continue
            assertTrue(required.isNotEmpty(), "$shape must require at least one tag")
            val event = RumorWriterFixtures.build(onlyCase(shape))
            for (name in required) {
                val stripped = without(event, name)
                assertTrue(
                    stripped.tags.size < event.tags.size,
                    "$shape's built event carries no `$name`, so removing it proves nothing",
                )
                val refusal = refusalFrom(shape, stripped)
                assertEquals(
                    name,
                    refusal,
                    "$shape: removing `$name` must be refused naming that tag",
                )
                checked++
            }
        }
        assertTrue(checked > 20, "the loop checked only $checked tags, which is not the vocabulary")
    }

    // -------------------------------------------------------------------------------------------
    // 3. §10.1's key-absence rule, and the mutation T41 names.
    // -------------------------------------------------------------------------------------------

    /**
     * §10.1: "The commitment message MUST NOT contain `decryption-key` or `decryption-nonce`. An
     * implementation MUST reject a commitment that does."
     *
     * The writer has no parameter for either, so an extension tag is the only way one could arrive,
     * and both are refused by name — the two are different mistakes with the same consequence and a
     * caller told only "forbidden tag" would have to go looking for which.
     */
    @JsName("a_commitment_carrying_s10_2s_key_or_nonce_as_an_extension_is_refused_by_name")
    @Test
    fun `a commitment carrying §10_2's key or nonce as an extension is refused by name`() {
        for (name in listOf(DeliveryTags.DECRYPTION_KEY, DeliveryTags.DECRYPTION_NONCE)) {
            val refused = RumorWriterFixtures.refused(
                RumorWriter.commitment(
                    envelope(extraTags = listOf(listOf(name, RumorWriterFixtures.keyFor(0)))),
                    RumorWriterFixtures.orderFor(0),
                    commitment(),
                    URL,
                ),
                "a commitment carrying `$name`",
            )
            assertEquals(ChannelRejection.COMMITMENT_CARRIES_KEY, refused.reason)
            assertEquals(name, refused.tag, "§10.1 names the two separately and so must the refusal")
        }
    }

    /**
     * T41's mutation, as a standing test rather than only as a run recorded in a commit message:
     * **if the commitment builder emitted a `decryption-key`, the decoder's own refusal is what
     * catches it.**
     *
     * The event is built legally and the tag is spliced in afterwards, which is exactly the event a
     * mutated builder would emit. `DeliveryCommitmentMessage.decode` refuses it as
     * [ChannelRejection.COMMITMENT_CARRIES_KEY] — the same constant the writer answers one step
     * earlier — so the two refusals name the same thing and the decoder is a real backstop rather
     * than an assumption. Without this, "the builder must not be the way around the decoder" would
     * be a claim about the builder alone.
     */
    @JsName("a_commitment_that_reached_the_wire_carrying_a_key_is_refused_by_the_decoder")
    @Test
    fun `a commitment that reached the wire carrying a key is refused by the decoder`() {
        val legal = RumorWriterFixtures.build(onlyCase(RumorWriterFixtures.Shape.COMMITMENT))
        // The decoder accepts the unmutated event, or the mutation below would prove nothing.
        DeliveryCommitmentMessage.decode(RumorWriterFixtures.bound(legal))

        for (name in listOf(DeliveryTags.DECRYPTION_KEY, DeliveryTags.DECRYPTION_NONCE)) {
            val mutated = WireEvent(
                legal.pubkey,
                legal.createdAt,
                legal.kind,
                legal.tags + listOf(listOf(name, RumorWriterFixtures.keyFor(0))),
                legal.content,
            )
            val refusal = runCatching {
                DeliveryCommitmentMessage.decode(RumorWriterFixtures.bound(mutated))
            }.exceptionOrNull() as? ChannelException
                ?: fail("§10.1 requires the decoder reject a commitment carrying `$name`")
            assertEquals(ChannelRejection.COMMITMENT_CARRIES_KEY, refusal.reason)
            assertEquals(name, refusal.tag)
        }
    }

    // -------------------------------------------------------------------------------------------
    // 4. §7.4's two unemittable values.
    // -------------------------------------------------------------------------------------------

    /**
     * §7.4: "Nenya v1 MUST NOT emit `type=4` and MUST ignore it on read."
     *
     * Made structural rather than refused: there is no entry point on [RumorWriter] that produces
     * one, which the corpus's own shape enumeration asserts below. [ChannelTags.type] is still the
     * refusal for any caller that reaches the tag another way, and `OrderMessageKind.SHIPPING` is
     * still *readable*, which is the asymmetry §7.4 states.
     */
    @JsName("s7_4s_reserved_type_4_has_no_entry_point_and_is_refused_at_the_tag")
    @Test
    fun `§7_4's reserved type 4 has no entry point and is refused at the tag`() {
        assertFalse(OrderMessageKind.SHIPPING.emittable, "§7.4 reserves type=4")
        assertFalse(
            OrderMessageKind.UNKNOWN.emittable,
            "§7.4's unknown sink is a treatment of somebody else's value, not a value",
        )

        val reserved = runCatching { ChannelTags.type(OrderMessageKind.SHIPPING) }
            .exceptionOrNull() as? ChannelException
            ?: fail("§7.4 forbids emitting type=4")
        assertEquals(ChannelRejection.RESERVED_TYPE, reserved.reason)
        assertEquals(ChannelVocabulary.TYPE, reserved.tag)

        val unknown = runCatching { ChannelTags.type(OrderMessageKind.UNKNOWN) }
            .exceptionOrNull() as? ChannelException
            ?: fail("§7.4's unknown `type` has no wire form")
        assertEquals(ChannelRejection.UNKNOWN_IS_NOT_EMITTABLE, unknown.reason)

        // And the corpus covers §7.4's two tables exactly, minus the one row §7.4 forbids emitting.
        assertEquals(
            OrderMessageKind.entries.filter { it.emittable }.map { it.type }.toSet(),
            RumorWriterFixtures.Shape.entries.mapNotNull { emittedType(it) }.toSet(),
            "every emittable §7.4 `type` must have a shape in the corpus, and no other",
        )
    }

    /**
     * §11.1's `unknown` is the required treatment of an unrecognised status, not a token, so a
     * status update carrying it is refused rather than emitted.
     *
     * The paired positive control is the corpus itself, which draws every [OrderState] that has a
     * token and round-trips all of them.
     */
    @JsName("s11_1s_unknown_status_is_not_emittable")
    @Test
    fun `§11_1's unknown status is not emittable`() {
        val refused = RumorWriterFixtures.refused(
            RumorWriter.statusUpdate(
                envelope(),
                RumorWriterFixtures.orderFor(0),
                OrderState.UNKNOWN,
            ),
            "a status update claiming §11.1's `unknown`",
        )
        assertEquals(ChannelRejection.UNKNOWN_IS_NOT_EMITTABLE, refused.reason)
        assertEquals(ChannelVocabulary.STATUS, refused.tag)

        // The positive control that stops the above passing over a writer that refuses every status.
        val accepted = RumorWriterFixtures.built(
            RumorWriter.statusUpdate(envelope(), RumorWriterFixtures.orderFor(0), OrderState.ACCEPTED),
            "a status update claiming `accepted`",
        )
        assertEquals(
            listOf(ChannelVocabulary.STATUS, OrderState.ACCEPTED.token),
            accepted.tags.single { it[0] == ChannelVocabulary.STATUS },
        )
    }

    /**
     * §7.4 and §6.1: a `type=6` MUST NOT carry an `order` tag, and the entry point takes no order
     * id at all — so an extension tag is the only way one could arrive, and it is refused.
     */
    @JsName("an_order_tag_on_a_private_bid_is_refused_as_s7_4s_prohibition")
    @Test
    fun `an order tag on a private bid is refused as §7_4's prohibition`() {
        val refused = RumorWriterFixtures.refused(
            RumorWriter.privateBid(
                envelope(
                    extraTags = listOf(
                        listOf(ChannelVocabulary.ORDER, RumorWriterFixtures.orderFor(0).toHex()),
                    ),
                ),
                item(),
                plainTerms(),
            ),
            "a private bid carrying an `order` tag",
        )
        assertEquals(ChannelRejection.FORBIDDEN_ORDER_TAG, refused.reason)
        assertEquals(ChannelVocabulary.ORDER, refused.tag)

        // The positive control: the same bid without it builds, and attributes to a rumor whose
        // decoded order id is `null` — which is what §7.4 requires rather than a missing read.
        val built = RumorWriterFixtures.built(
            RumorWriter.privateBid(envelope(), item(), plainTerms()),
            "a legal private bid",
        )
        assertNull(RumorWriterFixtures.bound(built).order, "§7.4: a type=6 carries no order id")
    }

    // -------------------------------------------------------------------------------------------
    // 5. §4.1's event fields and §4.5's version.
    // -------------------------------------------------------------------------------------------

    /** §4.3 fixes an author pubkey as exactly 64 hex characters; §4.1 hashes it into the id. */
    @JsName("a_malformed_author_pubkey_is_refused_as_an_event_field")
    @Test
    fun `a malformed author pubkey is refused as an event field`() {
        for (pubkey in listOf("", "abc", RumorWriterFixtures.pubkeyFor(0).dropLast(1), "g".repeat(64))) {
            val refused = RumorWriterFixtures.refused(
                RumorWriter.chat(envelope(authorPubkey = pubkey)),
                "a chat authored by a ${pubkey.length}-character pubkey",
            )
            assertEquals(ChannelRejection.MALFORMED_EVENT_FIELD, refused.reason)
            assertNull(refused.tag, "§4.1's five fields sit beside the tag array, not in it")
        }
    }

    /** §4.3 fixes every timestamp as a non-negative integer, and `created_at` is one. */
    @JsName("a_negative_created_at_is_refused_as_an_event_field")
    @Test
    fun `a negative created_at is refused as an event field`() {
        val refused = RumorWriterFixtures.refused(
            RumorWriter.chat(envelope(createdAt = -1L)),
            "a chat dated before the epoch",
        )
        assertEquals(ChannelRejection.MALFORMED_EVENT_FIELD, refused.reason)

        // Zero is the boundary and is legal: §4.3 says non-negative, not positive.
        RumorWriterFixtures.built(RumorWriter.chat(envelope(createdAt = 0L)), "a chat dated at the epoch")
    }

    /**
     * §4.5 and §7.4's collision rule: a version this build does not implement is refused on write.
     *
     * Deliberately stricter than the read side, and the KDoc on the check says why — on read a
     * conformant peer may implement a later revision, and on write there is no peer to accommodate.
     * The control covers a `type=5`, where §7.4 makes it a MUST, and a `kind:14`, where it does not
     * and this writer refuses it anyway.
     */
    @JsName("a_nenya_version_this_build_does_not_implement_is_refused_on_write")
    @Test
    fun `a nenya version this build does not implement is refused on write`() {
        val foreign = NenyaProtocol.VERSION + 1
        val builds = listOf(
            "a commitment" to RumorWriter.commitment(
                envelope(nenyaVersion = foreign),
                RumorWriterFixtures.orderFor(0),
                commitment(),
                URL,
            ),
            "a chat" to RumorWriter.chat(envelope(nenyaVersion = foreign)),
        )
        for ((what, build) in builds) {
            val refused = RumorWriterFixtures.refused(build, "$what claiming nenya $foreign")
            assertEquals(ChannelRejection.UNSUPPORTED_VERSION, refused.reason)
            assertEquals(ChannelVocabulary.VERSION, refused.tag)
        }

        // And the decoder really does discard a foreign `type=5`, which is why the writer refuses:
        // the refusal is not this writer inventing a rule but declining to emit what it cannot read.
        val legal = RumorWriterFixtures.build(onlyCase(RumorWriterFixtures.Shape.COMMITMENT))
        val rewritten = WireEvent(
            legal.pubkey,
            legal.createdAt,
            legal.kind,
            legal.tags.map {
                if (it[0] == ChannelVocabulary.VERSION) listOf(it[0], foreign.toString()) else it
            },
            legal.content,
        )
        val refusal = runCatching { RumorWriterFixtures.bound(rewritten) }
            .exceptionOrNull() as? ChannelException
            ?: fail("§7.4 requires a type=5 carrying a foreign `nenya` version be ignored")
        assertEquals(ChannelRejection.UNSUPPORTED_VERSION, refusal.reason)
    }

    // -------------------------------------------------------------------------------------------
    // 6. §8.1's arities, §4.4's price rule and §7.5's cross-check.
    // -------------------------------------------------------------------------------------------

    /** §8.1 makes the `fee` recipient a part of the term, so one without a term is an arity error. */
    @JsName("a_fee_recipient_with_no_fee_term_is_refused")
    @Test
    fun `a fee recipient with no fee term is refused`() {
        val refused = RumorWriterFixtures.refused(
            RumorWriter.proposal(
                envelope(),
                RumorWriterFixtures.orderFor(0),
                item(),
                OrderTerms.of(Msat.ofSat(50_000L), FeeTerm.Absent),
                feeRecipient = RumorWriterFixtures.pubkeyFor(7),
            ),
            "a proposal naming a fee recipient with no term",
        )
        assertEquals(ChannelRejection.MALFORMED_TAG, refused.reason)
        assertEquals(ChannelVocabulary.FEE, refused.tag)
    }

    /**
     * §8.1's three shapes produce three different events, which is the distinction a writer
     * collapses by accident: [FeeTerm.Absent] and a stated zero agree on the number and disagree on
     * the wire.
     */
    @JsName("s8_1s_absent_term_stated_zero_and_stated_fee_are_three_different_events")
    @Test
    fun `§8_1's absent term, stated zero and stated fee are three different events`() {
        fun feeTags(term: FeeTerm, recipient: String?): List<List<String>> =
            RumorWriterFixtures.built(
                RumorWriter.proposal(
                    envelope(),
                    RumorWriterFixtures.orderFor(0),
                    item(),
                    OrderTerms.of(Msat.ofSat(50_000L), term),
                    feeRecipient = recipient,
                ),
                "a proposal with fee $term",
            ).tags.filter { it[0] == ChannelVocabulary.FEE }

        assertEquals(emptyList(), feeTags(FeeTerm.Absent, null), "§8.1's absent term has no wire form")
        assertEquals(
            listOf(listOf(ChannelVocabulary.FEE, "0")),
            feeTags(FeeTerm.of(0), null),
            "§8.1: a stated zero is a signed statement that no fee applies, and carries no recipient",
        )
        val recipient = RumorWriterFixtures.pubkeyFor(7)
        assertEquals(
            listOf(listOf(ChannelVocabulary.FEE, "250", recipient)),
            feeTags(FeeTerm.of(250), recipient),
            "§8.1 makes the recipient REQUIRED above zero basis points",
        )
    }

    /**
     * §6.1's bid carries §5.3's satoshi-denominated `price` row, so an amount that does not land on
     * a whole satoshi is refused rather than rounded — §4.4's rule for that row.
     */
    @JsName("a_private_bid_whose_price_is_not_whole_satoshis_is_refused")
    @Test
    fun `a private bid whose price is not whole satoshis is refused`() {
        val refused = RumorWriterFixtures.refused(
            RumorWriter.privateBid(
                envelope(),
                item(),
                OrderTerms.of(Msat.ofMsat(1_500L), FeeTerm.Absent),
            ),
            "a bid priced at 1500 msat",
        )
        assertEquals(ChannelRejection.MALFORMED_TAG, refused.reason)
        assertEquals(ChannelVocabulary.PRICE, refused.tag)

        // The paired positive control: the same amount is legal on a proposal, whose `amount_msat`
        // is millisatoshi-denominated. The refusal is §4.4's about one row, not about the amount.
        val proposal = RumorWriterFixtures.built(
            RumorWriter.proposal(
                envelope(),
                RumorWriterFixtures.orderFor(0),
                item(),
                OrderTerms.of(Msat.ofMsat(1_500L), FeeTerm.Absent),
            ),
            "a proposal priced at 1500 msat",
        )
        assertEquals(
            listOf(ChannelVocabulary.AMOUNT_MSAT, "1500"),
            proposal.tags.single { it[0] == ChannelVocabulary.AMOUNT_MSAT },
        )
    }

    /**
     * §7.5: "If both are present and `amount × 1000 ≠ amount_msat`, the implementation MUST reject
     * the message. It MUST NOT prefer one and continue."
     *
     * Applied on write, by the same route the decoder takes — through `Msat.ofSat`, which bounds the
     * operand against §4.4's cap before multiplying, so no value is ever multiplied into a silent
     * 64-bit wrap.
     */
    @JsName("a_proposal_whose_amount_disagrees_with_amount_msat_is_refused")
    @Test
    fun `a proposal whose amount disagrees with amount_msat is refused`() {
        val terms = OrderTerms.of(Msat.ofSat(50_000L), FeeTerm.Absent)
        val refused = RumorWriterFixtures.refused(
            RumorWriter.proposal(
                envelope(),
                RumorWriterFixtures.orderFor(0),
                item(),
                terms,
                amountSat = 49_999L,
            ),
            "a proposal whose `amount` disagrees with `amount_msat`",
        )
        assertEquals(ChannelRejection.MALFORMED_TAG, refused.reason)
        assertEquals(ChannelVocabulary.AMOUNT, refused.tag)

        // The paired positive control: the agreeing pair builds, and the decoder — which makes the
        // same cross-check — accepts it. Both halves, or "refused" would be about any `amount` tag.
        val built = RumorWriterFixtures.built(
            RumorWriter.proposal(
                envelope(),
                RumorWriterFixtures.orderFor(0),
                item(),
                terms,
                amountSat = 50_000L,
            ),
            "a proposal whose `amount` agrees",
        )
        assertEquals(
            terms.split.price,
            OrderProposal.decode(RumorWriterFixtures.bound(built)).price,
            "§7.5's cross-check must pass on this writer's own output",
        )
    }

    /**
     * §7.5's `amount` compatibility tag is the one bare `Long` in this writer's API, so it is the
     * one parameter a caller can hand a negative or an above-supply figure — and both are refused
     * **by value**, which is [RumorBuild]'s whole contract.
     *
     * `Msat.ofSat` answers both with a `MoneyException`, which is not a `TagException` and so is
     * seen by none of `assemble`'s three catch clauses. Before this control existed it sailed
     * straight out of a function documented to refuse by value, so an embedding client passing an
     * unchecked satoshi figure from a form field got a crash instead of a `Refused`. The
     * `runCatching` is the assertion: a throw here fails the test naming what escaped.
     */
    @JsName("a_negative_or_above_supply_amount_is_refused_by_value_and_never_thrown")
    @Test
    fun `a negative or above-supply amount is refused by value and never thrown`() {
        for ((what, satoshis) in listOf("negative" to -1L, "above supply" to Long.MAX_VALUE)) {
            val build = runCatching {
                RumorWriter.proposal(
                    envelope(),
                    RumorWriterFixtures.orderFor(0),
                    item(),
                    plainTerms(),
                    amountSat = satoshis,
                )
            }.getOrElse {
                fail("§7.5's `amount` must be refused by value; a $what one threw $it")
            }
            val refused = RumorWriterFixtures.refused(build, "a proposal whose `amount` is $what")
            assertEquals(ChannelVocabulary.AMOUNT, refused.tag, what)
            assertTrue(
                refused.reason == ChannelRejection.MALFORMED_TAG ||
                    refused.reason == ChannelRejection.AMOUNT_ABOVE_SUPPLY,
                "a $what `amount` must name the §4.4 rule it broke; named ${refused.reason}",
            )
        }
    }

    /**
     * A `fee` recipient that is not a pubkey is refused naming the `fee` row, and **not** as
     * §10.2's key material.
     *
     * [ChannelRejection.MALFORMED_KEY_MATERIAL] is written about `decryption-key` and
     * `decryption-nonce`. Answering it here would send a reader to §10.2 for a mistake made on
     * §8.1's row, and would say this library had been handed malformed *key material* when what it
     * was handed was a malformed pubkey. Both spellings are drawn, because they arrive by different
     * `TagRejection`s — a non-hex character and a wrong length — and reporting one of them as
     * §10.2's constant split a single mistake across two reasons, neither about a pubkey.
     */
    @JsName("a_fee_recipient_that_is_not_a_pubkey_is_refused_naming_the_fee_row")
    @Test
    fun `a fee recipient that is not a pubkey is refused naming the fee row`() {
        for (recipient in listOf("g".repeat(64), RumorWriterFixtures.pubkeyFor(7).dropLast(1), "abc")) {
            val refused = RumorWriterFixtures.refused(
                RumorWriter.proposal(
                    envelope(),
                    RumorWriterFixtures.orderFor(0),
                    item(),
                    OrderTerms.of(Msat.ofSat(50_000L), FeeTerm.of(250)),
                    feeRecipient = recipient,
                ),
                "a proposal naming a ${recipient.length}-character fee recipient",
            )
            assertEquals(ChannelVocabulary.FEE, refused.tag)
            assertEquals(
                ChannelRejection.MALFORMED_TAG,
                refused.reason,
                "§10.2's constant is about a decryption key, not about §8.1's recipient",
            )
        }
    }

    /**
     * A `kind:14` built with an `order` **parameter** and an `order` **extension** would carry two,
     * so it is refused as §4.3's duplicate — not as [ChannelRejection.ROW_IS_NOT_AN_EXTENSION],
     * whose own text says "nothing is duplicated" and tells the caller the parameter "was left
     * `null`".
     *
     * A chat is where that went wrong, and the reason is worth keeping: §7.4 puts a `kind:14`
     * outside the *decoders'* duplicate rule, so deriving "would this message carry two?" from the
     * set of rows the decoders refuse a second occurrence of answered a different question than the
     * one being asked. All three branches are drawn — the duplicate, the genuine wrong door with no
     * parameter supplied, and §5.3's `0–n` row where two really is legal.
     */
    @JsName("a_chat_carrying_an_order_parameter_and_an_order_extension_is_a_duplicate")
    @Test
    fun `a chat carrying an order parameter and an order extension is a duplicate`() {
        val extras = listOf(listOf(ChannelVocabulary.ORDER, RumorWriterFixtures.orderFor(1).toHex()))

        val duplicate = RumorWriterFixtures.refused(
            RumorWriter.chat(envelope(extraTags = extras), RumorWriterFixtures.orderFor(0)),
            "a chat carrying an `order` parameter and an `order` extension",
        )
        assertEquals(ChannelRejection.DUPLICATE_TAG, duplicate.reason)
        assertEquals(ChannelVocabulary.ORDER, duplicate.tag)

        // No parameter: the extension would be the only occurrence, so it is the other reason.
        val wrongDoor = RumorWriterFixtures.refused(
            RumorWriter.chat(envelope(extraTags = extras), order = null),
            "a chat carrying only an `order` extension",
        )
        assertEquals(ChannelRejection.ROW_IS_NOT_AN_EXTENSION, wrongDoor.reason)
        assertEquals(ChannelVocabulary.ORDER, wrongDoor.tag)

        // §5.3 gives `p` cardinality `0–n`, so a second one is not a duplicate even though this
        // chat already emits one — it is still the wrong door, and that is the distinction.
        val counterparty = RumorWriterFixtures.refused(
            RumorWriter.chat(
                envelope(
                    extraTags = listOf(
                        listOf(ChannelVocabulary.COUNTERPARTY, RumorWriterFixtures.pubkeyFor(3)),
                    ),
                ),
            ),
            "a chat carrying a `p` extension",
        )
        assertEquals(ChannelRejection.ROW_IS_NOT_AN_EXTENSION, counterparty.reason)
        assertEquals(ChannelVocabulary.COUNTERPARTY, counterparty.tag)
    }

    // -------------------------------------------------------------------------------------------
    // 7. §10.2's write encoding.
    // -------------------------------------------------------------------------------------------

    /**
     * §10.2: both the key and the nonce "MUST be emitted as lowercase hex (64 and 24 characters
     * respectively)".
     *
     * This is the one place the writer is stricter than the decoder, and the asymmetry is §10.2's
     * own: the decoder accepts standard base64 and either hex case because "NIP-17 does not specify
     * the encoding and other clients may emit it", which is a rule about a counterparty's bytes.
     * The round trip cannot see this direction at all — a writer that emitted base64 would
     * round-trip perfectly — so it is asserted directly, with the decoder's acceptance of the same
     * values as the paired control.
     */
    @JsName("s10_2s_key_and_nonce_are_emitted_only_as_lowercase_hex")
    @Test
    fun `§10_2's key and nonce are emitted only as lowercase hex`() {
        val key = RumorWriterFixtures.keyFor(0)
        val nonce = RumorWriterFixtures.nonceFor(0)

        fun build(k: String, n: String) = RumorWriter.release(
            envelope(),
            RumorWriterFixtures.orderFor(0),
            release(),
            k,
            n,
        )

        // The legal pair, first, so every refusal below is about the spelling and not the fixture.
        RumorWriterFixtures.built(build(key, nonce), "a release with §10.2's mandatory spellings")

        val illegal = listOf(
            "an uppercase key" to Pair(key.uppercase(), nonce),
            "an uppercase nonce" to Pair(key, nonce.uppercase()),
            "a short key" to Pair(key.dropLast(2), nonce),
            "a long nonce" to Pair(key, nonce + "ab"),
            // Standard base64 of 32 bytes is 44 characters: the decoder accepts it on read and
            // §10.2 forbids emitting it, which is the whole of the asymmetry. Both halves of that
            // sentence are asserted at the foot of this test.
            "a base64-shaped key" to Pair(BASE64_KEY, nonce),
        )
        for ((what, pair) in illegal) {
            val refused = RumorWriterFixtures.refused(build(pair.first, pair.second), what)
            assertEquals(ChannelRejection.MALFORMED_KEY_MATERIAL, refused.reason, what)
            assertTrue(
                refused.tag == DeliveryTags.DECRYPTION_KEY || refused.tag == DeliveryTags.DECRYPTION_NONCE,
                "$what must name §10.2's tag it was about; named ${refused.tag}",
            )
            assertFalse(
                pair.first in refused.detail || pair.second in refused.detail,
                "§12 item 11 and STOP RULE 14: a refusal MUST NOT echo key material",
            )
        }

        // The paired control that proves the decoder really is the more permissive of the two, so
        // the strictness above is §10.2's write rule and not this writer refusing what it cannot
        // read. **Both** of the spellings the writer refused above are decoded here, because each
        // is a separate claim: §10.2 says the decoder accepts "either hex case" and "standard
        // base64", and asserting only the uppercase one would leave the base64 case above saying
        // "the decoder accepts it on read" with nothing behind it. 44 characters of standard base64
        // is 32 bytes, which is exactly §10.2's key length.
        val legal = RumorWriterFixtures.built(build(key, nonce), "a legal release")
        for ((what, spelling) in listOf("uppercase" to key.uppercase(), "base64" to BASE64_KEY)) {
            val rewritten = WireEvent(
                legal.pubkey,
                legal.createdAt,
                NenyaKind.FILE_MESSAGE,
                legal.tags.map {
                    if (it[0] == DeliveryTags.DECRYPTION_KEY) listOf(it[0], spelling) else it
                },
                legal.content,
            )
            DeliverableReleaseMessage.decode(RumorWriterFixtures.bound(rewritten))
            // And the writer refuses the very spelling the decoder just accepted, which is the
            // asymmetry stated as one fact rather than as two halves in different tests.
            assertEquals(
                ChannelRejection.MALFORMED_KEY_MATERIAL,
                RumorWriterFixtures.refused(build(spelling, nonce), "a $what key").reason,
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // 8. §4.3's extension rules.
    // -------------------------------------------------------------------------------------------

    /**
     * A tag this writer spells, handed in as an extension, is refused for the reason that tag's rule
     * gives — a duplicate where the message really would carry two, and
     * [ChannelRejection.ROW_IS_NOT_AN_EXTENSION] where it would carry one.
     *
     * Both cases, because the distinction is the caller's next move: a duplicate is an event §4.3
     * requires be rejected, and the other is a value passed to the wrong door.
     */
    @JsName("a_tag_this_writer_spells_handed_in_as_an_extension_is_refused_for_its_own_reason")
    @Test
    fun `a tag this writer spells handed in as an extension is refused for its own reason`() {
        // `order` is emitted from the parameter, so a second one really would be two.
        val duplicate = RumorWriterFixtures.refused(
            RumorWriter.proposal(
                envelope(
                    extraTags = listOf(
                        listOf(ChannelVocabulary.ORDER, RumorWriterFixtures.orderFor(1).toHex()),
                    ),
                ),
                RumorWriterFixtures.orderFor(0),
                item(),
                plainTerms(),
            ),
            "a proposal carrying a second `order` tag",
        )
        assertEquals(ChannelRejection.DUPLICATE_TAG, duplicate.reason)
        assertEquals(ChannelVocabulary.ORDER, duplicate.tag)

        // `deliver_by` is a row this writer spells and this proposal's terms leave absent, so there
        // is nothing to duplicate — the caller passed it to the wrong door.
        val wrongDoor = RumorWriterFixtures.refused(
            RumorWriter.proposal(
                envelope(extraTags = listOf(listOf(ChannelVocabulary.DELIVER_BY, "1757066034"))),
                RumorWriterFixtures.orderFor(0),
                item(),
                plainTerms(),
            ),
            "a proposal carrying `deliver_by` as an extension",
        )
        assertEquals(ChannelRejection.ROW_IS_NOT_AN_EXTENSION, wrongDoor.reason)
        assertEquals(ChannelVocabulary.DELIVER_BY, wrongDoor.tag)

        // And §4.3's empty tag array, which it requires be rejected rather than skipped.
        val empty = RumorWriterFixtures.refused(
            RumorWriter.chat(envelope(extraTags = listOf(emptyList()))),
            "a chat carrying an empty tag array",
        )
        assertEquals(ChannelRejection.MALFORMED_TAG, empty.reason)
    }

    /**
     * §7.4: "A `subject` tag (NIP-17) MAY be present on any rumor. It is display metadata only; an
     * implementation MUST NOT derive any term or state from it."
     *
     * This writer has **no parameter** for one, which is that rule made structural — so a client's
     * `subject` rides as an extension, is emitted verbatim and last (§4.3), and comes back out of
     * `AttributedRumor.unknownTags` having yielded no term anywhere. Extensions emitted last is the
     * other half: a client's extension can never displace a row from the position §4.1 hashes it in.
     */
    @JsName("s7_4s_subject_rides_as_an_extension_verbatim_and_last")
    @Test
    fun `§7_4's subject rides as an extension, verbatim and last`() {
        val extras = listOf(listOf("subject", "a thread title"), listOf("client", "a", "b"))
        val event = RumorWriterFixtures.built(
            RumorWriter.commitment(
                envelope(extraTags = extras),
                RumorWriterFixtures.orderFor(0),
                commitment(),
                URL,
            ),
            "a commitment carrying a subject",
        )

        assertEquals(
            extras,
            event.tags.takeLast(extras.size),
            "§4.3: extensions are emitted verbatim and last, in the order given",
        )
        val rumor = RumorWriterFixtures.bound(event)
        assertEquals(extras, rumor.unknownTags.filter { it[0] in setOf("subject", "client") })
        // And it yielded no term: the decode is identical to the same commitment without them.
        val decoded = DeliveryCommitmentMessage.decode(rumor)
        assertEquals(commitment().x.toHex(), decoded.commitment.x.toHex())
    }

    // -------------------------------------------------------------------------------------------
    // Helpers.
    // -------------------------------------------------------------------------------------------

    /**
     * The seeded corpus's first case of [shape] — the one at that shape's ordinal in a run of eight.
     *
     * **Not** a minimal case: it carries whatever optional rows and extension tags the generator
     * drew for it, which is why every caller that strips a tag asserts the tag was there first
     * (`stripped.tags.size < event.tags.size`) rather than assuming a fixed shape.
     */
    private fun onlyCase(shape: RumorWriterFixtures.Shape): RumorWriterFixtures.Case =
        RumorWriterFixtures.cases(RumorWriterFixtures.Shape.entries.size)
            .single { it.shape == shape }

    /** [event] with every occurrence of [name] removed, which is what the removal loop builds. */
    private fun without(event: WireEvent, name: String): WireEvent = WireEvent(
        event.pubkey,
        event.createdAt,
        event.kind,
        event.tags.filter { it[0] != name },
        event.content,
    )

    /** The tags the decoder for [shape] requires, or `null` where §7.4 states no required set. */
    private fun requiredFor(shape: RumorWriterFixtures.Shape): List<String>? = when (shape) {
        // §7.4 puts a kind:14 outside its required-tag rule entirely, so there is nothing to remove.
        RumorWriterFixtures.Shape.CHAT -> null
        RumorWriterFixtures.Shape.PROPOSAL -> OrderProposal.requiredTags()
        RumorWriterFixtures.Shape.COMMITMENT -> DeliveryCommitmentMessage.requiredTags()
        RumorWriterFixtures.Shape.RELEASE -> DeliverableReleaseMessage.requiredTags()
        // §11.1's `status`, which `OrderStatusMessage.decode` requires by name, plus §7.4's.
        RumorWriterFixtures.Shape.ORDER_UPDATE ->
            AttributedRumor.requiredTags() + ChannelVocabulary.TYPE + ChannelVocabulary.STATUS
        // §7.4's two survivors on a type=6, plus `type`. `item` is §5.3's, enforced by the tag
        // codec under the context T13 declares rather than by §7.4's set.
        RumorWriterFixtures.Shape.PRIVATE_BID ->
            AttributedRumor.requiredTags() - ChannelVocabulary.ORDER + ChannelVocabulary.TYPE
        // §8.6's and §9.2's are `PaymentWriterTest`'s, which runs the same loop over them.
        RumorWriterFixtures.Shape.PAYMENT_REQUEST, RumorWriterFixtures.Shape.RECEIPT -> null
    }

    /**
     * The tag the decoder for [shape] names when it refuses [event], through the decoder §7.4 gives
     * that message.
     *
     * A `kind:15`, a `type=1`, a `type=3` and a `type=5` each have their own decoder and a `type=6`
     * has none beyond §7.2's attribution, so the dispatch is explicit rather than derived — which is
     * also what makes the loop's assertion about the right refusal.
     */
    private fun refusalFrom(shape: RumorWriterFixtures.Shape, event: WireEvent): String? {
        val thrown = runCatching {
            val rumor = RumorWriterFixtures.bound(event)
            when (shape) {
                RumorWriterFixtures.Shape.PROPOSAL -> OrderProposal.decode(rumor)
                RumorWriterFixtures.Shape.ORDER_UPDATE -> OrderStatusMessage.decode(rumor)
                RumorWriterFixtures.Shape.COMMITMENT -> DeliveryCommitmentMessage.decode(rumor)
                RumorWriterFixtures.Shape.RELEASE -> DeliverableReleaseMessage.decode(rumor)
                // §7.2's attribution IS the decoder for a type=6: §7.4's envelope, its `order`
                // prohibition and its version-collision rule all run there and nowhere else.
                RumorWriterFixtures.Shape.PRIVATE_BID -> rumor
                else -> fail("$shape has no decoder in this package")
            }
        }.exceptionOrNull()
        val refusal = assertNotNull(
            thrown as? ChannelException,
            "$shape: a message missing a required tag must be refused; got $thrown",
        )
        return refusal.tag
    }

    /** §7.4's `type` number a shape emits, or `null` for a kind that carries no discriminator. */
    private fun emittedType(shape: RumorWriterFixtures.Shape): Int? = when (shape) {
        RumorWriterFixtures.Shape.PROPOSAL -> OrderMessageKind.PROPOSAL.type
        RumorWriterFixtures.Shape.PAYMENT_REQUEST -> OrderMessageKind.PAYMENT_REQUEST.type
        RumorWriterFixtures.Shape.ORDER_UPDATE -> OrderMessageKind.STATUS_UPDATE.type
        RumorWriterFixtures.Shape.COMMITMENT -> OrderMessageKind.DELIVERY_COMMITMENT.type
        RumorWriterFixtures.Shape.PRIVATE_BID -> OrderMessageKind.PRIVATE_BID.type
        else -> null
    }
}
