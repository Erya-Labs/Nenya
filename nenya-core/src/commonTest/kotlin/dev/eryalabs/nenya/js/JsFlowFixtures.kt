package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.envelope.OpenedMessage
import dev.eryalabs.nenya.envelope.SealedMessage
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.order.Order
import dev.eryalabs.nenya.order.OrderOutcome
import dev.eryalabs.nenya.seam.EphemeralSigners
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.Nip44Decryption
import dev.eryalabs.nenya.seam.Randomness
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.seam.Secp256k1Ops
import dev.eryalabs.nenya.seam.Signer
import dev.eryalabs.nenya.settlement.FeeTermSighting
import dev.eryalabs.nenya.settlement.PaymentRequestStore
import dev.eryalabs.nenya.settlement.Settlement
import dev.eryalabs.nenya.wire.encodeLowerHex
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The other side of T43's half of the boundary: §7.1's two procedures, §9.2's two checks and
 * §11.2's two doors, projected onto the crossing a second time so the equality proof has two
 * independent translations to compare.
 *
 * ### The same discipline [JsBoundaryFixtures] records, and the same reason
 *
 * A test that fed the facade's own `asOrderEvent` into `OrderMachine` would prove the call is made
 * and nothing about the translation: both sides would share the only code that could be wrong. So
 * every projection below is written from the published fields of the Kotlin types, and the
 * assertion is that two independently written crossings land on the same answer.
 *
 * ### Why the seam crossings are here and not in `src/commonMain`
 *
 * `JsSigner`, `JsEnvironment` and `JsPaymentRequestStore` cross *into* the library as functions,
 * because a JavaScript object cannot implement a Kotlin interface. A page writes those functions by
 * hand. A **test** has a Kotlin `Signer` and a Kotlin `PaymentRequestStore` and needs them to
 * arrive through the page's door, so [crossed] below is the adapter a page would have written — the
 * inverse of `JsSeams.kt`'s, and deliberately not reused from it. Feeding `asSigner()`'s output
 * back through itself would compare the adapter with itself.
 */
internal object JsFlowFixtures {

    // -----------------------------------------------------------------------------------------
    // The seams, as the functions a page supplies.
    // -----------------------------------------------------------------------------------------

    /**
     * [signer] as the four functions [JsSigner] takes.
     *
     * `SeamAnswer.Unavailable` becomes `null` in every case, which is the whole of the inbound
     * convention: §17 requires the capability statement be this library's own, so there is
     * deliberately no way for a page to supply an `Unavailable` detail and nothing here invents one.
     */
    fun crossed(signer: Signer): JsSigner = JsSigner(
        publicKeyHex = { provided(signer.publicKey()) },
        signEvent = { serialisation -> provided(signer.signEvent(serialisation)) },
        nip44Encrypt = { key, plaintext -> provided(signer.nip44Encrypt(key, plaintext)) },
        nip44Decrypt = { key, payload ->
            when (val answer = signer.nip44Decrypt(key, payload)) {
                // §17's three answers, kept three: a payload the signer checked and refused is
                // `JsDecryption(false)`, and a signer that never ran is `null`. Collapsing the two
                // would report a check that ran as a check that did not.
                is SeamAnswer.Provided -> when (val decryption = answer.value) {
                    is Nip44Decryption.Decrypted -> JsDecryption(true, decryption.plaintext)
                    Nip44Decryption.Refused -> JsDecryption(false, null)
                }
                is SeamAnswer.Unavailable -> null
            }
        },
    )

    /** The §3 seams `GiftWrap.seal` and `OrderMachine` take, as the functions a page supplies. */
    fun crossedEnvironment(
        clock: NenyaClock? = null,
        randomness: Randomness? = null,
        secp: Secp256k1Ops? = null,
        ephemeral: EphemeralSigners? = null,
    ): JsEnvironment = JsEnvironment(
        nowSeconds = clock?.let { { provided(it.now())?.toString() } },
        randomBytesHex = randomness?.let { source ->
            { count -> provided(source.randomBytes(count))?.let { encodeLowerHex(it) } }
        },
        verifySchnorr = secp?.let { ops ->
            { keyHex, messageHex, signatureHex ->
                provided(
                    ops.verifySchnorr(
                        bytes(keyHex),
                        bytes(messageHex),
                        bytes(signatureHex),
                    ),
                )?.name
            }
        },
        freshSigner = ephemeral?.let { signers -> { provided(signers.fresh())?.let { crossed(it) } } },
    )

    /** [store] as the two functions [JsPaymentRequestStore] takes. */
    fun crossed(store: PaymentRequestStore): JsPaymentRequestStore = JsPaymentRequestStore(
        find = { orderIdHex, payeeToken ->
            store.find(
                dev.eryalabs.nenya.seam.OrderId.ofHex(orderIdHex),
                dev.eryalabs.nenya.payment.Payee.entries.first { it.token == payeeToken },
            )?.let { JsAcceptedRequest(it) }
        },
    )

    /** [sighting] as §8.4's point name and the `fee` row verbatim. */
    fun crossed(sighting: FeeTermSighting): JsFeeTermSighting =
        JsFeeTermSighting(sighting.point.name, sighting.term?.toTypedArray())

    /** [commitment] as §10.1's four crossed values, written here and not through the facade. */
    fun crossed(commitment: DeliverableCommitment): JsCommitment = JsCommitment(
        xHex = commitment.x.toHex(),
        oxHex = commitment.ox.toHex(),
        mimeType = commitment.mimeType,
        sizeBytes = commitment.sizeBytes.toString(),
    )

    /** [rumor] as the opaque §7.4 handle [open] hands back. */
    fun crossed(rumor: AttributedRumor): JsRumor = JsRumor(rumor)

    /** A `SeamAnswer`'s value, or `null` for the unavailable case. */
    private fun <T> provided(answer: SeamAnswer<T>): T? = when (answer) {
        is SeamAnswer.Provided -> answer.value
        is SeamAnswer.Unavailable -> null
    }

    /** Lowercase hex as its bytes, written here rather than through [JsHex]. */
    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // -----------------------------------------------------------------------------------------
    // §7.1's two answers, reduced to what both sides must agree on.
    // -----------------------------------------------------------------------------------------

    /** What `GiftWrap.seal` answered, or the refusal, in one shape. */
    class SealAnswer(
        val ok: Boolean,
        val sealed: SealedMessage?,
        val reason: String?,
        val vocabulary: String?,
        val detail: String?,
    )

    /** [call]'s answer, with `EnvelopeException` reduced to the same shape decision **P** returns. */
    fun sealAnswerOf(call: () -> SealedMessage): SealAnswer = try {
        SealAnswer(true, call(), null, null, null)
    } catch (refused: dev.eryalabs.nenya.envelope.EnvelopeException) {
        SealAnswer(false, null, refused.reason.name, "EnvelopeRejection", refused.message)
    }

    /** The whole of §7.1's write equality: [crossed] says exactly what [expected] says. */
    fun assertSameSeal(crossed: JsSealResult, expected: SealAnswer, what: String) {
        assertEquals(expected.ok, crossed.ok, "$what: one side sealed and the other refused")
        assertEquals(
            if (expected.ok) "sealed" else "refused",
            crossed.kind,
            "$what: `kind` must discriminate the same way `ok` does",
        )
        if (expected.ok) {
            val sealed = expected.sealed ?: fail("$what: a sealed answer with no message")
            assertSameWrap(crossed.toRecipient, sealed.toRecipient, "$what: the recipient copy")
            assertSameWrap(crossed.toSelf, sealed.toSelf, "$what: the self copy")
            assertEquals(
                sealed.notPerformedHere.map { it.name }.toSet(),
                crossed.notPerformedHere.toSet(),
                "$what: §17's not-performed statement differs, so one side claims a check the " +
                    "other says nobody made",
            )
            assertNull(crossed.reason, "$what: a sealed answer must name no reason")
            assertNull(crossed.detail, "$what: a sealed answer must carry no detail")
        } else {
            assertNull(crossed.toRecipient, "$what: a refusal must carry no recipient copy")
            assertNull(crossed.toSelf, "$what: a refusal must carry no self copy")
            assertTrue(
                crossed.notPerformedHere.isEmpty(),
                "$what: a refusal produced no message, so there is nothing for §17 to report on",
            )
            assertEquals(expected.reason, crossed.reason, "$what: the refusals differ")
            assertEquals(expected.vocabulary, crossed.reasonVocabulary, "$what: the vocabularies differ")
            assertEquals(expected.detail, crossed.detail, "$what: the details differ")
        }
    }

    /** One copy, down to the JSON a relay would be handed and the id that is its hash. */
    private fun assertSameWrap(
        crossed: JsOutgoingWrap?,
        expected: dev.eryalabs.nenya.envelope.OutgoingWrap,
        what: String,
    ) {
        val wrap = crossed ?: fail("$what: the crossed answer carries no copy")
        assertEquals(expected.json, wrap.json, "$what: the object forms differ, so the ids differ")
        assertEquals(expected.id.toHex(), wrap.idHex, "$what: the recomputed ids differ")
        assertEquals(expected.addressee, wrap.addressee, "$what: the addressees differ")
    }

    /** What `GiftWrap.open` answered, or the refusal, in one shape. */
    class OpenAnswer(
        val ok: Boolean,
        val opened: OpenedMessage?,
        val reason: String?,
        val vocabulary: String?,
        val detail: String?,
    )

    /** [call]'s answer, with `EnvelopeException` reduced to the shape decision **P** returns. */
    fun openAnswerOf(call: () -> OpenedMessage): OpenAnswer = try {
        OpenAnswer(true, call(), null, null, null)
    } catch (refused: dev.eryalabs.nenya.envelope.EnvelopeException) {
        OpenAnswer(false, null, refused.reason.name, "EnvelopeRejection", refused.message)
    }

    /** The whole of §7.1's read equality, including §7.2's attribution and §17's two verdicts. */
    fun assertSameOpen(crossed: JsOpenResult, expected: OpenAnswer, what: String) {
        assertEquals(expected.ok, crossed.ok, "$what: one side opened and the other refused")
        assertEquals(
            if (expected.ok) "opened" else "refused",
            crossed.kind,
            "$what: `kind` must discriminate the same way `ok` does",
        )
        if (expected.ok) {
            val opened = expected.opened ?: fail("$what: an opened answer with no message")
            assertEquals(opened.sealSignature.name, crossed.sealSignature, "$what: §7.1 step 6's verdict")
            assertEquals(opened.wrapSignature.name, crossed.wrapSignature, "$what: §7.1 step 4's verdict")
            assertEquals(opened.wrapId.toHex(), crossed.wrapIdHex, "$what: the recomputed wrap ids")
            assertEquals(
                opened.notPerformedHere.map { it.name }.toSet(),
                crossed.notPerformedHere.toSet(),
                "$what: §17's not-performed statement differs",
            )
            assertSameRumor(
                crossed.rumor ?: fail("$what: the crossed answer carries no rumor"),
                opened.rumor,
                what,
            )
            assertNull(crossed.reason, "$what: an opened answer must name no reason")
        } else {
            assertNull(crossed.rumor, "$what: a refusal must carry no rumor")
            assertNull(crossed.sealSignature, "$what: a refusal reports no verdict")
            assertNull(crossed.wrapSignature, "$what: a refusal reports no verdict")
            assertNull(crossed.wrapIdHex, "$what: a refusal reports no id")
            assertEquals(expected.reason, crossed.reason, "$what: the refusals differ")
            assertEquals(expected.vocabulary, crossed.reasonVocabulary, "$what: the vocabularies differ")
            assertEquals(expected.detail, crossed.detail, "$what: the details differ")
        }
    }

    /** Every value `JsRumor` publishes, against the same value off the Kotlin rumor. */
    fun assertSameRumor(crossed: JsRumor, expected: AttributedRumor, what: String) {
        JsBoundaryFixtures.assertSameFields(
            listOf(
                JsBoundaryFixtures.Field("author", crossed.author, expected.author),
                JsBoundaryFixtures.Field("idHex", crossed.idHex, expected.id.toHex()),
                JsBoundaryFixtures.Field(
                    "createdAtSeconds",
                    crossed.createdAtSeconds,
                    expected.createdAt.toString(),
                ),
                JsBoundaryFixtures.Field("content", crossed.content, expected.content),
                JsBoundaryFixtures.Field("kind", crossed.kind, expected.kind.name),
                JsBoundaryFixtures.Field("kindNumber", crossed.kindNumber, expected.kind.kind),
                JsBoundaryFixtures.Field("attribution", crossed.attribution, expected.attribution.name),
                JsBoundaryFixtures.Field(
                    "notPerformedHere",
                    crossed.notPerformedHere.toSet(),
                    expected.notPerformedHere.map { it.name }.toSet(),
                ),
                JsBoundaryFixtures.Field("nenyaVersion", crossed.nenyaVersion, expected.nenyaVersion),
                JsBoundaryFixtures.Field(
                    "counterparties",
                    crossed.counterparties.map { it.toList() },
                    expected.counterparties.map { listOfNotNull(it.pubkey, it.relay) },
                ),
                JsBoundaryFixtures.Field(
                    "unknownTags",
                    crossed.unknownTags.map { it.toList() },
                    expected.unknownTags,
                ),
                JsBoundaryFixtures.Field(
                    "messageType",
                    crossed.messageType,
                    (expected as? AttributedRumor.Bound)?.type?.name,
                ),
                JsBoundaryFixtures.Field(
                    "orderIdHex",
                    crossed.orderIdHex,
                    (expected as? AttributedRumor.Bound)?.order?.toHex(),
                ),
            ),
            "$what: the rumor",
        )
        JsBoundaryFixtures.assertSameEvent(crossed.event, expected.encode(), "$what: the rumor's event")
    }

    // -----------------------------------------------------------------------------------------
    // §9.2's answer.
    // -----------------------------------------------------------------------------------------

    /** What one of `Settlement`'s two doors answered, or the refusal, in one shape. */
    class SettlementAnswer(
        val ok: Boolean,
        val settlement: Settlement?,
        val reason: String?,
        val vocabulary: String?,
        val tag: String?,
        val detail: String?,
    )

    /**
     * [call]'s answer, with §9.2's two refusal types reduced to the shape decision **P** returns.
     *
     * Two types and not one, and that is §9.2's own design rather than an inconvenience: check 3's
     * `PaymentException` is deliberately **not** wrapped by `Settlement`, because §9 calls check 3
     * the load-bearing rule of the entire document and re-badging its refusal would put two names
     * on one failure. So the comparison has to carry which vocabulary answered, or the facade could
     * report a `SettlementRejection` where the library raised a `PaymentRejection` and pass.
     */
    fun settlementAnswerOf(call: () -> Settlement): SettlementAnswer = try {
        SettlementAnswer(true, call(), null, null, null, null)
    } catch (refused: dev.eryalabs.nenya.settlement.SettlementException) {
        SettlementAnswer(false, null, refused.reason.name, "SettlementRejection", refused.tag, refused.message)
    } catch (refused: dev.eryalabs.nenya.payment.PaymentException) {
        SettlementAnswer(false, null, refused.reason.name, "PaymentRejection", null, refused.message)
    }

    /** The whole of §9.2's equality, field by field. */
    fun assertSameSettlement(crossed: JsSettlementResult, expected: SettlementAnswer, what: String) {
        assertEquals(expected.ok, crossed.ok, "$what: one side answered and the other refused")
        assertEquals(
            if (expected.ok) "settlement" else "refused",
            crossed.kind,
            "$what: `kind` must discriminate the same way `ok` does",
        )
        if (expected.ok) {
            val settlement = expected.settlement ?: fail("$what: an answer with no settlement")
            val value = crossed.settlement ?: fail("$what: the crossed answer carries none")
            JsBoundaryFixtures.assertSameFields(
                listOf(
                    JsBoundaryFixtures.Field("orderIdHex", value.orderIdHex, settlement.order.toHex()),
                    JsBoundaryFixtures.Field("payee", value.payee, settlement.payee.token),
                    JsBoundaryFixtures.Field("medium", value.medium, settlement.medium.name),
                    JsBoundaryFixtures.Field(
                        "evidenced",
                        value.evidenced,
                        settlement is Settlement.Evidenced,
                    ),
                    JsBoundaryFixtures.Field(
                        "paymentHashHex",
                        value.paymentHashHex,
                        (settlement as? Settlement.Evidenced)?.payment?.paymentHash?.toHex(),
                    ),
                    JsBoundaryFixtures.Field(
                        "checksPerformed",
                        value.checksPerformed.toSet(),
                        settlement.checksPerformed.map { it.name }.toSet(),
                    ),
                    JsBoundaryFixtures.Field(
                        "checksNotPerformedHere",
                        value.checksNotPerformedHere.toSet(),
                        settlement.checksNotPerformedHere.map { it.name }.toSet(),
                    ),
                ),
                "$what: the settlement",
            )
            assertNull(crossed.reason, "$what: an answer must name no reason")
        } else {
            assertNull(crossed.settlement, "$what: a refusal must carry no settlement")
            assertEquals(expected.reason, crossed.reason, "$what: the refusals differ")
            assertEquals(expected.vocabulary, crossed.reasonVocabulary, "$what: the vocabularies differ")
            assertEquals(expected.tag, crossed.tag, "$what: the tags the refusals are about differ")
            assertEquals(expected.detail, crossed.detail, "$what: the details differ")
        }
    }

    // -----------------------------------------------------------------------------------------
    // §11.2's answer. The mutation T43 names is aimed here.
    // -----------------------------------------------------------------------------------------

    /**
     * Every value `JsOrder` publishes, against the same value off the Kotlin order.
     *
     * **This is where T43's named mutation lands.** Making `stepOrder` report `paid` from a refused
     * outcome moves `state` on the crossed side and not on this one, and `state` is the first field
     * compared. The expected side reads `order.state.name` off the value `OrderMachine.on` returned
     * and nothing else, so there is no shape of the facade that can satisfy it by agreeing with
     * itself.
     */
    fun orderFields(crossed: JsOrder, expected: Order): List<JsBoundaryFixtures.Field> = listOf(
        JsBoundaryFixtures.Field("idHex", crossed.idHex, expected.id.toHex()),
        JsBoundaryFixtures.Field("state", crossed.state, expected.state.name),
        JsBoundaryFixtures.Field("stateToken", crossed.stateToken, expected.state.token),
        JsBoundaryFixtures.Field("terminal", crossed.terminal, expected.state.isTerminal),
        JsBoundaryFixtures.Field("recognised", crossed.recognised, expected.state.isRecognised),
        JsBoundaryFixtures.Field(
            "priceMsat",
            crossed.priceMsat,
            expected.terms.split.price.millisatoshis.toString(),
        ),
        JsBoundaryFixtures.Field(
            "feeMsat",
            crossed.feeMsat,
            expected.terms.split.fee.millisatoshis.toString(),
        ),
        JsBoundaryFixtures.Field(
            "totalMsat",
            crossed.totalMsat,
            expected.terms.split.total.millisatoshis.toString(),
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
        JsBoundaryFixtures.Field(
            "expirationSeconds",
            crossed.expirationSeconds,
            expected.terms.expiration?.toString(),
        ),
        JsBoundaryFixtures.Field(
            "deliverBySeconds",
            crossed.deliverBySeconds,
            expected.terms.deliverBy?.toString(),
        ),
        JsBoundaryFixtures.Field("provider", crossed.provider, expected.provider),
        JsBoundaryFixtures.Field(
            "commitment",
            crossed.commitment?.let { listOf(it.xHex, it.oxHex, it.mimeType, it.sizeBytes) },
            expected.commitment?.let {
                listOf(it.x.toHex(), it.ox.toHex(), it.mimeType, it.sizeBytes.toString())
            },
        ),
        JsBoundaryFixtures.Field("paidAtSeconds", crossed.paidAtSeconds, expected.paidAt?.toString()),
        JsBoundaryFixtures.Field(
            "releasedAtSeconds",
            crossed.releasedAtSeconds,
            expected.releasedAt?.toString(),
        ),
        JsBoundaryFixtures.Field("disputeGround", crossed.disputeGround, expected.disputeGround?.name),
        JsBoundaryFixtures.Field(
            "paymentChecksPerformed",
            crossed.paymentChecksPerformed.toSet(),
            expected.paymentChecksPerformed.map { it.name }.toSet(),
        ),
        JsBoundaryFixtures.Field(
            "paymentChecksNotPerformedHere",
            crossed.paymentChecksNotPerformedHere.toSet(),
            expected.paymentChecksNotPerformedHere.map { it.name }.toSet(),
        ),
        JsBoundaryFixtures.Field(
            "deliveryChecksPerformed",
            crossed.deliveryChecksPerformed.toSet(),
            expected.deliveryChecksPerformed.map { it.name }.toSet(),
        ),
        JsBoundaryFixtures.Field(
            "deliveryChecksNotPerformedHere",
            crossed.deliveryChecksNotPerformedHere.toSet(),
            expected.deliveryChecksNotPerformedHere.map { it.name }.toSet(),
        ),
    )

    /** The whole of §11.2's equality: the outcome's discriminator, its reason and its order. */
    fun assertSameOutcome(crossed: JsOrderOutcome, expected: OrderOutcome, what: String) {
        val advanced = expected is OrderOutcome.Advanced
        assertEquals(advanced, crossed.ok, "$what: one side advanced and the other refused")
        assertEquals(
            if (advanced) "advanced" else "refused",
            crossed.kind,
            "$what: `kind` must discriminate the same way `ok` does",
        )
        val refused = expected as? OrderOutcome.Refused
        assertEquals(refused?.reason?.name, crossed.reason, "$what: the refusals differ")
        assertEquals(
            if (refused == null) null else "TransitionRejection",
            crossed.reasonVocabulary,
            "$what: a §11.2 refusal must report §11.2's vocabulary and no other",
        )
        assertEquals(refused?.detail, crossed.detail, "$what: the details differ")
        assertEquals(
            (expected as? OrderOutcome.Refused.ChecksNotPerformed)?.missing?.map { it.name }?.toSet()
                ?: emptySet(),
            crossed.missingChecks.toSet(),
            "$what: decision B's missing-check set differs, so one side names a check the other " +
                "says was performed",
        )
        JsBoundaryFixtures.assertSameFields(
            orderFields(crossed.order ?: fail("$what: the crossed outcome carries no order"), expected.order),
            "$what: the order after the event",
        )
    }

    /** §8.1's fee term as the nullable basis-point count, for an order's own terms. */
    fun basisPointsOf(fee: FeeTerm): Int? = JsBoundaryFixtures.basisPointsOf(fee)
}
