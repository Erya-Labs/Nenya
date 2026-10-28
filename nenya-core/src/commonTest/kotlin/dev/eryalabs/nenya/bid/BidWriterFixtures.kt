package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.tag.Coordinate
import dev.eryalabs.nenya.tag.ImageRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * The generator behind the bid writer's corpus, and the one function that turns a decoded [Bid] back
 * into the [AuthoredBid] that must rebuild it.
 *
 * `ListingWriterFixtures`' design, one package over. Not a scratch file: the queue's Definition of done
 * forbids an encoded value typed out by hand, so the pubkeys are SHA-256 digests of a per-index label
 * ([TagFixtures.pubkeyFor]), every coordinate is assembled by [Coordinate] from one of them, and every
 * event id is computed by T8. A reviewer can change [SEED], re-run the suite, and every property must
 * hold.
 *
 * ### Why it is a fourth generator rather than `BidFixtures`
 *
 * `BidFixtures.bids` generates **tag arrays**, because the thing under test there is a decoder: its
 * corpus shuffles the tags, spells `price` in all eight of §4.4's read-side units and writes the same
 * pubkey in two cases — shapes a writer may not emit. This one generates the other side of the
 * boundary, the values a client hands [BidWriter].
 *
 * ### What the corpus carries and why
 *
 * Everything that could make the round trip's bytes differ: every optional row present and absent,
 * both relay hints present and absent independently, §8.1's three `fee` cases, both `image` arities,
 * mixed-case `t` tokens (lowercased on write), `d` values containing colons inside the coordinate
 * (§4.2's three-field split), §4.1's escaping cases inside values, extension tags §4.3 must preserve,
 * and bids against all three listing kinds.
 */
internal object BidWriterFixtures {

    /** Pinned so a failure is reproducible on the JVM and on JavaScript alike ([JdkRandom]). */
    const val SEED: Long = 20261028L

    /** T9's fixed, non-round `created_at`. */
    const val CREATED_AT: Long = TagFixtures.CREATED_AT

    /** The one `t` token [BidWriter] emits itself, before anything the author added. */
    const val FIXED_TOPICS: Int = 1

    /** The listing kinds a bid may scope to, in the order the corpus cycles them. */
    val LISTING_KINDS: List<Int> = listOf(NenyaKind.REQUEST, NenyaKind.OFFER, NenyaKind.OFFER_DRAFT)

    /**
     * The rows the writer emits only when asked: [BidWriter.TAG_ORDER] minus the six scope tags and
     * the three term tags §6 requires, so a row added to the writer joins the coverage floor unedited.
     */
    val OPTIONAL_ROWS: List<String> = BidWriter.TAG_ORDER - Bid.requiredTags().toSet()

    /** Tag names neither §5.3 nor §6 uses, so §4.3's "unknown tag" rule applies to them. */
    private val EXTRA_NAMES: List<String> = listOf(
        "client", "subject", "zap", "relays", "r", "emoji", "x-nenya-experiment",
    )

    /** §4.1's escaping cases: the seven shortcut escapes, two `\u00XX` ones, `/`, `0x7f`, non-BMP. */
    private val HOSTILE_PIECES: List<String> = buildList {
        add("")
        for (code in listOf(0x22, 0x5C, 0x08, 0x0C, 0x0A, 0x0D, 0x09, 0x00, 0x1F, 0x2F, 0x7F)) {
            add(code.toChar().toString())
        }
        add("é")
        add(TestText.codePoint(0x1F600))
        add("ordinary")
    }

    /** One generated case. */
    data class Case(val bid: AuthoredBid, val index: Int)

    /** [count] cases from one seeded run, cycling the three listing kinds. */
    fun cases(count: Int): List<Case> {
        val random = JdkRandom(SEED)
        return List(count) { index -> Case(authored(random, index, LISTING_KINDS[index % LISTING_KINDS.size]), index) }
    }

    /** The event [build] produced, or a loud failure naming the refusal. */
    fun built(build: BidBuild, what: String): WireEvent = when (build) {
        is BidBuild.Built -> build.event
        is BidBuild.Refused -> fail("$what was refused as ${build.reason} on `${build.tag}`: ${build.detail}")
    }

    /** The refusal [build] answered, or a loud failure saying it was accepted. */
    fun refused(build: BidBuild, what: String): BidBuild.Refused = when (build) {
        is BidBuild.Refused -> build
        is BidBuild.Built -> fail("$what was accepted; it emitted ${build.event.tags.size} tag(s)")
    }

    /** [event] as a T8 [CheckedEvent] — the only door into [Bid.decode] (§4.1). */
    fun checked(event: WireEvent): CheckedEvent = CheckedEvent.checkEventId(EventId.of(event).toHex(), event)

    /** [event] decoded as a bid. */
    fun decode(event: WireEvent): Bid = Bid.decode(checked(event))

    /**
     * The [AuthoredBid] that must rebuild [decoded], assembled **only** from what the decode reports.
     *
     * Two readings a careless reconstruction gets wrong, both visible as a different event id:
     *
     * - **`topics`** drops the one token the writer emits itself; the caller asserts which first.
     * - **`extraTags`** drops the scope tags `Bid.unknownTags` reports, because §5.3's table does not
     *   name `A`, `a`, `K`, `k` or `P` — so the decode lists them as unknown although the writer spells
     *   them from the coordinate.
     */
    fun reauthored(decoded: Bid): AuthoredBid = AuthoredBid(
        bidderPubkey = decoded.bidderPubkey,
        createdAt = decoded.createdAt,
        listing = decoded.listing,
        price = decoded.price,
        content = decoded.content,
        rootRelayHint = decoded.rootRelayHint,
        parentRelayHint = decoded.parentRelayHint,
        fee = decoded.fee,
        feeRecipient = decoded.feeRecipient,
        deliverBy = decoded.deliverBy,
        expiration = decoded.expiration,
        topics = decoded.topics.drop(FIXED_TOPICS),
        nenyaVersion = assertNotNull(decoded.nenyaVersion, "§6 marks `nenya` MUST on a bid"),
        alt = decoded.alt,
        dValue = decoded.tags.dValue,
        title = decoded.tags.title,
        summary = decoded.tags.summary,
        publishedAt = decoded.tags.publishedAt,
        status = decoded.tags.status,
        mimeType = decoded.tags.mimeType,
        images = decoded.images,
        license = decoded.tags.license,
        extraTags = decoded.unknownTags.filter { it[0] !in BidVocabulary.SINGLE_OCCURRENCE },
    )

    /** The minimal legal bid on a [listingKind] listing — the three term MUSTs and the scope. */
    fun minimal(listingKind: Int = NenyaKind.REQUEST, index: Int = 0): AuthoredBid = AuthoredBid(
        bidderPubkey = TagFixtures.pubkeyFor(index),
        createdAt = CREATED_AT,
        listing = coordinate(listingKind, index + LISTING_AUTHOR_OFFSET, "listing-$index"),
        price = Msat.ofSat(90_000L),
    )

    /** A coordinate on a generated author, assembled by [Coordinate] — never typed. */
    fun coordinate(kind: Int, authorIndex: Int, dValue: String): Coordinate =
        Coordinate(kind, TagFixtures.pubkeyFor(authorIndex), dValue)

    /** Asserts [left] and [right] are the same bytes on the wire. */
    fun assertSameBytes(left: WireEvent, right: WireEvent, what: String) {
        assertEquals(left.tags, right.tags, "$what: the tag arrays differ")
        assertEquals(
            left.canonicalSerialisation(),
            right.canonicalSerialisation(),
            "$what: §4.1's canonical serialisation differs, so the two events have different ids",
        )
    }

    private fun authored(random: JdkRandom, index: Int, listingKind: Int): AuthoredBid {
        val fee = feeTerm(random)
        // §5.3 makes `d` opaque, so a colon inside one is legal and is what §4.2's limited split is for.
        val listingD = "listing-$index${if (random.nextInt(3) == 0) ":$index" else ""}-${hostile(random, 1)}"
        return AuthoredBid(
            bidderPubkey = TagFixtures.pubkeyFor(index),
            createdAt = CREATED_AT + random.nextInt(99_999),
            listing = coordinate(listingKind, index + LISTING_AUTHOR_OFFSET, listingD),
            price = Msat.ofSat(random.nextInt(1_000_000).toLong()),
            content = hostile(random, 4),
            rootRelayHint = draw(random) { RELAY_HINT },
            parentRelayHint = draw(random) { RELAY_HINT },
            fee = fee,
            feeRecipient = if (fee.namesRecipient) TagFixtures.pubkeyFor(index + FEE_RECIPIENT_OFFSET) else null,
            deliverBy = draw(random) { CREATED_AT + random.nextInt(99_999) },
            expiration = draw(random) { CREATED_AT + random.nextInt(99_999) },
            topics = List(random.nextInt(3)) { topic(random, index, it) },
            alt = draw(random) { hostile(random, 2) },
            dValue = draw(random) { "bid-$index-${hostile(random, 1)}" },
            title = draw(random) { hostile(random, 2) },
            summary = draw(random) { hostile(random, 2) },
            publishedAt = draw(random) { CREATED_AT - random.nextInt(99_999) },
            status = draw(random) { hostile(random, 1) },
            mimeType = draw(random) { "video/mp4" },
            images = List(random.nextInt(3)) { image(random, index, it) },
            license = draw(random) { "cc-by-4.0" },
            extraTags = List(random.nextInt(4)) { extraTag(random, index, it) },
        )
    }

    /** §8.1's three cases, the stated zero drawn deliberately. */
    private fun feeTerm(random: JdkRandom): FeeTerm = when (random.nextInt(3)) {
        0 -> FeeTerm.Absent
        1 -> FeeTerm.of(0)
        else -> FeeTerm.of(1 + random.nextInt(FeeTerm.MAX_BASIS_POINTS))
    }

    /** A `t` token, sometimes with the uppercase §5.3 requires be lowered on write. */
    private fun topic(random: JdkRandom, index: Int, position: Int): String =
        if (random.nextBoolean()) "category-$index-$position" else "Category-$index-$position"

    /** Both `image` arities. */
    private fun image(random: JdkRandom, index: Int, position: Int): ImageRef {
        val url = "https://example.invalid/$index-$position.png"
        return if (random.nextBoolean()) ImageRef(url, "${64 + position}x${64 + position}") else ImageRef(url)
    }

    /** A tag neither §5.3 nor §6 names, of one to three elements. */
    private fun extraTag(random: JdkRandom, index: Int, position: Int): List<String> = buildList {
        add(EXTRA_NAMES[random.nextInt(EXTRA_NAMES.size)])
        repeat(random.nextInt(3)) { add("$index-$position-${hostile(random, 2)}") }
    }

    private fun <T> draw(random: JdkRandom, value: () -> T): T? = if (random.nextBoolean()) value() else null

    private fun hostile(random: JdkRandom, maxPieces: Int): String {
        val out = StringBuilder()
        repeat(random.nextInt(maxPieces + 1)) { out.append(HOSTILE_PIECES[random.nextInt(HOSTILE_PIECES.size)]) }
        return out.toString()
    }

    /** §6's example relay hint, on the reserved-for-documentation domain. */
    private const val RELAY_HINT: String = "wss://relay.example.invalid"

    /** Keeps a generated listing author distinct from the bidder at the same index. */
    private const val LISTING_AUTHOR_OFFSET: Int = 1

    /** Keeps a generated fee recipient distinct from both parties. */
    private const val FEE_RECIPIENT_OFFSET: Int = 7
}
