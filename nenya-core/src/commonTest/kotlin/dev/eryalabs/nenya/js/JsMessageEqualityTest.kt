package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelFixtures
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelTags
import dev.eryalabs.nenya.channel.DeliverableReleaseMessage
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
import dev.eryalabs.nenya.channel.DeliveryMessageFixtures
import dev.eryalabs.nenya.channel.DeliveryTags
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T44 gap (2) across the JavaScript boundary: [decodeProposal], [decodeCommitment] and
 * [decodeRelease] answer exactly what `OrderProposal.decode`,
 * `DeliveryCommitmentMessage.decode` and `DeliverableReleaseMessage.decode` answer.
 *
 * ### Why these three got their own entry points, restated as what this file proves
 *
 * The stage-1 page read the terms, the delivery promise and the key message out of
 * `JsRumor.unknownTags` by hand, which means it re-decided §7.5's `amount`/`amount_msat`
 * agreement, §8.1's absent-versus-stated-zero distinction and §10.1's key-absence rule for itself.
 * Each test below runs one rumor through the Kotlin decoder and through the crossing and holds the
 * two equal field for field — so what the page gets is this library's reading and not a second one.
 *
 * ### One rumor feeds both sides
 *
 * Every case takes a single `AttributedRumor.Bound` from the fixtures, hands it to `decode` on the
 * expected side and to [JsRumor] on the crossed side. Two messages built from two copies of one set
 * of tags could agree while the crossing lost a tag; one rumor cannot.
 */
class JsMessageEqualityTest {

    private companion object {

        /**
         * How many generated messages each corpus draws.
         *
         * The generators behind them vary §8.1's three fee shapes, both deadline shapes, §4.1's
         * escaping cases inside values, both key encodings and the presence of `subject` and of
         * unknown tags — so the number is a sample depth over shapes that are already distinct,
         * not a count of identical rows.
         */
        const val CORPUS: Int = 64
    }

    /** §7.5's proposals, decoded both ways — every field, over the generated corpus. */
    @Test
    @JsName("decodeProposal_crosses_section_7_5_s_proposal")
    fun `decodeProposal crosses §7_5's proposal`() {
        var feeAbsent = 0
        var feeStated = 0
        for (fixture in ProposalFixtures.pairs(CORPUS)) {
            val rumor = ProposalFixtures.bound(fixture.proposalTags, fixture.index)
            val crossed = proposalBothWays("proposal ${fixture.index}", rumor)
            val decoded = crossed.proposal ?: fail("the generated corpus must decode")
            if (decoded.feeBasisPoints == null) feeAbsent++ else feeStated++
        }
        // §8.1's distinction is the one T42's proof found an inherited facade losing, so the corpus
        // is asserted to reach both sides of it rather than assumed to.
        assertTrue(feeAbsent > 0, "the corpus must carry a proposal with no `fee` tag at all (§8.1)")
        assertTrue(feeStated > 0, "the corpus must carry a proposal with a stated `fee` term (§8.1)")
    }

    /** §10.1's commitments, decoded both ways — every field, over the generated corpus. */
    @Test
    @JsName("decodeCommitment_crosses_section_10_1_s_commitment")
    fun `decodeCommitment crosses §10_1's commitment`() {
        for (fixture in DeliveryMessageFixtures.pairs(CORPUS)) {
            val rumor = DeliveryMessageFixtures.bound(
                fixture.commitmentTags,
                NenyaKind.ORDER_MESSAGE,
                fixture.index,
            )
            // The equality holds trivially when both sides refuse, so the corpus is asserted to
            // decode rather than assumed to. A generator that started producing non-conformant
            // tags would otherwise leave this green over 64 fixtures while exercising only the
            // refusal path — which is the vacuity every corpus in this repository is checked for.
            commitmentBothWays("commitment ${fixture.index}", rumor).commitment
                ?: fail("the generated corpus must decode: commitment ${fixture.index}")
        }
    }

    /** §10.3's releases, decoded both ways — every field, over the generated corpus. */
    @Test
    @JsName("decodeRelease_crosses_section_10_3_s_release")
    fun `decodeRelease crosses §10_3's release`() {
        var hexKeys = 0
        var base64Keys = 0
        for (fixture in DeliveryMessageFixtures.pairs(CORPUS)) {
            val rumor = DeliveryMessageFixtures.bound(
                fixture.releaseTags,
                NenyaKind.FILE_MESSAGE,
                fixture.index,
            )
            // Asserted to decode, for the reason the commitment corpus above records — and here
            // the counters below cannot stand in for it, because they read the fixture's own
            // metadata rather than anything the decoder produced.
            releaseBothWays("release ${fixture.index}", rumor).release
                ?: fail("the generated corpus must decode: release ${fixture.index}")
            if (fixture.keyIsHex) hexKeys++ else base64Keys++
        }
        // §10.2 fixes two encodings and requires a value that is neither be rejected; a corpus that
        // only carried one of them would prove nothing about the decoder reaching the other.
        assertTrue(hexKeys > 0, "the corpus must carry a hex `decryption-key` (§10.2)")
        assertTrue(base64Keys > 0, "the corpus must carry a base64 `decryption-key` (§10.2)")
    }

    /**
     * The three decoders' own refusals, returned as values and equal to the exceptions a JVM caller
     * gets — including which tag each rule is about.
     *
     * `COMMITMENT_CARRIES_KEY` is the one that matters most to a page: §10.1 says a commitment
     * carrying the key "has already released the deliverable", and it is exactly the rule a page
     * reading tags itself would not know it was supposed to apply.
     */
    @Test
    @JsName("the_message_decoders_return_their_own_refusals_as_values")
    fun `the message decoders return their own refusals as values`() {
        val fixture = DeliveryMessageFixtures.pairs(1).single()
        val proposalFixture = ProposalFixtures.pairs(1).single()

        // §7.5's decoder handed a well-formed message of another type.
        val notAProposal = proposalBothWays(
            "a type=5 offered as a proposal",
            DeliveryMessageFixtures.bound(
                fixture.commitmentTags,
                NenyaKind.ORDER_MESSAGE,
                fixture.index,
            ),
        )
        assertFalse(notAProposal.ok, "a well-formed message of another type is not a proposal")
        assertEquals(ChannelRejection.NOT_A_PROPOSAL.name, notAProposal.reason, "§7.5's decoder")
        assertEquals("ChannelRejection", notAProposal.reasonVocabulary, "§7.5's own vocabulary")

        // §7.5's `amount_msat` removed: the refusal names the tag, which is what a page acts on.
        val noAmount = proposalBothWays(
            "a proposal with no amount_msat",
            ProposalFixtures.bound(
                ProposalFixtures.without(proposalFixture.proposalTags, ChannelTags.AMOUNT_MSAT),
                proposalFixture.index,
            ),
        )
        assertFalse(noAmount.ok, "§7.5 requires `amount_msat` on a type=1")
        assertEquals(ChannelRejection.MISSING_REQUIRED_TAG.name, noAmount.reason, "§7.5")
        assertEquals(ChannelTags.AMOUNT_MSAT, noAmount.tag, "the refusal names the tag it is about")

        // §10.1's absolute rule: a commitment carrying the key is the construction collapsed.
        val carriesKey = commitmentBothWays(
            "a commitment carrying decryption-key",
            DeliveryMessageFixtures.bound(
                DeliveryMessageFixtures.with(
                    fixture.commitmentTags,
                    listOf(DeliveryTags.DECRYPTION_KEY, DeliveryMessageFixtures.hex(
                        DeliveryMessageFixtures.keyBytes(fixture.index),
                    )),
                ),
                NenyaKind.ORDER_MESSAGE,
                fixture.index,
            ),
        )
        assertFalse(carriesKey.ok, "§10.1: a commitment MUST NOT contain `decryption-key`")
        assertEquals(ChannelRejection.COMMITMENT_CARRIES_KEY.name, carriesKey.reason, "§10.1")
        assertEquals(DeliveryTags.DECRYPTION_KEY, carriesKey.tag, "named by tag, not by `forbidden`")

        // §10.3's decoder handed a kind:16.
        val notARelease = releaseBothWays(
            "a kind:16 offered as a release",
            DeliveryMessageFixtures.bound(
                fixture.commitmentTags,
                NenyaKind.ORDER_MESSAGE,
                fixture.index,
            ),
        )
        assertFalse(notARelease.ok, "§10.3's decoder reads a kind:15 and no other")
        assertEquals(ChannelRejection.NOT_A_RELEASE.name, notARelease.reason, "§10.3's decoder")

        // §10.2's two encodings: a key that decodes to the wrong length under both is refused.
        val shortKey = releaseBothWays(
            "a release whose decryption-key is the wrong length",
            DeliveryMessageFixtures.bound(
                DeliveryMessageFixtures.replacing(
                    fixture.releaseTags,
                    listOf(DeliveryTags.DECRYPTION_KEY, "abcd"),
                ),
                NenyaKind.FILE_MESSAGE,
                fixture.index,
            ),
        )
        assertFalse(shortKey.ok, "§10.2 requires rejecting a value of the wrong length")
        assertEquals(ChannelRejection.MALFORMED_KEY_MATERIAL.name, shortKey.reason, "§10.2")
    }

    /**
     * §7.4's `kind:14` offered to each of the three: the crossing's own refusal, because the
     * library's decoders have no parameter for one.
     *
     * The same fact `JsSettlementEqualityTest` records for `PaymentReceipt.decode` and for the same
     * reason — §7.4 puts chat outside the bound half in so many words — asserted here because each
     * new entry point is a new place the vocabulary could have been read as a protocol refusal. No
     * new [JsCrossing] reason is added by this task and the set is checked to prove it.
     */
    @Test
    @JsName("a_chat_rumor_offered_to_any_of_the_three_decoders_is_the_crossing_s_own_refusal")
    fun `a chat rumor offered to any of the three decoders is the crossing's own refusal`() {
        val chat: AttributedRumor = ChannelFixtures.attribute(NenyaKind.CHAT, emptyList())
        val answers = listOf(
            "decodeProposal" to decodeProposal(JsRumor(chat)).let { Triple(it.ok, it.reason, it.reasonVocabulary) },
            "decodeCommitment" to decodeCommitment(JsRumor(chat)).let { Triple(it.ok, it.reason, it.reasonVocabulary) },
            "decodeRelease" to decodeRelease(JsRumor(chat)).let { Triple(it.ok, it.reason, it.reasonVocabulary) },
        )
        for ((name, answer) in answers) {
            val (ok, reason, vocabulary) = answer
            assertFalse(ok, "$name: a chat is not one of §7.4's bound kinds")
            assertEquals(JsCrossing.NOT_A_BOUND_RUMOR, reason, "$name: the crossing's own reason")
            assertEquals(
                JsCrossing.VOCABULARY,
                vocabulary,
                "$name: a page must never be able to read this as a §7.5 or §10.1 refusal",
            )
            assertTrue(
                reason in JsCrossing.REASONS,
                "$name: every reason this boundary reports must be one of the constants it declares",
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // The three calls, made twice.
    // -----------------------------------------------------------------------------------------

    /** One rumor through `OrderProposal.decode` and through [decodeProposal], held equal. */
    private fun proposalBothWays(what: String, rumor: AttributedRumor.Bound): JsProposalResult {
        val expected = JsMessageFixtures.answerOf { OrderProposal.decode(rumor) }
        val crossed = decodeProposal(JsRumor(rumor))
        JsMessageFixtures.assertSameProposal(crossed, expected, what)
        return crossed
    }

    /** One rumor through `DeliveryCommitmentMessage.decode` and through [decodeCommitment]. */
    private fun commitmentBothWays(what: String, rumor: AttributedRumor.Bound): JsCommitmentResult {
        val expected = JsMessageFixtures.answerOf { DeliveryCommitmentMessage.decode(rumor) }
        val crossed = decodeCommitment(JsRumor(rumor))
        JsMessageFixtures.assertSameCommitment(crossed, expected, what)
        return crossed
    }

    /** One rumor through `DeliverableReleaseMessage.decode` and through [decodeRelease]. */
    private fun releaseBothWays(what: String, rumor: AttributedRumor.Bound): JsReleaseResult {
        val expected = JsMessageFixtures.answerOf { DeliverableReleaseMessage.decode(rumor) }
        val crossed = decodeRelease(JsRumor(rumor))
        JsMessageFixtures.assertSameRelease(crossed, expected, what)
        return crossed
    }
}
