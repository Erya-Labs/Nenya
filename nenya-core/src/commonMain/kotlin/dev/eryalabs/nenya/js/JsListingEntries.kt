package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.bid.BidException
import dev.eryalabs.nenya.listing.AuthoredListing
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingBuild
import dev.eryalabs.nenya.listing.ListingException
import dev.eryalabs.nenya.listing.ListingStatusCodec
import dev.eryalabs.nenya.listing.ListingWriter
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.MoneyException
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.ImageRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventJson
import dev.eryalabs.nenya.wire.JsonException
import dev.eryalabs.nenya.wire.WireException
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

// =================================================================================================
// Decoding. One entry point per kind a client reads, each taking the event JSON a relay served and
// running the whole read path this library already has: §4.3's bounds and JSON rules
// (`EventJson.read`), §4.1's id recomputation (`CheckedEvent.checkEventId`), then §5 or §6's
// decoder. Nothing is reordered and nothing is skipped — a caller cannot reach the decoder without
// the id check, here or on the JVM, because the decoder takes a type only the id check produces.
// =================================================================================================

/**
 * Decode a §5.1 offer, §5.1 draft or §5.2 request from the event JSON a relay served.
 *
 * Returns the listing, or the refusal as a value — never throws (decision **P**). Every refusal
 * reported is one of this library's own: a `JsonRejection` from §4.3's JSON rules, a
 * `WireRejection` from §4.1's id check or §4.3's bounds, or a `ListingRejection` from §5.1, §5.2,
 * §5.3 or §4.3 with the tag layer's own reason beside it. This function decides none of them.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun decodeListing(eventJson: String): JsListingResult = try {
    val read = EventJson.read(eventJson)
    val checked = CheckedEvent.checkEventId(read.claimedIdHex, read.event)
    JsListingResult("listing", true, JsListing(Listing.decode(checked)), null, null, null, null, null)
} catch (refused: ListingException) {
    JsListingResult(
        "refused", false, null,
        refused.reason.name, "ListingRejection", refused.tag,
        (refused.cause as? TagException)?.reason?.name, refused.message,
    )
} catch (refused: WireException) {
    JsListingResult("refused", false, null, refused.reason.name, "WireRejection", null, null, refused.message)
} catch (refused: JsonException) {
    JsListingResult("refused", false, null, refused.reason.name, "JsonRejection", null, null, refused.message)
} catch (refused: TagException) {
    JsListingResult("refused", false, null, refused.reason.name, "TagRejection", null, null, refused.message)
}

/**
 * Decode a §6 public bid from the event JSON a relay served.
 *
 * The same read path and the same refusal discipline as [decodeListing]. §5.3's Card. and
 * Requirement columns are not applied, because `Bid.decode` does not apply them — §5.3 says they
 * state what a *listing* requires — and this function adds no rule of its own either way.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun decodeBid(eventJson: String): JsBidResult = try {
    val read = EventJson.read(eventJson)
    val checked = CheckedEvent.checkEventId(read.claimedIdHex, read.event)
    JsBidResult("bid", true, JsBid(Bid.decode(checked)), null, null, null, null, null)
} catch (refused: BidException) {
    JsBidResult(
        "refused", false, null,
        refused.reason.name, "BidRejection", refused.tag,
        (refused.cause as? TagException)?.reason?.name, refused.message,
    )
} catch (refused: WireException) {
    JsBidResult("refused", false, null, refused.reason.name, "WireRejection", null, null, refused.message)
} catch (refused: JsonException) {
    JsBidResult("refused", false, null, refused.reason.name, "JsonRejection", null, null, refused.message)
} catch (refused: TagException) {
    JsBidResult("refused", false, null, refused.reason.name, "TagRejection", null, null, refused.message)
}

// =================================================================================================
// Listing builders — the three T40 added, each one call to `ListingWriter` over the same translated
// `AuthoredListing`.
// =================================================================================================

/** §5.2's `kind:30404` want-to-buy request, through `ListingWriter.request`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildListingRequest(listing: JsAuthoredListing): JsBuildResult =
    buildListing(listing, NenyaKind.REQUEST)

/** §5.1's `kind:30402` offer, through `ListingWriter.offer`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildListingOffer(listing: JsAuthoredListing): JsBuildResult =
    buildListing(listing, NenyaKind.OFFER)

/** §5.1's `kind:30403` draft, through `ListingWriter.draft`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun buildListingDraft(listing: JsAuthoredListing): JsBuildResult =
    buildListing(listing, NenyaKind.OFFER_DRAFT)

/**
 * The one body behind the three entry points above: translate, then call the writer for that kind.
 *
 * The `when` is over [kind] rather than a function reference because `ListingWriter`'s three entry
 * points are three separate published functions and routing through one of them by name is what
 * makes the call this function makes identical to the call a JVM caller makes.
 */
private fun buildListing(listing: JsAuthoredListing, kind: Int): JsBuildResult = jsBuild {
    val authored = listing.asAuthoredListing(kind)
    when (kind) {
        NenyaKind.REQUEST -> ListingWriter.request(authored)
        NenyaKind.OFFER -> ListingWriter.offer(authored)
        else -> ListingWriter.draft(authored)
    }.asJsBuildResult()
}

/** `ListingBuild`'s two cases, flattened to [JsBuildResult]'s fields and nothing else. */
private fun ListingBuild.asJsBuildResult(): JsBuildResult = when (this) {
    is ListingBuild.Built -> jsBuilt(event)
    is ListingBuild.Refused ->
        jsRefused(reason.name, "ListingRejection", tag, tagReason?.name, detail)
}

/**
 * This object's fields as the `AuthoredListing` `ListingWriter` takes.
 *
 * Every nested value is built by **this library's own constructor** — `Msat.ofMsat`,
 * `ImageRef`, `PubkeyRef`, `FeeTerm.of`, `ListingStatusCodec.read` — so each one's refusal is the
 * refusal the JVM side gets for the same input. Nothing here re-checks a URL, a pubkey, a basis
 * point or a status token; the constructors do, and they throw the `TagException` and
 * `MoneyException` [jsBuild] translates.
 */
private fun JsAuthoredListing.asAuthoredListing(kind: Int): AuthoredListing = AuthoredListing(
    authorPubkey = authorPubkey,
    createdAt = crossedSeconds("created_at", createdAtSeconds),
    dValue = dValue,
    title = title,
    price = Msat.ofMsat(crossedAmount("price", priceMsat)),
    content = content,
    summary = summary,
    publishedAt = crossedSecondsOrNull("published_at", publishedAtSeconds),
    status = statusToken?.let { ListingStatusCodec.read(it, kind) },
    mimeType = mimeType,
    topics = topics.toList(),
    images = images.map { it.asImageRef() },
    expiration = crossedSecondsOrNull("expiration", expirationSeconds),
    alt = alt,
    nenyaVersion = nenyaVersion,
    pubkeyRefs = pubkeyRefs.map { it.asPubkeyRef() },
    fee = feeBasisPoints?.let { FeeTerm.of(it) } ?: FeeTerm.Absent,
    feeRecipient = feeRecipient,
    license = license,
    deliverBy = crossedSecondsOrNull("deliver_by", deliverBySeconds),
    extraTags = extraTags.asTagRows(),
)

/** §5.3's `image` row, through `ImageRef`'s own constructor so its refusals are its own. */
private fun Array<String>.asImageRef(): ImageRef = when (size) {
    1 -> ImageRef(this[0])
    2 -> ImageRef(this[0], this[1])
    else -> throw JsCrossing(
        JsCrossing.WRONG_ROW_ARITY,
        "image",
        "§5.3 encodes an `image` row as [url] or [url, \"<width>x<height>\"]; this one crossed with " +
            "$size element(s)",
    )
}

/** §5.3's `p` row, through `PubkeyRef`'s own constructor. */
private fun Array<String>.asPubkeyRef(): PubkeyRef = when (size) {
    1 -> PubkeyRef(this[0])
    2 -> PubkeyRef(this[0], this[1])
    else -> throw JsCrossing(
        JsCrossing.WRONG_ROW_ARITY,
        "p",
        "§5.3 encodes a `p` row as [pubkey] or [pubkey, relay]; this one crossed with $size element(s)",
    )
}
