package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.ChannelException
import dev.eryalabs.nenya.channel.DeliverableReleaseMessage
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.PrivateBid
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * The other side of T44's three decoders, projected onto the crossing a second time.
 *
 * The same discipline [JsBoundaryFixtures] and [JsFlowFixtures] record and the same reason: every
 * projection below is written from the published fields of the Kotlin type, so the assertion is
 * that two independently written crossings land on the same answer rather than that one crossing
 * agrees with itself.
 *
 * ### The field lists are swept, not claimed
 *
 * `JsSurfaceTest` holds each list below equal to the getters the compiled class has, which is what
 * makes "every field is compared" true. A getter added to `JsOrderProposal` and not to
 * [proposalFields] turns that test red, and that is the only point at which anybody is told.
 */
internal object JsMessageFixtures {

    /**
     * What one of the three decoders answered, or the refusal, in one shape.
     *
     * One type for all three, because the boundary gives all three the same four refusal fields and
     * the question is whether that shape loses anything.
     */
    class Decoded<T : Any>(
        val value: T?,
        val reason: String?,
        val vocabulary: String?,
        val tag: String?,
        val detail: String?,
    ) {

        /** True exactly when the decoder answered rather than refused. */
        val ok: Boolean get() = value != null
    }

    /**
     * [call]'s answer, with the refusals these decoders raise **as exceptions** reduced to the
     * shape decision **P** returns.
     *
     * ### One arm, and the one that is deliberately absent
     *
     * `ChannelException` is §7.5's, §10.1's, §10.2's and §10.3's own refusal type and carries the
     * tag each rule is about. There is **no** `OrderStateException` arm, although `jsGuarded` has
     * one: §7.5's inverted-deadline rule fires in `ChannelTerms.checkDeadlines`, which
     * `OrderProposal.decode` calls *before* `OrderTerms.of`, so the `init` that raises
     * `OrderStateException` is unreachable from these three decoders. An arm for it would be a
     * catch nothing can enter, which reads to a later reviewer as a case this file has covered.
     * (`buildProposal` does reach it, and `JsCrossingTest` proves that crossing.)
     *
     * Deliberately not `Throwable` either: anything else fails this test rather than being quietly
     * compared against whatever the facade did with it.
     */
    fun <T : Any> answerOf(call: () -> T): Decoded<T> = try {
        Decoded(call(), null, null, null, null)
    } catch (refused: ChannelException) {
        Decoded(null, refused.reason.name, "ChannelRejection", refused.tag, refused.message)
    }

    // -----------------------------------------------------------------------------------------
    // §7.5's proposal.
    // -----------------------------------------------------------------------------------------

    /** Every value `JsOrderProposal` publishes, paired with the same value off [expected]. */
    fun proposalFields(
        crossed: JsOrderProposal,
        expected: OrderProposal,
    ): List<JsBoundaryFixtures.Field> = listOf(
        JsBoundaryFixtures.Field("buyer", crossed.buyer, expected.buyer),
        JsBoundaryFixtures.Field("idHex", crossed.idHex, expected.id.toHex()),
        JsBoundaryFixtures.Field(
            "createdAtSeconds",
            crossed.createdAtSeconds,
            JsBoundaryFixtures.decimal(expected.createdAt),
        ),
        JsBoundaryFixtures.Field("orderIdHex", crossed.orderIdHex, expected.order.toHex()),
        JsBoundaryFixtures.Field(
            "itemCoordinate",
            crossed.itemCoordinate,
            expected.item.coordinate.toTagValue(),
        ),
        JsBoundaryFixtures.Field("itemQuantity", crossed.itemQuantity, expected.item.quantity),
        JsBoundaryFixtures.Field(
            "priceMsat",
            crossed.priceMsat,
            JsBoundaryFixtures.decimal(expected.terms.split.price.millisatoshis),
        ),
        JsBoundaryFixtures.Field(
            "feeMsat",
            crossed.feeMsat,
            JsBoundaryFixtures.decimal(expected.terms.split.fee.millisatoshis),
        ),
        JsBoundaryFixtures.Field(
            "totalMsat",
            crossed.totalMsat,
            JsBoundaryFixtures.decimal(expected.terms.split.total.millisatoshis),
        ),
        JsBoundaryFixtures.Field(
            "feeBasisPoints",
            crossed.feeBasisPoints,
            JsBoundaryFixtures.basisPointsOf(expected.terms.split.term),
        ),
        JsBoundaryFixtures.Field(
            "feePayeeRequired",
            crossed.feePayeeRequired,
            expected.terms.split.feePayeeRequired,
        ),
        JsBoundaryFixtures.Field("feeRecipient", crossed.feeRecipient, expected.feeRecipient),
        JsBoundaryFixtures.Field(
            "expirationSeconds",
            crossed.expirationSeconds,
            JsBoundaryFixtures.decimalOrNull(expected.terms.expiration),
        ),
        JsBoundaryFixtures.Field(
            "deliverBySeconds",
            crossed.deliverBySeconds,
            JsBoundaryFixtures.decimalOrNull(expected.terms.deliverBy),
        ),
        JsBoundaryFixtures.Field(
            "counterparties",
            crossed.counterparties.map { it.toList() },
            expected.counterparties.map { listOfNotNull(it.pubkey, it.relay) },
        ),
        // §4.1's canonical object form, which is the comparison two events that merely look alike
        // cannot satisfy: the `id` in it is the SHA-256 of the serialisation.
        JsBoundaryFixtures.Field(
            "event",
            crossed.event.unsignedJson().value,
            JsBoundaryFixtures.json(expected.encode()),
        ),
    )

    /** The whole of §7.5's decode equality: [crossed] says exactly what [expected] says. */
    fun assertSameProposal(crossed: JsProposalResult, expected: Decoded<OrderProposal>, what: String) {
        assertSameShape(crossed.ok, crossed.kind, "proposal", expected, what) {
            assertNull(crossed.proposal, "$what: a refusal must carry no proposal")
            assertEquals(expected.reason, crossed.reason, "$what: the refusals differ")
            assertEquals(expected.vocabulary, crossed.reasonVocabulary, "$what: the vocabularies differ")
            assertEquals(expected.tag, crossed.tag, "$what: the tags the refusals are about differ")
            assertEquals(expected.detail, crossed.detail, "$what: the details differ")
        }
        val decoded = expected.value ?: return
        val value = crossed.proposal ?: fail("$what: the crossed answer carries no proposal")
        JsBoundaryFixtures.assertSameFields(proposalFields(value, decoded), "$what: the proposal")
        assertNull(crossed.reason, "$what: an answer must name no reason")
    }

    // -----------------------------------------------------------------------------------------
    // §6.1's private bid. T59.
    // -----------------------------------------------------------------------------------------

    /** Every value `JsPrivateBid` publishes, paired with the same value off [expected]. */
    fun privateBidFields(
        crossed: JsPrivateBid,
        expected: PrivateBid,
    ): List<JsBoundaryFixtures.Field> = listOf(
        JsBoundaryFixtures.Field("bidder", crossed.bidder, expected.bidder),
        JsBoundaryFixtures.Field("idHex", crossed.idHex, expected.id.toHex()),
        JsBoundaryFixtures.Field(
            "createdAtSeconds",
            crossed.createdAtSeconds,
            JsBoundaryFixtures.decimal(expected.createdAt),
        ),
        JsBoundaryFixtures.Field(
            "itemCoordinate",
            crossed.itemCoordinate,
            expected.item.coordinate.toTagValue(),
        ),
        JsBoundaryFixtures.Field("itemQuantity", crossed.itemQuantity, expected.item.quantity),
        JsBoundaryFixtures.Field(
            "priceMsat",
            crossed.priceMsat,
            JsBoundaryFixtures.decimal(expected.price.millisatoshis),
        ),
        JsBoundaryFixtures.Field(
            "feeMsat",
            crossed.feeMsat,
            JsBoundaryFixtures.decimal(expected.split.fee.millisatoshis),
        ),
        JsBoundaryFixtures.Field(
            "totalMsat",
            crossed.totalMsat,
            JsBoundaryFixtures.decimal(expected.split.total.millisatoshis),
        ),
        JsBoundaryFixtures.Field(
            "feeBasisPoints",
            crossed.feeBasisPoints,
            JsBoundaryFixtures.basisPointsOf(expected.fee),
        ),
        JsBoundaryFixtures.Field(
            "feePayeeRequired",
            crossed.feePayeeRequired,
            expected.split.feePayeeRequired,
        ),
        JsBoundaryFixtures.Field("feeRecipient", crossed.feeRecipient, expected.feeRecipient),
        JsBoundaryFixtures.Field(
            "expirationSeconds",
            crossed.expirationSeconds,
            JsBoundaryFixtures.decimalOrNull(expected.expiration),
        ),
        JsBoundaryFixtures.Field(
            "deliverBySeconds",
            crossed.deliverBySeconds,
            JsBoundaryFixtures.decimalOrNull(expected.deliverBy),
        ),
        JsBoundaryFixtures.Field(
            "counterparties",
            crossed.counterparties.map { it.toList() },
            expected.counterparties.map { listOfNotNull(it.pubkey, it.relay) },
        ),
        JsBoundaryFixtures.Field(
            "event",
            crossed.event.unsignedJson().value,
            JsBoundaryFixtures.json(expected.encode()),
        ),
    )

    /** [crossed] is [expected]: the same refusal, or the same bid field for field. */
    fun assertSamePrivateBid(crossed: JsPrivateBidResult, expected: Decoded<PrivateBid>, what: String) {
        assertSameShape(crossed.ok, crossed.kind, "private_bid", expected, what) {
            assertNull(crossed.privateBid, "$what: a refusal must carry no bid")
            assertEquals(expected.reason, crossed.reason, "$what: the refusals differ")
            assertEquals(expected.vocabulary, crossed.reasonVocabulary, "$what: the vocabularies differ")
            assertEquals(expected.tag, crossed.tag, "$what: the tags the refusals are about differ")
            assertEquals(expected.detail, crossed.detail, "$what: the details differ")
        }
        val decoded = expected.value ?: return
        val value = crossed.privateBid ?: fail("$what: the crossed answer carries no bid")
        JsBoundaryFixtures.assertSameFields(privateBidFields(value, decoded), "$what: the private bid")
        assertNull(crossed.reason, "$what: an answer must name no reason")
    }

    // -----------------------------------------------------------------------------------------
    // §10.1's commitment.
    // -----------------------------------------------------------------------------------------

    /** Every value `JsCommitmentMessage` publishes, paired with the same value off [expected]. */
    fun commitmentFields(
        crossed: JsCommitmentMessage,
        expected: DeliveryCommitmentMessage,
    ): List<JsBoundaryFixtures.Field> = listOf(
        JsBoundaryFixtures.Field("sender", crossed.sender, expected.sender),
        JsBoundaryFixtures.Field("idHex", crossed.idHex, expected.id.toHex()),
        JsBoundaryFixtures.Field(
            "createdAtSeconds",
            crossed.createdAtSeconds,
            JsBoundaryFixtures.decimal(expected.createdAt),
        ),
        JsBoundaryFixtures.Field("orderIdHex", crossed.orderIdHex, expected.order.toHex()),
        JsBoundaryFixtures.Field("url", crossed.url, expected.url),
        JsBoundaryFixtures.Field(
            "commitment",
            crossed.commitment.let { listOf(it.xHex, it.oxHex, it.mimeType, it.sizeBytes) },
            expected.commitment.let {
                listOf(
                    it.x.toHex(),
                    it.ox.toHex(),
                    it.mimeType,
                    JsBoundaryFixtures.decimal(it.sizeBytes),
                )
            },
        ),
        JsBoundaryFixtures.Field(
            "event",
            crossed.event.unsignedJson().value,
            JsBoundaryFixtures.json(expected.encode()),
        ),
    )

    /** The whole of §10.1's decode equality. */
    fun assertSameCommitment(
        crossed: JsCommitmentResult,
        expected: Decoded<DeliveryCommitmentMessage>,
        what: String,
    ) {
        assertSameShape(crossed.ok, crossed.kind, "commitment", expected, what) {
            assertNull(crossed.commitment, "$what: a refusal must carry no commitment")
            assertEquals(expected.reason, crossed.reason, "$what: the refusals differ")
            assertEquals(expected.vocabulary, crossed.reasonVocabulary, "$what: the vocabularies differ")
            assertEquals(expected.tag, crossed.tag, "$what: the tags the refusals are about differ")
            assertEquals(expected.detail, crossed.detail, "$what: the details differ")
        }
        val decoded = expected.value ?: return
        val value = crossed.commitment ?: fail("$what: the crossed answer carries no commitment")
        JsBoundaryFixtures.assertSameFields(commitmentFields(value, decoded), "$what: the commitment")
        assertNull(crossed.reason, "$what: an answer must name no reason")
    }

    // -----------------------------------------------------------------------------------------
    // §10.3's release.
    // -----------------------------------------------------------------------------------------

    /** Every value `JsReleaseMessage` publishes, paired with the same value off [expected]. */
    fun releaseFields(
        crossed: JsReleaseMessage,
        expected: DeliverableReleaseMessage,
    ): List<JsBoundaryFixtures.Field> = listOf(
        JsBoundaryFixtures.Field("sender", crossed.sender, expected.sender),
        JsBoundaryFixtures.Field("idHex", crossed.idHex, expected.id.toHex()),
        JsBoundaryFixtures.Field(
            "createdAtSeconds",
            crossed.createdAtSeconds,
            JsBoundaryFixtures.decimal(expected.createdAt),
        ),
        JsBoundaryFixtures.Field("orderIdHex", crossed.orderIdHex, expected.order.toHex()),
        JsBoundaryFixtures.Field(
            "release",
            crossed.release.let { listOf(it.xHex, it.oxHex, it.fileType, it.sizeBytes) },
            expected.release.let {
                listOf(
                    it.x.toHex(),
                    it.ox.toHex(),
                    it.fileType,
                    JsBoundaryFixtures.decimal(it.sizeBytes),
                )
            },
        ),
        JsBoundaryFixtures.Field(
            "event",
            crossed.event.unsignedJson().value,
            JsBoundaryFixtures.json(expected.encode()),
        ),
    )

    /** The whole of §10.3's decode equality. */
    fun assertSameRelease(
        crossed: JsReleaseResult,
        expected: Decoded<DeliverableReleaseMessage>,
        what: String,
    ) {
        assertSameShape(crossed.ok, crossed.kind, "release", expected, what) {
            assertNull(crossed.release, "$what: a refusal must carry no release")
            assertEquals(expected.reason, crossed.reason, "$what: the refusals differ")
            assertEquals(expected.vocabulary, crossed.reasonVocabulary, "$what: the vocabularies differ")
            assertEquals(expected.tag, crossed.tag, "$what: the tags the refusals are about differ")
            assertEquals(expected.detail, crossed.detail, "$what: the details differ")
        }
        val decoded = expected.value ?: return
        val value = crossed.release ?: fail("$what: the crossed answer carries no release")
        JsBoundaryFixtures.assertSameFields(releaseFields(value, decoded), "$what: the release")
        assertNull(crossed.reason, "$what: an answer must name no reason")
    }

    // -----------------------------------------------------------------------------------------
    // §7.4's status update. T48.
    // -----------------------------------------------------------------------------------------

    /** Every value `JsStatusUpdate` publishes, paired with the same value off [expected]. */
    fun statusUpdateFields(
        crossed: JsStatusUpdate,
        expected: OrderStatusMessage,
    ): List<JsBoundaryFixtures.Field> = listOf(
        JsBoundaryFixtures.Field("sender", crossed.sender, expected.sender),
        JsBoundaryFixtures.Field("idHex", crossed.idHex, expected.id.toHex()),
        JsBoundaryFixtures.Field(
            "createdAtSeconds",
            crossed.createdAtSeconds,
            JsBoundaryFixtures.decimal(expected.createdAt),
        ),
        JsBoundaryFixtures.Field("orderIdHex", crossed.orderIdHex, expected.order.toHex()),
        JsBoundaryFixtures.Field("status", crossed.status, expected.status.name),
        JsBoundaryFixtures.Field("statusToken", crossed.statusToken, expected.status.token),
        JsBoundaryFixtures.Field(
            "event",
            crossed.event.unsignedJson().value,
            JsBoundaryFixtures.json(expected.encode()),
        ),
    )

    /** The whole of §7.4's `type=3` decode equality. */
    fun assertSameStatusUpdate(
        crossed: JsStatusUpdateResult,
        expected: Decoded<OrderStatusMessage>,
        what: String,
    ) {
        assertSameShape(crossed.ok, crossed.kind, "status_update", expected, what) {
            assertNull(crossed.statusUpdate, "$what: a refusal must carry no update")
            assertEquals(expected.reason, crossed.reason, "$what: the refusals differ")
            assertEquals(expected.vocabulary, crossed.reasonVocabulary, "$what: the vocabularies differ")
            assertEquals(expected.tag, crossed.tag, "$what: the tags the refusals are about differ")
            assertEquals(expected.detail, crossed.detail, "$what: the details differ")
        }
        val decoded = expected.value ?: return
        val value = crossed.statusUpdate ?: fail("$what: the crossed answer carries no update")
        JsBoundaryFixtures.assertSameFields(statusUpdateFields(value, decoded), "$what: the update")
        assertNull(crossed.reason, "$what: an answer must name no reason")
    }

    /**
     * The two assertions every result object in this package owes before its own fields are read:
     * both sides agree on whether this is an answer, and `kind` discriminates the way `ok` does.
     *
     * [onRefusal] runs only for a refusal, so the four refusal fields are compared where the names
     * for them exist — each result class publishes its own and none of them shares a supertype.
     */
    private fun assertSameShape(
        crossedOk: Boolean,
        crossedKind: String,
        okToken: String,
        expected: Decoded<*>,
        what: String,
        onRefusal: () -> Unit,
    ) {
        assertEquals(expected.ok, crossedOk, "$what: one side decoded and the other refused")
        assertEquals(
            if (expected.ok) okToken else "refused",
            crossedKind,
            "$what: `kind` must discriminate the same way `ok` does",
        )
        if (!expected.ok) onRefusal()
    }
}
