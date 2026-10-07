package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingWriter
import dev.eryalabs.nenya.money.FeeTerm
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * A §5.1 offer, §5.1 draft or §5.2 request this library decoded, as a plain object.
 *
 * Every field below is the same value `Listing` publishes, translated and nothing more: a `Msat`
 * becomes its exact decimal string, a unix-second `Long` becomes one too, an enum becomes its
 * `name`, a `List` becomes an `Array`, and `ImageRef`/`PubkeyRef`/`Coordinate` become the wire rows
 * §5.3 prints for them. No field is computed here and none is omitted because it was awkward.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsListing internal constructor(

    /** The decoded listing. Not exported — see [JsWireEvent]'s note on holding rather than crossing. */
    internal val listing: Listing,
) {

    /** §4.1's id, 64 lowercase hex characters. */
    public val idHex: String get() = listing.id.toHex()

    /** §4.1's `pubkey`: the listing's author. */
    public val authorPubkey: String get() = listing.authorPubkey

    /** §4.1's `created_at`, unix seconds as a decimal string. */
    public val createdAtSeconds: String get() = JsDecimal.of(listing.createdAt)

    /** §4.1's `content`. */
    public val content: String get() = listing.content

    /** §5.1's or §5.2's kind: 30402, 30403 or 30404. */
    public val kind: Int get() = listing.kind

    /** §5.3's `d` value. */
    public val dValue: String get() = listing.dValue

    /** §4.2's coordinate, `<kind>:<pubkey>:<d>`. */
    public val coordinate: String get() = listing.coordinate.toTagValue()

    /** §5.3's `title`. */
    public val title: String get() = listing.title

    /** §8.2's `price_msat`, in millisatoshis, as an exact decimal string. */
    public val priceMsat: String get() = JsDecimal.of(listing.price.millisatoshis)

    /** `ListingStatus.name` — the treatment, which is `UNKNOWN` for a token this version has no rule for. */
    public val status: String get() = listing.status.name

    /** `ListingSide.name`. */
    public val side: String get() = listing.side.name

    /** The `status` token exactly as it appeared, or `null` when the tag is absent. */
    public val statusToken: String? get() = listing.statusToken

    /** §5.3's `summary`. */
    public val summary: String? get() = listing.summary

    /** §5.3's `published_at`, unix seconds as a decimal string, or `null`. */
    public val publishedAtSeconds: String? get() = JsDecimal.ofOrNull(listing.publishedAt)

    /** §5.3's `t` topics. */
    public val topics: Array<String> get() = listing.topics.toTypedArray()

    /** §5.3's `m`. */
    public val mimeType: String? get() = listing.mimeType

    /** §5.3's `image` rows, each `[url]` or `[url, "<width>x<height>"]`. */
    public val images: Array<Array<String>>
        get() = Array(listing.images.size) { i ->
            val image = listing.images[i]
            if (image.dimensions == null) arrayOf(image.url) else arrayOf(image.url, image.dimensions!!)
        }

    /** §5.3's `expiration`, unix seconds as a decimal string, or `null`. */
    public val expirationSeconds: String? get() = JsDecimal.ofOrNull(listing.expiration)

    /** §5.3's `alt`. */
    public val alt: String? get() = listing.alt

    /** §4.5's `nenya` major version, or `null` when the tag is absent. */
    public val nenyaVersion: Int? get() = listing.nenyaVersion

    /** §5.3's `p` rows, each `[pubkey]` or `[pubkey, relay]`. */
    public val pubkeyRefs: Array<Array<String>>
        get() = Array(listing.pubkeyRefs.size) { i ->
            val ref = listing.pubkeyRefs[i]
            if (ref.relay == null) arrayOf(ref.pubkey) else arrayOf(ref.pubkey, ref.relay!!)
        }

    /**
     * §8.1's fee in basis points, or `null` for [FeeTerm.Absent].
     *
     * `FeeTerm` is a sealed hierarchy and no sealed type crosses, so the two cases are told apart
     * by this field being absent rather than by a type. `Absent` and `Stated(0)` are **not** the
     * same value — §8.1 gives them different meanings for whether a fee payee may exist at all —
     * and `null` versus `0` keeps them apart.
     *
     * **Which is why the discriminator is the type and not [FeeTerm.namesRecipient].** That property
     * answers whether §8.1 requires a *recipient*, and it is `false` for both `Absent` and
     * `Stated(0)`; branching on it collapsed §8.1's signed zero into "no term at all", and a listing
     * carrying `["fee", "0"]` rebuilt from these fields lost the tag and changed its own event id.
     * `JsFacadeEqualityTest` draws the stated zero deliberately for that reason.
     */
    public val feeBasisPoints: Int? get() = when (val fee = listing.fee) {
        is FeeTerm.Stated -> fee.basisPoints
        FeeTerm.Absent -> null
    }

    /** §8.4's `fee` recipient. */
    public val feeRecipient: String? get() = listing.feeRecipient

    /** §5.3's `license`. */
    public val license: String? get() = listing.license

    /** §7.5's `deliver_by`, unix seconds as a decimal string, or `null`. */
    public val deliverBySeconds: String? get() = JsDecimal.ofOrNull(listing.deliverBy)

    /** The tags §5.3 gives no row for, as they appeared. */
    public val unknownTags: Array<Array<String>> get() = listing.unknownTags.asTagArray()

    /** Names the kind only: a `d` value, a title and a price are §12 item 11 values. */
    override fun toString(): String = "JsListing(kind=$kind)"
}

/** A §6 public bid this library decoded, on the same terms as [JsListing]. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsBid internal constructor(

    /** The decoded bid. Not exported. */
    internal val bid: Bid,
) {

    /** §4.1's id, 64 lowercase hex characters. */
    public val idHex: String get() = bid.id.toHex()

    /** §4.1's `pubkey`: the bidder. */
    public val bidderPubkey: String get() = bid.bidderPubkey

    /** §4.1's `created_at`, unix seconds as a decimal string. */
    public val createdAtSeconds: String get() = JsDecimal.of(bid.createdAt)

    /** §4.1's `content`. */
    public val content: String get() = bid.content

    /** §6's kind: 1111. */
    public val kind: Int get() = bid.kind

    /** §4.2's coordinate of the listing bid on. */
    public val listingCoordinate: String get() = bid.listing.toTagValue()

    /** The bid-on listing's kind. */
    public val listingKind: Int get() = bid.listingKind

    /** The bid-on listing's author. */
    public val listingAuthorPubkey: String get() = bid.listingAuthorPubkey

    /** §8.2's `price_msat` the bid offers, as an exact decimal string. */
    public val priceMsat: String get() = JsDecimal.of(bid.price.millisatoshis)

    /** §6's root relay hint, or `null`. */
    public val rootRelayHint: String? get() = bid.rootRelayHint

    /** §6's parent relay hint, or `null`. */
    public val parentRelayHint: String? get() = bid.parentRelayHint

    /** §5.3's `expiration`, unix seconds as a decimal string, or `null`. */
    public val expirationSeconds: String? get() = JsDecimal.ofOrNull(bid.expiration)

    /** True exactly when the bid carries no `expiration` (§6). */
    public val openEnded: Boolean get() = bid.openEnded

    /** §7.5's `deliver_by`, unix seconds as a decimal string, or `null`. */
    public val deliverBySeconds: String? get() = JsDecimal.ofOrNull(bid.deliverBy)

    /** §8.1's fee in basis points, or `null` for an absent fee term. See [JsListing.feeBasisPoints]. */
    public val feeBasisPoints: Int? get() = when (val fee = bid.fee) {
        is FeeTerm.Stated -> fee.basisPoints
        FeeTerm.Absent -> null
    }

    /** §8.4's `fee` recipient. */
    public val feeRecipient: String? get() = bid.feeRecipient

    /** §5.3's `alt`. */
    public val alt: String? get() = bid.alt

    /** §4.5's `nenya` major version, or `null`. */
    public val nenyaVersion: Int? get() = bid.nenyaVersion

    /** §5.3's `t` topics. */
    public val topics: Array<String> get() = bid.topics.toTypedArray()

    /** The tags §5.3 gives no row for, as they appeared. */
    public val unknownTags: Array<Array<String>> get() = bid.unknownTags.asTagArray()

    /** Names the kind only (§12 item 11). */
    override fun toString(): String = "JsBid(kind=$kind)"
}

/**
 * What [decodeListing] answered: the listing, or the refusal, with [kind] as the discriminator.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsListingResult internal constructor(

    /** `"listing"` or `"refused"`. */
    public val kind: String,

    /** True exactly when [kind] is `"listing"`. */
    public val ok: Boolean,

    /** The listing, when [ok]. */
    public val listing: JsListing?,

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String?,

    /** Which rejection enum [reason] came from. */
    public val reasonVocabulary: String?,

    /** The tag the refusal is about, or `null`. */
    public val tag: String?,

    /** The tag layer's own `TagRejection.name`, where one refused the value. */
    public val tagReason: String?,

    /** Why, in words. Never echoes an input (§12 item 11). */
    public val detail: String?,
) {

    override fun toString(): String =
        if (ok) "JsListingResult(listing)" else "JsListingResult(refused: $reason)"
}

/** What [decodeBid] answered, on the same terms as [JsListingResult]. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsBidResult internal constructor(

    /** `"bid"` or `"refused"`. */
    public val kind: String,

    /** True exactly when [kind] is `"bid"`. */
    public val ok: Boolean,

    /** The bid, when [ok]. */
    public val bid: JsBid?,

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String?,

    /** Which rejection enum [reason] came from. */
    public val reasonVocabulary: String?,

    /** The tag the refusal is about, or `null`. */
    public val tag: String?,

    /** The tag layer's own `TagRejection.name`, where one refused the value. */
    public val tagReason: String?,

    /** Why, in words. Never echoes an input (§12 item 11). */
    public val detail: String?,
) {

    override fun toString(): String = if (ok) "JsBidResult(bid)" else "JsBidResult(refused: $reason)"
}

/**
 * The values a client authors a listing from, as strings and arrays.
 *
 * One field per `AuthoredListing` field, in that order, with the four translations decision **P**
 * fixes applied: money and timestamps are decimal strings, `FeeTerm` is a nullable basis-point
 * count, `ListingStatus` is its token, and `ImageRef`/`PubkeyRef` are the wire rows §5.3 prints.
 * Nothing is defaulted differently from `AuthoredListing` and nothing is added.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsAuthoredListing public constructor(

    /** §4.1's `pubkey`: the author's x-only key, 64 hex characters. */
    public val authorPubkey: String,

    /** §4.1's `created_at`, unix seconds as a decimal string (§4.6: the caller's clock). */
    public val createdAtSeconds: String,

    /** §5.3's `d` value. */
    public val dValue: String,

    /** §5.3's `title`. */
    public val title: String,

    /** §8.2's price in millisatoshis, as an exact decimal string. */
    public val priceMsat: String,

    /** §4.1's `content`. */
    public val content: String = "",

    /** §5.3's `summary`. */
    public val summary: String? = null,

    /** §5.3's `published_at`, unix seconds as a decimal string. */
    public val publishedAtSeconds: String? = null,

    /**
     * §5.1's or §5.2's `status` token — `active`, `sold`, `awarded`, `fulfilled`, `cancelled` — or
     * `null` for no `status` tag.
     *
     * Read through `ListingStatusCodec.read` for the kind being written, which is where §5.1's and
     * §5.2's two vocabularies differ. A token that kind has no rule for becomes `UNKNOWN`, and the
     * writer's own §5.2 refusal — `UNKNOWN_IS_NOT_EMITTABLE` — is what reports it.
     */
    public val statusToken: String? = null,

    /** §5.3's `m`. */
    public val mimeType: String? = null,

    /** §5.3's `t` topics. */
    public val topics: Array<String> = emptyArray(),

    /** §5.3's `image` rows, each `[url]` or `[url, "<width>x<height>"]`. */
    public val images: Array<Array<String>> = emptyArray(),

    /** §5.3's `expiration`, unix seconds as a decimal string. */
    public val expirationSeconds: String? = null,

    /** §5.3's `alt`. */
    public val alt: String? = null,

    /** §4.5's `nenya` major version. */
    public val nenyaVersion: Int = ListingWriter.NENYA_MAJOR_VERSION,

    /** §5.3's `p` rows, each `[pubkey]` or `[pubkey, relay]`. */
    public val pubkeyRefs: Array<Array<String>> = emptyArray(),

    /** §8.1's fee in basis points, or `null` for `FeeTerm.Absent`. See [JsListing.feeBasisPoints]. */
    public val feeBasisPoints: Int? = null,

    /** §8.4's `fee` recipient. */
    public val feeRecipient: String? = null,

    /** §5.3's `license`. */
    public val license: String? = null,

    /** §7.5's `deliver_by`, unix seconds as a decimal string. */
    public val deliverBySeconds: String? = null,

    /** Rows §5.3 gives no vocabulary for, passed through as extensions. */
    public val extraTags: Array<Array<String>> = emptyArray(),
) {

    /** Names nothing: a `d` value, a title and a price are §12 item 11 values. */
    override fun toString(): String = "JsAuthoredListing(redacted)"
}
