package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagRejection
import dev.eryalabs.nenya.wire.EventId
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §7.5's order proposal and §7.6's acceptance, control by control.
 *
 * Every negative control asserts the **reason** and not merely that something was refused, and every
 * one is paired with the positive control that proves the rule was modelled rather than inverted: a
 * disagreeing `amount` is refused *and* an agreeing one parses *and* an absent one parses, because
 * an implementation that required the compatibility tag refuses conformant peers. An over-strict
 * codec fails the second half; one that accepts everything fails the first.
 */
class ProposalCodecTest {

    // ---------------------------------------------------------------------------------------
    // §7.5 — the proposal, anchored to the document's own worked example.
    // ---------------------------------------------------------------------------------------

    /**
     * The anchor: §7.5's worked JSON example, parsed out of `spec/NENYA-1.md` at test time, holds
     * this codec's readable set **equal** to the nine tags the document prints — minus exactly two
     * extras, each named here with the clause it comes from.
     *
     * The extras are not a weakening. `amount` is §7.5's own GammaMarkets MAY — "A GammaMarkets
     * `["amount", "<sats>"]` tag MAY additionally be present for compatibility" — which the example
     * does not print and which this codec must accept. `subject` is §7.4's display-only MAY, which
     * "MAY be present on any rumor" and which §4.3 requires round-trip. A set equality written
     * without them would be false by construction, and closing it by weakening the assertion is
     * exactly what STOP RULE 1 forbids.
     */
    @JsName("s7_5_s_worked_example_names_exactly_the_terms_this_codec_reads")
    @Test
    fun `§7-5's worked example names exactly the terms this codec reads`() {
        val printed = Section75.exampleTagNames.toSet()
        val readable = OrderProposal.readableTags().toSet()
        val documentedExtras = setOf(ChannelTags.AMOUNT, ChannelVocabulary.SUBJECT)

        assertTrue(printed.isNotEmpty(), "an empty parse would make this equality \"empty equals empty\"")
        assertTrue(readable.isNotEmpty())
        assertEquals(
            printed,
            readable - documentedExtras,
            "§7.5's example in ${Section75.specPath()} prints $printed; this codec reads $readable. " +
                "The two documented extras are `${ChannelTags.AMOUNT}` (§7.5's GammaMarkets MAY, " +
                "which the example does not print) and `${ChannelVocabulary.SUBJECT}` (§7.4's " +
                "display-only MAY, which MUST round-trip on any rumor)",
        )
        assertTrue(
            documentedExtras.all { it in readable },
            "both extras must actually be in the readable set, or the subtraction above removes " +
                "nothing and the equality is about a different claim",
        )
        assertEquals(
            OrderProposal.readableTags().size,
            readable.size,
            "the readable set names each tag once",
        )
    }

    /** §7.5's proposal, whole: every term reaches the shape §11.2's transition function reads. */
    @JsName("a_well_formed_proposal_decodes_into_the_terms_the_state_machine_consumes")
    @Test
    fun `a well-formed proposal decodes into the terms the state machine consumes`() {
        val tags = ProposalFixtures.proposalTags()

        val proposal = ProposalFixtures.proposal(tags)

        assertEquals(
            Msat.ofSat(ProposalFixtures.PRICE_SAT),
            proposal.price,
            "§7.5: `amount_msat` is the price in millisatoshis — what the provider receives",
        )
        assertEquals(proposal.price, proposal.terms.split.price)
        assertEquals(ItemRef.CANONICAL_QUANTITY, proposal.item.quantity)
        assertEquals(
            ProposalFixtures.coordinate(0),
            proposal.item.coordinate.toTagValue(),
            "§7.5: `item` references the listing coordinate the proposal derives from",
        )
        assertEquals(
            ProposalFixtures.CREATED_AT + ProposalFixtures.ACCEPTANCE_WINDOW,
            proposal.terms.expiration,
        )
        assertEquals(
            ProposalFixtures.CREATED_AT + ProposalFixtures.DELIVERY_WINDOW,
            proposal.terms.deliverBy,
        )
        assertEquals(ProposalFixtures.orderHex(0), proposal.order.toHex())
        assertEquals(ProposalFixtures.pubkey(0), proposal.buyer)
        // And it is the genesis event §11.2's first row has no from-state for.
        assertEquals(proposal.terms, proposal.asOrderEvent().terms)
        assertEquals(proposal.createdAt, proposal.asOrderEvent().createdAt)
    }

    /** §4.3: decoding and re-encoding must reproduce the id T8 computed, byte for byte. */
    @JsName("decode_then_encode_reproduces_the_identical_event_id")
    @Test
    fun `decode then encode reproduces the identical event id`() {
        val tags = ProposalFixtures.proposalTags()
        val bound = ProposalFixtures.bound(tags)

        val proposal = OrderProposal.decode(bound)

        assertEquals(tags, proposal.encode().tags, "§4.3: not a tag reordered, not a tag dropped")
        assertEquals(bound.id, EventId.of(proposal.encode()))
        assertEquals(bound.id, proposal.id)
    }

    /**
     * §7.5's and §7.4's REQUIRED tags, proved by removing each in turn from a published list rather
     * than by six hand-written controls that could silently stop covering a seventh.
     */
    @JsName("every_required_tag_is_refused_by_name_when_it_is_missing_from_a_proposal")
    @Test
    fun `every required tag is refused by name when it is missing from a proposal`() {
        val required = OrderProposal.requiredTags()

        assertTrue(required.isNotEmpty(), "the removal loop must not iterate over nothing")
        for (tag in required) {
            val whole = ProposalFixtures.proposalTags()
            assertTrue(whole.any { it[0] == tag }, "the fixture must carry `$tag` to remove")
            val refused = assertFailsWith<ChannelException> {
                ProposalFixtures.proposal(ProposalFixtures.without(whole, tag))
            }
            assertEquals(ChannelRejection.MISSING_REQUIRED_TAG, refused.reason, tag)
            assertEquals(tag, refused.tag, "the refusal must name the tag that went missing")
        }
    }

    /**
     * §7.5: "exactly one `item` tag MUST be present". Both failures name the tag, with different
     * reasons, because "you sent two" and "you sent none" have different fixes.
     */
    @JsName("two_item_tags_and_none_are_each_refused_naming_item")
    @Test
    fun `two item tags and none are each refused, naming item`() {
        val whole = ProposalFixtures.proposalTags()
        val second = listOf(ChannelVocabulary.ITEM, ProposalFixtures.coordinate(1), "1")

        val twice = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(ProposalFixtures.with(whole, second))
        }
        assertEquals(ChannelRejection.DUPLICATE_TAG, twice.reason)
        assertEquals(ChannelVocabulary.ITEM, twice.tag)

        val none = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(ProposalFixtures.without(whole, ChannelVocabulary.ITEM))
        }
        assertEquals(ChannelRejection.MISSING_REQUIRED_TAG, none.reason)
        assertEquals(ChannelVocabulary.ITEM, none.tag)
    }

    /**
     * §7.5 and §5.3's `item` row: the quantity MUST be the string `"1"` and MUST be rejected
     * otherwise — as the quantity rule, and specifically not ignored as an unknown tag.
     */
    @JsName("an_item_quantity_other_than_one_is_refused_as_the_quantity_rule")
    @Test
    fun `an item quantity other than one is refused as the quantity rule`() {
        val refused = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(
                ProposalFixtures.replacing(
                    ProposalFixtures.proposalTags(),
                    listOf(ChannelVocabulary.ITEM, ProposalFixtures.coordinate(0), "2"),
                ),
            )
        }

        assertEquals(ChannelRejection.MALFORMED_TAG, refused.reason)
        val cause = assertIs<TagException>(refused.cause, "§5.3's reason must survive on the cause")
        assertEquals(
            TagRejection.MALFORMED_NUMBER,
            cause.reason,
            "§5.3 fixes the quantity as the string \"1\" in Nenya v1 and requires rejecting anything " +
                "else; an `item` tag is a §5.3 row and is never ignored as unknown",
        )
    }

    /** §4.3 and §4.4: a non-canonical `amount_msat` is refused rather than read as what it resembles. */
    @JsName("a_non_canonical_amount_msat_is_refused_rather_than_read_as_the_number_it_resembles")
    @Test
    fun `a non-canonical amount_msat is refused rather than read as the number it resembles`() {
        val canonical = (ProposalFixtures.PRICE_SAT * Msat.MSAT_PER_SAT).toString()
        for (spelling in listOf("0$canonical", "+$canonical", "$canonical.0", "", " $canonical")) {
            val refused = assertFailsWith<ChannelException> {
                ProposalFixtures.proposal(ProposalFixtures.proposalTags(amountMsat = spelling))
            }
            assertEquals(ChannelRejection.MALFORMED_AMOUNT, refused.reason, "`$spelling`")
            assertEquals(ChannelTags.AMOUNT_MSAT, refused.tag, "`$spelling`")
        }
        // The pair: the canonical spelling of the same number parses, so the refusals above are
        // about §4.4's form and not about a codec that refuses every amount.
        assertEquals(
            Msat.ofSat(ProposalFixtures.PRICE_SAT),
            ProposalFixtures.proposal(ProposalFixtures.proposalTags(amountMsat = canonical)).price,
        )
    }

    /**
     * §4.3's duplicate rationale over §7.5's own tag, which §5.3's table does not carry: two
     * `amount_msat` tags is two implementations disagreeing about the price of a signed order.
     */
    @JsName("a_second_amount_msat_tag_is_refused_rather_than_resolved")
    @Test
    fun `a second amount_msat tag is refused rather than resolved`() {
        val whole = ProposalFixtures.proposalTags()

        val refused = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(
                ProposalFixtures.with(whole, listOf(ChannelTags.AMOUNT_MSAT, "1")),
            )
        }

        assertEquals(ChannelRejection.DUPLICATE_TAG, refused.reason)
        assertEquals(ChannelTags.AMOUNT_MSAT, refused.tag)
    }

    /**
     * §7.5's cross-check, all three ways: disagreeing is refused, agreeing parses, and absent
     * parses. The third is the one an implementation that requires the compatibility tag fails,
     * and it would refuse every conformant peer that omits it.
     */
    @JsName("the_amount_cross_check_refuses_a_disagreement_and_accepts_agreement_or_absence")
    @Test
    fun `the amount cross-check refuses a disagreement and accepts agreement or absence`() {
        val satoshis = ProposalFixtures.PRICE_SAT
        val msat = (satoshis * Msat.MSAT_PER_SAT).toString()

        val refused = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(
                ProposalFixtures.proposalTags(amountMsat = msat, amount = (satoshis + 1).toString()),
            )
        }
        assertEquals(ChannelRejection.AMOUNT_DISAGREEMENT, refused.reason)
        assertEquals(ChannelTags.AMOUNT, refused.tag)

        val agreeing = ProposalFixtures.proposal(
            ProposalFixtures.proposalTags(amountMsat = msat, amount = satoshis.toString()),
        )
        assertEquals(Msat.ofSat(satoshis), agreeing.price)

        val absent = ProposalFixtures.proposal(ProposalFixtures.proposalTags(amountMsat = msat))
        assertEquals(Msat.ofSat(satoshis), absent.price)
    }

    /**
     * The control that proves `amount` went through T1's `Msat.ofSat` and not through a bare
     * `Long` multiply — and the one a codec that only types `amount_msat` fails.
     *
     * `amount` is a satoshi count from a stranger's event. `× 1000` wraps silently in 64 bits, and
     * because 1000 does not divide 2⁶⁴ evenly there exists, for a price divisible by eight, a
     * satoshi value whose wrapped product is **exactly** the legitimate `amount_msat`. It is
     * computed here rather than typed — the modular inverse is derived by Newton iteration and the
     * wrap is asserted before the codec ever sees it, so the fixture proves it really does defeat a
     * bare multiply — and `Msat.ofSat` refuses it as above supply because it bounds the operand
     * *before* multiplying.
     */
    @JsName("an_amount_that_wraps_onto_the_price_is_refused_as_above_supply")
    @Test
    fun `an amount that wraps onto the price is refused as above supply`() {
        val priceMsat = ProposalFixtures.PRICE_SAT * Msat.MSAT_PER_SAT
        val crafted = wrappingSatoshisFor(priceMsat)

        // The control's own control: a bare `Long` multiply of this value equals the price exactly,
        // so a codec comparing `amount * 1000 == amount_msat` would call it agreement and continue.
        assertEquals(priceMsat, crafted * Msat.MSAT_PER_SAT, "the crafted value must actually wrap")
        assertTrue(crafted > 0L, "a wrapped product that survives a sign check is the whole danger")
        assertNotEquals(priceMsat / Msat.MSAT_PER_SAT, crafted)
        assertTrue(
            crafted > Msat.SUPPLY_CAP_MSAT / Msat.MSAT_PER_SAT,
            "the crafted value must be above §4.4's cap, which is what `Msat.ofSat` refuses it for",
        )

        val refused = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(
                ProposalFixtures.proposalTags(
                    amountMsat = priceMsat.toString(),
                    amount = crafted.toString(),
                ),
            )
        }
        assertEquals(ChannelRejection.AMOUNT_ABOVE_SUPPLY, refused.reason)
        assertEquals(ChannelTags.AMOUNT, refused.tag)

        // And an honestly over-supply `amount` reports the same reason, so the constant is about
        // §4.4's bound rather than about this one crafted value.
        val overSupply = Msat.SUPPLY_CAP_MSAT / Msat.MSAT_PER_SAT + 1L
        val bounded = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(
                ProposalFixtures.proposalTags(
                    amountMsat = priceMsat.toString(),
                    amount = overSupply.toString(),
                ),
            )
        }
        assertEquals(ChannelRejection.AMOUNT_ABOVE_SUPPLY, bounded.reason)
    }

    /** §4.4's bound on the authoritative amount too: above the supply cap is not a price. */
    @JsName("an_amount_msat_above_the_supply_cap_is_refused_as_above_supply")
    @Test
    fun `an amount_msat above the supply cap is refused as above supply`() {
        val refused = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(
                ProposalFixtures.proposalTags(amountMsat = (Msat.SUPPLY_CAP_MSAT + 1L).toString()),
            )
        }

        assertEquals(ChannelRejection.AMOUNT_ABOVE_SUPPLY, refused.reason)
        // And the cap itself is accepted, so the refusal is about the bound and not about size.
        assertEquals(
            Msat.SUPPLY_CAP,
            ProposalFixtures.proposal(
                ProposalFixtures.proposalTags(amountMsat = Msat.SUPPLY_CAP_MSAT.toString()),
            ).price,
        )
    }

    /**
     * §8.1's read rule: a proposal with **no** `fee` tag is zero fee and MUST NOT be refused as
     * incomplete terms — and it is a different value from a stated `["fee", "0"]`, because §8.1
     * says the explicit zero is itself a signed statement and §8.4 requires the pair be reproduced
     * as what it was.
     */
    @JsName("a_proposal_with_no_fee_tag_opens_at_zero_fee_and_is_not_a_stated_zero")
    @Test
    fun `a proposal with no fee tag opens at zero fee, and is not a stated zero`() {
        val absent = ProposalFixtures.proposal(ProposalFixtures.proposalTags(fee = null))
        val stated = ProposalFixtures.proposal(
            ProposalFixtures.proposalTags(fee = ProposalFixtures.feeTag(0, 0)),
        )

        assertEquals(FeeTerm.Absent, absent.terms.split.term, "§8.1: a missing `fee` is zero fee")
        assertEquals(Msat.ZERO, absent.terms.split.fee)
        assertNull(absent.feeRecipient)
        assertEquals(FeeTerm.of(0), stated.terms.split.term)
        assertNotEquals(
            absent.terms.split.term,
            stated.terms.split.term,
            "§8.1's absent term and a stated zero agree on the number and disagree on the wire",
        )
        // And a stated non-zero term reaches §8.3's arithmetic with its recipient.
        val charged = ProposalFixtures.proposal(ProposalFixtures.proposalTags())
        assertEquals(FeeTerm.of(DEFAULT_BASIS_POINTS), charged.terms.split.term)
        assertEquals(ProposalFixtures.pubkey(FEE_RECIPIENT_INDEX), charged.feeRecipient)
        assertTrue(charged.terms.split.fee > Msat.ZERO)
    }

    /** §7.5: "a proposal MUST NOT carry more than one `fee` tag" — §4.3's duplicate rule reaching it. */
    @JsName("two_fee_tags_are_refused")
    @Test
    fun `two fee tags are refused`() {
        val refused = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(
                ProposalFixtures.with(ProposalFixtures.proposalTags(), ProposalFixtures.feeTag(9, 100)),
            )
        }

        assertEquals(ChannelRejection.DUPLICATE_TAG, refused.reason)
        assertEquals(ChannelVocabulary.FEE, refused.tag)
    }

    /**
     * §8.4's own divergence example, `["fee", "0250", …]`, closed **at the parse**.
     *
     * T9 already reads `fee` basis points through §4.4's strict-decimal reader, so `0250` never
     * reaches a term comparison at all — which is why the control is written here, against the
     * parse, rather than as a §7.6 divergence that would be testing nothing.
     */
    @JsName("a_non_canonical_fee_basis_point_value_is_refused_at_the_parse")
    @Test
    fun `a non-canonical fee basis-point value is refused at the parse`() {
        val refused = assertFailsWith<ChannelException> {
            ProposalFixtures.proposal(
                ProposalFixtures.proposalTags(
                    fee = listOf(ChannelVocabulary.FEE, "0250", ProposalFixtures.pubkey(2)),
                ),
            )
        }

        assertEquals(ChannelRejection.MALFORMED_TAG, refused.reason)
        val cause = assertIs<TagException>(refused.cause)
        assertEquals(TagRejection.MALFORMED_NUMBER, cause.reason)
    }

    /**
     * §7.5's two deadlines: `expiration` MUST be **strictly** earlier than `deliver_by`.
     *
     * The equal case is the one an implementation written from memory gets wrong by writing `<=`,
     * and it is refused here by this package's own named reason rather than by the
     * `OrderStateException` `OrderTerms`'s `init` would throw for the same reason one layer down.
     */
    @JsName("the_two_deadlines_must_not_invert_and_each_alone_is_accepted")
    @Test
    fun `the two deadlines must not invert, and each alone is accepted`() {
        val deliverBy = ProposalFixtures.CREATED_AT + ProposalFixtures.DELIVERY_WINDOW

        for (expiration in listOf(deliverBy, deliverBy + 1L)) {
            val refused = assertFailsWith<ChannelException> {
                ProposalFixtures.proposal(
                    ProposalFixtures.proposalTags(deliverBy = deliverBy, expiration = expiration),
                )
            }
            assertEquals(ChannelRejection.DEADLINES_INVERTED, refused.reason, "expiration=$expiration")
            assertEquals(ChannelVocabulary.EXPIRATION, refused.tag)
        }

        val strictlyEarlier = ProposalFixtures.proposal(
            ProposalFixtures.proposalTags(deliverBy = deliverBy, expiration = deliverBy - 1L),
        )
        assertEquals(deliverBy - 1L, strictlyEarlier.terms.expiration)

        // §7.5 makes neither REQUIRED, so each alone opens an order and so does neither.
        val expirationOnly = ProposalFixtures.proposal(
            ProposalFixtures.proposalTags(deliverBy = null, expiration = deliverBy + 1L),
        )
        assertEquals(deliverBy + 1L, expirationOnly.terms.expiration)
        assertNull(expirationOnly.terms.deliverBy)

        val deliverByOnly = ProposalFixtures.proposal(
            ProposalFixtures.proposalTags(deliverBy = deliverBy, expiration = null),
        )
        assertEquals(deliverBy, deliverByOnly.terms.deliverBy)
        assertNull(deliverByOnly.terms.expiration)

        val neither = ProposalFixtures.proposal(
            ProposalFixtures.proposalTags(deliverBy = null, expiration = null),
        )
        assertNull(neither.terms.expiration)
        assertNull(neither.terms.deliverBy)
    }

    /** A well-formed message of another `type` is not a malformed one, and says so. */
    @JsName("a_rumor_that_is_not_a_type_1_is_refused_as_not_a_proposal")
    @Test
    fun `a rumor that is not a type 1 is refused as not a proposal`() {
        val refused = assertFailsWith<ChannelException> {
            OrderProposal.decode(
                ProposalFixtures.bound(
                    ProposalFixtures.proposalTags(type = ProposalFixtures.STATUS_TYPE),
                ),
            )
        }

        assertEquals(ChannelRejection.NOT_A_PROPOSAL, refused.reason)
        assertEquals(ChannelTags.TYPE, refused.tag)
    }

    // ---------------------------------------------------------------------------------------
    // §7.6 — acceptance, and the distinction that carries the money.
    // ---------------------------------------------------------------------------------------

    /** §7.6's positive case: the same order, `status=accepted`, and four byte-identical terms. */
    @JsName("a_byte_identical_status_update_is_an_acceptance")
    @Test
    fun `a byte-identical status update is an acceptance`() {
        val (proposal, update) = pair()

        val answer = proposal.accepts(update)

        val accepted = assertIs<Acceptance.Accepted>(answer)
        assertEquals(proposal.order, accepted.order)
        assertEquals(proposal.terms, accepted.terms)
        assertEquals(OrderState.ACCEPTED, update.status)
    }

    /**
     * §7.6: "An acceptance carrying different terms is a **counter-proposal**" — reported as one by
     * name, and never as malformed, because the correct response §7.6 names is a new `type=1` with a
     * new order id and a caller told "malformed" does the wrong thing.
     */
    @JsName("an_acceptance_with_an_altered_term_is_a_counter_proposal_naming_that_term")
    @Test
    fun `an acceptance with an altered term is a counter-proposal, naming that term`() {
        // A list rather than a map, because two of these alter the same tag in different ways and a
        // map keyed by tag name would silently keep only the second.
        val altered = listOf(
            ChannelTags.AMOUNT_MSAT to listOf(
                ChannelTags.AMOUNT_MSAT,
                (ProposalFixtures.PRICE_SAT * Msat.MSAT_PER_SAT + 1L).toString(),
            ),
            ChannelVocabulary.FEE to ProposalFixtures.feeTag(0, DEFAULT_BASIS_POINTS + 1),
            // §8.4's whole point, and the one divergence `OrderTerms.namesTheSameDealAs` cannot see:
            // the same basis points paid to a **different key**. A codec comparing only the number
            // calls this an acceptance and the buyer signs a fee to a recipient it never agreed to.
            ChannelVocabulary.FEE to listOf(
                ChannelVocabulary.FEE,
                DEFAULT_BASIS_POINTS.toString(),
                ProposalFixtures.pubkey(FEE_RECIPIENT_INDEX + 1),
            ),
            ChannelVocabulary.DELIVER_BY to listOf(
                ChannelVocabulary.DELIVER_BY,
                (ProposalFixtures.CREATED_AT + ProposalFixtures.DELIVERY_WINDOW + 1L).toString(),
            ),
            ChannelVocabulary.ITEM to listOf(
                ChannelVocabulary.ITEM,
                ProposalFixtures.coordinate(1),
                "1",
            ),
        )

        for ((name, tag) in altered) {
            val proposalTags = ProposalFixtures.proposalTags()
            val proposal = ProposalFixtures.proposal(proposalTags)
            val update = ProposalFixtures.statusMessage(
                ProposalFixtures.replacing(ProposalFixtures.acceptanceTags(proposalTags), tag),
            )

            val counter = assertIs<Acceptance.CounterProposal>(proposal.accepts(update), name)
            assertEquals(listOf(name), counter.divergentTerms, name)
        }
    }

    /**
     * The half a narrowing would have papered over: `deliver_by` is **not** strictly parsed and must
     * not become so.
     *
     * `TagValues.readTimestamp` accepts a leading zero deliberately, on the stated grounds that
     * §4.3 requires only "a non-negative decimal integer" and that refusing `01757066034` would
     * refuse a peer the document permits. So §7.6's comparison is made over the **raw tag
     * elements** — which is what "byte-identical" says — and the two spellings of one instant are a
     * counter-proposal, while the identical spelling is an acceptance.
     */
    @JsName("a_leading_zero_deliver_by_is_a_counter_proposal_and_the_identical_spelling_is_an_acceptance")
    @Test
    fun `a leading-zero deliver_by is a counter-proposal, and the identical spelling is an acceptance`() {
        val deliverBy = ProposalFixtures.CREATED_AT + ProposalFixtures.DELIVERY_WINDOW
        val proposalTags = ProposalFixtures.proposalTags(deliverBy = deliverBy)
        val proposal = ProposalFixtures.proposal(proposalTags)
        val padded = listOf(ChannelVocabulary.DELIVER_BY, "0$deliverBy")

        val recased = ProposalFixtures.statusMessage(
            ProposalFixtures.replacing(ProposalFixtures.acceptanceTags(proposalTags), padded),
        )
        val counter = assertIs<Acceptance.CounterProposal>(proposal.accepts(recased))
        assertEquals(listOf(ChannelVocabulary.DELIVER_BY), counter.divergentTerms)

        val identical = ProposalFixtures.statusMessage(ProposalFixtures.acceptanceTags(proposalTags))
        assertIs<Acceptance.Accepted>(proposal.accepts(identical))

        // The padded spelling is a legal timestamp, not a malformed one: it decodes, and it decodes
        // to the same instant. The divergence above is §7.6's byte rule and not a parse failure.
        assertEquals(
            deliverBy,
            ProposalFixtures.proposal(
                ProposalFixtures.replacing(ProposalFixtures.proposalTags(), padded),
            ).terms.deliverBy,
        )
    }

    /**
     * The stated narrowing, made visible in the suite rather than only in a KDoc: [OrderProposal]
     * does not know who the provider is, so [OrderProposal.accepts] answers about the **terms** and
     * the caller answers about the key.
     *
     * §7.6 says an acceptance is a `type=3` "from the provider". §7.2 gives this library the key the
     * message was *sealed* by, and nothing here can establish that that key is the provider's: the
     * proposal's `p` tag is a counterparty pubkey the **buyer** wrote and §5.3 gives `p` cardinality
     * `0–n`. So an acceptance sealed by a key that is not the buyer's still compares byte-identical
     * and is still [Acceptance.Accepted] — and [OrderStatusMessage.sender] is what a caller compares
     * against the key it knows to be the provider's.
     *
     * Written down because every other §7.6 fixture here authors both messages with the same key,
     * which would leave a future change that started trusting the sender invisible.
     */
    @JsName("an_acceptance_is_about_terms_and_the_sender_is_the_callers_to_resolve")
    @Test
    fun `an acceptance is about terms, and the sender is the caller's to resolve`() {
        val proposalTags = ProposalFixtures.proposalTags()
        val proposal = ProposalFixtures.proposal(proposalTags)

        // Authored by the key the proposal's own `p` tag names — the provider, as far as the buyer
        // is concerned — which is a different key from the one that sealed the proposal.
        val update = ProposalFixtures.statusMessage(
            ProposalFixtures.acceptanceTags(proposalTags),
            index = PROVIDER_INDEX,
        )

        assertNotEquals(
            proposal.buyer,
            update.sender,
            "the fixture must seal the two messages with different keys, or this proves nothing",
        )
        assertIs<Acceptance.Accepted>(
            proposal.accepts(update),
            "§7.6's comparison is over the four terms and the order id; the sender is §7.2's " +
                "attribution and is the caller's to resolve",
        )
        assertEquals(
            ProposalFixtures.pubkey(PROVIDER_INDEX),
            update.sender,
            "§7.2: the sender is the key the seal was signed by, in §4.3's canonical lowercase",
        )
    }

    /**
     * §7.6 fixes the terms an acceptance must repeat as `item`, `amount_msat`, `fee` and
     * `deliver_by`. `expiration` is not among them — §7.5 makes it the deadline for *acceptance*,
     * which a conformant provider has no reason to echo — so an acceptance carrying a different one
     * is still an acceptance. Comparing it would deadlock every order at `proposed`.
     */
    @JsName("a_differing_expiration_does_not_make_an_acceptance_a_counter_proposal")
    @Test
    fun `a differing expiration does not make an acceptance a counter-proposal`() {
        val proposalTags = ProposalFixtures.proposalTags()
        val proposal = ProposalFixtures.proposal(proposalTags)
        val echoed = ProposalFixtures.with(
            ProposalFixtures.acceptanceTags(proposalTags),
            listOf(ChannelVocabulary.EXPIRATION, (ProposalFixtures.CREATED_AT + 1L).toString()),
        )

        val update = ProposalFixtures.statusMessage(echoed)

        assertIs<Acceptance.Accepted>(proposal.accepts(update))
    }

    /**
     * An acceptance carrying **no** terms agrees to nothing, so §7.6's byte-identity fails on all
     * four and it is a counter-proposal rather than an acceptance assumed to agree.
     */
    @JsName("a_status_update_carrying_no_terms_is_a_counter_proposal_on_all_four")
    @Test
    fun `a status update carrying no terms is a counter-proposal on all four`() {
        val proposalTags = ProposalFixtures.proposalTags()
        val proposal = ProposalFixtures.proposal(proposalTags)
        val update = ProposalFixtures.statusMessage(
            ProposalFixtures.acceptanceTags(proposalTags, terms = emptyList()),
        )

        val counter = assertIs<Acceptance.CounterProposal>(proposal.accepts(update))

        assertEquals(ProposalFixtures.TERM_NAMES, counter.divergentTerms)
    }

    /** A status update about another order is not a counter-proposal: it is not about this order. */
    @JsName("an_acceptance_naming_another_order_is_refused_as_a_different_order")
    @Test
    fun `an acceptance naming another order is refused as a different order`() {
        val proposalTags = ProposalFixtures.proposalTags()
        val proposal = ProposalFixtures.proposal(proposalTags)
        val update = ProposalFixtures.statusMessage(
            ProposalFixtures.acceptanceTags(proposalTags, order = ProposalFixtures.orderHex(1)),
        )

        val refused = assertFailsWith<ChannelException> { proposal.accepts(update) }

        assertEquals(ChannelRejection.DIFFERENT_ORDER, refused.reason)
        assertEquals(ChannelTags.ORDER, refused.tag)
    }

    /**
     * §5.2's "absent means active" is a **listing** rule. Applying it to a `type=3` would invent an
     * order transition out of a missing tag, so a status update with no `status` is refused.
     */
    @JsName("a_status_update_with_no_status_tag_is_refused_rather_than_defaulted")
    @Test
    fun `a status update with no status tag is refused rather than defaulted`() {
        val proposalTags = ProposalFixtures.proposalTags()

        val refused = assertFailsWith<ChannelException> {
            ProposalFixtures.statusMessage(
                ProposalFixtures.acceptanceTags(proposalTags, status = null),
            )
        }

        assertEquals(ChannelRejection.MISSING_REQUIRED_TAG, refused.reason)
        assertEquals(ChannelVocabulary.STATUS, refused.tag)
    }

    /**
     * §11.1's conflation, from the direction only this package can reach: the `status` tag on a
     * `type=3` carries the **order** vocabulary, and the identically-named §5.3 tag on a listing
     * carries §5.2's.
     *
     * `sold`, `awarded` and `fulfilled` are §5.2's listing tokens, and every one of them lands in
     * [OrderState.UNKNOWN] here — never in a listing state, which is not even assignable to this
     * field. `cancelled` is the single shared token and reads as the **order** `cancelled`: it is
     * the one place the conflation looks correct and passes a careless test.
     */
    @JsName("a_listing_status_token_on_a_type_3_lands_in_the_unknown_sink")
    @Test
    fun `a listing status token on a type 3 lands in the unknown sink`() {
        val proposalTags = ProposalFixtures.proposalTags()
        val proposal = ProposalFixtures.proposal(proposalTags)

        for (token in listOf("sold", "awarded", "fulfilled")) {
            assertTrue(
                OrderState.entries.none { it.token == token },
                "`$token` is §5.2's listing vocabulary and MUST NOT be an order state",
            )
            val update = ProposalFixtures.statusMessage(
                ProposalFixtures.acceptanceTags(proposalTags, status = token),
            )
            assertEquals(OrderState.UNKNOWN, update.status, token)
            val answer = assertIs<Acceptance.NotAnAcceptance>(proposal.accepts(update), token)
            assertEquals(OrderState.UNKNOWN, answer.status)
        }

        val cancelled = ProposalFixtures.statusMessage(
            ProposalFixtures.acceptanceTags(proposalTags, status = "cancelled"),
        )
        assertEquals(
            OrderState.CANCELLED,
            cancelled.status,
            "the single shared token reads as the ORDER `cancelled` — an order that ended before " +
                "`paid`, not a listing withdrawn from the board",
        )
        assertEquals(
            OrderState.CANCELLED,
            assertIs<Acceptance.NotAnAcceptance>(proposal.accepts(cancelled)).status,
        )
    }

    /** §7.6's decoder takes a `type=3` and nothing else, so the other shapes are unrepresentable. */
    @JsName("a_rumor_that_is_not_a_type_3_is_refused_as_not_a_status_update")
    @Test
    fun `a rumor that is not a type 3 is refused as not a status update`() {
        val refused = assertFailsWith<ChannelException> {
            OrderStatusMessage.decode(ProposalFixtures.bound(ProposalFixtures.proposalTags()))
        }

        assertEquals(ChannelRejection.NOT_A_STATUS_UPDATE, refused.reason)
        assertEquals(ChannelTags.TYPE, refused.tag)
    }

    // ---------------------------------------------------------------------------------------
    // §12 item 11 and STOP RULE 14.
    // ---------------------------------------------------------------------------------------

    /**
     * §12 item 11 names the order id in one sentence with key material and preimages, and §12 item 2
     * adds the counterparty pubkey; STOP RULE 14 adds the listing id, which is what a coordinate is.
     * This is that control for every type in this round that transitively holds one.
     */
    @JsName("no_string_representation_of_a_proposal_carries_an_order_id_or_a_coordinate")
    @Test
    fun `no string representation of a proposal carries an order id or a coordinate`() {
        val proposalTags = ProposalFixtures.proposalTags()
        val proposal = ProposalFixtures.proposal(proposalTags)
        val update = ProposalFixtures.statusMessage(ProposalFixtures.acceptanceTags(proposalTags))
        val accepted = assertIs<Acceptance.Accepted>(proposal.accepts(update))
        val counter = assertIs<Acceptance.CounterProposal>(
            proposal.accepts(
                ProposalFixtures.statusMessage(
                    ProposalFixtures.replacing(
                        ProposalFixtures.acceptanceTags(proposalTags),
                        listOf(ChannelTags.AMOUNT_MSAT, "1"),
                    ),
                ),
            ),
        )

        val secrets = listOf(
            ProposalFixtures.orderHex(0),
            ProposalFixtures.orderHex(0).uppercase(),
            ProposalFixtures.coordinate(0),
            ProposalFixtures.pubkey(0),
            ProposalFixtures.pubkey(1),
            (ProposalFixtures.PRICE_SAT * Msat.MSAT_PER_SAT).toString(),
        )
        val rendered = listOf(
            proposal.toString(),
            update.toString(),
            accepted.toString(),
            counter.toString(),
            proposal.terms.toString(),
            proposal.item.coordinate.toString(),
        )

        for (text in rendered) {
            for (secret in secrets) {
                assertFalse(secret in text, "`$secret` reached a string representation: $text")
            }
        }
        // The control's own control: the values really are in these objects, so the assertions
        // above are about redaction rather than about a fixture that never carried one.
        assertEquals(ProposalFixtures.orderHex(0), proposal.order.toHex())
        assertEquals(ProposalFixtures.coordinate(0), proposal.item.coordinate.toTagValue())
    }

    private fun pair(): Pair<OrderProposal, OrderStatusMessage> {
        val proposalTags = ProposalFixtures.proposalTags()
        return ProposalFixtures.proposal(proposalTags) to
            ProposalFixtures.statusMessage(ProposalFixtures.acceptanceTags(proposalTags))
    }

    /**
     * A satoshi count whose **wrapped** 64-bit product with 1000 is exactly [priceMsat].
     *
     * `1000 = 8 × 125`, so `1000·s ≡ P (mod 2⁶⁴)` is solvable whenever `8 | P`, and its solutions
     * are `s ≡ (P / 8) · 125⁻¹ (mod 2⁶¹)` — a whole residue class, not one number. The **smallest**
     * member of that class is the honest satoshi count `P / 1000`, which is exactly what makes the
     * class dangerous: every other member multiplies to the same 64-bit result and none of them is
     * the price. So one full period is added, and what comes back is a value no honest peer would
     * send whose bare `Long` product is byte-for-byte the legitimate `amount_msat`.
     *
     * The inverse of an odd number modulo a power of two is computed by Newton iteration —
     * `x ← x(2 − ax)` doubles the number of correct bits each round, starting from `x = a`, which is
     * already correct modulo 8 for any odd `a`. Computed rather than typed, per the queue's rule
     * about fabricated fixtures, and the test asserts the wrap before using it.
     */
    private fun wrappingSatoshisFor(priceMsat: Long): Long {
        var twos = 0
        var odd = Msat.MSAT_PER_SAT
        while (odd % 2L == 0L) {
            odd /= 2L
            twos++
        }
        val powerOfTwo = 1L shl twos
        check(priceMsat % powerOfTwo == 0L) { "the price must be divisible by $powerOfTwo to wrap" }
        var inverse = odd
        repeat(NEWTON_ROUNDS) { inverse *= 2L - odd * inverse }
        check(odd * inverse == 1L) { "the Newton iteration did not invert $odd modulo 2^64" }
        // The period of the residue class, 2^61. Reducing to below it gives the honest `P / 1000`;
        // adding one period gives the value that wraps onto the same product and is not the price.
        // The sum stays below 2^62, so it is a positive Long.
        val period = 1L shl (Long.SIZE_BITS - twos)
        return (((priceMsat / powerOfTwo) * inverse) and (period - 1L)) + period
    }

    private companion object {

        /** §7.5's example fee, 2.5%, which `ProposalFixtures` also defaults to. */
        const val DEFAULT_BASIS_POINTS: Int = 250

        /** `ProposalFixtures` places a fee recipient two keys along from the buyer. */
        const val FEE_RECIPIENT_INDEX: Int = 2

        /** And the provider — the `p` tag of a proposal at index 0 — one key along. */
        const val PROVIDER_INDEX: Int = 1

        /** Newton doubles from 3 correct bits: 6, 12, 24, 48, 96 — six rounds covers 64. */
        const val NEWTON_ROUNDS: Int = 6
    }
}
