package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelFixtures
import dev.eryalabs.nenya.channel.ChannelRejection
import dev.eryalabs.nenya.channel.ChannelTags
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderMachine
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T48 across the JavaScript boundary: [checkAcceptance] answers exactly what
 * `OrderProposal.accepts` answers, and [decodeStatusUpdate] exactly what `OrderStatusMessage.decode`
 * answers — field for field, over the same rumors.
 *
 * ### What was lost before, restated as what this file proves
 *
 * `stepOrder`'s `acceptance_received` computes §7.6's verdict and keeps only `Accepted`; the other two
 * cases arrive at a page as `JsCrossing.UNCONSTRUCTIBLE_VALUE`, the same answer as an argument that
 * could not be built. Each case below runs one pair of rumors through the Kotlin call and through the
 * crossing and holds the two equal, so a page told "counter-proposal on `amount_msat`" is told what
 * this library found, and nothing a second comparison decided.
 *
 * ### The named mutation
 *
 * T48's mutation is "report the counter-proposal `accepted`". [assertSameVerdict] reads the expected
 * `kind` off the Kotlin `Acceptance` by its own `when`, so a crossing that promoted a counter-proposal
 * turns the first assertion red — and
 * [a padded amount_msat is a counter-proposal and still refuses at stepOrder] is the control aimed at it.
 */
class JsVerdictEqualityTest {

    private companion object {

        /** How many generated proposal/acceptance pairs the corpus sweeps draw. */
        const val CORPUS: Int = 48

        /** A reading before every generated deadline: §7.5's proposals are dated [ProposalFixtures.CREATED_AT]. */
        val CLOCK: FakeClock = FakeClock(ProposalFixtures.CREATED_AT)
    }

    // -----------------------------------------------------------------------------------------
    // checkAcceptance
    // -----------------------------------------------------------------------------------------

    /**
     * §7.6's three answers and its refusals, over the generated corpus, each held equal to `accepts`.
     *
     * Every pair is offered six ways — as sent, with `amount_msat` one millisatoshi higher, with
     * `amount_msat` spelled with a leading zero, carrying `cancelled`, sealed by a stranger, and
     * checked against the buyer's own key — so each fixture reaches all four kinds. The kinds reached
     * are counted and asserted, because an equality between two refusals is true and says nothing.
     */
    @Test
    @JsName("checkAcceptance_crosses_section_7_6_s_three_way_answer_over_the_corpus")
    fun `checkAcceptance crosses §7_6's three-way answer over the corpus`() {
        val kinds = mutableMapOf<String, Int>()
        for (fixture in ProposalFixtures.pairs(CORPUS)) {
            val index = fixture.index
            val proposal = ProposalFixtures.bound(fixture.proposalTags, index)
            val provider = providerOf(index)
            val amount = fixture.proposalTags.first { it[0] == ChannelTags.AMOUNT_MSAT }[1]
            val cases = listOf(
                "as sent" to fixture.acceptanceTags,
                "padded amount_msat" to ProposalFixtures.replacing(
                    fixture.acceptanceTags,
                    listOf(ChannelTags.AMOUNT_MSAT, (amount.toLong() + 1L).toString()),
                ),
                "leading-zero amount_msat" to ProposalFixtures.replacing(
                    fixture.acceptanceTags,
                    listOf(ChannelTags.AMOUNT_MSAT, "0$amount"),
                ),
                "cancelled" to ProposalFixtures.replacing(
                    fixture.acceptanceTags,
                    listOf(ChannelVocabulary.STATUS, OrderStatusCodec.write(OrderState.CANCELLED)),
                ),
            )
            for ((what, tags) in cases) {
                val crossed = checkBothWays("$what $index", proposal, statusFrom(provider, tags), provider)
                kinds[crossed.kind] = (kinds[crossed.kind] ?: 0) + 1
            }
            val stranger = checkBothWays(
                "sealed by a stranger $index",
                proposal,
                statusFrom(ProposalFixtures.pubkey(index + 2), fixture.acceptanceTags),
                provider,
            )
            kinds[stranger.kind] = (kinds[stranger.kind] ?: 0) + 1
            val buyer = checkBothWays(
                "checked against the buyer $index",
                proposal,
                statusFrom(provider, fixture.acceptanceTags),
                ProposalFixtures.pubkey(index),
            )
            kinds[buyer.kind] = (kinds[buyer.kind] ?: 0) + 1
        }
        assertEquals(CORPUS, kinds["accepted"], "every pair as sent is §7.6's acceptance: $kinds")
        assertEquals(2 * CORPUS, kinds["counter_proposal"], "both paddings diverge: $kinds")
        assertEquals(CORPUS, kinds["not_an_acceptance"], "`cancelled` is not an acceptance: $kinds")
        assertEquals(2 * CORPUS, kinds["refused"], "the stranger and the buyer are refused: $kinds")
    }

    /**
     * T48's negative control: one millisatoshi more is `counter_proposal`, never `accepted` — and the
     * same pair still refuses at [stepOrder], so the new entry point gave a page no door the machine
     * did not already have.
     *
     * The as-sent pair is driven through the same machine first, so the refusal below is about the
     * divergent term and not about a clock or an order that could not be accepted at all.
     */
    @Test
    @JsName("a_padded_amount_msat_is_a_counter_proposal_and_still_refuses_at_stepOrder")
    fun `a padded amount_msat is a counter-proposal and still refuses at stepOrder`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val provider = providerOf(messages.index)
        val padded = statusFrom(
            provider,
            ProposalFixtures.replacing(
                ProposalFixtures.acceptanceTags(messages.proposalTags, messages.index),
                listOf(ChannelTags.AMOUNT_MSAT, (OrderFixtures.PRICE.millisatoshis + 1L).toString()),
            ),
        )

        val check = checkBothWays("a padded acceptance", messages.proposalRumor, padded, provider)
        assertEquals("counter_proposal", check.kind, "§7.6: different terms are a counter-proposal")
        assertFalse(check.accepted, "a counter-proposal is never an acceptance")
        assertEquals(listOf(ChannelTags.AMOUNT_MSAT), check.divergentTerms.toList(), "named by tag")
        assertNull(check.terms, "a counter-proposal publishes no accepted terms")
        assertNull(check.reason, "a counter-proposal is an answer, not a refusal")

        val machine = JsOrderMachine(JsFlowFixtures.crossedEnvironment(clock = CLOCK))
        val proposed = JsOrder(
            OrderMachine(CLOCK).open(OrderEvent.Proposal(messages.proposal.order, messages.proposal.terms)),
        )
        val stepped = stepOrder(machine, proposed, acceptanceEvent(messages.proposalRumor, padded, provider))
        assertFalse(stepped.ok, "`acceptance_received` keeps requiring `Acceptance.Accepted`")
        assertEquals(JsCrossing.UNCONSTRUCTIBLE_VALUE, stepped.reason, "the machine is never offered it")
        assertEquals(JsCrossing.VOCABULARY, stepped.reasonVocabulary)

        val asSent = stepOrder(
            machine,
            proposed,
            acceptanceEvent(messages.proposalRumor, messages.acceptanceRumor, provider),
        )
        assertTrue(asSent.ok, "the control pair must advance: ${asSent.reason} ${asSent.detail}")
        assertEquals(OrderState.ACCEPTED.name, asSent.order?.state, "§11.2's proposed → accepted")
        assertEquals(
            "accepted",
            checkAcceptance(JsRumor(messages.proposalRumor), JsRumor(messages.acceptanceRumor), provider).kind,
            "and the entry point agrees with the machine about the pair that advanced it",
        )
    }

    /**
     * Each of §7.6's four term names diverging alone, and a `type=3` carrying none of them.
     *
     * The divergence list is the one field a page acts on — it is what a new `type=1` has to fix — so
     * it is held equal for every term rather than only for `amount_msat`. Each replacement is the
     * proposal's own value moved by one, through the generator's own helpers — never typed.
     */
    @Test
    @JsName("each_diverging_term_is_named_and_an_update_with_no_terms_names_all_four")
    fun `each diverging term is named and an update with no terms names all four`() {
        val fixture = ProposalFixtures.pairs(CORPUS).first { it.basisPoints != null && it.deliverBy != null }
        val index = fixture.index
        val proposal = ProposalFixtures.bound(fixture.proposalTags, index)
        val provider = providerOf(index)
        val moved = mapOf(
            ChannelVocabulary.ITEM to listOf(ChannelVocabulary.ITEM, ProposalFixtures.coordinate(index + 1), "1"),
            ChannelTags.AMOUNT_MSAT to listOf(ChannelTags.AMOUNT_MSAT, (fixture.priceMsat + 1L).toString()),
            ChannelVocabulary.FEE to ProposalFixtures.feeTag(index, (fixture.basisPoints ?: 0) + 1),
            ChannelVocabulary.DELIVER_BY to
                listOf(ChannelVocabulary.DELIVER_BY, ((fixture.deliverBy ?: 0L) + 1L).toString()),
        )
        assertEquals(ProposalFixtures.TERM_NAMES.toSet(), moved.keys, "one replacement per §7.6 term")
        for (name in ProposalFixtures.TERM_NAMES) {
            val spoiled = moved.getValue(name)
            val crossed = checkBothWays(
                "a diverging $name",
                proposal,
                statusFrom(provider, ProposalFixtures.replacing(fixture.acceptanceTags, spoiled)),
                provider,
            )
            assertEquals("counter_proposal", crossed.kind, "$name diverging is a counter-proposal")
            assertEquals(listOf(name), crossed.divergentTerms.toList(), "$name, by name and alone")
        }

        var bare = fixture.acceptanceTags
        for (name in ProposalFixtures.TERM_NAMES) bare = ProposalFixtures.without(bare, name)
        val none = checkBothWays("an update with no terms", proposal, statusFrom(provider, bare), provider)
        assertEquals(ProposalFixtures.TERM_NAMES, none.divergentTerms.toList(), "all four, in §7.6's order")
    }

    /**
     * The refusals `accepts` and the two decoders make, returned as values with the Kotlin reason
     * and tag — plus the one refusal that is the crossing's own.
     */
    @Test
    @JsName("checkAcceptance_returns_the_library_s_refusals_as_values")
    fun `checkAcceptance returns the library's refusals as values`() {
        val messages = OrderFixtures.messages(OrderFixtures.ORDER_INDEX, stream = 0)
        val other = OrderFixtures.messages(OrderFixtures.OTHER_ORDER_INDEX, stream = 0)
        val provider = providerOf(messages.index)

        val cases = listOf(
            Triple("another order's acceptance", other.acceptanceRumor, provider) to
                ChannelRejection.DIFFERENT_ORDER,
            Triple("a malformed provider key", messages.acceptanceRumor, provider.dropLast(1)) to
                ChannelRejection.MALFORMED_PROVIDER_PUBKEY,
            Triple("the proposal offered as its own update", messages.proposalRumor, provider) to
                ChannelRejection.NOT_A_STATUS_UPDATE,
        )
        for ((case, expected) in cases) {
            val (what, status, key) = case
            val crossed = checkBothWays(what, messages.proposalRumor, status, key)
            assertEquals("refused", crossed.kind, what)
            assertEquals(expected.name, crossed.reason, what)
            assertEquals("ChannelRejection", crossed.reasonVocabulary, "$what: §7.6's own vocabulary")
        }

        // The acceptance offered where the proposal belongs: §7.5's decoder refuses it first.
        val swapped = checkBothWays("the two rumors swapped", messages.acceptanceRumor, messages.proposalRumor, provider)
        assertEquals(ChannelRejection.NOT_A_PROPOSAL.name, swapped.reason, "§7.5's decoder")

        // §7.4's `kind:14`: no decoder has a parameter for it, so the crossing answers.
        val chat: AttributedRumor = ChannelFixtures.attribute(NenyaKind.CHAT, emptyList())
        val crossedChat = checkAcceptance(JsRumor(messages.proposalRumor), JsRumor(chat), provider)
        assertEquals("refused", crossedChat.kind)
        assertFalse(crossedChat.accepted)
        assertEquals(JsCrossing.NOT_A_BOUND_RUMOR, crossedChat.reason, "the crossing's own reason")
        assertEquals(JsCrossing.VOCABULARY, crossedChat.reasonVocabulary, "never a §7.6 ruling")
    }

    // -----------------------------------------------------------------------------------------
    // decodeStatusUpdate
    // -----------------------------------------------------------------------------------------

    /**
     * §7.4's `type=3`, decoded both ways — every field, over the corpus and over §11.1's whole
     * vocabulary plus a listing token, which §11.1 says lands in the unknown sink.
     */
    @Test
    @JsName("decodeStatusUpdate_crosses_section_7_4_s_status_update")
    fun `decodeStatusUpdate crosses §7_4's status update`() {
        val tokens = OrderState.entries.mapNotNull { it.token } + "sold"
        val seen = mutableSetOf<String>()
        for (fixture in ProposalFixtures.pairs(CORPUS)) {
            val token = tokens[fixture.index % tokens.size]
            val tags = ProposalFixtures.replacing(fixture.acceptanceTags, listOf(ChannelVocabulary.STATUS, token))
            val crossed = decodeBothWays("update ${fixture.index}", statusFrom(providerOf(fixture.index), tags))
            val update = crossed.statusUpdate ?: fail("the generated corpus must decode: ${crossed.reason}")
            seen += update.status
        }
        assertEquals(
            OrderState.entries.map { it.name }.toSet(),
            seen,
            "the corpus must reach every §11.1 state and the unknown sink",
        )
    }

    /** `OrderStatusMessage.decode`'s two refusals and the crossing's own, as values. */
    @Test
    @JsName("decodeStatusUpdate_returns_its_refusals_as_values")
    fun `decodeStatusUpdate returns its refusals as values`() {
        val fixture = ProposalFixtures.pairs(1).single()
        val proposal = ProposalFixtures.bound(fixture.proposalTags, fixture.index)
        val notAnUpdate = decodeBothWays("a proposal", proposal)
        assertEquals(ChannelRejection.NOT_A_STATUS_UPDATE.name, notAnUpdate.reason, "§7.4's `type`")

        val noStatus = decodeBothWays(
            "an update with no status",
            statusFrom(providerOf(fixture.index), ProposalFixtures.without(fixture.acceptanceTags, ChannelVocabulary.STATUS)),
        )
        assertEquals(ChannelRejection.MISSING_REQUIRED_TAG.name, noStatus.reason, "§7.4: never defaulted")
        assertEquals(ChannelVocabulary.STATUS, noStatus.tag, "named by tag")

        val chat: AttributedRumor = ChannelFixtures.attribute(NenyaKind.CHAT, emptyList())
        val crossedChat = decodeStatusUpdate(JsRumor(chat))
        assertFalse(crossedChat.ok)
        assertEquals(JsCrossing.NOT_A_BOUND_RUMOR, crossedChat.reason, "the crossing's own reason")
        assertEquals(JsCrossing.VOCABULARY, crossedChat.reasonVocabulary)
    }

    // -----------------------------------------------------------------------------------------
    // The calls, made twice.
    // -----------------------------------------------------------------------------------------

    /** The key the corpus's acceptances are sealed by: [ProposalFixtures]' provider offset. */
    private fun providerOf(index: Int): String = ProposalFixtures.pubkey(index + 1)

    /** [tags] as a `type=3` sealed by [author]. */
    private fun statusFrom(author: String, tags: List<List<String>>): AttributedRumor.Bound {
        val rumor = AttributedRumor.attribute(author, ChannelFixtures.checked(author, NenyaKind.ORDER_MESSAGE, tags))
        return rumor as? AttributedRumor.Bound ?: fail("a kind:16 must decode to a bound rumor")
    }

    private fun acceptanceEvent(
        proposal: AttributedRumor.Bound,
        status: AttributedRumor.Bound,
        provider: String,
    ): JsOrderEvent = JsOrderEvent(
        type = "acceptance_received",
        proposalRumor = JsRumor(proposal),
        acceptanceRumor = JsRumor(status),
        providerPubkey = provider,
    )

    /** One pair through `OrderProposal.accepts` and through [checkAcceptance], held equal. */
    private fun checkBothWays(
        what: String,
        proposal: AttributedRumor.Bound,
        status: AttributedRumor.Bound,
        provider: String,
    ): JsAcceptanceCheck {
        val expected = JsMessageFixtures.answerOf {
            OrderProposal.decode(proposal).accepts(OrderStatusMessage.decode(status), provider)
        }
        val crossed = checkAcceptance(JsRumor(proposal), JsRumor(status), provider)
        assertSameVerdict(crossed, expected, what)
        return crossed
    }

    /**
     * The whole of §7.6's equality: [crossed] says exactly what [expected] says, every field.
     *
     * Each expected value is projected from the Kotlin `Acceptance`'s published fields here, by its own
     * `when` — never through the crossing's helpers — so the two sides are independent translations.
     */
    private fun assertSameVerdict(crossed: JsAcceptanceCheck, expected: JsMessageFixtures.Decoded<Acceptance>, what: String) {
        val answer = expected.value
        val expectedKind = when (answer) {
            is Acceptance.Accepted -> "accepted"
            is Acceptance.CounterProposal -> "counter_proposal"
            is Acceptance.NotAnAcceptance -> "not_an_acceptance"
            null -> "refused"
        }
        assertEquals(expectedKind, crossed.kind, "$what: the two sides reached different answers")
        assertEquals(answer is Acceptance.Accepted, crossed.accepted, "$what: `accepted` must match `kind`")

        val accepted = answer as? Acceptance.Accepted
        assertEquals(accepted?.order?.toHex(), crossed.orderIdHex, "$what: the order id")
        assertEquals(accepted?.provider, crossed.provider, "$what: the provider checked against")
        assertEquals(
            accepted?.terms?.let {
                listOf(
                    JsBoundaryFixtures.decimal(it.split.price.millisatoshis),
                    JsBoundaryFixtures.basisPointsOf(it.split.term),
                    JsBoundaryFixtures.decimalOrNull(it.expiration),
                    JsBoundaryFixtures.decimalOrNull(it.deliverBy),
                )
            },
            crossed.terms?.let { listOf(it.priceMsat, it.feeBasisPoints, it.expirationSeconds, it.deliverBySeconds) },
            "$what: the accepted terms",
        )
        assertEquals(
            (answer as? Acceptance.CounterProposal)?.divergentTerms ?: emptyList(),
            crossed.divergentTerms.toList(),
            "$what: the diverging tag names",
        )
        val claimed = (answer as? Acceptance.NotAnAcceptance)?.status
        assertEquals(claimed?.name, crossed.claimedStatus, "$what: the status claimed instead")
        assertEquals(claimed?.token, crossed.claimedStatusToken, "$what: its §11.1 token")

        assertEquals(expected.reason, crossed.reason, "$what: the refusals differ")
        assertEquals(expected.vocabulary, crossed.reasonVocabulary, "$what: the vocabularies differ")
        assertEquals(expected.tag, crossed.tag, "$what: the tags the refusals are about differ")
        assertEquals(expected.detail, crossed.detail, "$what: the details differ")
    }

    /** One rumor through `OrderStatusMessage.decode` and through [decodeStatusUpdate], held equal. */
    private fun decodeBothWays(what: String, rumor: AttributedRumor.Bound): JsStatusUpdateResult {
        val expected = JsMessageFixtures.answerOf { OrderStatusMessage.decode(rumor) }
        val crossed = decodeStatusUpdate(JsRumor(rumor))
        JsMessageFixtures.assertSameStatusUpdate(crossed, expected, what)
        return crossed
    }
}
