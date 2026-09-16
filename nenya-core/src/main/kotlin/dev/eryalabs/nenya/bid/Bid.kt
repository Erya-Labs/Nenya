package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.Coordinate
import dev.eryalabs.nenya.tag.ImageRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagContext
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.tag.TagSet
import dev.eryalabs.nenya.tag.readPubkeyHex
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent

/**
 * A public bid — a NIP-22 `kind:1111` comment scoped to a listing **coordinate** (§6).
 *
 * §17 item 3 requires public bidding and requires it be "the behaviour a user gets without changing
 * a setting", and says an implementation offering only private bidding is **not** conformant. So
 * this is the one codec a conformant implementation cannot omit, and it is why §6 and not §6.1 is
 * what this package reads.
 *
 * ### The door is [decode], and what comes in is a [CheckedEvent]
 *
 * §4.1 requires an implementation reject a received event whose `id` does not match its recomputed
 * value **before any other processing**, and T8 enforces that ordering by type: the only way to
 * obtain a [CheckedEvent] is to have had this library recompute the id. This decoder takes one, so
 * there is no path from a relay's bytes to a `Bid` that skips the check.
 *
 * ### A bid advances nothing, and that is structural rather than merely untested
 *
 * §6: "**A bid is not an acceptance and creates no obligation on anyone.** Only the sealed order
 * proposal and its sealed acceptance bind (§7.4). An implementation MUST NOT advance any order
 * state on the basis of a bid, public or private." §11.2 says the same, and T7's transition
 * function refuses a bid from all eleven states.
 *
 * This package closes the other side of that rule: **no published function here takes or returns
 * anything from `dev.eryalabs.nenya.order`**, and [Bid] is not assignable to an `OrderEvent`. A
 * caller cannot feed a bid to the state machine because there is no value of the right type to
 * feed it, and `BidStructureTest` asserts that by reflection rather than by inspection. The bid
 * layer is where an implementer would breach §6's rule, so it is where the rule is made
 * unrepresentable.
 *
 * ### Two scope statements, said here rather than left to be discovered
 *
 * **Only top-level bids are read.** §6: "A top-level bid has the listing as both root and parent,
 * so the uppercase and lowercase tags carry the same values." This codec asserts that equality —
 * `A` equals `a` and `P` equals `p` — and a NIP-22 **reply to a bid**, whose parent is the bid's
 * event id rather than the listing's coordinate, is therefore refused as
 * [BidRejection.SCOPED_BY_EVENT_ID]. A reply is a legitimate NIP-22 event; it is simply not a Nenya
 * bid, it carries no independent terms, and reading one as a bid would put a price on the board
 * that nobody offered. Threading bids into a conversation is a client's business, not this codec's.
 *
 * **Private bids (§6.1) are not here.** A `kind:16` `type=6` rumor arrives only through gift-wrap
 * machinery this library does not build, T7 already refuses `type=6` from all eleven states, and
 * §6.1 with §17 item 3 makes an implementation supporting public bidding only fully conformant.
 *
 * ### One narrowing stated rather than papered over: exactly one of each scope tag
 *
 * §6 makes `A`, `a`, `K`, `k`, `P` and `p` all REQUIRED and fixes what each must equal. A *second*
 * occurrence of any of them is refused as [BidRejection.DUPLICATE_TAG] rather than resolved. §5.3's
 * table does not contain the five uppercase/NIP-22 rows, so §4.3's duplicate rule — stated over
 * "any tag **this document** marks with cardinality `1` or `0–1`" — does not literally reach them;
 * the rationale it gives does, unchanged. "First wins" and "last wins" are both defensible, which
 * is exactly the problem, because two implementations would then disagree about which listing a
 * signed bid was scoped to. `p` is held to the same rule although §5.3 gives it cardinality `0–n`,
 * because that cardinality is a **listing** rule that §5.3 forbids applying here and §6 states its
 * own: `p` "MUST equal the listing author's pubkey", which a second `p` naming somebody else
 * contradicts.
 *
 * ### What this type does and does not claim
 *
 * It claims the event's id was recomputed (T8), that it carries §6's required tags, that its scope
 * tags agree with each other and with the coordinate they name, and that its values decode under
 * §5.3's Encoding column. It claims **nothing** about the signature: no signature is checked,
 * produced or claimed anywhere in this library — `Secp256k1Ops` answers `Unavailable` — so a `Bid`
 * is a bid somebody published, not a bid a particular key published. §17 permits that omission
 * provided the implementation does not report unverified things as verified, which is why it is
 * said here. It claims nothing about the **listing** either: this codec never saw it, so
 * "`k` agrees with the coordinate" is a statement about the bid's internal consistency and not
 * evidence that a listing of that kind exists, is live (§5.6), or was authored by that key.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class Bid internal constructor(

    /** The id T8 recomputed and found to match the relay's claim (§4.1). */
    public val id: EventId,

    /** The bidder's x-only pubkey, exactly as it appeared in the event (§4.1, and [WireEvent.pubkey]). */
    public val bidderPubkey: String,

    /**
     * §4.1's `created_at`, verbatim.
     *
     * A **claim**, and §4.6 says so: a counterparty's `created_at` orders nothing and decides no
     * deadline. §6 leans on it once — "`deliver_by` is exactly `created_at + 18 h`, which is what
     * the prose says" — and that is an observation about the worked example, not a rule this codec
     * may enforce. Nothing here evaluates a deadline against it.
     */
    public val createdAt: Long,

    /**
     * §5.4's prose, verbatim, carrying **no** machine meaning.
     *
     * §6 makes that explicit and gives the consequence: an implementation MUST derive every term
     * from the tags, "so a bid whose prose and tags disagree is a display defect, never a terms
     * dispute". This codec never reads it, which is how that rule is kept — a parser that never
     * looks cannot be fooled by what it would have found. §6's "`content` MUST be plain text" binds
     * the publisher and the renderer (§5.4, §12.10: no embedded scripting, no auto-fetch); it is
     * not a shape this codec can check, and pretending otherwise would be the §17 over-claim.
     */
    public val content: String,

    /** Every tag, decoded under §5.3's Encoding column and preserved verbatim for [encode] (§4.3). */
    public val tags: TagSet,

    /**
     * §4.2's coordinate of the listing this bid is scoped to — the value both `A` and `a` carry.
     *
     * §6 requires coordinate scoping and gives the reason §4.2 was written for: "A bid scoped by
     * event id detaches the moment the listing is edited."
     */
    public val listing: Coordinate,

    /** §5.3's `price`, REQUIRED on a bid (§6), in millisatoshis. §8.2: what the provider receives. */
    public val price: Msat,

    /** The relay hint on the root scope tag `A`, or `null`. NIP-22's optional third element. */
    public val rootRelayHint: String?,

    /** The relay hint on the parent scope tag `a`, or `null`. */
    public val parentRelayHint: String?,
) {

    /** §6's kind: NIP-22 `kind:1111`, always. */
    public val kind: Int get() = NenyaKind.PUBLIC_BID

    /** The listing's kind, the value §6 requires `K` and `k` to carry as a decimal string. */
    public val listingKind: Int get() = listing.kind

    /** The listing author's pubkey, the value §6 requires `P` and `p` to carry (§4.3, normalised). */
    public val listingAuthorPubkey: String get() = listing.pubkey

    /** §5.3's `expiration` (NIP-40), in Unix seconds, or `null`. §6 makes it a SHOULD. */
    public val expiration: Long? get() = tags.expiration

    /**
     * §6: "A bid with no expiration is an open-ended commitment and implementations SHOULD render
     * it as such."
     *
     * A flag rather than a refusal, and that distinction is the SHOULD/MUST line §6 actually draws.
     * A codec written from memory rather than from §6 makes `expiration` REQUIRED and refuses every
     * open-ended bid on the board — conformant bids, refused by an implementation that looks
     * correct doing it.
     */
    public val openEnded: Boolean get() = expiration == null

    /**
     * §5.3's `deliver_by`, in Unix seconds, or `null`.
     *
     * §6: "A bid's `deliver_by`, when present, is a deadline in the same sense as every other
     * deadline in this document (§4.6), and MUST be evaluated against the injected clock."
     * Evaluating it is the caller's, because a bid binds nothing: the deadline becomes load-bearing
     * only once it is copied into accepted terms (§5.3, §7.4), and that is where the clock belongs.
     * What this codec owes the rule is that the value is never compared against [createdAt], and it
     * is not — nothing here reads a clock at all.
     */
    public val deliverBy: Long? get() = tags.deliverBy

    /**
     * §8.1's `fee` term, optional on a bid (§6).
     *
     * §6: "A bid MAY carry `fee`. If it does, that fee term is the one the bidder is willing to
     * transact under, and any later divergence MUST abort the order (§8.4)." [FeeTerm.Absent] and a
     * stated `["fee", "0"]` are different values here as everywhere else (§8.1).
     *
     * Nothing in the range `0..10000` is refused for being large. §8.1 forbids that on read and
     * requires a client's own lower limit to surface as its own named local-policy condition, which
     * this library does not hold — the same rule T9 enforces one layer down, reached here by a
     * second caller.
     */
    public val fee: FeeTerm get() = tags.fee

    /** The `fee` tag's recipient pubkey, present exactly when the term names one (§8.1). */
    public val feeRecipient: String? get() = tags.feeRecipient

    /** §5.3's `alt` (NIP-31), or `null`. §6 makes it a SHOULD — see [Bid] on §15's interop claim. */
    public val alt: String? get() = tags.alt

    /** §4.5's `nenya` version. REQUIRED on a bid (§6); deciding whether to implement it is §4.5's. */
    public val nenyaVersion: Int? get() = tags.nenyaVersion

    /** §5.3's `t` tokens, verbatim and in order. Includes `nenya`; see [TagSet.normalisedTopics]. */
    public val topics: List<String> get() = tags.topics

    /** §5.3's `p` tags as the tag codec decoded them. Exactly one on a bid, the listing author's. */
    public val pubkeyRefs: List<PubkeyRef> get() = tags.pubkeyRefs

    /** §5.3's `image` tags, in order. Each is an `https:` URL; §12.10 forbids auto-fetching them. */
    public val images: List<ImageRef> get() = tags.images

    /**
     * The tags §5.3's table does not name, verbatim and in order (§4.3). Preserved by [encode].
     *
     * On a bid that **includes §6's own scope tags** — `A`, `a`, `K`, `k` and `P` — because §5.3's
     * table is the listing vocabulary and NIP-22's uppercase rows are not in it. They are read by
     * this codec and are also here, which is not a contradiction: "unknown" names the table they
     * are absent from, and §4.3's round-trip guarantee is what keeps them byte-exact on the way
     * back out. Only `p` is in §5.3's table, so only `p` is missing from this list.
     */
    public val unknownTags: List<List<String>> get() = tags.unknownTags

    /**
     * The event this bid was decoded from, byte for byte — same tags, same order, same id.
     *
     * §4.3 requires unknown tags be preserved verbatim when re-publishing an event this
     * implementation did not author, and states the consequence itself: dropping one changes the
     * id. `BidPropertyTest` proves this the way the rule states it, by recomputing the id through
     * T8 over ten thousand generated bids rather than by comparing lists.
     */
    public fun encode(): WireEvent =
        WireEvent(bidderPubkey, createdAt, kind, tags.republish(), content)

    /**
     * Names the kind it bids on and nothing the bidder wrote.
     *
     * A bid is a public event and none of this is secret, so this is convention rather than §12:
     * every type in this library redacts by default, because the one that does not is the one that
     * later holds a coordinate somebody pasted into a log. [listing] and [price] are there for the
     * caller that genuinely needs them.
     */
    override fun toString(): String = "Bid(listingKind=$listingKind, openEnded=$openEnded)"

    public companion object {

        /**
         * Decode a §6 public bid from an id-checked event.
         *
         * §5.3's Card. and Requirement columns are **not** applied: §5.3 says they state what a
         * listing requires and "MUST NOT be applied to a bid or to an order rumor, which carry
         * their own required sets". §6's set is [requiredTags]. The encoding column *is* applied,
         * because §5.3 makes it universal, and §4.3's duplicate rule with it.
         *
         * @param limits §4.3's fifth resource bound — 64 `image` tags — injected because §4.3 says
         *   the bounds SHOULD be configurable. Exceeding it is a rejection, never a truncation.
         * @throws BidException naming which §6, §5.3 or §4.3 rule refused the event, and the tag it
         *   was about. The tag layer's own [dev.eryalabs.nenya.tag.TagRejection] is preserved on the
         *   cause.
         */
        public fun decode(event: CheckedEvent, limits: TagLimits = TagLimits.DEFAULT): Bid {
            val kind = event.event.kind
            if (kind != NenyaKind.PUBLIC_BID) {
                throw BidException(
                    BidRejection.NOT_A_BID_KIND,
                    null,
                    "§6 defines a public bid as a NIP-22 kind:${NenyaKind.PUBLIC_BID} comment; this " +
                        "event is kind:$kind, and §6's required set MUST NOT be applied to a " +
                        "listing or to an order rumor",
                )
            }
            val raw = event.event.tags
            val tags = try {
                TagSet.read(event, TagContext.publicBid(), limits)
            } catch (refused: TagException) {
                throw refused.asBidRejection(raw)
            }

            refuseEventIdScoping(raw)
            refuseDuplicateScopeTags(raw)
            val listing = coordinate(raw)
            checkListingKind(raw, listing)
            checkListingAuthor(raw, listing)

            // §6's three term MUSTs. The tag codec has decoded them where they are present and has
            // deliberately not required them, because requiring them is a listing rule (§5.3).
            val price = tags.price ?: throw missing(BidVocabulary.PRICE)
            checkDiscoveryTopic(tags)
            if (tags.nenyaVersion == null) throw missing(BidVocabulary.VERSION)

            return Bid(
                id = event.id,
                bidderPubkey = event.event.pubkey,
                createdAt = event.event.createdAt,
                content = event.event.content,
                tags = tags,
                listing = listing,
                price = price,
                rootRelayHint = relayHint(raw, BidVocabulary.ROOT_COORDINATE),
                parentRelayHint = relayHint(raw, BidVocabulary.PARENT_COORDINATE),
            )
        }

        /**
         * The tags §6 marks MUST on a bid — six NIP-22 scope tags and three term tags.
         *
         * Published because it is the set this decoder enforces, a caller building a bid needs the
         * same one, and `BidCodecTest` walks it removing each in turn, so the proof that the decoder
         * requires them is a loop over a published list rather than over one kept in sync by hand.
         *
         * Unlike `Listing.requiredTags` this is **not** derived from §5.3's Requirement column, and
         * that is the rule rather than an omission: §5.3 says that column is a listing rule and MUST
         * NOT be applied here. §6 states the bid's set in prose, so it is transcribed once, in
         * `BidVocabulary`. A codec that derived it from §5.3 would require `d`, `title` and `alt` on
         * a bid and reject §6's own worked example.
         */
        public fun requiredTags(): List<String> = BidVocabulary.REQUIRED

        /**
         * §6: "A bid MUST scope to a coordinate (`A`/`a`), never to an event id (`E`/`e`)."
         *
         * Checked **first**, and refused with its own reason rather than as a missing `a` tag. §6
         * gives the rationale — a bid scoped by event id detaches the moment the listing is edited —
         * and the two rejections are what an implementer conflates: one reads as "the peer forgot
         * something", the other as "the peer used the scheme §4.2 exists to replace".
         *
         * A bid carrying `A`/`a` **and** an `E`/`e` tag is refused too, and that is the case worth
         * naming because it is the one a conformant generic NIP-22 client actually emits: NIP-22
         * permits pinning the exact *version* of an addressable root with an `E` beside the `A`, so
         * this rule refuses a peer whose terms are entirely §6-conformant. §6 says "never to an
         * event id" without an exception for the pin, and the specification is the contract — half a
         * bid scoped by event id is still a reference that detaches the moment the listing is edited,
         * which is precisely what §6 gives as the reason. Picking the coordinate and ignoring the
         * rest would be resolving an ambiguity the publisher created, which is the shape §4.3's
         * duplicate rule refuses everywhere else. The rarer case, a NIP-22 reply to a bid, lands
         * here for the same reason: its parent is the bid's event id.
         */
        private fun refuseEventIdScoping(tags: List<List<String>>) {
            for (name in BidVocabulary.EVENT_ID_SCOPES) {
                if (tags.any { it[0] == name }) {
                    throw BidException(
                        BidRejection.SCOPED_BY_EVENT_ID,
                        name,
                        "§6: a bid MUST scope to a coordinate (`${BidVocabulary.ROOT_COORDINATE}`/" +
                            "`${BidVocabulary.PARENT_COORDINATE}`), never to an event id " +
                            "(`${BidVocabulary.ROOT_EVENT_ID}`/`${BidVocabulary.PARENT_EVENT_ID}`), " +
                            "because a bid scoped by event id detaches the moment the listing is " +
                            "edited; this event carries `$name`",
                    )
                }
            }
        }

        /** See [Bid] on why §4.3's rationale reaches NIP-22's rows although §5.3's table does not. */
        private fun refuseDuplicateScopeTags(tags: List<List<String>>) {
            for (name in BidVocabulary.SINGLE_OCCURRENCE) {
                val count = tags.count { it[0] == name }
                if (count > 1) {
                    throw BidException(
                        BidRejection.DUPLICATE_TAG,
                        name,
                        "§6 fixes what `$name` carries on a bid and this event carries $count of " +
                            "them; §4.3's rationale applies unchanged — resolving it by first, last " +
                            "or smallest would let two implementations disagree about which listing " +
                            "a signed bid was scoped to",
                    )
                }
            }
        }

        /**
         * §4.2's coordinate, taken from `A` and cross-checked against `a`.
         *
         * The two are compared as **parsed coordinates** rather than as strings, so the optional
         * NIP-22 relay hint may differ between them — it is a hint about where to find the event
         * and not part of its address (§4.2) — while the address itself may not.
         */
        private fun coordinate(tags: List<List<String>>): Coordinate {
            val root = parseCoordinate(tags, BidVocabulary.ROOT_COORDINATE)
            val parent = parseCoordinate(tags, BidVocabulary.PARENT_COORDINATE)
            if (root != parent) {
                throw BidException(
                    BidRejection.SCOPE_DISAGREEMENT,
                    BidVocabulary.PARENT_COORDINATE,
                    "§6: a top-level bid has the listing as both root and parent, so " +
                        "`${BidVocabulary.ROOT_COORDINATE}` and " +
                        "`${BidVocabulary.PARENT_COORDINATE}` carry the same coordinate; these name " +
                        "two different listings, which is a NIP-22 reply shape and not a Nenya bid",
                )
            }
            if (root.kind !in NenyaKind.LISTING_KINDS) {
                throw BidException(
                    BidRejection.NOT_A_LISTING_COORDINATE,
                    BidVocabulary.ROOT_COORDINATE,
                    "§6 scopes a bid to a listing — a request or, as a counter-offer, an offer — and " +
                        "this coordinate names kind:${root.kind}, which is not one of " +
                        "${NenyaKind.LISTING_KINDS.sorted()}",
                )
            }
            return root
        }

        /** §6: `K` and `k` "MUST both equal the listing's kind as a decimal string". */
        private fun checkListingKind(tags: List<List<String>>, listing: Coordinate) {
            val canonical = listing.kind.toString()
            for (name in listOf(BidVocabulary.ROOT_KIND, BidVocabulary.PARENT_KIND)) {
                val stated = value(tags, name)
                if (stated != canonical) {
                    throw BidException(
                        BidRejection.LISTING_KIND_MISMATCH,
                        name,
                        "§6 requires `${BidVocabulary.ROOT_KIND}` and `${BidVocabulary.PARENT_KIND}` " +
                            "both equal the listing's kind as a decimal string; the coordinate names " +
                            "kind:${listing.kind} and `$name` does not carry that decimal string",
                    )
                }
            }
        }

        /**
         * §6: `P` and `p` "MUST both be present and MUST both equal the listing author's pubkey".
         *
         * Compared after §4.3's accept-and-normalise rule has run on both sides, so a peer that
         * wrote the pubkey in uppercase in one tag and lowercase in another is not refused for it:
         * §4.3's no-normalisation exception list has exactly two entries and names neither.
         */
        private fun checkListingAuthor(tags: List<List<String>>, listing: Coordinate) {
            for (name in listOf(BidVocabulary.ROOT_AUTHOR, BidVocabulary.PARENT_AUTHOR)) {
                val stated = try {
                    readPubkeyHex(value(tags, name), "a `$name` tag's pubkey")
                } catch (refused: TagException) {
                    throw refused.asBidRejection(tags)
                }
                if (stated != listing.pubkey) {
                    throw BidException(
                        BidRejection.LISTING_AUTHOR_MISMATCH,
                        name,
                        "§6 requires `${BidVocabulary.ROOT_AUTHOR}` and " +
                            "`${BidVocabulary.PARENT_AUTHOR}` both equal the listing author's " +
                            "pubkey; `$name` names a different key from the one the coordinate does",
                    )
                }
            }
        }

        /** §6: "A bid MUST carry `price`, `t=nenya` and `nenya`" — the discovery token half (§4.5). */
        private fun checkDiscoveryTopic(tags: TagSet) {
            if (tags.topics.isEmpty()) throw missing(BidVocabulary.TOPIC)
            if (TagSet.TOPIC_NENYA !in tags.normalisedTopics) {
                throw BidException(
                    BidRejection.MISSING_TOPIC,
                    BidVocabulary.TOPIC,
                    "§4.5 and §6 require `[\"${BidVocabulary.TOPIC}\", \"${TagSet.TOPIC_NENYA}\"]` " +
                        "on a bid; §5.3's further `wtb`/`wts` rule is a listing rule and is NOT " +
                        "applied here, so a single `${TagSet.TOPIC_NENYA}` token is correct",
                )
            }
        }

        /** The single value element of a §6 scope tag, or the refusal naming that tag as missing. */
        private fun value(tags: List<List<String>>, name: String): String {
            val tag = tags.firstOrNull { it[0] == name } ?: throw missing(name)
            if (tag.size < VALUE_ELEMENTS) throw missing(name)
            return tag[1]
        }

        /** NIP-22's optional relay hint, the third element of a scope tag. */
        private fun relayHint(tags: List<List<String>>, name: String): String? =
            tags.firstOrNull { it[0] == name }?.getOrNull(HINT_ELEMENT)

        private fun parseCoordinate(tags: List<List<String>>, name: String): Coordinate = try {
            Coordinate.parse(value(tags, name))
        } catch (refused: TagException) {
            throw refused.asBidRejection(tags)
        }

        private fun missing(tag: String): BidException = BidException(
            BidRejection.MISSING_REQUIRED_TAG,
            tag,
            "§6 marks `$tag` MUST on a kind:${NenyaKind.PUBLIC_BID} bid and this event carries no " +
                "readable one",
        )

        /** A scope tag carries a name and a value; anything shorter carries no value at all. */
        private const val VALUE_ELEMENTS: Int = 2

        /** NIP-22's optional relay hint sits third, after the name and the value. */
        private const val HINT_ELEMENT: Int = 2
    }
}
