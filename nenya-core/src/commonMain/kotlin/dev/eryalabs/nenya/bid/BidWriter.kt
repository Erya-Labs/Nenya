package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.listing.ListingWriter
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.Coordinate
import dev.eryalabs.nenya.tag.ImageRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagEncoding
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.tag.TagRejection
import dev.eryalabs.nenya.tag.TagSet
import dev.eryalabs.nenya.tag.TagWriter
import dev.eryalabs.nenya.tag.readPubkeyHex
import dev.eryalabs.nenya.wire.WireEvent
import dev.eryalabs.nenya.wire.WireException

/**
 * The values a client authors for one §6 public bid, before any §6 or §5.3 rule has been applied.
 *
 * ### A carrier, not a check
 *
 * The same design as `AuthoredListing`, for the same reason: [BidWriter] refuses **by value**, with
 * the tag named, and a carrier that threw would put half of those refusals behind a `try` the caller
 * did not ask for. The typed fields are typed because the type is the rule — [price] is a [Msat],
 * [fee] a [FeeTerm] where `Absent` and a stated zero are different events (§8.1), and [listing] a
 * §4.2 [Coordinate], whose constructor is this library's one reader of that form.
 *
 * ### The scope tags are not parameters, and that is the point
 *
 * §6 fixes what each of `A`, `a`, `K`, `k`, `P` and `p` carries: the coordinate twice, the listing's
 * kind as a decimal string twice, and the listing author's pubkey twice. All six are a function of
 * [listing], so the writer spells them from it and a caller cannot hand in a `k` that disagrees with
 * the coordinate's kind — except as an extra tag, which is refused naming `k` (see [BidWriter]).
 *
 * The two relay hints are the one thing about the scope that is the author's choice. NIP-22 makes
 * the third element optional; [rootRelayHint] goes on the root pair `A` and `P` and [parentRelayHint]
 * on the parent pair `a` and `p`, which is how §6's worked bid prints them and is what `Bid` reports
 * back as `rootRelayHint` and `parentRelayHint`.
 *
 * ### The §5.3 rows §6 does not print
 *
 * §6's worked bid prints `price`, `fee`, `deliver_by`, `expiration`, `t`, `nenya` and `alt`.
 * `Bid.decode` applies §5.3's Encoding column to every row of that table, and §5.3 says its Card. and
 * Requirement columns "MUST NOT be applied to a bid", so `d`, `title`, `summary`, `published_at`,
 * `status`, `m`, `image` and `license` are rows a bid may legally carry and the decoder reads. Each
 * has a parameter here, because a builder refusing what its own decoder accepts is a defect, and a
 * row handed in as an extension would skip §5.3's Encoding column. They carry **no** meaning §6
 * gives them: [status] in particular is not §5.1's or §5.2's listing vocabulary — that is a listing
 * rule — and is emitted as the text it was handed, which is exactly how `Bid.decode` reads it.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public class AuthoredBid(

    /**
     * The bidder's x-only public key, 64 hex characters (§4.3), lowercased on the way out — §4.3's
     * rule for an event this client authors.
     */
    public val bidderPubkey: String,

    /** §4.1's `created_at`, in Unix seconds. The app's clock, never this library's (§4.6). */
    public val createdAt: Long,

    /**
     * §4.2's coordinate of the listing bid on: a request, or an offer as a counter-offer (§6).
     *
     * Every scope tag is spelled from it. A coordinate naming a kind that is not one of §5.1's or
     * §5.2's is refused as [BidRejection.NOT_A_LISTING_COORDINATE], as `Bid.decode` refuses it.
     */
    public val listing: Coordinate,

    /** §5.3's `price`, MUST on a bid (§6), in millisatoshis — §8.2's "what the provider receives". */
    public val price: Msat,

    /**
     * §5.4's prose. §6: it SHOULD restate the price and the delivery terms, and carries **no**
     * machine meaning — an implementation MUST derive every term from the tags.
     */
    public val content: String = "",

    /** NIP-22's optional third element on the root pair `A` and `P`, or `null` for none. */
    public val rootRelayHint: String? = null,

    /** NIP-22's optional third element on the parent pair `a` and `p`, or `null` for none. */
    public val parentRelayHint: String? = null,

    /**
     * §8.1's `fee` term, MAY on a bid (§6): "the one the bidder is willing to transact under".
     *
     * [FeeTerm.Absent] emits no `fee` tag; a stated zero emits the two-element `["fee", "0"]`.
     */
    public val fee: FeeTerm = FeeTerm.Absent,

    /** The `fee` recipient pubkey. §8.1: REQUIRED above zero basis points, OMITTED at zero. */
    public val feeRecipient: String? = null,

    /** §5.3's `deliver_by`, in Unix seconds. §6: a deadline, evaluated against the injected clock. */
    public val deliverBy: Long? = null,

    /** §5.3's `expiration` (NIP-40), SHOULD on a bid (§6). `null` is §6's open-ended commitment. */
    public val expiration: Long? = null,

    /**
     * §5.3's `t` tokens **beyond** the `nenya` token §6 requires, which the writer emits first.
     *
     * Lowercased on the way out (§5.3). A `request` or `wts` token is **permitted** here: §5.3's
     * exactly-one-side rule is a listing rule, `Bid.decode` does not apply it, and §6 only says a
     * bid without one "is correct".
     */
    public val topics: List<String> = emptyList(),

    /** §4.5's `nenya` version, MUST on a bid (§6). This document's major version by default. */
    public val nenyaVersion: Int = ListingWriter.NENYA_MAJOR_VERSION,

    /** §5.3's `alt` (NIP-31), SHOULD on a bid (§6) — what a generic NIP-22 client renders (§15). */
    public val alt: String? = null,

    /** §5.3's `d`, or `null`. A `kind:1111` is not addressable, so it addresses nothing here. */
    public val dValue: String? = null,

    /** §5.3's `title`, or `null`. Plain text. */
    public val title: String? = null,

    /** §5.3's `summary`, or `null`. Plain text. */
    public val summary: String? = null,

    /** §5.3's `published_at`, in Unix seconds, or `null`. */
    public val publishedAt: Long? = null,

    /** §5.3's `status` text, or `null`. Carried, never interpreted: see this class's note. */
    public val status: String? = null,

    /** §5.3's `m`, or `null` — a MIME type, NIP-94 semantics. */
    public val mimeType: String? = null,

    /** §5.3's `image` tags, in order. Bounded by §4.3's fifth resource bound; see [BidWriter]. */
    public val images: List<ImageRef> = emptyList(),

    /** §5.3's `license` token, or `null`. Its vocabulary is `OPEN-5`, so it is carried, not read. */
    public val license: String? = null,

    /** Tags §5.3 and §6 do not name, emitted verbatim after every row the writer spells (§4.3). */
    public val extraTags: List<List<String>> = emptyList(),
) {

    /**
     * Names counts and no value the author wrote.
     *
     * A bid is a public event, but a coordinate is a listing id and a counterparty pubkey in one
     * string (STOP RULE 14), so this type redacts by default like every other in this library.
     */
    override fun toString(): String =
        "AuthoredBid(listingKind=${listing.kind}, topics=${topics.size}, images=${images.size}, " +
            "extraTags=${extraTags.size})"
}

/**
 * What [BidWriter] answers: the unsigned event, or the §6, §5.3 or §4.3 rule that refused it.
 *
 * A value rather than an exception — decision **P**'s rule for the whole writing half, and the same
 * shape as `ListingBuild`.
 */
public sealed interface BidBuild {

    /**
     * The unsigned event, for the app to sign.
     *
     * Nothing is signed and nothing is published here; §4.1's `id` is recomputed from these five
     * fields by `EventId.of`. §4.3's four size bounds are measured there and not here, for the
     * reason `ListingBuild.Built` gives; the fifth — `image` tags — is checked by the writer.
     */
    public class Built internal constructor(

        /** §4.1's five id-bearing fields, with the tags in [BidWriter.TAG_ORDER]. */
        public val event: WireEvent,
    ) : BidBuild {

        /** Names no field of the event: a coordinate is a §12 item 11 value. */
        override fun toString(): String = "BidBuild.Built(kind=${event.kind}, tags=${event.tags.size})"
    }

    /** The bid was refused, carrying the reason and the tag it is about as **data**. */
    public class Refused internal constructor(

        /** Which §6, §5.3, §4.3 or §4.1 rule refused the bid. */
        public val reason: BidRejection,

        /** The tag this refusal is about, or `null` where the rule names no single tag. */
        public val tag: String?,

        /** The §5.3 Encoding-column reason, where a tag codec refused the value, or `null`. */
        public val tagReason: TagRejection?,

        /** Why, in words, for a log. **Never echoes the caller's input** (§12, STOP RULE 14). */
        public val detail: String,
    ) : BidBuild {

        override fun toString(): String = "BidBuild.Refused(reason=$reason, tag=$tag)"
    }
}

/**
 * The write half of §6: the one public event this library read and could not write.
 *
 * ### The proof it is held to
 *
 * Decision **P**'s, as for `ListingWriter`: build, decode with `Bid.decode`, rebuild from what the
 * decode reports, and compare the emitted bytes. A builder whose own decoder refuses its output is a
 * defect in the builder.
 *
 * ### Nothing here is a second copy of §6's required set
 *
 * The final audit walks `Bid.requiredTags()` — the list `Bid.decode` enforces and `BidCodecTest`
 * removes tag by tag — and the scope tags whose second occurrence `Bid.decode` refuses. So a tag
 * added to §6's MUSTs and to that list, with no parameter here to spell it, makes this writer refuse
 * every bid rather than quietly emit ones its decoder rejects.
 *
 * ### What it refuses, by value
 *
 * **In the order the checks are made**, because exactly one refusal is reported and it is the first.
 *
 * 1. §4.3's event fields — a bidder pubkey that is not 64 hex characters, a negative `created_at`
 *    ([BidRejection.MALFORMED_EVENT_FIELD]).
 * 2. §6's scope: a coordinate naming a kind that is not a listing kind
 *    ([BidRejection.NOT_A_LISTING_COORDINATE], naming `A`).
 * 3. §4.3's fifth resource bound on `image` tags ([BidRejection.LIMIT_EXCEEDED]) — rejected, never
 *    truncated, against the injected [TagLimits].
 * 4. §5.3's Encoding column, row by row in [TAG_ORDER], through `TagWriter`
 *    ([BidRejection.MALFORMED_TAG] naming the row, with the tag-layer reason beside it).
 * 5. The extra tags, against the rows just emitted: an empty tag ([BidRejection.MALFORMED_TAG]); `E`
 *    or `e` ([BidRejection.SCOPED_BY_EVENT_ID]); one of the six scope tags, refused for the §6 rule
 *    it breaks when its value disagrees with the one the writer spelled from the coordinate
 *    ([BidRejection.SCOPE_DISAGREEMENT], [BidRejection.LISTING_KIND_MISMATCH],
 *    [BidRejection.LISTING_AUTHOR_MISMATCH]) and as [BidRejection.DUPLICATE_TAG] when it agrees;
 *    `item` ([BidRejection.DOUBLE_BINDING]); `g` and `location`, which §5.3 says an implementation
 *    MUST NOT emit ([BidRejection.FORBIDDEN_TAG]); a second occurrence of a single-occurrence §5.3
 *    row ([BidRejection.DUPLICATE_TAG]); and any other §5.3 row
 *    ([BidRejection.ROW_IS_NOT_AN_EXTENSION]).
 * 6. A final audit, derived: every tag in `Bid.requiredTags()` present, `t` carrying `nenya`, no
 *    scope tag or single-occurrence §5.3 row twice, no `E`/`e`, no `item`, no `g` or `location`.
 *
 * Pure computation: no clock, no randomness, no I/O. The `created_at` is the caller's (§4.6).
 */
public object BidWriter {

    /**
     * The order the tags are emitted in, which is wire-visible — §4.1 hashes the tags in order — and
     * therefore pinned.
     *
     * The first thirteen are the order §6's worked bid prints its tag names in; `BidWriterTest`
     * parses that example out of `spec/NENYA-1.md` at test time and holds this list equal to it. The
     * remaining eight are every other §5.3 row a bid may carry, in §5.3's table order, which the same
     * test derives from [NenyaTags]. The three it may not — `item`, `g`, `location` — are absent.
     */
    public val TAG_ORDER: List<String> = listOf(
        "A",
        "K",
        "P",
        "a",
        "k",
        "p",
        "price",
        "fee",
        "deliver_by",
        "expiration",
        "t",
        "nenya",
        "alt",
        "d",
        "title",
        "summary",
        "published_at",
        "status",
        "m",
        "image",
        "license",
    )

    /** A §6 public bid, `kind:1111`, on the listing [AuthoredBid.listing] names. */
    public fun bid(bid: AuthoredBid, limits: TagLimits = TagLimits.DEFAULT): BidBuild {
        val bidder = try {
            PubkeyRef(bid.bidderPubkey).pubkey
        } catch (refused: TagException) {
            return refusal(
                BidRejection.MALFORMED_EVENT_FIELD,
                null,
                "§4.3 fixes a bidder pubkey as exactly 64 hexadecimal characters and §4.1 hashes it " +
                    "into the event id; this one was refused as ${refused.reason.name}",
                refused.reason,
            )
        }
        if (bid.createdAt < 0L) {
            return refusal(
                BidRejection.MALFORMED_EVENT_FIELD,
                null,
                "§4.3 fixes a timestamp as a non-negative integer and `created_at` is one",
            )
        }
        if (bid.listing.kind !in NenyaKind.LISTING_KINDS) {
            return refusal(
                BidRejection.NOT_A_LISTING_COORDINATE,
                BidVocabulary.ROOT_COORDINATE,
                "§6 scopes a bid to a listing — a request or, as a counter-offer, an offer — and this " +
                    "coordinate names kind:${bid.listing.kind}, which is not one of " +
                    "${NenyaKind.LISTING_KINDS.sorted()}",
            )
        }
        if (bid.images.size > limits.maxImageTags) {
            return refusal(
                BidRejection.LIMIT_EXCEEDED,
                BidVocabulary.IMAGE,
                "this bid names ${bid.images.size} `${BidVocabulary.IMAGE}` tags and the injected bound " +
                    "is ${limits.maxImageTags}; §4.3 requires rejecting rather than truncating",
            )
        }

        val tags = mutableListOf<List<String>>()
        for (name in TAG_ORDER) {
            try {
                tags += row(name, bid)
            } catch (refused: TagException) {
                return refusal(
                    BidRejection.MALFORMED_TAG,
                    name,
                    "§5.3's Encoding column refused the value this writer was handed for `$name`, as " +
                        refused.reason.name,
                    refused.reason,
                )
            }
        }
        extraTagProblem(bid, tags)?.let { return it }
        // §4.3: verbatim and last, so an extension can never displace a row from its position.
        for (tag in bid.extraTags) tags += tag.toList()

        auditProblem(tags)?.let { return it }

        val event = try {
            WireEvent(bidder, bid.createdAt, NenyaKind.PUBLIC_BID, tags, bid.content)
        } catch (refused: WireException) {
            return refusal(
                BidRejection.MALFORMED_EVENT_FIELD,
                null,
                "§4.1's event structure refused these fields as ${refused.reason.name}",
            )
        }
        return BidBuild.Built(event)
    }

    // ---------------------------------------------------------------------------------------------
    // Assembly.
    // ---------------------------------------------------------------------------------------------

    /**
     * One row's tags — none, one or several — spelled from the coordinate, by `TagWriter`, or by the
     * row's own wire form. The `else` refuses rather than emitting nothing, for the reason
     * `ListingWriter.row` gives: a name added to [TAG_ORDER] and not here must not vanish silently.
     *
     * @throws TagException from the tag layer, caught by the caller, which names the row.
     */
    private fun row(name: String, bid: AuthoredBid): List<List<String>> = when (name) {
        // §6: the uppercase pair is the root and the lowercase pair the parent; on a top-level bid
        // both are the listing, so each pair carries the same values.
        BidVocabulary.ROOT_COORDINATE, BidVocabulary.PARENT_COORDINATE ->
            listOf(scope(name, bid.listing.toTagValue(), hintFor(name, bid)))
        BidVocabulary.ROOT_KIND, BidVocabulary.PARENT_KIND ->
            listOf(listOf(name, bid.listing.kind.toString()))
        BidVocabulary.ROOT_AUTHOR, BidVocabulary.PARENT_AUTHOR ->
            listOf(scope(name, bid.listing.pubkey, hintFor(name, bid)))
        "price" -> listOf(TagWriter.price(bid.price))
        "fee" -> optional(feeTag(bid)) { it }
        "deliver_by" -> optional(bid.deliverBy) { TagWriter.timestamp(name, it) }
        "expiration" -> optional(bid.expiration) { TagWriter.timestamp(name, it) }
        // §6's `t=nenya` first, then whatever the author added — wire-visible, and asserted
        // positively by the round-trip test because a reordering is a fixed point of a round trip.
        "t" -> buildList {
            add(TagWriter.topic(TagSet.TOPIC_NENYA))
            for (token in bid.topics) add(TagWriter.topic(token))
        }
        "nenya" -> listOf(TagWriter.version(bid.nenyaVersion))
        "alt" -> optional(bid.alt) { text(name, it) }
        "d" -> optional(bid.dValue) { TagWriter.d(it) }
        "title" -> optional(bid.title) { text(name, it) }
        "summary" -> optional(bid.summary) { text(name, it) }
        "published_at" -> optional(bid.publishedAt) { TagWriter.timestamp(name, it) }
        "status" -> optional(bid.status) { text(name, it) }
        "m" -> optional(bid.mimeType) { text(name, it) }
        "image" -> bid.images.map { it.toTag() }
        "license" -> optional(bid.license) { text(name, it) }
        else -> throw TagException(
            TagRejection.MALFORMED_CONTEXT,
            "`$name` is in this writer's emission order and it has no row here; a tag this writer " +
                "cannot spell is a bid it must not emit",
        )
    }

    /** A NIP-22 scope tag, with its optional relay hint third. */
    private fun scope(name: String, value: String, hint: String?): List<String> =
        if (hint == null) listOf(name, value) else listOf(name, value, hint)

    /** The root pair takes the root hint and the parent pair the parent hint. */
    private fun hintFor(name: String, bid: AuthoredBid): String? =
        if (name == BidVocabulary.ROOT_COORDINATE || name == BidVocabulary.ROOT_AUTHOR) bid.rootRelayHint
        else bid.parentRelayHint

    private fun <T> optional(value: T?, tag: (T) -> List<String>): List<List<String>> =
        if (value == null) emptyList() else listOf(tag(value))

    /** §5.3's `TEXT` encoding: `["<name>", "<text>"]`, with the name one of [TAG_ORDER]'s. */
    private fun text(name: String, value: String): List<String> = listOf(name, value)

    /** §8.1's two arities, or no tag for [FeeTerm.Absent] — and a recipient with no term is refused. */
    private fun feeTag(bid: AuthoredBid): List<String>? {
        if (bid.fee == FeeTerm.Absent) {
            if (bid.feeRecipient != null) {
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§8.1 makes the `fee` recipient a part of the term; an absent term has no wire " +
                        "form at all, so there is nothing for a recipient to belong to",
                )
            }
            return null
        }
        return TagWriter.fee(bid.fee, bid.feeRecipient)
    }

    // ---------------------------------------------------------------------------------------------
    // The checks.
    // ---------------------------------------------------------------------------------------------

    /** A tag handed in as an extension that §6 or §5.3 already names, refused for its own reason. */
    private fun extraTagProblem(bid: AuthoredBid, emitted: List<List<String>>): BidBuild.Refused? {
        val emittedNames = emitted.map { it[0] }.toSet()
        for (tag in bid.extraTags) {
            if (tag.isEmpty()) {
                return refusal(
                    BidRejection.MALFORMED_TAG,
                    null,
                    "§4.3 says a tag is an array of one or more strings and requires rejecting an event " +
                        "carrying an empty tag array",
                )
            }
            val name = tag[0]
            if (name in BidVocabulary.EVENT_ID_SCOPES) return eventIdScoping(name)
            if (name in BidVocabulary.SINGLE_OCCURRENCE) return scopeTagProblem(tag, bid)
            if (name == BidVocabulary.ITEM) return doubleBinding(1)
            if (name in NOT_EMITTABLE) return forbidden(name)
            val spec = NenyaTags.byName(name) ?: continue
            return if (name in emittedNames && spec.cardinality.singleOccurrence) {
                refusal(
                    BidRejection.DUPLICATE_TAG,
                    name,
                    "this bid already carries `$name` from the parameter `AuthoredBid` has for it, so " +
                        "the extra tag is a second occurrence; §5.3 marks that row cardinality " +
                        "${spec.cardinality.token} and §4.3 requires rejecting that rather than " +
                        "resolving it",
                )
            } else {
                refusal(
                    BidRejection.ROW_IS_NOT_AN_EXTENSION,
                    name,
                    "`$name` is a §5.3 row, not an extension: pass the value to the parameter this " +
                        "writer spells that row from, so §5.3's Encoding column is applied to it and it " +
                        "lands in the position §4.1 hashes it in",
                )
            }
        }
        return null
    }

    /**
     * One of §6's six scope tags handed in as an extension: always a second occurrence, which
     * `Bid.decode` refuses, and named for the §6 rule it breaks when it says something different
     * from what the writer spelled from the coordinate.
     *
     * Agreement is judged on the **value** after §4.3's normalisation — a coordinate parsed, a pubkey
     * lowercased — so an uppercase spelling of the same key is a duplicate and not a disagreement. A
     * value that does not parse cannot agree with anything, so it is a disagreement.
     */
    private fun scopeTagProblem(tag: List<String>, bid: AuthoredBid): BidBuild.Refused {
        val name = tag[0]
        val value = tag.getOrNull(1)
        val (agrees, reason, rule) = when (name) {
            BidVocabulary.ROOT_COORDINATE, BidVocabulary.PARENT_COORDINATE -> Triple(
                value != null && parses { Coordinate.parse(value) == bid.listing },
                BidRejection.SCOPE_DISAGREEMENT,
                "a top-level bid has the listing as both root and parent",
            )
            BidVocabulary.ROOT_KIND, BidVocabulary.PARENT_KIND -> Triple(
                value == bid.listing.kind.toString(),
                BidRejection.LISTING_KIND_MISMATCH,
                "`K` and `k` MUST both equal the listing's kind as a decimal string",
            )
            else -> Triple(
                value != null && parses { readPubkeyHex(value, "a `$name` tag's pubkey") == bid.listing.pubkey },
                BidRejection.LISTING_AUTHOR_MISMATCH,
                "`P` and `p` MUST both equal the listing author's pubkey",
            )
        }
        return if (agrees) {
            refusal(
                BidRejection.DUPLICATE_TAG,
                name,
                "this writer already spells `$name` from the coordinate, so the extra tag is a second " +
                    "occurrence; resolving it by first or last would let two implementations disagree " +
                    "about which listing a signed bid was scoped to",
            )
        } else {
            refusal(
                reason,
                name,
                "§6: $rule, and this extra `$name` does not carry the value this writer spelled from " +
                    "the coordinate",
            )
        }
    }

    /** Whether [check] answered true without the tag layer refusing what it was reading. */
    private inline fun parses(check: () -> Boolean): Boolean = try {
        check()
    } catch (refused: TagException) {
        false
    }

    /**
     * The final audit, over the assembled tags — **derived** from `Bid.requiredTags()` and from the
     * scope tags `Bid.decode` refuses a second of, never from a list kept here.
     *
     * Unreachable for every refusal above, and here anyway: it is the one check that is a function of
     * §6's required set rather than of this file, so a tag added there with no parameter here turns
     * every build red — the fail-closed direction.
     */
    private fun auditProblem(tags: List<List<String>>): BidBuild.Refused? {
        val counts = mutableMapOf<String, Int>()
        for (tag in tags) counts[tag[0]] = (counts[tag[0]] ?: 0) + 1
        for (name in Bid.requiredTags()) {
            if ((counts[name] ?: 0) == 0) {
                return refusal(
                    BidRejection.MISSING_REQUIRED_TAG,
                    name,
                    "§6 marks `$name` MUST on a kind:${NenyaKind.PUBLIC_BID} bid and this one would " +
                        "carry none",
                )
            }
        }
        val topics = tags.filter { it[0] == BidVocabulary.TOPIC }.map { it.getOrNull(1)?.lowercase() }
        if (TagSet.TOPIC_NENYA !in topics) {
            return refusal(
                BidRejection.MISSING_TOPIC,
                BidVocabulary.TOPIC,
                "§4.5 and §6 require `[\"${BidVocabulary.TOPIC}\", \"${TagSet.TOPIC_NENYA}\"]` on a bid",
            )
        }
        for (name in BidVocabulary.EVENT_ID_SCOPES) {
            if ((counts[name] ?: 0) > 0) return eventIdScoping(name)
        }
        val items = counts[BidVocabulary.ITEM] ?: 0
        if (items > 0) return doubleBinding(items)
        for (name in NOT_EMITTABLE) {
            if ((counts[name] ?: 0) > 0) return forbidden(name)
        }
        val singles = BidVocabulary.SINGLE_OCCURRENCE +
            NenyaTags.ALL.filter { it.cardinality.singleOccurrence }.map { it.name }
        for (name in singles) {
            val count = counts[name] ?: 0
            if (count > 1) {
                return refusal(
                    BidRejection.DUPLICATE_TAG,
                    name,
                    "this bid would carry $count `$name` tags, and `Bid.decode` refuses a second one " +
                        "rather than resolving it",
                )
            }
        }
        return null
    }

    private fun eventIdScoping(name: String): BidBuild.Refused = refusal(
        BidRejection.SCOPED_BY_EVENT_ID,
        name,
        "§6: a bid MUST scope to a coordinate (`${BidVocabulary.ROOT_COORDINATE}`/" +
            "`${BidVocabulary.PARENT_COORDINATE}`), never to an event id " +
            "(`${BidVocabulary.ROOT_EVENT_ID}`/`${BidVocabulary.PARENT_EVENT_ID}`), because a bid scoped " +
            "by event id detaches the moment the listing is edited; this bid would carry `$name`",
    )

    private fun doubleBinding(count: Int): BidBuild.Refused = refusal(
        BidRejection.DOUBLE_BINDING,
        BidVocabulary.ITEM,
        "§5.3 gives `${BidVocabulary.ITEM}` cardinality 0 on a public bid, where " +
            "`${BidVocabulary.ROOT_COORDINATE}`/`${BidVocabulary.PARENT_COORDINATE}` already carry the " +
            "binding; this bid would carry $count",
    )

    private fun forbidden(name: String): BidBuild.Refused = refusal(
        BidRejection.FORBIDDEN_TAG,
        name,
        "§5.3 says an implementation MUST NOT emit `$name`: Nenya deliverables are digital, and a " +
            "location on a Nenya event is a leak with no compensating function",
    )

    /**
     * §5.3's rows with no encoding — `g` and `location`, whose Encoding cell is an em dash because
     * nothing may be emitted. Derived from [NenyaTags] rather than listed.
     */
    private val NOT_EMITTABLE: List<String> =
        NenyaTags.ALL.filter { it.encoding == TagEncoding.NONE }.map { it.name }

    private fun refusal(
        reason: BidRejection,
        tag: String?,
        detail: String,
        tagReason: TagRejection? = null,
    ): BidBuild.Refused = BidBuild.Refused(reason, tag, tagReason, detail)
}
