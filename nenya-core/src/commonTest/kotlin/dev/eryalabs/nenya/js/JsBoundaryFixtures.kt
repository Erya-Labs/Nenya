package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.channel.RumorBuild
import dev.eryalabs.nenya.channel.RumorEnvelope
import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliverableRelease
import dev.eryalabs.nenya.listing.AuthoredListing
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingBuild
import dev.eryalabs.nenya.listing.ListingException
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.order.OrderStateException
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.settlement.PaymentBuild
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.EventJson
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The other side of the JavaScript boundary, written here so the equality proof has two independent
 * translations to compare.
 *
 * ### Why the projections are re-written rather than reused
 *
 * T42's proof is that "the facade's answer equals the underlying library call's answer for the same
 * input". A test that fed the *facade's own* `asAuthoredListing` into `ListingWriter` would prove the
 * call is made and nothing about the translation: both sides would share the only code that could be
 * wrong. So [crossed] and its siblings below are a second translation, written from the published
 * fields of the Kotlin types, and the assertion is that two independently written crossings land on
 * the same bytes.
 *
 * That split is not decoration. It is what caught the defect this task fixed: `JsListing`'s
 * `feeBasisPoints` branched on `FeeTerm.namesRecipient`, which is `false` for both `Absent` and
 * `Stated(0)`, so §8.1's signed zero crossed as "no fee term at all" and a listing carrying
 * `["fee", "0"]` rebuilt from the crossed fields lost the tag and changed its own event id.
 * [basisPointsOf] here branches on the **type**, which is the distinction §8.1 draws, and the two
 * disagreed.
 *
 * ### Where the corpora come from
 *
 * Nothing here generates a listing or a rumor. `ListingWriterFixtures` and `RumorWriterFixtures`
 * already hold seeded generators whose coverage T40 and T41 proved — §4.1's eleven escaping cases
 * inside values, both `image` and `p` arities, every optional §5.3 row present and absent, and §8.1's
 * three fee cases including the stated zero. This file projects those Kotlin cases onto the boundary;
 * the tests pair the two calls.
 *
 * ### The vocabulary strings are pinned here, and deliberately
 *
 * [Answer.vocabulary] is the facade's own metadata — which rejection enum a `reason` came from — and
 * no section of NENYA-1 names it. It is passed in as a literal by each [answerOf] below rather than
 * derived by reflection, because `KClass.simpleName` is the one route to it and this file has to
 * compile and run identically on Kotlin/JS, where the loop cannot execute it. A pinned string a
 * reader can check against the facade is worth more than a derivation nobody can run.
 */
internal object JsBoundaryFixtures {

    // -----------------------------------------------------------------------------------------
    // Values the boundary carries as decimal strings.
    // -----------------------------------------------------------------------------------------

    /**
     * A `Long` as the exact decimal string this boundary carries it as, written **here** and not
     * through [JsDecimal].
     *
     * `Long.toString()` is the same call [JsDecimal.of] makes, and that is the point: the codec's
     * whole claim is that it formats and nothing else — no rounding, no grouping, no `Double`. A
     * projection that went through the codec could not tell "the codec is exact" from "the codec and
     * this file are wrong in the same way".
     */
    fun decimal(value: Long): String = value.toString()

    /** The same for a value that may be absent, where absent crosses as `null`. */
    fun decimalOrNull(value: Long?): String? = value?.toString()

    /**
     * §8.1's fee term as the nullable basis-point count the boundary carries it as.
     *
     * Branches on the **type**, which is the only thing that separates `Absent` from `Stated(0)`.
     * See this object's note: the inherited facade branched on `namesRecipient` and lost the
     * distinction §8.1 calls "itself a signed statement".
     */
    fun basisPointsOf(fee: FeeTerm): Int? = when (fee) {
        is FeeTerm.Stated -> fee.basisPoints
        FeeTerm.Absent -> null
    }

    // -----------------------------------------------------------------------------------------
    // Projections onto the boundary's input types.
    // -----------------------------------------------------------------------------------------

    /** [listing] as the values a page would hand [buildListingRequest] and its two siblings. */
    fun crossed(listing: AuthoredListing): JsAuthoredListing = JsAuthoredListing(
        authorPubkey = listing.authorPubkey,
        createdAtSeconds = decimal(listing.createdAt),
        dValue = listing.dValue,
        title = listing.title,
        priceMsat = decimal(listing.price.millisatoshis),
        content = listing.content,
        summary = listing.summary,
        publishedAtSeconds = decimalOrNull(listing.publishedAt),
        // §5.1's and §5.2's token, never the treatment. `ListingStatus.UNKNOWN` carries no token, and
        // a projection that silently sent `null` for it would turn "the writer refuses UNKNOWN" into
        // "the writer was never asked", so it fails loudly instead.
        statusToken = listing.status?.let {
            it.token ?: fail("§5.2's `unknown` is a treatment rather than a token and cannot cross")
        },
        mimeType = listing.mimeType,
        topics = listing.topics.toTypedArray(),
        images = listing.images.map { image ->
            if (image.dimensions == null) arrayOf(image.url) else arrayOf(image.url, image.dimensions!!)
        }.toTypedArray(),
        expirationSeconds = decimalOrNull(listing.expiration),
        alt = listing.alt,
        nenyaVersion = listing.nenyaVersion,
        pubkeyRefs = listing.pubkeyRefs.map { ref ->
            if (ref.relay == null) arrayOf(ref.pubkey) else arrayOf(ref.pubkey, ref.relay!!)
        }.toTypedArray(),
        feeBasisPoints = basisPointsOf(listing.fee),
        feeRecipient = listing.feeRecipient,
        license = listing.license,
        deliverBySeconds = decimalOrNull(listing.deliverBy),
        extraTags = listing.extraTags.map { it.toTypedArray() }.toTypedArray(),
    )

    /** [envelope] as §7.4's five crossed fields. */
    fun crossed(envelope: RumorEnvelope): JsRumorEnvelope = JsRumorEnvelope(
        authorPubkey = envelope.authorPubkey,
        createdAtSeconds = decimal(envelope.createdAt),
        counterparties = envelope.counterparties.map { ref ->
            if (ref.relay == null) arrayOf(ref.pubkey) else arrayOf(ref.pubkey, ref.relay!!)
        }.toTypedArray(),
        content = envelope.content,
        nenyaVersion = envelope.nenyaVersion,
        extraTags = envelope.extraTags.map { it.toTypedArray() }.toTypedArray(),
    )

    /**
     * [terms] as §8.3's two inputs and §7.5's two deadlines.
     *
     * The price comes off `split.price` and the fee off `split.term`, which are §8.3's *inputs*; the
     * fee and the total are derived by `OrderTerms.of` on the far side, exactly as they are on this
     * one. A boundary that carried the total would let a caller state a split disagreeing with its
     * own terms, which is the shape §8.4 aborts an order over.
     */
    fun crossed(terms: OrderTerms): JsOrderTerms = JsOrderTerms(
        priceMsat = decimal(terms.split.price.millisatoshis),
        feeBasisPoints = basisPointsOf(terms.split.term),
        expirationSeconds = decimalOrNull(terms.expiration),
        deliverBySeconds = decimalOrNull(terms.deliverBy),
    )

    /** [commitment] as §10.1's four crossed values. */
    fun crossed(commitment: DeliverableCommitment): JsCommitment = JsCommitment(
        xHex = commitment.x.toHex(),
        oxHex = commitment.ox.toHex(),
        mimeType = commitment.mimeType,
        sizeBytes = decimal(commitment.sizeBytes),
    )

    /** [release] as §10.3's four crossed values. */
    fun crossed(release: DeliverableRelease): JsRelease = JsRelease(
        xHex = release.x.toHex(),
        oxHex = release.ox.toHex(),
        fileType = release.fileType,
        sizeBytes = decimal(release.sizeBytes),
    )

    // -----------------------------------------------------------------------------------------
    // Answers, reduced to what both sides must agree on.
    // -----------------------------------------------------------------------------------------

    /**
     * A builder's answer: the event it built, or the refusal, with every field the boundary carries.
     *
     * One type for all three of `ListingBuild`, `RumorBuild` and `PaymentBuild`, because the boundary
     * flattens all three into one [JsBuildResult] and the question is whether that flattening loses
     * anything.
     */
    class Answer(
        val ok: Boolean,
        val event: WireEvent?,
        val reason: String?,
        val vocabulary: String?,
        val tag: String?,
        val tagReason: String?,
        val detail: String?,
    )

    /**
     * [call]'s answer, with the refusals the library raises **as exceptions** reduced to the same
     * [Answer] shape.
     *
     * Not every refusal on this path is a `Refused` value. Two rules fire before any writer is
     * reached, from the constructors the translation calls: §5.1's closed `status` vocabulary, where
     * `ListingStatusCodec.read` refuses one of §5.2's request tokens on an offer by name, and §7.5's
     * inverted-deadline rule inside `OrderTerms`'s own `init`. A JVM caller gets an exception for
     * both; decision **P** says the boundary returns them, so the comparison has to put the two
     * shapes into one vocabulary — and this is where, so that the facade is never given credit for a
     * refusal the library would have thrown past it.
     *
     * Deliberately **not** `Throwable`: an unexpected failure must fail this test rather than be
     * quietly compared against whatever the facade did with it.
     */
    fun answerOfCall(call: () -> Answer): Answer = try {
        call()
    } catch (refused: ListingException) {
        Answer(
            false, null, refused.reason.name, "ListingRejection", refused.tag,
            (refused.cause as? TagException)?.reason?.name, refused.message,
        )
    } catch (refused: OrderStateException) {
        Answer(false, null, refused.reason.name, "OrderStateRejection", null, null, refused.message)
    }

    /** §5.1's and §5.2's writer answer. */
    fun answerOf(build: ListingBuild): Answer = when (build) {
        is ListingBuild.Built -> Answer(true, build.event, null, null, null, null, null)
        is ListingBuild.Refused -> Answer(
            false, null, build.reason.name, "ListingRejection",
            build.tag, build.tagReason?.name, build.detail,
        )
    }

    /** §7.4's writer answer. */
    fun answerOf(build: RumorBuild): Answer = when (build) {
        is RumorBuild.Built -> Answer(true, build.event, null, null, null, null, null)
        is RumorBuild.Refused -> Answer(
            false, null, build.reason.name, "ChannelRejection", build.tag, null, build.detail,
        )
    }

    /**
     * §8.6's and §9.2's writer answer.
     *
     * `PaymentBuild.Refused` publishes exactly one of a `SettlementRejection` and a
     * `ChannelRejection`, and which one says which layer refused — §8.6's and §9.2's own rules, or
     * §7.4's envelope coming back from `RumorWriter`. That is why the vocabulary is part of the
     * answer rather than a constant per entry point.
     */
    fun answerOf(build: PaymentBuild): Answer = when (build) {
        is PaymentBuild.Built -> Answer(true, build.event, null, null, null, null, null)
        is PaymentBuild.Refused -> {
            val settlement = build.reason
            if (settlement != null) {
                Answer(false, null, settlement.name, "SettlementRejection", build.tag, null, build.detail)
            } else {
                Answer(
                    false, null, build.channelReason?.name, "ChannelRejection",
                    build.tag, null, build.detail,
                )
            }
        }
    }

    /**
     * The whole of the equality: [crossed] says exactly what [expected] says.
     *
     * Field by field rather than through one combined string, so a failure names the field. The
     * built case compares §4.1's canonical object form as well as the five fields, which is the
     * comparison that cannot be satisfied by two events that merely look alike: the `id` in it is
     * the SHA-256 of the serialisation, so equal JSON is equal bytes.
     */
    fun assertSameAnswer(crossed: JsBuildResult, expected: Answer, what: String) {
        assertEquals(expected.ok, crossed.ok, "$what: one side built and the other refused")
        assertEquals(
            if (expected.ok) "built" else "refused",
            crossed.kind,
            "$what: `kind` must discriminate the same way `ok` does",
        )
        if (expected.ok) {
            val event = expected.event ?: fail("$what: a built answer with no event")
            val crossedEvent = crossed.event ?: fail("$what: the crossed answer carries no event")
            assertSameEvent(crossedEvent, event, what)
            assertNull(crossed.reason, "$what: a built answer must name no reason")
            assertNull(crossed.reasonVocabulary, "$what: a built answer must name no vocabulary")
            assertNull(crossed.tag, "$what: a built answer must name no tag")
            assertNull(crossed.tagReason, "$what: a built answer must name no tag reason")
            assertNull(crossed.detail, "$what: a built answer must carry no detail")
        } else {
            assertNull(crossed.event, "$what: a refusal must carry no event")
            assertEquals(expected.reason, crossed.reason, "$what: the refusals differ")
            assertEquals(expected.vocabulary, crossed.reasonVocabulary, "$what: the vocabularies differ")
            assertEquals(expected.tag, crossed.tag, "$what: the tags the refusals are about differ")
            assertEquals(expected.tagReason, crossed.tagReason, "$what: the tag-layer reasons differ")
            assertEquals(expected.detail, crossed.detail, "$what: the details differ")
        }
    }

    /** [crossed] is the same event as [event], down to §4.1's canonical object form. */
    fun assertSameEvent(crossed: JsWireEvent, event: WireEvent, what: String) {
        assertEquals(event.kind, crossed.kind, "$what: the kinds differ")
        assertEquals(event.pubkey, crossed.pubkey, "$what: the authors differ")
        assertEquals(
            decimal(event.createdAt),
            crossed.createdAtSeconds,
            "$what: §4.1's created_at differs, or crossed as something other than its decimal form",
        )
        assertEquals(event.content, crossed.content, "$what: the contents differ")
        assertEquals(
            event.tags,
            crossed.tags.map { it.toList() },
            "$what: the tag arrays differ, so the two events have different ids",
        )
        // The id, by way of the serialisation it is the hash of. `unsignedJson` is where the crossing
        // computes it and where §4.3's event-level bounds are measured, so it is also the step that
        // can still refuse — which is why this compares a JsText and not a String.
        val crossedJson = crossed.unsignedJson()
        assertTrue(
            crossedJson.ok,
            "$what: the crossing refused to serialise an event the library built: " +
                "${crossedJson.reason} (${crossedJson.reasonVocabulary}) ${crossedJson.detail}",
        )
        assertEquals(
            EventJson.writeUnsigned(event, EventId.of(event)),
            crossedJson.value,
            "$what: §4.1's object form differs, so the two events have different ids",
        )
    }

    /** The event JSON a relay would serve for [event] — the input both decoders take. */
    fun json(event: WireEvent): String = EventJson.writeUnsigned(event, EventId.of(event))

    // -----------------------------------------------------------------------------------------
    // The decoded values, field by field. The names are swept against the compiled class by
    // `JsSurfaceTest`, so "every field" is derived from `JsListing` rather than from this list.
    // -----------------------------------------------------------------------------------------

    /** One published value, as the boundary spells it and as the Kotlin type holds it. */
    class Field(val name: String, val crossed: Any?, val expected: Any?)

    /**
     * Every value `JsListing` publishes, paired with the same value off [decoded].
     *
     * Arrays are reduced to lists, because an `Array` compares by identity and two correct
     * translations would still fail.
     */
    fun listingFields(crossed: JsListing, decoded: Listing): List<Field> = listOf(
        Field("idHex", crossed.idHex, decoded.id.toHex()),
        Field("authorPubkey", crossed.authorPubkey, decoded.authorPubkey),
        Field("createdAtSeconds", crossed.createdAtSeconds, decimal(decoded.createdAt)),
        Field("content", crossed.content, decoded.content),
        Field("kind", crossed.kind, decoded.kind),
        Field("dValue", crossed.dValue, decoded.dValue),
        Field("coordinate", crossed.coordinate, decoded.coordinate.toTagValue()),
        Field("title", crossed.title, decoded.title),
        Field("priceMsat", crossed.priceMsat, decimal(decoded.price.millisatoshis)),
        Field("status", crossed.status, decoded.status.name),
        Field("side", crossed.side, decoded.side.name),
        Field("statusToken", crossed.statusToken, decoded.statusToken),
        Field("summary", crossed.summary, decoded.summary),
        Field("publishedAtSeconds", crossed.publishedAtSeconds, decimalOrNull(decoded.publishedAt)),
        Field("topics", crossed.topics.toList(), decoded.topics),
        Field("mimeType", crossed.mimeType, decoded.mimeType),
        Field(
            "images",
            crossed.images.map { it.toList() },
            decoded.images.map { listOfNotNull(it.url, it.dimensions) },
        ),
        Field("expirationSeconds", crossed.expirationSeconds, decimalOrNull(decoded.expiration)),
        Field("alt", crossed.alt, decoded.alt),
        Field("nenyaVersion", crossed.nenyaVersion, decoded.nenyaVersion),
        Field(
            "pubkeyRefs",
            crossed.pubkeyRefs.map { it.toList() },
            decoded.pubkeyRefs.map { listOfNotNull(it.pubkey, it.relay) },
        ),
        Field("feeBasisPoints", crossed.feeBasisPoints, basisPointsOf(decoded.fee)),
        Field("feeRecipient", crossed.feeRecipient, decoded.feeRecipient),
        Field("license", crossed.license, decoded.license),
        Field("deliverBySeconds", crossed.deliverBySeconds, decimalOrNull(decoded.deliverBy)),
        Field("unknownTags", crossed.unknownTags.map { it.toList() }, decoded.unknownTags),
    )

    /** Every value `JsBid` publishes, on the same terms as [listingFields]. */
    fun bidFields(crossed: JsBid, decoded: Bid): List<Field> = listOf(
        Field("idHex", crossed.idHex, decoded.id.toHex()),
        Field("bidderPubkey", crossed.bidderPubkey, decoded.bidderPubkey),
        Field("createdAtSeconds", crossed.createdAtSeconds, decimal(decoded.createdAt)),
        Field("content", crossed.content, decoded.content),
        Field("kind", crossed.kind, decoded.kind),
        Field("listingCoordinate", crossed.listingCoordinate, decoded.listing.toTagValue()),
        Field("listingKind", crossed.listingKind, decoded.listingKind),
        Field("listingAuthorPubkey", crossed.listingAuthorPubkey, decoded.listingAuthorPubkey),
        Field("priceMsat", crossed.priceMsat, decimal(decoded.price.millisatoshis)),
        Field("rootRelayHint", crossed.rootRelayHint, decoded.rootRelayHint),
        Field("parentRelayHint", crossed.parentRelayHint, decoded.parentRelayHint),
        Field("expirationSeconds", crossed.expirationSeconds, decimalOrNull(decoded.expiration)),
        Field("openEnded", crossed.openEnded, decoded.openEnded),
        Field("deliverBySeconds", crossed.deliverBySeconds, decimalOrNull(decoded.deliverBy)),
        Field("feeBasisPoints", crossed.feeBasisPoints, basisPointsOf(decoded.fee)),
        Field("feeRecipient", crossed.feeRecipient, decoded.feeRecipient),
        Field("alt", crossed.alt, decoded.alt),
        Field("nenyaVersion", crossed.nenyaVersion, decoded.nenyaVersion),
        Field("topics", crossed.topics.toList(), decoded.topics),
        Field("unknownTags", crossed.unknownTags.map { it.toList() }, decoded.unknownTags),
    )

    /** Each pair in [fields] agrees, naming the field that did not. */
    fun assertSameFields(fields: List<Field>, what: String) {
        for (field in fields) {
            assertEquals(
                field.expected,
                field.crossed,
                "$what: `${field.name}` crossed as something other than the value the library decoded",
            )
        }
    }
}
