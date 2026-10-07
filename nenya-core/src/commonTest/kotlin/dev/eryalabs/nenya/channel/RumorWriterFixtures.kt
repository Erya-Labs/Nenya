package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.NenyaProtocol
import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliverableHash
import dev.eryalabs.nenya.delivery.DeliverableRelease
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.oracleSha256
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.settlement.Bolt11Reference
import dev.eryalabs.nenya.settlement.PaymentBuild
import dev.eryalabs.nenya.settlement.PaymentMedium
import dev.eryalabs.nenya.settlement.PaymentReceipt
import dev.eryalabs.nenya.settlement.PaymentRequest
import dev.eryalabs.nenya.settlement.PaymentWriter
import dev.eryalabs.nenya.settlement.Settlement
import dev.eryalabs.nenya.settlement.SettlementFixtures
import dev.eryalabs.nenya.tag.Coordinate
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.utf8Bytes
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * The generator behind the rumor writers' corpus, and the eight functions that turn a decoded
 * message back into the values that must rebuild it byte for byte.
 *
 * This is not a scratch file. The queue's Definition of done forbids an encoded value appearing in
 * a test as something somebody typed out, and nothing here is one: every pubkey and every order id
 * is a SHA-256 digest of a per-index label computed by the platform's own SHA-256 ([oracleSha256]),
 * every `x`/`ox` hash and every decryption key is the same, every event id is computed inside T8,
 * and every BOLT-11 string comes from the vendored official examples (decision **D**). A reviewer
 * can change [SEED], re-run the suite, and every property must still hold.
 *
 * ### Why it is its own generator rather than `ChannelFixtures`'
 *
 * `ChannelFixtures` generates **tag arrays**, because the thing under test there is a decoder, and
 * its corpus therefore carries shapes no writer may emit — an uppercase `order` id, a `type=4`, a
 * `nenya` version this build does not implement. The thing under test here is a writer, so the
 * corpus is the other side of the same boundary: the values a client would hand [RumorWriter]. The
 * generated pubkeys, the hostile string pieces and the checked-event door are reused rather than
 * copied.
 *
 * ### The three values a decode deliberately does not report, named once here
 *
 * The proof is **build → decode → rebuild → the same bytes**, and the rebuild is assembled from
 * what the decode reports — which is what makes it stronger than a self-comparison. Three values
 * are the exception, and each is an exception *because a rule says so* rather than because it was
 * inconvenient:
 *
 * 1. **A `kind:14`'s `order` tag.** §7.4: "an implementation MAY carry `order` on a `kind:14` and
 *    MUST NOT derive anything from its presence or absence." `AttributedRumor.Chat` therefore
 *    declares no order id at all — `ChannelStructureTest` asserts that by reflection — so there is
 *    no decoded value to rebuild from, by design. Reading it back out of the raw tag would be the
 *    test performing exactly the derivation §7.4 forbids.
 * 2. **A `kind:15`'s `decryption-key` and `decryption-nonce`.** §10.2's values are checked and
 *    **dropped**: §12 item 11 and STOP RULE 14 name decryption keys alongside key material, and
 *    `DeliverableReleaseMessage` holds neither after checking them. These two are read back from
 *    the attributed rumor's own preserved tags, which is the strongest source that exists — §4.3's
 *    verbatim-preservation rule is what carries them, and that is the rule under test for them.
 * 3. **A `kind:17`'s `amount_msat`.** §9.2 states no rule for it and no check reads it, so no
 *    decoded value reports it; and it cannot be read back off the tag either, because
 *    `PaymentWriter` derives it from `Settlement.expectedAmount`, which is not invertible for a
 *    `fee` payee. It comes from the authored case.
 *
 * Each is carried from the authored case in the rebuild, and `RumorWriterPropertyTest` asserts
 * positively that the tag it produces is present in the built event — so none of the three is a
 * value the round trip is silent about.
 *
 * ### What the corpus carries and why
 *
 * Everything that could make §4.1's bytes differ: every optional row of every message present and
 * absent, §4.1's three escaping rules inside the values the writer emits, §8.1's three `fee`
 * shapes, both `p` arities, extension tags §4.3 requires be preserved verbatim, and `subject` tags
 * — which §7.4 makes display metadata and which this writer deliberately has no parameter for, so
 * they ride as extensions.
 */
internal object RumorWriterFixtures {

    /**
     * Pinned so a failure is reproducible. [JdkRandom]'s algorithm is the JDK's specified one and
     * is identical on the JVM and JavaScript, so this file produces the same run wherever it is
     * re-run.
     */
    const val SEED: Long = 20261007L

    /** T9's fixed, non-round `created_at`. A writer that emitted a constant would still be visible. */
    const val CREATED_AT: Long = TagFixtures.CREATED_AT

    /**
     * The eight messages §7.4 lets Nenya v1 emit: four kinds, with the `kind:16` appearing once per
     * emittable `type`.
     *
     * `type=4` is absent and that is §7.4's rule rather than a gap — "Nenya v1 MUST NOT emit
     * `type=4`" — and `RumorWriterTest` asserts there is no entry point that produces one and that
     * `ChannelTags.type` refuses it. The enum is held equal to §7.4's own two tables, minus that
     * one row, by `RumorWriterTest`.
     */
    enum class Shape {
        CHAT,
        RELEASE,
        PROPOSAL,
        PAYMENT_REQUEST,
        ORDER_UPDATE,
        COMMITMENT,
        PRIVATE_BID,
        RECEIPT,
    }

    /** Tag names no writer here spells, so §4.3's unknown-tag rule applies to them. */
    private val EXTRA_NAMES: List<String> = listOf(
        // §7.4's display-only MAY, which must round-trip and must yield no term. It is an extension
        // here because `RumorWriter` has no parameter for it, which is §7.4's rule made structural.
        "subject",
        "client", "zap", "relays", "r", "emoji", "x-nenya-experiment",
    )

    /**
     * The pieces values are assembled from: §4.1's three escaping rules, exercised.
     *
     * The seven characters §4.1 gives a shortcut escape, two that need rule 2's `\u00XX` form, `/`
     * and `0x7f` which rule 3 emits verbatim, and two multi-byte characters including one outside
     * the BMP. The id the round trip compares is computed over those bytes.
     */
    private val HOSTILE_PIECES: List<String> = buildList {
        add("")
        for (code in listOf(0x22, 0x5C, 0x08, 0x0C, 0x0A, 0x0D, 0x09, 0x00, 0x1F, 0x2F, 0x7F)) {
            add(code.toChar().toString())
        }
        add("é")
        add("中")
        add(TestText.codePoint(0x1F600))
        add("ordinary")
    }

    /**
     * One generated case: the message to build, the §7.4 envelope to build it in, and the terms
     * that message carries.
     *
     * Every field is nullable or defaulted except the two every message needs, because the eight
     * shapes genuinely carry different things — a chat carries none of them and a commitment
     * carries four nothing else does.
     */
    class Case(
        val shape: Shape,
        val index: Int,
        val envelope: RumorEnvelope,
        val order: OrderId?,
        val item: ItemRef? = null,
        val terms: OrderTerms? = null,
        val feeRecipient: String? = null,
        val amountSat: Long? = null,
        val status: OrderState? = null,
        val commitment: DeliverableCommitment? = null,
        val url: String? = null,
        val release: DeliverableRelease? = null,
        val decryptionKey: String? = null,
        val decryptionNonce: String? = null,
        val payee: Payee? = null,
        val invoice: Bolt11Reference? = null,
        val reference: String? = null,
        val proof: String? = null,
        val medium: PaymentMedium = PaymentMedium.LIGHTNING,
    ) {

        /** Names the shape and the index, and no value at all (§12 items 1, 2 and 11). */
        override fun toString(): String = "Case(${shape.name} #$index)"
    }

    /** [count] cases from one seeded run, cycling the eight shapes. */
    fun cases(count: Int): List<Case> {
        val random = JdkRandom(SEED)
        return List(count) { index ->
            authored(random, index, Shape.entries[index % Shape.entries.size])
        }
    }

    /** [case] built through the entry point §7.4 gives its shape. */
    fun build(case: Case): WireEvent = when (case.shape) {
        Shape.CHAT -> built(RumorWriter.chat(case.envelope, case.order), case)
        Shape.RELEASE -> built(
            RumorWriter.release(
                case.envelope,
                requireOrder(case),
                assertNotNull(case.release, "$case must carry a release"),
                assertNotNull(case.decryptionKey, "$case must carry §10.2's key"),
                assertNotNull(case.decryptionNonce, "$case must carry §10.2's nonce"),
            ),
            case,
        )
        Shape.PROPOSAL -> built(
            RumorWriter.proposal(
                case.envelope,
                requireOrder(case),
                assertNotNull(case.item, "$case must carry §7.5's item"),
                assertNotNull(case.terms, "$case must carry terms"),
                case.feeRecipient,
                case.amountSat,
            ),
            case,
        )
        Shape.ORDER_UPDATE -> built(
            RumorWriter.statusUpdate(
                case.envelope,
                requireOrder(case),
                assertNotNull(case.status, "$case must carry §11.1's status"),
                case.item,
                case.terms,
                case.feeRecipient,
            ),
            case,
        )
        Shape.COMMITMENT -> built(
            RumorWriter.commitment(
                case.envelope,
                requireOrder(case),
                assertNotNull(case.commitment, "$case must carry §10.1's four values"),
                assertNotNull(case.url, "$case must carry §10.1's url"),
            ),
            case,
        )
        Shape.PRIVATE_BID -> built(
            RumorWriter.privateBid(
                case.envelope,
                assertNotNull(case.item, "$case must carry §6.1's item"),
                assertNotNull(case.terms, "$case must carry §6.1's terms"),
                case.feeRecipient,
            ),
            case,
        )
        Shape.PAYMENT_REQUEST -> paid(
            PaymentWriter.paymentRequest(
                case.envelope,
                requireOrder(case),
                assertNotNull(case.payee, "$case must name §8.6's payee"),
                assertNotNull(case.invoice, "$case must carry §8.6's invoice"),
                case.feeRecipient,
                case.medium,
                // §8.4 point 3 makes the `fee` pair REQUIRED on a `payee=fee` request, so the
                // terms travel with the case rather than being optional on this side.
                case.terms,
            ),
            case,
        )
        Shape.RECEIPT -> paid(
            PaymentWriter.receipt(
                case.envelope,
                requireOrder(case),
                assertNotNull(case.payee, "$case must name §8.6's payee"),
                assertNotNull(case.reference, "$case must carry §9.2's reference"),
                assertNotNull(case.proof, "$case must carry §9.2's proof"),
                case.feeRecipient,
                case.medium,
                case.terms,
            ),
            case,
        )
    }

    /**
     * The event [build] produced, or a loud failure naming the refusal.
     *
     * Failing here rather than asserting a type keeps every positive test's message useful: a
     * corpus entry the writer refused prints the tag and the reason, not "expected Built".
     */
    fun built(build: RumorBuild, what: Any): WireEvent = when (build) {
        is RumorBuild.Built -> build.event
        is RumorBuild.Refused ->
            fail("$what was refused as ${build.reason} on `${build.tag}`: ${build.detail}")
    }

    /** The refusal [build] answered, or a loud failure saying it was accepted. */
    fun refused(build: RumorBuild, what: Any): RumorBuild.Refused = when (build) {
        is RumorBuild.Refused -> build
        is RumorBuild.Built -> fail("$what was accepted; it emitted ${build.event.tags.size} tag(s)")
    }

    /** The §8.6 or §9.2 event the settlement writer produced, or a loud failure. */
    fun paid(build: PaymentBuild, what: Any): WireEvent = when (build) {
        is PaymentBuild.Built -> build.event
        is PaymentBuild.Refused -> fail(
            "$what was refused as ${build.reason ?: build.channelReason} on `${build.tag}`: " +
                build.detail,
        )
    }

    /** The §8.6 or §9.2 refusal, or a loud failure saying the message was accepted. */
    fun paidRefusal(build: PaymentBuild, what: Any): PaymentBuild.Refused = when (build) {
        is PaymentBuild.Refused -> build
        is PaymentBuild.Built -> fail("$what was accepted; it emitted ${build.event.tags.size} tag(s)")
    }

    /** [event] as a T8 [CheckedEvent] — the only door into §7.2's attribution (§4.1). */
    fun checked(event: WireEvent): CheckedEvent =
        CheckedEvent.checkEventId(EventId.of(event).toHex(), event)

    /**
     * [event] attributed to the key that authored it — §7.2's positive case.
     *
     * The seal pubkey is the event's own `pubkey`, which is what §7.2 compares: a writer's output
     * is a rumor the app is about to seal with the key it put in the `pubkey` field, so a corpus
     * that sealed it with any other would be testing §7.2's refusal rather than the round trip.
     */
    fun attribute(event: WireEvent): AttributedRumor =
        AttributedRumor.attribute(event.pubkey, checked(event))

    /** [event] attributed, as the [AttributedRumor.Bound] every kind but `kind:14` decodes to. */
    fun bound(event: WireEvent): AttributedRumor.Bound = when (val rumor = attribute(event)) {
        is AttributedRumor.Bound -> rumor
        else -> fail("a kind:${event.kind} must attribute to a bound rumor; got $rumor")
    }

    /** Asserts [left] and [right] are the same bytes on the wire, and says what differed if not. */
    fun assertSameBytes(left: WireEvent, right: WireEvent, what: Any) {
        // The tag lists first, because a difference there is readable and the serialisation is not.
        assertEquals(left.tags, right.tags, "$what: the tag arrays differ")
        assertEquals(
            left.canonicalSerialisation(),
            right.canonicalSerialisation(),
            "$what: §4.1's canonical serialisation differs, so the two events have different ids",
        )
    }

    /**
     * The §7.4 envelope that must rebuild [rumor] — assembled **only** from what the attribution
     * reports, which is the half of the round trip that proves the decoder reports enough.
     *
     * [AttributedRumor.author] rather than the event's own `pubkey`: the attribution normalises to
     * §4.3's lowercase and so does the writer, so the two agree — and taking the normalised one is
     * what proves it.
     *
     * ### Why [spelled] has to be subtracted, and what that costs
     *
     * [AttributedRumor.unknownTags] means "not a row in §5.3's table", not "not decoded", and its
     * own KDoc says so: §7.4's `order` and `type` are in there, and so are §7.5's `amount_msat`,
     * §8.6's `payee` and `payment` and every one of §10's tags, because §5.3's table is the
     * *listing* vocabulary. Handing all of those back as `extraTags` would ask the writer to emit a
     * second copy of each — which it correctly refuses as §4.3's duplicate — so the reconstruction
     * drops exactly the names the message's own emission order spells, and takes their values from
     * the decoded message instead.
     *
     * That is the same step `ListingWriterFixtures.reauthored` takes when it drops the two `t`
     * tokens the listing writer emits itself, and it carries the same cost, which T40 measured
     * rather than assumed: **what the reconstruction drops, the round trip cannot police.** If a
     * writer emitted its rows in the wrong order the decode would report them in that order, the
     * drop would remove the same names, and the rebuild would reproduce the wrong order byte for
     * byte. So every emission order is asserted **positively** in `RumorWriterTest`, against the
     * worked examples parsed out of `spec/NENYA-1.md` — where one side of the comparison is the
     * document rather than this writer.
     *
     * @param spelled the message's emission order: every name the writer spells from a parameter.
     */
    fun envelopeOf(rumor: AttributedRumor, spelled: List<String>): RumorEnvelope = RumorEnvelope(
        authorPubkey = rumor.author,
        createdAt = rumor.createdAt,
        counterparties = rumor.counterparties,
        content = rumor.content,
        nenyaVersion = assertNotNull(
            rumor.nenyaVersion,
            "every message this writer emits carries §4.5's version; a chat's is optional in §7.4 " +
                "and this writer always emits one",
        ),
        // §7.4's `subject` and every other genuine extension comes back here, verbatim and in
        // order, which is §4.3's rule and the thing the byte comparison proves about them.
        extraTags = rumor.unknownTags.filter { it[0] !in spelled },
    )

    /**
     * The names the writer for [shape] spells from a parameter — its emission order, plus the two
     * §9.2 and §8.6 rows `PaymentWriter` adds.
     *
     * Read off the writers' own published orders rather than listed here, so a tag added to one of
     * them joins the drop without anybody editing this function. A name that was *not* dropped
     * would turn the round trip red as §4.3's duplicate, which is the fail-loud direction.
     */
    fun spelledBy(shape: Shape): List<String> = when (shape) {
        Shape.CHAT -> RumorWriter.CHAT_TAG_ORDER
        Shape.RELEASE -> RumorWriter.RELEASE_TAG_ORDER
        Shape.PROPOSAL -> RumorWriter.PROPOSAL_TAG_ORDER + ChannelVocabulary.AMOUNT
        Shape.ORDER_UPDATE -> RumorWriter.ORDER_UPDATE_TAG_ORDER
        Shape.COMMITMENT -> RumorWriter.COMMITMENT_TAG_ORDER
        Shape.PRIVATE_BID -> RumorWriter.PRIVATE_BID_TAG_ORDER
        Shape.PAYMENT_REQUEST -> PaymentWriter.REQUEST_TAG_ORDER
        Shape.RECEIPT -> PaymentWriter.RECEIPT_TAG_ORDER
    }

    /**
     * The case rebuilt from what its own decoder reported — the second half of the round trip.
     *
     * One branch per shape, each going through the decoder §7.4 gives that message and then back
     * through the same entry point. The three values no decode reports are taken from [case] and
     * are named in this object's note; every other value here comes off the decoded message or off
     * the attributed rumor it was decoded from.
     *
     * @param split §8.3's arithmetic for the order, which `PaymentRequest.decode` requires and
     *   which is not a value any message carries: §8.6 says a payee whose expected amount is `0`
     *   has no request at all, so there is no version of that decode that can run without the
     *   terms.
     */
    fun rebuild(case: Case, event: WireEvent): WireEvent = when (case.shape) {
        Shape.CHAT -> {
            val rumor = attribute(event)
            // §7.4 forbids deriving anything from a kind:14's `order` tag, so `AttributedRumor.Chat`
            // reports none and this is the one value carried from the case. See this object's note.
            build(
                Case(case.shape, case.index, envelopeOf(rumor, spelledBy(case.shape)), case.order),
            )
        }
        Shape.RELEASE -> {
            val rumor = bound(event)
            val message = DeliverableReleaseMessage.decode(rumor)
            build(
                Case(
                    case.shape,
                    case.index,
                    envelopeOf(rumor, spelledBy(case.shape)),
                    message.order,
                    release = message.release,
                    // §10.2's two values are checked and dropped by the decoder (§12 item 11), so
                    // they come back off §4.3's preserved tags — which is the rule under test here.
                    decryptionKey = tagValue(rumor, DeliveryTags.DECRYPTION_KEY),
                    decryptionNonce = tagValue(rumor, DeliveryTags.DECRYPTION_NONCE),
                ),
            )
        }
        Shape.PROPOSAL -> {
            val rumor = bound(event)
            val message = OrderProposal.decode(rumor)
            build(
                Case(
                    case.shape,
                    case.index,
                    envelopeOf(rumor, spelledBy(case.shape)),
                    message.order,
                    item = message.item,
                    terms = message.terms,
                    feeRecipient = message.feeRecipient,
                    // §7.5's GammaMarkets MAY, which `OrderProposal` does not publish because it is
                    // read only to be cross-checked and never as the price. Off the preserved tag.
                    amountSat = tagValue(rumor, ChannelVocabulary.AMOUNT)?.toLong(),
                ),
            )
        }
        Shape.ORDER_UPDATE -> {
            val rumor = bound(event)
            val message = OrderStatusMessage.decode(rumor)
            build(
                Case(
                    case.shape,
                    case.index,
                    envelopeOf(rumor, spelledBy(case.shape)),
                    message.order,
                    item = rumor.tags.item,
                    terms = termsOf(rumor),
                    feeRecipient = rumor.tags.feeRecipient,
                    status = message.status,
                ),
            )
        }
        Shape.COMMITMENT -> {
            val rumor = bound(event)
            val message = DeliveryCommitmentMessage.decode(rumor)
            build(
                Case(
                    case.shape,
                    case.index,
                    envelopeOf(rumor, spelledBy(case.shape)),
                    message.order,
                    commitment = message.commitment,
                    url = message.url,
                ),
            )
        }
        Shape.PRIVATE_BID -> {
            val rumor = bound(event)
            // §7.4 gives a `type=6` no decoder of its own — it advances no state, so there is
            // nothing for one to produce — and `AttributedRumor.attribute` IS the decoder that
            // reads it: §7.4's envelope, §7.4's `order` prohibition, §7.4's version-collision rule
            // and §5.3's Encoding column over `item`, `price` and `fee` all run there.
            assertEquals(
                OrderMessageKind.PRIVATE_BID,
                rumor.type,
                "${case}: §6.1's bid is a kind:16 type=6",
            )
            assertEquals(null, rumor.order, "§7.4: a type=6 MUST NOT carry an `order` tag")
            build(
                Case(
                    case.shape,
                    case.index,
                    envelopeOf(rumor, spelledBy(case.shape)),
                    null,
                    item = rumor.tags.item,
                    terms = bidTermsOf(rumor),
                    feeRecipient = rumor.tags.feeRecipient,
                ),
            )
        }
        Shape.PAYMENT_REQUEST -> {
            val rumor = bound(event)
            val message = PaymentRequest.decode(
                rumor,
                assertNotNull(case.terms, "$case must carry terms").split,
            )
            // §8.4's pair really is reported — as `rumor.tags.fee` and `rumor.tags.feeRecipient` —
            // so the one thing the rebuild cannot take from the decode is the `OrderTerms` the pair
            // sits inside: §8.6 puts no price on a request at all, and `OrderTerms` holds one. The
            // pair itself is asserted to have survived, which is §8.4's property over these bytes.
            assertEquals(
                // §8.1's read rule: no `fee` tag at all *is* `FeeTerm.Absent`, so a case that
                // stated no terms and a case that stated an absent term agree here — which is the
                // same reading `OrderProposal.decode` relies on one package over.
                case.terms?.split?.term ?: FeeTerm.Absent,
                rumor.tags.fee,
                "${case}: §8.4's `(bps, recipient)` pair must survive the round trip",
            )
            assertEquals(case.feeRecipient, rumor.tags.feeRecipient, "${case}: §8.4's recipient")
            build(
                Case(
                    case.shape,
                    case.index,
                    envelopeOf(rumor, spelledBy(case.shape)),
                    message.order,
                    terms = case.terms,
                    payee = message.payee,
                    invoice = message.invoice,
                    // §8.4's recipient off the `fee` tag, which the tag codec reports — and **not**
                    // `PaymentRequest.feeRecipient`, which is §8.6's `payee` third element and is
                    // `null` on a provider request. The two are the same value where both exist and
                    // taking the wrong one loses §8.4's optional pair on every provider message.
                    feeRecipient = rumor.tags.feeRecipient,
                ),
            )
        }
        Shape.RECEIPT -> {
            val rumor = bound(event)
            val message = PaymentReceipt.decode(rumor)
            val payment = rumor.tags.occurrences(SETTLEMENT_PAYMENT).single()
            assertEquals(
                message.reference,
                payment[REFERENCE_INDEX],
                "${case}: the decoder's reference is the tag's third element",
            )
            // §8.4 point 4's pair, which is REQUIRED on a `payee=fee` receipt and OPTIONAL on a
            // `payee=provider` one — and is reported by the tag codec either way.
            assertEquals(
                // §8.1's read rule: no `fee` tag at all *is* `FeeTerm.Absent`, so a case that
                // stated no terms and a case that stated an absent term agree here — which is the
                // same reading `OrderProposal.decode` relies on one package over.
                case.terms?.split?.term ?: FeeTerm.Absent,
                rumor.tags.fee,
                "${case}: §8.4's `(bps, recipient)` pair must survive the round trip",
            )
            build(
                Case(
                    case.shape,
                    case.index,
                    envelopeOf(rumor, spelledBy(case.shape)),
                    message.order,
                    payee = message.payee,
                    // §8.4's recipient off the `fee` tag, as on the request; see that branch.
                    feeRecipient = rumor.tags.feeRecipient,
                    reference = message.reference,
                    // §9.2's proof as the tag carried it. A `Preimage` publishes no hex form on
                    // purpose (§12 item 11, STOP RULE 14), so there is no decoded value to take it
                    // from and §4.3's preserved tag is the honest source.
                    proof = payment[PROOF_INDEX],
                    medium = message.medium,
                    // §9.2 states no rule for `amount_msat` and no check reads it, so no decode
                    // reports it. See this object's note.
                    terms = case.terms,
                ),
            )
        }
    }

    /** The one value of [name]'s single occurrence on [rumor], or `null`. §4.3's preserved tag. */
    fun tagValue(rumor: AttributedRumor, name: String): String? =
        rumor.tags.occurrences(name).singleOrNull()?.getOrNull(1)

    /** §7.6's four terms as T9 and §7.5 decoded them, for a `type=3` that repeats them. */
    private fun termsOf(rumor: AttributedRumor.Bound): OrderTerms? {
        val amount = tagValue(rumor, ChannelVocabulary.AMOUNT_MSAT) ?: return null
        return OrderTerms.of(
            Msat.ofMsat(amount.toLong()),
            rumor.tags.fee,
            rumor.tags.expiration,
            rumor.tags.deliverBy,
        )
    }

    /** §6.1's four terms, whose price is §5.3's satoshi-denominated `price` row. */
    private fun bidTermsOf(rumor: AttributedRumor.Bound): OrderTerms = OrderTerms.of(
        assertNotNull(rumor.tags.price, "§6.1 gives a private bid a `price` row"),
        rumor.tags.fee,
        rumor.tags.expiration,
        rumor.tags.deliverBy,
    )

    private fun requireOrder(case: Case): OrderId =
        assertNotNull(case.order, "$case must carry §7.4's order id")

    /** A generated 32-byte order id, unique per [index] and never typed. */
    fun orderFor(index: Int): OrderId =
        OrderId.ofHex(TagFixtures.lowerHex(oracleSha256("nenya-t41-order-$index".utf8Bytes())))

    /** A generated 64-character lowercase-hex pubkey, unique per [index] and never typed. */
    fun pubkeyFor(index: Int): String = TagFixtures.pubkeyFor(index)

    /** A generated 32-byte hash for §10's `x` and `ox`, computed and never typed. */
    fun hashFor(label: String): DeliverableHash =
        DeliverableHash.ofHex(TagFixtures.lowerHex(oracleSha256(label.utf8Bytes())))

    /**
     * §10.2's 256-bit key as 64 lowercase hex characters — a digest, not key material anybody
     * chose, and never a value typed into this file.
     */
    fun keyFor(index: Int): String =
        TagFixtures.lowerHex(oracleSha256("nenya-t41-key-$index".utf8Bytes()))

    /** §10.2's 96-bit nonce as 24 lowercase hex characters — the first 12 bytes of a digest. */
    fun nonceFor(index: Int): String = keyFor(index + NONCE_OFFSET).substring(0, NONCE_HEX)

    /** §9.2's 32-byte preimage as 64 lowercase hex characters. Computed, never typed. */
    fun proofFor(index: Int): String =
        TagFixtures.lowerHex(oracleSha256("nenya-t41-preimage-$index".utf8Bytes()))

    /**
     * §8.6's `<bolt11>` for one case: a real invoice for [amount], whose `p` field is the SHA-256
     * of [proofFor]'s preimage.
     *
     * Decision **D**: every invoice in a test comes from the vendored official examples or is
     * derived from one by a composer proven to rebuild every example exactly.
     * `SettlementFixtures.invoice` is that derivation, and it is used here rather than a raw
     * example because the §8.6 proof runs the built request through
     * `AcceptedPaymentRequest.accept` — which applies §9.2 checks 4 and 5 to the request, so the
     * invoice has to be **for the right amount and unexpired** or the proof would be about the
     * store refusing a fixture rather than about the builder.
     */
    fun invoiceFor(index: Int, amount: Msat): Bolt11Reference =
        Bolt11Reference.recognise(SettlementFixtures.invoice(proofFor(index), amount))

    // ---------------------------------------------------------------------------------------------
    // Generation.
    // ---------------------------------------------------------------------------------------------

    private fun authored(random: JdkRandom, index: Int, shape: Shape): Case {
        val envelope = RumorEnvelope(
            authorPubkey = pubkeyFor(index),
            createdAt = CREATED_AT + random.nextInt(99_999),
            // §7.4 marks `p` MUST on every kind but chat, and §5.3 gives it cardinality `0–n`, so
            // the corpus draws one and two. A chat draws zero as well, because §7.4 puts it outside
            // the rule and the writer must not invent one.
            counterparties = List(if (shape == Shape.CHAT) random.nextInt(3) else 1 + random.nextInt(2)) {
                pubkeyRef(random, index, it)
            },
            content = hostile(random, 4),
            nenyaVersion = NenyaProtocol.VERSION,
            extraTags = List(random.nextInt(4)) { extraTag(random, index, it) },
        )
        val fee = feeTerm(random)
        val feeRecipient = if (fee.namesRecipient) pubkeyFor(index + FEE_RECIPIENT_OFFSET) else null
        return when (shape) {
            Shape.CHAT -> Case(
                shape,
                index,
                envelope,
                // §7.4's MAY, both halves, because "MUST NOT derive anything from its presence or
                // absence" is only tested if both are in the corpus.
                order = if (random.nextBoolean()) orderFor(index) else null,
            )
            Shape.RELEASE -> Case(
                shape,
                index,
                envelope,
                orderFor(index),
                release = DeliverableRelease(
                    hashFor("served-$index"),
                    hashFor("plaintext-$index"),
                    // §10.3 makes an absent `file-type` reach the identity check rather than the
                    // decoder, so the corpus draws it absent too.
                    if (random.nextBoolean()) "video/mp4" else null,
                    random.nextInt(99_999_999).toLong(),
                ),
                decryptionKey = keyFor(index),
                decryptionNonce = nonceFor(index),
            )
            Shape.PROPOSAL -> {
                val terms = orderTerms(random, fee)
                Case(
                    shape,
                    index,
                    envelope,
                    orderFor(index),
                    item = itemRef(index),
                    terms = terms,
                    feeRecipient = feeRecipient,
                    // §7.5's compatibility tag, drawn present and absent. When present it MUST
                    // agree with `amount_msat`, so it is derived from the same price rather than
                    // drawn — a disagreeing pair is its own negative control in `RumorWriterTest`.
                    amountSat = if (random.nextBoolean()) terms.split.price.toSatoshis() else null,
                )
            }
            Shape.ORDER_UPDATE -> {
                val repeats = random.nextBoolean()
                Case(
                    shape,
                    index,
                    envelope,
                    orderFor(index),
                    // §7.6: an update repeating no terms is a counter-proposal rather than a
                    // malformed message, so both halves are in the corpus.
                    item = if (repeats) itemRef(index) else null,
                    terms = if (repeats) orderTerms(random, fee) else null,
                    feeRecipient = if (repeats) feeRecipient else null,
                    status = emittableState(random),
                )
            }
            Shape.COMMITMENT -> Case(
                shape,
                index,
                envelope,
                orderFor(index),
                commitment = DeliverableCommitment(
                    hashFor("served-$index"),
                    hashFor("plaintext-$index"),
                    if (random.nextBoolean()) "video/mp4" else "application/zip",
                    random.nextInt(99_999_999).toLong(),
                ),
                url = "https://blob.example.invalid/$index-${random.nextInt(9999)}",
            )
            Shape.PRIVATE_BID -> Case(
                shape,
                index,
                envelope,
                order = null,
                item = itemRef(index),
                // §6.1's price rides as §5.3's satoshi-denominated `price` row, so the amount must
                // land on a whole satoshi: `TagWriter.price` refuses a part-satoshi rather than
                // rounding it, which is §4.4's rule for that row.
                terms = orderTerms(random, fee),
                feeRecipient = feeRecipient,
            )
            Shape.PAYMENT_REQUEST -> {
                // §8.6: a payee whose expected amount is `0` has no request at all, so the terms
                // decide which payees the corpus may draw. `Payee.requiredPayees` owns that rule.
                val terms = orderTerms(random, fee)
                val required = Payee.requiredPayees(terms.split).toList()
                val payee = required[random.nextInt(required.size)]
                Case(
                    shape,
                    index,
                    envelope,
                    orderFor(index),
                    terms = terms,
                    // The **order's** fee recipient, which §8.4's pair names at every point and
                    // §8.6's `payee` tag names only on a `payee=fee` message. A provider request
                    // therefore draws §8.4's optional pair whenever the order has a fee term, which
                    // is the half of §8.4 a corpus that passed `null` here would never reach.
                    feeRecipient = feeRecipient,
                    payee = payee,
                    // §8.6: the request MUST be for exactly what this payee is owed, and
                    // `AcceptedPaymentRequest.accept` applies §9.2 check 4 to it — so the invoice
                    // is written for the expected amount rather than for an arbitrary one.
                    invoice = invoiceFor(index, Settlement.expectedAmount(payee, terms.split)),
                )
            }
            Shape.RECEIPT -> {
                val terms = orderTerms(random, fee)
                val required = Payee.requiredPayees(terms.split).toList()
                val payee = required[random.nextInt(required.size)]
                // §9.4's three rails: `lightning`, whose proof is check 2's preimage, and the two
                // for which v1 defines no rule at all and whose proof is therefore not a preimage.
                val medium = RECEIPT_MEDIA[random.nextInt(RECEIPT_MEDIA.size)]
                // §9.2 prints `amount_msat` on a receipt; drawn present and absent, because §9.2
                // states no rule for it and a receipt carrying none must round-trip too. A
                // `payee=fee` receipt always carries them: §8.4 point 4 makes the `fee` pair
                // REQUIRED there, so the absent case belongs to the provider side alone.
                val receiptTerms = if (payee == Payee.FEE || random.nextBoolean()) terms else null
                Case(
                    shape,
                    index,
                    envelope,
                    orderFor(index),
                    terms = receiptTerms,
                    // The order's fee recipient — see the request branch above on why it is not
                    // conditioned on the payee — and `null` where the receipt states no terms at
                    // all, because §8.1 makes the recipient a part of the term and half a pair is
                    // refused rather than emitted.
                    feeRecipient = if (receiptTerms == null) null else feeRecipient,
                    payee = payee,
                    reference = if (medium == PaymentMedium.LIGHTNING) {
                        invoiceFor(index, Settlement.expectedAmount(payee, terms.split)).text
                    } else {
                        "address-or-mint-$index"
                    },
                    proof = if (medium == PaymentMedium.LIGHTNING) proofFor(index)
                    else "an-unverifiable-proof-$index",
                    medium = medium,
                )
            }
        }
    }

    /**
     * §7.5's two deadlines and §8.3's split, with `expiration` **strictly** before `deliver_by` —
     * which `OrderTerms`'s own `init` requires and §7.5 states.
     *
     * Both deadlines drawn present and absent independently, because the four combinations are four
     * different tag sets and the round trip is about bytes.
     */
    private fun orderTerms(random: JdkRandom, fee: FeeTerm): OrderTerms {
        val expiration = draw(random) { CREATED_AT + 1 + random.nextInt(9_999) }
        val deliverBy = draw(random) { (expiration ?: CREATED_AT) + 1 + random.nextInt(99_999) }
        return OrderTerms.of(
            // Whole satoshis, because §6.1's bid carries §5.3's satoshi-denominated `price` row and
            // `TagWriter.price` refuses a part-satoshi rather than rounding it. A proposal's
            // `amount_msat` would carry any amount; one generator for both keeps the corpus one
            // thing, and the part-satoshi case is its own negative control.
            Msat.ofSat(1L + random.nextInt(1_000_000)),
            fee,
            expiration,
            deliverBy,
        )
    }

    /**
     * §8.1's three cases: no term at all, a stated zero, and a term above zero with a recipient.
     *
     * The stated zero is drawn deliberately rather than left to chance, because it is the one §8.1
     * calls out — "the absence of a fee is itself a signed statement" — and the writer emits a
     * different number of tags for it than for [FeeTerm.Absent].
     */
    private fun feeTerm(random: JdkRandom): FeeTerm = when (random.nextInt(3)) {
        0 -> FeeTerm.Absent
        1 -> FeeTerm.of(0)
        else -> FeeTerm.of(1 + random.nextInt(FeeTerm.MAX_BASIS_POINTS))
    }

    /**
     * A §11.1 state this writer may emit: every constant with a token, which is every one but
     * [OrderState.UNKNOWN].
     *
     * Derived rather than listed, so a state added to §11.1 joins the corpus without anybody
     * editing a list here. `UNKNOWN` is excluded because §11.1 makes it the treatment of an
     * unrecognised token rather than a token — and that refusal is its own negative control.
     */
    private fun emittableState(random: JdkRandom): OrderState {
        val emittable = OrderState.entries.filter { it.token != null }
        return emittable[random.nextInt(emittable.size)]
    }

    /** Both `p` arities: §5.3's optional relay hint present and absent. */
    private fun pubkeyRef(random: JdkRandom, index: Int, position: Int): PubkeyRef {
        val pubkey = pubkeyFor(index + position + COUNTERPARTY_OFFSET)
        return if (random.nextBoolean()) PubkeyRef(pubkey, "wss://relay.example.invalid")
        else PubkeyRef(pubkey)
    }

    /** §5.3's `item`: a coordinate and the quantity `"1"`, which `ItemRef` refuses any other value of. */
    private fun itemRef(index: Int): ItemRef =
        ItemRef(Coordinate.parse(TagFixtures.coordinate(index = index + ITEM_OFFSET)))

    /** A tag no writer here names, of one to three elements, which §4.3 requires be preserved. */
    private fun extraTag(random: JdkRandom, index: Int, position: Int): List<String> {
        val name = EXTRA_NAMES[random.nextInt(EXTRA_NAMES.size)]
        return buildList {
            add(name)
            repeat(random.nextInt(3)) { add("$index-$position-${hostile(random, 2)}") }
        }
    }

    /** `null` half the time, so every optional row is drawn present and absent across the corpus. */
    private fun <T> draw(random: JdkRandom, value: () -> T): T? =
        if (random.nextBoolean()) value() else null

    private fun hostile(random: JdkRandom, maxPieces: Int): String {
        val out = StringBuilder()
        repeat(random.nextInt(maxPieces + 1)) {
            out.append(HOSTILE_PIECES[random.nextInt(HOSTILE_PIECES.size)])
        }
        return out.toString()
    }

    /** §9.4's three rails, so the corpus reaches the proof rule and the two rails that have none. */
    private val RECEIPT_MEDIA: List<PaymentMedium> =
        listOf(PaymentMedium.LIGHTNING, PaymentMedium.BITCOIN, PaymentMedium.ECASH)

    /** §8.6's `payment` tag name, read through the test's own copy of §9.2's indices. */
    private const val SETTLEMENT_PAYMENT: String = "payment"

    /** §9.2's `<medium-reference>` sits third. */
    private const val REFERENCE_INDEX: Int = 2

    /** §9.2's `<proof>` sits fourth. */
    private const val PROOF_INDEX: Int = 3

    /** §10.2's nonce is 12 bytes, so 24 hex characters. */
    private const val NONCE_HEX: Int = 24

    /** Keeps a generated nonce from being a prefix of the key beside it. */
    private const val NONCE_OFFSET: Int = 101

    /** Keeps the generated counterparty distinct from the generated author at the same index. */
    private const val COUNTERPARTY_OFFSET: Int = 1

    /** Keeps a generated fee recipient distinct from both parties. */
    private const val FEE_RECIPIENT_OFFSET: Int = 7

    /** Keeps the `item` coordinate's author distinct from both parties. */
    private const val ITEM_OFFSET: Int = 11
}
