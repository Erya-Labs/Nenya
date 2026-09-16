package dev.eryalabs.nenya.tag

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.MoneyException
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.wire.CheckedEvent

/**
 * §4.3's fifth resource bound, the one T8 deliberately left for this package.
 *
 * T8 enforces the four bounds that are properties of an event — serialised size, tag count, tag
 * value size, `content` size — and stops there, because §4.3's fifth names a tag from §5.3's
 * vocabulary and the wire layer knows no tag meanings at all. §4.3 requires the bound be enforced
 * as reject-never-truncate and says the bounds SHOULD be configurable, so it is injected with
 * §4.3's published default.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public class TagLimits(

    /** §4.3's 64-`image`-tags bound. */
    public val maxImageTags: Int = DEFAULT_MAX_IMAGE_TAGS,
) {

    init {
        if (maxImageTags < 1) {
            throw TagException(
                TagRejection.NON_POSITIVE_LIMIT,
                "maxImageTags is $maxImageTags; §4.3 requires a bound, and a bound below one " +
                    "refuses every listing rather than bounding anything",
            )
        }
    }

    override fun toString(): String = "TagLimits(maxImageTags=$maxImageTags)"

    public companion object {

        /** 64 — §4.3's default bound on the number of `image` tags in one event. */
        public const val DEFAULT_MAX_IMAGE_TAGS: Int = 64

        /** §4.3's published default, and what [TagSet.read] uses when given none. */
        public val DEFAULT: TagLimits = TagLimits()
    }
}

/**
 * The §5.3 tags of one event, read under a declared [TagContext] and decoded by §5.3's Encoding
 * column — together with every tag this codec did not recognise, kept verbatim.
 *
 * ### Which of §5.3's rules apply where
 *
 * §5.3 splits its own table and this reader acts on the split:
 *
 * - **Encoding is universal.** Every tag named in §5.3 is decoded by exactly that encoding
 *   wherever it appears — on a listing, on a `kind:1111` bid, on a private bid, on an order
 *   rumor. A malformed `price` is a rejection on all four.
 * - **Requirement and Card. are listing rules.** The MUST/SHOULD/MAY column and the `1` / `0–1` /
 *   `≥2` / `0–n` / `0` column state what a `kind:30402`, `kind:30403` or `kind:30404` listing
 *   requires, and §5.3 says they MUST NOT be applied to a bid or an order rumor. §5.3 also says
 *   what happens if they are: "Applying the listing rules everywhere rejects this document's own
 *   bid example", which carries a single `["t", "nenya"]` and no `wtb`/`wts`.
 *
 * ### The one clause that crosses the line, said out loud
 *
 * §4.3's **duplicate** rule is enforced in every context, not only on listings. §4.3 states it
 * over "any tag this document marks with cardinality `1` or `0–1`" and gives a rationale that is
 * about signed events rather than about listings: "First wins" and "last wins" are both
 * defensible, which is exactly the problem, because two conformant implementations would then
 * disagree about the price of the same signed event. A `kind:16` `type=1` proposal carrying two
 * different `amount`-bearing `price` tags has that same defect, and §7.5 independently forbids a
 * second `fee` tag on a proposal. So the *presence* rules stay behind the listing gate and the
 * *duplicate* rule does not. This is a reading of §4.3 rather than a quotation of §5.3, which is
 * why it is written here rather than left for someone to infer from the code.
 *
 * ### Unknown tags survive
 *
 * §4.3 requires unknown tags be ignored on read and preserved verbatim when an implementation
 * round-trips an event it did not author, because dropping one silently strips an extension **and
 * changes the event id**. [republish] is that guarantee, and `TagPropertyTest` proves it the way
 * §4.3 states the consequence: it re-computes the id through T8 and asserts it is unchanged.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class TagSet internal constructor(

    /** The event this was read under (§5.3's "a shared tag validator MUST take the kind"). */
    public val context: TagContext,

    /** Every tag, in the order given, verbatim — known and unknown alike. Unmodifiable. */
    private val allTags: List<List<String>>,

    /** §5.3's `d` — the addressability key, opaque and non-empty. */
    public val dValue: String?,

    /** §5.3's `title`. */
    public val title: String?,

    /** §5.3's `summary`. */
    public val summary: String?,

    /** §5.3's `published_at`, in Unix seconds (§4.3). */
    public val publishedAt: Long?,

    /**
     * §5.3's `price`, normalised to millisatoshis (§4.4).
     *
     * The tag is satoshi-denominated on the wire — "On the wire, in public listing and bid tags,
     * prices are **integer satoshis**" — so a value that does not land on a whole satoshi is
     * refused as [TagRejection.LOSSY] rather than rounded, whichever of the eight unit tokens it
     * arrived under. §8.2: this is what the **provider receives**.
     */
    public val price: Msat?,

    /**
     * §5.3's `status`, as the raw token.
     *
     * Deliberately **not** interpreted here. §5.2 gives the listing vocabulary — `active`,
     * `awarded`, `fulfilled`, `cancelled` on a request, `active` and `sold` only on an offer —
     * and §11.1 says one `status` codec MUST NOT serve both it and the order-state vocabulary,
     * which shares the single token `cancelled` with a different meaning. The listing codec owns
     * that reading; this row's Encoding cell is `["status", "<value>"]` and nothing more.
     */
    public val status: String?,

    /** §5.3's `t` tokens, in order, **verbatim**. See [normalisedTopics] for the matching form. */
    public val topics: List<String>,

    /** §5.3's `m` — the desired or produced MIME type of the deliverable, NIP-94 semantics. */
    public val mimeType: String?,

    /** §5.3's `image` tags, in order, each an `https:` URL (§5.3, §12.10). */
    public val images: List<ImageRef>,

    /** §5.3's `expiration` (NIP-40), in Unix seconds. Enforced by the consumer (§5.6), not here. */
    public val expiration: Long?,

    /** §5.3's `alt` — NIP-31 fallback rendering. MUST on a request (§5.2). */
    public val alt: String?,

    /** §4.5's `nenya` version. §4.5's "ignore an event whose version I do not implement" is the caller's. */
    public val nenyaVersion: Int?,

    /** §5.3's `p` tags, in order — a directed listing, or §7.4's counterparty on a rumor. */
    public val pubkeyRefs: List<PubkeyRef>,

    /**
     * §5.3's and §8.1's `fee` term.
     *
     * [FeeTerm.Absent] when there is no `fee` tag, which is §8.1's read rule — a missing `fee`
     * MUST be read as zero fee and MUST NOT be refused as incomplete terms — and **not** the same
     * value as a stated `["fee", "0"]`. §8.1 says a proposal SHOULD carry the explicit zero "so
     * that the absence of a fee is itself a signed statement", and a codec that collapsed the two
     * would destroy exactly that distinction.
     */
    public val fee: FeeTerm,

    /**
     * The `fee` tag's third element — the recipient x-only pubkey — which `FeeTerm` deliberately
     * does not hold, because validating it belongs to this codec (§8.1, and `FeeTerm`'s own KDoc).
     *
     * Present exactly when the term names a recipient, which §8.1 makes the same thing as basis
     * points above zero.
     */
    public val feeRecipient: String?,

    /** §5.3's `license`. Vocabulary is `OPEN-5`, so the token is carried and not interpreted. */
    public val license: String?,

    /** §5.3's `deliver_by`, in Unix seconds. Advisory on a listing; binding in accepted terms. */
    public val deliverBy: Long?,

    /** §5.3's `item` — the listing coordinate and quantity a proposal or a private bid carries. */
    public val item: ItemRef?,

    /** The tags this codec did not recognise, verbatim and in order (§4.3). */
    public val unknownTags: List<List<String>>,
) {

    /**
     * [topics] lowercased, for the case-insensitive matching §5.3 asks for on read.
     *
     * §5.3 requires implementations to lowercase on write and SHOULD match case-insensitively on
     * read, "because relay tag indexes are byte-exact" — so a `["t", "Nenya"]` a peer published is
     * invisible to §5.5's `{"#t": ["nenya"]}` filter and must still be understood once it arrives
     * by another route.
     */
    public val normalisedTopics: List<String> = topics.map { it.lowercase() }

    /**
     * §4.3's round-trip guarantee: **every** tag, in the order given, byte for byte.
     *
     * §4.3 requires an implementation not drop tags it does not understand when re-publishing
     * another party's event, and states the consequence itself — doing so "silently strips
     * extensions and changes the event id". So nothing here is re-encoded, not even a tag this
     * codec read and normalised: lowercasing a legitimately-uppercase pubkey would change the id
     * of somebody else's signed event, and this library would then be publishing an event whose
     * signature no longer verifies.
     *
     * [TagWriter] is the other half — the canonical encoding, for events this client authors.
     */
    public fun republish(): List<List<String>> = allTags

    /** Every occurrence of [name], verbatim. For a caller reading a tag this codec does not model. */
    public fun occurrences(name: String): List<List<String>> = allTags.filter { it[0] == name }

    /**
     * Names the context and two counts, and no tag value at all.
     *
     * A listing's tags are public; a `kind:16` rumor's are not. Its `item` coordinate is the
     * listing id, its `p` tag is the counterparty pubkey and its `order` tag is the order id —
     * three of the values §12 item 11 and STOP RULE 14 forbid in "the string representation of
     * anything the implementation exposes". One type reads both, so it redacts.
     */
    override fun toString(): String =
        "TagSet(context=$context, tags=${allTags.size}, unknown=${unknownTags.size})"

    public companion object {

        /** §4.5's discovery token, and §5.3's "MUST include `nenya`". */
        public const val TOPIC_NENYA: String = "nenya"

        /** §5.2's want-to-buy side token, REQUIRED on a request. */
        public const val TOPIC_WTB: String = "wtb"

        /** §5.1's want-to-sell side token, REQUIRED on an offer. */
        public const val TOPIC_WTS: String = "wts"

        /**
         * Read the §5.3 tags of [event] under [context].
         *
         * The input is a T8 [CheckedEvent] and never a bare `WireEvent`: §4.1 says an
         * implementation MUST reject a received event whose `id` does not match its recomputed
         * value **before any other processing**, and the way to enforce an ordering is to make
         * the later step take a type only the earlier step can produce. This is that later step.
         *
         * @throws TagException naming which §4.3, §4.4, §5.3 or §8.1 rule refused the event.
         */
        public fun read(
            event: CheckedEvent,
            context: TagContext,
            limits: TagLimits = TagLimits.DEFAULT,
        ): TagSet {
            if (event.event.kind != context.kind) {
                throw TagException(
                    TagRejection.KIND_MISMATCH,
                    "the context declares kind ${context.kind} and the event carries kind " +
                        "${event.event.kind}; §5.3's cardinality and requirement rules are a " +
                        "function of the kind, so reading one under the other applies the wrong set",
                )
            }
            val tags = event.event.tags
            val counts = mutableMapOf<String, Int>()
            for (tag in tags) counts[tag[0]] = (counts[tag[0]] ?: 0) + 1

            checkDuplicates(counts)
            if (context.isListing) checkListingRules(context, counts)
            checkItemRules(context, counts)
            checkImageBound(counts, limits)

            return decode(context, tags)
        }

        /**
         * §4.3's duplicate rule, in **every** context. See this class's note on why this one
         * clause is not behind the listing gate.
         *
         * `item` is included although its cardinality is `0` rather than `1` or `0–1`, because
         * §5.3's `item` row says so in its own words: "more than one is a rejection everywhere
         * (§4.3, duplicate tags)".
         */
        private fun checkDuplicates(counts: Map<String, Int>) {
            for (spec in NenyaTags.ALL) {
                val count = counts[spec.name] ?: 0
                val single = spec.cardinality.singleOccurrence || spec.name == "item"
                if (single && count > 1) {
                    throw TagException(
                        TagRejection.DUPLICATE_TAG,
                        "`${spec.name}` is marked cardinality ${spec.cardinality.token} and appears " +
                            "$count times; §4.3 requires rejecting that rather than resolving it by " +
                            "taking the first, the last or the smallest",
                    )
                }
            }
        }

        /** §5.3's Requirement and Card. columns, which apply **only** to a listing. */
        private fun checkListingRules(context: TagContext, counts: Map<String, Int>) {
            for (spec in NenyaTags.ALL) {
                val count = counts[spec.name] ?: 0
                when (spec.requirement.on(context)) {
                    TagRequirement.MUST ->
                        if (count == 0) {
                            throw TagException(
                                TagRejection.MISSING_REQUIRED,
                                "§5.3 marks `${spec.name}` ${spec.requirement.cell} and this " +
                                    "listing carries none",
                            )
                        }
                    TagRequirement.MUST_NOT ->
                        if (count > 0) {
                            throw TagException(
                                TagRejection.FORBIDDEN_TAG,
                                "§5.3 marks `${spec.name}` MUST NOT on a listing and this one " +
                                    "carries $count",
                            )
                        }
                    TagRequirement.SHOULD, TagRequirement.MAY -> Unit
                }
                if (spec.cardinality == TagCardinality.TWO_OR_MORE && count < 2) {
                    throw TagException(
                        TagRejection.CARDINALITY_TOO_FEW,
                        "§5.3 marks `${spec.name}` cardinality ${spec.cardinality.token} on a " +
                            "listing and this one carries $count",
                    )
                }
            }
        }

        /**
         * §5.3's `item` row states its own cardinality by context, and it is the only row that
         * does: exactly `1` on a `kind:16` `type=1` proposal (§7.5) and on a `type=6` private bid
         * (§6.1); `0` on a public `kind:1111` bid, "where `A`/`a` already carry the binding and a
         * second binding that could disagree with the first is precisely what §4.2 exists to
         * prevent"; and `0` on a listing, which cannot reference itself.
         *
         * The listing half is already covered by the MUST NOT above; this adds the other two.
         */
        private fun checkItemRules(context: TagContext, counts: Map<String, Int>) {
            val count = counts["item"] ?: 0
            if (context.requiresItem && count != 1) {
                throw TagException(
                    if (count == 0) TagRejection.MISSING_REQUIRED else TagRejection.DUPLICATE_TAG,
                    "§5.3 requires exactly one `item` tag on a kind:${context.kind} " +
                        "type=${context.orderMessageType}; this one carries $count",
                )
            }
            if (context.forbidsItem && count > 0) {
                throw TagException(
                    TagRejection.FORBIDDEN_TAG,
                    "§5.3 gives `item` cardinality 0 on kind ${context.kind}; this one carries $count",
                )
            }
        }

        /** §4.3's fifth bound: reject, never truncate. */
        private fun checkImageBound(counts: Map<String, Int>, limits: TagLimits) {
            val count = counts["image"] ?: 0
            if (count > limits.maxImageTags) {
                throw TagException(
                    TagRejection.LIMIT_EXCEEDED,
                    "the event carries $count `image` tags and the bound is ${limits.maxImageTags}; " +
                        "§4.3 requires rejecting rather than truncating",
                )
            }
        }

        /**
         * §5.3's Encoding column, applied to every recognised tag in every context.
         *
         * The dispatch is on [TagSpec.encoding] rather than on the tag name, because that column
         * is the universal half of §5.3 and a second tag arriving with an existing encoding
         * should reach the same codec by construction rather than by a reviewer remembering to
         * add a branch. The tags whose encoding carries no further structure — text and
         * timestamps — are collected by name into a map for the same reason: a `when (name)`
         * nested inside a `when (encoding)` needs an `else` branch that cannot be reached, and an
         * unreachable branch is where the next tag row quietly lands.
         */
        private fun decode(context: TagContext, tags: List<List<String>>): TagSet {
            val texts = mutableMapOf<String, String>()
            val timestamps = mutableMapOf<String, Long>()
            var dValue: String? = null
            var price: Msat? = null
            val topics = mutableListOf<String>()
            val images = mutableListOf<ImageRef>()
            var nenyaVersion: Int? = null
            val pubkeyRefs = mutableListOf<PubkeyRef>()
            var fee: FeeTerm = FeeTerm.Absent
            var feeRecipient: String? = null
            var item: ItemRef? = null
            val unknown = mutableListOf<List<String>>()

            for (tag in tags) {
                val name = tag[0]
                val spec = NenyaTags.byName(name)
                if (spec == null) {
                    // §4.3: ignored on read, preserved verbatim on the way back out.
                    unknown += tag
                    continue
                }
                when (spec.encoding) {
                    TagEncoding.OPAQUE -> dValue = readOpaque(tag)
                    TagEncoding.TEXT -> texts[name] = readText(tag)
                    TagEncoding.TIMESTAMP ->
                        timestamps[name] = readTimestamp(value(tag, "a `$name` tag"), "a `$name` value")
                    TagEncoding.PRICE -> price = readPrice(tag)
                    TagEncoding.FEE -> {
                        val term = readFee(tag)
                        fee = term.first
                        feeRecipient = term.second
                    }
                    TagEncoding.TOPIC -> topics += readTopic(tag)
                    TagEncoding.IMAGE -> images += readImage(tag)
                    TagEncoding.PUBKEY_REF -> pubkeyRefs += readPubkeyRef(tag)
                    TagEncoding.ITEM -> item = readItem(tag)
                    TagEncoding.VERSION -> nenyaVersion = readVersion(tag)
                    // `g` and `location` have no encoding — §5.3 prints an em dash, because
                    // nothing may be emitted. On a listing they were refused above; elsewhere
                    // §5.3 only says an implementation SHOULD warn, so the value is carried
                    // through untouched rather than parsed into a location this library would
                    // then be holding.
                    TagEncoding.NONE -> Unit
                }
            }

            if (context.isListing) checkListingTopics(context, topics)

            return TagSet(
                context = context,
                allTags = tags,
                dValue = dValue,
                title = texts["title"],
                summary = texts["summary"],
                publishedAt = timestamps["published_at"],
                price = price,
                status = texts["status"],
                topics = topics.toList(),
                mimeType = texts["m"],
                images = images.toList(),
                expiration = timestamps["expiration"],
                alt = texts["alt"],
                nenyaVersion = nenyaVersion,
                pubkeyRefs = pubkeyRefs.toList(),
                fee = fee,
                feeRecipient = feeRecipient,
                license = texts["license"],
                deliverBy = timestamps["deliver_by"],
                item = item,
                unknownTags = unknown.toList(),
            )
        }

        /**
         * §5.3's `t` rule for a listing: "MUST include `nenya` and exactly one of `wtb` / `wts`".
         *
         * Matched case-insensitively, per the same cell. A listing carrying both side tokens is
         * refused as well as one carrying neither — "exactly one" is a claim about which side of
         * the board the event is on, and an event on both sides cannot be rendered by §5.5's
         * filters or acted on by a counterparty.
         */
        private fun checkListingTopics(context: TagContext, topics: List<String>) {
            val normalised = topics.map { it.lowercase() }
            if (TOPIC_NENYA !in normalised) {
                throw TagException(
                    TagRejection.MISSING_TOPIC,
                    "§4.5 and §5.3 require `[\"t\", \"$TOPIC_NENYA\"]` on every public Nenya event",
                )
            }
            val sides = normalised.count { it == TOPIC_WTB || it == TOPIC_WTS }
            if (sides != 1) {
                throw TagException(
                    TagRejection.MISSING_TOPIC,
                    "§5.3 requires exactly one of `$TOPIC_WTB` / `$TOPIC_WTS` on a listing; this " +
                        "kind:${context.kind} carries $sides",
                )
            }
        }

        /** The single value element of a two-element tag, or [TagRejection.WRONG_ARITY]. */
        private fun value(tag: List<String>, what: String): String {
            if (tag.size < 2) {
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "$what carries a name and a value; this one carries ${tag.size} element(s)",
                )
            }
            return tag[1]
        }

        private fun readText(tag: List<String>): String = value(tag, "a `${tag[0]}` tag")

        private fun readOpaque(tag: List<String>): String {
            val d = value(tag, "a `d` tag")
            if (d.isEmpty()) {
                throw TagException(TagRejection.EMPTY_VALUE, "§5.3 says a `d` value MUST be non-empty")
            }
            return d
        }

        private fun readTopic(tag: List<String>): String {
            val token = value(tag, "a `t` tag")
            if (token.isEmpty()) {
                throw TagException(TagRejection.EMPTY_VALUE, "a `t` token is not the empty string")
            }
            return token
        }

        /**
         * §4.4's `price`, permissive on read.
         *
         * A fourth element is the optional NIP-99 `<frequency>` recurring-price form, which §4.4
         * says MUST NOT be emitted by Nenya v1 and "MUST be treated as **unsupported** on read".
         * Unsupported and malformed are different answers with different fixes — a recurring
         * NIP-99 listing is a well-formed event this version does not implement — so they get
         * different reasons.
         */
        private fun readPrice(tag: List<String>): Msat {
            if (tag.size > PRICE_ELEMENTS) {
                throw TagException(
                    TagRejection.UNSUPPORTED,
                    "§4.4: the optional NIP-99 `<frequency>` fourth element of `price` MUST NOT be " +
                        "emitted by Nenya v1 and MUST be treated as unsupported on read",
                )
            }
            if (tag.size < PRICE_ELEMENTS) {
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§4.4 encodes a price as `[\"price\", \"<sats>\", \"SAT\"]`; this one carries " +
                        "${tag.size} element(s)",
                )
            }
            val amount = readAmount(tag[1], tag[2], "a `price` tag")
            // §4.4: a listing or bid price is satoshi-denominated on the wire, so an `msat` or
            // `BTC` value that does not land on a whole satoshi is lossy and MUST fail loudly.
            try {
                amount.toSatoshis()
            } catch (refused: MoneyException) {
                throw refused.asTagRejection("a `price` tag")
            }
            return amount
        }

        /**
         * §8.1's `fee`, in **both** arities: three elements above zero basis points, two at zero.
         *
         * §8.1 names the two failures in so many words — a three-element `["fee", "0", "<pubkey>"]`
         * and a two-element `["fee", "250"]` "are both malformed and MUST be rejected" — so the
         * arity is checked against the value rather than accepted in either form.
         *
         * `10001` is refused and `10000` is accepted. Nothing between `0` and `10000` is refused
         * for being large: §8.1 forbids that on read and requires a client's own lower limit to
         * surface as its own named condition, which this library does not hold.
         */
        private fun readFee(tag: List<String>): Pair<FeeTerm, String?> {
            if (tag.size != FEE_ELEMENTS_ZERO && tag.size != FEE_ELEMENTS_NONZERO) {
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§8.1 gives `fee` exactly two legal arities — two elements at zero basis " +
                        "points, three above — and this one carries ${tag.size}",
                )
            }
            val bps = readStrictDecimal(
                tag[1],
                "a `fee` basis-point value",
                TagRejection.MALFORMED_NUMBER,
                // §8.1 names the upper bound itself, so a value too long to parse is reported as
                // above the maximum rather than as unreadable: `10000` is five digits, and a
                // canonical form cannot pad its way past that.
                maxDigits = MAX_BASIS_POINT_DIGITS,
                tooLargeReason = TagRejection.BPS_ABOVE_MAXIMUM,
            )
            if (bps > FeeTerm.MAX_BASIS_POINTS) {
                throw TagException(
                    TagRejection.BPS_ABOVE_MAXIMUM,
                    "§8.1 rejects a `fee` above ${FeeTerm.MAX_BASIS_POINTS} basis points, which is 100%",
                )
            }
            val term = try {
                FeeTerm.of(bps.toInt())
            } catch (refused: MoneyException) {
                throw refused.asTagRejection("a `fee` tag")
            }
            if (term.namesRecipient && tag.size != FEE_ELEMENTS_NONZERO) {
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§8.1 makes the recipient REQUIRED when basis points are greater than zero; " +
                        "this `fee` carries ${tag.size} elements",
                )
            }
            if (!term.namesRecipient && tag.size != FEE_ELEMENTS_ZERO) {
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§8.1 requires the recipient be OMITTED at zero basis points, so the canonical " +
                        "no-fee form is the two-element `[\"fee\", \"0\"]`",
                )
            }
            val recipient =
                if (term.namesRecipient) readPubkeyHex(tag[2], "a `fee` recipient") else null
            return term to recipient
        }

        private fun readImage(tag: List<String>): ImageRef {
            if (tag.size !in IMAGE_ELEMENTS_MIN..IMAGE_ELEMENTS_MAX) {
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§5.3 encodes an `image` tag as a URL and optional `<width>x<height>`; this " +
                        "one carries ${tag.size} element(s)",
                )
            }
            return ImageRef(tag[1], if (tag.size == IMAGE_ELEMENTS_MAX) tag[2] else null)
        }

        private fun readPubkeyRef(tag: List<String>): PubkeyRef {
            if (tag.size !in PUBKEY_REF_ELEMENTS_MIN..PUBKEY_REF_ELEMENTS_MAX) {
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§5.3 encodes a `p` tag as a pubkey and an optional relay hint; this one " +
                        "carries ${tag.size} element(s)",
                )
            }
            return PubkeyRef(tag[1], if (tag.size == PUBKEY_REF_ELEMENTS_MAX) tag[2] else null)
        }

        private fun readItem(tag: List<String>): ItemRef {
            if (tag.size != ITEM_ELEMENTS) {
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§5.3 encodes an `item` tag as a coordinate and a quantity; this one carries " +
                        "${tag.size} element(s)",
                )
            }
            return ItemRef(Coordinate.parse(tag[1]), tag[2])
        }

        private fun readVersion(tag: List<String>): Int {
            val major = readStrictDecimal(
                value(tag, "a `nenya` tag"),
                "a `nenya` version",
                TagRejection.MALFORMED_NUMBER,
            )
            if (major > Int.MAX_VALUE) {
                throw TagException(
                    TagRejection.MALFORMED_NUMBER,
                    "§4.5's `nenya` value is a decimal major version, not a number this large",
                )
            }
            return major.toInt()
        }

        /** `10000` is five digits, and §4.4's canonical form admits no leading zeros. */
        private val MAX_BASIS_POINT_DIGITS: Int = FeeTerm.MAX_BASIS_POINTS.toString().length

        private const val PRICE_ELEMENTS: Int = 3
        private const val FEE_ELEMENTS_ZERO: Int = 2
        private const val FEE_ELEMENTS_NONZERO: Int = 3
        private const val IMAGE_ELEMENTS_MIN: Int = 2
        private const val IMAGE_ELEMENTS_MAX: Int = 3
        private const val PUBKEY_REF_ELEMENTS_MIN: Int = 2
        private const val PUBKEY_REF_ELEMENTS_MAX: Int = 3
        private const val ITEM_ELEMENTS: Int = 3
    }
}
