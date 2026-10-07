package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.ImageRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagCardinality
import dev.eryalabs.nenya.tag.TagContext
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.tag.TagRejection
import dev.eryalabs.nenya.tag.TagRequirement
import dev.eryalabs.nenya.tag.TagSet
import dev.eryalabs.nenya.tag.TagWriter
import dev.eryalabs.nenya.wire.WireEvent
import dev.eryalabs.nenya.wire.WireException

/**
 * The values a client authors for one listing, before any §5.3 rule has been applied to them.
 *
 * ### A carrier, not a check
 *
 * Nothing is validated here, deliberately. [ListingWriter] refuses **by value** and a carrier that
 * threw would put half the refusals behind a `try` the caller did not ask for: a client assembling
 * a listing from a form wants one answer for the whole thing, with the §5.3 row named, and it wants
 * it as a return value. So this type holds whatever it was handed and the writer is the only place
 * a rule lives.
 *
 * Two fields are typed rather than stringly, because the type is the rule: [price] is a
 * [Msat] — §4.4's supply cap and its no-negative rule are therefore unrepresentable here rather
 * than checked — and [fee] is a [FeeTerm], where [FeeTerm.Absent] and a stated zero are different
 * values (§8.1) and the writer emits a different number of tags for each.
 *
 * ### What [extraTags] is for, and what it is not
 *
 * §4.3 requires that an implementation not drop tags it does not understand, and a client with a
 * `["client", …]` or a NIP-89 handler tag has nowhere else to put it. So extra tags are emitted
 * verbatim, after §5.3's rows, in the order given.
 *
 * A name §5.3 **does** name is refused there rather than emitted. Every one of §5.3's rows has a
 * parameter on this class, so a row arriving as an extra tag is either a second occurrence of a row
 * the writer already emits (§4.3 requires rejecting that rather than resolving it) or a caller
 * spelling a tag by hand that the writer spells from §5.3's own table — and `g`, `location` and
 * `item` are refused outright, which is the whole of their Requirement column.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public class AuthoredListing(

    /**
     * The author's x-only public key, 64 hex characters (§4.3).
     *
     * **Normalised to lowercase on the way out**, which is §4.3's rule for an event this client
     * authors: "an implementation MUST emit lowercase hex". That is the opposite of what
     * [WireEvent.pubkey] does to somebody else's event, and for the reason stated there — rewriting
     * a stranger's bytes changes their event's id.
     */
    public val authorPubkey: String,

    /** §4.1's `created_at`, in Unix seconds. The app's clock, never this library's (§4.6). */
    public val createdAt: Long,

    /** §5.3's `d`, MUST and non-empty: the addressability key, opaque and stable across edits. */
    public val dValue: String,

    /** §5.3's `title`, MUST. Plain text; anything a counterparty acts on is a tag, not prose. */
    public val title: String,

    /**
     * §5.3's `price`, MUST, in millisatoshis — and §8.2's "what the provider **receives**".
     *
     * Satoshi-denominated on the wire (§4.4), so an amount that does not land on a whole satoshi is
     * refused rather than rounded: see [ListingRejection.MALFORMED_TAG] and `TagWriter.price`.
     */
    public val price: Msat,

    /** §5.4's human-readable description. Carries no terms. */
    public val content: String = "",

    /** §5.3's `summary`, SHOULD, or `null`. */
    public val summary: String? = null,

    /** §5.3's `published_at`, SHOULD — the time of **first** publication, unchanged across edits. */
    public val publishedAt: Long? = null,

    /**
     * §5.1's or §5.2's `status`, or `null` for no `status` tag at all.
     *
     * `null` and [ListingStatus.ACTIVE] are **different** here, and the round trip is why: §5.3
     * says an absent `status` means `active`, so a writer that emitted `["status", "active"]` for
     * the absent case would produce a different event — and a different id — from the one it was
     * asked for. [ListingStatus.UNKNOWN] is not emittable at all (§5.2: it is the required
     * *treatment* of somebody else's unrecognised token, not a token).
     */
    public val status: ListingStatus? = null,

    /** §5.3's `m`, SHOULD — the desired or produced MIME type, NIP-94 semantics. */
    public val mimeType: String? = null,

    /**
     * §5.3's `t` tokens **beyond** the two §5.3 requires.
     *
     * `nenya` and this kind's side token are emitted by the writer from §4.5's and §5.1's/§5.2's own
     * constants, so neither needs listing here. The two are **not** treated alike, and the line is
     * drawn where `Listing.decode` draws it rather than where it would be tidy:
     *
     * - A **side token** here is refused. §5.3 requires *exactly one* of the pair and the decoder
     *   refuses a listing carrying two, so emitting one would be emitting an event this library's own
     *   decoder rejects — and §5.5's filters would put the listing on both halves of the board.
     * - `nenya` here is **permitted**, and produces a second `["t", "nenya"]`. §5.3 gives `t`
     *   cardinality `≥2` and §4.3's duplicate rule does not reach it, so the decoder accepts that and
     *   the event round-trips. It is redundant rather than wrong, and refusing it would be this writer
     *   refusing what its decoder accepts — which is the one thing T40 says a builder may not do.
     *
     * Lowercased on the way out (§5.3), because a relay's tag index is byte-exact.
     */
    public val topics: List<String> = emptyList(),

    /** §5.3's `image` tags, in order. Bounded by §4.3's fifth resource bound; see [ListingWriter]. */
    public val images: List<ImageRef> = emptyList(),

    /** §5.3's `expiration` (NIP-40), in Unix seconds. §5.6 makes the consumer enforce it. */
    public val expiration: Long? = null,

    /** §5.3's `alt` (NIP-31) — MUST on a request (§5.2), SHOULD on an offer (§5.1). */
    public val alt: String? = null,

    /** §4.5's `nenya` version, MUST. This document's major version; see [ListingWriter.NENYA_MAJOR_VERSION]. */
    public val nenyaVersion: Int = ListingWriter.NENYA_MAJOR_VERSION,

    /** §5.3's `p` tags, in order: a directed listing, addressed to specific counterparties. */
    public val pubkeyRefs: List<PubkeyRef> = emptyList(),

    /**
     * §5.3's and §8.1's `fee` term — **advisory** on a listing, since "only the order copy binds".
     *
     * [FeeTerm.Absent] emits no `fee` tag; a stated zero emits the two-element `["fee", "0"]`,
     * which §8.1 calls a signed statement that no fee applies. They are not the same event.
     */
    public val fee: FeeTerm = FeeTerm.Absent,

    /** The `fee` recipient pubkey. §8.1: REQUIRED above zero basis points, OMITTED at zero. */
    public val feeRecipient: String? = null,

    /** §5.3's `license` token. Its vocabulary is `OPEN-5`, so it is carried and not interpreted. */
    public val license: String? = null,

    /** §5.3's `deliver_by`, in Unix seconds. Advisory on a listing; binding in accepted terms. */
    public val deliverBy: Long? = null,

    /** Tags §5.3 does not name, emitted verbatim after its rows (§4.3). See this class's note. */
    public val extraTags: List<List<String>> = emptyList(),
) {

    /**
     * Names three counts and no value the author wrote.
     *
     * §12 item 11 and STOP RULE 14: a `d` value, a title and a pubkey are exactly what must not
     * reach a string representation, and this type holds all three at once.
     */
    override fun toString(): String =
        "AuthoredListing(topics=${topics.size}, images=${images.size}, extraTags=${extraTags.size})"
}

/**
 * What [ListingWriter] answers: the unsigned event, or the §5.1/§5.2/§5.3 rule that refused it.
 *
 * A value rather than an exception, and that is decision **P**'s rule for the whole writing half:
 * assembling a listing is something a client does from a form, where "which field is wrong" is the
 * normal answer rather than the exceptional one. Every refusal below is one a `Listing.decode` of
 * the same event would also make, which is what the round-trip proof in `ListingWriterPropertyTest`
 * is for: a builder whose own decoder refuses its output is a defect in the builder.
 */
public sealed interface ListingBuild {

    /**
     * The unsigned event, for the app to sign.
     *
     * **Nothing is signed here and nothing is published here.** No signature is produced anywhere
     * in this library (`Secp256k1Ops` answers `Unavailable`), and §4.1's `id` is not carried either:
     * it is recomputed from these five fields by `EventId.of`.
     *
     * ### Which of §4.3's bounds this answer has and has not checked
     *
     * §4.3's **fifth** bound — 64 `image` tags — is checked by the writer, because it names a row in
     * §5.3's vocabulary and is the one bound `TagSet.read` owns rather than `WireEvent`. The other
     * four — serialised event size, tag count, tag value size, `content` size — are **not**: they are
     * measured in `WireEvent.canonicalSerialisation`, which `EventId.of` calls, and that is the next
     * thing an app does with this value. So a [Built] carrying 600 extension tags is a real answer,
     * and the refusal arrives one step later as a `WireException` from the id computation.
     *
     * The asymmetry is deliberate rather than an omission, and it is the same one on the reading side:
     * `Listing.decode` takes a `CheckedEvent`, so those four bounds are behind it there too. A writer
     * that measured them here would be measuring them twice — the serialisation is not free — and
     * would still not be the last word, because the app is free to inject a different `WireLimits`.
     */
    public class Built internal constructor(

        /** §4.1's five id-bearing fields, with §5.3's tags in [ListingWriter.TAG_ORDER]. */
        public val event: WireEvent,
    ) : ListingBuild {

        /** Names no field of the event: a `d` value and a title are §12 item 11 values. */
        override fun toString(): String = "ListingBuild.Built(kind=${event.kind}, tags=${event.tags.size})"
    }

    /**
     * The listing was refused, carrying the reason and the §5.3 row it is about as **data**.
     *
     * Same convention as `ListingException`: tests assert on [reason] and [tag] rather than on
     * [detail]'s wording, and the §5.3 Encoding-column reason is not flattened away — it is on
     * [tagReason], which is the by-value equivalent of the `cause` a thrown refusal carries.
     */
    public class Refused internal constructor(

        /** Which §5.1, §5.2, §5.3, §4.3 or §4.5 rule refused the listing. */
        public val reason: ListingRejection,

        /** The §5.3 tag this refusal is about, or `null` where the rule names no single tag. */
        public val tag: String?,

        /**
         * The §5.3 Encoding-column reason, where a tag codec refused the value, or `null`.
         *
         * `ListingException` keeps this on the `cause`; a value-shaped refusal has no cause, so it
         * is a field. Nothing is lost either way: a caller that wants to tell a `data:` image URI
         * from a part-satoshi price still can.
         */
        public val tagReason: TagRejection?,

        /**
         * Why, in words, for a log.
         *
         * **Never echoes the caller's input**, on the same terms as every other message in this
         * library (§12, STOP RULE 14): a tag *name*, a *count* and a *reason* may appear; a `d`
         * value, a pubkey, a title or an arbitrary tag value may not.
         */
        public val detail: String,
    ) : ListingBuild {

        override fun toString(): String = "ListingBuild.Refused(reason=$reason, tag=$tag)"
    }
}

/**
 * The write half of §5.1, §5.2 and §5.3: one entry point per listing kind, each returning the
 * unsigned event an app then signs.
 *
 * ### Why this exists at all
 *
 * The library decoded every event it defines and constructed none, so every client had to assemble
 * tag arrays by hand — which is where cardinality, ordering and completeness go wrong silently.
 * Decision **P** in `loop/VISION.md` makes the builders library work and fixes the proof: build,
 * decode with the decoder that already reads them, build again from what the decode reports, and
 * compare the emitted bytes. A builder whose own decoder refuses its output is a defect in the
 * builder, and nothing short of the round trip finds that.
 *
 * ### It signs nothing, publishes nothing and invents nothing
 *
 * Signing stays the app's, as everywhere else. Every tag is spelled by `TagWriter` or by §5.3's own
 * table through [NenyaTags] — no tag name and no value form is written out here — and every rule
 * applied is one `Listing.decode` already applies. The required set and the forbidden set are
 * **derived** from [NenyaTags], which `TagVocabularyTest` holds equal to §5.3's table parsed out of
 * the document at test time, so a row whose Requirement changes reaches this writer and that
 * decoder together.
 *
 * ### What it refuses, by value
 *
 * **In the order the checks are made**, because exactly one refusal is reported and it is the first.
 * The order is part of the API, so it is written here as the code runs rather than as a tidier list:
 * a listing carrying both `status = UNKNOWN` and a hundred `image` tags answers
 * [ListingRejection.LIMIT_EXCEEDED], not the status refusal.
 *
 * 1. §4.3's event fields — an author pubkey that is not 64 hex characters, a negative `created_at`
 *    ([ListingRejection.MALFORMED_EVENT_FIELD]).
 * 2. §4.3's fifth resource bound on `image` tags ([ListingRejection.LIMIT_EXCEEDED]) — rejected,
 *    never truncated, and taking the injected [TagLimits] because §4.3 says the bounds SHOULD be
 *    configurable. See [ListingBuild.Built] on why this is the only one of §4.3's five bounds here.
 * 3. §5.1's or §5.2's `status` vocabulary for **this** kind, through `ListingStatusCodec.write`, so
 *    a `kind:30402` can no more be *written* with `awarded` than read with it; and §5.2's `unknown`,
 *    which is a treatment rather than a token ([ListingRejection.UNKNOWN_IS_NOT_EMITTABLE]).
 * 4. §5.3's `t` rule: a side token among [AuthoredListing.topics]
 *    ([ListingRejection.MISSING_TOPIC], the reason the decoder gives for a listing on both sides).
 * 5. §5.3's Encoding column, row by row, through `TagWriter`, `ImageRef` and `PubkeyRef`
 *    ([ListingRejection.MALFORMED_TAG] naming the row, with the tag-layer reason on
 *    [ListingBuild.Refused.tagReason]).
 * 6. §5.3's rows handed in as extra tags, checked against the rows just emitted: `g` and `location`
 *    ([ListingRejection.FORBIDDEN_TAG]), `item` ([ListingRejection.SELF_REFERENCE]), a side token
 *    ([ListingRejection.MISSING_TOPIC], the same answer as 4 for the same event), a second occurrence
 *    of a row §5.3 gives cardinality `1` or `0–1` ([ListingRejection.DUPLICATE_TAG]), and any other
 *    §5.3 row ([ListingRejection.ROW_IS_NOT_AN_EXTENSION]).
 * 7. A final audit against §5.3's own table — every MUST row present, every MUST NOT row absent,
 *    every `1`/`0–1` row at most once, every `≥2` row at least twice. It is derived rather than
 *    listed, so a MUST row added to §5.3 that this writer has no parameter for makes it refuse
 *    everything rather than quietly emit a non-conformant listing.
 *
 * Pure computation: no clock, no randomness, no I/O. The `created_at` is the caller's (§4.6).
 */
public object ListingWriter {

    /**
     * §4.5's `nenya` value: the decimal **major** version of this document.
     *
     * `1`, for `NENYA-1`. Not read from anywhere at run time — §4.5 fixes it as a property of the
     * document a build implements — and held equal at test time to the value §5.1's and §5.2's own
     * worked examples print, so a major-version bump cannot leave this writer behind.
     */
    public const val NENYA_MAJOR_VERSION: Int = 1

    /**
     * The order §5.3's rows are emitted in, which is a wire-visible choice and therefore pinned.
     *
     * §4.1 computes the id over the tags **in order**, so the emission order is part of the event.
     * It is not invented here: for the twelve rows §5.1's and §5.2's worked listings print, it is
     * the order *those examples* print — the document says its request example "is copy-pasteable
     * as written, and fixtures MAY be built against it" — and `ListingWriterTest` parses both
     * examples out of `spec/NENYA-1.md` at test time and holds this list equal to them. The four
     * rows the examples do not print (`p`, `fee`, `license`, `deliver_by`) follow, in §5.3's table
     * order, which the same test derives from [NenyaTags].
     *
     * The three rows §5.3 marks MUST NOT — `item`, `g`, `location` — are absent, and the same test
     * holds this list plus those three equal to §5.3's whole vocabulary, so a row added to the
     * table cannot be silently unemittable.
     */
    public val TAG_ORDER: List<String> = listOf(
        "d",
        "title",
        "summary",
        "published_at",
        "price",
        "status",
        "m",
        "t",
        "image",
        "expiration",
        "nenya",
        "alt",
        "p",
        "fee",
        "license",
        "deliver_by",
    )

    /** A §5.2 request, `kind:30404`. `alt` is MUST here and the writer refuses a listing without one. */
    public fun request(listing: AuthoredListing, limits: TagLimits = TagLimits.DEFAULT): ListingBuild =
        build(NenyaKind.REQUEST, listing, limits)

    /** A §5.1 offer, `kind:30402` — an unmodified NIP-99 classified listing with Nenya's tags added. */
    public fun offer(listing: AuthoredListing, limits: TagLimits = TagLimits.DEFAULT): ListingBuild =
        build(NenyaKind.OFFER, listing, limits)

    /** A §5.1 draft or parked offer, `kind:30403` — "identical structure" to an offer. */
    public fun draft(listing: AuthoredListing, limits: TagLimits = TagLimits.DEFAULT): ListingBuild =
        build(NenyaKind.OFFER_DRAFT, listing, limits)

    // ---------------------------------------------------------------------------------------------
    // Assembly.
    // ---------------------------------------------------------------------------------------------

    private fun build(kind: Int, listing: AuthoredListing, limits: TagLimits): ListingBuild {
        val side = ListingSide.requiredOn(kind) ?: return refusal(
            ListingRejection.NOT_A_LISTING_KIND,
            null,
            "§5.1 and §5.2 fix a side token per listing kind; kind:$kind is not one of " +
                "${NenyaKind.LISTING_KINDS.sorted()}",
        )

        // §4.3's event fields, before any tag is spelled: a pubkey that is not a pubkey has no
        // business reaching §5.3's Encoding column, and a negative `created_at` is no §4.3 timestamp.
        // The pubkey goes through `PubkeyRef`, which is this library's hex reader — so it is checked
        // and lowercased by the same code a `p` tag is, rather than by a second copy of §4.3's rule.
        val author = try {
            PubkeyRef(listing.authorPubkey).pubkey
        } catch (refused: TagException) {
            return refusal(
                ListingRejection.MALFORMED_EVENT_FIELD,
                null,
                "§4.3 fixes an author pubkey as exactly 64 hexadecimal characters and §4.1 hashes " +
                    "it into the event id; this one was refused as ${refused.reason.name}",
                refused.reason,
            )
        }
        if (listing.createdAt < 0L) {
            return refusal(
                ListingRejection.MALFORMED_EVENT_FIELD,
                null,
                "§4.3 fixes a timestamp as a non-negative integer and `created_at` is one",
            )
        }

        // §4.3's fifth resource bound, before the tags are built rather than after: §4.3 requires
        // rejecting rather than truncating, and a writer that assembled 70 `image` tags and then
        // refused would have done the work either way.
        if (listing.images.size > limits.maxImageTags) {
            return refusal(
                ListingRejection.LIMIT_EXCEEDED,
                IMAGE_TAG,
                "this listing names ${listing.images.size} `$IMAGE_TAG` tags and the injected bound " +
                    "is ${limits.maxImageTags}; §4.3 requires rejecting rather than truncating",
            )
        }

        val statusTag = if (listing.status == null) null else try {
            ListingStatusCodec.write(listing.status, kind)
        } catch (refused: ListingException) {
            return refusal(refused.reason, refused.tag, refused.message ?: "", null)
        }

        topicProblem(kind, listing.topics)?.let { return it }

        // §5.3's rows, in TAG_ORDER, one row at a time — so a refusal from the Encoding column names
        // the row it came from as data. A single try around the whole assembly would answer "some
        // value was malformed", which is what the `tag` field exists not to be.
        val tags = mutableListOf<List<String>>()
        for (name in TAG_ORDER) {
            try {
                tags += row(name, side, listing, statusTag)
            } catch (refused: TagException) {
                return refusal(
                    ListingRejection.MALFORMED_TAG,
                    name,
                    "§5.3's Encoding column refused the value this writer was handed for `$name`, " +
                        "as ${refused.reason.name}",
                    refused.reason,
                )
            }
        }
        // The extra tags are checked **after** assembly, against the rows that were actually emitted,
        // so the reason a §5.3 row handed in as an extension gets is the true one: a duplicate when
        // this listing really would carry two, and `ROW_IS_NOT_AN_EXTENSION` when it would carry one.
        // Diagnosing it before assembly reported every such row as a duplicate, including rows whose
        // parameter was `null` — a refusal `Listing.decode` does not make, with a message saying
        // "a second occurrence of it" about an event that carried one.
        extraTagProblem(kind, listing.extraTags, tags.map { it[0] }.toSet())?.let { return it }
        // §4.3: emitted verbatim and last, so a client's extension can never displace a §5.3 row
        // from the position TAG_ORDER fixes for it.
        for (tag in listing.extraTags) tags += tag.toList()

        vocabularyProblem(kind, tags)?.let { return it }

        val event = try {
            WireEvent(author, listing.createdAt, kind, tags, listing.content)
        } catch (refused: WireException) {
            return refusal(
                ListingRejection.MALFORMED_EVENT_FIELD,
                null,
                "§4.1's event structure refused these fields as ${refused.reason.name}",
            )
        }
        return ListingBuild.Built(event)
    }

    /**
     * One §5.3 row's tags — none, one, or several — spelled by `TagWriter` or by the row's own wire
     * form, and never by a tag name written out here.
     *
     * The `when` is over [TAG_ORDER]'s names and its `else` is a refusal rather than a fall-through:
     * a name added to that list and not here would otherwise be a row silently emitted as nothing,
     * which is exactly the "completeness goes wrong in silence" failure the builders exist to stop.
     * The final audit in [vocabularyProblem] catches it a second time if the row is a MUST.
     *
     * @throws TagException from the tag layer, caught by the caller, which names the row.
     */
    private fun row(
        name: String,
        side: ListingSide,
        listing: AuthoredListing,
        statusTag: List<String>?,
    ): List<List<String>> = when (name) {
        "d" -> listOf(TagWriter.d(listing.dValue))
        "title" -> listOf(text(name, listing.title))
        "summary" -> optional(listing.summary) { text(name, it) }
        "published_at" -> optional(listing.publishedAt) { TagWriter.timestamp(name, it) }
        "price" -> listOf(TagWriter.price(listing.price))
        "status" -> optional(statusTag) { it }
        "m" -> optional(listing.mimeType) { text(name, it) }
        // §4.5's discovery token and §5.1's/§5.2's side token, in that order, then whatever the
        // author added. The order is wire-visible — §5.5 filters on `#t` and §4.1 hashes the tags in
        // order — and the round-trip proof is what holds it: a writer that emitted the pair the other
        // way round produces a different event id from the one the decode reports back.
        "t" -> buildList {
            add(TagWriter.topic(TagSet.TOPIC_NENYA))
            add(TagWriter.topic(side.token))
            for (token in listing.topics) add(TagWriter.topic(token))
        }
        "image" -> listing.images.map { it.toTag() }
        "expiration" -> optional(listing.expiration) { TagWriter.timestamp(name, it) }
        "nenya" -> listOf(TagWriter.version(listing.nenyaVersion))
        "alt" -> optional(listing.alt) { text(name, it) }
        "p" -> listing.pubkeyRefs.map { it.toTag() }
        "fee" -> optional(feeTag(listing)) { it }
        "license" -> optional(listing.license) { text(name, it) }
        "deliver_by" -> optional(listing.deliverBy) { TagWriter.timestamp(name, it) }
        else -> throw TagException(
            TagRejection.MALFORMED_CONTEXT,
            "`$name` is in this writer's emission order and it has no row here; a §5.3 row this " +
                "writer cannot spell is a listing it must not emit",
        )
    }

    /** One tag or none, for a §5.3 row whose Card. column permits zero. */
    private fun <T> optional(value: T?, tag: (T) -> List<String>): List<List<String>> =
        if (value == null) emptyList() else listOf(tag(value))

    /**
     * §5.3's `TEXT` encoding: `["<name>", "<text>"]`, and nothing further.
     *
     * The name is one of [TAG_ORDER]'s, which the tests hold equal to §5.3's own table, so no tag
     * spelling originates here. There is no `TagWriter` function for this because there is no rule
     * to apply: §5.3's cell is the two-element array and the value is plain text.
     */
    private fun text(name: String, value: String): List<String> = listOf(name, value)

    /** §8.1's two arities, or no tag at all for [FeeTerm.Absent] — which has no wire form. */
    private fun feeTag(listing: AuthoredListing): List<String>? {
        if (listing.fee == FeeTerm.Absent) {
            if (listing.feeRecipient != null) {
                // `TagWriter.fee` would refuse this as ABSENT_FEE_TERM, which names the wrong half:
                // the caller did not ask for the absent form, they asked for a recipient without a
                // term. §8.1 makes the recipient a property of the term, so this is an arity error.
                throw TagException(
                    TagRejection.WRONG_ARITY,
                    "§8.1 makes the `fee` recipient a part of the term; an absent term has no wire " +
                        "form at all, so there is nothing for a recipient to belong to",
                )
            }
            return null
        }
        return TagWriter.fee(listing.fee, listing.feeRecipient)
    }

    // ---------------------------------------------------------------------------------------------
    // The checks, each derived from §5.3's table rather than restated.
    // ---------------------------------------------------------------------------------------------

    /**
     * A §5.3 row handed in as an extra tag, refused for the reason §5.3 gives that row.
     *
     * @param emitted the row names this listing's own parameters produced, so a row that really would
     *   appear twice is reported as §4.3's duplicate and a row that would appear once is not.
     */
    private fun extraTagProblem(
        kind: Int,
        extraTags: List<List<String>>,
        emitted: Set<String>,
    ): ListingBuild.Refused? {
        for (tag in extraTags) {
            if (tag.isEmpty()) {
                return refusal(
                    ListingRejection.MALFORMED_TAG,
                    null,
                    "§4.3 says a tag is an array of one or more strings and requires rejecting an " +
                        "event carrying an empty tag array",
                )
            }
            val name = tag[0]
            val spec = NenyaTags.byName(name) ?: continue
            // A side token arriving as an extension is the same event as one arriving in `topics`, so
            // it gets the same reason: `MISSING_TOPIC`, which is what `Listing.decode` answers for a
            // listing carrying two of the pair. Two reasons for one event would be this writer
            // disagreeing with itself about which rule the author broke.
            if (name == TOPIC_TAG && tag.size > 1 && ListingSide.byToken(tag[1]) != null) {
                return sideTokenRefusal(kind)
            }
            if (name == ITEM_TAG) {
                return refusal(
                    ListingRejection.SELF_REFERENCE,
                    ITEM_TAG,
                    "§5.3: `$ITEM_TAG` is not a listing tag, because a listing cannot reference " +
                        "itself, and an implementation MUST reject one on a kind:$kind event",
                )
            }
            if (name in ListingVocabulary.forbidden(kind)) {
                return refusal(
                    ListingRejection.FORBIDDEN_TAG,
                    name,
                    "§5.3 marks `$name` MUST NOT on a listing: a geohash on a Nenya listing is a " +
                        "location leak with no compensating function",
                )
            }
            // §4.3's duplicate rule only reaches a row §5.3 gives cardinality `1` or `0–1`, so that
            // is the only case where "a second occurrence" is both true of the event and a thing the
            // decoder would refuse. A second `image` or `p` tag is legal on a listing; handing one in
            // as an extension is still refused, but as the row it is rather than as a duplicate.
            return if (name in emitted && spec.cardinality.singleOccurrence) {
                refusal(
                    ListingRejection.DUPLICATE_TAG,
                    name,
                    "this listing already carries `$name` from the parameter §5.3's row has on " +
                        "`AuthoredListing`, so the extra tag is a second occurrence of it; §5.3 " +
                        "marks that row cardinality ${spec.cardinality.token} and §4.3 requires " +
                        "rejecting that rather than resolving it by taking the first or the last",
                )
            } else {
                refusal(
                    ListingRejection.ROW_IS_NOT_AN_EXTENSION,
                    name,
                    "`$name` is a §5.3 row, not an extension: pass the value to the parameter this " +
                        "writer spells that row from, so §5.3's Encoding column is applied to it and " +
                        "it lands in the position §4.1 hashes it in",
                )
            }
        }
        return null
    }

    /**
     * §5.3's `t` rule, the half the writer can break: *exactly one* of `wtb` / `wts`.
     *
     * Matched case-insensitively, because that is how §5.3 says a `t` token is matched on read and
     * `TagWriter.topic` lowercases on write — so an author's `["t", "WTS"]` on a request would
     * otherwise reach the wire as a second side token.
     */
    private fun topicProblem(kind: Int, topics: List<String>): ListingBuild.Refused? {
        for (token in topics) {
            if (ListingSide.byToken(token) != null) return sideTokenRefusal(kind)
        }
        return null
    }

    /** One refusal for a second side token, wherever it arrived from — `topics` or an extension tag. */
    private fun sideTokenRefusal(kind: Int): ListingBuild.Refused = refusal(
        ListingRejection.MISSING_TOPIC,
        TOPIC_TAG,
        "§5.3 requires exactly one of `${ListingSide.WANT_TO_BUY.token}` / " +
            "`${ListingSide.WANT_TO_SELL.token}` on a listing, and this writer already emits the one " +
            "§5.1 or §5.2 requires on a kind:$kind; a second one would put the listing on both " +
            "halves of §5.5's board",
    )

    /**
     * The final audit: the assembled tags against §5.3's Card. and Requirement columns, derived.
     *
     * Unreachable for every refusal above — each of those is caught earlier and more precisely —
     * and here anyway, because it is the one check that is a function of §5.3's table rather than of
     * this file. A MUST row added to §5.3 that no parameter of [AuthoredListing] supplies turns
     * every build red, which is the fail-closed direction: a writer that emitted a listing missing
     * a required tag would be publishing events its own decoder refuses.
     */
    private fun vocabularyProblem(kind: Int, tags: List<List<String>>): ListingBuild.Refused? {
        val counts = mutableMapOf<String, Int>()
        for (tag in tags) counts[tag[0]] = (counts[tag[0]] ?: 0) + 1
        val context = TagContext.listing(kind)
        for (spec in NenyaTags.ALL) {
            val count = counts[spec.name] ?: 0
            val requirement = spec.requirement.on(context)
            if (requirement == TagRequirement.MUST && count == 0) {
                return refusal(
                    ListingRejection.MISSING_REQUIRED_TAG,
                    spec.name,
                    "§5.3 marks `${spec.name}` ${spec.requirement.cell} and this kind:$kind " +
                        "listing would carry none",
                )
            }
            if (requirement == TagRequirement.MUST_NOT && count > 0) {
                return refusal(
                    if (spec.name == ITEM_TAG) ListingRejection.SELF_REFERENCE
                    else ListingRejection.FORBIDDEN_TAG,
                    spec.name,
                    "§5.3 marks `${spec.name}` MUST NOT on a listing and this one would carry $count",
                )
            }
            if (spec.cardinality.singleOccurrence && count > 1) {
                return refusal(
                    ListingRejection.DUPLICATE_TAG,
                    spec.name,
                    "§5.3 marks `${spec.name}` cardinality ${spec.cardinality.token} and this " +
                        "listing would carry $count; §4.3 requires rejecting that rather than " +
                        "resolving it",
                )
            }
            if (spec.cardinality == TagCardinality.TWO_OR_MORE && count < 2) {
                return refusal(
                    ListingRejection.TOO_FEW_OCCURRENCES,
                    spec.name,
                    "§5.3 marks `${spec.name}` cardinality ${spec.cardinality.token} on a listing " +
                        "and this one would carry $count",
                )
            }
        }
        return null
    }

    private fun refusal(
        reason: ListingRejection,
        tag: String?,
        detail: String,
        tagReason: TagRejection? = null,
    ): ListingBuild.Refused = ListingBuild.Refused(reason, tag, tagReason, detail)
}
