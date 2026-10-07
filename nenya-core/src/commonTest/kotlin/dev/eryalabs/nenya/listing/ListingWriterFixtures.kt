package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.ImageRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * The generator behind the writer's corpus, and the one function that turns a decoded [Listing] back
 * into the [AuthoredListing] that must rebuild it.
 *
 * This is not a scratch file. The queue's Definition of done forbids an encoded value appearing in a
 * test as something somebody typed out, so nothing here is one: the pubkeys are SHA-256 digests of a
 * per-index label ([TagFixtures.pubkeyFor]) and every event id is computed by the platform's own
 * SHA-256 inside T8. A reviewer can change [SEED], re-run the suite, and every property must hold.
 *
 * ### Why this corpus is authored values and not tag arrays
 *
 * `ListingFixtures` generates **tag arrays**, because the thing under test there is a decoder. The
 * thing under test here is a writer, so the corpus is the other side of the same boundary: the
 * values a client would hand [ListingWriter]. The decoder's corpus can therefore carry shapes no
 * writer may emit — a `["status", "part-exchanged"]`, an uppercase `t` token — and this one cannot,
 * which is why it is its own generator rather than a transformation of that one.
 *
 * ### What the corpus carries and why
 *
 * The proof is a byte-for-byte round trip through `Listing.decode`, so the corpus carries everything
 * that could make those bytes differ:
 *
 * - **Every optional §5.3 row present and absent.** `ListingWriterPropertyTest` asserts that
 *   coverage over the generated corpus rather than trusting the draws, because a row that is always
 *   absent is a row the round trip never exercises.
 *   An absent `status` and a stated one are separate cases: §5.3 says absence *means* `active`, so a
 *   writer that emitted the tag for the absent case would produce a different event id.
 * - **§4.1's escaping cases inside values.** All seven characters §4.1 gives a shortcut escape, a
 *   control character that needs the `\u00XX` form, `/` (which §4.1 never escapes), `0x7f` (which is
 *   above `0x20` and so is emitted verbatim) and a non-BMP character (emitted as UTF-8, never as an
 *   escaped surrogate pair). The id the round trip compares is computed over those bytes.
 * - **Both `fee` arities**, including the stated zero §8.1 distinguishes from an absent term, and
 *   both `image` arities.
 * - **Extra tags** §5.3 does not name, which §4.3 requires be preserved verbatim.
 * - **Mixed-case `t` tokens**, because §5.3 requires lowercasing on write and a relay tag index is
 *   byte-exact.
 */
internal object ListingWriterFixtures {

    /**
     * Pinned so a failure is reproducible. [JdkRandom]'s algorithm is the JDK's specified one and is
     * identical on the JVM and JavaScript, so this file produces the same run wherever it is re-run.
     */
    const val SEED: Long = 20261007L

    /** T9's fixed, non-round `created_at`. A writer that emitted a constant would still be visible. */
    const val CREATED_AT: Long = TagFixtures.CREATED_AT

    /** The two `t` tokens [ListingWriter] emits itself, before anything the author added. */
    const val FIXED_TOPICS: Int = 2

    /** §5.1's and §5.2's three kinds, in the order the corpus cycles them. */
    val KINDS: List<Int> = listOf(NenyaKind.REQUEST, NenyaKind.OFFER, NenyaKind.OFFER_DRAFT)

    /**
     * §5.3's optional rows — every row that is neither MUST on both kinds nor MUST NOT.
     *
     * Derived from the writer's own emission order minus the rows it always emits, so a row added to
     * §5.3 and to [ListingWriter.TAG_ORDER] joins the present-and-absent coverage assertion without
     * anybody editing this list.
     */
    val OPTIONAL_ROWS: List<String> =
        ListingWriter.TAG_ORDER - setOf("d", "title", "price", "t", "nenya")

    /** Tag names §5.3 does not name, so §4.3's "unknown tag" rule applies to them. */
    private val EXTRA_NAMES: List<String> = listOf(
        "client", "subject", "zap", "relays", "A", "K", "P", "e", "E", "k", "x-nenya-experiment",
    )

    /**
     * The pieces values are assembled from: §4.1's three escaping rules, exercised.
     *
     * The seven characters §4.1 gives a shortcut escape (`"`, `\`, backspace, form feed, newline,
     * carriage return, tab), two that need rule 2's `\u00XX` form, `/` and `0x7f` which rule 3
     * emits verbatim, and two multi-byte characters including one outside the BMP.
     */
    private val HOSTILE_PIECES: List<String> = buildList {
        add("")
        for (code in listOf(0x22, 0x5C, 0x08, 0x0C, 0x0A, 0x0D, 0x09, 0x00, 0x1F, 0x2F, 0x7F)) {
            add(code.toChar().toString())
        }
        add("é")
        add(TestText.codePoint(0x1F600))
        add("ordinary")
    }

    /** One generated case: the kind to build it as, and the values to build it from. */
    data class Case(val kind: Int, val listing: AuthoredListing, val index: Int)

    /** [count] cases from one seeded run, cycling §5.1's and §5.2's three kinds. */
    fun cases(count: Int): List<Case> {
        val random = JdkRandom(SEED)
        return List(count) { index ->
            val kind = KINDS[index % KINDS.size]
            Case(kind, authored(random, index, kind), index)
        }
    }

    /** [listing] built as [kind] through the entry point §5.1 or §5.2 gives that kind. */
    fun build(kind: Int, listing: AuthoredListing): ListingBuild = when (kind) {
        NenyaKind.REQUEST -> ListingWriter.request(listing)
        NenyaKind.OFFER -> ListingWriter.offer(listing)
        else -> ListingWriter.draft(listing)
    }

    /**
     * The event [build] produced, or a loud failure naming the refusal.
     *
     * Failing here rather than asserting a type keeps every positive test's message useful: a corpus
     * entry the writer refused prints the §5.3 row and the reason, not "expected Built".
     */
    fun built(build: ListingBuild, what: String): WireEvent = when (build) {
        is ListingBuild.Built -> build.event
        is ListingBuild.Refused ->
            fail("$what was refused as ${build.reason} on `${build.tag}`: ${build.detail}")
    }

    /** The refusal [build] answered, or a loud failure saying it was accepted. */
    fun refused(build: ListingBuild, what: String): ListingBuild.Refused = when (build) {
        is ListingBuild.Refused -> build
        is ListingBuild.Built ->
            fail("$what was accepted; it emitted ${build.event.tags.size} tag(s)")
    }

    /** [event] as a T8 [CheckedEvent] — the only door into [Listing.decode] (§4.1). */
    fun checked(event: WireEvent): CheckedEvent =
        CheckedEvent.checkEventId(EventId.of(event).toHex(), event)

    /** [event] decoded as the listing it claims to be. */
    fun decode(event: WireEvent): Listing = Listing.decode(checked(event))

    /**
     * The [AuthoredListing] that must rebuild [decoded] — assembled **only** from what the decode
     * reports, which is the half of the round trip that proves the decoder reports enough.
     *
     * Two readings here are the ones a careless reconstruction gets wrong, and both would show up as
     * a different event id rather than as a crash:
     *
     * - **`status`** comes from [Listing.statusToken] and not from [Listing.status]. §5.3 says an
     *   absent `status` *means* `active`, so the decode answers [ListingStatus.ACTIVE] for a listing
     *   that carried no tag at all; re-emitting one would add a tag the author never wrote.
     * - **`topics`** drops the two tokens the writer emits itself. Which two, and in which order, is
     *   asserted by the caller before this drops them — see `ListingWriterPropertyTest`.
     */
    fun reauthored(decoded: Listing): AuthoredListing = AuthoredListing(
        authorPubkey = decoded.authorPubkey,
        createdAt = decoded.createdAt,
        dValue = decoded.dValue,
        title = decoded.title,
        price = decoded.price,
        content = decoded.content,
        summary = decoded.summary,
        publishedAt = decoded.publishedAt,
        status = if (decoded.statusToken == null) null else decoded.status,
        mimeType = decoded.mimeType,
        topics = decoded.topics.drop(FIXED_TOPICS),
        images = decoded.images,
        expiration = decoded.expiration,
        alt = decoded.alt,
        nenyaVersion = assertNotNull(decoded.nenyaVersion, "§5.3 marks `nenya` MUST on a listing"),
        pubkeyRefs = decoded.pubkeyRefs,
        fee = decoded.fee,
        feeRecipient = decoded.feeRecipient,
        license = decoded.license,
        deliverBy = decoded.deliverBy,
        extraTags = decoded.unknownTags,
    )

    /** The minimal legal listing for [kind] — every MUST row and nothing else. */
    fun minimal(kind: Int, index: Int = 0): AuthoredListing = AuthoredListing(
        authorPubkey = TagFixtures.pubkeyFor(index),
        createdAt = CREATED_AT,
        dValue = "listing-$index",
        title = "a title",
        price = Msat.ofSat(50_000L),
        alt = if (kind == NenyaKind.REQUEST) "a fallback rendering" else null,
    )

    /**
     * The tag arrays of one of §5.1's or §5.2's worked listings, as a [CheckedEvent] over a generated
     * pubkey.
     *
     * The example's own `"pubkey"` is the placeholder `<provider-pubkey-hex>`, which is not a pubkey
     * and never could be: §4.3 fixes 64 hex characters. So the structure under test is the tag array,
     * and the author is a digest this repository computes.
     */
    fun checkedExample(example: Section51.Example, index: Int = 0): CheckedEvent {
        val event = WireEvent(TagFixtures.pubkeyFor(index), CREATED_AT, example.kind, example.tags, "")
        return checked(event)
    }

    /** Asserts [left] and [right] are the same bytes on the wire, and says what differed if not. */
    fun assertSameBytes(left: WireEvent, right: WireEvent, what: String) {
        // The tag lists first, because a difference there is readable and the serialisation is not.
        assertEquals(left.tags, right.tags, "$what: the tag arrays differ")
        assertEquals(
            left.canonicalSerialisation(),
            right.canonicalSerialisation(),
            "$what: §4.1's canonical serialisation differs, so the two events have different ids",
        )
    }

    private fun authored(random: JdkRandom, index: Int, kind: Int): AuthoredListing {
        val request = kind == NenyaKind.REQUEST
        val fee = feeTerm(random)
        return AuthoredListing(
            authorPubkey = TagFixtures.pubkeyFor(index),
            createdAt = CREATED_AT + random.nextInt(99_999),
            dValue = "listing-$index-${hostile(random, 1)}",
            title = hostile(random, 2),
            price = Msat.ofSat(random.nextInt(1_000_000).toLong()),
            content = hostile(random, 4),
            summary = draw(random) { hostile(random, 2) },
            publishedAt = draw(random) { CREATED_AT - random.nextInt(99_999) },
            // §5.1's closed vocabulary and §5.2's extension, plus the absent case §5.3 gives its own
            // meaning. Never `UNKNOWN`: §5.2 makes that a treatment rather than a token, and the
            // writer refuses it — which `ListingWriterTest` asserts separately.
            status = draw(random) {
                val vocabulary = ListingStatusCodec.vocabulary(kind).toList()
                vocabulary[random.nextInt(vocabulary.size)]
            },
            mimeType = draw(random) { if (request) "video/mp4" else "image/png" },
            topics = List(random.nextInt(3)) { topic(random, index, it) },
            images = List(random.nextInt(3)) { image(random, index, it) },
            expiration = draw(random) { CREATED_AT + random.nextInt(99_999) },
            // MUST on a request (§5.2), SHOULD on an offer (§5.1) — so only the offer side draws it.
            alt = if (request) hostile(random, 2) else draw(random) { hostile(random, 2) },
            pubkeyRefs = List(random.nextInt(3)) { pubkeyRef(random, index, it) },
            fee = fee,
            feeRecipient = if (fee.namesRecipient) TagFixtures.pubkeyFor(index + 7) else null,
            license = draw(random) { "cc-by-4.0" },
            deliverBy = draw(random) { CREATED_AT + random.nextInt(99_999) },
            extraTags = List(random.nextInt(4)) { extraTag(random, index, it) },
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

    /** A `t` token §5.3 does not reserve, sometimes with the uppercase §5.3 requires be lowered. */
    private fun topic(random: JdkRandom, index: Int, position: Int): String =
        if (random.nextBoolean()) "category-$index-$position" else "Category-$index-$position"

    /** Both `image` arities: §5.3's Encoding cell prints three elements and NIP-99 makes two legal. */
    private fun image(random: JdkRandom, index: Int, position: Int): ImageRef {
        val url = "https://example.invalid/$index-$position.png"
        return if (random.nextBoolean()) ImageRef(url, "${64 + position}x${64 + position}")
        else ImageRef(url)
    }

    /** Both `p` arities: §5.3's optional relay hint present and absent. */
    private fun pubkeyRef(random: JdkRandom, index: Int, position: Int): PubkeyRef {
        val pubkey = TagFixtures.pubkeyFor(index + position + 1)
        return if (random.nextBoolean()) PubkeyRef(pubkey, "wss://relay.example.invalid")
        else PubkeyRef(pubkey)
    }

    /** A tag §5.3 does not name, of one to three elements, which §4.3 requires be preserved verbatim. */
    private fun extraTag(random: JdkRandom, index: Int, position: Int): List<String> {
        val name = EXTRA_NAMES[random.nextInt(EXTRA_NAMES.size)]
        return buildList {
            add(name)
            repeat(random.nextInt(3)) { add("$index-$position-${hostile(random, 2)}") }
        }
    }

    /** `null` half the time, so every optional row is drawn present and absent across the corpus. */
    private fun <T> draw(random: JdkRandom, value: () -> T): T? = if (random.nextBoolean()) value() else null

    private fun hostile(random: JdkRandom, maxPieces: Int): String {
        val out = StringBuilder()
        repeat(random.nextInt(maxPieces + 1)) {
            out.append(HOSTILE_PIECES[random.nextInt(HOSTILE_PIECES.size)])
        }
        return out.toString()
    }
}
