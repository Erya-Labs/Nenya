package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelException
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.settlement.SettlementFixtures
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
 * T75: §7.2's attributed sender mapped to its §11.2 party by `OrderProposal.partyOf`, and crossed as
 * [partyOf].
 *
 * Every `type=1` this file reaches is asked about four keys — its buyer, the provider the test
 * resolved, its fee recipient where it names one, and a stranger — and the crossed answer is compared
 * field for field with the Kotlin call. The expected party is the role the **fixture** gave the key,
 * never a call to the code under test.
 */
class JsPartyOfEqualityTest {

    private companion object {

        /** Enough `RumorWriterFixtures` cases to reach twenty-five `type=1`s. */
        val WRITER_CASES: Int = RumorWriterFixtures.Shape.entries.size * 25
    }

    /** One `type=1` and the three keys its fixture assigned the three roles. */
    private class Roles(
        val what: String,
        val rumor: AttributedRumor.Bound,
        val buyer: String,
        val provider: String,
        val feeRecipient: String?,
        val stranger: String,
    )

    private fun proposals(): List<Roles> = buildList {
        for ((what, terms) in listOf(
            "a priced order with a stated fee" to OrderFixtures.TERMS,
            "§8.1's signed zero" to OrderFixtures.zeroFeeTerms,
            "no fee term at all" to OrderFixtures.absentFeeTerms,
            "a free order" to OrderFixtures.freeTerms(),
        )) {
            val chain = OrderFixtures.chain(terms, OrderFixtures.ORDER_INDEX)
            val rumor = ProposalFixtures.bound(chain.proposalTags, chain.index)
            add(
                Roles(
                    "OrderFixtures chain: $what",
                    rumor,
                    SettlementFixtures.buyer(chain.index),
                    SettlementFixtures.provider(chain.index),
                    OrderProposal.decode(rumor).feeRecipient?.let { SettlementFixtures.feeRecipient(chain.index) },
                    SettlementFixtures.stranger(chain.index),
                ),
            )
        }
        for (index in 0..OrderFixtures.OTHER_ORDER_INDEX) {
            for (stream in 0 until OrderFixtures.STREAMS) {
                val messages = OrderFixtures.messages(index, stream)
                add(
                    Roles(
                        "OrderFixtures messages $index/$stream",
                        messages.proposalRumor,
                        SettlementFixtures.buyer(index),
                        // The key that really sealed this order's acceptance, so the provider is the
                        // one §7.6 checks and not one this test chose to agree with itself.
                        messages.acceptanceRumor.author,
                        SettlementFixtures.feeRecipient(index),
                        SettlementFixtures.stranger(index),
                    ),
                )
            }
        }
        for (case in RumorWriterFixtures.cases(WRITER_CASES).filter { it.shape == RumorWriterFixtures.Shape.PROPOSAL }) {
            add(
                Roles(
                    "RumorWriterFixtures $case",
                    RumorWriterFixtures.bound(RumorWriterFixtures.build(case)),
                    case.envelope.authorPubkey,
                    // The buyer's first `p`: decision H makes the provider the caller's resolution,
                    // and the generator draws it distinct from the author and the fee recipient.
                    case.envelope.counterparties.first().pubkey,
                    case.feeRecipient,
                    SettlementFixtures.stranger(case.index),
                ),
            )
        }
    }

    /** The crossed answer, field for field against the Kotlin call and against the expected party. */
    private fun assertCrossedEquals(roles: Roles, sender: String, expected: Party?, what: String) {
        val kotlin = OrderProposal.decode(roles.rumor).partyOf(sender, roles.provider)
        assertEquals(expected, kotlin, "${roles.what}: $what, Kotlin")
        val crossed = partyOf(JsRumor(roles.rumor), roles.provider, sender)
        assertTrue(crossed.ok, "${roles.what}: $what answers: ${crossed.reason} ${crossed.detail}")
        assertEquals(if (kotlin == null) "not_a_party" else "party", crossed.kind, "${roles.what}: $what")
        assertEquals(kotlin?.name, crossed.party, "${roles.what}: $what, crossed")
        assertNull(crossed.reason, "${roles.what}: $what")
        assertNull(crossed.reasonVocabulary, "${roles.what}: $what")
        assertNull(crossed.tag, "${roles.what}: $what")
        assertNull(crossed.detail, "${roles.what}: $what")
    }

    @Test
    @JsName("buyer_provider_and_fee_recipient_each_map_to_their_party_crossed_equal_to_Kotlin")
    fun `buyer, provider and fee recipient each map to their party, crossed equal to Kotlin`() {
        var writerCases = 0
        var feeBearing = 0
        for (roles in proposals()) {
            val decoded = OrderProposal.decode(roles.rumor)
            // The fixture's roles are the proposal's own, so the expected side is not a guess.
            assertEquals(roles.buyer, decoded.buyer, "${roles.what}: §7.4's sender is the buyer")
            assertEquals(roles.feeRecipient, decoded.feeRecipient, "${roles.what}: §8.1's recipient")
            val keys = listOfNotNull(roles.buyer, roles.provider, roles.feeRecipient)
            assertEquals(keys.size, keys.toSet().size, "${roles.what}: the fixture's roles are three keys")
            assertFalse(roles.stranger in keys, "${roles.what}: the stranger holds no role")

            assertCrossedEquals(roles, roles.buyer, Party.BUYER, "the buyer")
            assertCrossedEquals(roles, roles.provider, Party.PROVIDER, "the provider")
            assertCrossedEquals(roles, roles.provider.uppercase(), Party.PROVIDER, "the provider in uppercase (§4.3)")
            roles.feeRecipient?.let {
                assertCrossedEquals(roles, it, Party.FEE_RECIPIENT, "the fee recipient")
                feeBearing++
            }
            assertCrossedEquals(roles, roles.stranger, null, "a stranger")
            assertCrossedEquals(roles, roles.buyer.substring(1), null, "a key one character short")
            assertCrossedEquals(roles, "", null, "an empty key")
            if (roles.what.startsWith("RumorWriterFixtures")) writerCases++
        }
        assertEquals(25, writerCases, "every generated `type=1` is reached")
        assertTrue(feeBearing > 0, "a proposal naming a fee recipient is reached")
    }

    /**
     * A key holding two roles is no party, never a pick: the control the task's mutation names. A
     * `partyOf` that tests `PROVIDER` before counting roles answers `PROVIDER` for the provider here.
     *
     * Only the provider can hold two roles (§8.1's MAY). The buyer-as-fee-recipient half this test
     * once carried is now refused before any party is mapped — a declared fixture change (T93).
     */
    @Test
    @JsName("a_key_holding_two_roles_is_no_party_on_either_side_of_the_boundary")
    fun `a key holding two roles is no party on either side of the boundary`() {
        val index = OrderFixtures.ORDER_INDEX
        val buyer = SettlementFixtures.buyer(index)
        val provider = SettlementFixtures.provider(index)
        val tags = ProposalFixtures.proposalTags(
            index = index,
            fee = listOf(ChannelVocabulary.FEE, OrderFixtures.FEE_BASIS_POINTS.toString(), provider),
        )
        val rumor = ProposalFixtures.bound(tags, index)
        val decoded = OrderProposal.decode(rumor)
        assertEquals(buyer, decoded.buyer, "§7.4's sender is the buyer")
        assertEquals(provider, decoded.feeRecipient, "the proposal names the provider's key as fee recipient")
        val roles = Roles("fee recipient = provider", rumor, buyer, provider, provider, SettlementFixtures.stranger(index))
        assertCrossedEquals(roles, provider, null, "the key holding two roles")
        assertCrossedEquals(roles, buyer, Party.BUYER, "the other party, unaffected")
    }

    /**
     * §8.1: "The recipient MUST NOT be the buyer's pubkey." A proposal naming its buyer as fee recipient
     * no longer decodes, so `partyOf` refuses it as a value naming `FEE_RECIPIENT_IS_BUYER` (T93).
     */
    @Test
    @JsName("a_proposal_naming_its_buyer_as_fee_recipient_is_refused_before_any_party_is_mapped")
    fun `a proposal naming its buyer as fee recipient is refused before any party is mapped`() {
        val index = OrderFixtures.ORDER_INDEX
        val buyer = SettlementFixtures.buyer(index)
        val provider = SettlementFixtures.provider(index)
        val tags = ProposalFixtures.proposalTags(
            index = index,
            fee = listOf(ChannelVocabulary.FEE, OrderFixtures.FEE_BASIS_POINTS.toString(), buyer),
        )
        val rumor = ProposalFixtures.bound(tags, index)
        val kotlin = try {
            OrderProposal.decode(rumor)
            fail("§8.1 forbids the buyer as fee recipient")
        } catch (refused: ChannelException) {
            refused
        }
        assertEquals(ChannelRejection.FEE_RECIPIENT_IS_BUYER, kotlin.reason)
        assertEquals(ChannelVocabulary.FEE, kotlin.tag)
        for (sender in listOf(buyer, provider)) {
            val crossed = partyOf(JsRumor(rumor), provider, sender)
            assertFalse(crossed.ok)
            assertEquals("refused", crossed.kind)
            assertNull(crossed.party, "a refusal names no party")
            assertEquals(ChannelRejection.FEE_RECIPIENT_IS_BUYER.name, crossed.reason)
            assertEquals("ChannelRejection", crossed.reasonVocabulary)
            assertEquals(ChannelVocabulary.FEE, crossed.tag)
        }
    }

    /**
     * The provider key is refused exactly as `accepts` refuses it, first, whatever the sender is — and
     * across the boundary as a value naming the same constant `checkAcceptance` names.
     */
    @Test
    @JsName("a_provider_key_accepts_refuses_is_refused_the_same_way_as_a_value")
    fun `a provider key accepts refuses is refused the same way, as a value`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val buyer = SettlementFixtures.buyer(OrderFixtures.ORDER_INDEX)
        for ((provider, reason) in listOf(
            buyer to ChannelRejection.PROVIDER_IS_BUYER,
            buyer.uppercase() to ChannelRejection.PROVIDER_IS_BUYER,
            buyer.substring(1) to ChannelRejection.MALFORMED_PROVIDER_PUBKEY,
            "" to ChannelRejection.MALFORMED_PROVIDER_PUBKEY,
        )) {
            val what = "provider ${reason.name}, ${provider.length} characters"
            val kotlin = try {
                messages.proposal.partyOf(buyer, provider)
                fail("$what: Kotlin must refuse")
            } catch (refused: ChannelException) {
                refused
            }
            assertEquals(reason, kotlin.reason, what)
            val viaAccepts = try {
                messages.proposal.accepts(messages.acceptance, provider)
                fail("$what: accepts must refuse too")
            } catch (refused: ChannelException) {
                refused
            }
            assertEquals(viaAccepts.reason, kotlin.reason, "$what: the same refusal `accepts` makes")
            assertEquals(viaAccepts.tag, kotlin.tag, "$what: about the same argument")

            val crossed = partyOf(JsRumor(messages.proposalRumor), provider, buyer)
            assertFalse(crossed.ok, what)
            assertEquals("refused", crossed.kind, what)
            assertNull(crossed.party, "$what: a refusal names no party")
            assertEquals(reason.name, crossed.reason, what)
            assertEquals("ChannelRejection", crossed.reasonVocabulary, what)
            assertEquals(kotlin.tag, crossed.tag, what)
            assertNotNull(crossed.detail, what)
            assertEquals(
                checkAcceptance(JsRumor(messages.proposalRumor), JsRumor(messages.acceptanceRumor), provider).reason,
                crossed.reason,
                "$what: the refusal `checkAcceptance` names",
            )
        }
    }

    /** A `type=3` is not a proposal: the decoder's own `NOT_A_PROPOSAL`, as a value, and no party. */
    @Test
    @JsName("partyOf_over_a_status_update_is_refused_NOT_A_PROPOSAL_with_no_party")
    fun `partyOf over a status update is refused NOT_A_PROPOSAL with no party`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val provider = messages.acceptanceRumor.author
        val answer = partyOf(JsRumor(messages.acceptanceRumor), provider, provider)
        assertFalse(answer.ok)
        assertEquals("refused", answer.kind)
        assertNull(answer.party)
        assertEquals(ChannelRejection.NOT_A_PROPOSAL.name, answer.reason)
        assertEquals("ChannelRejection", answer.reasonVocabulary)
        assertNotEquals("", answer.detail ?: "", "a refusal carries a sentence")
        assertFalse(provider in answer.toString(), "the answer's text names no key")
    }
}
